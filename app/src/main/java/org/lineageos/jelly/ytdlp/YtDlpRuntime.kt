/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import android.content.Context
import android.util.Log
import org.lineageos.jelly.adblock.AdBlock
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** How a run of a program ended. [code] is null when it was stopped. */
class RunResult(
    val code: Int?,
    val out: String,
    val err: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
)

/** The settings Python and yt-dlp need, all pointing at the app's own folders. */
fun environment(home: File, cache: File, prefix: File, certs: File): Map<String, String> = mapOf(
    "PYTHONHOME" to prefix.path,
    "LD_LIBRARY_PATH" to File(prefix, "lib").path,
    "HOME" to home.path,
    "XDG_CACHE_HOME" to cache.path,
    "SSL_CERT_FILE" to certs.path,
    "PYTHONDONTWRITEBYTECODE" to "1",
)

/**
 * Runs [pb] and collects what it prints. The program is killed when it runs
 * past [limitMs], when [cancelled] says so, or, with [stallMs], when
 * [growing] (say, the size of the file it writes) hasn't changed for that long.
 */
fun runWithLimit(
    pb: ProcessBuilder,
    limitMs: Long,
    cancelled: () -> Boolean,
    stallMs: Long? = null,
    growing: (() -> Long)? = null,
): RunResult {
    val p = pb.start()
    p.outputStream.close()
    val out = StringBuilder()
    val err = StringBuilder()
    fun drain(s: InputStream, into: StringBuilder) = thread(isDaemon = true) {
        runCatching {
            s.bufferedReader().use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    synchronized(into) { into.appendRange(buf, 0, n) }
                }
            }
        }
    }
    val readers = listOf(drain(p.inputStream, out), drain(p.errorStream, err))
    val start = System.nanoTime()
    var lastSize = growing?.invoke()
    var lastChange = start
    var timedOut = false
    var stopped = false
    while (true) {
        if (p.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) break
        val now = System.nanoTime()
        if (cancelled()) {
            stopped = true
        } else if ((now - start) / 1_000_000 > limitMs) {
            timedOut = true
        } else if (stallMs != null && growing != null) {
            val size = growing()
            if (size != lastSize) {
                lastSize = size
                lastChange = now
            } else if ((now - lastChange) / 1_000_000 > stallMs) {
                timedOut = true
            }
        }
        if (stopped || timedOut) {
            p.destroyForcibly()
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            break
        }
    }
    readers.forEach { it.join(1000) }
    val code = if (stopped || timedOut) null else p.exitValue()
    return RunResult(
        code,
        synchronized(out) { out.toString() },
        synchronized(err) { err.toString() },
        timedOut,
        stopped,
    )
}

/**
 * Python, yt-dlp and QuickJS as shipped in the app. The two programs come
 * installed as native libraries (Android only lets an app run those); Python's
 * own files are unpacked from the app on first use, and again after an update.
 */
class YtDlpRuntime(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "ytdlp")
    private val prefix = File(root, "prefix")
    private val certs = File(root, "cacert.pem")
    private val marker = File(root, "marker")
    private val home = File(root, "home")
    private val cache = File(app.cacheDir, "ytdlp-cache")
    private val libs = File(app.applicationInfo.nativeLibraryDir)

    /** The yt-dlp in use: the shipped one, or a newer one it updated to. */
    val ytdlpFile = File(root, "yt-dlp")

    /** The version of the yt-dlp file that shipped with the app. */
    val shippedVersion: String
        get() = runCatching { app.assets.open("ytdlp/version").use { it.readBytes().decodeToString().trim() } }
            .getOrDefault("")

    /** True when Python still has to be unpacked (the first run is slower). */
    val needsUnpacking get() = marker.takeIf { it.exists() }?.readText() != stamp()

    private fun stamp(): String {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return "${info.longVersionCode}:${info.lastUpdateTime}"
    }

    /** Unpacks what's missing. Slow the first time; call it off the main thread. */
    @Synchronized
    fun prepare(): Boolean = runCatching {
        if (needsUnpacking) {
            val t = System.nanoTime()
            prefix.deleteRecursively()
            prefix.mkdirs()
            app.assets.open("ytdlp/python.zip").use { unzip(it, prefix) }
            writeCerts()
            if (!ytdlpFile.exists()) copyShipped()
            marker.writeText(stamp())
            Log.i(AdBlock.TAG, "yt-dlp: unpacked Python in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        if (!ytdlpFile.exists()) copyShipped()
        home.mkdirs()
        cache.mkdirs()
        true
    }.getOrElse {
        Log.w(AdBlock.TAG, "yt-dlp: couldn't get ready", it)
        false
    }

    /** Replaces the yt-dlp in use with the one that shipped with the app. */
    fun copyShipped() {
        root.mkdirs()
        val tmp = File(root, "yt-dlp.tmp")
        app.assets.open("ytdlp/yt-dlp").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(ytdlpFile)
    }

    private fun unzip(input: InputStream, into: File) {
        val base = into.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val f = File(into, e.name)
                if (!f.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /** Python can't read Android's certificate folder, so it gets them as one file. */
    private fun writeCerts() {
        val dir = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
            .map(::File).firstOrNull { it.isDirectory } ?: return
        certs.outputStream().bufferedWriter().use { w ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                runCatching { f.readText() }.getOrNull()?.let {
                    w.write(it)
                    if (!it.endsWith("\n")) w.write("\n")
                }
            }
        }
    }

    /** A yt-dlp run with [args]; call [prepare] first. */
    fun command(args: List<String>): ProcessBuilder {
        val qjs = File(libs, "libqjs.so").path
        val pb = ProcessBuilder(
            listOf(File(libs, "libytpython.so").path, ytdlpFile.path, "--js-runtimes", "quickjs:$qjs") + args
        )
        pb.environment().putAll(environment(home, cache, prefix, certs))
        pb.directory(home)
        return pb
    }

    /** The version of the yt-dlp in use, or null if it won't run. */
    fun version(): String? {
        if (!prepare()) return null
        val r = runWithLimit(command(listOf("--version")), 20_000, { false })
        if (r.code != 0) {
            Log.w(AdBlock.TAG, "yt-dlp --version failed (${r.code}): ${r.err.takeLast(500)}")
            return null
        }
        return r.out.trim().takeIf { it.isNotEmpty() }
    }
}
