/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient.CustomViewCallback
import androidx.appcompat.app.AppCompatActivity
import org.lineageos.jelly.models.PwaManifest
import org.lineageos.jelly.models.WebShare

abstract class WebViewExtActivity : AppCompatActivity() {
    abstract fun downloadFileAsk(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    )

    abstract fun showSheetMenu(url: String, shouldAllowDownload: Boolean)
    /** A magnet link was tapped: start the torrent. */
    abstract fun onTorrentLink(url: String)
    abstract fun onFaviconLoaded(favicon: Bitmap?)
    abstract fun onShowCustomView(view: View?, callback: CustomViewCallback)
    abstract fun onHideCustomView()
    abstract fun launchFileRequest(input: Array<String>)
    abstract fun setFileRequestCallback(cb: ((data: List<Uri>) -> Unit))
    abstract fun showLocationDialog(origin: String, callback: GeolocationPermissions.Callback)
    abstract fun updateHistory(title: String, url: String)
    abstract fun replaceHistory(title: String, url: String, newUrl: String)
    abstract fun setPwaManifest(manifest: PwaManifest?)
    abstract fun setStatusBarColor(color: String)
    abstract fun webRequestPermissions(
        permissions: Array<String>,
        cb: ((granted: Array<String>) -> Unit)
    )
    abstract fun webProtectedMedia(origin: String, cb: ((granted: Boolean) -> Unit))
    abstract fun onWebShare(value: WebShare)

    /** The element picker chose something (`label` describes it; empty for nothing). */
    open fun onElementPicked(label: String) {}

    /** An ad redirect to `url` was stopped, in this tab or as a new one. */
    open fun onJumpStopped(url: String, newTab: Boolean) {}

    /**
     * A page tried to open another site in a new window without a link being tapped. [window]
     * is that window, stopped: show it if the user asks, otherwise destroy it.
     */
    open fun onPopupBlocked(url: String, window: android.webkit.WebView) {
        window.destroy()
    }

    /** Shows a window a page opened over that page, still connected to it. */
    open fun showPopup(window: android.webkit.WebView) {}

    /** The page closed its window (or [window] is null: close whichever is open). */
    open fun closePopup(window: android.webkit.WebView? = null) {}

    /** The window over the page moved to [url]. */
    open fun onPopupAddress(url: String) {}

    /** The page reported on the video being watched. */
    open fun onVideoState(state: org.lineageos.jelly.video.VideoState) {}

    /** A new page started loading in this tab. */
    open fun onPageNavigated() {}
}
