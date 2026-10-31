package com.servi.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * SERVI Browser
 *
 * Privacy behaviour:
 * - No application history database or history screen is created.
 * - WebView back/forward navigation exists only in the current in-memory tab.
 * - Password/form saving and WebView backup are disabled.
 * - Cookies, Web Storage and WebView cache are removed on startup, when changing
 *   privacy mode, when the user clears session data, and when the activity exits.
 * - A website may still use a temporary sign-in session while this app is open.
 *   That session is intentionally removed when the app exits or is next launched.
 */
class MainActivity : Activity() {

    companion object {
        private const val HOME_URL = "https://www.google.com"
        private const val DEFAULT_SEARCH_ENGINE = "Google"
        private const val MAX_TABS = 12
        private const val DARK = 0xFF07101E.toInt()
        private const val PANEL = 0xFF101B2C.toInt()
        private const val PANEL_LIGHT = 0xFF17263B.toInt()
        private const val BLUE = 0xFF2878FF.toInt()
        private const val CYAN = 0xFF35C8FF.toInt()
        private const val WHITE = 0xFFF4F7FC.toInt()
        private const val MUTED = 0xFF8FA2BC.toInt()
        private const val GREEN = 0xFF4BE0AD.toInt()

        // Lightweight built-in blocklist for common advertising and analytics endpoints.
        // This is a convenience filter, not a complete content blocker.
        private val TRACKER_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "google-analytics.com", "analytics.google.com",
            "adnxs.com", "adsrvr.org", "criteo.com", "taboola.com",
            "outbrain.com", "hotjar.com", "segment.io", "mixpanel.com",
            "amplitude.com", "clarity.ms", "scorecardresearch.com"
        )

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private class BrowserTab(
        val webView: WebView,
        var title: String = "New tab",
        var url: String = HOME_URL
    )

    private val handler = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<BrowserTab>()

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var addressField: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var modeBadge: TextView
    private lateinit var tabCount: TextView
    private lateinit var pageTitle: TextView
    private lateinit var bottomPrivateButton: TextView
    private var splashView: View? = null

    private var activeTabIndex = 0
    private var isIncognito = false
    private var desktopMode = false
    private var searchEngine = DEFAULT_SEARCH_ENGINE
    private var suppressAddressCallback = false
    private var trackerShieldEnabled = true
    private var readerModeEnabled = false
    private var dataSaverEnabled = false
    private var pageTextZoom = 100
    private val blockedTrackerRequests = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = DARK
        window.navigationBarColor = DARK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContentView(buildBrowserUi())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        showSplashScreen()

        // Clear any data left by a previously terminated process before the first page loads.
        clearTemporaryBrowserData {
            if (tabs.isEmpty()) createTab(HOME_URL)
        }
    }

    private fun buildBrowserUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(DARK) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DARK)
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // Brand header.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(DARK)
        }
        val logo = ServiLogoView(this).apply {
            contentDescription = "Animated SERVI Browser logo"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))

        val brandStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val brand = text("SERVI", 19f, WHITE, true).apply { letterSpacing = 0.12f }
        val brandSub = text("BROWSER  •  EDUSPACE EDITION", 9f, MUTED, true).apply {
            letterSpacing = 0.08f
        }
        pageTitle = text("PRIVATE BROWSING", 9f, MUTED, false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        brandStack.addView(brand)
        brandStack.addView(brandSub)
        brandStack.addView(pageTitle)
        header.addView(brandStack, LinearLayout.LayoutParams(0, -2, 1f))

        modeBadge = text("PRIVATE BY DEFAULT", 9f, CYAN, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(PANEL_LIGHT, 24f, BLUE)
            letterSpacing = 0.05f
        }
        modeBadge.setOnClickListener { showPrivacyDashboard() }
        modeBadge.contentDescription = "Open privacy dashboard"
        header.addView(modeBadge)
        column.addView(header)

        // Address and navigation controls.
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(8))
            setBackgroundColor(DARK)
        }
        addressRow.addView(iconButton("‹", "Go back") { goBack() }, LinearLayout.LayoutParams(dp(36), dp(42)))
        addressRow.addView(iconButton("›", "Go forward") { goForward() }, LinearLayout.LayoutParams(dp(36), dp(42)))
        addressRow.addView(iconButton("↻", "Reload page") { reloadPage() }, LinearLayout.LayoutParams(dp(36), dp(42)))

        addressField = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(WHITE)
            setHintTextColor(MUTED)
            hint = "Search or enter website"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(PANEL, 22f, 0xFF20334D.toInt())
            setPadding(dp(14), dp(2), dp(10), dp(2))
            isSaveEnabled = false
            setSelectAllOnFocus(false)
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_DOWN } == true
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                    navigateFromAddressBar()
                    true
                } else false
            }
        }
        val addressParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(2), 0, dp(5), 0)
        }
        addressRow.addView(addressField, addressParams)
        addressRow.addView(actionPill("GO") { navigateFromAddressBar() }, LinearLayout.LayoutParams(dp(48), dp(40)))
        column.addView(addressRow)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(PANEL)
            visibility = View.GONE
        }
        column.addView(progressBar, LinearLayout.LayoutParams(-1, dp(2)))

        webContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        column.addView(webContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom browser dock.
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(7), dp(5), dp(7))
            background = rounded(PANEL, 18f)
        }
        val dockParams = LinearLayout.LayoutParams(-1, dp(58)).apply {
            setMargins(dp(8), dp(7), dp(8), dp(8))
        }
        dock.addView(dockButton("⌂", "Home") { openHome() }, weightedDockParams())
        dock.addView(dockButton("＋", "New tab") { createTab(HOME_URL) }, weightedDockParams())
        val tabsButton = dockButton("▢", "Tabs") { showTabsDialog() }
        tabCount = text("1", 9f, CYAN, true).apply {
            gravity = Gravity.CENTER
            background = rounded(PANEL_LIGHT, 20f)
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }
        val tabsStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        tabsStack.addView(tabsButton)
        tabsStack.addView(tabCount)
        dock.addView(tabsStack, weightedDockParams())
        bottomPrivateButton = dockButton("◉", "Incognito") { toggleIncognito() }
        dock.addView(bottomPrivateButton, weightedDockParams())
        dock.addView(dockButton("⋯", "More") { showMoreMenu() }, weightedDockParams())
        column.addView(dock, dockParams)

        return root
    }

    private fun showSplashScreen() {
        val overlay = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_splash)
            alpha = 1f
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
        }
        val logo = ServiLogoView(this).apply {
            contentDescription = "SERVI animated startup logo"
        }
        center.addView(logo, LinearLayout.LayoutParams(dp(124), dp(124)))
        val title = text("SERVI", 32f, WHITE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            setPadding(0, dp(15), 0, 0)
        }
        center.addView(title)
        title.alpha = 0f
        title.translationY = dp(10).toFloat()
        title.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(620).start()
        center.addView(text("B R O W S E R", 11f, CYAN, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(20))
        })
        val loading = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(CYAN) }
        center.addView(loading, LinearLayout.LayoutParams(dp(34), dp(34)))
        center.addView(text("PRIVATE BY DEFAULT", 10f, WHITE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.10f
            setPadding(0, dp(18), 0, dp(5))
        })
        center.addView(text("NO SAVED HISTORY  •  NO SAVED PASSWORDS", 8f, MUTED, false).apply {
            gravity = Gravity.CENTER
        })
        overlay.addView(center, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        splashView = overlay

        handler.postDelayed({
            val splash = splashView ?: return@postDelayed
            splash.animate().alpha(0f).setDuration(320).withEndAction {
                (splash.parent as? ViewGroup)?.removeView(splash)
                splashView = null
            }.start()
        }, 1450L)
    }

    private fun createTab(url: String) {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(this, "SERVI supports up to $MAX_TABS open tabs to protect memory", Toast.LENGTH_SHORT).show()
            return
        }
        val webView = WebView(this).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        configureWebView(webView)
        val tab = BrowserTab(webView, "Loading…", url)
        tabs.add(tab)
        switchToTab(tabs.lastIndex)
        webView.loadUrl(url)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = !isIncognito
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.loadsImagesAutomatically = !dataSaverEnabled
        settings.textZoom = pageTextZoom
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.setGeolocationEnabled(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.saveFormData = false
        settings.savePassword = false
        settings.safeBrowsingEnabled = true
        settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else WebSettings.getDefaultUserAgent(this)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val host = request?.url?.host
                if (trackerShieldEnabled && request != null && !request.isForMainFrame && isTrackerHost(host)) {
                    blockedTrackerRequests.incrementAndGet()
                    runOnUiThread { if (!isFinishing) updateBrowserChrome() }
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                if (target.startsWith("http://") || target.startsWith("https://")) return false
                return openExternalUri(target)
            }

            @Deprecated("Deprecated by Android; retained for older WebView callbacks")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return false
                if (target.startsWith("http://") || target.startsWith("https://")) return false
                return openExternalUri(target)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val tab = tabFor(webView) ?: return
                if (!url.isNullOrBlank()) tab.url = url
                progressBar.progress = 3
                progressBar.visibility = View.VISIBLE
                if (tabs.getOrNull(activeTabIndex) === tab) updateAddressField(tab.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val tab = tabFor(webView) ?: return
                tab.url = webView.url ?: url ?: tab.url
                tab.title = webView.title?.takeIf { it.isNotBlank() } ?: tab.title
                // Do not leave any HTML form values in WebView's form-data helper.
                webView.clearFormData()
                if (readerModeEnabled) applyReaderStyle(webView, true)
                if (tabs.getOrNull(activeTabIndex) === tab) updateBrowserChrome()
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                val crashedTab = tabs.firstOrNull { it.webView === view } ?: return true
                val crashedIndex = tabs.indexOf(crashedTab)
                tabs.remove(crashedTab)
                (view?.parent as? ViewGroup)?.removeView(view)
                try { view?.destroy() } catch (_: Exception) { }
                if (tabs.isEmpty()) {
                    activeTabIndex = 0
                    createTab(HOME_URL)
                } else {
                    activeTabIndex = crashedIndex.coerceAtMost(tabs.lastIndex)
                    switchToTab(activeTabIndex)
                }
                Toast.makeText(this@MainActivity, "A page stopped unexpectedly. SERVI reopened your tabs safely.", Toast.LENGTH_LONG).show()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tabs.getOrNull(activeTabIndex)?.webView === webView) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val tab = tabFor(webView) ?: return
                if (!title.isNullOrBlank()) tab.title = title
                if (tabs.getOrNull(activeTabIndex) === tab) updateBrowserChrome()
            }
        }

        webView.setDownloadListener(DownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            startDownload(downloadUrl, userAgent, contentDisposition, mimeType)
        })
        webView.setBackgroundColor(Color.WHITE)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloading with SERVI Browser")
                setMimeType(mimeType ?: "application/octet-stream")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Could not start download: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        webContainer.removeAllViews()
        val selected = tabs[index].webView
        (selected.parent as? ViewGroup)?.removeView(selected)
        webContainer.addView(selected, FrameLayout.LayoutParams(-1, -1))
        if (readerModeEnabled) applyReaderStyle(selected, true)
        updateBrowserChrome()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val closing = tabs.removeAt(index)
        (closing.webView.parent as? ViewGroup)?.removeView(closing.webView)
        closing.webView.stopLoading()
        closing.webView.loadUrl("about:blank")
        closing.webView.clearHistory()
        closing.webView.clearCache(true)
        closing.webView.destroy()

        if (tabs.isEmpty()) {
            activeTabIndex = 0
            clearTemporaryBrowserData { createTab(HOME_URL) }
        } else {
            activeTabIndex = (index - 1).coerceIn(0, tabs.lastIndex)
            switchToTab(activeTabIndex)
        }
        updateBrowserChrome()
    }

    private fun destroyAllTabs() {
        webContainer.removeAllViews()
        tabs.forEach { tab ->
            try {
                tab.webView.stopLoading()
                tab.webView.loadUrl("about:blank")
                tab.webView.clearHistory()
                tab.webView.clearCache(true)
                tab.webView.destroy()
            } catch (_: Exception) { }
        }
        tabs.clear()
        activeTabIndex = 0
    }

    private fun navigateFromAddressBar() {
        val raw = addressField.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return
        val target = resolveAddress(raw)
        hideKeyboard()
        addressField.clearFocus()
        currentWebView()?.loadUrl(target)
    }

    private fun resolveAddress(input: String): String {
        if (input.matches(Regex("(?i)^https?://.*"))) return input
        if (input.matches(Regex("(?i)^[a-z][a-z0-9+.-]*:.*"))) return input
        val looksLikeHost = !input.contains(' ') && input.contains('.') &&
            !input.startsWith('.') && !input.endsWith('.')
        return if (looksLikeHost) "https://$input" else searchUrl(input)
    }

    private fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return when (searchEngine) {
            "Bing" -> "https://www.bing.com/search?q=$encoded"
            "DuckDuckGo" -> "https://duckduckgo.com/?q=$encoded"
            "Yahoo" -> "https://search.yahoo.com/search?p=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
    }

    private fun openExternalUri(target: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            true
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeTabIndex)
    private fun currentWebView(): WebView? = currentTab()?.webView
    private fun tabFor(webView: WebView): BrowserTab? = tabs.firstOrNull { it.webView === webView }

    private fun isTrackerHost(host: String?): Boolean {
        val value = host?.lowercase()?.trim('.') ?: return false
        return TRACKER_HOSTS.any { value == it || value.endsWith(".$it") }
    }

    private fun updateBrowserChrome() {
        val tab = currentTab()
        updateAddressField(tab?.webView?.url ?: tab?.url ?: "")
        pageTitle.text = tab?.title ?: "SERVI Browser"
        tabCount.text = tabs.size.toString()
        val shieldLabel = if (trackerShieldEnabled) "SHIELD ON" else "SHIELD OFF"
        val blocked = blockedTrackerRequests.get()
        modeBadge.text = if (isIncognito) "INCOGNITO · $blocked" else "$shieldLabel · $blocked"
        modeBadge.setTextColor(if (isIncognito) 0xFFD8C5FF.toInt() else if (trackerShieldEnabled) CYAN else MUTED)
        modeBadge.background = rounded(if (isIncognito) 0xFF251C3D.toInt() else PANEL_LIGHT, 24f,
            if (isIncognito) 0xFF8C65D8.toInt() else if (trackerShieldEnabled) BLUE else 0xFF53647A.toInt())
        bottomPrivateButton.text = if (isIncognito) "◈\nPrivate ON" else "◉\nIncognito"
        bottomPrivateButton.setTextColor(if (isIncognito) 0xFFD8C5FF.toInt() else WHITE)
    }

    private fun updateAddressField(value: String) {
        if (!::addressField.isInitialized) return
        if (addressField.hasFocus()) return
        suppressAddressCallback = true
        addressField.setText(value)
        addressField.setSelection(addressField.text?.length ?: 0)
        suppressAddressCallback = false
    }

    private fun openHome() {
        currentWebView()?.loadUrl(HOME_URL) ?: createTab(HOME_URL)
    }

    private fun reloadPage() {
        currentWebView()?.let { view ->
            if (view.url.isNullOrBlank()) view.loadUrl(HOME_URL) else view.reload()
        } ?: createTab(HOME_URL)
    }

    private fun goBack() {
        currentWebView()?.let { if (it.canGoBack()) it.goBack() }
    }

    private fun goForward() {
        currentWebView()?.let { if (it.canGoForward()) it.goForward() }
    }

    private fun toggleIncognito() {
        val currentUrl = currentWebView()?.url ?: currentTab()?.url ?: HOME_URL
        isIncognito = !isIncognito
        destroyAllTabs()
        readerModeEnabled = false
        blockedTrackerRequests.set(0)
        updateBrowserChrome()
        clearTemporaryBrowserData { createTab(currentUrl) }
        Toast.makeText(
            this,
            if (isIncognito) "Incognito enabled. Temporary site data will be cleared when you leave this mode." 
            else "Incognito disabled. A fresh private session has started.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun clearTemporaryBrowserData(onComplete: (() -> Unit)? = null) {
        try {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.removeAllCookies {
                try { cookies.flush() } catch (_: Exception) { }
                try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) { }
                try {
                    WebViewDatabase.getInstance(this).clearFormData()
                    WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                    WebViewDatabase.getInstance(this).clearUsernamePassword()
                } catch (_: Exception) { }
                runOnUiThread {
                    tabs.forEach { tab ->
                        try {
                            tab.webView.clearHistory()
                            tab.webView.clearCache(true)
                            tab.webView.clearFormData()
                            tab.webView.clearSslPreferences()
                        } catch (_: Exception) { }
                    }
                    onComplete?.invoke()
                }
            }
        } catch (_: Exception) {
            try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) { }
            onComplete?.invoke()
        }
    }

    private fun showTabsDialog() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { index, tab ->
            val title = tab.title.take(28)
            "${index + 1}. $title\n${tab.webView.url ?: tab.url}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open tabs (${tabs.size})")
            .setItems(labels) { _, which -> switchToTab(which) }
            .setNeutralButton("Close current tab") { _, _ -> closeTab(activeTabIndex) }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showMoreMenu() {
        val options = arrayOf(
            "Choose search engine",
            if (desktopMode) "Turn off desktop site" else "Request desktop site",
            "Find in page",
            if (readerModeEnabled) "Turn off focus reading view" else "Focus reading view",
            "Translate page to Hindi",
            if (trackerShieldEnabled) "Turn tracker shield off" else "Turn tracker shield on",
            "Privacy dashboard",
            "Clear temporary browser data",
            "Close all tabs and clear session",
            if (dataSaverEnabled) "Turn data saver off" else "Turn data saver on",
            "Page text size",
            "Copy current page link",
            "Open current page in another app",
            "Page and connection info",
            "About SERVI Browser"
        )
        AlertDialog.Builder(this)
            .setTitle("SERVI Browser tools")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSearchEngineDialog()
                    1 -> toggleDesktopMode()
                    2 -> showFindInPageDialog()
                    3 -> toggleReaderMode()
                    4 -> translateCurrentPageToHindi()
                    5 -> toggleTrackerShield()
                    6 -> showPrivacyDashboard()
                    7 -> clearTemporaryBrowserData {
                        Toast.makeText(this, "Temporary cookies, storage and cache cleared", Toast.LENGTH_SHORT).show()
                    }
                    8 -> closeAllTabsAndClearSession()
                    9 -> toggleDataSaver()
                    10 -> showTextZoomDialog()
                    11 -> copyCurrentUrl()
                    12 -> openCurrentPageExternally()
                    13 -> showSiteInfoDialog()
                    14 -> showAboutDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFindInPageDialog() {
        val input = EditText(this).apply {
            hint = "Find text on this page"
            setSingleLine(true)
            setTextColor(WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(PANEL, 12f, BLUE)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(input)
            .setPositiveButton("Find", null)
            .setNeutralButton("Clear highlights") { _, _ -> currentWebView()?.clearMatches() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    currentWebView()?.findAllAsync(query)
                    Toast.makeText(this, "Searching this page…", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    input.error = "Enter text to find"
                }
            }
        }
        dialog.show()
    }

    private fun toggleReaderMode() {
        val webView = currentWebView() ?: return
        readerModeEnabled = !readerModeEnabled
        if (readerModeEnabled) {
            applyReaderStyle(webView, true)
        } else {
            tabs.forEach { applyReaderStyle(it.webView, false) }
        }
        Toast.makeText(this, if (readerModeEnabled) "Focus reading view enabled" else "Focus reading view disabled", Toast.LENGTH_SHORT).show()
    }

    private fun applyReaderStyle(webView: WebView, enabled: Boolean) {
        val script = if (enabled) {
            """(function(){var id='servi-reader-style';if(document.getElementById(id))return;var s=document.createElement('style');s.id=id;s.textContent='html{background:#f4f1e9!important}body{max-width:820px!important;margin:32px auto!important;padding:28px!important;background:#fffdf7!important;color:#20242b!important;font-family:Georgia,serif!important;font-size:19px!important;line-height:1.8!important;box-shadow:0 4px 28px rgba(0,0,0,.12)!important}img,video,iframe{max-width:100%!important;height:auto!important}nav,header,footer,aside,[role=navigation],[role=banner],[role=complementary],.ads,.advertisement{display:none!important}p,li{font-size:1em!important;line-height:1.8!important}h1,h2,h3{line-height:1.3!important;color:#111827!important}';document.head.appendChild(s);})();"""
        } else {
            "(function(){var s=document.getElementById('servi-reader-style');if(s)s.remove();})();"
        }
        webView.evaluateJavascript(script, null)
    }

    private fun toggleDataSaver() {
        dataSaverEnabled = !dataSaverEnabled
        tabs.forEach { tab -> tab.webView.settings.loadsImagesAutomatically = !dataSaverEnabled }
        currentWebView()?.reload()
        Toast.makeText(this, if (dataSaverEnabled) "Data saver on: automatic images disabled" else "Data saver off: images enabled", Toast.LENGTH_SHORT).show()
    }

    private fun showTextZoomDialog() {
        val values = intArrayOf(85, 100, 115, 130)
        val labels = arrayOf("85% — Compact", "100% — Default", "115% — Large", "130% — Extra large")
        val selected = values.indexOf(pageTextZoom).coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle("Page text size")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                pageTextZoom = values[which]
                tabs.forEach { it.webView.settings.textZoom = pageTextZoom }
                dialog.dismiss()
                Toast.makeText(this, "Page text size set to ${pageTextZoom}%", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun translateCurrentPageToHindi() {
        val url = currentWebView()?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            Toast.makeText(this, "Open a webpage first", Toast.LENGTH_SHORT).show()
            return
        }
        val encoded = URLEncoder.encode(url, "UTF-8")
        currentWebView()?.loadUrl("https://translate.google.com/translate?sl=auto&tl=hi&u=$encoded")
    }

    private fun toggleTrackerShield() {
        trackerShieldEnabled = !trackerShieldEnabled
        currentWebView()?.reload()
        updateBrowserChrome()
        Toast.makeText(this, if (trackerShieldEnabled) "Tracker shield enabled" else "Tracker shield disabled", Toast.LENGTH_SHORT).show()
    }

    private fun showPrivacyDashboard() {
        val shieldState = if (trackerShieldEnabled) "ON" else "OFF"
        val mode = if (isIncognito) "Incognito session" else "Temporary private session"
        val dataSaver = if (dataSaverEnabled) "ON" else "OFF"
        val message = "Browsing mode: $mode\nTracker shield: $shieldState\nRequests blocked this session: ${blockedTrackerRequests.get()}\nData saver: $dataSaver\nOpen tabs: ${tabs.size}/$MAX_TABS\n\nSaved browsing history: No\nSaved passwords: No\nPersistent login sessions: No\n\nCookies and website storage are temporary and are cleared when you exit, switch modes, or start the next session. Websites and network providers may still see requests sent to them."
        AlertDialog.Builder(this)
            .setTitle("SERVI Privacy Dashboard")
            .setMessage(message)
            .setPositiveButton(if (trackerShieldEnabled) "Turn shield off" else "Turn shield on") { _, _ -> toggleTrackerShield() }
            .setNeutralButton("Clear session data") { _, _ ->
                clearTemporaryBrowserData {
                    blockedTrackerRequests.set(0)
                    updateBrowserChrome()
                    Toast.makeText(this, "Session data cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun closeAllTabsAndClearSession() {
        destroyAllTabs()
        readerModeEnabled = false
        clearTemporaryBrowserData {
            createTab(HOME_URL)
            Toast.makeText(this, "All tabs closed and session data cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSearchEngineDialog() {
        val engines = arrayOf("Google", "Bing", "DuckDuckGo", "Yahoo")
        val selected = engines.indexOf(searchEngine).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Default search engine")
            .setSingleChoiceItems(engines, selected) { dialog, which ->
                searchEngine = engines[which]
                dialog.dismiss()
                Toast.makeText(this, "$searchEngine selected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        val tab = currentTab() ?: return
        tab.webView.settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else WebSettings.getDefaultUserAgent(this)
        tab.webView.settings.useWideViewPort = desktopMode
        tab.webView.reload()
        Toast.makeText(this, if (desktopMode) "Desktop site requested" else "Mobile site requested", Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrentPage() {
        val url = currentWebView()?.url ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(share, "Share page"))
    }

    private fun copyCurrentUrl() {
        val url = currentWebView()?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            Toast.makeText(this, "No webpage link to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Page link", url))
        Toast.makeText(this, "Page link copied", Toast.LENGTH_SHORT).show()
    }

    private fun openCurrentPageExternally() {
        val url = currentWebView()?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            Toast.makeText(this, "No webpage to open", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSiteInfoDialog() {
        val uri = currentWebView()?.url?.let { Uri.parse(it) }
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
            Toast.makeText(this, "Open a website to view its connection information", Toast.LENGTH_SHORT).show()
            return
        }
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val details = buildString {
            append("Website: ${uri.host ?: "Unknown"}\n")
            append("Connection: ${if (secure) "HTTPS encrypted transport" else "HTTP — not encrypted"}\n")
            append("Tracker shield: ${if (trackerShieldEnabled) "On" else "Off"}\n")
            append("Third-party cookies: Blocked\n")
            append("Saved history: Disabled\n")
            append("Saved passwords/autofill: Disabled\n\n")
            append("HTTPS protects data in transit to this website, but it does not prove that the website itself is trustworthy.")
        }
        AlertDialog.Builder(this)
            .setTitle(if (secure) "Connection information" else "Connection warning")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("SERVI Browser")
            .setMessage(
                "A dark, privacy-first Android browser by the SERVI / EduSpace concept.\n\n" +
                    "Includes a lightweight tracker shield, focus reading view, find-in-page, Hindi translation, data saver, adjustable text size, tab management, and a privacy dashboard.\n\n" +
                    "Privacy by default: this app does not create a saved browsing-history list or store passwords. " +
                    "Website cookies and temporary site storage are cleared when the app exits, on the next launch, " +
                    "and when switching privacy modes. A site may still sign you in temporarily while the browser is open.\n\n" +
                    "Websites and network providers can still see activity sent to them. Incognito does not make you anonymous."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressField.windowToken, 0)
    }

    override fun onPause() {
        tabs.forEach { try { it.webView.onPause() } catch (_: Exception) { } }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { try { it.webView.onResume() } catch (_: Exception) { } }
    }

    @Deprecated("Deprecated by Android; retained for compatibility with the activity API")
    override fun onBackPressed() {
        val webView = currentWebView()
        if (addressField.hasFocus()) {
            addressField.clearFocus()
            hideKeyboard()
        } else if (webView?.canGoBack() == true) {
            webView.goBack()
        } else if (tabs.size > 1) {
            closeTab(activeTabIndex)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Exit SERVI Browser?")
                .setMessage("Your temporary cookies and site data will be cleared when you exit.")
                .setNegativeButton("Stay", null)
                .setPositiveButton("Exit") { _, _ -> finish() }
                .show()
        }
    }

    override fun onDestroy() {
        // Clear web session state when the Activity is actually finishing. If Android kills
        // the process without this callback, the next launch performs the same cleanup first.
        if (isFinishing) {
            try {
                tabs.forEach { tab ->
                    tab.webView.stopLoading()
                    tab.webView.clearHistory()
                    tab.webView.clearCache(true)
                    tab.webView.clearFormData()
                    tab.webView.destroy()
                }
                tabs.clear()
                clearTemporaryBrowserData()
            } catch (_: Exception) { }
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

    private fun iconButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            contentDescription = description
            textSize = 25f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { action() }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x332878FF),
                rounded(PANEL, 12f), null
            )
        }

    private fun actionPill(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = rounded(BLUE, 20f)
            setOnClickListener { action() }
        }

    private fun dockButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = "$label\n$description"
            textSize = 10f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            setOnClickListener { action() }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x332878FF), null, null
            )
        }

    private fun weightedDockParams() = LinearLayout.LayoutParams(0, -1, 1f)

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Animated SERVI browser emblem. The central symbol is an original three-ribbon
     * browser mark (no initials or letters); a thin luminous orbit rotates around it.
     */
    private class ServiLogoView(context: Context) : View(context) {
        private var rotation = 0f
        private val logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.servi_logo)
        private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 5200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
        }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.8f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val orbitBounds = RectF(-47f, -47f, 47f, 47f)
        private val logoBounds = RectF(-38f, -38f, 38f, 38f)

        init {
            // Software rendering keeps the soft glow predictable on varied Android devices.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!animator.isStarted) animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = minOf(width, height).toFloat()
            if (side <= 0f || logoBitmap == null) return

            val pulse = 0.985f + 0.015f * kotlin.math.sin(
                Math.toRadians(rotation.toDouble())
            ).toFloat()
            val save = canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.scale(side / 100f * pulse, side / 100f * pulse)

            backgroundPaint.shader = RadialGradient(
                0f, 0f, 52f,
                intArrayOf(0x5535C8FF, 0x222878FF, 0x0007101E),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(0f, 0f, 52f, backgroundPaint)
            backgroundPaint.shader = null

            // A slowly rotating cyan-blue orbit adds motion without rotating the emblem itself.
            canvas.save()
            canvas.rotate(rotation)
            orbitPaint.shader = SweepGradient(
                0f, 0f,
                intArrayOf(
                    0x0035C8FF, 0xFF35C8FF.toInt(),
                    0xFF2878FF.toInt(), 0x002878FF
                ),
                floatArrayOf(0f, 0.22f, 0.58f, 1f)
            )
            canvas.drawArc(orbitBounds, 4f, 302f, false, orbitPaint)
            orbitPaint.shader = null
            glowPaint.color = 0xFF8DEBFF.toInt()
            glowPaint.setShadowLayer(8f, 0f, 0f, 0xFF35C8FF.toInt())
            canvas.drawCircle(0f, -47f, 3.1f, glowPaint)
            glowPaint.clearShadowLayer()
            canvas.restore()

            // Draw the icon's symbol artwork; the bitmap contains no letters or wordmark.
            canvas.drawBitmap(logoBitmap, null, logoBounds, bitmapPaint)

            // Fine outside rim to make the icon legible against both dark and light pages.
            orbitPaint.color = 0x6635C8FF
            orbitPaint.strokeWidth = 0.9f
            canvas.drawCircle(0f, 0f, 40.5f, orbitPaint)

            canvas.restoreToCount(save)
        }
    }
}
