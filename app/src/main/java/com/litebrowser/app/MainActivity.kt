package com.litebrowser.app

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.DownloadListener
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import com.litebrowser.app.data.BrowserDatabase
import com.litebrowser.app.data.SiteCursorAdapter
import com.litebrowser.app.download.DownloadHelper
import com.litebrowser.app.webview.BrowserWebChromeClient
import com.litebrowser.app.webview.BrowserWebViewClient

class MainActivity : android.app.Activity() {

    private lateinit var webView: WebView
    private lateinit var urlEdit: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var mainContent: View
    private lateinit var fullscreenContainer: android.widget.FrameLayout
    private lateinit var bookmarkButton: ImageButton
    private lateinit var db: BrowserDatabase

    private lateinit var chromeClient: BrowserWebChromeClient
    private var currentTitle = ""
    private var lastBackPress = 0L
    private var pendingDownloadUrl: String? = null

    companion object {
        private const val REQ_NOTIFICATION = 1001
        private const val SEARCH_ENGINE = "https://www.baidu.com/s?wd="
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = BrowserDatabase(this)
        bindViews()
        configureWebView()
        bindActions()

        // 处理外部应用传来的链接
        val incoming = intent?.data?.toString()
        loadUrl(incoming ?: getString(R.string.home_url))
    }

    private fun bindViews() {
        webView = findViewById(R.id.web_view)
        urlEdit = findViewById(R.id.url_edit)
        progressBar = findViewById(R.id.progress_bar)
        mainContent = findViewById(R.id.main_content)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        bookmarkButton = findViewById(R.id.bookmark_button)
    }

    /** WebView 配置：开启必要能力，同时关闭非必要特性以节省低端机资源 */
    private fun configureWebView() {
        val settings: WebSettings = webView.settings.apply {
            javaScriptEnabled = true                 // 现代网页/视频播放必需
            domStorageEnabled = true                 // HTML5 本地存储
            databaseEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = false
            builtInZoomControls = false              // 关闭缩放控件，减少 UI 开销
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT      // 优先缓存，省流量且加载更快
            mediaPlaybackRequiresUserGesture = true  // 禁止自动播放，避免后台偷跑流量/电量
            textZoom = 100
        }
        // 混合内容：允许 https 页面加载 http 资源（很多老视频站点依赖），兼顾可用性
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 让网页能拿到一个正常的 UA，避免部分站点屏蔽移动 WebView
        settings.userAgentString = settings.userAgentString + " LiteBrowser/1.0"

        chromeClient = BrowserWebChromeClient(
            fullscreenContainer,
            onProgress = { progress ->
                if (progress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = progress
                }
            },
            onFullscreenChanged = { inFullscreen ->
                mainContent.visibility = if (inFullscreen) View.GONE else View.VISIBLE
            }
        )

        webView.webViewClient = BrowserWebViewClient(
            onLoadingChanged = { loading ->
                if (!loading) updateBookmarkIcon()
            },
            onPageFinished = { url, title ->
                currentTitle = title
                urlEdit.setText(url)
                db.addHistory(title, url)
                updateBookmarkIcon()
            },
            onError = { toast(getString(R.string.error_load)) }
        )
        webView.webChromeClient = chromeClient

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })
    }

    private fun bindActions() {
        urlEdit.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                navigateTo(urlEdit.text.toString())
                hideKeyboard()
                true
            } else false
        }

        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else toast("已经在第一页")
        }
        findViewById<ImageButton>(R.id.forward_button).setOnClickListener {
            if (webView.canGoForward()) webView.goForward() else toast("没有更后的页面")
        }
        findViewById<ImageButton>(R.id.home_button).setOnClickListener {
            loadUrl(getString(R.string.home_url))
        }
        findViewById<ImageButton>(R.id.refresh_button).setOnClickListener {
            webView.reload()
        }
        findViewById<ImageButton>(R.id.downloads_button).setOnClickListener {
            openDownloads()
        }
        bookmarkButton.setOnClickListener { toggleBookmark() }
        findViewById<ImageButton>(R.id.menu_button).setOnClickListener { showMenu() }
    }

    /** 输入网址或关键词：带协议直接打开，否则补全 https；非网址当关键词搜索 */
    private fun navigateTo(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://", true) || text.startsWith("https://", true) -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> SEARCH_ENGINE + Uri.encode(text)
        }
        loadUrl(url)
    }

    private fun loadUrl(url: String) {
        urlEdit.setText(url)
        webView.loadUrl(url)
    }

    // ---------------- 收藏 ----------------

    private fun toggleBookmark() {
        val url = webView.url ?: return
        if (url.isEmpty() || url == "about:blank") return
        if (db.isBookmarked(url)) {
            db.removeBookmark(url)
            toast(getString(R.string.bookmark_removed))
        } else {
            val added = db.addBookmark(currentTitle.ifEmpty { url }, url)
            toast(if (added) getString(R.string.bookmark_added) else getString(R.string.bookmark_added))
        }
        updateBookmarkIcon()
    }

    private fun updateBookmarkIcon() {
        val url = webView.url
        val marked = !url.isNullOrEmpty() && db.isBookmarked(url)
        bookmarkButton.setImageResource(
            if (marked) R.drawable.ic_star_filled else R.drawable.ic_star_border
        )
    }

    // ---------------- 菜单：收藏夹 / 历史 / 关于 ----------------

    private fun showMenu() {
        val items = arrayOf<CharSequence>(
            getString(R.string.bookmarks),
            getString(R.string.history),
            getString(R.string.clear_history),
            getString(R.string.about)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.more))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSiteList(isBookmark = true)
                    1 -> showSiteList(isBookmark = false)
                    2 -> confirmClearHistory()
                    3 -> showAbout()
                }
            }
            .show()
    }

    private fun showSiteList(isBookmark: Boolean) {
        val cursor = if (isBookmark) db.queryBookmarks() else db.queryHistory()
        val titleRes = if (isBookmark) R.string.bookmarks else R.string.history
        val emptyText = getString(if (isBookmark) R.string.no_bookmarks else R.string.no_history)

        if (cursor.count == 0) {
            cursor.close()
            toast(emptyText)
            return
        }

        val adapter = SiteCursorAdapter(this, cursor) { id ->
            if (isBookmark) {
                // 收藏列表删除按 url，需要查一次
                val c = db.queryBookmarks()
                var url = ""
                if (c.moveToFirst()) {
                    do {
                        if (c.getLong(c.getColumnIndexOrThrow("_id")) == id) {
                            url = c.getString(c.getColumnIndexOrThrow("url"))
                            break
                        }
                    } while (c.moveToNext())
                }
                c.close()
                db.removeBookmark(url)
            } else {
                db.deleteHistory(id)
            }
            // 重新打开游标刷新列表
            refreshSiteList(isBookmark)
        }

        val listView = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = 1
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val c = adapter.getItem(position) as? android.database.Cursor ?: return@OnItemClickListener
                val url = c.getString(c.getColumnIndexOrThrow("url"))
                loadUrl(url)
                dialog?.dismiss()
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(listView)
            .setPositiveButton(getString(R.string.cancel), null)
            .create()
        dialog?.show()
    }

    private var dialog: AlertDialog? = null

    /** 删除条目后重建对话框游标 */
    private fun refreshSiteList(isBookmark: Boolean) {
        dialog?.dismiss()
        showSiteList(isBookmark)
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_clear_history))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                db.clearHistory()
                toast(getString(R.string.history_cleared))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about))
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------- 下载 ----------------

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        // Android 13+ 需要通知权限才能在下载完成时提示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadUrl = url
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION
            )
            return
        }
        doDownload(url, userAgent, contentDisposition, mimeType)
    }

    private fun doDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            DownloadHelper.startDownload(this, url, userAgent, contentDisposition, mimeType)
            toast(getString(R.string.download_started))
        } catch (e: Exception) {
            toast("下载失败：${e.message ?: "未知错误"}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            // 无论是否授权都继续下载，只是没有完成通知
            pendingDownloadUrl?.let { doDownload(it, null, null, null) }
            pendingDownloadUrl = null
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            toast("无法打开下载管理")
        }
    }

    // ---------------- 系统交互 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (chromeClient.onBackPressed()) return true
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            if (SystemClock.elapsedRealtime() - lastBackPress < 2000) {
                return super.onKeyDown(keyCode, event)
            }
            lastBackPress = SystemClock.elapsedRealtime()
            toast(getString(R.string.press_again_to_exit))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        updateBookmarkIcon()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlEdit.windowToken, 0)
    }

    private fun toast(message: String) {
        if (!TextUtils.isEmpty(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
