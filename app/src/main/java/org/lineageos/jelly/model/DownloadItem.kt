/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadKind { FILE, STREAM, YTDLP, TORRENT }

enum class DownloadState { QUEUED, RUNNING, CONVERTING, PAUSED, WAITING_VPN, WAITING_WIFI, SHARING, DONE, FAILED, CANCELLED }

/** One download the browser made, as the Downloads page shows it. */
@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: DownloadKind,
    val name: String,
    val state: DownloadState,
    val doneBytes: Long = 0,
    /** -1 while unknown. */
    val totalBytes: Long = -1,
    val downBps: Long = 0,
    val upBps: Long = 0,
    /** Torrents: how much was shared back. */
    val uploadedBytes: Long = 0,
    val peers: Int = 0,
    /** Conversions; -1 when not used. */
    val percent: Int = -1,
    /** Where the finished file is (a content or file URI). */
    val uri: String? = null,
    val mime: String? = null,
    /** The page, link or magnet it came from. */
    val source: String? = null,
    val infoHash: String? = null,
    /** The torrent engine's number for it, -1 when not running. */
    val engineId: Int = -1,
    val error: String? = null,
    val created: Long,
    val updated: Long,
    /** Android DownloadManager's id, -1 when not through it. */
    val dmId: Long = -1,
)
