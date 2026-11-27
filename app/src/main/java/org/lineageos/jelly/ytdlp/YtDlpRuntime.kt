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
    private val versionFile = File(root, "yt-dlp.version")

    /**
     * yt-dlp unpacked and compiled once. Run from its packed file, Python
     * compiles all of it again on every lookup, which costs seconds.
     */
    private val appDir = File(root, "app")
    private val appVersionFile = File(root, "app.version")

    /** The version of the yt-dlp in use, as recorded when it was put there. */
    fun installedVersion(): String? = versionFile.takeIf { it.exists() }?.readText()?.trim()

    /** The version of the yt-dlp file that shipped with the app. */
    val shippedVersion: String
        get() = runCatching { app.assets.open("ytdlp/version").use { it.readBytes().decodeToString().trim() } }
            .getOrDefault("")

    /** True when Python still has to be unpacked (the first run is slower). */
    val needsUnpacking
        get() = marker.takeIf { it.exists() }?.readText() != stamp() ||
            !File(appDir, "__main__.py").exists() ||
            appVersionFile.takeIf { it.exists() }?.readText() != installedVersion()

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
            compile(File(prefix, "lib/python3.14"))
            marker.writeText(stamp())
            Log.i(AdBlock.TAG, "yt-dlp: unpacked Python in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        // An app update may ship a newer yt-dlp than the one updated to
        if (!ytdlpFile.exists() || isNewer(shippedVersion, installedVersion() ?: "")) copyShipped()
        if (!File(appDir, "__main__.py").exists() ||
            appVersionFile.takeIf { it.exists() }?.readText() != installedVersion()
        ) {
            unpackApp()
        }
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
        versionFile.writeText(shippedVersion)
    }

    private fun python(args: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(listOf(File(libs, "libytpython.so").path) + args)
        pb.environment().putAll(environment(home, cache, prefix, certs))
        home.mkdirs()
        pb.directory(home)
        return pb
    }

    /** Compiles Python code under [dir] once, so later runs only load it. */
    private fun compile(dir: File) {
        val t = System.nanoTime()
        val r = runWithLimit(python(listOf("-m", "compileall", "-q", "-j", "0", dir.path)), 180_000, { false })
        Log.i(AdBlock.TAG, "yt-dlp: compiled ${dir.name} in ${(System.nanoTime() - t) / 1_000_000} ms (exit ${r.code})")
    }

    /** Unpacks the yt-dlp in use into a folder and compiles it. */
    private fun unpackApp() {
        val tmp = File(root, "app.tmp")
        tmp.deleteRecursively()
        // Python reads the file: it starts with a "#!" line Java's zip reader trips over
        val r = runWithLimit(
            python(listOf("-c", "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])", ytdlpFile.path, tmp.path)),
            60_000, { false },
        )
        if (r.code != 0 || !File(tmp, "__main__.py").exists()) {
            Log.w(AdBlock.TAG, "yt-dlp: couldn't unpack (${r.code}): ${r.err.takeLast(300)}")
            tmp.deleteRecursively()
            return
        }
        compile(tmp)
        appDir.deleteRecursively()
        tmp.renameTo(appDir)
        appVersionFile.writeText(installedVersion() ?: "")
    }

    /** Looks for a newer yt-dlp (at most daily unless [force]); call off the main thread. */
    fun update(force: Boolean): UpdateResult {
        if (!prepare()) return UpdateResult(Outcome.FAILED)
        val r = YtDlpUpdate.forApp(app).check(installedVersion() ?: shippedVersion, ytdlpFile, force)
        if (r.outcome == Outcome.UPDATED) {
            r.version?.let { versionFile.writeText(it) }
            unpackApp()
        }
        Log.i(AdBlock.TAG, "yt-dlp update check: ${r.outcome} ${r.version ?: ""}")
        return r
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
        val script = if (File(appDir, "__main__.py").exists()) appDir else ytdlpFile
        return python(listOf(script.path, "--js-runtimes", "quickjs:$qjs") + args)
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
