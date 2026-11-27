/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.app.Activity
import android.app.ActivityManager.TaskDescription
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.content.pm.ActivityInfo
import org.lineageos.jelly.video.VideoState
import org.lineageos.jelly.video.VideoPlayerView
import android.widget.Toast
import android.view.MotionEvent
import android.view.InputDevice
import android.util.Rational
import android.app.PictureInPictureParams
import org.lineageos.jelly.download.DownloadService
import android.content.pm.PackageManager
import android.Manifest
import android.os.SystemClock
import android.widget.TextView
import android.text.format.Formatter
import org.lineageos.jelly.utils.AssetLoader
import org.lineageos.jelly.js.JsElementHide
import org.lineageos.jelly.adblock.ElementRules
import org.json.JSONArray
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lineageos.jelly.download.CodecSupport
import org.lineageos.jelly.download.Transcode
import org.lineageos.jelly.ytdlp.Option
import org.lineageos.jelly.ytdlp.Output
import org.lineageos.jelly.ytdlp.YtDlpFinder
import org.lineageos.jelly.ytdlp.YtDlpInfo
import org.lineageos.jelly.ytdlp.YtDlpJob
import org.lineageos.jelly.ytdlp.YtDlpRuntime
import org.lineageos.jelly.ytdlp.fileName
import org.lineageos.jelly.ytdlp.options
import org.lineageos.jelly.ytdlp.writeCookies
import java.util.concurrent.atomic.AtomicBoolean
import org.lineageos.jelly.ext.buildShareIntent
import org.lineageos.jelly.favorite.FavoriteActivity
import org.lineageos.jelly.history.HistoryActivity
import org.lineageos.jelly.models.PwaManifest
import org.lineageos.jelly.models.WebShare
import org.lineageos.jelly.shortcut.BackgroundShortcut
import org.lineageos.jelly.shortcut.BackgroundShortcutActivity
import org.lineageos.jelly.shortcut.BackgroundShortcutService
import org.lineageos.jelly.tabs.TabSnapshots
import org.lineageos.jelly.tabs.TabsActivity
import org.lineageos.jelly.tabs.tabTasks
import org.lineageos.jelly.ui.MenuDialog
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.IntentUtils
import org.lineageos.jelly.utils.PermissionsUtils
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.TabUtils.openInNewTab
import org.lineageos.jelly.utils.UiUtils
import org.lineageos.jelly.utils.UrlUtils
import org.lineageos.jelly.viewmodels.FavoriteViewModel
import org.lineageos.jelly.viewmodels.HistoryViewModel
import org.lineageos.jelly.viewmodels.SuggestionProviderViewModel
import org.lineageos.jelly.webview.WebViewExt
import org.lineageos.jelly.webview.WebViewExtActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.reflect.cast

class MainActivity : WebViewExtActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    // View model
    private val historyViewModel: HistoryViewModel by viewModels()
    private val favoritesViewModel: FavoriteViewModel by viewModels()
    private val suggestionProviderViewModel: SuggestionProviderViewModel by viewModels()

    // Views
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val constraintLayout by lazy { findViewById<ConstraintLayout>(R.id.constraintLayout) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val urlBarLayout by lazy { findViewById<UrlBarLayout>(R.id.urlBarLayout) }

    private lateinit var webView: WebViewExt
    private var shortcutId: String? = null
    private var shortcutName: String? = null
    private var backgroundShortcutId: String? = null
    private var backgroundShortcutServiceConnected: Boolean = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            backgroundShortcutServiceConnected = true
            val binder = BackgroundShortcutService.ServiceBinder::class.cast(service)
            val id = backgroundShortcutId!!
            val name = shortcutName ?: id
            val backgroundShortcut = BackgroundShortcut(id, name, true)
            val backgroundShortcutService = binder.getService()
            webView = backgroundShortcutService.getWebView(backgroundShortcut)
            onWebViewResolved()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            backgroundShortcutServiceConnected = false
        }
    }

    private var backgroundShortcutResultFinished: Boolean = true
    private val backgroundShortcutLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            backgroundShortcutResultFinished = true
            if (it.resultCode == RESULT_OK) finish()
            else prepareWebView()
        }

    private val shortcutManager by lazy { getSystemService(ShortcutManager::class.java) }

    private val fileRequest =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            fileRequestCallback.invoke(it)
        }
    private lateinit var fileRequestCallback: ((data: List<Uri>) -> Unit)

    private var pwaManifest: PwaManifest? = null
    private var webRequestPermissions: ((granted: Array<String>) -> Unit)? = null
    private var webRequestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = ArrayList<String>()
            it.entries.forEach { permission ->
                if (permission.value) granted.add(permission.key)
            }
            webRequestPermissions?.invoke(granted.toTypedArray())
        }

    override fun launchFileRequest(input: Array<String>) {
        fileRequest.launch(input)
    }

    override fun setFileRequestCallback(cb: (data: List<Uri>) -> Unit) {
        fileRequestCallback = cb
    }

    private val urlResolvedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.hasExtra(Intent.EXTRA_INTENT) ||
                !intent.hasExtra(Intent.EXTRA_RESULT_RECEIVER)
            ) {
                return
            }
            @Suppress("UnsafeIntentLaunch")
            val resolvedIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            } else {
                @Suppress("Deprecation")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)!!
            }
            if (TextUtils.equals(packageName, resolvedIntent.getPackage())) {
                val url: String = intent.getStringExtra(IntentUtils.EXTRA_URL)!!
                webView.loadUrl(url)
            } else {
                startActivity(resolvedIntent)
            }
            val receiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    Intent.EXTRA_RESULT_RECEIVER,
                    ResultReceiver::class.java
                )!!
            } else {
                @Suppress("Deprecation")
                intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER)!!
            }
            receiver.send(Activity.RESULT_CANCELED, Bundle())
        }
    }
    private var urlIcon: Bitmap? = null
    private var url: String? = null
    private var desktopMode = false
    private var incognito = false
    private var isFullscreenPwa = false
    private var customView: View? = null
    private var fullScreenCallback: CustomViewCallback? = null
    private lateinit var menuDialog: MenuDialog

    private val sharedPreferencesExt by lazy { SharedPreferencesExt(this) }

    private val permissionsUtils by lazy { PermissionsUtils(this) }

    private lateinit var locationDialogCallback: (() -> Unit)
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it.isNotEmpty()) {
            if (permissionsUtils.locationPermissionsGranted()) {
                // Precise or approximate location access granted.
                locationDialogCallback()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(sharedPreferencesExt.webDebuggingEnabled)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        shortcutId = intent.getStringExtra(IntentUtils.EXTRA_SHORTCUT_ID)
        shortcutName = intent.getStringExtra(IntentUtils.EXTRA_SHORTCUT_NAME)
        url = when (intent.getBooleanExtra(IntentUtils.EXTRA_IGNORE_DATA, false)) {
            true -> intent.getStringExtra(IntentUtils.EXTRA_PAGE_URL)
            false -> intent.dataString
        }
        desktopMode = sharedPreferencesExt.desktopShortcuts.contains(shortcutId)
        incognito = intent.getBooleanExtra(IntentUtils.EXTRA_INCOGNITO, false)
        isFullscreenPwa = when {
            sharedPreferencesExt.fullscreenShortcutsEnabled && !shortcutId.isNullOrEmpty() -> true
            else -> intent.getStringExtra(MANIFEST_DISPLAY)?.let {
                ALLOWED_FULLSCREEN_PWA_VALUES.contains(it)
            } ?: false
        }

        // Restore from previous instance
        savedInstanceState?.let {
            url = url?.takeIf { url ->
                url.isNotEmpty()
            } ?: it.getString(IntentUtils.EXTRA_URL, null)
            desktopMode = it.getBoolean(IntentUtils.EXTRA_DESKTOP_MODE, desktopMode)
            incognito = it.getBoolean(IntentUtils.EXTRA_INCOGNITO, incognito)
            isFullscreenPwa = it.getBoolean(IntentUtils.EXTRA_FULLSCREEN_PWA, isFullscreenPwa)
        }

        // Make sure prefs are set before loading them
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        val preferenceManager = PreferenceManager.getDefaultSharedPreferences(this)
        preferenceManager.registerOnSharedPreferenceChangeListener(this)

        // Register app shortcuts
        registerShortcuts()

        urlBarLayout.isIncognito = incognito

        menuDialog = MenuDialog(this, desktopMode) { option: MenuDialog.Option ->
            when (option) {
                MenuDialog.Option.FORWARD -> webView.goForward()
                MenuDialog.Option.NEW_TAB -> openInNewTab(this, null, false)
                MenuDialog.Option.NEW_PRIVATE_TAB -> openInNewTab(this, null, true)
                MenuDialog.Option.REFRESH -> webView.reload()
                MenuDialog.Option.ADD_TO_FAVORITE ->
                    webView.title?.let { title ->
                        webView.url?.let { url ->
                            setAsFavorite(title, url)
                        }
                    }

                MenuDialog.Option.SHARE -> {
                    // Delay a bit to allow popup menu hide animation to play
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.url?.let { url -> shareUrl(url) }
                    }, 300)
                }

                MenuDialog.Option.HIDE_ELEMENT -> startPicker()

                MenuDialog.Option.FIND_IN_PAGE -> {
                    // Run the search setup
                    showSearch()
                }

                MenuDialog.Option.FAVORITES -> startActivity(
                    Intent(
                        this,
                        FavoriteActivity::class.java
                    )
                )

                MenuDialog.Option.HISTORY -> startActivity(
                    Intent(
                        this,
                        HistoryActivity::class.java
                    )
                )

                MenuDialog.Option.DOWNLOADS -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                MenuDialog.Option.DOWNLOAD_VIDEO -> downloadWithYtDlp(webView.url)
                MenuDialog.Option.ADD_TO_HOME_SCREEN -> addShortcut()
                MenuDialog.Option.PRINT -> {
                    val printManager = getSystemService(PrintManager::class.java)
                    val documentName = "Jelly document"
                    val printAdapter = webView.createPrintDocumentAdapter(documentName)
                    printManager.print(
                        documentName, printAdapter,
                        PrintAttributes.Builder().build()
                    )
                }

                MenuDialog.Option.DESKTOP_VIEW -> {
                    desktopMode = !desktopMode
                    webView.isDesktopMode = desktopMode
                    menuDialog.isDesktopMode = desktopMode
                }

                MenuDialog.Option.DARK_PAGES -> {
                    sharedPreferencesExt.darkPages = !sharedPreferencesExt.darkPages
                    webView.applyDarkPages(sharedPreferencesExt.darkPages)
                    menuDialog.isDarkPages = sharedPreferencesExt.darkPages
                }

                MenuDialog.Option.DESKTOP_SHORTCUTS -> Intent(
                    this,
                    BackgroundShortcutActivity::class.java
                ).apply {
                    putExtra(BackgroundShortcutActivity.DESKTOP_SHORTCUTS, true)
                    startActivity(this)
                }

                MenuDialog.Option.BACKGROUND_SHORTCUTS -> backgroundShortcutLauncher.launch(
                    Intent(
                        this,
                        BackgroundShortcutActivity::class.java
                    ).apply {
                        backgroundShortcutResultFinished = false
                        putExtra(IntentUtils.EXTRA_SHORTCUT_ID, shortcutId)
                    }
                )

                MenuDialog.Option.SETTINGS -> startActivity(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    )
                )
            }
            menuDialog.dismiss()
        }
        urlBarLayout.onTabsClick = {
            UiUtils.hideKeyboard(window, urlBarLayout)
            startActivity(Intent(this, TabsActivity::class.java))
        }
        urlBarLayout.onMoreButtonClickCallback = {
            UiUtils.hideKeyboard(window, urlBarLayout)
            menuDialog.darkPagesAvailable = isNightMode()
            menuDialog.isDarkPages = sharedPreferencesExt.darkPages
            menuDialog.setBlockedCount(
                webView.blockedOnPage().takeIf { sharedPreferencesExt.adBlockEnabled }
            )
            menuDialog.isFavorite = false
            webView.url?.let { url ->
                lifecycleScope.launch {
                    menuDialog.isFavorite =
                        (application as JellyApplication).favoriteRepository.contains(url)
                }
            }
            menuDialog.showAsDropdownMenu(urlBarLayout, sharedPreferencesExt.reachModeEnabled)
        }

        CookieManager.getInstance()
            .setAcceptCookie(!incognito && sharedPreferencesExt.cookiesEnabled)

        try {
            val httpCacheDir = File(cacheDir, "suggestion_responses")
            val httpCacheSize = 1024 * 1024.toLong() // 1 MiB
            HttpResponseCache.install(httpCacheDir, httpCacheSize)
        } catch (e: IOException) {
            Log.i(TAG, "HTTP response cache installation failed:$e")
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    picking -> stopPicker()

                    urlBarLayout.currentMode == UrlBarLayout.UrlBarMode.SEARCH -> {
                        urlBarLayout.currentMode = UrlBarLayout.UrlBarMode.URL
                    }

                    videoStretched -> unstretchVideo()

                    customView != null -> {
                        onHideCustomView()
                    }

                    webView.canGoBack() -> {
                        webView.goBack()
                    }

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                suggestionProviderViewModel.suggestionProvider().collectLatest {
                    urlBarLayout.setSuggestionsProvider(it)
                }
            }
        }

        appBarLayout.isVisible = !isFullscreenPwa
        intent.extras?.let {
            it.getString(MANIFEST_THEME_COLOR)?.let { themeColor ->
                setStatusBarColor(themeColor)
            }
        }

        // Opened from a download note that offered yt-dlp (not when restoring)
        if (savedInstanceState == null && intent.getBooleanExtra(DownloadService.EXTRA_YTDLP, false)) {
            url?.let { page -> Handler(Looper.getMainLooper()).post { downloadWithYtDlp(page) } }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                urlResolvedReceiver, IntentFilter(IntentUtils.EVENT_URL_RESOLVED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(urlResolvedReceiver, IntentFilter(IntentUtils.EVENT_URL_RESOLVED))
        }
        if (backgroundShortcutResultFinished) prepareWebView()
    }

    override fun onStop() {
        ytdlpLookup?.set(true)
        // The small video window was closed: stop the video too
        if (videoStretched) {
            webView.video.send("pause")
            unstretchVideo()
        }
        CookieManager.getInstance().flush()
        if (backgroundShortcutServiceConnected) unbindService(serviceConnection)
        unregisterReceiver(urlResolvedReceiver)
        HttpResponseCache.getInstalled().flush()
        super.onStop()
    }

    public override fun onPause() {
        if (picking) stopPicker()
        // The tab screen shows this page as it was left
        if (::webView.isInitialized && !incognito) TabSnapshots.save(this, taskId, webView)
        // In the small video window the page has to keep playing and drawing
        if (backgroundShortcutId == null && !isInPictureInPictureMode) webView.onPause()
        super.onPause()
    }

    private fun isNightMode() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onResume() {
        super.onResume()
        if (backgroundShortcutId == null) webView.onResume()
        urlBarLayout.tabCount = tabTasks(this).size
        // Other tabs may have switched Dark pages while this one was away
        if (::webView.isInitialized) webView.applyDarkPages(sharedPreferencesExt.darkPages)
        CookieManager.getInstance()
            .setAcceptCookie(!incognito && sharedPreferencesExt.cookiesEnabled)
        if (sharedPreferencesExt.lookLockEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Preserve webView status
        outState.putString(IntentUtils.EXTRA_URL, webView.url)
        outState.putBoolean(IntentUtils.EXTRA_INCOGNITO, webView.isIncognito)
        outState.putBoolean(IntentUtils.EXTRA_DESKTOP_MODE, webView.isDesktopMode)

        // Pwa fullscreen status
        outState.putBoolean(IntentUtils.EXTRA_FULLSCREEN_PWA, isFullscreenPwa)
    }

    private fun prepareWebView() {
        val backgroundShortcuts = sharedPreferencesExt.backgroundShortcuts
        val isBackgroundShortcut = backgroundShortcuts.contains(shortcutId)
        backgroundShortcutId = if (isBackgroundShortcut) shortcutId else null
        if (backgroundShortcutId == null) {
            if (::webView.isInitialized) return
            webView = WebViewExt.newInstance(this)
            onWebViewResolved()
        } else {
            Intent(this, BackgroundShortcutService::class.java).let {
                it.action = BackgroundShortcutService.START_FOREGROUND_ACTION
                startForegroundService(it)
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    private fun onWebViewResolved() {
        if (urlBarLayout.url == null) urlBarLayout.url = webView.url
        webView.parent?.let { ViewGroup::class.cast(it).removeView(webView) }
        constraintLayout.addView(webView)
        setUiMode()
        if (webView.initialized) return
        webView.init(this, urlBarLayout, desktopMode, incognito)
        if (url != null || sharedPreferencesExt.homePageAutoload) {
            webView.loadUrl(url ?: sharedPreferencesExt.homePage)
        }
    }

    private fun registerShortcuts() {
        shortcutManager.dynamicShortcuts = listOf(
            ShortcutInfo.Builder(this, "new_incognito_tab_shortcut")
                .setShortLabel(getString(R.string.shortcut_new_incognito_tab))
                .setLongLabel(getString(R.string.shortcut_new_incognito_tab))
                .setIcon(Icon.createWithResource(this, R.drawable.shortcut_incognito))
                .setIntent(
                    Intent(this, MainActivity::class.java)
                        .putExtra("extra_incognito", true)
                        .setAction(Intent.ACTION_VIEW)
                )
                .build()
        )
    }

    // "Hide an element": the picker runs in the page (assets/picker.js); this
    // bar at the foot of the screen drives it.
    private var picking = false
    private val pickerBar: View by lazy {
        layoutInflater.inflate(R.layout.picker_bar, constraintLayout, false).also { bar ->
            bar.findViewById<View>(R.id.pickerCancel).setOnClickListener { stopPicker() }
            bar.findViewById<View>(R.id.pickerSmaller).setOnClickListener {
                webView.evaluateJavascript("window.__jhPicker && __jhPicker.smaller()", null)
            }
            bar.findViewById<View>(R.id.pickerBigger).setOnClickListener {
                webView.evaluateJavascript("window.__jhPicker && __jhPicker.bigger()", null)
            }
            bar.findViewById<View>(R.id.pickerHide).setOnClickListener { hidePicked() }
            constraintLayout.addView(bar)
        }
    }

    private fun startPicker() {
        if (!webView.settings.javaScriptEnabled) {
            Snackbar.make(constraintLayout, R.string.picker_needs_js, Snackbar.LENGTH_LONG).show()
            return
        }
        if (urlBarLayout.currentMode == UrlBarLayout.UrlBarMode.SEARCH) {
            urlBarLayout.currentMode = UrlBarLayout.UrlBarMode.URL
        }
        val script = AssetLoader.loadAsset(resources, "picker.js")
        webView.evaluateJavascript("$script\n__jhPicker.start();", null)
        picking = true
        (pickerBar.layoutParams as ConstraintLayout.LayoutParams).apply {
            width = 0
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            // Away from the toolbar: at the foot normally, at the top in one-handed mode
            if (sharedPreferencesExt.reachModeEnabled) {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            } else {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.UNSET
            }
            pickerBar.layoutParams = this
        }
        showPicked("")
        pickerBar.isVisible = true
    }

    private fun stopPicker() {
        if (!picking) return
        picking = false
        pickerBar.isVisible = false
        webView.evaluateJavascript("window.__jhPicker && __jhPicker.stop()", null)
    }

    private fun showPicked(label: String) {
        pickerBar.findViewById<TextView>(R.id.pickerLabel).text =
            label.ifEmpty { getString(R.string.picker_hint) }
        listOf(R.id.pickerSmaller, R.id.pickerBigger, R.id.pickerHide).forEach { id ->
            pickerBar.findViewById<View>(id).apply {
                isEnabled = label.isNotEmpty()
                alpha = if (isEnabled) 1f else 0.4f
            }
        }
    }

    override fun onElementPicked(label: String) {
        if (picking) showPicked(label)
    }

    override fun onJumpStopped(url: String, newTab: Boolean) {
        val host = Uri.parse(url).host ?: url
        Snackbar.make(
            constraintLayout,
            getString(R.string.jump_stopped, host),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.jump_go_anyway) {
            if (newTab) {
                openInNewTab(this, url, incognito)
            } else {
                webView.jumpsAllowedUntil = SystemClock.elapsedRealtime() + 15_000
                webView.loadUrl(url)
            }
        }.show()
    }

    // The app's own video player (see video/): a floating "Video" button
    // while a video plays, and the player over any full-screen video.
    private var player: VideoPlayerView? = null
    private var autoOpenedFor: String? = null

    /** The video is stretched over the page in place of full screen (see [stretchVideo]). */
    private var videoStretched = false
    private var lastTouch: Pair<Float, Float>? = null
    private val hideVideoButton = Runnable { videoButton.isVisible = false }

    private val videoButton: View by lazy {
        layoutInflater.inflate(R.layout.video_button, constraintLayout, false).also { b ->
            b.setOnClickListener { openPlayer() }
            constraintLayout.addView(b)
            (b.layoutParams as ConstraintLayout.LayoutParams).apply {
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                if (sharedPreferencesExt.reachModeEnabled) {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                } else {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
                b.layoutParams = this
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::webView.isInitialized && webView.isShown) {
            val at = IntArray(2).also { webView.getLocationOnScreen(it) }
            lastTouch = (ev.rawX - at[0]) to (ev.rawY - at[1])
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onVideoState(state: VideoState) {
        // Videos the user starts open straight in the player (silent previews don't)
        val key = state.src.ifEmpty { "stream:" + state.title }
        if (sharedPreferencesExt.videoAutoOpen && state.playing && state.gesture && !state.muted &&
            !state.fullscreen && customView == null && !videoStretched && autoOpenedFor != key
        ) {
            autoOpenedFor = key
            webView.video.send("enter")
        }

        if ((customView != null || videoStretched) && state.fullscreen) {
            showPlayer().update(state)
            // Wide videos turn the screen sideways, as a phone's own player does
            if (state.videoW > state.videoH && state.videoH > 0 &&
                requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else if (!state.fullscreen && !videoStretched) {
            player?.let { removePlayer() }
        }

        cancelVideoButtonHide()
        if (state.playing && !state.fullscreen && customView == null && !videoStretched && state.w >= 200) {
            videoButton.isVisible = true
        } else if (videoButton.isVisible) {
            videoButton.postDelayed(hideVideoButton, if (state.fullscreen) 0 else 4000)
        }
    }

    private fun cancelVideoButtonHide() {
        if (::webView.isInitialized) videoButton.removeCallbacks(hideVideoButton)
    }

    /** "Video" button: arm the video's frame, then tap the video for it (full screen needs a tap). */
    private fun openPlayer() {
        val s = webView.video.state ?: return
        webView.video.send("arm")
        val point = if (s.top && s.viewW > 0) {
            val scale = webView.width.toFloat() / s.viewW
            (s.x + s.w / 2f) * scale to (s.y + s.h / 2f) * scale
        } else {
            lastTouch
        }
        point?.let { (x, y) ->
            val t = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 40).forEach { (action, at) ->
                MotionEvent.obtain(t, at, action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                    webView.dispatchTouchEvent(this)
                    recycle()
                }
            }
        }
        videoButton.postDelayed({
            if (customView == null) Toast.makeText(this, R.string.vp_tap_video, Toast.LENGTH_LONG).show()
        }, 1500)
    }

    private fun showPlayer(): VideoPlayerView {
        player?.let { return it }
        val p = layoutInflater.inflate(R.layout.video_player, null) as VideoPlayerView
        p.host = object : VideoPlayerView.Host {
            override fun send(cmd: String, vararg args: Pair<String, Any>) =
                webView.video.send(cmd, *args)

            override fun download(state: VideoState) = downloadVideo(state)

            override fun pictureInPicture(state: VideoState) = enterPip(state)

            override fun close() {
                if (videoStretched) {
                    unstretchVideo()
                    return
                }
                webView.video.send("exit")
                p.postDelayed({ if (customView != null) onHideCustomView() }, 700)
            }
        }
        addContentView(
            p, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        p.isVisible = !isInPictureInPictureMode
        player = p
        return p
    }

    /**
     * The web engine leaves full screen when the app shrinks into the small
     * video window. The page then stretches the video over itself instead,
     * with the app's bars hidden, so the window (and the app on return)
     * still shows only the video.
     */
    private fun stretchVideo() {
        videoStretched = true
        appBarLayout.isVisible = false
        setImmersiveMode(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView.video.send("stretch")
    }

    private fun unstretchVideo() {
        if (!videoStretched) return
        videoStretched = false
        webView.video.send("unstretch")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode(false)
        appBarLayout.isVisible = !isFullscreenPwa
        webView.requestFocus()
        removePlayer()
    }

    private fun removePlayer() {
        val p = player ?: return
        (p.parent as? ViewGroup)?.removeView(p)
        player = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun downloadVideo(state: VideoState) {
        if (state.kind == "stream") {
            downloadStream(state)
            return
        }
        if (state.kind != "file" || state.src.isEmpty()) {
            // Nothing the browser can save itself: ask yt-dlp about the page
            downloadWithYtDlp(state.page.ifEmpty { webView.url })
            return
        }
        val guessed = UrlUtils.guessFileName(state.src, null, null)
        val ext = guessed.substringAfterLast('.', "").lowercase()
        val name = if (ext in VIDEO_EXTENSIONS && state.title.isNotBlank()) {
            state.title.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(80) + "." + ext
        } else {
            guessed
        }
        fetchFile(state.src, webView.settings.userAgentString, name)
        Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
    }

    private fun enterPip(state: VideoState) {
        val (w, h) = if (state.videoW > 0 && state.videoH > 0) state.videoW to state.videoH else 16 to 9
        val ratio = (w.toFloat() / h).coerceIn(1 / 2.39f, 2.39f)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        player?.isVisible = !isInPictureInPictureMode
        if (!isInPictureInPictureMode) player?.poke()
    }

    override fun onPageNavigated() {
        autoOpenedFor = null
        unstretchVideo()
        if (::webView.isInitialized) videoButton.isVisible = false
        if (picking) stopPicker()
    }

    private fun hidePicked() {
        webView.evaluateJavascript("window.__jhPicker ? __jhPicker.selector() : ''") { result ->
            val selector = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")
            val rule = if (selector.isEmpty()) null else ElementRules.add(this, webView.url, selector)
            stopPicker()
            if (rule == null) {
                Snackbar.make(constraintLayout, R.string.picker_failed, Snackbar.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            webView.evaluateJavascript(JsElementHide.SCRIPT, null)
            Snackbar.make(
                constraintLayout,
                getString(R.string.picker_hidden, ElementRules.site(rule)),
                Snackbar.LENGTH_LONG
            ).setAction(R.string.picker_undo) {
                ElementRules.remove(this, rule)
                webView.evaluateJavascript(JsElementHide.SCRIPT, null)
            }.show()
        }
    }

    private fun showSearch() {
        urlBarLayout.currentMode = UrlBarLayout.UrlBarMode.SEARCH
    }

    private fun shareUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, url)
        if (sharedPreferencesExt.advancedShareEnabled && url == webView.url) {
            val file = File(cacheDir, System.currentTimeMillis().toString() + ".png")
            try {
                FileOutputStream(file).use { out ->
                    val bm = webView.snap
                    bm.compress(Bitmap.CompressFormat.PNG, 70, out)
                    out.flush()
                    out.close()
                    intent.putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            this,
                            "${application.packageName}.fileprovider",
                            file
                        )
                    )
                    intent.type = "image/png"
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: IOException) {
                Log.e(TAG, "${e.message}", e)
            }
        } else {
            intent.type = "text/plain"
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }


    private fun setAsFavorite(title: String, url: String) {
        val color = urlIcon?.takeUnless { it.isRecycled }?.let { bitmap ->
            UiUtils.getColor(bitmap, false).takeUnless { it == Color.TRANSPARENT }
        } ?: ContextCompat.getColor(this, R.color.holo_blue)
        favoritesViewModel.insert(title, url, color)
        Snackbar.make(
            constraintLayout, getString(R.string.favorite_added),
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun downloadFileAsk(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val fileName = UrlUtils.guessFileName(url, contentDisposition, mimeType)
        AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setMessage(getString(R.string.download_message, fileName))
            .setPositiveButton(
                getString(R.string.download_positive)
            ) { _: DialogInterface?, _: Int -> fetchFile(url, userAgent, fileName) }
            .setNegativeButton(
                getString(R.string.dismiss)
            ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
            .show()
    }

    /** A streamed video: the playlists its page loaded are pieced back together. */
    private fun downloadStream(state: VideoState) {
        val streams = (webView.streams() + listOfNotNull(state.src.takeIf { it.isNotEmpty() })).distinct()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.vp_stream_no_download, Toast.LENGTH_LONG).show()
            return
        }
        if (streams.isEmpty()) {
            // The page feeds the player itself (YouTube): ask yt-dlp
            downloadWithYtDlp(state.page.ifEmpty { webView.url })
            return
        }
        askForNotifications()
        val name = state.title.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(80).ifEmpty { "video" }
        DownloadService.startStream(
            this, streams, state.duration, name,
            webView.settings.userAgentString,
            state.page.ifEmpty { webView.url },
            incognito,
        )
        Toast.makeText(this, getString(R.string.download_started, "$name.mp4"), Toast.LENGTH_SHORT).show()
    }

    /** Set while yt-dlp looks at a page; leaving the browser stops it. */
    private var ytdlpLookup: AtomicBoolean? = null

    /**
     * Asks yt-dlp which versions of the video on [page] exist, then lets
     * the user pick a size. Runs in the browser, since the list has to
     * appear right after.
     */
    private fun downloadWithYtDlp(page: String?) {
        if (page.isNullOrEmpty() || !(page.startsWith("https://") || page.startsWith("http://"))) {
            Toast.makeText(this, R.string.ytdlp_no_video, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.vp_stream_no_download, Toast.LENGTH_LONG).show()
            return
        }
        if (ytdlpLookup != null) return
        askForNotifications()
        val cancelled = AtomicBoolean(false)
        ytdlpLookup = cancelled
        val runtime = YtDlpRuntime(this)
        val cookies = File(cacheDir, "ytdlp-cookies-${System.nanoTime()}.txt")
        val haveCookies = writeCookies(
            cookies, page,
            if (incognito) null else CookieManager.getInstance().getCookie(page),
            incognito,
        )
        val dialog = AlertDialog.Builder(this)
            .setMessage(if (runtime.needsUnpacking) R.string.ytdlp_getting_ready else R.string.ytdlp_finding)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .show()
        val private = incognito
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    YtDlpFinder(runtime).find(page, cookies.takeIf { haveCookies }, cancelled::get)
                } finally {
                    cookies.delete()
                }
            }
            ytdlpLookup = null
            runCatching { dialog.dismiss() }
            if (isFinishing || isDestroyed) return@launch
            when (found) {
                is YtDlpFinder.Found.Cancelled -> {}
                is YtDlpFinder.Found.Failed ->
                    Toast.makeText(this@MainActivity, found.problem.message, Toast.LENGTH_LONG).show()

                is YtDlpFinder.Found.Ok -> showQualities(found.info, page, private)
            }
        }
    }

    private fun showQualities(info: YtDlpInfo, page: String, private: Boolean) {
        if (info.isLive) {
            Toast.makeText(this, R.string.ytdlp_live, Toast.LENGTH_LONG).show()
            return
        }
        val list = options(info, Transcode::encodable, CodecSupport::playable)
        when (list.size) {
            0 -> Toast.makeText(this, R.string.ytdlp_no_video, Toast.LENGTH_LONG).show()
            1 -> chooseQuality(info, list.first(), page, private)
            else -> {
                val sheet = BottomSheetDialog(this)
                val view = layoutInflater.inflate(R.layout.sheet_qualities, null)
                val rows = view.findViewById<LinearLayout>(R.id.qualityList)
                list.forEach { option ->
                    val row = layoutInflater.inflate(R.layout.item_quality, rows, false) as TextView
                    val label = if (option.video == null) getString(R.string.ytdlp_sound_only) else option.label
                    row.text = option.size?.let { "$label  ·  ${Formatter.formatShortFileSize(this, it)}" } ?: label
                    row.setOnClickListener {
                        sheet.dismiss()
                        chooseQuality(info, option, page, private)
                    }
                    rows.addView(row)
                }
                sheet.setContentView(view)
                sheet.show()
            }
        }
    }

    private fun chooseQuality(info: YtDlpInfo, option: Option, page: String, private: Boolean) {
        val start = {
            val job = YtDlpJob(info.page.ifEmpty { page }, info.title, option, private, info.duration)
            DownloadService.startYtDlp(this, job)
            val ext = if (option.output == Output.AUDIO) option.audio?.ext else option.video?.ext
            val name = fileName(info.title, option.output, ext)
            Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
        }
        if (option.output != Output.CONVERT) {
            start()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ytdlp_convert_title)
            .setMessage(R.string.ytdlp_convert_text)
            .setPositiveButton(R.string.ytdlp_convert) { _, _ -> start() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Download progress shows as a notification; ask once if needed. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun fetchFile(url: String?, userAgent: String?, fileName: String) {
        // Fast downloads: several parts at once, straight into Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sharedPreferencesExt.fastDownloads &&
            url != null && (url.startsWith("https://") || url.startsWith("http://"))
        ) {
            askForNotifications()
            DownloadService.start(
                this, url, fileName,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    MimeTypeMap.getFileExtensionFromUrl(url)
                ),
                userAgent,
                CookieManager.getInstance().getCookie(url)?.takeUnless { it.isEmpty() },
                webView.url,
            )
            Snackbar.make(
                constraintLayout, getString(R.string.download_started, fileName), Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val request = try {
            DownloadManager.Request(Uri.parse(url))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot download non http or https scheme")
            return
        }

        // Let this downloaded file be scanned by MediaScanner - so that it can
        // show up in Gallery app, for example.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("Deprecation")
            request.allowScanningByMediaScanner()
        }
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        request.setMimeType(
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(url)
            )
        )
        userAgent?.let {
            request.addRequestHeader("User-Agent", it)
        }
        CookieManager.getInstance().getCookie(url)?.takeUnless { it.isEmpty() }?.let {
            request.addRequestHeader("Cookie", it)
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
    }

    override fun showSheetMenu(url: String, shouldAllowDownload: Boolean) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, LinearLayout(this))
        val tabLayout = view.findViewById<View>(R.id.sheetNewTabLayout)
        val shareLayout = view.findViewById<View>(R.id.sheetShareLayout)
        val favouriteLayout = view.findViewById<View>(R.id.sheetFavouriteLayout)
        val downloadLayout = view.findViewById<View>(R.id.sheetDownloadLayout)
        tabLayout.setOnClickListener {
            openInNewTab(this, url, incognito)
            sheet.dismiss()
        }
        shareLayout.setOnClickListener {
            shareUrl(url)
            sheet.dismiss()
        }
        favouriteLayout.setOnClickListener {
            setAsFavorite(url, url)
            sheet.dismiss()
        }
        if (shouldAllowDownload) {
            downloadLayout.setOnClickListener {
                downloadFileAsk(url, webView.settings.userAgentString, null, null, 0)
                sheet.dismiss()
            }
            downloadLayout.visibility = View.VISIBLE
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /*
     * This is called only if GeolocationPermissions doesn't have an explicit entry for @origin
     */
    override fun showLocationDialog(origin: String, callback: GeolocationPermissions.Callback) {
        locationDialogCallback = {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_dialog_title)
                .setMessage(getString(R.string.location_dialog_message, origin))
                .setPositiveButton(R.string.location_dialog_allow) { _, _ ->
                    callback(origin, true, true)
                }
                .setNegativeButton(R.string.location_dialog_block) { _, _ ->
                    callback(origin, false, true)
                }
                .setNeutralButton(R.string.location_dialog_cancel) { _, _ ->
                    callback(origin, false, false)
                }
                .setOnCancelListener {
                    callback(origin, false, false)
                }
                .create()
                .show()
        }

        if (!permissionsUtils.locationPermissionsGranted()) {
            locationPermissionRequest.launch(PermissionsUtils.locationPermissions)
        } else {
            locationDialogCallback()
        }
    }

    override fun onFaviconLoaded(favicon: Bitmap?) {
        favicon?.let {
            if (it.isRecycled) {
                return
            }
            urlIcon = it.config?.let { config ->
                it.copy(config, true)
            }
            updateTaskDescription()
            urlIcon?.let { icon -> TabSnapshots.saveIcon(this, taskId, icon) }
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    private fun updateTaskDescription() {
        setTaskDescription(
            @Suppress("Deprecation")
            TaskDescription(
                webView.title,
                urlIcon, Color.WHITE
            )
        )
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback) {
        customView?.let {
            callback.onCustomViewHidden()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customView = view
        fullScreenCallback = callback
        setImmersiveMode(true)
        customView?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
        addContentView(
            customView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        appBarLayout.visibility = View.GONE
        webView.visibility = View.GONE
    }

    override fun onHideCustomView() {
        // In the small window the player stays: the video is stretched instead
        val stretch = isInPictureInPictureMode && customView != null
        if (!stretch) removePlayer()
        val customView = customView ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode(false)
        appBarLayout.isVisible = !isFullscreenPwa
        webView.visibility = View.VISIBLE
        val viewGroup = customView.parent as ViewGroup
        viewGroup.removeView(customView)
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
        this.customView = null
        // Otherwise the address bar takes the focus and opens the keyboard
        webView.requestFocus()
        if (stretch) stretchVideo()
    }

    private fun addShortcut() {
        val shortcutInfo = buildShortcutInfo()
        shortcutManager.requestPinShortcut(shortcutInfo, null)
    }

    private fun buildShortcutInfo(): ShortcutInfo {
        val id = pwaManifest?.id ?: System.currentTimeMillis().toString()
        val shortName = pwaManifest?.shortName ?: webView.title.toString()
        val intent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse(pwaManifest?.startUrl ?: webView.url)
            action = Intent.ACTION_MAIN
            putExtra(IntentUtils.EXTRA_SHORTCUT_ID, id)
            putExtra(IntentUtils.EXTRA_SHORTCUT_NAME, shortName)
            pwaManifest?.let {
                putExtra(MANIFEST_DISPLAY, it.display)
                putExtra(MANIFEST_THEME_COLOR, it.themeColor)
            }
        }
        val launcherIcon = urlIcon?.let {
            Icon.createWithBitmap(UiUtils.getShortcutIcon(it, Color.WHITE))
        } ?: Icon.createWithResource(this, R.mipmap.ic_launcher)
        return ShortcutInfo.Builder(this, id).apply {
            setShortLabel(shortName)
            setIcon(launcherIcon)
            setIntent(intent)
            pwaManifest?.let {
                setLongLabel(it.name)
            }
        }.build()
    }

    override fun updateHistory(title: String, url: String) {
        historyViewModel.insertOrUpdate(title, url)
        // Name the tab (in recent apps and the tab screen) even when the site has no icon
        runOnUiThread { updateTaskDescription() }
    }

    override fun replaceHistory(title: String, url: String, newUrl: String) {
        historyViewModel.replace(title, url, newUrl)
    }

    override fun setPwaManifest(manifest: PwaManifest?) {
        pwaManifest = manifest
        if (manifest == null) return
        // find and update pwa shortcut
        lifecycleScope.launch {
            val pinnedShortcuts = shortcutManager.pinnedShortcuts
            val index = pinnedShortcuts.indexOfFirst { it.id == manifest.id }
            if (index == -1) return@launch
            pinnedShortcuts[index] = buildShortcutInfo()
            shortcutManager.updateShortcuts(pinnedShortcuts)
        }
    }

    override fun setStatusBarColor(color: String) {
        if (!isFullscreenPwa) return
        runCatching {
            val color = color.toColorInt()
            val isColorLight = UiUtils.isColorLight(color)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = isColorLight
                isAppearanceLightNavigationBars = isColorLight
            }
            window.decorView.setBackgroundColor(color)
        }
    }

    override fun webRequestPermissions(
        permissions: Array<String>,
        cb: ((granted: Array<String>) -> Unit)
    ) {
        webRequestPermissions = cb
        webRequestPermissionsLauncher.launch(permissions)
    }

    override fun webProtectedMedia(origin: String, cb: ((granted: Boolean) -> Unit)) {
        AlertDialog.Builder(this)
            .setTitle(origin)
            .setMessage(R.string.protected_media_dialog_message)
            .setPositiveButton(R.string.protected_media_dialog_grant) { _, _ ->
                cb(true)
            }.setNegativeButton(R.string.protected_media_dialog_deny) { _, _ ->
                cb(false)
            }.show()
    }

    override fun onWebShare(value: WebShare) {
        val intent = buildShareIntent(*value.files.toTypedArray())
        intent.putExtra(Intent.EXTRA_TITLE, value.title)

        buildString {
            value.text?.let { appendLine(it) }
            value.url?.let { appendLine(it) }
        }.trimEnd().takeUnless { it.isEmpty() }?.let {
            intent.putExtra(Intent.EXTRA_TEXT, it)
        }

        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun setImmersiveMode(enable: Boolean) {
        val decorView = window.decorView

        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("Deprecation")
                decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        )
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                @Suppress("Deprecation")
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(decorView) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = if (enable) systemBarsInsets.top else 0,
                bottom = if (enable) systemBarsInsets.bottom else 0,
                left = if (enable) systemBarsInsets.left else 0,
                right = if (enable) systemBarsInsets.right else 0
            )
            insets
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "key_reach_mode" -> setUiMode()
        }
    }

    private fun setUiMode() {
        // Now you don't see it
        constraintLayout.alpha = 0f
        // Magic happens
        changeUiMode(sharedPreferencesExt.reachModeEnabled)
        // Now you see it
        constraintLayout.alpha = 1f
    }

    private fun changeUiMode(isReachMode: Boolean) {
        appBarLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> ConstraintLayout.LayoutParams.PARENT_ID
            }
            bottomToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.PARENT_ID
                false -> ConstraintLayout.LayoutParams.UNSET
            }
        }

        webView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> ConstraintLayout.LayoutParams.PARENT_ID
            }
            bottomToTop = when (isReachMode) {
                true -> R.id.appBarLayout
                false -> ConstraintLayout.LayoutParams.UNSET
            }
            topToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> R.id.appBarLayout
            }
            topToTop = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.PARENT_ID
                false -> ConstraintLayout.LayoutParams.UNSET
            }
        }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi")

        private val TAG = MainActivity::class.java.simpleName
        private const val MANIFEST_DISPLAY = "manifest_display"
        private const val MANIFEST_THEME_COLOR = "manifest_theme_color"
        private val ALLOWED_FULLSCREEN_PWA_VALUES = arrayOf("fullscreen", "standalone")
    }
}
