/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkLockTest {
    private fun state(
        on: Boolean = true,
        vpnOnly: Boolean = false,
        wifiOnly: Boolean = false,
        vpn: Long? = null,
        unmetered: Boolean = true,
    ) = NetState(torrentsOn = on, vpnOnly = vpnOnly, wifiOnly = wifiOnly, vpnHandle = vpn, unmetered = unmetered)

    @Test
    fun torrents_off_means_off() {
        assertEquals(Lock.Off, decide(state(on = false, vpn = 42)))
    }

    @Test
    fun without_the_vpn_rule_it_runs_on_the_phones_network() {
        assertEquals(Lock.Run(null), decide(state()))
    }

    @Test
    fun without_the_vpn_rule_a_running_vpn_is_not_forced() {
        assertEquals(Lock.Run(null), decide(state(vpn = 42)))
    }

    @Test
    fun vpn_only_without_a_vpn_waits() {
        assertEquals(Lock.WaitVpn, decide(state(vpnOnly = true)))
    }

    @Test
    fun vpn_only_with_a_vpn_ties_to_it() {
        assertEquals(Lock.Run(42), decide(state(vpnOnly = true, vpn = 42)))
    }

    @Test
    fun wifi_only_on_mobile_data_waits_before_the_vpn_rule() {
        assertEquals(Lock.WaitWifi, decide(state(wifiOnly = true, unmetered = false, vpnOnly = true)))
        assertEquals(Lock.WaitWifi, decide(state(wifiOnly = true, unmetered = false, vpnOnly = true, vpn = 42)))
    }

    @Test
    fun wifi_only_on_wifi_runs() {
        assertEquals(Lock.Run(42), decide(state(wifiOnly = true, vpnOnly = true, vpn = 42)))
    }

    @Test
    fun vpn_handle_change_pauses_then_reties() {
        assertEquals(listOf(Step.PAUSE_ALL, Step.BIND, Step.RESUME_ALL), transition(Lock.Run(42), Lock.Run(43)))
        assertEquals(listOf(Step.PAUSE_ALL), transition(Lock.Run(42), Lock.WaitVpn))
        assertEquals(listOf(Step.BIND, Step.RESUME_ALL), transition(Lock.WaitVpn, Lock.Run(43)))
        assertEquals(emptyList<Step>(), transition(Lock.Run(null), Lock.Run(null)))
    }

    @Test
    fun turning_off_pauses_and_turning_on_binds_first() {
        assertEquals(listOf(Step.PAUSE_ALL), transition(Lock.Run(null), Lock.Off))
        assertEquals(listOf(Step.BIND, Step.RESUME_ALL), transition(Lock.Off, Lock.Run(null)))
        assertEquals(emptyList<Step>(), transition(Lock.WaitVpn, Lock.Off))
    }

    @Test
    fun turning_the_vpn_rule_off_unties_from_the_vpn() {
        assertEquals(listOf(Step.PAUSE_ALL, Step.BIND, Step.RESUME_ALL), transition(Lock.Run(42), Lock.Run(null)))
    }
}
