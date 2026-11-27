/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import org.lineageos.jelly.R
import org.lineageos.jelly.torrent.TorrentPrefs
import org.lineageos.jelly.torrent.TorrentService
import org.lineageos.jelly.torrent.netNow

/** Torrent settings, opened from the Downloads page's ⚙. */
class TorrentSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings, TorrentSettingsFragment()).commit()
        }
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

class TorrentSettingsFragment : PreferenceFragmentCompat() {
    private val prefs by lazy { TorrentPrefs(requireContext()) }

    /** Keeps the settings in TorrentPrefs' own types; each change goes to the torrent service. */
    private inner class Store : PreferenceDataStore() {
        override fun getBoolean(key: String, defValue: Boolean) = when (key) {
            "on" -> prefs.torrentsOn
            "vpn_only" -> prefs.vpnOnly
            "keep_sharing" -> prefs.keepSharing
            "wifi_only" -> prefs.wifiOnly
            else -> defValue
        }

        override fun putBoolean(key: String, value: Boolean) {
            when (key) {
                "on" -> prefs.torrentsOn = value
                "vpn_only" -> prefs.vpnOnly = value
                "keep_sharing" -> prefs.keepSharing = value
                "wifi_only" -> prefs.wifiOnly = value
            }
            changed()
        }

        override fun getString(key: String, defValue: String?): String? = when (key) {
            "port" -> prefs.port.takeIf { it > 0 }?.toString() ?: ""
            "down_limit" -> (prefs.downLimit / 1000).takeIf { it > 0 }?.toString() ?: ""
            "up_limit" -> (prefs.upLimit / 1000).takeIf { it > 0 }?.toString() ?: ""
            "ratio" -> prefs.ratio.toString()
            else -> defValue
        }

        override fun putString(key: String, value: String?) {
            val n = value?.trim()?.toIntOrNull() ?: 0
            when (key) {
                "port" -> prefs.port = n
                "down_limit" -> prefs.downLimit = n * 1000
                "up_limit" -> prefs.upLimit = n * 1000
                "ratio" -> prefs.ratio = value?.toFloatOrNull() ?: 1.0f
            }
            changed()
            summaries()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = Store()
        setPreferencesFromResource(R.xml.torrent_settings, rootKey)
        for (key in listOf("port", "down_limit", "up_limit")) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.setSelection(it.text.length)
            }
        }
        findPreference<Preference>("port")?.setOnPreferenceChangeListener { _, value ->
            val text = (value as String).trim()
            val ok = text.isEmpty() || text.toIntOrNull() in 1024..65535
            if (!ok) Toast.makeText(requireContext(), R.string.torrent_set_port_bad, Toast.LENGTH_LONG).show()
            ok
        }
        summaries()
    }

    override fun onResume() {
        super.onResume()
        vpnLine()
    }

    private fun changed() {
        TorrentService.settingsChanged(requireContext())
        vpnLine()
    }

    private fun summaries() {
        findPreference<Preference>("port")?.summary =
            prefs.port.takeIf { it > 0 }?.toString() ?: getString(R.string.torrent_set_port_none)
        fun limit(bps: Int) = if (bps > 0) getString(R.string.torrent_set_limit_value, bps / 1000) else getString(R.string.torrent_set_no_limit)
        findPreference<Preference>("down_limit")?.summary = limit(prefs.downLimit)
        findPreference<Preference>("up_limit")?.summary = limit(prefs.upLimit)
    }

    /** "VPN connected · tun0 · 10.2.0.2", or that none is. */
    private fun vpnLine() {
        val now = netNow(requireContext(), prefs)
        findPreference<Preference>("vpn_line")?.title = if (now.state.vpnHandle != null) {
            getString(R.string.torrent_set_vpn_up, listOfNotNull(now.vpnInterface, now.vpnAddress).joinToString(" · "))
        } else {
            getString(R.string.torrent_set_vpn_none)
        }
    }
}
