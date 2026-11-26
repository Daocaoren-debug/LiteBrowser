package com.litebrowser.app.download

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import java.io.File
import java.util.HashMap

/**
 * 下载入口：把请求交给自研 DownloadService，不再使用系统 DownloadManager。
 *
 * 自研的好处：能带 Referer/Cookie 绕过防盗链、支持 m3u8 连分片下载、
 * 进度/暂停/续传都可控制，且不依赖各厂商对 DownloadManager 的实现差异。
 */
object DownloadHelper {

    enum class Status {
        STARTED,
        /** 同链接已在队列 / 下载中 / 已暂停 */
        ALREADY_DOWNLOADING,
        /** 同链接已经下载完成且文件还在 */
        ALREADY_DONE,
        FAILED
    }

    /**
     * @param referer 当前页面 URL（防盗链关键）
     * @param userAgent WebView 的 UA
     * @return 入队结果，调用方据此提示用户（避免连点产生重复任务）
     */
    fun startDownload(
        context: Context,
        url: String,
        referer: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        nameHint: String? = null
    ): Status {
        if (url.isBlank()) return Status.FAILED

        // 去重：同一个链接只允许有一个进行中的任务，已下完的也不再重复下
        try {
            val tasks = DownloadStore.load(context)
            val existing = DownloadStore.findByUrl(tasks, url)
            if (existing != null) {
                when (existing.state) {
                    DownloadStore.State.QUEUED,
                    DownloadStore.State.RUNNING,
                    DownloadStore.State.PAUSED -> return Status.ALREADY_DOWNLOADING

                    DownloadStore.State.DONE -> {
                        if (File(existing.targetPath).exists()) return Status.ALREADY_DONE
                        // 记录还在但文件被外部删了：清掉旧记录，重新下一份
                        tasks.removeAll { it.id == existing.id }
                        DownloadStore.save(context, tasks)
                    }

                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // 去重只是优化，读失败就按新任务处理
        }

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
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Status.STARTED
        } catch (e: Exception) {
            Status.FAILED
        }
    }
}
