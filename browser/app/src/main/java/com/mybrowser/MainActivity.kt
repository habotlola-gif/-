package com.mybrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

private const val HOME = "https://duckduckgo.com/"
private const val SEARCH = "https://duckduckgo.com/?q="

private const val BG = 0xFF101114.toInt()
private const val BAR = 0xFF17181C.toInt()
private const val PILL = 0xFF22242A.toInt()
private const val TEXT = 0xFFE8EAED.toInt()
private const val SUBTLE = 0xFF9AA0A6.toInt()
private const val ACCENT = 0xFF8AB4FF.toInt()

class Tab(val web: WebView) {
    var title: String = "Новая вкладка"
    var url: String = ""
}

class MainActivity : Activity() {

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null

    private lateinit var root: FrameLayout
    private lateinit var webHolder: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var address: EditText
    private lateinit var tabsBtn: TextView
    private lateinit var tabsPanel: FrameLayout
    private lateinit var tabsList: LinearLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun ripple(content: android.graphics.drawable.Drawable?): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(Color.WHITE, 14))

    private fun button(label: String, size: Int = 22, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
            gravity = Gravity.CENTER
            background = ripple(null)
            isFocusable = false
            setOnClickListener { onClick() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieManager.getInstance().setAcceptCookie(true)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(BG)
            visibility = View.INVISIBLE
        }
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        webHolder = FrameLayout(this)
        column.addView(webHolder, LinearLayout.LayoutParams(-1, 0, 1f))

        column.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, dp(60)))

        buildTabsPanel()
        root.addView(tabsPanel, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        // Системные отступы (статус-бар / жестовая навигация)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        restoreTabs(intent?.data?.toString())
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        bar.addView(button("‹", 30) { goBackOrClose() }, LinearLayout.LayoutParams(dp(44), -1))

        address = EditText(this).apply {
            background = rounded(PILL, 20) // свой фон: никаких синих подчёркиваний
            setTextColor(TEXT)
            setHintTextColor(SUBTLE)
            hint = "Поиск или адрес"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine()
            setPadding(dp(16), 0, dp(16), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            highlightColor = 0x558AB4FF
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    load(v.text.toString())
                    v.clearFocus()
                    hideKeyboard(v)
                    true
                } else false
            }
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    (v as EditText).setText(current?.url.orEmpty())
                    v.selectAll()
                } else {
                    showHost()
                }
            }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, -1, 1f).apply {
            marginStart = dp(4); marginEnd = dp(8)
        })

        tabsBtn = TextView(this).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = ripple(GradientDrawable().apply {
                setStroke(dp(2), TEXT)
                cornerRadius = dp(8).toFloat()
            })
            setOnClickListener { toggleTabsPanel() }
        }
        bar.addView(tabsBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(6)
        })

        bar.addView(button("⋮", 24) { showMenu() }, LinearLayout.LayoutParams(dp(40), -1))
        return bar
    }

    private fun buildTabsPanel() {
        tabsPanel = FrameLayout(this).apply {
            setBackgroundColor(BG)
            visibility = View.GONE
            isClickable = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabsPanel.addView(col, FrameLayout.LayoutParams(-1, -1))

        val title = TextView(this).apply {
            text = "Вкладки"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        col.addView(title)

        tabsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(tabsList)
            setPadding(dp(12), 0, dp(12), 0)
            clipToPadding = false
        }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val add = TextView(this).apply {
            text = "+  Новая вкладка"
            setTextColor(BG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(ACCENT, 24)
            setOnClickListener {
                newTab(HOME)
                hideTabsPanel()
            }
        }
        col.addView(add, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(16), dp(8), dp(16), dp(12))
        })
    }

    private fun refreshTabsPanel() {
        tabsList.removeAllViews()
        for (t in tabs) {
            val selected = t === current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(rounded(if (selected) 0xFF2B2F3A.toInt() else PILL, 14))
                setPadding(dp(16), dp(10), dp(4), dp(10))
                setOnClickListener {
                    switchTo(t)
                    hideTabsPanel()
                }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = t.title.ifBlank { "Новая вкладка" }
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this).apply {
                text = hostOf(t.url)
                setTextColor(SUBTLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("✕", 18) {
                closeTab(t)
                refreshTabsPanel()
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            tabsList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun toggleTabsPanel() {
        if (tabsPanel.visibility == View.VISIBLE) hideTabsPanel() else {
            address.clearFocus()
            hideKeyboard(address)
            refreshTabsPanel()
            tabsPanel.visibility = View.VISIBLE
        }
    }

    private fun hideTabsPanel() {
        tabsPanel.visibility = View.GONE
    }

    // ---------- Вкладки ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, switch: Boolean = true): Tab {
        val web = WebView(this)
        val tab = Tab(web)
        web.setBackgroundColor(BG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "about") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.url = url
                if (tab === current) {
                    progress.visibility = View.VISIBLE
                    if (!address.hasFocus()) showHost()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.url = url
                tab.title = view.title ?: tab.title
                if (tab === current) {
                    progress.visibility = View.INVISIBLE
                    if (!address.hasFocus()) showHost()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab === current) progress.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val t = newTab(null)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = t.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                view.setBackgroundColor(Color.BLACK)
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
            }
        }

        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(dlUrl))
                req.setMimeType(mimeType)
                req.addRequestHeader("User-Agent", userAgent)
                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(dlUrl) ?: "")
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
                )
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Загрузка началась", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось скачать", Toast.LENGTH_SHORT).show()
            }
        }

        tabs.add(tab)
        if (switch) switchTo(tab)
        if (url != null) {
            tab.url = url
            web.loadUrl(url)
        }
        updateTabCount()
        return tab
    }

    private fun switchTo(tab: Tab) {
        current?.let { webHolder.removeView(it.web) }
        current = tab
        webHolder.addView(tab.web, FrameLayout.LayoutParams(-1, -1))
        progress.visibility = View.INVISIBLE
        address.clearFocus()
        showHost()
        updateTabCount()
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tab === current) {
            webHolder.removeView(tab.web)
            current = null
            if (tabs.isEmpty()) {
                newTab(HOME)
            } else {
                switchTo(tabs[minOf(idx, tabs.size - 1)])
            }
        }
        tab.web.destroy()
        updateTabCount()
    }

    private fun updateTabCount() {
        tabsBtn.text = tabs.size.toString()
    }

    // ---------- Адресная строка ----------

    private fun hostOf(url: String): String {
        if (url.isBlank()) return ""
        return try {
            Uri.parse(url).host?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun showHost() {
        val u = current?.url.orEmpty()
        address.setText(hostOf(u))
    }

    private fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains(" ") || !text.contains(".") -> SEARCH + Uri.encode(text)
            else -> "https://$text"
        }
        current?.let {
            it.url = url
            it.web.loadUrl(url)
        }
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ---------- Меню ----------

    private fun showMenu() {
        val web = current?.web ?: return
        val desktop = web.settings.userAgentString.contains("X11")
        val items = arrayOf(
            "Вперёд",
            "Обновить",
            "Новая вкладка",
            if (desktop) "Мобильная версия" else "Версия для ПК",
            "Поделиться ссылкой"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (web.canGoForward()) web.goForward()
                    1 -> web.reload()
                    2 -> newTab(HOME)
                    3 -> {
                        web.settings.userAgentString = if (desktop) null else
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 Safari/537.36"
                        web.settings.useWideViewPort = !desktop
                        web.reload()
                    }
                    4 -> startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, current?.url.orEmpty())
                            }, "Поделиться"
                        )
                    )
                }
            }
            .show()
    }

    // ---------- Навигация / жизненный цикл ----------

    private fun goBackOrClose() {
        val t = current ?: return
        if (t.web.canGoBack()) t.web.goBack()
        else if (tabs.size > 1) closeTab(t)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> {
                customView?.let { root.removeView(it) }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
            }
            tabsPanel.visibility == View.VISIBLE -> hideTabsPanel()
            address.hasFocus() -> { address.clearFocus(); hideKeyboard(address) }
            current?.web?.canGoBack() == true -> current?.web?.goBack()
            tabs.size > 1 -> current?.let { closeTab(it) }
            else -> super.onBackPressed()
        }
    }

    private fun saveTabs() {
        val prefs = getSharedPreferences("tabs", MODE_PRIVATE)
        prefs.edit()
            .putString("urls", tabs.joinToString("\n") { it.url.ifBlank { HOME } })
            .putInt("current", tabs.indexOf(current).coerceAtLeast(0))
            .apply()
    }

    private fun restoreTabs(openUrl: String?) {
        val prefs = getSharedPreferences("tabs", MODE_PRIVATE)
        val saved = prefs.getString("urls", null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
        val idx = prefs.getInt("current", 0)
        if (saved.isEmpty()) {
            newTab(openUrl ?: HOME)
            return
        }
        saved.forEach { newTab(it, switch = false) }
        switchTo(tabs[idx.coerceIn(0, tabs.size - 1)])
        if (openUrl != null) newTab(openUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.toString()?.let { newTab(it) }
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
        CookieManager.getInstance().flush()
        current?.web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        current?.web?.onResume()
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }
}
