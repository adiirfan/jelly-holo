/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

/** What the torrent service knows about the switches and the networks. */
data class NetState(
    val torrentsOn: Boolean,
    val vpnOnly: Boolean,
    val wifiOnly: Boolean,
    /** The VPN's network handle; null when no VPN is up. */
    val vpnHandle: Long?,
    /** The phone's main connection isn't charged by the byte (Wi-Fi, usually). */
    val unmetered: Boolean,
)

/** Whether torrents may run, and over which network. */
sealed class Lock {
    data object Off : Lock()
    data object WaitVpn : Lock()
    data object WaitWifi : Lock()

    /** Running, tied to [bindTo]; null = whatever network the phone uses. */
    data class Run(val bindTo: Long?) : Lock()
}

enum class Step { PAUSE_ALL, BIND, RESUME_ALL }

fun decide(s: NetState): Lock = when {
    !s.torrentsOn -> Lock.Off
    s.wifiOnly && !s.unmetered -> Lock.WaitWifi
    s.vpnOnly && s.vpnHandle == null -> Lock.WaitVpn
    s.vpnOnly -> Lock.Run(s.vpnHandle)
    else -> Lock.Run(null)
}

/**
 * What to do to go from [old] to [new]. Torrents always stop before the
 * network they are tied to changes, so nothing goes out on the wrong one.
 */
fun transition(old: Lock, new: Lock): List<Step> = when {
    old == new -> emptyList()
    new is Lock.Run && old is Lock.Run -> listOf(Step.PAUSE_ALL, Step.BIND, Step.RESUME_ALL)
    new is Lock.Run -> listOf(Step.BIND, Step.RESUME_ALL)
    old is Lock.Run -> listOf(Step.PAUSE_ALL)
    else -> emptyList()
}

/** A finished torrent stops sharing once it gave back [ratio] times its size. */
fun shouldStopSharing(uploaded: Long, total: Long, ratio: Float, keepSharing: Boolean): Boolean = when {
    !keepSharing -> true
    total <= 0 -> false
    else -> uploaded >= total * ratio.toDouble()
}

/** One of the phone's networks, as far as "Wi-Fi only" cares. */
data class NetInfo(val vpn: Boolean, val notMetered: Boolean)

/**
 * Whether the connection under any VPN is free (Wi-Fi). A VPN often calls
 * itself metered whatever it runs over, so only the networks under it count.
 */
fun unmeteredUnder(networks: List<NetInfo>): Boolean = networks.any { !it.vpn && it.notMetered }
