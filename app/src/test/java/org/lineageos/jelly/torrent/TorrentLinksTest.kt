/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentLinksTest {
    private val ubuntu = "magnet:?xt=urn:btih:62a4d9e139f3315f8716bcccca0cc984a9809da1" +
        "&dn=ubuntu-24.04.4-live-server-amd64.iso&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"

    @Test
    fun magnet_links_are_recognised_in_any_case() {
        assertTrue(isMagnet(ubuntu))
        assertTrue(isMagnet("MAGNET:?xt=urn:btih:62a4d9e139f3315f8716bcccca0cc984a9809da1"))
        assertFalse(isMagnet("https://x/magnet"))
    }

    @Test
    fun torrent_files_are_recognised_by_type_address_or_name() {
        assertTrue(isTorrentDownload("https://x/get?id=1", "application/x-bittorrent", null))
        assertTrue(isTorrentDownload("https://x/file.torrent?x=1", "application/octet-stream", null))
        assertTrue(isTorrentDownload("https://x/get?id=1", "application/octet-stream", "attachment; filename=\"a.torrent\""))
        assertTrue(isTorrentDownload("https://x/get", null, "attachment; filename=b.TORRENT"))
        assertFalse(isTorrentDownload("https://x/file.pdf", "application/pdf", "attachment; filename=\"a.pdf\""))
        assertFalse(isTorrentDownload(null, null, null))
    }

    @Test
    fun a_magnets_name_comes_from_dn() {
        assertEquals("ubuntu-24.04.4-live-server-amd64.iso", magnetName(ubuntu))
        assertEquals("My Show S01", magnetName("magnet:?xt=urn:btih:aa&dn=My+Show%20S01"))
    }

    @Test
    fun a_magnet_without_dn_has_no_name() {
        assertNull(magnetName("magnet:?xt=urn:btih:62a4d9e139f3315f8716bcccca0cc984a9809da1"))
    }

    @Test
    fun a_magnet_needs_a_tap_or_a_redirect_right_after_one() {
        assertTrue(allowMagnet(hasGesture = true, isRedirect = false, msSinceTap = Long.MAX_VALUE))
        assertTrue(allowMagnet(hasGesture = false, isRedirect = true, msSinceTap = 2_000))
        assertFalse(allowMagnet(hasGesture = false, isRedirect = true, msSinceTap = 60_000))
        assertFalse(allowMagnet(hasGesture = false, isRedirect = false, msSinceTap = 0))
    }
}
