package com.litebrowser.app.webview

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** 导航与加载事件处理 */
class BrowserWebViewClient(
    private val onLoadingChanged: (loading: Boolean) -> Unit,
    private val onPageFinished: (url: String, title: String) -> Unit,
    private val onError: () -> Unit
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingChanged(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingChanged(false)
        if (url != null) {
            onPageFinished(url, view?.title ?: "")
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        // 只在主框架加载失败时提示，忽略图片等子资源失败
        if (request?.isForMainFrame == true) {
            onLoadingChanged(false)
            onError()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        // http/https 由 WebView 自身加载，其他 scheme（tel/mailto 等）交给系统
        return !(url.startsWith("http://") || url.startsWith("https://"))
    }
}
