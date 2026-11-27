/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState

class DownloadsSectionsTest {
    private fun item(id: Long, state: DownloadState) =
        DownloadItem(id = id, kind = DownloadKind.FILE, name = "f$id", state = state, created = 0, updated = 0)

    @Test
    fun active_first_then_finished_each_under_a_header() {
        val rows = sections(listOf(item(1, DownloadState.DONE), item(2, DownloadState.RUNNING), item(3, DownloadState.WAITING_VPN)))
        assertEquals(
            listOf(Row.Header(true), Row.Entry(item(2, DownloadState.RUNNING)), Row.Entry(item(3, DownloadState.WAITING_VPN)),
                Row.Header(false), Row.Entry(item(1, DownloadState.DONE))),
            rows,
        )
    }

    @Test
    fun nothing_downloaded_gives_no_rows() {
        assertEquals(emptyList<Row>(), sections(emptyList()))
    }

    @Test
    fun a_failed_download_counts_as_finished() {
        val rows = sections(listOf(item(1, DownloadState.FAILED)))
        assertEquals(listOf(Row.Header(false), Row.Entry(item(1, DownloadState.FAILED))), rows)
    }

    @Test
    fun a_finished_torrent_still_sharing_is_listed_as_finished() {
        val sharing = DownloadItem(
            id = 1, kind = DownloadKind.TORRENT, name = "a",
            state = DownloadState.SHARING, created = 1, updated = 1,
        )
        assertEquals(listOf(Row.Header(false), Row.Entry(sharing)), sections(listOf(sharing)))
    }

    @Test
    fun a_download_whose_job_is_gone_is_marked_interrupted() {
        fun row(id: Long, kind: DownloadKind = DownloadKind.FILE, updated: Long = 0, dmId: Long = -1) = DownloadItem(
            id = id, kind = kind, name = "f", state = DownloadState.RUNNING, created = 0, updated = updated, dmId = dmId,
        )
        val orphan = row(1)
        val live = row(2)
        val torrent = row(3, DownloadKind.TORRENT)
        val viaAndroid = row(4, dmId = 9)
        val justStarted = row(5, updated = 59_000)
        val fixed = interruptedRows(listOf(orphan, live, torrent, viaAndroid, justStarted), live = setOf(2L), now = 60_000)
        assertEquals(listOf(orphan.copy(state = DownloadState.FAILED, error = "Interrupted", downBps = 0, updated = 60_000)), fixed)
    }
}
