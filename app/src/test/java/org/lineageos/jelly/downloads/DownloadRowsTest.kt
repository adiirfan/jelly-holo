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

class DownloadRowsTest {
    @Test
    fun torrent_rows_match_by_info_hash() {
        val old = DownloadItem(
            id = 7, kind = DownloadKind.TORRENT, name = "old", state = DownloadState.RUNNING,
            infoHash = "abc", created = 100, updated = 100,
        )
        val kept = upsertTorrent(old, "abc", "new name", 500)
        assertEquals(7L, kept.id)
        assertEquals(100L, kept.created)
        assertEquals("new name", kept.name)
        assertEquals(500L, kept.updated)

        val fresh = upsertTorrent(null, "def", "n", 500)
        assertEquals(0L, fresh.id)
        assertEquals(DownloadState.QUEUED, fresh.state)
        assertEquals("def", fresh.infoHash)
        assertEquals(DownloadKind.TORRENT, fresh.kind)
    }
}
