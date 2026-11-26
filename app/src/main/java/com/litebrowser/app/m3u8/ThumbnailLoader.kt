package com.litebrowser.app.m3u8

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 轻量缩略图加载，按命中率与开销从低到高排列：
 * 1) 已转出的同名 mp4（最可靠）或同目录同名封面图片
 * 2) m3u8 里的 EXT-X-THUMBNAIL / EXT-X-IMAGE-STREAM-INF 标签
 * 3) 第一个本地分片抽帧：系统解码器先试，失败用 FFmpeg（无扩展名分片也能读）
 * 全部在后台线程执行，主线程回调，带内存缓存。
 */
object ThumbnailLoader {

    private const val HEAD_LIMIT = 32 * 1024
    private const val THUMB_WIDTH = 200
    private const val HTTP_TIMEOUT = 3000

    private val cache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val thumbnailTag = Regex("URI=\"([^\"]+)\"")
    private val imageStreamTag = Regex("#EXT-X-IMAGE-STREAM-INF:[^\\r\\n]*")

    fun get(key: String): Bitmap? = cache.get(key)

    fun load(context: Context, file: MediaFile, callback: (Bitmap?) -> Unit) {
        val key = file.uri.toString()
        val cached = cache.get(key)
        if (cached != null) {
            mainHandler.post { callback(cached) }
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val bitmap = try {
                loadSync(appContext, file)
            } catch (e: Throwable) {
                null
            }
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post { callback(bitmap) }
        }
    }

    private fun loadSync(context: Context, file: MediaFile): Bitmap? {
        // MP4：系统解码器取首帧，失败再交给 FFmpeg
        if (file.kind == MediaFile.Kind.MP4) {
            return firstFrame(context, file.uri)
                ?: ffmpegFrame(context, file.path)
        }

        // 1) 优先用转换产物的同名 mp4：mp4 首帧比 TS 分片稳定得多
        if (file.uri.scheme == "file") {
            val converted = File(ConvertService.outputPathOf(file.path))
            if (converted.exists() && converted.length() > 0L) {
                firstFrame(context, Uri.fromFile(converted))?.let { return it }
            }

            // 同目录同名封面图片
            val baseName = file.name.substringBeforeLast('.', file.name)
            val parent = try {
                File(file.path).parentFile
            } catch (e: Exception) {
                null
            }
            if (parent != null) {
                val names = listOf(
                    "$baseName.jpg", "$baseName.jpeg", "$baseName.png",
                    "$baseName.webp", "${file.name}.jpg", "${file.name}.png"
                )
                for (n in names) {
                    val cover = File(parent, n)
                    if (cover.exists()) {
                        decodeFile(cover.absolutePath)?.let { return it }
                    }
                }
            }
        }

        val head = readHead(context, file.uri) ?: return null

        // 2) HLS 缩略图标签
        val imageLine = imageStreamTag.find(head)?.value
            ?: head.lineSequence().firstOrNull { it.startsWith("#EXT-X-THUMBNAIL") }
        val uriRef = imageLine?.let { thumbnailTag.find(it)?.groupValues?.getOrNull(1) }
        if (!uriRef.isNullOrBlank()) {
            resolve(file.uri, uriRef)?.let { uri ->
                decodeUri(context, uri)?.let { return it }
            }
        }

        // 3) 第一个本地分片抽帧。用 SegmentResolver 解析，正确处理 MAP / 变体列表 / 中文路径
        val info = SegmentResolver.inspect(context, file)
        // fMP4 需要 init 段和媒体段合起来才能解，抽帧会失败，跳过
        if (info.fmp4) return null
        val first = info.segments.firstOrNull() ?: return null

        val firstKey = info.segmentKeys.firstOrNull()
        if (firstKey?.keyUri != null) {
            // 第一段是密文，直接抽帧是花屏，先解密第一段
            val work = File(context.cacheDir, "thumb_dec_${System.currentTimeMillis()}")
            val result = HlsDecryptor.decryptFirst(context, info, work)
            try {
                result.paths.firstOrNull()?.let { ffmpegFrame(context, it) }?.let { return it }
            } finally {
                HlsDecryptor.cleanup(result, work)
            }
        } else {
            // 系统解码器便宜先试；无扩展名 TS 一般会失败，再交给 FFmpeg
            firstFrame(context, Uri.fromFile(File(first)))?.let { return it }
            ffmpegFrame(context, first)?.let { return it }
        }
        return null
    }

    /** 用 FFmpeg 抽一帧，TS / 无扩展名分片都能读；输出临时 jpg 落在应用缓存目录 */
    private fun ffmpegFrame(context: Context, path: String): Bitmap? {
        val out = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
        return try {
            val args = arrayOf(
                "-hide_banner", "-loglevel", "error", "-y",
                "-threads", "1",
                "-i", path,
                "-frames:v", "1",
                "-vf", "scale=$THUMB_WIDTH:-2",
                out.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(args)
            if (!ReturnCode.isSuccess(session.returnCode)) return null
            if (!out.exists() || out.length() <= 0L) return null
            BitmapFactory.decodeFile(out.absolutePath)
        } catch (e: Throwable) {
            // 32 位设备没有 FFmpeg so 时会抛 UnsatisfiedLinkError
            null
        } finally {
            try {
                out.delete()
            } catch (e: Exception) {
            }
        }
    }

    private fun readHead(context: Context, uri: Uri): String? {
        val input = try {
            if (uri.scheme == "file") {
                FileInputStream(File(uri.path ?: return null))
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            null
        } ?: return null

        return input.use { stream ->
            val buffer = ByteArray(HEAD_LIMIT)
            var total = 0
            while (total < HEAD_LIMIT) {
                val read = stream.read(buffer, total, HEAD_LIMIT - total)
                if (read <= 0) break
                total += read
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }
    }

    private fun resolve(base: Uri, ref: String): Uri? {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return Uri.parse(trimmed)
        }
        if (trimmed.startsWith("file://", true) || trimmed.startsWith("content://", true)) {
            return Uri.parse(trimmed)
        }
        // 本地播放列表直接按 File 解析，避开 URI.create 对中文/空格路径的转义问题
        if (base.scheme == "file") {
            val parent = File(base.path ?: return null).parentFile ?: return null
            val target = if (trimmed.startsWith("/")) File(trimmed) else File(parent, trimmed)
            return Uri.fromFile(target)
        }
        return try {
            Uri.parse(java.net.URI.create(base.toString()).resolve(trimmed).toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (uri.scheme == "http" || uri.scheme == "https") {
                val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                conn.connectTimeout = HTTP_TIMEOUT
                conn.readTimeout = HTTP_TIMEOUT
                conn.inputStream.use { BitmapFactory.decodeStream(it) }?.let { resize(it) }
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }?.let { resize(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFile(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeFile(path, options)?.let { resize(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun firstFrame(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && uri.scheme == "content") return null
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path ?: return null)
            } else {
                retriever.setDataSource(context, uri)
            }
            val frame = try {
                retriever.getFrameAtTime(0L)
            } catch (e: Exception) {
                null
            }
            if (frame == null) return null
            return resize(frame)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var size = 1
        val max = THUMB_WIDTH * 2
        while (width / size > max || height / size > max) size *= 2
        return size
    }

    private fun resize(source: Bitmap): Bitmap {
        if (source.width <= THUMB_WIDTH) return source
        val height = source.height * THUMB_WIDTH / source.width
        // 不 recycle 源图：缩略图会进 LruCache，recycle 后复用会导致"使用已回收的 bitmap"崩溃
        return Bitmap.createScaledBitmap(source, THUMB_WIDTH, height, true)
    }
}
