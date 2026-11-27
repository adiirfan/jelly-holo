/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.lineageos.jelly.download.HlsPlaylist
import kotlin.math.roundToInt

/** What the download turns into. */
enum class Output { MP4, WEBM, CONVERT, AUDIO }

/** One row of the quality list. [size] is in bytes, estimated when yt-dlp doesn't know it. */
class Option(
    val label: String,
    val video: Format?,
    val audio: Format?,
    val output: Output,
    val size: Long?,
)

const val SOUND_ONLY = "Sound only"

private fun isMp4(f: Format) = f.protocol.startsWith("m3u8") || f.ext in setOf("mp4", "m4a", "m4v", "mov")
private fun isWebm(f: Format) = f.ext == "webm"
/** 10- or 12-bit pictures (HDR): the conversion copies 8-bit frames only. */
internal fun deepColour(codec: String?): Boolean {
    val c = codec?.lowercase() ?: return false
    val p = c.split('.')
    return when {
        c.startsWith("av01") -> p.getOrNull(3)?.let { it == "10" || it == "12" } == true
        c.startsWith("vp09") -> p.getOrNull(1) in setOf("02", "03") || p.getOrNull(3)?.let { it == "10" || it == "12" } == true
        c.startsWith("hvc1") || c.startsWith("hev1") -> p.getOrNull(1) == "2"
        else -> false
    }
}

private fun mimeOf(codec: String?) = codec?.takeIf { it.isNotEmpty() }?.let { HlsPlaylist.mimeOf(it) }

/**
 * The quality list for [info], best first: one row per picture size, then
 * "Sound only". Versions the phone plays well in MP4 come first; WebM only
 * when there's no MP4; conversion only when nothing plays well.
 */
fun options(
    info: YtDlpInfo,
    /** Whether the phone's H.264 encoder takes a picture of this size, for conversion. */
    encodable: (Int, Int) -> Boolean = { _, _ -> true },
    playable: (String) -> Boolean,
): List<Option> {
    val usable = info.formats.filter { it.url.isNotEmpty() && it.protocol != "mhtml" && (it.hasPicture || it.hasSound) }
    val pictures = usable.filter { it.hasPicture }
    val sounds = usable.filter { !it.hasPicture && it.hasSound }

    fun soundPick(container: (Format) -> Boolean, codecOk: (String) -> Boolean): Format? = sounds
        .filter { f -> container(f) && mimeOf(f.acodec)?.let { codecOk(it) && playable(it) } == true }
        .sortedWith(
            compareBy<Format> { (it.channels ?: 2) > 2 }      // stereo suits a phone
                .thenBy { "drc" in it.id }                     // YouTube's squashed-volume copies
                .thenByDescending { it.tbr ?: 0.0 }
        )
        .firstOrNull()

    val mp4Sound = soundPick(::isMp4) { it == "audio/mp4a-latm" }
    val webmSound = soundPick(::isWebm) { it == "audio/opus" || it == "audio/vorbis" }
    val anySound = soundPick({ true }) { true }

    fun size(parts: List<Format>): Long? = parts.map { f ->
        f.size ?: f.tbr?.let { t -> info.duration?.let { d -> (t * 1000 / 8 * d).toLong() } } ?: return null
    }.sum()

    fun label(f: Format): String {
        val side = f.shortSide ?: return "Video"
        val fps = f.fps?.takeIf { it > 30.5 }?.let { " ${it.roundToInt()}fps" } ?: ""
        return "${side}p$fps"
    }

    fun rows(candidates: List<Format>, sound: Format?, output: Output): List<Option> = candidates
        .groupBy { it.shortSide ?: -1 }
        .toSortedMap(compareByDescending { it })
        .values
        .map { group ->
            group.sortedWith(
                compareByDescending<Format> { it.hasSound || sound != null }
                    .thenByDescending { it.fps ?: 0.0 }
                    .thenByDescending { it.protocol == "https" || it.protocol == "http" }
                    .thenByDescending { !it.hasSound }
                    .thenByDescending { it.tbr ?: 0.0 }
            ).first()
        }
        .map { v ->
            val a = if (v.hasSound) null else sound
            Option(label(v), v, a, output, size(listOfNotNull(v, a)))
        }

    var list = rows(
        pictures.filter { isMp4(it) && mimeOf(it.vcodec)?.let(playable) == true },
        mp4Sound ?: anySound,
        Output.MP4,
    )
    if (list.isEmpty()) {
        list = rows(pictures.filter { isMp4(it) && it.vcodec == "" }, mp4Sound ?: anySound, Output.MP4)
    }
    if (list.isEmpty()) {
        list = rows(pictures.filter { isWebm(it) && mimeOf(it.vcodec)?.let(playable) == true }, webmSound, Output.WEBM)
    }
    if (list.isEmpty()) {
        list = rows(pictures.filter { isWebm(it) && it.vcodec == "" }, webmSound, Output.WEBM)
    }
    if (list.isEmpty()) {
        // The biggest size the encoder takes, so nothing needs shrinking
        val convertible = pictures.filter { f ->
            mimeOf(f.vcodec) != null && !deepColour(f.vcodec) &&
                (f.width == null || f.height == null || encodable(f.width, f.height))
        }
        list = rows(convertible, mp4Sound ?: anySound, Output.CONVERT).take(1)
    }
    val soundOnly = (mp4Sound ?: anySound)?.let { Option(SOUND_ONLY, null, it, Output.AUDIO, size(listOf(it))) }
    return list + listOfNotNull(soundOnly)
}
