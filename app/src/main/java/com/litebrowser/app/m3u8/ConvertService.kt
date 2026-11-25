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
        worker.post { convertAll(inputs, deleteSource, deleteSegments) }
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

            // 标准 HLS(TS) 的 AAC 需要 aac_adtstoasc；少数非 AAC 音轨会失败，降级重试一次
            var ok = runFFmpeg(input, out.absolutePath, withAacBsf = true)
            if (!ok) {
                if (out.exists()) out.delete()
                ok = runFFmpeg(input, out.absolutePath, withAacBsf = false)
            }

            if (ok && out.exists() && out.length() > 0L) {
                // 先记录再决定是否删源文件：即使删失败，下次也不会重复转换
                ConvertStore.mark(applicationContext, input, out.absolutePath, sourceSize, sourceMtime)
                succeeded.add("${out.name}（${formatSize(out.length())}）")

                if (deleteSource) {
                    // 必须在删源之前解析它引用了哪些分片
                    val segments = if (deleteSegments) {
                        val sourceItem = MediaFile(
                            name = label,
                            path = input,
                            size = sourceSize,
                            uri = Uri.fromFile(source),
                            lastModified = sourceMtime
                        )
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
        if (failed.isNotEmpty()) parts.add("失败 ${failed.size} 个：${failed.joinToString("、")}")
        val summary = parts.joinToString("\n").ifEmpty { "没有需要转换的文件" }

        running = false
        current = 0
        total = 0
        broadcast(ACTION_DONE, succeeded.isNotEmpty(), summary = summary)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runFFmpeg(input: String, output: String, withAacBsf: Boolean): Boolean {
        val args = ArrayList<String>()
        args += "-n"                 // 不覆盖已有文件，界面已提前跳过
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
        args += "-movflags"
        args += "+faststart"
        args += output
        return try {
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            ReturnCode.isSuccess(session.returnCode)
        } catch (e: Throwable) {
            // 32 位机型没有对应 so 时会抛 UnsatisfiedLinkError，不能让它冲垮服务
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
