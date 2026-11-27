/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadKind
import org.lineageos.jelly.model.DownloadState
import java.util.Locale
import kotlin.math.ceil

/** The words under each download's name on the Downloads page. */
object DownloadText {
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> "${bytes / 1_000} kB"
        else -> "$bytes B"
    }

    fun speed(bps: Long): String = size(bps) + "/s"

    fun isActive(state: DownloadState): Boolean =
        state != DownloadState.DONE && state != DownloadState.FAILED && state != DownloadState.CANCELLED

    /** "2.1 of 5.0 GB", or just the size so far when the total isn't known. */
    private fun amount(i: DownloadItem): String = if (i.totalBytes > 0) {
        val total = size(i.totalBytes)
        val unit = total.substringAfter(' ')
        val done = size(i.doneBytes).let { if (it.substringAfter(' ') == unit) it.substringBefore(' ') else it }
        "$done of $total"
    } else {
        size(i.doneBytes)
    }

    private fun shared(i: DownloadItem): String =
        "shared " + String.format(Locale.US, "%.1f×", if (i.totalBytes > 0) i.uploadedBytes.toDouble() / i.totalBytes else 0.0)

    fun meta(i: DownloadItem, now: Long): String = when (i.state) {
        DownloadState.FAILED -> "Failed" + (i.error?.let { ": $it" } ?: "")
        DownloadState.CANCELLED -> "Cancelled"
        DownloadState.WAITING_VPN -> "Waiting for VPN · " + amount(i)
        DownloadState.WAITING_WIFI -> "Waiting for Wi-Fi · " + amount(i)
        DownloadState.PAUSED -> "Paused · " + amount(i) + (i.error?.let { " · $it" } ?: "")
        DownloadState.QUEUED -> "Starting…"
        DownloadState.CONVERTING -> "Converting… ${i.percent.coerceAtLeast(0)}%"
        DownloadState.SHARING -> listOf("Done", "sharing", "↑ " + speed(i.upBps), shared(i)).joinToString(" · ")
        DownloadState.DONE -> listOfNotNull(
            size(if (i.totalBytes > 0) i.totalBytes else i.doneBytes),
            if (i.kind == DownloadKind.TORRENT && i.uploadedBytes > 0) shared(i) else null,
            ago(now - i.updated),
        ).joinToString(" · ")
        DownloadState.RUNNING -> listOfNotNull(
            amount(i),
            if (i.downBps > 0 && i.totalBytes > 0) {
                val min = ceil((i.totalBytes - i.doneBytes).toDouble() / i.downBps / 60).toLong()
                "$min min left"
            } else null,
            "↓ " + speed(i.downBps),
            if (i.kind == DownloadKind.TORRENT) "↑ " + speed(i.upBps) else null,
            if (i.kind == DownloadKind.TORRENT) "${i.peers} people" else null,
        ).joinToString(" · ")
    }

    private fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 48 * 60 -> "yesterday"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}

/** A torrent's row: the one already there for this info hash, or a new one. */
fun upsertTorrent(existing: DownloadItem?, hash: String, name: String, now: Long): DownloadItem =
    existing?.copy(name = name, updated = now) ?: DownloadItem(
        kind = DownloadKind.TORRENT, name = name, state = DownloadState.QUEUED,
        infoHash = hash, created = now, updated = now,
    )
