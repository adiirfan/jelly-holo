/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.SystemClock
import android.util.Log
import org.lineageos.jelly.adblock.AdBlock
import org.lineageos.jelly.utils.SharedPreferencesExt
import java.io.ByteArrayInputStream
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import org.lineageos.jelly.R
import org.lineageos.jelly.js.JsElementHide
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.js.JsMediaSession
import org.lineageos.jelly.js.JsShare
import org.lineageos.jelly.js.JsSyncUrl
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.AssetLoader
import org.lineageos.jelly.utils.IntentUtils
import org.lineageos.jelly.utils.UrlUtils
import java.net.URISyntaxException

internal class WebClient(
    private val context: Context,
    private val urlBarLayout: UrlBarLayout,
) : WebViewClient() {
    private val scripts by lazy {
        val mediaSessionAPI = AssetLoader.loadAsset(
            context.resources,
            "MediaSessionAPI.js",
        )
        buildString {
            appendLine(mediaSessionAPI)
            appendLine(JsMediaSession.SCRIPT)
            appendLine(JsShare.SCRIPT)
        }
    }

    private val prefs by lazy { SharedPreferencesExt(context) }

    /** The page whose requests are being checked; set on each main-frame load. */
    @Volatile
    private var pageUrl = ""

    @Volatile
    private var blocking = true

    @Volatile
    private var pageStartedAt = 0L

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val ext = view as? WebViewExt ?: return null
        val url = request.url.toString()
        if (request.isForMainFrame) {
            pageUrl = url
            ext.pageUrl = url
            pageStartedAt = SystemClock.elapsedRealtime()
            if (AdBlock.logging) startDataLog(ext, request.url.host)
            ext.blockedCount.set(0)
            ext.checkedCount.set(0)
            ext.slowestCheckNanos.set(0)
            ext.checkCpuNanos.set(0)
            blocking = prefs.adBlockEnabled &&
                    request.url.host !in prefs.adBlockAllowedHosts
            return null
        }
        if (!blocking) return null
        val log = AdBlock.logging
        val cpu = if (log) android.os.Debug.threadCpuTimeNanos() else 0L
        val t = System.nanoTime()
        val block = AdBlock.shouldBlock(url, pageUrl, request.requestHeaders["Accept"] ?: "*/*")
        val took = System.nanoTime() - t
        ext.checkedCount.incrementAndGet()
        if (log) {
            ext.checkCpuNanos.addAndGet(android.os.Debug.threadCpuTimeNanos() - cpu)
            ext.slowestCheckNanos.accumulateAndGet(took) { a, b -> maxOf(a, b) }
        }
        if (!block) return null
        if (log) Log.d(AdBlock.TAG, "blocked ${url.take(140)}")
        ext.blockedCount.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /** Debug log only: data the app received in the 20 s after a page started. */
    private fun startDataLog(view: WebViewExt, host: String?) {
        val uid = android.os.Process.myUid()
        val start = android.net.TrafficStats.getUidRxBytes(uid)
        view.postDelayed({
            val kb = (android.net.TrafficStats.getUidRxBytes(uid) - start) / 1024
            Log.d(AdBlock.TAG, "data $host: $kb KB in 20 s, blocked ${view.blockedCount.get()}")
        }, 20_000)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        urlBarLayout.onPageLoadStarted(url)
        (view as? WebViewExt)?.startedUrl = url
        (context as? WebViewExtActivity)?.onPageNavigated()
        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript(scripts, null)
            // Fallback for WebViews without document-start scripts; harmless twice.
            view.evaluateJavascript(JsElementHide.SCRIPT, null)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        urlBarLayout.onPageLoadFinished(view.certificate)
        if (AdBlock.logging && view is WebViewExt) {
            Log.d(
                AdBlock.TAG,
                "page ${Uri.parse(url).host} loaded in " +
                        "${SystemClock.elapsedRealtime() - pageStartedAt} ms: " +
                        "blocked ${view.blockedCount.get()} of ${view.checkedCount.get()}, " +
                        "average check ${view.checkCpuNanos.get() / maxOf(1, view.checkedCount.get()) / 1000} us " +
                        "processor time (slowest ${view.slowestCheckNanos.get() / 1000} us), " +
                        "blocking ${if (blocking) "on" else "off"}"
            )
        }
        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript(JsSyncUrl.SCRIPT, null)
            view.evaluateJavascript(JsManifest.SCRIPT, null)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        super.onReceivedSslError(view, handler, error)
        urlBarLayout.onSslError(error)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) {
            val webViewExt = view as WebViewExt
            val url = request.url.toString()
            val needsLookup = (request.hasGesture()
                    || !TextUtils.equals(url, webViewExt.lastLoadedUrl))
            if (!webViewExt.isIncognito
                && needsLookup
                && startActivityForUrl(view, url)
                && !request.isRedirect
            ) {
                return true
            } else if (webViewExt.requestHeaders.isNotEmpty()) {
                webViewExt.followUrl(url)
                return true
            }
        }
        return false
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler, host: String, realm: String
    ) {
        val context = view.context
        val builder = AlertDialog.Builder(context)
        val layoutInflater = LayoutInflater.from(context)
        val dialogView = layoutInflater.inflate(R.layout.auth_dialog, LinearLayout(context))
        val usernameEditText = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)
        val authDetailTextView = dialogView.findViewById<TextView>(R.id.authDetailTextView)
        val text = context.getString(R.string.auth_dialog_detail, view.url)
        authDetailTextView.text = text
        builder.setView(dialogView)
            .setTitle(R.string.auth_dialog_title)
            .setPositiveButton(R.string.auth_dialog_login)
            { _: DialogInterface?, _: Int ->
                handler.proceed(
                    usernameEditText.text.toString(), passwordEditText.text.toString()
                )
            }
            .setNegativeButton(android.R.string.cancel)
            { _: DialogInterface?, _: Int ->
                handler.cancel()
            }
            .setOnDismissListener { handler.cancel() }
            .show()
    }

    private fun startActivityForUrl(view: WebView, url: String): Boolean {
        val context = view.context
        var intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (ex: URISyntaxException) {
            return false
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val m = UrlUtils.ACCEPTED_URI_SCHEMA.matcher(url)
        if (m.matches()) {
            // If there only are browsers for this URL, handle it ourselves
            intent = makeHandlerChooserIntent(context, intent, url) ?: return false
        } else {
            val packageName = intent.getPackage()
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(intent, ResolveInfoFlags.of(0))
            } else {
                context.packageManager.resolveActivity(intent, 0)
            }
            if (packageName != null && resolveInfo == null) {
                // Explicit intent, but app is not installed - try to redirect to Play Store
                val storeUri = Uri.parse("market://search?q=pname:$packageName")
                intent = Intent(Intent.ACTION_VIEW, storeUri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        try {
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(
                view, context.getString(R.string.error_no_activity_found),
                Snackbar.LENGTH_LONG
            ).show()
        }
        return false
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun makeHandlerChooserIntent(context: Context, intent: Intent, url: String): Intent? {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, ResolveInfoFlags.of(flags.toLong()))
        } else {
            pm.queryIntentActivities(intent, flags)
        }
        if (activities.isEmpty()) {
            return null
        }
        val chooserIntents = ArrayList<Intent>()
        val ourPackageName = context.packageName
        activities.sortWith(ResolveInfo.DisplayNameComparator(pm))
        for (resolveInfo in activities) {
            val filter = resolveInfo.filter ?: continue
            val info = resolveInfo.activityInfo
            if (!info.enabled || !info.exported) {
                continue
            }
            if (filter.countDataAuthorities() == 0
                && !TextUtils.equals(info.packageName, ourPackageName)
            ) {
                continue
            }
            val targetIntent = Intent(intent)
            targetIntent.setPackage(info.packageName)
            chooserIntents.add(targetIntent)
        }
        if (chooserIntents.isEmpty()) {
            return null
        }
        val lastIntent = chooserIntents.removeAt(chooserIntents.size - 1)
        if (chooserIntents.isEmpty()) {
            // there was only one, no need to show the chooser
            return if (ourPackageName.equals(lastIntent.getPackage())) null else lastIntent
        }
        val changeIntent = Intent(IntentUtils.EVENT_URL_RESOLVED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            .putExtra(IntentUtils.EXTRA_URL, url)
        val pi = PendingIntent.getBroadcast(
            context, 0, changeIntent,
            PendingIntent.FLAG_IMMUTABLE
                    or PendingIntent.FLAG_UPDATE_CURRENT
                    or PendingIntent.FLAG_ONE_SHOT
        )
        val chooserIntent = Intent.createChooser(lastIntent, null)
        chooserIntent.putExtra(
            Intent.EXTRA_INITIAL_INTENTS,
            chooserIntents.toTypedArray()
        )
        chooserIntent.putExtra(Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER, pi.intentSender)
        return chooserIntent
    }
}
