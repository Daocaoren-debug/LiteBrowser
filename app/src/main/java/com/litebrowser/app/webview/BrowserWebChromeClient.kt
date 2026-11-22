package com.litebrowser.app.webview

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * 进度回调 + 网页视频全屏支持。
 * 网页中的 <video> 全屏时会回调 onShowCustomView，我们把视频 View 挂到 Activity
 * 提供的全屏容器；退出全屏时还原。
 */
class BrowserWebChromeClient(
    private val fullscreenContainer: FrameLayout,
    private val onProgress: (Int) -> Unit,
    private val onFullscreenChanged: (inFullscreen: Boolean) -> Unit
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgress(newProgress)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            // 已有一个全屏 View，直接忽略重复请求
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
        onFullscreenChanged(true)
    }

    override fun onHideCustomView() {
        customView?.let { fullscreenContainer.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenContainer.visibility = View.GONE
        onFullscreenChanged(false)
    }

    /** 返回键按下时如果在全屏，退出全屏并返回 true */
    fun onBackPressed(): Boolean {
        return if (customView != null) {
            onHideCustomView()
            true
        } else {
            false
        }
    }
}
