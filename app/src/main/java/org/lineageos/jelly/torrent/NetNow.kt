/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** The switches and networks right now, for screens (the service follows changes itself). */
data class NetNow(val state: NetState, val vpnInterface: String?, val vpnAddress: String?)

fun netNow(context: Context, prefs: TorrentPrefs = TorrentPrefs(context)): NetNow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION") // the only way to list every network, VPNs included
    val vpn = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    // Judged by the networks under any VPN: a VPN often calls itself metered over Wi-Fi
    @Suppress("DEPRECATION")
    val unmetered = unmeteredUnder(
        cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.map {
            NetInfo(
                vpn = it.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                notMetered = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
    )
    val link = vpn?.let { cm.getLinkProperties(it) }
    return NetNow(
        NetState(prefs.torrentsOn, prefs.vpnOnly, prefs.wifiOnly, vpn?.networkHandle, unmetered),
        link?.interfaceName,
        link?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress,
    )
}
