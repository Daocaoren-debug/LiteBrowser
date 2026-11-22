package com.litebrowser.app.webview

import android.app.Activity
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 进度回调 + 网页视频全屏 + target="_blank" 新窗口接管。
 *
 * 1. onShowCustomView：网页 <video> 全屏时把播放器 View 挂到 Activity 的全屏容器，
 *    同时隐藏系统栏并保持屏幕常亮，退出时全部还原。
 * 2. onCreateWindow：很多站点的链接带 target="_blank"，若不做处理点击后毫无反应。
 *    这里用一个不显示的临时 WebView 接住 URL，再交给主 WebView 打开（单窗口模型，省内存）。
 */
class BrowserWebChromeClient(
    private val activity: Activity,
    private val fullscreenContainer: FrameLayout,
    private val onProgress: (Int) -> Unit,
    private val onFullscreenChanged: (inFullscreen: Boolean) -> Unit,
    private val onNewWindowUrl: (String) -> Unit
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgress(newProgress)
    }

    // ---------------- 视频全屏 ----------------

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback

        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer.visibility = View.VISIBLE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive(true)
        onFullscreenChanged(true)
    }

    override fun onHideCustomView() {
        customView?.let { fullscreenContainer.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenContainer.visibility = View.GONE
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive(false)
        onFullscreenChanged(false)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(immersive: Boolean) {
        val decor = activity.window.decorView
        decor.systemUiVisibility = if (immersive) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /** 返回键：全屏时先退出全屏，返回 true 表示已消费 */
    fun onBackPressed(): Boolean {
        return if (customView != null) {
            onHideCustomView()
            true
        } else {
            false
        }
    }

    // ---------------- 新窗口链接 ----------------

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (resultMsg == null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        // 临时 WebView 只用来接收 URL，不参与渲染
        val temp = WebView(activity)
        temp.settings.javaScriptEnabled = false
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.url?.toString()?.let { onNewWindowUrl(it) }
                return true
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (!url.isNullOrEmpty()) onNewWindowUrl(url)
                return true
            }
        }
        transport.webView = temp
        resultMsg.sendToTarget()
        return true
    }
}
