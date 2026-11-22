package com.litebrowser.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil

/**
 * 下载模块：使用系统 DownloadManager。
 * 好处：下载在系统进程执行，不受本应用被杀影响，且我们不需要自己做网络/文件 IO，
 * 极大降低低端机上的内存与电量开销。
 */
object DownloadHelper {

    fun startDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): Long {
        // 解析文件名，优先 Content-Disposition，其次 URL 推断
        val rawName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val fileName = sanitizeFileName(rawName)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("轻览浏览器下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
            // 保存到公共 Download 目录；Android 10+ 由系统处理分区存储
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            allowScanningByMediaScanner()
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    /** 去掉文件名中的非法字符并限制长度，避免部分机型上 enqueue 失败 */
    private fun sanitizeFileName(name: String): String {
        var result = name.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        if (result.isEmpty() || result == "." || result == "..") {
            result = "download_${System.currentTimeMillis()}"
        }
        if (result.length > 120) {
            // 尽量保留扩展名
            val dot = result.lastIndexOf('.')
            result = if (dot > 0 && result.length - dot <= 10) {
                result.take(120 - (result.length - dot)) + result.substring(dot)
            } else {
                result.take(120)
            }
        }
        return result
    }
}
