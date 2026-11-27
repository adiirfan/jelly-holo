/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.content.DialogInterface
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import org.lineageos.jelly.adblock.ElementRules
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.Toolbar
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.checkbox.MaterialCheckBox
import org.lineageos.jelly.adblock.AdBlock
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.ytdlp.Outcome
import org.lineageos.jelly.ytdlp.YtDlpRuntime
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.reflect.safeCast

class SettingsActivity : AppCompatActivity() {
    // Views
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
        private val sharedPreferencesExt by lazy { SharedPreferencesExt(requireContext()) }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // Thin Holo dividers between rows
            setDivider(ColorDrawable(ContextCompat.getColor(requireContext(), R.color.holo_divider)))
            setDividerHeight(resources.getDimensionPixelSize(R.dimen.holo_divider_height))
        }

        override fun onCreatePreferences(savedInstance: Bundle?, rootKey: String?) {
            // Load the preferences from an XML resource
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<Preference>("key_home_page")?.let {
                it.onPreferenceChangeListener = this
                it.summary = homePagePrefSummary()
            }
            if (resources.getBoolean(R.bool.is_tablet)) {
                findPreference<SwitchPreference>("key_reach_mode")?.let {
                    preferenceScreen.removePreference(it)
                }
            }
            findPreference<Preference>("key_adblock_update")?.summary = adBlockUpdateSummary()
            findPreference<Preference>("key_hidden_elements")?.let { it.summary = hiddenSummary() }
            findPreference<Preference>("key_ytdlp")?.let { showYtDlpVersion(it) }
            findPreference<Preference>("key_version")?.summary = runCatching {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            }.getOrNull()
            findPreference<SwitchPreference>("key_web_debugging")?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    WebView.setWebContentsDebuggingEnabled(newValue as Boolean)
                    true
                }
        }

        override fun onPreferenceChange(preference: Preference, value: Any?): Boolean {
            val stringValue = value.toString()

            ListPreference::class.safeCast(preference)?.also {
                val prefIndex = it.findIndexOfValue(stringValue)
                if (prefIndex >= 0) {
                    preference.summary = it.entries[prefIndex]
                }
            } ?: run {
                preference.summary = stringValue
            }
            return true
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            return when (preference.key) {
                "key_home_page" -> {
                    editHomePage(preference)
                    true
                }

                "key_adblock_update" -> {
                    updateBlockLists(preference)
                    true
                }

                "key_licenses" -> {
                    startActivity(Intent(requireContext(), LicensesActivity::class.java))
                    true
                }

                "key_hidden_elements" -> {
                    showHiddenElements(preference)
                    true
                }

                "key_ytdlp" -> {
                    updateYtDlp(preference)
                    true
                }

                "key_cookie_clear" -> {
                    CookieManager.getInstance().removeAllCookies(null)
                    Toast.makeText(
                        preference.context, getString(R.string.pref_cookie_clear_done),
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }

                else -> {
                    super.onPreferenceTreeClick(preference)
                }
            }
        }

        /** Runs yt-dlp once to show its version, which also shows it works here. */
        private fun showYtDlpVersion(preference: Preference) {
            val runtime = YtDlpRuntime(requireContext())
            preference.summary = getString(
                if (runtime.needsUnpacking) R.string.ytdlp_getting_ready else R.string.pref_ytdlp_checking
            )
            lifecycleScope.launch {
                val version = withContext(Dispatchers.IO) { runtime.version() }
                if (!isAdded) return@launch
                preference.summary = version?.let { getString(R.string.pref_ytdlp_version, it) }
                    ?: getString(R.string.pref_ytdlp_broken)
            }
        }

        private fun updateYtDlp(preference: Preference) {
            val context = preference.context.applicationContext
            preference.summary = getString(R.string.pref_ytdlp_checking)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { YtDlpRuntime(context).update(force = true) }
                val text = when (r.outcome) {
                    Outcome.UPDATED -> getString(R.string.pref_ytdlp_updated, r.version)
                    Outcome.UP_TO_DATE, Outcome.SKIPPED -> getString(R.string.pref_ytdlp_up_to_date)
                    Outcome.FAILED -> getString(R.string.pref_ytdlp_update_failed)
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                showYtDlpVersion(preference)
            }
        }

        private fun hiddenSummary(): String {
            val n = ElementRules.all().size
            return if (n == 0) getString(R.string.pref_hidden_elements_none)
            else getString(R.string.pref_hidden_elements_count, n)
        }

        /** The user's hidden elements, by site; tapping one offers to show it again. */
        private fun showHiddenElements(preference: Preference) {
            val rules = ElementRules.all().sortedBy { ElementRules.site(it) }
            if (rules.isEmpty()) return
            val context = preference.context
            AlertDialog.Builder(context)
                .setTitle(R.string.pref_hidden_elements_title)
                .setItems(rules.map { "${ElementRules.site(it)}  ·  ${ElementRules.selector(it)}" }
                    .toTypedArray()) { _, which ->
                    val rule = rules[which]
                    AlertDialog.Builder(context)
                        .setTitle(R.string.hidden_elements_remove_title)
                        .setMessage("${ElementRules.site(rule)}\n${ElementRules.selector(rule)}")
                        .setPositiveButton(R.string.hidden_elements_remove) { _, _ ->
                            ElementRules.remove(context, rule)
                            preference.summary = hiddenSummary()
                            showHiddenElements(preference)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun editHomePage(preference: Preference) {
            val builder = AlertDialog.Builder(preference.context)
            val alertDialog = builder.create()
            val inflater = alertDialog.layoutInflater
            val homepageView = inflater.inflate(
                R.layout.dialog_homepage_edit,
                LinearLayout(preference.context)
            )
            val homepageUrlEditText = homepageView.findViewById<EditText>(R.id.homepageUrlEditText)
            homepageUrlEditText.setText(sharedPreferencesExt.homePage)
            val homepageAutoload = homepageView.findViewById<MaterialCheckBox>(
                R.id.homePageAutoloadCheckBox
            )
            homepageAutoload.isChecked = sharedPreferencesExt.homePageAutoload
            homepageAutoload.setOnCheckedChangeListener { _, checked ->
                homepageUrlEditText.isEnabled = checked
            }
            homepageUrlEditText.isEnabled = homepageAutoload.isChecked
            builder.setTitle(R.string.pref_start_page_dialog_title)
                .setMessage(R.string.pref_start_page_dialog_message)
                .setView(homepageView)
                .setPositiveButton(
                    android.R.string.ok
                ) { _: DialogInterface?, _: Int ->
                    val url = homepageUrlEditText.text.toString().ifEmpty {
                        sharedPreferencesExt.defaultHomePage
                    }
                    sharedPreferencesExt.homePage = url
                    sharedPreferencesExt.homePageAutoload = homepageAutoload.isChecked
                    preference.summary = homePagePrefSummary()
                }
                .setNeutralButton(
                    R.string.pref_start_page_dialog_reset
                ) { _: DialogInterface?, _: Int ->
                    val url = sharedPreferencesExt.defaultHomePage
                    val autoload = sharedPreferencesExt.defaultHomePageAutoload
                    sharedPreferencesExt.homePage = url
                    sharedPreferencesExt.homePageAutoload = autoload
                    preference.summary = homePagePrefSummary()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private var updating = false

        private fun updateBlockLists(preference: Preference) {
            if (updating) return
            updating = true
            preference.summary = getString(R.string.pref_adblock_update_running)
            AdBlock.update(requireContext()) { ok, message ->
                updating = false
                if (ok) sharedPreferencesExt.adBlockListsUpdated = System.currentTimeMillis()
                if (!isAdded) return@update
                preference.summary = if (ok) {
                    adBlockUpdateSummary()
                } else {
                    getString(R.string.pref_adblock_update_failed, message)
                }
            }
        }

        private fun adBlockUpdateSummary(): String {
            val updated = sharedPreferencesExt.adBlockListsUpdated
            return if (updated == 0L) {
                getString(R.string.pref_adblock_update_bundled)
            } else {
                getString(
                    R.string.pref_adblock_update_date,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(updated))
                )
            }
        }

        private fun homePagePrefSummary() =
            if (sharedPreferencesExt.homePageAutoload) {
                sharedPreferencesExt.homePage
            } else {
                getString(R.string.pref_start_page_autoload_disabled)
            }
    }
}
