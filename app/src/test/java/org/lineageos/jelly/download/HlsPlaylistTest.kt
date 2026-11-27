/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {
    private val base = "https://cdn.example.com/v/42/master.m3u8?tok=1"

    @Test
    fun masterPicksTheHighestBandwidthAndItsAudio() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-lo",NAME="en",DEFAULT=YES,URI="audio/lo.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-hi",NAME="de",DEFAULT=NO,URI="audio/hi-de.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-hi",NAME="en",DEFAULT=YES,URI="audio/hi-en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud-lo"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud-hi"
            /abs/1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud-hi"
            https://other.example.net/720.m3u8
        """.trimIndent()
        val p = HlsPlaylist.parse(text, base) as HlsPlaylist.Master
        assertEquals(3, p.variants.size)
        val best = p.best()!!
        assertEquals("https://cdn.example.com/abs/1080/index.m3u8", best.video)
        assertEquals("https://cdn.example.com/v/42/audio/hi-en.m3u8", best.audio)
        assertEquals("https://cdn.example.com/v/42/360/index.m3u8", p.variants.first { it.bandwidth == 800000L }.uri)
    }

    @Test
    fun audioIsNullWhenTheVariantCarriesItsOwnSound() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n"
        val best = (HlsPlaylist.parse(text, base) as HlsPlaylist.Master).best()!!
        assertEquals("https://cdn.example.com/v/42/low.m3u8", best.video)
        assertNull(best.audio)
    }

    @Test
    fun audioGroupWithoutAddressMeansSoundIsInTheVideo() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="main",DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="a"
            v.m3u8
        """.trimIndent()
        assertNull((HlsPlaylist.parse(text, base) as HlsPlaylist.Master).best()!!.audio)
    }

    @Test
    fun mediaPlaylistListsPiecesWithLengths() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.006,
            seg0.ts
            #EXTINF:6.006,
            seg1.ts?x=1
            #EXTINF:3.5,
            https://edge.example.org/seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylist.parse(text, "https://cdn.example.com/v/42/720/index.m3u8") as HlsPlaylist.Media
        assertEquals(
            listOf(
                "https://cdn.example.com/v/42/720/seg0.ts",
                "https://cdn.example.com/v/42/720/seg1.ts?x=1",
                "https://edge.example.org/seg2.ts",
            ),
            p.segments.map { it.uri },
        )
        assertEquals(15.512, p.duration, 0.001)
        assertTrue(p.ended)
        assertNull(p.init)
        assertNull(p.protection)
    }

    @Test
    fun liveStreamHasNoEnd() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts\n"
        assertFalse((HlsPlaylist.parse(text, base) as HlsPlaylist.Media).ended)
    }

    @Test
    fun keysOtherThanNoneMarkThePlaylistProtected() {
        val aes = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
        assertEquals("AES-128", (HlsPlaylist.parse(aes, base) as HlsPlaylist.Media).protection)
        val none = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
        assertNull((HlsPlaylist.parse(none, base) as HlsPlaylist.Media).protection)
    }

    @Test
    fun initPieceAndByteRanges() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@720
            all.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            all.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylist.parse(text, "https://cdn.example.com/v/x.m3u8") as HlsPlaylist.Media
        assertEquals("https://cdn.example.com/v/init.mp4", p.init!!.uri)
        assertEquals(0L until 720L, p.init!!.range)
        assertEquals(720L until 1720L, p.segments[0].range)
        // No offset: carries on from the end of the previous piece of the same file
        assertEquals(1720L until 2220L, p.segments[1].range)
    }

    private val choices = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="av01.0.08M.08,mp4a.40.2"
        av1.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=7000000,CODECS="hvc1.1.6.L120.90,mp4a.40.2"
        hevc.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2"
        avc.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="avc1.4d401e,mp4a.40.2"
        small.m3u8
    """.trimIndent()

    @Test
    fun bestSkipsFormatsThePhoneCantPlay() {
        val p = HlsPlaylist.parse(choices, base) as HlsPlaylist.Master
        val noAv1 = p.best { mime -> mime != "video/av01" }!!
        assertEquals("https://cdn.example.com/v/42/hevc.m3u8", noAv1.video)
        val avcOnly = p.best { mime -> mime == "video/avc" || mime.startsWith("audio/") }!!
        assertEquals("https://cdn.example.com/v/42/avc.m3u8", avcOnly.video)
    }

    @Test
    fun whenNothingFitsTheBestIsStillChosen() {
        val p = HlsPlaylist.parse(choices, base) as HlsPlaylist.Master
        assertEquals("https://cdn.example.com/v/42/av1.m3u8", p.best { false }!!.video)
    }

    @Test
    fun formatNamesBecomeTypes() {
        assertEquals("video/avc", HlsPlaylist.mimeOf("avc1.640028"))
        assertEquals("video/avc", HlsPlaylist.mimeOf("avc3.4d401e"))
        assertEquals("video/hevc", HlsPlaylist.mimeOf("hvc1.1.6.L120.90"))
        assertEquals("video/hevc", HlsPlaylist.mimeOf("hev1.2.4.L153"))
        assertEquals("video/x-vnd.on2.vp9", HlsPlaylist.mimeOf("vp09.00.40.08"))
        assertEquals("video/av01", HlsPlaylist.mimeOf("av01.0.08M.08"))
        assertEquals("video/dolby-vision", HlsPlaylist.mimeOf("dvh1.05.06"))
        assertEquals("audio/mp4a-latm", HlsPlaylist.mimeOf("mp4a.40.2"))
        assertEquals("audio/eac3", HlsPlaylist.mimeOf("ec-3"))
        assertEquals("audio/ac3", HlsPlaylist.mimeOf("ac-3"))
        assertEquals("audio/opus", HlsPlaylist.mimeOf("Opus"))
        assertNull(HlsPlaylist.mimeOf("wvtt"))
    }

    @Test
    fun notAPlaylist() {
        assertNull(HlsPlaylist.parse("<html>nope</html>", base))
    }
}
