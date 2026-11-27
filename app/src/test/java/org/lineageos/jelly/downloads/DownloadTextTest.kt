/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState

class DownloadTextTest {
    private fun item(state: DownloadState, kind: DownloadKind = DownloadKind.TORRENT) =
        DownloadItem(kind = kind, name = "x", state = state, created = 0, updated = 0)

    @Test
    fun sizes_and_speeds_read_like_the_mockup() {
        assertEquals("2.1 GB", DownloadText.size(2_100_000_000))
        assertEquals("742 kB", DownloadText.size(742_000))
        assertEquals("96 kB", DownloadText.size(96_000))
        assertEquals("4.2 MB/s", DownloadText.speed(4_200_000))
    }

    @Test
    fun a_running_torrent_shows_progress_time_speeds_and_people() {
        val i = item(DownloadState.RUNNING).copy(
            doneBytes = 2_100_000_000, totalBytes = 5_000_000_000,
            downBps = 4_200_000, upBps = 610_000, peers = 23,
        )
        // 2.9 GB left at 4.2 MB/s is 11.5 minutes, shown rounded up
        assertEquals("2.1 of 5.0 GB · 12 min left · ↓ 4.2 MB/s · ↑ 610 kB/s · 23 people", DownloadText.meta(i, 0))
    }

    @Test
    fun waiting_paused_and_converting_say_so_first() {
        assertTrue(DownloadText.meta(item(DownloadState.WAITING_VPN), 0).startsWith("Waiting for VPN"))
        assertTrue(DownloadText.meta(item(DownloadState.PAUSED), 0).startsWith("Paused"))
        assertEquals(
            "Converting… 62%",
            DownloadText.meta(item(DownloadState.CONVERTING, DownloadKind.YTDLP).copy(percent = 62), 0),
        )
    }

    @Test
    fun sharing_shows_how_much_was_shared_back() {
        val i = item(DownloadState.SHARING).copy(doneBytes = 1_000, totalBytes = 1_000, uploadedBytes = 800)
        assertTrue(DownloadText.meta(i, 0), DownloadText.meta(i, 0).contains("shared 0.8×"))
    }

    @Test
    fun error_state_shows_the_engine_message() {
        val i = item(DownloadState.FAILED).copy(error = "No space left on device")
        assertEquals("Failed: No space left on device", DownloadText.meta(i, 0))
    }
}
