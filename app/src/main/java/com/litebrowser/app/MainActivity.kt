package com.litebrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import com.litebrowser.app.data.BrowserDatabase
import com.litebrowser.app.data.SiteCursorAdapter
import com.litebrowser.app.download.DownloadHelper
import com.litebrowser.app.m3u8.M3u8BrowserActivity
import com.litebrowser.app.player.MediaSniffer
import com.litebrowser.app.player.PlayerActivity
import com.litebrowser.app.webview.BrowserWebChromeClient
import com.litebrowser.app.webview.BrowserWebViewClient
import org.json.JSONArray
import org.json.JSONTokener

/**
 * 主界面：一个 WebView + 地址栏 + 底部工具条。
 *
 * 设计取向（低端机优先）：
 * - 界面/存储/下载/嗅探全部用系统 framework 组件，唯一第三方依赖是播放内核 Media3；
 * - 下载交给系统下载服务，App 内不做网络与文件 IO；
 * - 收藏/历史用 CursorAdapter 直连游标，列表不一次性载入内存；
 * - 页面状态可保存恢复，后台被回收后回到原页面。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlEdit: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var mainContent: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var bookmarkButton: ImageButton
    private lateinit var db: BrowserDatabase
    private lateinit var chromeClient: BrowserWebChromeClient

    private var currentTitle = ""
    private var lastBackPress = 0L
    private var pendingDownload: DownloadTask? = null

    /** 下载参数：授权后需要用它继续下载，所以先暂存整个请求；referer 供防盗链使用 */
    private data class DownloadTask(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val referer: String?
    )

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_NOTIFICATION = 1002
        private const val SEARCH_ENGINE = "https://www.baidu.com/s?wd="
        private const val UA_SUFFIX = "LiteBrowser/1.2"
        private const val KEY_NOTIFICATION_ASKED = "notification_asked"

        /**
         * 页面内探针：把 <video> 的真实地址和 performance 资源表里的媒体链接捞出来。
         * 拦截层只能看到 WebView 发起的请求（很多播放器把主列表藏在 XHR 里，只暴露 .ts 分片），
         * 这一层正好补上。刻意不用正则里的 $ / 反斜杠，避免和 Kotlin 字符串模板打架。
         */
        private val SNIFF_JS = """
            (function () {
              var out = [], seen = {}, exts = ['.mp4', '.m4v', '.webm', '.mkv', '.flv', '.mov', '.3gp', '.m3u8', '.mpd'];
              function push(u) { if (!u || seen[u]) return; seen[u] = 1; out.push(u); }
              try {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                  var v = vids[i], s = v.currentSrc || v.src || '';
                  if (!s) {
                    var ss = v.getElementsByTagName('source');
                    for (var j = 0; j < ss.length; j++) { if (ss[j].src) { s = ss[j].src; break; } }
                  }
                  push(s);
                }
              } catch (e) {}
              try {
                var es = performance.getEntriesByType('resource');
                for (var k = 0; k < es.length; k++) {
                  var u = es[k].name || '';
                  var p = u.split('#')[0].split('?')[0].toLowerCase();
                  var dot = p.lastIndexOf('.');
                  if (dot >= 0 && exts.indexOf(p.substring(dot)) >= 0) push(u);
                }
              } catch (e) {}
              return JSON.stringify(out);
            })()
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = BrowserDatabase(this)
        bindViews()
        configureWebView()
        bindActions()

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            // 从后台回收 / 系统重启后恢复原页面，避免用户丢失正在看的网页
            urlEdit.setText(webView.url ?: getString(R.string.home_url))
        } else {
            // 启动显示空白首页，地址栏留空；只有外部链接进来才打开具体网址
            val external = intent?.data?.toString().orEmpty()
            if (external.isNotEmpty()) loadUrl(external) else loadHome()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTask 模式下从别的应用再次点链接进来
        val url = intent?.data?.toString()
        if (!url.isNullOrEmpty()) loadUrl(url)
    }

    private fun bindViews() {
        webView = findViewById(R.id.web_view)
        urlEdit = findViewById(R.id.url_edit)
        progressBar = findViewById(R.id.progress_bar)
        mainContent = findViewById(R.id.main_content)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        bookmarkButton = findViewById(R.id.bookmark_button)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true                  // 现代网页与视频播放必需
        settings.domStorageEnabled = true                  // HTML5 localStorage / sessionStorage
        settings.databaseEnabled = true
        settings.useWideViewPort = true                    // 尊重网页 viewport，移动端排版正常
        settings.loadWithOverviewMode = true               // 桌面版网页整体缩放到屏幕宽度
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true                // 开启后双指缩放才生效
        settings.displayZoomControls = false               // 但不显示 +/- 按钮，省屏幕空间
        settings.setSupportMultipleWindows(true)           // target="_blank" 的链接才能在当前窗口打开
        settings.javaScriptCanOpenWindowsAutomatically = false // 只允许用户点击触发新窗口，屏蔽弹窗
        settings.cacheMode = WebSettings.LOAD_DEFAULT      // 有网走网络、无网用缓存，省流量
        settings.mediaPlaybackRequiresUserGesture = true   // 禁止自动播放，避免偷跑流量电量
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.defaultTextEncodingName = "UTF-8"
        settings.textZoom = 100
        settings.userAgentString = settings.userAgentString + " " + UA_SUFFIX

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        chromeClient = BrowserWebChromeClient(
            activity = this,
            fullscreenContainer = fullscreenContainer,
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
            },
            onNewWindowUrl = { url -> loadUrl(url) }
        )

        webView.webChromeClient = chromeClient
        webView.webViewClient = BrowserWebViewClient(
            onStarted = { MediaSniffer.clear() },
            onFinished = { url, title ->
                // 只在真实网页上更新地址栏 / 记录历史，跳过错误页（about:blank）等
                if (isWebUrl(url)) {
                    currentTitle = title
                    urlEdit.setText(url)
                    db.addHistory(title, url)
                    updateBookmarkIcon()
                }
            },
            onError = { url, description -> showErrorPage(url, description) },
            onExternalScheme = { url -> openExternal(url) },
            onResourceRequest = { url -> MediaSniffer.add(url) }
        )

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })
        webView.setBackgroundColor(Color.WHITE)
    }

    private fun bindActions() {
        urlEdit.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                navigateTo(urlEdit.text.toString())
                hideKeyboard()
                urlEdit.clearFocus()
                true
            } else {
                false
            }
        }

        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else toast(getString(R.string.no_previous_page))
        }
        findViewById<ImageButton>(R.id.forward_button).setOnClickListener {
            if (webView.canGoForward()) webView.goForward() else toast(getString(R.string.no_next_page))
        }
        findViewById<ImageButton>(R.id.home_button).setOnClickListener {
            loadHome()
        }
        findViewById<ImageButton>(R.id.refresh_button).setOnClickListener {
            webView.reload()
        }
        findViewById<ImageButton>(R.id.downloads_button).setOnClickListener {
            openDownloads()
        }
        bookmarkButton.setOnClickListener { toggleBookmark() }
        findViewById<ImageButton>(R.id.sniff_button).setOnClickListener { sniffVideos() }
        findViewById<ImageButton>(R.id.menu_button).setOnClickListener { showMenu() }
    }

    // ---------------- 导航 ----------------

    /** 输入网址或关键词：带协议直接打开；含点的补 https；其余交给搜索引擎 */
    private fun navigateTo(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://", true) || text.startsWith("https://", true) -> text
            text.startsWith("file://", true) || text.startsWith("about:", true) -> text
            !text.contains(".") || text.contains(" ") -> SEARCH_ENGINE + Uri.encode(text)
            else -> "https://$text"
        }
        loadUrl(url)
    }

    private fun loadUrl(url: String) {
        urlEdit.setText(url)
        webView.loadUrl(url)
    }

    /** 空白首页：不加载任何网址，地址栏清空 */
    private fun loadHome() {
        urlEdit.setText("")
        webView.loadUrl("about:blank")
    }

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    private fun currentUrl(): String? {
        val url = webView.url ?: return null
        return if (isWebUrl(url)) url else null
    }

    /** tel: / mailto: / weixin: / intent: 等非 http 协议交给系统处理 */
    private fun openExternal(url: String) {
        try {
            val intent = if (url.startsWith("intent:", true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    // ---------------- 收藏 ----------------

    private fun toggleBookmark() {
        val url = currentUrl() ?: return
        if (db.isBookmarked(url)) {
            db.removeBookmark(url)
            toast(getString(R.string.bookmark_removed))
        } else {
            db.addBookmark(currentTitle.ifEmpty { url }, url)
            toast(getString(R.string.bookmark_added))
        }
        updateBookmarkIcon()
    }

    private fun updateBookmarkIcon() {
        val url = currentUrl()
        val marked = url != null && db.isBookmarked(url)
        bookmarkButton.setImageResource(
            if (marked) R.drawable.ic_star_filled else R.drawable.ic_star_border
        )
    }

    // ---------------- 菜单 ----------------

    private fun showMenu() {
        val items = arrayOf<CharSequence>(
            getString(R.string.bookmarks),
            getString(R.string.history),
            getString(R.string.share_page),
            getString(R.string.copy_link),
            getString(R.string.open_in_browser),
            getString(R.string.m3u8_library),
            getString(R.string.clear_history),
            getString(R.string.about)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.more)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSiteList(isBookmark = true)
                    1 -> showSiteList(isBookmark = false)
                    2 -> sharePage()
                    3 -> copyLink()
                    4 -> openInSystemBrowser()
                    5 -> startActivity(Intent(this, M3u8BrowserActivity::class.java))
                    6 -> confirmClearHistory()
                    7 -> showAbout()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 收藏夹 / 历史记录列表：ListView 直连游标，高度固定为屏幕 60%，避免弹窗被压扁 */
    private fun showSiteList(isBookmark: Boolean) {
        val cursor = if (isBookmark) db.queryBookmarks() else db.queryHistory()
        if (cursor.count == 0) {
            cursor.close()
            toast(getString(if (isBookmark) R.string.no_bookmarks else R.string.no_history))
            return
        }

        lateinit var adapter: SiteCursorAdapter
        adapter = SiteCursorAdapter(this, cursor) { id, url ->
            if (isBookmark) db.removeBookmark(url) else db.deleteHistory(id)
            toast(getString(if (isBookmark) R.string.bookmark_removed else R.string.item_deleted))
            // changeCursor 会关闭旧游标，列表原地刷新，不闪屏
            adapter.changeCursor(if (isBookmark) db.queryBookmarks() else db.queryHistory())
        }

        val listView = ListView(this).apply {
            this.adapter = adapter
            divider = ColorDrawable(Color.parseColor("#E0E0E0"))
            dividerHeight = 1
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                (adapter.getItem(position) as? Cursor)?.let { c ->
                    val url = c.getString(c.getColumnIndexOrThrow("url"))
                    loadUrl(url)
                    siteDialog?.dismiss()
                }
            }
        }

        val height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val container = FrameLayout(this).apply {
            addView(
                listView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            )
        }

        siteDialog = AlertDialog.Builder(this)
            .setTitle(if (isBookmark) R.string.bookmarks else R.string.history)
            .setView(container)
            .setNegativeButton(R.string.close, null)
            .create()

        siteDialog?.setOnDismissListener {
            adapter.changeCursor(null)   // 关闭游标，避免数据库句柄泄漏
            siteDialog = null
        }
        siteDialog?.show()
    }

    private var siteDialog: AlertDialog? = null

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_clear_history)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                db.clearHistory()
                toast(getString(R.string.history_cleared))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sharePage() {
        val url = currentUrl() ?: return
        val text = if (currentTitle.isEmpty()) url else "$currentTitle\n$url"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentTitle)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_page)))
        } catch (e: Exception) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    private fun copyLink() {
        val url = currentUrl() ?: return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("url", url))
        toast(getString(R.string.link_copied))
    }

    private fun openInSystemBrowser() {
        val url = currentUrl() ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------- 资源嗅探 ----------------

    /**
     * 嗅探本页视频：先取拦截层已收集的，再用 JS 探针补一轮，合并去重后列出来给用户选。
     * 只做"发现"，点击后交给内置播放器，网页本身不受任何影响。
     */
    private fun sniffVideos() {
        if (currentUrl() == null) {
            toast(getString(R.string.sniff_need_page))
            return
        }
        toast(getString(R.string.sniff_scanning))
        webView.evaluateJavascript(SNIFF_JS) { result -> showSniffResult(result) }
    }

    private fun showSniffResult(result: String?) {
        if (isFinishing || isDestroyed) return

        // evaluateJavascript 回传的是 JSON 编码值：返回字符串时外面会多一层引号
        if (!result.isNullOrEmpty()) {
            try {
                val raw = if (result.startsWith("\"")) {
                    JSONTokener(result).nextValue().toString()
                } else {
                    result
                }
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    MediaSniffer.add(array.optString(i))
                }
            } catch (e: Exception) {
                // 解析失败就只用拦截层的结果，不影响主流程
            }
        }

        val videos = MediaSniffer.snapshot()
        if (videos.isEmpty()) {
            toast(getString(R.string.sniff_none))
            return
        }

        val labels = ArrayList<CharSequence>(videos.size)
        videos.forEachIndexed { index, url ->
            labels.add((index + 1).toString() + ". " + MediaSniffer.prettyName(url))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sniff_found, videos.size))
            .setItems(labels.toTypedArray()) { _, which -> openPlayer(videos[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPlayer(url: String) {
        webView.onPause()   // 网页里的 <video> 先停下，避免两个声音叠在一起
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_REFERER, webView.url)
            putExtra(PlayerActivity.EXTRA_UA, webView.settings.userAgentString)
            putExtra(PlayerActivity.EXTRA_TITLE, currentTitle)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.player_error_message))
        }
    }

    // ---------------- 下载 ----------------

    /**
     * 下载前置检查：
     * - Android 8/9（API 26-28）写公共 Download 目录需要运行时存储权限，很多低端机正跑这两个版本；
     * - Android 13+ 需要通知权限才能显示下载进度通知。
     * 任一权限缺失都先暂存请求，授权后继续下载。
     */
    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.startsWith("blob:", true) || url.startsWith("data:", true)) {
            // blob:/data: 只在页面内存里有效，系统下载器取不到内容
            toast(getString(R.string.download_unsupported))
            return
        }

        val task = DownloadTask(url, userAgent, contentDisposition, mimeType, webView.url)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = task
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationAsked()
        ) {
            markNotificationAsked()
            pendingDownload = task
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            return
        }

        startDownload(task)
    }

    /** 通知权限只主动要一次，避免每次下载都弹（被拒后下载照常进行，只是没有完成通知） */
    private fun notificationAsked(): Boolean =
        getPreferences(Context.MODE_PRIVATE).getBoolean(KEY_NOTIFICATION_ASKED, false)

    private fun markNotificationAsked() {
        getPreferences(Context.MODE_PRIVATE).edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
    }

    private fun startDownload(task: DownloadTask) {
        try {
            DownloadHelper.startDownload(
                this, task.url, task.referer, task.userAgent,
                task.contentDisposition, task.mimeType
            )
            toast(getString(R.string.download_started))
        } catch (e: Exception) {
            toast(getString(R.string.download_failed))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE && requestCode != REQ_NOTIFICATION) return

        val task = pendingDownload ?: return
        pendingDownload = null

        val storageGranted = Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (storageGranted) {
            startDownload(task)
        } else {
            toast(getString(R.string.permission_denied))
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(this, com.litebrowser.app.download.DownloadActivity::class.java))
        } catch (e: Exception) {
            toast(getString(R.string.download_manager_missing))
        }
    }

    // ---------------- 错误页 ----------------

    /** 加载失败时显示一个轻量本地错误页，比系统默认的白屏友好 */
    private fun showErrorPage(failedUrl: String, description: String) {
        val detail = if (description.isEmpty()) "" else "<p>" + escapeHtml(description) + "</p>"
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>body{margin:0;padding:56px 24px;background:#f5f5f5;color:#212121;" +
            "font-family:sans-serif;text-align:center}h2{font-size:18px;margin:0 0 14px}" +
            "p{font-size:13px;color:#757575;line-height:1.7;word-break:break-all;margin:0 0 10px}" +
            "</style></head><body><h2>" + getString(R.string.error_page_title) + "</h2>" +
            "<p>" + escapeHtml(failedUrl) + "</p>" + detail +
            "<p>" + getString(R.string.error_page_hint) + "</p></body></html>"
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ---------------- 系统交互 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (chromeClient.onBackPressed()) return true          // 先退出视频全屏
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        cookieSync()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        updateBookmarkIcon()
    }

    override fun onDestroy() {
        siteDialog?.dismiss()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /** Cookie 攒在内存里，低端机若被后台杀掉可能丢失登录态，切后台时落盘一次 */
    private fun cookieSync() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // 忽略：部分 ROM 的 WebView 实现未提供 flush
        }
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
