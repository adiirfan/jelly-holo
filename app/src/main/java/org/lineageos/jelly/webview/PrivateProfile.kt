/*
 * SPDX-FileCopyrightText: Jelly Holo contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Private tabs' own storage: cookies, site storage and cache apart from normal
 * tabs, so signing in works there without reaching the normal tabs. It is
 * wiped when the last private tab closes, or at the next start if that
 * couldn't happen then. All calls are on the main thread.
 *
 * Needs an Android System WebView recent enough to have profiles; on an older
 * one private tabs fall back to no cookies at all.
 */
object PrivateProfile {
    private const val NAME = "jelly-private"
    private const val TAG = "JellyHolo"

    /** Private tabs open in this run of the app. */
    private var open = 0

    /** Last run's private storage has been wiped. */
    private var wipedAtStart = false

    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Gives a new private tab's page the private storage. Call before it loads anything. */
    fun attach(webView: WebView) {
        if (!supported) return
        if (!wipedAtStart && open == 0) wipe()
        wipedAtStart = true
        ProfileStore.getInstance().getOrCreateProfile(NAME)
        WebViewCompat.setProfile(webView, NAME)
        open++
    }

    /**
     * A private tab closed (its page already destroyed): wipe when it was the last, unless
     * [reopening] (the tab is only being rebuilt, e.g. for a theme change).
     */
    fun detach(reopening: Boolean = false) {
        if (!supported || open == 0) return
        open--
        if (open == 0 && !reopening) wipe()
    }

    /** The private tabs' cookie jar, or null without profiles. */
    fun cookies(): CookieManager? =
        if (supported) ProfileStore.getInstance().getProfile(NAME)?.cookieManager else null

    /**
     * Empties the private cookies and site storage at once (that works while Android still
     * holds the closed tabs' pages), then removes the whole private storage when it can.
     */
    private fun wipe() {
        runCatching {
            ProfileStore.getInstance().getProfile(NAME)?.let { p ->
                p.cookieManager.removeAllCookies(null)
                p.cookieManager.flush()
                p.webStorage.deleteAllData()
            }
        }.onFailure { Log.w(TAG, "could not empty private storage", it) }
        deleteWhenFree(tries = 3)
    }

    private fun deleteWhenFree(tries: Int) {
        runCatching { ProfileStore.getInstance().deleteProfile(NAME) }.onFailure {
            if (tries > 1) main.postDelayed({ if (open == 0) deleteWhenFree(tries - 1) }, RETRY_MS)
            else Log.i(TAG, "private storage emptied; the rest goes at the next start")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private const val RETRY_MS = 3_000L
}
