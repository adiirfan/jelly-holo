/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpJobTest {
    private val video = Format(
        "299", "https://example.invalid/299", "https", "mp4", "avc1.64002A", null,
        1920, 1080, 60.0, 3247.8, 257619653L, null, mapOf("User-Agent" to "UA", "Accept" to "*/*"),
    )
    private val audio = Format(
        "140", "https://example.invalid/140", "https", "m4a", null, "mp4a.40.2",
        null, null, null, 129.5, 10271496L, 2, emptyMap(),
    )

    @Test
    fun job_survives_the_trip_to_the_download_service() {
        val job = YtDlpJob(
            "https://www.youtube.com/watch?v=x", "Big Buck Bunny",
            Option("1080p 60fps", video, audio, Output.MP4, 267891149L), private = true, duration = 635.0,
        )
        val back = YtDlpJob.fromJson(job.toJson())
        assertEquals(job.page, back.page)
        assertEquals(job.title, back.title)
        assertTrue(back.private)
        assertEquals(635.0, back.duration!!, 0.0)
        assertEquals("1080p 60fps", back.option.label)
        assertEquals(Output.MP4, back.option.output)
        assertEquals(267891149L, back.option.size)
        val v = back.option.video!!
        assertEquals("299", v.id)
        assertEquals("avc1.64002A", v.vcodec)
        assertNull(v.acodec)
        assertEquals(1080, v.height)
        assertEquals(mapOf("User-Agent" to "UA", "Accept" to "*/*"), v.headers)
        val a = back.option.audio!!
        assertNull(a.vcodec)
        assertEquals("mp4a.40.2", a.acodec)
        assertEquals(2, a.channels)
    }

    @Test
    fun job_without_sound_or_length() {
        val job = YtDlpJob("https://x.invalid/", "t", Option("720p", video, null, Output.MP4, null), false, null)
        val back = YtDlpJob.fromJson(job.toJson())
        assertNull(back.option.audio)
        assertNull(back.option.size)
        assertNull(back.duration)
    }

    @Test
    fun file_names_are_safe() {
        assertEquals("a b  c.mp4", fileName("a/b: c?", Output.MP4, "mp4"))
        assertEquals("x".repeat(80) + ".mp4", fileName("x".repeat(200), Output.MP4, "mp4"))
        assertEquals("Cat 🐈 video.mp4", fileName("Cat 🐈 video", Output.CONVERT, "webm"))
        assertEquals("video.mp4", fileName("  ", Output.MP4, "mp4"))
        assertEquals("tab here.webm", fileName("tab\there", Output.WEBM, "webm"))
        assertEquals("song.m4a", fileName("song", Output.AUDIO, "m4a"))
        assertEquals("song.webm", fileName("song", Output.AUDIO, "webm"))
        assertEquals("song.m4a", fileName("song", Output.AUDIO, "mp4"))
        // An emoji across the 80-character cut is dropped whole, never halved
        val name = fileName("x".repeat(79) + "🐈tail", Output.MP4, "mp4")
        assertEquals("x".repeat(79) + ".mp4", name)
    }
}
