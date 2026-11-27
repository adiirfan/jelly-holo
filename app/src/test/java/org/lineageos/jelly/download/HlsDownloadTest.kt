/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

class HlsDownloadTest {
    /** Serves fixed bodies by path; can fail a path a set number of times. */
    private class Server {
        val files = ConcurrentHashMap<String, ByteArray>()
        val failTimes = ConcurrentHashMap<String, AtomicInteger>()
        val requests: MutableList<Pair<String, Map<String, String>>> = Collections.synchronizedList(mutableListOf())
        private val socket = ServerSocket(0)
        val base get() = "http://127.0.0.1:${socket.localPort}"

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
            val path = input.readLine()?.split(" ")?.getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            requests.add(path to headers)
            val out = s.getOutputStream()
            val body = files[path.substringBefore('?')]
            val failing = failTimes[path]?.let { it.getAndDecrement() > 0 } == true
            if (body == null || failing) {
                out.write("HTTP/1.1 ${if (failing) 500 else 404} No\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val range = headers["range"]?.removePrefix("bytes=")?.split("-")
            val (start, end) = if (range != null) range[0].toInt() to range[1].toInt() else 0 to body.size - 1
            val status = if (range != null) "206 Partial Content" else "200 OK"
            out.write("HTTP/1.1 $status\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body, start, end - start + 1)
            out.flush()
        }

        fun close() = socket.close()
    }

    private val server = Server()
    private val dir = File(System.getProperty("java.io.tmpdir"), "hlstest-" + System.nanoTime()).apply { mkdirs() }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun text(path: String, body: String) {
        server.files[path] = body.toByteArray()
    }

    private fun pieces(prefix: String, n: Int, seconds: Int = 6): List<ByteArray> =
        (0 until n).map { i ->
            Random(i + prefix.hashCode()).nextBytes(1000 + i * 337).also { server.files["/$prefix$i.ts"] = it }
        }.also {
            text("/$prefix.m3u8", "#EXTM3U\n" + (0 until n).joinToString("") { "#EXTINF:$seconds,\n$prefix$it.ts\n" } + "#EXT-X-ENDLIST\n")
        }

    private fun hls(headers: (String) -> Map<String, String> = { emptyMap() }) =
        HlsDownload(headers, timeoutMs = 3000, retryPauseMs = 10)

    private fun run(d: HlsDownload, plan: HlsDownload.Plan, cancelled: () -> Boolean = { false }): Boolean =
        d.download(plan, File(dir, "video"), plan.audio?.let { File(dir, "audio") }, { _, _ -> }, cancelled)

    @Test
    fun choosesThePlaylistMatchingTheVideosLength() {
        pieces("ad", 2)              // a 12 s advert stream
        pieces("lo", 10)
        pieces("hi", 10)             // 60 s
        text("/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlo.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhi.m3u8\n")
        val plan = hls().choose(listOf("${server.base}/ad.m3u8", "${server.base}/master.m3u8"), 60.2)
        assertEquals("${server.base}/hi0.ts", plan.video.segments.first().uri)
        assertEquals(60.0, plan.duration, 0.01)
    }

    @Test
    fun withoutAKnownLengthTheFirstMasterWins() {
        pieces("ad", 2)
        pieces("hi", 3)
        text("/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhi.m3u8\n")
        val plan = hls().choose(listOf("${server.base}/ad.m3u8", "${server.base}/master.m3u8"), -1.0)
        assertEquals("${server.base}/hi0.ts", plan.video.segments.first().uri)
    }

    @Test
    fun joinsPiecesInOrderWithTheInitPieceFirst() {
        val init = Random(7).nextBytes(500)
        server.files["/init.mp4"] = init
        val segs = (0 until 9).map { Random(it).nextBytes(2000 + it * 911).also { b -> server.files["/s$it.m4s"] = b } }
        text("/v.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n" + segs.indices.joinToString("") { "#EXTINF:2,\ns$it.m4s\n" } + "#EXT-X-ENDLIST\n")
        val d = hls()
        val plan = d.choose(listOf("${server.base}/v.m3u8"), 18.0)
        assertTrue(run(d, plan))
        assertArrayEquals(init + segs.reduce { a, b -> a + b }, File(dir, "video").readBytes())
    }

    @Test
    fun separateSoundGoesToItsOwnFile() {
        val v = pieces("vid", 4)
        val a = pieces("aud", 4)
        text(
            "/master.m3u8",
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",DEFAULT=YES,URI=\"aud.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=900,AUDIO=\"a\"\nvid.m3u8\n",
        )
        val d = hls()
        val plan = d.choose(listOf("${server.base}/master.m3u8"), 24.0)
        assertTrue(run(d, plan))
        assertArrayEquals(v.reduce { x, y -> x + y }, File(dir, "video").readBytes())
        assertArrayEquals(a.reduce { x, y -> x + y }, File(dir, "audio").readBytes())
    }

    @Test
    fun piecesFromRangesOfOneFile() {
        val all = Random(3).nextBytes(5000)
        server.files["/all.ts"] = all
        text("/r.m3u8", "#EXTM3U\n#EXTINF:2,\n#EXT-X-BYTERANGE:1200@0\nall.ts\n#EXTINF:2,\n#EXT-X-BYTERANGE:3800\nall.ts\n#EXT-X-ENDLIST\n")
        val d = hls()
        val plan = d.choose(listOf("${server.base}/r.m3u8"), 4.0)
        assertTrue(run(d, plan))
        assertArrayEquals(all, File(dir, "video").readBytes())
    }

    @Test
    fun aFailedPieceIsTriedAgain() {
        val v = pieces("p", 3)
        server.failTimes["/p1.ts"] = AtomicInteger(2)
        val d = hls()
        val plan = d.choose(listOf("${server.base}/p.m3u8"), 18.0)
        assertTrue(run(d, plan))
        assertArrayEquals(v.reduce { x, y -> x + y }, File(dir, "video").readBytes())
    }

    @Test
    fun eachAddressGetsItsOwnHeaders() {
        pieces("c", 2)
        val d = hls { url -> mapOf("Cookie" to "for=" + url.substringAfterLast('/'), "Referer" to "https://site.example/watch") }
        assertTrue(run(d, d.choose(listOf("${server.base}/c.m3u8"), 12.0)))
        val seg = server.requests.first { it.first == "/c1.ts" }.second
        assertEquals("for=c1.ts", seg["cookie"])
        assertEquals("https://site.example/watch", seg["referer"])
    }

    @Test
    fun liveAndProtectedStreamsAreRefused() {
        text("/live.m3u8", "#EXTM3U\n#EXTINF:4,\na.ts\n")
        text("/locked.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n")
        assertEquals(HlsDownload.Reason.LIVE, refusal { hls().choose(listOf("${server.base}/live.m3u8"), -1.0) })
        assertEquals(HlsDownload.Reason.PROTECTED, refusal { hls().choose(listOf("${server.base}/locked.m3u8"), 4.0) })
    }

    @Test
    fun nothingUsableIsRefused() {
        text("/page.m3u8", "<html></html>")
        assertEquals(
            HlsDownload.Reason.NOT_FOUND,
            refusal { hls().choose(listOf("${server.base}/page.m3u8", "${server.base}/missing.m3u8"), 10.0) },
        )
    }

    @Test
    fun cancellingStopsTheDownload() {
        pieces("k", 20)
        val d = hls()
        val plan = d.choose(listOf("${server.base}/k.m3u8"), 120.0)
        var asked = 0
        assertFalse(run(d, plan) { ++asked > 3 })
        assertTrue(server.requests.count { it.first.endsWith(".ts") } < 20)
    }

    @Test
    fun tsPiecesAreNumberedOnAcrossJoins() {
        fun piece(): ByteArray = (0 until 3).map { cc ->
            ByteArray(188).also { it[0] = 0x47; it[1] = 0x01; it[2] = 0x02; it[3] = (0x10 or cc).toByte() }
        }.reduce { a, b -> a + b }
        server.files["/t0.ts"] = piece()
        server.files["/t1.ts"] = piece()
        text("/t.m3u8", "#EXTM3U\n#EXTINF:2,\nt0.ts\n#EXTINF:2,\nt1.ts\n#EXT-X-ENDLIST\n")
        val d = hls()
        assertTrue(run(d, d.choose(listOf("${server.base}/t.m3u8"), 4.0)))
        val out = File(dir, "video").readBytes()
        assertEquals(listOf(0, 1, 2, 3, 4, 5), (out.indices step 188).map { out[it + 3].toInt() and 0x0F })
    }

    @Test
    fun soundIsNullWhenItIsInTheVideo() {
        pieces("one", 2)
        assertNull(hls().choose(listOf("${server.base}/one.m3u8"), 12.0).audio)
    }

    private fun refusal(block: () -> Unit): HlsDownload.Reason {
        try {
            block()
        } catch (e: HlsDownload.Refused) {
            return e.reason
        }
        fail("not refused")
        throw IllegalStateException()
    }
}
