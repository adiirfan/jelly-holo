/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.jelly.dao.DownloadDao
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState

/** Keeps rows in memory and counts writes. */
class FakeDownloadDao : DownloadDao {
    val rows = linkedMapOf<Long, DownloadItem>()
    var updates = 0
    private var next = 1L
    override fun all(): Flow<List<DownloadItem>> = MutableStateFlow(rows.values.toList())
    override suspend fun insert(item: DownloadItem): Long {
        val id = next++
        rows[id] = item.copy(id = id)
        return id
    }
    override suspend fun update(item: DownloadItem) {
        updates++
        rows[item.id] = item
    }
    override suspend fun byId(id: Long) = rows[id]
    override suspend fun byInfoHash(hash: String) = rows.values.firstOrNull { it.infoHash == hash }
    override suspend fun delete(id: Long) {
        rows.remove(id)
    }
    override suspend fun activeDm() = rows.values.filter { it.dmId >= 0 }
}

class DownloadRecorderTest {
    private var clock = 1_000L
    private val dao = FakeDownloadDao()
    private val rec = DownloadRecorder(dao) { clock }

    @Test
    fun started_adds_a_running_row() = runBlocking {
        val id = rec.started(DownloadKind.FILE, "a.pdf", "https://example.invalid/a.pdf", "application/pdf")
        val r = dao.rows[id]!!
        assertEquals(DownloadState.RUNNING, r.state)
        assertEquals(1_000L, r.created)
        assertEquals(1_000L, r.updated)
        assertEquals("a.pdf", r.name)
    }

    @Test
    fun progress_writes_at_most_every_700_ms() = runBlocking {
        val id = rec.started(DownloadKind.FILE, "a", null, null)
        rec.progress(id, 10, 100, 5)
        clock += 300
        rec.progress(id, 20, 100, 5)
        clock += 300
        rec.progress(id, 30, 100, 5)
        assertEquals(1, dao.updates)
        clock += 200
        rec.progress(id, 40, 100, 5)
        assertEquals(2, dao.updates)
        assertEquals(40L, dao.rows[id]!!.doneBytes)
    }

    @Test
    fun finished_marks_done_with_the_file() = runBlocking {
        val id = rec.started(DownloadKind.YTDLP, "v.mp4", null, "video/mp4")
        rec.finished(id, "content://media/1", 500)
        val r = dao.rows[id]!!
        assertEquals(DownloadState.DONE, r.state)
        assertEquals(100, r.percent)
        assertEquals("content://media/1", r.uri)
        assertEquals(500L, r.totalBytes)
    }

    @Test
    fun failed_keeps_the_bytes_so_far() = runBlocking {
        val id = rec.started(DownloadKind.FILE, "a", null, null)
        rec.progress(id, 40, 100, 5)
        rec.failed(id, "Connection lost")
        val r = dao.rows[id]!!
        assertEquals(DownloadState.FAILED, r.state)
        assertEquals(40L, r.doneBytes)
        assertEquals("Connection lost", r.error)
    }
}
