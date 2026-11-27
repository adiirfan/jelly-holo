/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import android.content.Context
import androidx.core.content.edit

/**
 * Torrent settings. Read from both the browser and the torrent process, so
 * each read goes back to the file (written by the browser, read by both).
 */
class TorrentPrefs(private val context: Context) {
    @Suppress("DEPRECATION") // still reloads the file when another process changed it
    private val p get() = context.getSharedPreferences(NAME, Context.MODE_MULTI_PROCESS)

    var torrentsOn: Boolean
        get() = p.getBoolean("on", true)
        set(v) = p.edit(commit = true) { putBoolean("on", v) }
    var vpnOnly: Boolean
        get() = p.getBoolean("vpn_only", false)
        set(v) = p.edit(commit = true) { putBoolean("vpn_only", v) }
    /** The port the VPN forwards to us; 0 = none. */
    var port: Int
        get() = p.getInt("port", 0)
        set(v) = p.edit(commit = true) { putInt("port", v) }
    var keepSharing: Boolean
        get() = p.getBoolean("keep_sharing", true)
        set(v) = p.edit(commit = true) { putBoolean("keep_sharing", v) }
    var ratio: Float
        get() = p.getFloat("ratio", 1.0f)
        set(v) = p.edit(commit = true) { putFloat("ratio", v) }
    var wifiOnly: Boolean
        get() = p.getBoolean("wifi_only", false)
        set(v) = p.edit(commit = true) { putBoolean("wifi_only", v) }
    /** Bytes a second; 0 = no limit. */
    var downLimit: Int
        get() = p.getInt("down_limit", 0)
        set(v) = p.edit(commit = true) { putInt("down_limit", v) }
    var upLimit: Int
        get() = p.getInt("up_limit", 0)
        set(v) = p.edit(commit = true) { putInt("up_limit", v) }

    /** Torrents removed while the engine was stopped: "hash|1" deletes the files too. */
    var pendingRemovals: Set<String>
        get() = p.getStringSet("pending_removals", emptySet())!!.toSet()
        set(v) = p.edit(commit = true) { putStringSet("pending_removals", v) }

    companion object {
        const val NAME = "torrent"
    }
}
