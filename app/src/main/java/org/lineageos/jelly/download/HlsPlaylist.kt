/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import java.net.URI
import java.net.URL

/**
 * An HLS playlist (`.m3u8`): either a master list of quality choices, or a
 * media list of the pieces that make up one track.
 */
sealed class HlsPlaylist {
    class Variant(val uri: String, val bandwidth: Long, val audioGroup: String?, val codecs: List<String> = emptyList())

    class Rendition(val uri: String?, val group: String, val default: Boolean)

    /** What to download: the video list, and a separate sound list if there is one. */
    class Choice(val video: String, val audio: String?)

    class Master(val variants: List<Variant>, val audio: List<Rendition>) : HlsPlaylist() {
        /**
         * The highest quality whose formats all pass [playable] (given each
         * format's type, as in [mimeOf]), with its sound (the default one of
         * its group). If none pass, the highest quality of all.
         */
        fun best(playable: (String) -> Boolean = { true }): Choice? {
            val fits = variants.filter { v -> v.codecs.mapNotNull { mimeOf(it) }.all(playable) }
            val v = fits.maxByOrNull { it.bandwidth } ?: variants.maxByOrNull { it.bandwidth } ?: return null
            val group = audio.filter { it.group == v.audioGroup }
            val sound = (group.firstOrNull { it.default } ?: group.firstOrNull())?.uri
            return Choice(v.uri, sound)
        }
    }

    /** One piece; [range] is a byte range within the address, or null for all of it. */
    class Segment(val uri: String, val duration: Double, val range: LongRange?)

    class Media(
        val segments: List<Segment>,
        val init: Segment?,
        val ended: Boolean,
        /** The encryption method when the pieces are encrypted, else null. */
        val protection: String?,
    ) : HlsPlaylist() {
        val duration get() = segments.sumOf { it.duration }
    }

    companion object {
        /** Reads [text] fetched from [base]; null if it isn't a playlist. */
        fun parse(text: String, base: String): HlsPlaylist? {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return null
            return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) master(lines, base) else media(lines, base)
        }

        private fun master(lines: List<String>, base: String): Master {
            val variants = mutableListOf<Variant>()
            val audio = mutableListOf<Rendition>()
            var pending: Map<String, String>? = null
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-MEDIA:") -> {
                        val a = attributes(line.substringAfter(':'))
                        if (a["TYPE"] == "AUDIO") {
                            audio += Rendition(
                                a["URI"]?.let { resolve(base, it) },
                                a["GROUP-ID"] ?: "",
                                a["DEFAULT"] == "YES",
                            )
                        }
                    }

                    line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))

                    !line.startsWith("#") && pending != null -> {
                        variants += Variant(
                            resolve(base, line),
                            pending["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                            pending["AUDIO"],
                            pending["CODECS"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                        )
                        pending = null
                    }
                }
            }
            return Master(variants, audio)
        }

        private fun media(lines: List<String>, base: String): Media {
            val segments = mutableListOf<Segment>()
            var init: Segment? = null
            var ended = false
            var protection: String? = null
            var duration = 0.0
            var range: Pair<Long, Long?>? = null
            // Where the last ranged piece of each address ended
            val ends = mutableMapOf<String, Long>()

            fun place(uri: String, r: Pair<Long, Long?>?): LongRange? {
                r ?: return null
                val start = r.second ?: ends[uri] ?: 0L
                ends[uri] = start + r.first
                return start until start + r.first
            }

            for (line in lines) {
                when {
                    line.startsWith("#EXTINF:") ->
                        duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                    line.startsWith("#EXT-X-BYTERANGE:") -> range = byteRange(line.substringAfter(':'))

                    line.startsWith("#EXT-X-MAP:") -> {
                        val a = attributes(line.substringAfter(':'))
                        a["URI"]?.let { u ->
                            val uri = resolve(base, u)
                            init = Segment(uri, 0.0, place(uri, a["BYTERANGE"]?.let { byteRange(it) }))
                        }
                    }

                    line.startsWith("#EXT-X-KEY:") -> {
                        val method = attributes(line.substringAfter(':'))["METHOD"] ?: "NONE"
                        protection = method.takeIf { it != "NONE" }
                    }

                    line.startsWith("#EXT-X-ENDLIST") -> ended = true

                    !line.startsWith("#") -> {
                        val uri = resolve(base, line)
                        segments += Segment(uri, duration, place(uri, range))
                        duration = 0.0
                        range = null
                    }
                }
            }
            return Media(segments, init, ended, protection)
        }

        /** "length[@offset]" */
        private fun byteRange(s: String): Pair<Long, Long?>? {
            val length = s.substringBefore('@').trim().toLongOrNull() ?: return null
            val offset = if ('@' in s) s.substringAfter('@').trim().toLongOrNull() else null
            return length to offset
        }

        /** KEY=value,KEY="quoted, value",... */
        private fun attributes(s: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var i = 0
            while (i < s.length) {
                val eq = s.indexOf('=', i)
                if (eq < 0) break
                val key = s.substring(i, eq).trim()
                var j = eq + 1
                val value: String
                if (j < s.length && s[j] == '"') {
                    val close = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                    value = s.substring(j + 1, close)
                    j = close + 1
                } else {
                    val comma = s.indexOf(',', j).let { if (it < 0) s.length else it }
                    value = s.substring(j, comma).trim()
                    j = comma
                }
                out[key] = value
                i = s.indexOf(',', j).let { if (it < 0) s.length else it + 1 }
            }
            return out
        }

        /** The media type a playlist's format name (RFC 6381) stands for, or null if unknown. */
        fun mimeOf(codec: String): String? {
            val c = codec.lowercase()
            return when {
                c.startsWith("avc1") || c.startsWith("avc3") -> "video/avc"
                c.startsWith("hvc1") || c.startsWith("hev1") -> "video/hevc"
                c.startsWith("dvh1") || c.startsWith("dvhe") || c.startsWith("dav1") -> "video/dolby-vision"
                c.startsWith("vp09") || c == "vp9" -> "video/x-vnd.on2.vp9"
                c.startsWith("vp08") || c == "vp8" -> "video/x-vnd.on2.vp8"
                c.startsWith("av01") -> "video/av01"
                c.startsWith("mp4a") -> "audio/mp4a-latm"
                c == "ac-3" -> "audio/ac3"
                c == "ec-3" -> "audio/eac3"
                c == "opus" -> "audio/opus"
                c == "flac" -> "audio/flac"
                else -> null
            }
        }

        fun resolve(base: String, ref: String): String =
            runCatching { URI(base).resolve(ref).toString() }
                .getOrElse { URL(URL(base), ref).toString() }
    }
}
