/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
    @Test
    fun stops_when_the_ratio_is_reached() {
        assertTrue(shouldStopSharing(uploaded = 1000, total = 1000, ratio = 1.0f, keepSharing = true))
    }

    @Test
    fun keeps_sharing_just_below_the_ratio() {
        assertFalse(shouldStopSharing(uploaded = 990, total = 1000, ratio = 1.0f, keepSharing = true))
    }

    @Test
    fun stops_at_once_when_sharing_is_off() {
        assertTrue(shouldStopSharing(uploaded = 0, total = 1000, ratio = 1.0f, keepSharing = false))
    }

    @Test
    fun an_unknown_size_never_stops_by_ratio() {
        assertFalse(shouldStopSharing(uploaded = 5000, total = 0, ratio = 1.0f, keepSharing = true))
    }
}
