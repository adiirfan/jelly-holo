/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import android.os.Looper
import android.os.Handler
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.lineageos.jelly.R
import org.lineageos.jelly.adblock.AdBlock
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.TabUtils.openInNewTab
import kotlin.reflect.cast

internal class ChromeClient(
    private val activity: WebViewExtActivity,
    private val incognito: Boolean,
    private val urlBarLayout: UrlBarLayout,
    private val sharedPreferencesExt: SharedPreferencesExt,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, progress: Int) {
        urlBarLayout.loadingProgress = progress
        super.onProgressChanged(view, progress)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        view.url?.let {
            if (!incognito) {
                activity.updateHistory(title, url = it)
            }
        }
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        if (!view.settings.javaScriptEnabled) {
            activity.onFaviconLoaded(icon)
            return
        }

        view.evaluateJavascript("${JsManifest.URL}()") { manifestUrl ->
            if (manifestUrl.isBlank() || manifestUrl == "\"\"") {
                activity.onFaviconLoaded(icon)
            }
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin.toString()
        val resources = request.resources
        if (resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
            val permission = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            val whitelist = sharedPreferencesExt.protectedMediaWhitelist.toMutableSet()
            when (whitelist.contains(origin)) {
                true -> request.grant(permission)
                false -> {
                    activity.webProtectedMedia(origin) { granted ->
                        if (!granted) return@webProtectedMedia
                        request.grant(permission)
                        whitelist.add(origin)
                        sharedPreferencesExt.protectedMediaWhitelist = whitelist.toSet()
                    }
                }
            }
            return
        }
        val permissions = buildList {
            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                add(android.Manifest.permission.CAMERA)
            }
            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                add(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        if (permissions.isEmpty()) return
        activity.webRequestPermissions(permissions.toTypedArray()) { granted ->
            val grantedResources = buildList {
                if (granted.contains(android.Manifest.permission.CAMERA)) {
                    add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                }
                if (granted.contains(android.Manifest.permission.RECORD_AUDIO)) {
                    add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                }
            }
            if (grantedResources.isEmpty()) return@webRequestPermissions
            request.grant(grantedResources.toTypedArray())
        }
    }

    override fun onShowFileChooser(
        view: WebView, path: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ): Boolean {
        activity.setFileRequestCallback {
            path.onReceiveValue(it.toTypedArray())
        }

        try {
            activity.launchFileRequest(params.acceptTypes.mapNotNull {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            }.toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                activity, activity.getString(R.string.error_no_activity_found),
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        activity.showLocationDialog(origin, callback)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        activity.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        activity.onHideCustomView()
    }

    /** Pop-ups are stopped with ad blocking, except on sites the user allowed. */
    private fun popupsBlockedOn(opener: String?): Boolean {
        if (!sharedPreferencesExt.adBlockEnabled) return false
        val host = opener?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: return true
        return host !in sharedPreferencesExt.adBlockAllowedHosts
    }

    override fun onCreateWindow(
        view: WebView, isDialog: Boolean,
        isUserGesture: Boolean, resultMsg: Message
    ): Boolean {
        if (!sharedPreferencesExt.dynamicPopupEnabled && !isUserGesture) {
            return false
        }

        val transport = WebView.WebViewTransport::class.cast(resultMsg.obj)
        val tempWebView = WebView(view.context)
        val opener = view.url
        // The link that was tapped, if any: a new tab to where it points is a real one
        val hit = view.hitTestResult
        var tappedLink: String? = if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) hit.extra else null
        if (hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            val answer = Handler(Looper.getMainLooper()) { m ->
                tappedLink = m.data.getString("url")
                true
            }.obtainMessage()
            view.requestFocusNodeHref(answer)
        }

        tempWebView.webViewClient = object : WebViewClient() {
            private var done = false

            override fun onLoadResource(view: WebView, url: String) {
                if (done) return
                done = true
                tempWebView.destroy()
                // New tabs that pages open on ad networks ("pop-unders")
                if (AdBlock.stopsJump(sharedPreferencesExt, url, opener)) {
                    activity.onJumpStopped(url, newTab = true)
                    return
                }
                // A tap anywhere that opens another site in a new tab: a pop-up ad
                if (popupsBlockedOn(opener) && !Popups.allow(opener, url, tappedLink)) {
                    activity.onPopupBlocked(url)
                    return
                }
                openInNewTab(activity, url, incognito)
            }
        }

        transport.webView = tempWebView
        resultMsg.sendToTarget()
        return true
    }
}
