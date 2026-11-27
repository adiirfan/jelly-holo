/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscodeMathTest {
    @Test
    fun bitrate_follows_picture_size() {
        assertEquals(7_464_960, bitrateFor(1920, 1080, 30.0))
        assertEquals(1_000_000, bitrateFor(320, 240, 30.0))
        assertEquals(20_000_000, bitrateFor(3840, 2160, 60.0))
        // Odd frame rates are kept within 24..60
        assertEquals(bitrateFor(1280, 720, 24.0), bitrateFor(1280, 720, 10.0))
        assertEquals(bitrateFor(1280, 720, 60.0), bitrateFor(1280, 720, 120.0))
    }

    @Test
    fun fit_keeps_shape_and_even_sides() {
        assertEquals(1920 to 1080, fitSize(3840, 2160, 1920, 1088))
        assertEquals(1278 to 718, fitSize(1279, 719, 4096, 4096))
        assertEquals(1080 to 1920, fitSize(2160, 3840, 1920, 1920))
        assertEquals(640 to 360, fitSize(640, 360, 1920, 1080))
    }
}
