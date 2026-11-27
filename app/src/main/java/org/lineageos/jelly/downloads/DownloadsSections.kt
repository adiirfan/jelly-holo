/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState

/** One line of the Downloads page: a section title or a download. */
sealed class Row {
    data class Header(val active: Boolean) : Row()
    data class Entry(val item: DownloadItem) : Row()
}

/** Running downloads under "Active" first, then the rest under "Finished". */
fun sections(items: List<DownloadItem>): List<Row> {
    // A torrent that is done but still sharing is finished as far as the user is concerned
    val (active, finished) = items.partition { DownloadText.isActive(it.state) && it.state != DownloadState.SHARING }
    return buildList {
        if (active.isNotEmpty()) {
            add(Row.Header(true))
            active.forEach { add(Row.Entry(it)) }
        }
        if (finished.isNotEmpty()) {
            add(Row.Header(false))
            finished.forEach { add(Row.Entry(it)) }
        }
    }
}

/**
 * Ordinary downloads whose job is gone (the browser was killed mid-download):
 * marked failed, so they don't sit in Active forever. A row younger than
 * [GRACE_MS] may belong to a job that is still starting up.
 */
fun interruptedRows(rows: List<DownloadItem>, live: Set<Long>, now: Long): List<DownloadItem> = rows
    .filter {
        it.kind != DownloadKind.TORRENT && it.dmId < 0 && DownloadText.isActive(it.state) &&
            it.id !in live && now - it.updated > GRACE_MS
    }
    .map { it.copy(state = DownloadState.FAILED, error = "Interrupted", downBps = 0, updated = now) }

private const val GRACE_MS = 5_000L
