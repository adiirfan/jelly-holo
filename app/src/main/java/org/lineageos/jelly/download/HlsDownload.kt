/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max

/**
 * Saves a streamed video (HLS): finds the playlist that belongs to the video
 * being watched, then downloads its pieces a few at a time and joins them,
 * in order, into one file per track. [Remux] turns those into an MP4.
 */
class HlsDownload(
    /** Headers for one address: the page's cookies for it, Referer, User-Agent. */
    private val headersFor: (String) -> Map<String, String>,
    /** Whether the phone plays a format (a media type) well; the best such quality is chosen. */
    private val playable: (String) -> Boolean = { true },
    private val threads: Int = 4,
    private val timeoutMs: Int = 20_000,
    private val retries: Int = 3,
    private val retryPauseMs: Long = 500,
    private val log: (String) -> Unit = {},
) {
    enum class Reason { NOT_FOUND, LIVE, PROTECTED }

    class Refused(val reason: Reason) : IOException(reason.name)

    class Cancelled : IOException("cancelled")

    class Plan(val video: HlsPlaylist.Media, val audio: HlsPlaylist.Media?) {
        val duration get() = video.duration
        val pieces get() = count(video) + (audio?.let { count(it) } ?: 0)

        private fun count(m: HlsPlaylist.Media) = m.segments.size + (if (m.init != null) 1 else 0)
    }

    /**
     * Picks from the playlists the page loaded (oldest first) the one whose
     * length matches the video's ([expected] seconds, or <= 0 if unknown).
     * Master playlists come before single-track ones, since the page loads
     * a master's own track lists too, sound included.
     */
    fun choose(candidates: List<String>, expected: Double): Plan {
        val masters = mutableListOf<Plan>()
        val singles = mutableListOf<Plan>()
        val covered = mutableSetOf<String>()
        for (url in candidates.distinct()) {
            if (url in covered) continue
            val list = runCatching { fetchPlaylist(url) }.getOrNull() ?: continue
            when (list) {
                is HlsPlaylist.Master -> {
                    covered += list.variants.map { it.uri }
                    covered += list.audio.mapNotNull { it.uri }
                    val best = list.best(playable) ?: continue
                    val video = runCatching { fetchPlaylist(best.video) }.getOrNull() as? HlsPlaylist.Media ?: continue
                    val audio = best.audio?.let { runCatching { fetchPlaylist(it) }.getOrNull() as? HlsPlaylist.Media }
                    if (best.audio != null && audio == null) continue
                    masters += Plan(video, audio)
                }

                is HlsPlaylist.Media -> if (list.segments.isNotEmpty()) singles += Plan(list, null)
            }
        }
        val all = masters + singles.filter { p -> p.video.segments.first().uri !in covered }
        if (all.isEmpty()) throw Refused(Reason.NOT_FOUND)
        val plan = if (expected > 0) {
            val near = max(3.0, expected * 0.03)
            all.firstOrNull { abs(it.duration - expected) <= near } ?: all.minBy { abs(it.duration - expected) }
        } else {
            all.first()
        }
        log("chose ${plan.video.segments.size} pieces, ${plan.duration} s, sound ${plan.audio != null}")
        if (!plan.video.ended || plan.audio?.ended == false) throw Refused(Reason.LIVE)
        if (plan.video.protection != null || plan.audio?.protection != null) throw Refused(Reason.PROTECTED)
        return plan
    }

    /**
     * Downloads [plan]'s pieces into [videoOut] (and [audioOut] when the
     * sound is separate). False if [cancelled] stopped it.
     */
    fun download(
        plan: Plan,
        videoOut: File,
        audioOut: File?,
        progress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
    ): Boolean {
        val tracks = buildList {
            add(plan.video to videoOut)
            if (plan.audio != null && audioOut != null) add(plan.audio to audioOut)
        }
        val total = plan.pieces
        var done = 0
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for ((media, out) in tracks) {
                val pieces = listOfNotNull(media.init) + media.segments
                // TS pieces (no init piece) are numbered on across the joins
                val join = if (media.init == null) TsJoin() else null
                FileOutputStream(out).use { os ->
                    val waiting = ArrayDeque<Future<ByteArray>>()
                    var next = 0
                    // A few pieces ahead, written strictly in order
                    fun fill() {
                        while (waiting.size < threads * 2 && next < pieces.size) {
                            val p = pieces[next++]
                            waiting.addLast(pool.submit<ByteArray> { fetchPiece(p, cancelled) })
                        }
                    }
                    fill()
                    while (waiting.isNotEmpty()) {
                        if (cancelled()) throw Cancelled()
                        val bytes = try {
                            waiting.removeFirst().get()
                        } catch (e: ExecutionException) {
                            throw e.cause ?: e
                        }
                        join?.fix(bytes, bytes.size)
                        os.write(bytes)
                        progress(++done, total)
                        fill()
                    }
                }
            }
            return true
        } catch (e: Cancelled) {
            return false
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchPlaylist(url: String): HlsPlaylist? {
        val bytes = fetch(url, null, { false }, limit = 8 shl 20)
        return HlsPlaylist.parse(String(bytes, Charsets.UTF_8), url)
    }

    private fun fetchPiece(p: HlsPlaylist.Segment, cancelled: () -> Boolean): ByteArray =
        fetch(p.uri, p.range, cancelled, limit = Int.MAX_VALUE)

    private fun fetch(url: String, range: LongRange?, cancelled: () -> Boolean, limit: Int): ByteArray {
        var last: IOException? = null
        repeat(retries) { attempt ->
            if (cancelled()) throw Cancelled()
            if (attempt > 0) Thread.sleep(retryPauseMs * attempt)
            try {
                return once(url, range, cancelled, limit)
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                log("try ${attempt + 1} of $url failed: ${e.message}")
                last = e
            }
        }
        throw last ?: IOException("couldn't fetch $url")
    }

    private fun once(url: String, range: LongRange?, cancelled: () -> Boolean, limit: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            headersFor(url).forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("Accept-Encoding", "identity")
            range?.let { c.setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            val out = ByteArrayOutputStream(c.contentLength.coerceIn(0, 4 shl 20))
            c.inputStream.use { input ->
                // A server that ignored the range sends the whole file: skip to the piece
                if (range != null && code == 200) input.skipNBytesCompat(range.first)
                val want = range?.let { (it.last - it.first + 1).toInt() } ?: limit
                val buf = ByteArray(64 * 1024)
                while (out.size() < want) {
                    if (cancelled()) throw Cancelled()
                    val n = input.read(buf, 0, minOf(buf.size, want - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (range != null && out.size() < want) throw IOException("piece cut short")
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.skipNBytesCompat(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) {
                if (read() < 0) throw IOException("piece starts past the end")
                left--
            } else {
                left -= s
            }
        }
    }
}
