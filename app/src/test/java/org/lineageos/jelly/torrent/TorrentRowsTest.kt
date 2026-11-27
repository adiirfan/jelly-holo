/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState

class TorrentRowsTest {
    private val row = DownloadItem(
        id = 7, kind = DownloadKind.TORRENT, name = "Torrent", state = DownloadState.QUEUED,
        infoHash = "abc", created = 1, updated = 1,
    )

    private fun stat(state: String = "live", finished: Boolean = false, error: String? = null) = TorrentStat(
        id = 3, infoHash = "abc", name = "ubuntu.iso", state = state, done = 400, total = 1000,
        uploaded = 50, downBps = 2000, upBps = 300, peers = 12, finished = finished, error = error,
        files = listOf(TorrentFile("ubuntu.iso", 1000, 400, true)),
    )

    @Test
    fun parses_the_engines_list() {
        val json = """[{"id":3,"info_hash":"abc","name":"ubuntu.iso","state":"live","done":400,"total":1000,
            "uploaded":50,"down_bps":2000,"up_bps":300,"peers":12,"finished":false,"error":null,
            "files":[{"name":"ubuntu.iso","size":1000,"done":400,"selected":true}]}]"""
        assertEquals(listOf(stat()), parseStats(json))
    }

    @Test
    fun an_engine_error_parses_as_no_torrents() {
        assertEquals(emptyList<TorrentStat>(), parseStats("""{"error":"torrents are off"}"""))
    }

    @Test
    fun downloading_shows_progress_speeds_and_people() {
        val r = applyStat(row, stat(), now = 5)
        assertEquals(DownloadState.RUNNING, r.state)
        assertEquals("ubuntu.iso", r.name)
        assertEquals(3, r.engineId)
        assertEquals(listOf(400L, 1000L, 2000L, 300L, 50L), listOf(r.doneBytes, r.totalBytes, r.downBps, r.upBps, r.uploadedBytes))
        assertEquals(12, r.peers)
        assertEquals(5L, r.updated)
    }

    @Test
    fun finished_and_running_is_sharing() {
        assertEquals(DownloadState.SHARING, applyStat(row, stat(finished = true), 5).state)
    }

    @Test
    fun finished_and_paused_is_done() {
        val r = applyStat(row, stat(state = "paused", finished = true), 5)
        assertEquals(DownloadState.DONE, r.state)
        assertEquals(0L, r.upBps)
    }

    @Test
    fun paused_before_finishing_is_paused() {
        assertEquals(DownloadState.PAUSED, applyStat(row, stat(state = "paused"), 5).state)
    }

    @Test
    fun starting_up_is_queued() {
        assertEquals(DownloadState.QUEUED, applyStat(row, stat(state = "initializing"), 5).state)
    }

    @Test
    fun error_state_carries_the_engines_message() {
        val r = applyStat(row, stat(state = "error", error = "No space left on device"), 5)
        assertEquals(DownloadState.FAILED, r.state)
        assertEquals("No space left on device", r.error)
    }

    @Test
    fun while_waiting_running_rows_wait_and_settled_rows_stay() {
        val running = row.copy(state = DownloadState.RUNNING, downBps = 9, upBps = 9, peers = 4)
        val w = waitingRow(running, Lock.WaitVpn, 5)
        assertEquals(DownloadState.WAITING_VPN, w.state)
        assertEquals(listOf(0L, 0L), listOf(w.downBps, w.upBps))
        assertEquals(0, w.peers)
        assertEquals(DownloadState.WAITING_WIFI, waitingRow(running, Lock.WaitWifi, 5).state)
        assertEquals(DownloadState.PAUSED, waitingRow(running, Lock.Off, 5).state)
        for (settled in listOf(DownloadState.PAUSED, DownloadState.DONE, DownloadState.FAILED)) {
            assertEquals(settled, waitingRow(row.copy(state = settled), Lock.WaitVpn, 5).state)
        }
        assertEquals(DownloadState.WAITING_VPN, waitingRow(row.copy(state = DownloadState.SHARING), Lock.WaitVpn, 5).state)
    }
}
