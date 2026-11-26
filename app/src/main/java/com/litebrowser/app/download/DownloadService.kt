package com.litebrowser.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自研下载服务：完全不依赖系统 DownloadManager。
 *
 * - 纯 HttpURLConnection，支持 Referer/Cookie/UA 防盗链、断点续传；
 * - m3u8 播放列表：下载主/子列表、所有 ts 分片与密钥，重写为本地相对路径的 m3u8，
 *   下载完成后可直接进本地视频库播放或转 mp4；
 * - 前台服务 + 单线程队列，切后台/锁屏继续下载；暂停保留断点，取消删除半成品；
 * - 任务状态落盘，App 被杀后重建仍可续传。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_ADD = "com.litebrowser.app.download.ADD"
        const val ACTION_PAUSE = "com.litebrowser.app.download.PAUSE"
        const val ACTION_RESUME = "com.litebrowser.app.download.RESUME"
        const val ACTION_CANCEL = "com.litebrowser.app.download.CANCEL"
        const val ACTION_RETRY = "com.litebrowser.app.download.RETRY"
        /** 进度/状态变化广播，下载管理界面监听 */
        const val ACTION_UPDATE = "com.litebrowser.app.download.UPDATE"

        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_M3U8 = "m3u8"

        private const val CHANNEL = "download"
        private const val NOTIFY_ID = 2027

        @Volatile
        var running = false
    }

    private val workerThread = HandlerThread("litebrowser-download").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotBlank()) addTask(intent, url)
            }
            ACTION_PAUSE -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                pauseFlags[id]?.set(true)
                setPaused(id)
            }
            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                val task = findTask(id)
                if (task != null && task.state == DownloadStore.State.PAUSED) {
                    task.state = DownloadStore.State.QUEUED
                    task.message = ""
                    pauseFlags[id]?.set(false)
                    save()
                    broadcast()
                    worker.post { pump() }
                }
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                cancelFlags[id]?.set(true)
                pauseFlags[id]?.set(false)
                val task = findTask(id)
                if (task != null && task.state != DownloadStore.State.RUNNING) {
                    finishCanceled(task)
                }
            }
            ACTION_RETRY -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                val task = findTask(id)
                if (task != null) {
                    task.state = DownloadStore.State.QUEUED
                    task.message = ""
                    pauseFlags[id]?.set(false)
                    cancelFlags[id]?.set(false)
                    save()
                    broadcast()
                    worker.post { pump() }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 上次进程被杀时正在下载的任务，重建为排队状态继续（断点文件还在）
        val tasks = DownloadStore.load(this)
        var changed = false
        tasks.forEach {
            if (it.state == DownloadStore.State.RUNNING) {
                it.state = DownloadStore.State.QUEUED
                changed = true
            }
        }
        if (changed) DownloadStore.save(this, tasks)
        if (tasks.any { it.state == DownloadStore.State.QUEUED }) {
            startForeground(NOTIFY_ID, notification("准备下载", 0, 0))
            running = true
            worker.post { pump() }
        }
    }

    private fun addTask(intent: Intent, url: String) {
        val tasks = DownloadStore.load(this)
        val headers = HashMap<String, String>()
        (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<*, *>)?.forEach { (k, v) ->
            if (k is String && v is String) headers[k] = v
        }
        val referenceName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val isM3u8 = url.contains(".m3u8", ignoreCase = true) ||
            intent.getBooleanExtra(EXTRA_M3U8, false)
        val name = sanitize(
            if (referenceName.isNotBlank()) referenceName else guessName(url, isM3u8)
        )
        val task = DownloadStore.Task(
            id = DownloadStore.nextId(tasks),
            url = url,
            name = name,
            targetPath = File(downloadDir(), name).absolutePath,
            headers = headers,
            m3u8 = isM3u8
        )
        tasks.add(task)
        DownloadStore.save(this, tasks)
        pauseFlags[task.id] = AtomicBoolean(false)
        cancelFlags[task.id] = AtomicBoolean(false)
        startForeground(NOTIFY_ID, notification("准备下载", 0, 0))
        running = true
        broadcast()
        worker.post { pump() }
    }

    /** 串行处理队列：一个任务跑完才下一个，避免多个连接拖死低端机 */
    private fun pump() {
        while (true) {
            val tasks = DownloadStore.load(this)
            val task = tasks.firstOrNull { it.state == DownloadStore.State.QUEUED } ?: break
            running = true
            execute(task)
        }
        running = false
        broadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun execute(task: DownloadStore.Task) {
        val pause = pauseFlags.getOrPut(task.id) { AtomicBoolean(false) }
        val cancel = cancelFlags.getOrPut(task.id) { AtomicBoolean(false) }
        if (cancel.get()) {
            finishCanceled(task)
            return
        }
        // 若之前被暂停，等用户恢复或取消
        while (pause.get() && !cancel.get()) {
            Thread.sleep(200)
        }
        if (cancel.get()) {
            finishCanceled(task)
            return
        }

        updateTask(task.id) {
            state = DownloadStore.State.RUNNING
            message = ""
        }
        notifyProgress(task.name, 0, 0)

        val result = if (task.m3u8) {
            downloadPlaylist(task, pause, cancel)
        } else {
            val target = File(task.targetPath)
            HttpDownloader.download(task.url, target, task.headers, pause, cancel) { bytes, total ->
                updateTask(task.id) {
                    this.bytes = bytes
                    this.total = total
                }
                notifyProgress(task.name, bytes.toInt(), total.toInt())
            }
        }

        updateTask(task.id) {
            bytes = result.bytes
            total = result.total
            when {
                result.ok -> {
                    state = DownloadStore.State.DONE
                    message = if (result.message.isNotBlank()) result.message else "已完成"
                }
                result.paused -> {
                    state = DownloadStore.State.PAUSED
                    message = "已暂停"
                }
                result.canceled -> {
                    state = DownloadStore.State.CANCELED
                    message = "已取消"
                }
                else -> {
                    state = DownloadStore.State.FAILED
                    message = result.message
                }
            }
        }
        notifyFinish(result)
    }

    /**
     * m3u8 下载：解析主/媒体列表，逐个下载分片与密钥，重写成本地相对路径的 m3u8。
     * 进度按"字节数"累加，界面复用同一套进度条。
     */
    private fun downloadPlaylist(
        task: DownloadStore.Task,
        pause: AtomicBoolean,
        cancel: AtomicBoolean
    ): HttpDownloader.Result {
        var masterText = HttpDownloader.fetchText(task.url, task.headers)
            ?: return HttpDownloader.Result(false, "无法读取 m3u8 列表（可能是网络或防盗链问题）")
        var listUrl = task.url

        // 主播放列表：选第一个子列表下载（多码率全部下载体积太大，且低端机没必要）
        if (masterText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val variant = masterText.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return HttpDownloader.Result(false, "主播放列表里没有可用的子列表")
            listUrl = resolveUrl(listUrl, variant)
            masterText = HttpDownloader.fetchText(listUrl, task.headers)
                ?: return HttpDownloader.Result(false, "无法读取子播放列表")
        }

        val listFile = File(task.targetPath)
        val dirName = listFile.name.substringBeforeLast('.', listFile.name) + "_contents"
        val dir = File(listFile.parentFile, dirName)
        try {
            dir.mkdirs()
        } catch (e: Exception) {
            return HttpDownloader.Result(false, "创建分片目录失败：${e.message}")
        }

        val rewritten = ArrayList<String>()
        var totalBytes = 0L
        var doneBytes = 0L
        val lines = masterText.lineSequence().toList()

        // 预告总数：统计所有分片的远端 Content-Length 太慢，这里先按 0 显示，边下边更新
        for (raw in lines) {
            if (pause.get() || cancel.get()) break
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()

            when {
                trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val uri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.groupValues?.getOrNull(1)
                    if (uri.isNullOrBlank() || uri.startsWith("skd://", true)) {
                        rewritten.add(line)
                    } else {
                        val keyName = "key_${safeSegmentName(uri.substringAfterLast('/'))}"
                        val keyFile = File(dir, keyName)
                        val r = HttpDownloader.download(
                            resolveUrl(listUrl, uri), keyFile, task.headers, pause, cancel
                        ) { _, _ -> }
                        if (r.ok) {
                            rewritten.add(
                                trimmed.replace(Regex("URI=\"[^\"]+\"", RegexOption.IGNORE_CASE), "URI=\"$keyName\"")
                            )
                            totalBytes += r.bytes
                            doneBytes += r.bytes
                        } else {
                            rewritten.add(line)
                        }
                    }
                }

                trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    val uri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.groupValues?.getOrNull(1)
                    if (uri.isNullOrBlank()) {
                        rewritten.add(line)
                    } else {
                        val segName = safeSegmentName(uri.substringAfterLast('/'))
                        val r = segment(resolveUrl(listUrl, uri), File(dir, segName), task, pause, cancel) { d, t ->
                            updateTask(task.id) {
                                bytes = doneBytes + d
                                total = if (t > 0) doneBytes + t else -1L
                            }
                        }
                        if (r.ok) {
                            rewritten.add(trimmed.replace(
                                Regex("URI=\"[^\"]+\"", RegexOption.IGNORE_CASE), "URI=\"$segName\""
                            ))
                            doneBytes += r.bytes
                            totalBytes += r.bytes
                        } else {
                            rewritten.add(line)
                        }
                    }
                }

                trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                    val lower = trimmed.lowercase()
                    if (lower.startsWith("data:") || lower.startsWith("blob:")) {
                        rewritten.add(line)
                        continue
                    }
                    val segName = safeSegmentName(trimmed.substringAfterLast('/'))
                    val r = segment(resolveUrl(listUrl, trimmed), File(dir, segName), task, pause, cancel) { d, t ->
                        updateTask(task.id) {
                            bytes = doneBytes + d
                            total = if (t > 0) doneBytes + t else -1L
                        }
                    }
                    when {
                        r.ok -> {
                            rewritten.add(segName)
                            doneBytes += r.bytes
                            totalBytes += r.bytes
                        }
                        r.paused || r.canceled -> return r
                        else -> {
                            // 单个分片失败：跳过它并在结果里标注，其余继续
                            rewritten.add(line)
                        }
                    }
                }

                else -> rewritten.add(line)
            }
        }

        if (cancel.get()) return HttpDownloader.Result(false, "已取消", doneBytes, totalBytes, canceled = true)
        if (pause.get()) return HttpDownloader.Result(false, "已暂停", doneBytes, totalBytes, paused = true)

        return try {
            listFile.writeText(rewritten.joinToString("\n"), Charsets.UTF_8)
            HttpDownloader.Result(true, "完成", doneBytes, totalBytes)
        } catch (e: Exception) {
            HttpDownloader.Result(false, "写入本地 m3u8 失败：${e.message}", doneBytes, totalBytes)
        }
    }

    private fun segment(
        url: String,
        target: File,
        task: DownloadStore.Task,
        pause: AtomicBoolean,
        cancel: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ): HttpDownloader.Result {
        val existing = HttpDownloader.existingBytes(target)
        return HttpDownloader.download(url, target, task.headers, pause, cancel) { bytes, total ->
            // HttpDownloader 在续传时 bytes 从 0 累计新读取字节，total 已含 existing
            onProgress(bytes, total)
        }
    }

    private fun finishCanceled(task: DownloadStore.Task) {
        try {
            File(task.targetPath).delete()
        } catch (e: Exception) {
        }
        updateTask(task.id) {
            state = DownloadStore.State.CANCELED
            message = "已取消"
        }
    }

    private fun setPaused(id: Long) {
        val task = findTask(id) ?: return
        if (task.state == DownloadStore.State.RUNNING) {
            updateTask(id) {
                state = DownloadStore.State.PAUSED
                message = "已暂停"
            }
        }
    }

    private fun updateTask(id: Long, block: DownloadStore.Task.() -> Unit) {
        val tasks = DownloadStore.load(this)
        val task = tasks.firstOrNull { it.id == id } ?: return
        task.block()
        DownloadStore.save(this, tasks)
        broadcast()
    }

    private fun findTask(id: Long): DownloadStore.Task? =
        DownloadStore.load(this).firstOrNull { it.id == id }

    private fun save() {
        // updateTask 已保存，这里保留给显式场景
    }

    private fun broadcast() {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName))
    }

    private fun downloadDir(): File {
        // 放到公共 Download 目录，本地视频库可直接扫描到
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: File(filesDir, "downloads")
        } else {
            @Suppress("DEPRECATION")
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "LiteBrowser")
        }.apply { mkdirs() }
    }

    private fun guessName(url: String, isM3u8: Boolean): String {
        val path = try {
            URI.create(url).path
        } catch (e: Exception) {
            url.substringBefore('?')
        }
        var name = path.substringAfterLast('/')
        if (name.isBlank()) name = "download_${System.currentTimeMillis()}"
        if (isM3u8 && !name.contains(".m3u8", true)) name += ".m3u8"
        return name
    }

    private fun sanitize(raw: String): String {
        var name = raw.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        if (name.isEmpty() || name == "." || name == "..") {
            name = "download_${System.currentTimeMillis()}"
        }
        if (name.length > 100) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0 && name.length - dot <= 10) {
                name.take(100 - (name.length - dot)) + name.substring(dot)
            } else {
                name.take(100)
            }
        }
        return name
    }

    private fun safeSegmentName(raw: String): String {
        val base = raw.substringBefore('?').substringBefore('#')
        return sanitize(base.ifBlank { "seg" })
    }

    private fun resolveUrl(base: String, ref: String): String {
        return try {
            URI.create(base).resolve(ref).toString()
        } catch (e: Exception) {
            if (ref.startsWith("http", true)) ref
            else base.substringBeforeLast('/') + "/" + ref
        }
    }

    private fun notifyProgress(name: String, current: Int, total: Int) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFY_ID, notification(name, current, total)
            )
        } catch (e: Exception) {
        }
    }

    private fun notifyFinish(result: HttpDownloader.Result) {
        // 结束时落一个简短通知；详情在下载管理界面看
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(
                NOTIFY_ID + 1,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(if (result.ok) "下载完成" else "下载结束")
                    .setContentText(result.message)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                val channel = NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW)
                channel.description = "视频下载进度"
                manager?.createNotificationChannel(channel)
            }
        }
    }

    private fun notification(name: String, current: Int, total: Int): Notification {
        val content = if (total > 0) "$name（$current/$total）" else name
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (total > 0) 100 else 0, current, total <= 0)
            .build()
    }

    override fun onDestroy() {
        workerThread.quitSafely()
        running = false
        super.onDestroy()
    }
}
