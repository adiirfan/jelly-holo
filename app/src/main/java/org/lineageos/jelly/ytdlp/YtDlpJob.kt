/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.json.JSONObject

/** A download the user picked from the quality list, as handed to the download service. */
class YtDlpJob(
    val page: String,
    val title: String,
    val option: Option,
    /** From a private tab: no cookies are passed. */
    val private: Boolean,
    val duration: Double?,
) {
    fun toJson(): String = JSONObject().apply {
        put("page", page)
        put("title", title)
        put("private", private)
        duration?.let { put("duration", it) }
        put("label", option.label)
        put("output", option.output.name)
        option.size?.let { put("size", it) }
        option.video?.let { put("video", it.toJson()) }
        option.audio?.let { put("audio", it.toJson()) }
    }.toString()

    companion object {
        fun fromJson(s: String): YtDlpJob {
            val o = JSONObject(s)
            return YtDlpJob(
                page = o.getString("page"),
                title = o.optString("title"),
                option = Option(
                    label = o.optString("label"),
                    video = o.optJSONObject("video")?.let(Format::fromJson),
                    audio = o.optJSONObject("audio")?.let(Format::fromJson),
                    output = Output.valueOf(o.getString("output")),
                    size = o.num("size")?.toLong(),
                ),
                private = o.optBoolean("private", false),
                duration = o.num("duration"),
            )
        }
    }
}

/**
 * A safe file name for a saved video: characters file systems refuse become
 * spaces, at most 80 characters, and the extension the output will have.
 * For sound only, [ext] is the sound format's own extension.
 */
fun fileName(title: String, output: Output, ext: String?): String {
    val clean = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").trim()
    // At most 80 characters, never cutting an emoji (two UTF-16 units) in half
    var end = minOf(clean.length, 80)
    if (end < clean.length && Character.isHighSurrogate(clean[end - 1])) end--
    val base = clean.substring(0, end).trim().ifEmpty { "video" }
    val suffix = when (output) {
        Output.MP4, Output.CONVERT -> "mp4"
        Output.WEBM -> "webm"
        Output.AUDIO -> if (ext == "webm") "webm" else "m4a"
    }
    return "$base.$suffix"
}
