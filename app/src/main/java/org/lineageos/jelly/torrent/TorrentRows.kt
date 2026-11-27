/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import org.json.JSONArray
import org.lineageos.jelly.model.DownloadItem
import org.lineageos.jelly.model.DownloadState

data class TorrentFile(val name: String, val size: Long, val done: Long, val selected: Boolean)

/** One torrent as the engine reports it (see `Stat` in torrent/src/lib.rs). */
data class TorrentStat(
    val id: Int,
    val infoHash: String,
    val name: String,
    /** "initializing", "live", "paused" or "error". */
    val state: String,
    val done: Long,
    val total: Long,
    val uploaded: Long,
    val downBps: Long,
    val upBps: Long,
    val peers: Int,
    val finished: Boolean,
    val error: String?,
    val files: List<TorrentFile>,
)

/** The engine's list; anything else (an `{"error": …}`) is no torrents. */
fun parseStats(json: String): List<TorrentStat> {
    if (!json.trimStart().startsWith("[")) return emptyList()
    val a = JSONArray(json)
    return (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val files = o.optJSONArray("files") ?: JSONArray()
        TorrentStat(
            id = o.getInt("id"),
            infoHash = o.getString("info_hash"),
            name = o.getString("name"),
            state = o.getString("state"),
            done = o.getLong("done"),
            total = o.getLong("total"),
            uploaded = o.getLong("uploaded"),
            downBps = o.getLong("down_bps"),
            upBps = o.getLong("up_bps"),
            peers = o.getInt("peers"),
            finished = o.getBoolean("finished"),
            error = if (o.isNull("error")) null else o.getString("error"),
            files = (0 until files.length()).map { j ->
                val f = files.getJSONObject(j)
                TorrentFile(f.getString("name"), f.getLong("size"), f.getLong("done"), f.getBoolean("selected"))
            },
        )
    }
}

/** The page's row for a torrent the engine is running. */
fun applyStat(row: DownloadItem, s: TorrentStat, now: Long): DownloadItem {
    val state = when {
        s.state == "error" -> DownloadState.FAILED
        s.state == "initializing" -> DownloadState.QUEUED
        s.finished && s.state == "paused" -> DownloadState.DONE
        s.finished -> DownloadState.SHARING
        s.state == "paused" -> DownloadState.PAUSED
        else -> DownloadState.RUNNING
    }
    val moving = state == DownloadState.RUNNING || state == DownloadState.SHARING
    return row.copy(
        name = s.name,
        state = state,
        engineId = s.id,
        doneBytes = s.done,
        totalBytes = s.total,
        uploadedBytes = s.uploaded,
        downBps = if (moving) s.downBps else 0,
        upBps = if (moving) s.upBps else 0,
        peers = if (moving) s.peers else 0,
        error = if (state == DownloadState.FAILED) s.error else null,
        updated = now,
    )
}

/** The row while the engine is stopped by [lock]: what was moving now waits. */
fun waitingRow(row: DownloadItem, lock: Lock, now: Long): DownloadItem {
    val state = when (row.state) {
        DownloadState.PAUSED, DownloadState.DONE, DownloadState.FAILED, DownloadState.CANCELLED -> return row
        else -> when (lock) {
            Lock.WaitVpn -> DownloadState.WAITING_VPN
            Lock.WaitWifi -> DownloadState.WAITING_WIFI
            else -> DownloadState.PAUSED
        }
    }
    return row.copy(state = state, downBps = 0, upBps = 0, peers = 0, updated = now)
}
