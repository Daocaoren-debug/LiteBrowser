package com.litebrowser.app.m3u8

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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * m3u8 -> mp4 无损封装转换服务。
 *
 * 关键点：
 * - 用 -c copy 直接搬运音视频流，不重新编码，所以画质完全不变、体积不会变大；
 * - HLS 里的 AAC 是 ADTS 封装，进 MP4 必须过 aac_adtstoasc，否则音频可能无声；
 * - -movflags +faststart 把 moov 挪到文件头，方便边下边播，不会增加体积；
 * - 前台服务 + 后台单线程，切到后台或锁屏也能把队列跑完。
 */
class ConvertService : Service() {

    companion object {
        const val EXTRA_INPUTS = "inputs"
        const val EXTRA_DELETE_SOURCE = "deleteSource"
        const val EXTRA_DELETE_SEGMENTS = "deleteSegments"
        const val ACTION_PROGRESS = "com.litebrowser.app.convert.PROGRESS"
        const val ACTION_DONE = "com.litebrowser.app.convert.DONE"
        const val ACTION_REMOVED = "com.litebrowser.app.convert.REMOVED"
        const val EXTRA_PATH = "path"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_NAME = "name"
        const val EXTRA_OK = "ok"
        const val EXTRA_SUMMARY = "summary"

        private const val CHANNEL = "convert"
        private const val NOTIFY_ID = 2026

        /** 最近一次转换失败的错误日志，界面上可以查看 */
        const val ERROR_FILE = "convert_error.txt"

        /** 输出与源文件同目录，仅改扩展名；界面也用它提前判断目标是否已存在 */
        fun outputPathOf(input: String): String {
            val source = File(input)
            val base = source.name.substringBeforeLast('.', source.name)
            return File(source.parentFile, "$base.mp4").absolutePath
        }

        /** 供界面进入时读取当前进度（进程内单例，进程被杀自然归零） */
        @Volatile
        var running = false
        @Volatile
        var current = 0
        @Volatile
        var total = 0
    }

    private val workerThread = HandlerThread("m3u8-convert").apply { start() }
    private val worker = Handler(workerThread.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputs: List<String> =
            intent?.getStringArrayListExtra(EXTRA_INPUTS)?.toList() ?: emptyList()
        if (inputs.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            // 已有队列在跑，直接拒绝，避免两个 FFmpeg 抢 CPU 把低端机拖死
            broadcast(
                ACTION_DONE,
                false,
                summary = "已有转换任务正在进行，请等它跑完"
            )
            // 不能 stopSelf()：当前实例正跑着上一批任务
            return START_NOT_STICKY
        }

        running = true
        current = 0
        total = inputs.size
        createChannel()
        startForeground(NOTIFY_ID, notification("准备转换 ${inputs.size} 个文件", 0, inputs.size))

        val deleteSource = intent?.getBooleanExtra(EXTRA_DELETE_SOURCE, false) == true
        val deleteSegments = deleteSource &&
            intent?.getBooleanExtra(EXTRA_DELETE_SEGMENTS, false) == true
        worker.post {
            try {
                convertAll(inputs, deleteSource, deleteSegments)
            } catch (e: Throwable) {
                // 任何意外都要把状态复位，否则之后每次都会被“已有任务”挡住
                lastError = "转换任务异常：" + e.toString()
                broadcast(ACTION_DONE, false, summary = lastError)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                running = false
                current = 0
                total = 0
            }
        }
        return START_NOT_STICKY
    }

    private fun convertAll(
        inputs: List<String>,
        deleteSource: Boolean,
        deleteSegments: Boolean
    ) {
        val succeeded = ArrayList<String>()
        val failed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val removed = ArrayList<String>()
        var releasedBytes = 0L
        var removedSourceBytes = 0L

        for ((index, input) in inputs.withIndex()) {
            current = index + 1
            val source = File(input)
            val out = File(outputPathOf(input))
            val label = source.name
            val sourceSize = try { source.length() } catch (e: Exception) { 0L }
            val sourceMtime = try { source.lastModified() } catch (e: Exception) { 0L }

            broadcast(ACTION_PROGRESS, currentValue = current, totalValue = total, name = label)
            notifyProgress(label)

            if (!source.exists()) {
                failed.add("$label（源文件不存在）")
                continue
            }
            if (out.exists()) {
                skipped.add("${out.name}（目标已存在）")
                continue
            }

            val sourceItem = MediaFile(
                name = label,
                path = input,
                size = sourceSize,
                uri = Uri.fromFile(source),
                lastModified = sourceMtime
            )
            // 先体检分片：只下到 m3u8 列表、或本身就是网络流，是最常见的“转换失败”原因
            val info = SegmentResolver.inspect(applicationContext, sourceItem)
            if (info.segments.isEmpty() && info.remote > 0) {
                failed.add("$label（是网络播放列表，分片不在本地）")
                continue
            }
            if (info.segments.isNotEmpty() && info.missing.size == info.segments.size) {
                failed.add("$label（分片不在本地，可能只下载了 m3u8 列表）")
                continue
            }
            if (info.missing.isNotEmpty()) {
                failed.add(
                    "$label（缺少 ${info.missing.size}/${info.segments.size} 个分片，无法完整转换）"
                )
                continue
            }

            // 无扩展名分片必须走 concat；fMP4 / BYTERANGE 用 concat 会得到错误内容，
            // 提前给出准确原因。AES-128 加密会先在本地解密再 concat。
            if (info.hasExtensionlessSegment && (info.fmp4 || info.byteRange)) {
                failed.add(
                    "$label（分片无扩展名且为 ${if (info.fmp4) "fMP4" else "BYTERANGE"}，暂不支持转换）"
                )
                continue
            }

            var ok = attemptAll(input, out, info)

            // 仍失败就把输出先写到应用专属目录，再拷回目标目录。
            // 绕开 Android 11+ 上原生代码对公共目录的写入限制，也能区分"写不了"还是"转不了"。
            if (!ok) {
                val tempDir = (getExternalFilesDir("convert") ?: File(cacheDir, "convert"))
                    .apply { mkdirs() }
                val temp = File(tempDir, "tmp_${System.currentTimeMillis()}.mp4")
                val tempOk = attemptAll(input, temp, info)
                if (tempOk) {
                    ok = copyFile(temp, out)
                    temp.delete()
                }
            }

            if (ok && out.exists() && out.length() > 0L) {
                // 先记录再决定是否删源文件：即使删失败，下次也不会重复转换
                ConvertStore.mark(applicationContext, input, out.absolutePath, sourceSize, sourceMtime)
                succeeded.add("${out.name}（${formatSize(out.length())}）")

                if (deleteSource) {
                    // 必须在删源之前解析它引用了哪些分片
                    val segments = if (deleteSegments) {
                        SegmentResolver.releasableSegments(applicationContext, sourceItem)
                    } else emptyList()
                    val segmentBytes = SegmentResolver.sizeOf(segments)

                    if (source.delete()) {
                        if (segments.isNotEmpty()) {
                            SegmentResolver.delete(segments)
                            releasedBytes += segmentBytes
                        }
                        ConvertStore.removeSource(applicationContext, input)
                        removed.add(input)
                        removedSourceBytes += sourceSize
                        broadcast(ACTION_REMOVED, path = input)
                    } else {
                        succeeded[succeeded.size - 1] += "（源 m3u8 删除失败，已标记为已转换）"
                    }
                }
            } else {
                if (out.exists()) out.delete()
                failed.add(label)
            }
        }

        val parts = ArrayList<String>()
        if (succeeded.isNotEmpty()) parts.add("成功 ${succeeded.size} 个：${succeeded.joinToString("、")}")
        if (removed.isNotEmpty()) {
            val freed = SegmentResolver.formatSize(releasedBytes + removedSourceBytes)
            parts.add("已删除 ${removed.size} 个源 m3u8，释放约 $freed")
        }
        if (skipped.isNotEmpty()) parts.add("跳过 ${skipped.size} 个：${skipped.joinToString("、")}")
        if (failed.isNotEmpty()) {
            parts.add("失败 ${failed.size} 个：${failed.joinToString("、")}")
            if (lastError.isNotBlank()) {
                val diagnostic = buildString {
                    append("ABI=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
                    append("SDK=").append(Build.VERSION.SDK_INT).append('\n')
                    append(
                        try {
                            val version = FFmpegKit.execute("-version")
                            "FFmpeg 可执行：" +
                                (version.output?.lineSequence()?.firstOrNull() ?: "无输出")
                        } catch (e: Throwable) {
                            "FFmpeg 无法执行：" + e.toString()
                        }
                    ).append("\n\n")
                    append(lastError)
                }
                try {
                    File(filesDir, ERROR_FILE).writeText(diagnostic, Charsets.UTF_8)
                } catch (e: Exception) {
                    // 日志写不出来不影响主流程
                }
                parts.add("错误：${lastError.takeLast(400)}")
            }
        }
        val summary = parts.joinToString("\n").ifEmpty { "没有需要转换的文件" }

        running = false
        current = 0
        total = 0
        broadcast(ACTION_DONE, succeeded.isNotEmpty(), summary = summary)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var lastError: String = ""

    /**
     * 按优先级尝试：
     * 1. 分片无扩展名时，先用 concat 拼（HLS demuxer 对无扩展名分片是硬拒绝）；
     * 2. 常规 HLS 路径，带/不带 aac_adtstoasc 各试一次；
     * 3. 分片有扩展名但 HLS 失败时，再用 concat 兜底。
     */
    private fun attemptAll(
        input: String,
        out: File,
        info: SegmentResolver.PlaylistInfo
    ): Boolean {
        val canConcat = info.segments.isNotEmpty() &&
            info.missing.isEmpty() &&
            !info.fmp4 &&
            !info.byteRange

        var ok = false
        if (canConcat && info.hasExtensionlessSegment) {
            if (info.encrypted) {
                // 先把 AES-128 分片解密成临时 .ts，再拼；密钥读不到就直接报准确原因
                val work = File(cacheDir, "dec_${System.currentTimeMillis()}")
                val decrypted = HlsDecryptor.decrypt(applicationContext, info, work)
                if (decrypted.error != null) {
                    lastError = "分片解密失败：" + decrypted.error
                    return false
                }
                try {
                    ok = tryConcat(out, decrypted.paths)
                } finally {
                    HlsDecryptor.cleanup(decrypted.paths, work)
                }
            } else {
                ok = tryConcat(out, info.segments)
            }
        }
        if (!ok) ok = tryConvert(input, out, withAacBsf = true)
        if (!ok) ok = tryConvert(input, out, withAacBsf = false)
        if (!ok && canConcat && !info.hasExtensionlessSegment && !info.encrypted) {
            ok = tryConcat(out, info.segments)
        }
        return ok
    }

    private fun tryConvert(input: String, out: File, withAacBsf: Boolean): Boolean {
        if (out.exists()) out.delete()
        val ok = runFFmpeg(input, out.absolutePath, withAacBsf)
        return ok && out.exists() && out.length() > 0L
    }

    private fun tryConcat(out: File, segments: List<String>): Boolean {
        if (out.exists()) out.delete()
        val listFile = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
        return try {
            listFile.writeText(SegmentResolver.concatList(segments), Charsets.UTF_8)
            val ok = runConcatFfmpeg(listFile.absolutePath, out.absolutePath)
            ok && out.exists() && out.length() > 0L
        } catch (e: Exception) {
            lastError = "生成 concat 分片列表失败：" + e.toString()
            false
        } finally {
            try {
                listFile.delete()
            } catch (e: Exception) {
                // 列表文件删不掉不影响结果
            }
        }
    }

    private fun runFFmpeg(input: String, output: String, withAacBsf: Boolean): Boolean {
        val args = ArrayList<String>()
        args += "-hide_banner"
        args += "-n"                 // 不覆盖已有文件，界面已提前跳过
        // 本地 HLS 的分片是 file:，显式放行；部分 FFmpeg 版本默认白名单不含 file
        args += "-protocol_whitelist"
        args += "file,crypto,data,http,https,tcp,tls"
        args += "-allowed_extensions"
        args += "ALL"
        args += "-fflags"
        args += "+genpts"            // 部分 TS 时间戳缺失/回绕，补生成后再封装
        args += "-i"
        args += input
        args += "-map"
        args += "0:v:0"              // 只取第一条视频轨，避免无关数据流撑大文件
        args += "-map"
        args += "0:a?"               // 有音轨才映射
        args += "-c"
        args += "copy"               // 核心：不重编码，画质不变
        if (withAacBsf) {
            args += "-bsf:a"
            args += "aac_adtstoasc"
        }
        args += "-avoid_negative_ts"
        args += "make_zero"
        args += "-movflags"
        args += "+faststart"
        args += output
        return execute(args)
    }

    /** 分片无扩展名时用 concat demuxer 拼，绕开 HLS demuxer 的扩展名校验 */
    private fun runConcatFfmpeg(listPath: String, output: String): Boolean {
        val args = ArrayList<String>()
        args += "-hide_banner"
        args += "-n"
        args += "-f"
        args += "concat"
        args += "-safe"
        args += "0"
        args += "-fflags"
        args += "+genpts"
        args += "-i"
        args += listPath
        args += "-map"
        args += "0:v:0"
        args += "-map"
        args += "0:a?"
        args += "-c"
        args += "copy"
        args += "-avoid_negative_ts"
        args += "make_zero"
        args += "-movflags"
        args += "+faststart"
        args += output
        return execute(args)
    }

    private fun execute(args: List<String>): Boolean {
        return try {
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (!ok) {
                lastError = buildString {
                    append("returnCode=").append(session.returnCode).append('\n')
                    append("arguments=").append(args.joinToString(" ")).append('\n')
                    append(session.output?.takeLast(3000) ?: "（FFmpeg 没有输出日志）")
                }
            }
            ok
        } catch (e: Throwable) {
            // 32 位机型没有对应 so 时会抛 UnsatisfiedLinkError，不能让它冲垮服务
            lastError = buildString {
                append("FFmpegKit 调用异常：").append(e.toString()).append('\n')
                append("arguments=").append(args.joinToString(" "))
            }
            false
        }
    }

    private fun copyFile(from: File, to: File): Boolean {
        return try {
            to.parentFile?.mkdirs()
            from.inputStream().use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: Exception) {
            lastError = "临时文件拷贝到目标目录失败：" + e.toString()
            try {
                if (to.exists()) to.delete()
            } catch (ignored: Exception) {
            }
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL,
                    "视频转换",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "m3u8 转 mp4 的后台进度"
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }
    }

    private fun notification(text: String, current: Int, total: Int): Notification {
        val content = if (total > 0) "$text（$current/$total）" else text
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("正在转换视频")
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), current, total <= 0)
            .build()
    }

    private fun notifyProgress(name: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFY_ID, notification(name, current, total))
        } catch (e: Exception) {
            // 部分定制 ROM 会限制通知，不影响转换本身
        }
    }

    private fun broadcast(
        action: String,
        ok: Boolean = false,
        currentValue: Int = current,
        totalValue: Int = total,
        name: String = "",
        summary: String = "",
        path: String = ""
    ) {
        val intent = Intent(action).setPackage(packageName)
        intent.putExtra(EXTRA_OK, ok)
        intent.putExtra(EXTRA_CURRENT, currentValue)
        intent.putExtra(EXTRA_TOTAL, totalValue)
        intent.putExtra(EXTRA_NAME, name)
        intent.putExtra(EXTRA_SUMMARY, summary)
        intent.putExtra(EXTRA_PATH, path)
        sendBroadcast(intent)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }

    override fun onDestroy() {
        workerThread.quitSafely()
        super.onDestroy()
    }
}
