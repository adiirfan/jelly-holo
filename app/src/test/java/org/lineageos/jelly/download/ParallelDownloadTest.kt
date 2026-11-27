/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

class ParallelDownloadTest {
    /** A tiny HTTP/1.1 file server whose behaviour each test sets. */
    private class Server(val data: ByteArray) {
        var ranges = true
        var sendLength = true
        var dropOnceAfter = -1L          // cut the first response that passes this byte
        var ignoreRangesAfter = Int.MAX_VALUE  // answer later range requests with the whole file
        val requests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
        val active = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        private val dropped = java.util.concurrent.atomic.AtomicBoolean(false)
        private val rangeRequests = AtomicInteger()
        private val socket = ServerSocket(0)
        val url get() = "http://127.0.0.1:${socket.localPort}/file.bin"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
                }
            }
        }

        private fun serve(s: Socket) {
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            requests.add(headers)
            val now = active.incrementAndGet()
            mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                val range = headers["range"]?.removePrefix("bytes=")?.split("-")
                val useRange = ranges && range != null && rangeRequests.incrementAndGet() <= ignoreRangesAfter
                val start = if (useRange) range!![0].toLong() else 0L
                val end = if (useRange && range!![1].isNotEmpty()) range[1].toLong() else data.size - 1L
                val out = s.getOutputStream()
                val head = StringBuilder()
                head.append(if (useRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                if (useRange) head.append("Content-Range: bytes $start-$end/${data.size}\r\n")
                if (ranges) head.append("Accept-Ranges: bytes\r\n")
                if (sendLength) head.append("Content-Length: ${end - start + 1}\r\n")
                head.append("Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                var pos = start
                while (pos <= end) {
                    val n = minOf(16 * 1024L, end - pos + 1).toInt()
                    if (dropOnceAfter in pos until pos + n && dropped.compareAndSet(false, true)) {
                        out.write(data, pos.toInt(), (dropOnceAfter - pos).toInt())
                        out.flush()
                        return
                    }
                    out.write(data, pos.toInt(), n)
                    pos += n
                    if (end - start > 1) Thread.sleep(1)
                }
                out.flush()
            } finally {
                active.decrementAndGet()
            }
        }

        fun close() = socket.close()
    }

    private val data = Random(7).nextBytes(5 * 1024 * 1024 + 123)
    private val server = Server(data)
    private val file = File.createTempFile("pdtest", ".bin")

    @After
    fun tearDown() {
        server.close()
        file.delete()
    }

    private fun fetch(
        headers: Map<String, String> = emptyMap(),
        cancelled: () -> Boolean = { false },
    ): Pair<Boolean, ParallelDownload> {
        val d = ParallelDownload(server.url, headers, retryPauseMs = 10)
        val info = d.probe()
        val ok = RandomAccessFile(file, "rw").use { raf ->
            d.download(info, raf.channel, cancelled = cancelled)
        }
        return ok to d
    }

    @Test
    fun split_ranges_cover_the_file_without_gaps() {
        val parts = splitRanges(10_000_001L, 4, 1L)
        assertEquals(4, parts.size)
        assertEquals(0L, parts.first().first)
        assertEquals(10_000_000L, parts.last().last)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        val sizes = parts.map { it.last - it.first + 1 }
        assertTrue(sizes.max() - sizes.min() <= 1)
    }

    @Test
    fun small_files_are_one_part() {
        assertEquals(1, splitRanges(1_500_000L, 4, 1L shl 20).size)
        assertEquals(3, splitRanges(3L shl 20, 4, 1L shl 20).size)
    }

    @Test
    fun downloads_in_four_parts_at_once() {
        val (ok, d) = fetch()
        assertTrue(ok)
        assertArrayEquals(data, file.readBytes())
        assertEquals(4, d.partsUsed)
        assertTrue("parts ran one after another", server.mostAtOnce.get() >= 2)
    }

    @Test
    fun the_first_request_is_also_the_first_part() {
        val (ok, d) = fetch()
        assertTrue(ok)
        assertEquals(4, d.partsUsed)
        assertEquals("a separate size check costs a round trip", 4, server.requests.size)
    }

    @Test
    fun one_stream_needs_only_one_request() {
        server.ranges = false
        val (ok, _) = fetch()
        assertTrue(ok)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun falls_back_to_one_stream_without_range_support() {
        server.ranges = false
        val (ok, d) = fetch()
        assertTrue(ok)
        assertArrayEquals(data, file.readBytes())
        assertEquals(1, d.partsUsed)
    }

    @Test
    fun resumes_a_part_after_a_dropped_connection() {
        server.dropOnceAfter = 300_000L
        val (ok, _) = fetch()
        assertTrue(ok)
        assertArrayEquals(data, file.readBytes())
        assertTrue(
            "no request resumed from the drop",
            server.requests.any { it["range"] == "bytes=300000-${data.size / 4 - 1}" ||
                    it["range"]?.startsWith("bytes=300000-") == true }
        )
    }

    @Test
    fun server_that_stops_honouring_ranges_still_gives_a_whole_file() {
        server.ignoreRangesAfter = 2  // the probe and one part, then whole files
        val (ok, _) = fetch()
        assertTrue(ok)
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun unknown_length_streams_the_whole_body() {
        server.ranges = false
        server.sendLength = false
        val (ok, d) = fetch()
        assertTrue(ok)
        assertArrayEquals(data, file.readBytes())
        assertEquals(1, d.partsUsed)
    }

    @Test
    fun cancel_stops_the_download() {
        var calls = 0
        val (ok, _) = fetch(cancelled = { ++calls > 20 })
        assertFalse(ok)
    }

    @Test
    fun sends_the_browser_headers() {
        fetch(mapOf("Cookie" to "session=abc", "User-Agent" to "JellyTest/1"))
        assertTrue(server.requests.isNotEmpty())
        server.requests.forEach {
            assertEquals("session=abc", it["cookie"])
            assertEquals("JellyTest/1", it["user-agent"])
        }
    }
}
