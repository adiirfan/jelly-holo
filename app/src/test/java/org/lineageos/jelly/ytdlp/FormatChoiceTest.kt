/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatChoiceTest {
    private fun load(name: String) =
        YtDlpInfo.parse(javaClass.classLoader!!.getResource("ytdlp/$name")!!.readText())

    /** Like the Xperia: everything but AV1 plays well. */
    private val xperia: (String) -> Boolean = { it != "video/av01" }

    @Test
    fun parse_reads_the_basics() {
        val info = load("youtube.json")
        assertEquals("Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", info.title)
        assertEquals(635.0, info.duration!!, 0.5)
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", info.page)
        assertFalse(info.isLive)
        val f140 = info.formats.first { it.id == "140" }
        assertNull(f140.vcodec)
        assertEquals("mp4a.40.2", f140.acodec)
        assertEquals(10271496L, f140.size)
        assertEquals(2, f140.channels)
        assertTrue(f140.headers.containsKey("User-Agent"))
    }

    @Test
    fun youtube_best_on_the_xperia_is_4k_vp9_with_aac_sound() {
        val first = options(load("youtube.json"), playable = xperia).first()
        assertEquals("2160p 60fps", first.label)
        assertEquals("628", first.video!!.id)
        assertEquals("140", first.audio!!.id)
        assertEquals(Output.MP4, first.output)
    }

    @Test
    fun youtube_prefers_plain_downloads_over_pieces_at_the_same_size() {
        val all = options(load("youtube.json"), playable = xperia)
        assertEquals("299", all.first { it.label == "1080p 60fps" }.video!!.id)
        assertEquals("298", all.first { it.label == "720p 60fps" }.video!!.id)
    }

    @Test
    fun youtube_skips_av1_and_webm_on_the_xperia() {
        options(load("youtube.json"), playable = xperia).forEach { o ->
            o.video?.let {
                assertFalse(o.label, it.vcodec!!.startsWith("av01"))
                assertFalse(o.label, it.ext == "webm")
            }
        }
    }

    @Test
    fun youtube_has_one_row_per_size_and_sound_only_last() {
        val all = options(load("youtube.json"), playable = xperia)
        assertEquals(all.map { it.label }.distinct(), all.map { it.label })
        val last = all.last()
        assertEquals("Sound only", last.label)
        assertNull(last.video)
        assertEquals("140", last.audio!!.id)
        assertEquals(Output.AUDIO, last.output)
    }

    @Test
    fun phone_with_av1_gets_av1_at_4k() {
        val first = options(load("youtube.json")) { true }.first()
        assertEquals("2160p 60fps", first.label)
        assertTrue(first.video!!.vcodec!!.startsWith("av01"))
        assertEquals(Output.MP4, first.output)
    }

    @Test
    fun phone_with_only_h264_tops_out_at_1080p() {
        val first = options(load("youtube.json")) { it == "video/avc" || it.startsWith("audio/") }.first()
        assertEquals("1080p 60fps", first.label)
        assertEquals("299", first.video!!.id)
    }

    @Test
    fun sizes_come_from_the_file_or_an_estimate() {
        val all = options(load("youtube.json"), playable = xperia)
        val p1080 = all.first { it.label == "1080p 60fps" }
        assertEquals(257619653L + 10271496L, p1080.size)
        // The 4K one comes in pieces with no size: estimated from its bitrate
        val p2160 = all.first()
        val expected = ((27987.109 + 0) * 1000 / 8 * 635).toLong() + 10271496L
        assertEquals(expected.toDouble(), p2160.size!!.toDouble(), expected * 0.01)
    }

    @Test
    fun instagram_portrait_reel_is_labelled_by_its_short_side() {
        val all = options(load("instagram.json"), playable = xperia)
        val first = all.first()
        assertEquals("720p", first.label)
        assertEquals("dash-841320813455671v", first.video!!.id)
        assertNull(first.audio) // this reel has no sound
        assertEquals(Output.MP4, first.output)
        assertFalse(all.any { it.output == Output.AUDIO })
    }

    @Test
    fun webm_only_saves_webm() {
        val all = options(load("webm-only.json"), playable = xperia)
        assertEquals(listOf("720p", "360p", "Sound only"), all.map { it.label })
        assertEquals(Output.WEBM, all.first().output)
        assertEquals("opus", all.first().audio!!.acodec)
    }

    @Test
    fun av1_only_converts() {
        val all = options(load("av1-only.json"), playable = xperia)
        assertEquals(listOf("1080p", "Sound only"), all.map { it.label })
        assertEquals(Output.CONVERT, all.first().output)
        assertEquals("v1080", all.first().video!!.id)
        assertNotNull(all.first().audio)
    }

    @Test
    fun conversion_picks_the_biggest_size_the_encoder_takes() {
        val all = options(load("av1-only.json"), encodable = { w, _ -> w <= 1280 }, playable = xperia)
        assertEquals("480p", all.first().label)
        assertEquals(Output.CONVERT, all.first().output)
    }

    @Test
    fun unknown_size_without_a_bitrate_stays_unknown() {
        val info = YtDlpInfo.parse(
            """{"title":"t","duration":10,"webpage_url":"https://example.invalid/","is_live":true,
               "formats":[{"format_id":"v","url":"https://example.invalid/v","protocol":"https","ext":"mp4",
               "vcodec":"avc1.4D401F","acodec":"mp4a.40.2","height":480,"width":854}]}"""
        )
        assertTrue(info.isLive)
        val o = options(info, playable = xperia).single()
        assertNull(o.size)
        assertEquals("480p", o.label)
    }
}
