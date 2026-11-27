/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import org.lineageos.jelly.model.DownloadState
import org.lineageos.jelly.torrent.Lock

/** The line under the Torrents switch on the Downloads page. */
fun stripStatus(torrentsOn: Boolean, vpnOnly: Boolean, lock: Lock, port: Int): String {
    if (!torrentsOn) return "Off"
    val portText = if (port > 0) "port $port" else "no port"
    return when (lock) {
        Lock.Off -> "Off"
        Lock.WaitVpn -> "Paused: VPN not connected. They carry on when it's back."
        Lock.WaitWifi -> "Paused: no Wi-Fi. They carry on when it's back."
        is Lock.Run -> if (vpnOnly) "Only over VPN · connected · $portText" else "Any connection · $portText"
    }
}

enum class TorrentButton { PAUSE, RESUME, STOP_SHARING, MENU }

/** The button at the end of a torrent's row. */
fun torrentButton(state: DownloadState): TorrentButton = when (state) {
    DownloadState.PAUSED -> TorrentButton.RESUME
    DownloadState.SHARING -> TorrentButton.STOP_SHARING
    DownloadState.DONE, DownloadState.FAILED, DownloadState.CANCELLED -> TorrentButton.MENU
    else -> TorrentButton.PAUSE
}
