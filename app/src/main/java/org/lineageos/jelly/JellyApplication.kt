/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import org.lineageos.jelly.adblock.AdBlock
import org.lineageos.jelly.database.FavoriteDatabase
import org.lineageos.jelly.database.HistoryDatabase
import org.lineageos.jelly.repository.FavoriteRepository
import org.lineageos.jelly.repository.HistoryRepository
import org.lineageos.jelly.ui.HoloBars
import org.lineageos.jelly.utils.SharedPreferencesExt
import java.io.File

class JellyApplication : Application() {
    private val historyDatabase by lazy { HistoryDatabase.getDatabase(this) }
    val historyRepository by lazy { HistoryRepository(historyDatabase.historyDao()) }

    private val favoriteDatabase by lazy { FavoriteDatabase.getDatabase(this) }
    val favoriteRepository by lazy { FavoriteRepository(favoriteDatabase.favoriteDao()) }

    override fun onCreate() {
        super.onCreate()
        // The torrent process only runs the torrent engine
        if (processName().endsWith(":torrent")) return

        applyLook()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(lookListener)
        registerActivityLifecycleCallbacks(barsCallbacks)

        AdBlock.start(this)
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        }

    // Held here: the preference manager keeps only a weak reference.
    private val lookListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SharedPreferencesExt.LOOK_KEY) applyLook()
    }

    /** Light, Black, or follow the phone: the Black look lives in the night resources. */
    private fun applyLook() {
        AppCompatDelegate.setDefaultNightMode(
            when (SharedPreferencesExt(this).look) {
                SharedPreferencesExt.LOOK_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                SharedPreferencesExt.LOOK_BLACK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private val barsCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.window.decorView.post { HoloBars.apply(activity) }
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
