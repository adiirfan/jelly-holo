/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.lineageos.jelly.model.DownloadItem

/** One line of the Downloads page: a section title or a download. */
sealed class Row {
    data class Header(val active: Boolean) : Row()
    data class Entry(val item: DownloadItem) : Row()
}

/** Running downloads under "Active" first, then the rest under "Finished". */
fun sections(items: List<DownloadItem>): List<Row> {
    val (active, finished) = items.partition { DownloadText.isActive(it.state) }
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
