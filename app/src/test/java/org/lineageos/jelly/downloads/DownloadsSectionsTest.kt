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
}
