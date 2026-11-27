/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.adblock

import android.content.Context
import android.net.Uri
import org.lineageos.jelly.utils.SharedPreferencesExt
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import android.net.ConnectivityManager
import android.net.Network
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * Ad and tracker blocking. The matching runs in Rust (libjellyblock, built
 * on adblock-rust); this side loads the block lists and asks per request.
 */
object AdBlock {
    const val TAG = "JellyHolo"

    private const val SAVED = "adblock.dat"
    private const val TEXTS = "blocklists"
    private const val PREFS = "adblock"
    private const val KEY_LISTS = "lists"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_LIST_BYTES = 30 shl 20

    @Volatile
    private var started = false

    /** All list work, one thing at a time: downloads, adding, removing, compiling. */
    private val work = Executors.newSingleThreadExecutor { Thread(it, "AdBlockLists") }
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever the lists change (the Block lists page listens). */
    @Volatile
    var onListsChanged: (() -> Unit)? = null

    @Volatile
    private var waitingForNetwork = false

    init {
        System.loadLibrary("jellyblock")
    }

    /** True when `adb shell setprop log.tag.JellyHolo DEBUG` is set. */
    val logging: Boolean
        get() = Log.isLoggable(TAG, Log.DEBUG)

    /** Load the saved lists, then fetch any that are due (on a new install: EasyList and EasyPrivacy). */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        work.execute {
            ElementRules.load(app)
            val t = SystemClock.elapsedRealtime()
            val saved = File(app.filesDir, SAVED)
            val loaded = saved.isFile && nativeLoad(saved.readBytes())
            if (loaded) {
                Log.i(TAG, "loaded saved lists in ${SystemClock.elapsedRealtime() - t} ms")
            } else {
                Log.i(TAG, "no block lists yet")
            }
            refresh(app, BlockLists.due(lists(app), now()))
        }
    }

    /** The user's lists. Safe from any thread. */
    fun lists(context: Context): List<BlockList> = synchronized(this) {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_LISTS, null)
        BlockLists.firstRun(stored).also { if (stored == null) save(context, it) }
    }

    /** Downloads every list that is on, now. */
    fun updateNow(context: Context) {
        val app = context.applicationContext
        work.execute { refresh(app, lists(app).filter { it.on && it.url != null }, force = true) }
    }

    fun addAddress(context: Context, url: String, name: String) {
        val app = context.applicationContext
        work.execute {
            val list = BlockList(BlockLists.newId(), name, url.trim())
            change(app) { it + list }
            refresh(app, listOf(list), force = true)
        }
    }

    /** Copies a list file in. [done] gets null, or why it couldn't be added. */
    fun addFile(context: Context, uri: Uri, fallbackName: String, done: (String?) -> Unit) {
        val app = context.applicationContext
        work.execute {
            val problem = runCatching {
                val text = app.contentResolver.openInputStream(uri)?.use { readCapped(it) }
                    ?: error("the file couldn't be opened")
                check(BlockLists.looksLikeList(text)) { "that file isn't a block list" }
                val list = BlockList(
                    BlockLists.newId(), BlockLists.titleOf(text) ?: fallbackName, null, updated = now(),
                )
                textFile(app, list.id).writeText(text)
                change(app) { it + list }
                rebuild(app)
            }.exceptionOrNull()?.let { reason(it) }
            main.post { done(problem) }
        }
    }

    fun setOn(context: Context, id: String, on: Boolean) {
        val app = context.applicationContext
        work.execute {
            change(app) { all -> all.map { if (it.id == id) it.copy(on = on) else it } }
            val list = lists(app).firstOrNull { it.id == id }
            // Switched on but never fetched: fetch it (that rebuilds too)
            if (on && list != null && list.url != null && !textFile(app, id).isFile) {
                refresh(app, listOf(list), force = true)
            } else {
                rebuild(app)
            }
        }
    }

    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        work.execute {
            change(app) { all -> all.filterNot { it.id == id } }
            textFile(app, id).delete()
            rebuild(app)
        }
    }

    /** Downloads [due], records how each went, and rebuilds the blocker if anything changed. Runs on [work]. */
    private fun refresh(app: Context, due: List<BlockList>, force: Boolean = false) {
        if (due.isEmpty()) {
            // Lists on disk but nothing saved yet (e.g. the save failed): build from what there is
            if (!File(app.filesDir, SAVED).isFile && lists(app).any { it.on && textFile(app, it.id).isFile }) rebuild(app)
            return
        }
        var fetched = false
        var offline = false
        for (list in due) {
            val t = now()
            val result = runCatching { download(list.url!!) }
            change(app) { all ->
                all.map { l ->
                    if (l.id != list.id) return@map l
                    result.fold(
                        { text ->
                            textFile(app, l.id).writeText(text)
                            fetched = true
                            l.copy(
                                name = if (l.updated == 0L) BlockLists.titleOf(text) ?: l.name else l.name,
                                updated = t, expiresMs = BlockLists.expiryOf(text), error = null, triedAt = t,
                            )
                        },
                        { e ->
                            Log.w(TAG, "block list ${l.url} failed", e)
                            if (e is UnknownHostException || e is ConnectException || e is NoRouteToHostException) offline = true
                            l.copy(error = reason(e), triedAt = t)
                        },
                    )
                }
            }
        }
        if (fetched || force) rebuild(app)
        if (offline) retryWhenOnline(app)
    }

    /** Compiles every list that is on into the blocker, saves it and uses it. Runs on [work]. */
    private fun rebuild(app: Context) {
        val texts = lists(app).filter { it.on }.mapNotNull { l ->
            textFile(app, l.id).takeIf { it.isFile }?.readText()
        }
        val bytes = nativeCompile(texts.toTypedArray())
        if (bytes.isEmpty() || !nativeLoad(bytes)) {
            Log.w(TAG, "the block lists could not be built")
            return
        }
        val tmp = File(app.filesDir, "$SAVED.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(app.filesDir, SAVED))) Log.w(TAG, "could not save the block lists")
        Log.i(TAG, "block lists built from ${texts.size} list(s)")
        notifyChanged()
    }

    /** A download failed for want of a connection: try once more when one appears. */
    private fun retryWhenOnline(app: Context) {
        if (waitingForNetwork) return
        waitingForNetwork = true
        val cm = app.getSystemService(ConnectivityManager::class.java)
        // The network it failed on is reported again at once; only a different one counts
        val failedOn = cm.activeNetwork
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == failedOn) return
                runCatching { cm.unregisterNetworkCallback(this) }
                waitingForNetwork = false
                work.execute { refresh(app, BlockLists.due(lists(app), now(), retryFailed = true)) }
            }
        })
    }

    private fun change(app: Context, f: (List<BlockList>) -> List<BlockList>) {
        synchronized(this) { save(app, f(lists(app))) }
        notifyChanged()
    }

    private fun save(context: Context, lists: List<BlockList>) {
        prefs(context).edit().putString(KEY_LISTS, BlockLists.encode(lists)).apply()
    }

    private fun notifyChanged() {
        main.post { onListsChanged?.invoke() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun textFile(app: Context, id: String) = File(File(app.filesDir, TEXTS).apply { mkdirs() }, "$id.txt")

    private fun now() = System.currentTimeMillis()

    fun shouldBlock(url: String, pageUrl: String, accept: String): Boolean =
        nativeShouldBlock(url, pageUrl, accept)

    /**
     * Whether the page at `fromUrl` jumping to `url` (same tab or a new one)
     * should be stopped: blocking is on, the page's site isn't allowed, and
     * `url` is a known ad or tracker address.
     */
    fun stopsJump(prefs: SharedPreferencesExt, url: String, fromUrl: String?): Boolean {
        if (fromUrl == null || !prefs.adBlockEnabled) return false
        val fromHost = runCatching { Uri.parse(fromUrl).host }.getOrNull() ?: return false
        if (fromHost in prefs.adBlockAllowedHosts) return false
        return nativeShouldBlockPage(url, fromUrl)
    }

    /** The stylesheet that hides the user's chosen elements on the page at `url`. */
    fun hideCss(url: String): String = nativeHideCss(url) ?: ""

    fun setElementRules(rules: List<String>) = nativeSetElementRules(rules.joinToString("\n"))

    private fun reason(e: Throwable): String = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> "no internet connection"
        is java.net.SocketTimeoutException -> "the list server didn't answer in time"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            check(conn.responseCode == 200) { "the server answered ${conn.responseCode}" }
            val text = conn.inputStream.use { readCapped(it) }
            check(BlockLists.looksLikeList(text)) { "that address gives a web page, not a block list" }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            check(out.size() <= MAX_LIST_BYTES) { "the list is bigger than 30 MB" }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    @JvmStatic
    private external fun nativeLoad(bytes: ByteArray): Boolean

    @JvmStatic
    private external fun nativeCompile(lists: Array<String>): ByteArray

    @JvmStatic
    private external fun nativeShouldBlock(url: String, pageUrl: String, accept: String): Boolean

    @JvmStatic
    private external fun nativeShouldBlockPage(url: String, fromUrl: String): Boolean

    @JvmStatic
    private external fun nativeSetElementRules(rules: String)

    @JvmStatic
    private external fun nativeHideCss(url: String): String?
}
