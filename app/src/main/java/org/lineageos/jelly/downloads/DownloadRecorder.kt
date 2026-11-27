/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.lineageos.jelly.dao.DownloadDao
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes what a running download is doing into the Downloads page's record.
 * Progress is written at most every [PROGRESS_MS], like the notification.
 */
class DownloadRecorder(private val dao: DownloadDao, private val now: () -> Long = System::currentTimeMillis) {
    private val lastWrite = ConcurrentHashMap<Long, Long>()

    suspend fun started(kind: DownloadKind, name: String, source: String?, mime: String?): Long {
        val t = now()
        return dao.insert(
            DownloadItem(kind = kind, name = name, state = DownloadState.RUNNING, source = source, mime = mime, created = t, updated = t)
        )
    }

    /** A download through Android's own download manager, followed by its [dmId]. */
    suspend fun dmStarted(dmId: Long, name: String, source: String?, mime: String?): Long {
        val t = now()
        return dao.insert(
            DownloadItem(
                kind = DownloadKind.FILE, name = name, state = DownloadState.RUNNING, source = source, mime = mime,
                dmId = dmId, created = t, updated = t,
            )
        )
    }

    suspend fun progress(id: Long, done: Long, total: Long, bps: Long) {
        val t = now()
        val last = lastWrite[id]
        if (last != null && t - last < PROGRESS_MS) return
        lastWrite[id] = t
        change(id) { it.copy(state = DownloadState.RUNNING, doneBytes = done, totalBytes = total, downBps = bps) }
    }

    suspend fun converting(id: Long, percent: Int) {
        val t = now()
        val last = lastWrite[id]
        if (last != null && t - last < PROGRESS_MS) return
        lastWrite[id] = t
        change(id) { it.copy(state = DownloadState.CONVERTING, percent = percent, downBps = 0) }
    }

    suspend fun finished(id: Long, uri: String?, size: Long) {
        lastWrite.remove(id)
        change(id) {
            it.copy(state = DownloadState.DONE, uri = uri, doneBytes = size, totalBytes = size, percent = 100, downBps = 0)
        }
    }

    suspend fun failed(id: Long, error: String?) {
        lastWrite.remove(id)
        change(id) { it.copy(state = DownloadState.FAILED, error = error, downBps = 0) }
    }

    suspend fun cancelled(id: Long) {
        lastWrite.remove(id)
        change(id) { it.copy(state = DownloadState.CANCELLED, downBps = 0) }
    }

    private suspend fun change(id: Long, f: (DownloadItem) -> DownloadItem) {
        val row = dao.byId(id) ?: return
        dao.update(f(row).copy(updated = now()))
    }

    companion object {
        const val PROGRESS_MS = 700L
    }
}
