package com.litebrowser.app.download

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import java.util.HashMap

/**
 * 下载入口：把请求交给自研 DownloadService，不再使用系统 DownloadManager。
 *
 * 自研的好处：能带 Referer/Cookie 绕过防盗链、支持 m3u8 连分片下载、
 * 进度/暂停/续传都可控制，且不依赖各厂商对 DownloadManager 的实现差异。
 */
object DownloadHelper {

    /**
     * @param referer 当前页面 URL（防盗链关键）
     * @param userAgent WebView 的 UA
     */
    fun startDownload(
        context: Context,
        url: String,
        referer: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        nameHint: String? = null
    ) {
        val headers = HashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
        // CookieManager 里存着页面的登录态/防盗链 Cookie，一并带上
        try {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        } catch (e: Exception) {
        }

        // Content-Disposition 常是 "attachment; filename=xxx.mp4"，取出 filename
        val hint = nameHint?.takeIf { it.isNotBlank() }
            ?: contentDisposition?.let { disp ->
                Regex("filename\\*?=(?:\"?UTF-8\"?\'\')?\"?([^\";]+)", RegexOption.IGNORE_CASE)
                    .find(disp)?.groupValues?.getOrNull(1)?.trim('"')
            }
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_ADD
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_NAME, hint.orEmpty())
            putExtra(DownloadService.EXTRA_HEADERS, headers)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
