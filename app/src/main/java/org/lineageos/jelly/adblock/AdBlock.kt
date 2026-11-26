/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.adblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Ad and tracker blocking. The matching runs in Rust (libjellyblock, built
 * on adblock-rust); this side loads the block lists and asks per request.
 */
object AdBlock {
    const val TAG = "JellyHolo"

    private const val ASSET = "adblock.dat"
    private const val SAVED = "adblock.dat"
    private val LISTS = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
    )
    private const val TIMEOUT_MS = 20_000

    @Volatile
    private var started = false

    init {
        System.loadLibrary("jellyblock")
    }

    /** True when `adb shell setprop log.tag.JellyHolo DEBUG` is set. */
    val logging: Boolean
        get() = Log.isLoggable(TAG, Log.DEBUG)

    /** Load the lists in the background: the saved update first, else the bundled copy. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "AdBlockLoad") {
            val t = SystemClock.elapsedRealtime()
            val saved = File(app.filesDir, SAVED)
            val from = when {
                saved.isFile && nativeLoad(saved.readBytes()) -> "saved lists"
                nativeLoad(app.assets.open(ASSET).use { it.readBytes() }) -> "bundled lists"
                else -> null
            }
            val ms = SystemClock.elapsedRealtime() - t
            if (from == null) {
                Log.w(TAG, "could not load any block list")
            } else {
                Log.i(TAG, "loaded $from in $ms ms")
            }
        }
    }

    fun shouldBlock(url: String, pageUrl: String, accept: String): Boolean =
        nativeShouldBlock(url, pageUrl, accept)

    /** Download fresh lists, compile and save them, then use them. */
    fun update(context: Context, done: (ok: Boolean, message: String) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread(name = "AdBlockUpdate") {
            val result = runCatching {
                val texts = LISTS.map { download(it) }.toTypedArray()
                val bytes = nativeCompile(texts)
                check(bytes.isNotEmpty()) { "the lists could not be read" }
                val tmp = File(app.filesDir, "$SAVED.tmp")
                tmp.writeBytes(bytes)
                check(tmp.renameTo(File(app.filesDir, SAVED))) { "could not save the lists" }
                check(nativeLoad(bytes)) { "the new lists could not be loaded" }
            }
            main.post {
                result.fold(
                    { done(true, "") },
                    {
                        Log.w(TAG, "list update failed", it)
                        done(false, it.message ?: it.javaClass.simpleName)
                    },
                )
            }
        }
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            check(conn.responseCode == 200) { "download failed (${conn.responseCode})" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    @JvmStatic
    private external fun nativeLoad(bytes: ByteArray): Boolean

    @JvmStatic
    private external fun nativeCompile(lists: Array<String>): ByteArray

    @JvmStatic
    private external fun nativeShouldBlock(url: String, pageUrl: String, accept: String): Boolean
}
