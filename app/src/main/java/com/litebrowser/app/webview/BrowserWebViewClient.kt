package com.litebrowser.app.webview

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 导航与加载事件：
 * - http/https 交给 WebView 自己加载；
 * - 其他协议（tel/mailto/weixin/intent…）抛给系统，避免 WebView 显示 ERR_UNKNOWN_URL_SCHEME；
 * - 只在主框架失败时回调错误，图片/广告等子资源失败不打扰用户。
 */
class BrowserWebViewClient(
    private val onStarted: (url: String) -> Unit,
    private val onFinished: (url: String, title: String) -> Unit,
    private val onError: (url: String, description: String) -> Unit,
    private val onExternalScheme: (url: String) -> Unit
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url != null) onStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url != null) onFinished(url, view?.title ?: "")
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onError(request.url.toString(), error?.description?.toString() ?: "")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleUrl(url)
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return handleUrl(url)
    }

    /** 返回 true 表示由我们接管（不再让 WebView 加载） */
    private fun handleUrl(url: String): Boolean {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            return false
        }
        if (url.startsWith("about:", true) || url.startsWith("javascript:", true)) {
            return false
        }
        onExternalScheme(url)
        return true
    }
}
