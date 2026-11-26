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
import android.os.SystemClock
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

        /** 进度落盘/广播的最小间隔：太频繁会把主线程刷爆，按钮就点不动了 */
        private const val PROGRESS_INTERVAL_MS = 350L

        @Volatile
        var running = false
    }

    private val workerThread = HandlerThread("litebrowser-download").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    /** 多个线程（工作线程 / 主线程指令）都会读写任务文件，必须串行化，否则状态会互相覆盖 */
    private val storeLock = Any()
    private var lastReportAt = 0L
    @Volatile
    private var currentName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotBlank()) addTask(intent, url)
            }
            ACTION_PAUSE -> pause(intent.getLongExtra(EXTRA_ID, -1L))
            ACTION_RESUME -> resume(intent.getLongExtra(EXTRA_ID, -1L))
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                cancelFlags.getOrPut(id) { AtomicBoolean(false) }.set(true)
                pauseFlags.getOrPut(id) { AtomicBoolean(false) }.set(false)
                val task = findTask(id)
                if (task != null && task.state != DownloadStore.State.RUNNING) {
                    finishCanceled(task)
                } else if (task != null) {
                    // 正在下载：立即置为已取消，下载线程在下一个数据块处退出
                    updateTask(id) {
                        state = DownloadStore.State.CANCELED
                        message = "已取消"
                    }
                }
            }
            ACTION_RETRY -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                val task = findTask(id)
                if (task != null &&
                    task.state != DownloadStore.State.QUEUED &&
                    task.state != DownloadStore.State.RUNNING
                ) {
                    pauseFlags[id] = AtomicBoolean(false)
                    cancelFlags[id] = AtomicBoolean(false)
                    updateTask(id) {
                        state = DownloadStore.State.QUEUED
                        message = ""
                    }
                    ensureForeground()
                    worker.post { pump() }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** 暂停：立刻落盘并广播，界面马上有反馈；下载线程在下一个数据块处自行退出 */
    private fun pause(id: Long) {
        pauseFlags.getOrPut(id) { AtomicBoolean(false) }.set(true)
        updateTask(id) {
            if (state == DownloadStore.State.QUEUED || state == DownloadStore.State.RUNNING) {
                state = DownloadStore.State.PAUSED
                message = "已暂停"
            }
        }
    }

    private fun resume(id: Long) {
        val task = findTask(id) ?: return
        if (task.state != DownloadStore.State.PAUSED && task.state != DownloadStore.State.FAILED) return
        pauseFlags[id] = AtomicBoolean(false)
        cancelFlags[id] = AtomicBoolean(false)
        updateTask(id) {
            state = DownloadStore.State.QUEUED
            message = ""
        }
        ensureForeground()
        worker.post { pump() }
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
            ensureForeground()
            worker.post { pump() }
        }
    }

    private fun addTask(intent: Intent, url: String) {
        val headers = HashMap<String, String>()
        (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<*, *>)?.forEach { (k, v) ->
            if (k is String && v is String) headers[k] = v
        }
        val referenceName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val isM3u8 = url.contains(".m3u8", ignoreCase = true) ||
            intent.getBooleanExtra(EXTRA_M3U8, false)

        val task = synchronized(storeLock) {
            val tasks = DownloadStore.load(this)

            // 去重：同一链接已在队列/下载/暂停中，直接忽略（连点下载按钮不再重复入队）
            if (tasks.any { it.url == url && it.state in DownloadStore.ACTIVE_STATES }) return
            // 已下载完成且文件还在，也没必要再下一遍
            if (tasks.any {
                    it.url == url && it.state == DownloadStore.State.DONE &&
                        File(it.targetPath).exists()
                }
            ) return

            var name = sanitize(
                if (referenceName.isNotBlank()) referenceName else guessName(url, isM3u8)
            )
            // 嗅探到的标题通常是纯文字（无后缀），补上扩展名，否则下载完系统/内置播放器都认不出
            val hasVideoExt =
                listOf(".mp4", ".m4v", ".m3u8", ".m3u", ".webm", ".mkv", ".flv", ".mov")
                    .any { name.endsWith(it, ignoreCase = true) }
            if (!hasVideoExt) {
                val ext = when {
                    isM3u8 -> ".m3u8"
                    looksLikeVideoUrl(url) -> ".mp4"
                    else -> ""
                }
                if (ext.isNotEmpty()) name += ext
            }
            name = uniqueName(tasks, name)

            val created = DownloadStore.Task(
                id = DownloadStore.nextId(tasks),
                url = url,
                name = name,
                targetPath = File(DownloadStore.dir(this), name).absolutePath,
                headers = headers,
                m3u8 = isM3u8
            )
            tasks.add(created)
            DownloadStore.save(this, tasks)
            created
        }
        pauseFlags[task.id] = AtomicBoolean(false)
        cancelFlags[task.id] = AtomicBoolean(false)
        ensureForeground()
        broadcast()
        worker.post { pump() }
    }

    /** 同名文件/同名任务自动加序号，避免两个视频互相覆盖 */
    private fun uniqueName(tasks: List<DownloadStore.Task>, name: String): String {
        val dir = DownloadStore.dir(this)
        val used = tasks.filter { it.state != DownloadStore.State.CANCELED }
            .map { it.targetPath }.toHashSet()
        var candidate = name
        var index = 2
        while (index < 1000 &&
            (File(dir, candidate).exists() || used.contains(File(dir, candidate).absolutePath))
        ) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) {
                name.substring(0, dot) + " ($index)" + name.substring(dot)
            } else {
                "$name ($index)"
            }
            index++
        }
        return candidate
    }

    private fun ensureForeground() {
        try {
            startForeground(NOTIFY_ID, notification("准备下载", 0, 0))
            running = true
        } catch (e: Exception) {
        }
    }

    /** 串行处理队列：一个任务跑完才下一个，避免多个连接拖死低端机 */
    private fun pump() {
        ensureForeground()
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
        // 排队期间被暂停：直接跳过，不要把工作线程堵在这里（否则后面的任务全卡住）
        if (pause.get()) {
            updateTask(task.id) {
                state = DownloadStore.State.PAUSED
                message = "已暂停"
            }
            return
        }

        ensureForeground()
        currentName = task.name
        lastReportAt = 0L
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
                reportProgress(task.id, bytes, total)
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
        // 取消时把 m3u8 的分片目录也清掉，避免留下占空间的无用分片
        if (result.canceled && task.m3u8) removeSegments(task.targetPath)
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
                            reportProgress(task.id, doneBytes + d, if (t > 0) doneBytes + t else -1L)
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
                        reportProgress(task.id, doneBytes + d, if (t > 0) doneBytes + t else -1L)
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
            if (task.m3u8) removeSegments(task.targetPath)
        } catch (e: Exception) {
        }
        updateTask(task.id) {
            state = DownloadStore.State.CANCELED
            message = "已取消"
        }
    }

    /** 删除 m3u8 对应的分片目录 */
    private fun removeSegments(listPath: String) {
        try {
            val listFile = File(listPath)
            val dir = File(listFile.parentFile, listFile.name.substringBeforeLast('.') + "_contents")
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        } catch (e: Exception) {
        }
    }

    private fun updateTask(id: Long, block: DownloadStore.Task.() -> Unit) {
        synchronized(storeLock) {
            val tasks = DownloadStore.load(this)
            val task = tasks.firstOrNull { it.id == id } ?: return
            task.block()
            DownloadStore.save(this, tasks)
        }
        broadcast()
    }

    /** 进度节流：落盘 + 广播 + 通知都限速，避免每个数据块都刷主线程（按钮会因此点不动） */
    private fun reportProgress(id: Long, bytes: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportAt < PROGRESS_INTERVAL_MS) return
        lastReportAt = now
        updateTask(id) {
            this.bytes = bytes
            if (total > 0) this.total = total
        }
        notifyProgress(currentName, clampInt(bytes), clampInt(total))
    }

    private fun clampInt(value: Long): Int =
        if (value <= 0) 0 else value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun findTask(id: Long): DownloadStore.Task? =
        synchronized(storeLock) { DownloadStore.load(this) }.firstOrNull { it.id == id }

    private fun broadcast() {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName))
    }

    /** URL 路径或 mime 暗示是视频（直链常被 CDN 隐藏扩展名，用路径关键字兜底） */
    private fun looksLikeVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("video", true) ||
            listOf(".mp4", ".m4v", ".webm", ".mkv", ".flv", ".mov", ".3gp")
                .any { lower.contains(it) }
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
