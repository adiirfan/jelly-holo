/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Split `length` bytes into at most `parts` contiguous ranges of at least
 * `minPart` bytes each (one range if the file is too small to split).
 */
fun splitRanges(length: Long, parts: Int, minPart: Long): List<LongRange> {
    val n = (length / minPart.coerceAtLeast(1)).coerceIn(1, parts.toLong().coerceAtLeast(1)).toInt()
    val base = length / n
    val extra = length % n
    var start = 0L
    return List(n) { i ->
        val size = base + if (i < extra) 1 else 0
        (start until start + size).also { start += size }
    }
}

/**
 * Fetches one file, in several parts at once when the server allows it.
 * Plain Java networking, so it runs (and is tested) off Android too.
 *
 * Every request carries `headers` (cookies, user agent, referer). A part
 * whose connection drops picks up where it stopped. A server that stops
 * honouring part requests gets the whole file fetched again in one stream.
 */
class ParallelDownload(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val parts: Int = 4,
    private val minPartBytes: Long = 1L shl 20,
    private val timeoutMs: Int = 20_000,
    private val retries: Int = 3,
    private val retryPauseMs: Long = 500,
    private val log: (String) -> Unit = {},
) {
    private val t0 = System.nanoTime()
    private fun ms() = (System.nanoTime() - t0) / 1_000_000
    /** What the server said about the file. `length` is -1 when it didn't say. */
    class Info(
        val length: Long,
        val ranges: Boolean,
        val contentType: String?,
        val contentDisposition: String?,
        val finalUrl: String,
    )

    class Cancelled : IOException("cancelled")
    private class RangesIgnored : IOException("server stopped honouring part requests")

    /** How many parts the last download used. */
    @Volatile
    var partsUsed = 0
        private set

    /** Bytes written so far. */
    val received = AtomicLong()

    @Volatile
    private var abort = false

    private fun open(target: String, from: Long?, to: Long?): HttpURLConnection {
        val c = URL(target).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        // Compressed answers have no fixed byte positions
        c.setRequestProperty("Accept-Encoding", "identity")
        if (from != null) c.setRequestProperty("Range", "bytes=$from-${to ?: ""}")
        return c
    }

    /** The first request, kept open: its body is the start of the file. */
    private var first: HttpURLConnection? = null

    @Synchronized
    private fun takeFirst(): HttpURLConnection? = first.also { first = null }

    /**
     * Start fetching the whole file. The answer says whether it can be split
     * (a part answer with a total size); the connection stays open and
     * becomes the first part, so finding out costs no extra round trip.
     */
    fun probe(): Info {
        val c = open(url, 0, null)
        try {
            val code = c.responseCode
            log("first answer: $code at ${ms()} ms")
            if (code !in 200..299) throw IOException("the server answered $code")
            val ranged = code == 206 && c.getHeaderField("Content-Range")?.startsWith("bytes 0-") == true
            val total = if (ranged) {
                c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
            } else {
                c.contentLengthLong
            }
            val info = Info(
                length = total,
                ranges = ranged && total > 0,
                contentType = c.contentType,
                contentDisposition = c.getHeaderField("Content-Disposition"),
                finalUrl = c.url.toString(),
            )
            synchronized(this) { first = c }
            return info
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
    }

    /** Let go of the first request if [download] never ran. */
    fun close() {
        takeFirst()?.disconnect()
    }

    /**
     * Write the file into `out`. Returns false if `cancelled` said stop;
     * throws IOException when it can't finish.
     */
    fun download(
        info: Info,
        out: FileChannel,
        progress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Boolean = try {
        abort = false
        received.set(0)
        var done = false
        if (info.ranges && info.length >= 2 * minPartBytes) {
            val ranges = splitRanges(info.length, parts, minPartBytes)
            partsUsed = ranges.size
            done = try {
                runParts(info, ranges, out, progress, cancelled)
                true
            } catch (e: RangesIgnored) {
                false
            }
        }
        if (!done) {
            partsUsed = 1
            received.set(0)
            out.truncate(0)
            single(info, out, progress, cancelled)
        }
        if (info.length >= 0 && out.size() != info.length) {
            throw IOException("got ${out.size()} of ${info.length} bytes")
        }
        true
    } catch (e: Cancelled) {
        false
    } finally {
        close()
    }

    private fun checkStop(cancelled: () -> Boolean) {
        if (abort || cancelled()) throw Cancelled()
    }

    private fun runParts(
        info: Info,
        ranges: List<LongRange>,
        out: FileChannel,
        progress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val pool = Executors.newFixedThreadPool(ranges.size)
        try {
            val jobs = ranges.map { r -> pool.submit { part(info, r, out, progress, cancelled) } }
            var failure: Throwable? = null
            for (job in jobs) {
                try {
                    job.get()
                } catch (e: ExecutionException) {
                    if (failure == null) {
                        failure = e.cause
                        abort = true  // the other parts stop at their next buffer
                    }
                }
            }
            abort = false
            failure?.let { throw it }
        } finally {
            pool.shutdownNow()
            close()
        }
    }

    private fun part(
        info: Info,
        range: LongRange,
        out: FileChannel,
        progress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        var pos = range.first
        var tries = 0
        log("part ${range.first}: start at ${ms()} ms")
        while (pos <= range.last) {
            checkStop(cancelled)
            try {
                val c = (if (pos == 0L) takeFirst() else null) ?: open(info.finalUrl, pos, range.last)
                try {
                    val code = c.responseCode
                    log("part ${range.first}: answer $code at ${ms()} ms")
                    if (code == 200) throw RangesIgnored()
                    if (code != 206) throw IOException("the server answered $code")
                    // A part must start exactly where it asked, or the file is scrambled
                    if (c.getHeaderField("Content-Range")?.startsWith("bytes $pos-") != true) {
                        throw RangesIgnored()
                    }
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (pos <= range.last) {
                            checkStop(cancelled)
                            val read = input.read(buf)
                            if (read < 0) break
                            val n = minOf(read.toLong(), range.last - pos + 1).toInt()
                            writeAt(out, buf, n, pos)
                            pos += n
                            progress(received.addAndGet(n.toLong()))
                        }
                    }
                } finally {
                    c.disconnect()
                }
                if (pos <= range.last) throw IOException("the connection ended early")
                log("part ${range.first}: done at ${ms()} ms")
            } catch (e: RangesIgnored) {
                log("part ${range.first}: ranges ignored at ${ms()} ms")
                throw e
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                log("part ${range.first}: retry after $e at ${ms()} ms")
                if (++tries > retries) throw e
                Thread.sleep(retryPauseMs * tries)
            }
        }
    }

    private fun single(
        info: Info,
        out: FileChannel,
        progress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        var pos = 0L
        var tries = 0
        while (true) {
            checkStop(cancelled)
            try {
                // Resume where it stopped if the server allows it, else start over
                val resume = pos > 0 && info.ranges
                if (pos > 0 && !resume) {
                    pos = 0
                    out.truncate(0)
                    received.set(0)
                }
                val c = (if (pos == 0L) takeFirst() else null)
                    ?: open(info.finalUrl, if (resume) pos else null, null)
                try {
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("the server answered $code")
                    if (resume && code != 206) {
                        pos = 0
                        out.truncate(0)
                        received.set(0)
                    }
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            checkStop(cancelled)
                            val n = input.read(buf)
                            if (n < 0) break
                            writeAt(out, buf, n, pos)
                            pos += n
                            progress(received.addAndGet(n.toLong()))
                        }
                    }
                } finally {
                    c.disconnect()
                }
                if (info.length >= 0 && pos < info.length) throw IOException("the connection ended early")
                return
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                if (++tries > retries) throw e
                Thread.sleep(retryPauseMs * tries)
            }
        }
    }

    private fun writeAt(out: FileChannel, buf: ByteArray, n: Int, at: Long) {
        val b = ByteBuffer.wrap(buf, 0, n)
        var p = at
        while (b.hasRemaining()) p += out.write(b, p)
    }
}
