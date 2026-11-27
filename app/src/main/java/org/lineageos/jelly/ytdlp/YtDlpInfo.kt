/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.json.JSONObject

/**
 * One version of a video as yt-dlp describes it. [vcodec] and [acodec] are
 * null when the format has no picture or no sound, and empty when it has one
 * but yt-dlp doesn't know its codec.
 */
class Format(
    val id: String,
    val url: String,
    val protocol: String,
    val ext: String,
    val vcodec: String?,
    val acodec: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    /** Total bitrate, in kbit/s. */
    val tbr: Double?,
    val size: Long?,
    val channels: Int?,
    val headers: Map<String, String>,
) {
    val hasPicture get() = vcodec != null
    val hasSound get() = acodec != null

    /** The picture's short side, which is how sizes are named ("720p"), even for portrait videos. */
    val shortSide: Int?
        get() = if (width != null && height != null) minOf(width, height) else height

    fun toJson(): JSONObject = JSONObject().apply {
        put("format_id", id)
        put("url", url)
        put("protocol", protocol)
        put("ext", ext)
        put("vcodec", vcodec ?: "none")
        put("acodec", acodec ?: "none")
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        fps?.let { put("fps", it) }
        tbr?.let { put("tbr", it) }
        size?.let { put("filesize", it) }
        channels?.let { put("audio_channels", it) }
        put("http_headers", JSONObject(headers))
    }

    companion object {
        fun fromJson(o: JSONObject): Format {
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("http_headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.getString(k) } }
            return Format(
                id = o.str("format_id") ?: "",
                url = o.str("url") ?: "",
                protocol = o.str("protocol") ?: "https",
                ext = o.str("ext") ?: "",
                vcodec = codec(o, "vcodec", "video_ext"),
                acodec = codec(o, "acodec", "audio_ext"),
                width = o.num("width")?.toInt(),
                height = o.num("height")?.toInt(),
                fps = o.num("fps"),
                tbr = o.num("tbr"),
                size = (o.num("filesize") ?: o.num("filesize_approx"))?.toLong(),
                channels = o.num("audio_channels")?.toInt(),
                headers = headers,
            )
        }

        /** "none" means there's no such track; a missing codec counts as unknown when the file type says the track is there. */
        private fun codec(o: JSONObject, key: String, extKey: String): String? {
            o.str(key)?.let { return if (it == "none") null else it }
            val ext = o.str(extKey) ?: return null
            return if (ext == "none") null else ""
        }
    }
}

/** What yt-dlp found on a page. */
class YtDlpInfo(
    val title: String,
    val duration: Double?,
    val page: String,
    val formats: List<Format>,
    val isLive: Boolean,
) {
    companion object {
        fun parse(json: String): YtDlpInfo {
            var o = JSONObject(json)
            // A page answered as a list: its first video with formats
            o.optJSONArray("entries")?.let { entries ->
                (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
                    .firstOrNull { it.has("formats") || it.has("url") }
                    ?.let { o = it }
            }
            val list = o.optJSONArray("formats")
            val formats = if (list == null) {
                // A page with a single format describes it at the top level
                listOf(Format.fromJson(o)).filter { it.url.isNotEmpty() }
            } else {
                (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(Format::fromJson) }
            }
            return YtDlpInfo(
                title = o.str("title") ?: "",
                duration = o.num("duration"),
                page = o.str("webpage_url") ?: "",
                formats = formats,
                isLive = o.optBoolean("is_live", false),
            )
        }
    }
}

/** A string, or null when missing or JSON null (Android's org.json turns null into "null"). */
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

internal fun JSONObject.num(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
