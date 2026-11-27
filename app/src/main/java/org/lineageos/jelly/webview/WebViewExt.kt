/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.webview

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.constraintlayout.widget.ConstraintLayout
import org.lineageos.jelly.R
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.js.JsMediaSession
import org.lineageos.jelly.js.JsShare
import org.lineageos.jelly.js.JsElementHide
import org.lineageos.jelly.utils.AssetLoader
import org.lineageos.jelly.video.VideoBridge
import org.lineageos.jelly.js.JsSyncUrl
import org.lineageos.jelly.shortcut.BackgroundShortcut
import org.lineageos.jelly.shortcut.BackgroundShortcutService
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.UrlUtils
import java.util.regex.Pattern

class WebViewExt @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
    backgroundShortcut: BackgroundShortcut? = null,
    backgroundShortcutService: BackgroundShortcutService? = null
) : WebView(context, attrs, defStyle) {
    private lateinit var activity: WebViewExtActivity
    val requestHeaders = mutableMapOf<String?, String?>()
    private var mobileUserAgent: String? = null
    private var desktopUserAgent: String? = null
    var isIncognito = false
        private set
    private var desktopMode = false
    var lastLoadedUrl: String? = null
        private set
    var backgroundShortcut = backgroundShortcut
        private set
    var backgroundShortcutService = backgroundShortcutService
        private set
    var initialized: Boolean = false
        private set

    private val sharedPreferencesExt by lazy { SharedPreferencesExt(context) }

    /** Requests the ad blocker dropped on the page now showing. */
    val blockedCount = AtomicInteger()

    /** Requests checked on this page, and the slowest check (for the debug log). */
    val checkedCount = AtomicInteger()
    val slowestCheckNanos = AtomicLong()
    val checkCpuNanos = AtomicLong()

    fun blockedOnPage(): Int = blockedCount.get()

    /** The page this tab is loading: its first request, and the address it started at. */
    @Volatile
    var pageUrl = ""

    @Volatile
    var startedUrl = ""

    /** After "Go anyway", ad redirects aren't stopped in this tab until this time. */
    var jumpsAllowedUntil = 0L

    /** The line to the video player script in every frame of this tab. */
    /** Stream playlists (.m3u8) this page has loaded, oldest first, for saving a streamed video. */
    private val streamList = ArrayDeque<String>()

    fun noteStream(url: String) = synchronized(streamList) {
        if (url !in streamList) {
            if (org.lineageos.jelly.adblock.AdBlock.logging) {
                android.util.Log.d(org.lineageos.jelly.adblock.AdBlock.TAG, "stream seen: ${url.take(120)}")
            }
            streamList.addLast(url)
            while (streamList.size > 20) streamList.removeFirst()
        }
    }

    fun streams(): List<String> = synchronized(streamList) { streamList.toList() }

    fun clearStreams() = synchronized(streamList) { streamList.clear() }

    val video = VideoBridge { s -> if (::activity.isInitialized) activity.onVideoState(s) }

    fun onElementPicked(label: String) {
        if (::activity.isInitialized) activity.onElementPicked(label)
    }

    /**
     * Let Android repaint web pages dark. It only takes effect while the app
     * itself is dark (the Black look); in the light look pages stay as they are.
     */
    fun applyDarkPages(enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, enabled)
        }
    }

    /** When the page was last tapped, so a link that forwards to a magnet counts as tapped. */
    @Volatile
    var lastTapAt = 0L

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) lastTapAt = android.os.SystemClock.elapsedRealtime()
        return super.onTouchEvent(event)
    }

    override fun loadUrl(url: String) {
        lastLoadedUrl = url
        followUrl(url)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        val backgroundMode = backgroundShortcutService != null
        super.onWindowVisibilityChanged(
            if (backgroundMode) View.VISIBLE else visibility
        )
    }

    fun followUrl(url: String) {
        UrlUtils.smartUrlFilter(url)?.let {
            super.loadUrl(it, this.requestHeaders)
            return
        }
        val templateUri = sharedPreferencesExt.searchEngine
        super.loadUrl(UrlUtils.getFormattedUri(templateUri, url), this.requestHeaders)
    }

    private fun setup(urlBarLayout: UrlBarLayout) {
        settings.javaScriptEnabled = sharedPreferencesExt.javascriptEnabled
        settings.javaScriptCanOpenWindowsAutomatically = sharedPreferencesExt.javascriptEnabled
        settings.setGeolocationEnabled(sharedPreferencesExt.locationEnabled)
        settings.setSupportMultipleWindows(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.domStorageEnabled = !isIncognito
        setOnLongClickListener(object : OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                val result = hitTestResult
                result.extra?.let {
                    when (result.type) {
                        HitTestResult.IMAGE_TYPE, HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            activity.showSheetMenu(it, true)
                            return true
                        }

                        HitTestResult.SRC_ANCHOR_TYPE -> {
                            activity.showSheetMenu(it, false)
                            return true
                        }

                        else -> {
                            return false
                        }
                    }
                }
                return false
            }
        })
        setDownloadListener { url: String?, userAgent: String?, contentDisposition: String?,
                              mimeType: String?, contentLength: Long ->
            activity.downloadFileAsk(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        // Mobile: Remove "wv" and "Version/4.0" from the WebView's user agent.
        // Some websites don't work properly if the browser reports itself as a simple WebView.
        // Desktop: Generate the desktop user agent starting from the mobile one so that
        // we always report the current engine version.
        val pattern = Pattern.compile("([^)]+ \\()([^)]+)(\\) .*)")
        val matcher = pattern.matcher(settings.userAgentString)
        if (matcher.matches()) {
            val mobileDevice = matcher.group(2)!!.replace("; wv", "")
            mobileUserAgent = matcher.group(1)!! + mobileDevice + matcher.group(3)!!
                .replace(" Version/4.0 ", " ")
            desktopUserAgent = matcher.group(1)!! + DESKTOP_DEVICE + matcher.group(3)!!
                .replace(" Mobile ", " ")
                .replace(" Version/4.0 ", " ")
            settings.userAgentString = mobileUserAgent
        } else {
            Log.e(TAG, "Couldn't parse the user agent")
            mobileUserAgent = settings.userAgentString
            desktopUserAgent = DESKTOP_USER_AGENT_FALLBACK
        }
        if (sharedPreferencesExt.doNotTrackEnabled) {
            this.requestHeaders[HEADER_DNT] = "1"
        }

        if (settings.javaScriptEnabled) {
            addJavascriptInterface(
                JsSyncUrl(urlBarLayout, activity),
                JsSyncUrl.INTERFACE
            )
            addJavascriptInterface(
                JsManifest(activity),
                JsManifest.INTERFACE
            )
            addJavascriptInterface(
                JsMediaSession(this),
                JsMediaSession.INTERFACE
            )
            addJavascriptInterface(
                JsShare(activity),
                JsShare.INTERFACE
            )
            addJavascriptInterface(JsElementHide(this), JsElementHide.INTERFACE)
            // Hide the user's chosen elements before the page first draws.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, JsElementHide.SCRIPT, setOf("*"))
            }
            // The app's own video player: a script in every frame reports the
            // video and takes the player's commands
            if (video.attach(this)) {
                WebViewCompat.addDocumentStartJavaScript(
                    this, AssetLoader.loadAsset(resources, "video.js"), setOf("*")
                )
            }
        }
    }

    fun init(
        activity: WebViewExtActivity,
        urlBarLayout: UrlBarLayout,
        desktopMode: Boolean,
        incognito: Boolean,
    ) {
        if (initialized) return
        this.activity = activity
        isIncognito = incognito
        val chromeClient = ChromeClient(
            activity, incognito, urlBarLayout, sharedPreferencesExt
        )
        webChromeClient = chromeClient
        webViewClient = WebClient(activity, urlBarLayout)
        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            urlBarLayout.searchPositionInfo = Pair(activeMatchOrdinal, numberOfMatches)
        }
        urlBarLayout.onLoadUrlCallback = { loadUrl(it) }
        urlBarLayout.onStartSearchCallback = { findAllAsync(it) }
        urlBarLayout.onClearSearchCallback = { clearMatches() }
        urlBarLayout.onReloadCallback = { reload() }
        urlBarLayout.onSearchPositionChangeCallback = { findNext(it) }
        setup(urlBarLayout)
        // Black (or white) before the page paints: no white flash in the Black look
        setBackgroundColor(ContextCompat.getColor(context, R.color.holo_page_background))
        applyDarkPages(sharedPreferencesExt.darkPages)
        setWebViewDesktopMode(desktopMode)
        initialized = true
    }

    val snap: Bitmap
        get() {
            measure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            layout(0, 0, measuredWidth, measuredHeight)
            val size = if (measuredWidth > measuredHeight) measuredHeight else measuredWidth
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val height = bitmap.height
            canvas.drawBitmap(bitmap, 0f, height.toFloat(), paint)
            draw(canvas)
            return bitmap
        }

    var isDesktopMode: Boolean
        get() = desktopMode
        set(value) {
            setWebViewDesktopMode(value)
            reload()
        }

    private fun setWebViewDesktopMode(value: Boolean) {
        desktopMode = value
        settings.apply {
            userAgentString = if (value) desktopUserAgent else mobileUserAgent
            useWideViewPort = value
            loadWithOverviewMode = value
        }
    }

    companion object {
        private const val TAG = "WebViewExt"
        private const val DESKTOP_DEVICE = "X11; Linux x86_64"
        private const val DESKTOP_USER_AGENT_FALLBACK =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val HEADER_DNT = "DNT"

        fun newInstance(
            context: Context,
            backgroundShortcut: BackgroundShortcut? = null,
            backgroundShortcutService: BackgroundShortcutService? = null
        ) = WebViewExt(
            context,
            backgroundShortcut = backgroundShortcut,
            backgroundShortcutService = backgroundShortcutService
        ).apply {
            id = R.id.webView
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = R.id.appBarLayout
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
    }
}
