/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMathTest {
    @Test
    fun times_read_like_a_player() {
        assertEquals("0:00", formatTime(0.0))
        assertEquals("0:07", formatTime(7.9))
        assertEquals("1:05", formatTime(65.0))
        assertEquals("59:59", formatTime(3599.0))
        assertEquals("1:02:03", formatTime(3723.0))
        assertEquals("--:--", formatTime(-1.0))
    }

    @Test
    fun double_taps_skip_by_side() {
        assertEquals(Side.BACK, sideOf(10f, 900f))
        assertEquals(Side.MIDDLE, sideOf(450f, 900f))
        assertEquals(Side.FORWARD, sideOf(890f, 900f))
    }

    @Test
    fun a_screen_width_of_swipe_scrubs_ninety_seconds() {
        assertEquals(190.0, scrubTo(start = 100.0, dx = 1000f, width = 1000f, duration = 600.0), 0.01)
        assertEquals(55.0, scrubTo(start = 100.0, dx = -500f, width = 1000f, duration = 600.0), 0.01)
    }

    @Test
    fun scrubbing_stays_inside_the_video() {
        assertEquals(600.0, scrubTo(start = 590.0, dx = 1000f, width = 1000f, duration = 600.0), 0.01)
        assertEquals(0.0, scrubTo(start = 5.0, dx = -1000f, width = 1000f, duration = 600.0), 0.01)
    }

    @Test
    fun a_full_height_swipe_up_goes_from_none_to_all() {
        assertEquals(1f, levelAfterSwipe(start = 0f, dy = -1000f, height = 1000f), 0.001f)
        assertEquals(0.25f, levelAfterSwipe(start = 0.5f, dy = 250f, height = 1000f), 0.001f)
        assertEquals(0f, levelAfterSwipe(start = 0.1f, dy = 500f, height = 1000f), 0.001f)
    }
}
