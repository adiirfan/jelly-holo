/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.jelly.database.DownloadDatabase
import org.lineageos.jelly.model.DownloadState
import org.lineageos.jelly.torrent.Lock

class TorrentStatusTest {
    @Test
    fun vpn_only_and_connected_names_the_port() {
        assertEquals("Only over VPN · connected · port 51413", stripStatus(true, true, Lock.Run(42), 51413))
    }

    @Test
    fun waiting_for_the_vpn_says_they_carry_on() {
        assertEquals("Paused: VPN not connected. They carry on when it's back.", stripStatus(true, true, Lock.WaitVpn, 0))
    }

    @Test
    fun waiting_for_wifi_says_they_carry_on() {
        assertEquals("Paused: no Wi-Fi. They carry on when it's back.", stripStatus(true, false, Lock.WaitWifi, 0))
    }

    @Test
    fun switched_off_is_off() {
        assertEquals("Off", stripStatus(false, true, Lock.Off, 51413))
    }

    @Test
    fun any_connection_without_a_port() {
        assertEquals("Any connection · no port", stripStatus(true, false, Lock.Run(null), 0))
    }

    @Test
    fun the_upgrade_adds_the_file_list() {
        assertEquals("ALTER TABLE downloads ADD COLUMN files TEXT", DownloadDatabase.ADD_FILES_SQL)
    }

    @Test
    fun a_torrent_rows_button_follows_its_state() {
        assertEquals(TorrentButton.PAUSE, torrentButton(DownloadState.RUNNING))
        assertEquals(TorrentButton.PAUSE, torrentButton(DownloadState.QUEUED))
        assertEquals(TorrentButton.PAUSE, torrentButton(DownloadState.WAITING_VPN))
        assertEquals(TorrentButton.RESUME, torrentButton(DownloadState.PAUSED))
        assertEquals(TorrentButton.STOP_SHARING, torrentButton(DownloadState.SHARING))
        assertEquals(TorrentButton.MENU, torrentButton(DownloadState.DONE))
        assertEquals(TorrentButton.MENU, torrentButton(DownloadState.FAILED))
    }
}
