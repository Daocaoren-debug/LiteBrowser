package com.litebrowser.app.download

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 纯 HttpURLConnection 下载器（不依赖系统 DownloadManager）。
 * 支持：
 * - 自定义请求头（Referer / Cookie / User-Agent），应对防盗链；
 * - 断点续传：目标文件已存在时用 Range 从断点继续；
 * - 暂停/取消：由外部 AtomicBoolean 控制，暂停保留已下载字节；
 * - 进度回调（当前字节 / 总字节）。
 */
object HttpDownloader {

    data class Result(
        val ok: Boolean,
        val message: String = "",
        val bytes: Long = 0L,
        val total: Long = 0L,
        val paused: Boolean = false,
        val canceled: Boolean = false
    )

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 下载一个 URL 到 target。
     * @param pauseFlag 置 true 时暂停（保留已下载部分）
     * @param cancelFlag 置 true 时取消（删除半成品）
     */
    fun download(
        url: String,
        target: File,
        headers: Map<String, String>,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean,
        onProgress: (bytes: Long, total: Long) -> Unit
    ): Result {
        if (cancelFlag.get()) return Result(false, "已取消", canceled = true)

        var connection: HttpURLConnection? = null
        var out: FileOutputStream? = null
        var downloaded = 0L
        var totalBytes = -1L

        return try {
            val existing = if (target.exists()) target.length() else 0L
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
                if (existing > 0L) {
                    // 断点续传：带上 Range，服务端支持时返回 206
                    setRequestProperty("Range", "bytes=$existing-")
                }
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                return Result(false, "HTTP $code")
            }

            val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (!append && target.exists() && !target.delete()) {
                return Result(false, "无法覆盖已有文件")
            }
            target.parentFile?.mkdirs()

            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            totalBytes = if (append) existing + contentLength else contentLength
            downloaded = if (append) existing else 0L
            out = FileOutputStream(target, append)

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                while (!pauseFlag.get() && !cancelFlag.get()) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out!!.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, totalBytes)
                }
            }
            try {
                out?.flush()
            } finally {
                out?.close()
                out = null
            }

            when {
                cancelFlag.get() -> {
                    target.delete()
                    Result(false, "已取消", downloaded, totalBytes, canceled = true)
                }
                pauseFlag.get() -> {
                    Result(false, "已暂停", downloaded, totalBytes, paused = true)
                }
                else -> Result(true, "完成", downloaded, totalBytes)
            }
        } catch (e: Exception) {
            // 暂停/取消时读取被中断属正常，不删除已下载部分
            when {
                cancelFlag.get() -> {
                    try { target.delete() } catch (ignored: Exception) {}
                    Result(false, "已取消", downloaded, totalBytes, canceled = true)
                }
                pauseFlag.get() -> Result(false, "已暂停", downloaded, totalBytes, paused = true)
                else -> Result(false, e.message ?: "网络错误", downloaded, totalBytes)
            }
        } finally {
            try {
                out?.close()
            } catch (e: Exception) {
            }
            connection?.disconnect()
        }
    }

    /** 读取小文本（m3u8 列表），失败返回 null */
    fun fetchText(url: String, headers: Map<String, String>): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
            }
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 已下载字节数（断点续传用） */
    fun existingBytes(target: File): Long {
        return try {
            if (target.exists()) RandomAccessFile(target, "r").use { it.length() } else 0L
        } catch (e: Exception) {
            if (target.exists()) target.length() else 0L
        }
    }
}
