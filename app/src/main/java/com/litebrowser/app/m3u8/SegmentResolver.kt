package com.litebrowser.app.m3u8

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 解析 m3u8 引用的本地分片，供"删除时连带释放空间"使用。
 *
 * 安全策略：
 * - 只处理 file:// 的本地播放列表；远端 http(s) 分片不碰；
 * - 只删这个播放列表引用的分片，不做目录级 *.ts 通配；
 * - 若某个分片仍被同目录其他 m3u8 引用，则保留（多列表共用很常见）；
 * - 主播放列表指向的子 m3u8 不当成分片删除。
 */
object SegmentResolver {

    private val uriAttr = Regex("URI=\"([^\"]+)\"")

    private fun attr(line: String, name: String): String? =
        Regex("$name=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.getOrNull(1)

    data class PlaylistInfo(
        /** 按 m3u8 顺序排列的本地分片绝对路径（含 EXT-X-MAP 初始化段） */
        val segments: List<String>,
        val missing: List<String>,
        val remote: Int,
        /** 有 EXT-X-KEY 且 METHOD 不是 NONE */
        val encrypted: Boolean,
        /** 有 EXT-X-MAP，即 fMP4 分片 */
        val fmp4: Boolean,
        /** 有 EXT-X-BYTERANGE，分片是同一文件的区间 */
        val byteRange: Boolean,
        /** 主播放列表（引用的下一层还是 m3u8） */
        val master: Boolean,
        /** AES-128 密钥地址（URI），已按 m3u8 所在目录解析成绝对路径或网络地址 */
        val keyUri: String? = null,
        /** #EXT-X-KEY 的 IV，0x 开头的十六进制 */
        val keyIv: String? = null,
        /** #EXT-X-MEDIA-SEQUENCE，未指定 IV 时用它推算 */
        val mediaSequence: Long = 0L
    ) {
        /** 分片文件名没有扩展名时，FFmpeg 的 HLS demuxer 会直接拒绝 */
        val hasExtensionlessSegment: Boolean
            get() = segments.any { path ->
                val name = try { File(path).name } catch (e: Exception) { path }
                !name.contains('.')
            }
    }

    /** 这份 m3u8 引用的本地分片绝对路径（含 EXT-X-MAP 初始化段） */
    fun localSegments(context: Context, file: MediaFile): List<String> = parse(file).segments

    /** 转换前体检：分片是否存在、是否加密/fMP4/BYTERANGE、是否其实是网络播放列表 */
    fun inspect(context: Context, file: MediaFile): PlaylistInfo {
        val info = parse(file)
        val missing = info.segments.filterNot { path ->
            try {
                File(path).exists()
            } catch (e: Exception) {
                true
            }
        }
        return info.copy(missing = missing)
    }

    private fun parse(file: MediaFile): PlaylistInfo {
        val empty = PlaylistInfo(
            segments = emptyList(),
            missing = emptyList(),
            remote = 0,
            encrypted = false,
            fmp4 = false,
            byteRange = false,
            master = false,
            keyUri = null,
            keyIv = null,
            mediaSequence = 0L
        )
        if (file.uri.scheme != "file") return empty
        val source = File(file.path)
        val base = source.parentFile ?: return empty
        val text = try {
            source.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return empty
        }

        val result = LinkedHashSet<String>()
        var remote = 0
        var encrypted = false
        var fmp4 = false
        var byteRange = false
        var master = false
        var keyUri: String? = null
        var keyIv: String? = null
        var mediaSequence = 0L
        var previousWasVariant = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXT-X-KEY:", ignoreCase = true)) {
                val method = attr(line, "METHOD") ?: ""
                if (!method.equals("NONE", ignoreCase = true)) {
                    encrypted = true
                    // 只取第一个可用的 AES-128 密钥；identity 之后可能还有 DRM 的 KEYFORMAT，跳过
                    if (keyUri == null &&
                        (method.equals("AES-128", ignoreCase = true) || method.isEmpty())
                    ) {
                        val rawUri = attr(line, "URI")
                        keyIv = attr(line, "IV")
                        keyUri = when {
                            rawUri.isNullOrBlank() -> null
                            rawUri.startsWith("http://", true) ||
                                rawUri.startsWith("https://", true) -> rawUri
                            else -> resolve(base, rawUri)
                        }
                    }
                }
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true)) {
                val value = line.substringAfter(":", "").trim().toLongOrNull()
                if (value != null) mediaSequence = value
            }
            if (line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true)) byteRange = true
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true)
            ) master = true

            var ref: String? = null
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                    fmp4 = true
                    ref = uriAttr.find(line)?.groupValues?.getOrNull(1)
                }
                // EXT-X-STREAM-INF 的下一行是子播放列表，不能当分片删
                previousWasVariant = line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                    line.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true)
            } else {
                if (!previousWasVariant) ref = line
                previousWasVariant = false
            }

            if (ref.isNullOrBlank()) continue
            val lower = ref.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                remote++
                continue
            }
            if (lower.startsWith("data:") || lower.startsWith("blob:")) continue
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) continue

            val path = resolve(base, ref) ?: continue
            if (path == file.path) continue
            result.add(path)
        }
        return PlaylistInfo(
            segments = result.toList(),
            missing = emptyList(),
            remote = remote,
            encrypted = encrypted,
            fmp4 = fmp4,
            byteRange = byteRange,
            master = master,
            keyUri = keyUri,
            keyIv = keyIv,
            mediaSequence = mediaSequence
        )
    }

    /** 生成 concat demuxer 用的分片列表；路径里的单引号按 concat 规则转义 */
    fun concatList(segments: List<String>): String = buildString {
        for (path in segments) {
            append("file '")
            append(path.replace("'", "'\\''"))
            append("'\n")
        }
    }

    private fun resolve(base: File, ref: String): String? {
        val value = if (ref.startsWith("file://", ignoreCase = true)) {
            Uri.parse(ref).path ?: return null
        } else ref
        val target = if (value.startsWith("/")) File(value) else File(base, value)
        return try {
            target.canonicalPath
        } catch (e: Exception) {
            target.absolutePath
        }
    }

    /** 仍被同目录其他播放列表引用的分片，必须保留 */
    private fun sharedSegments(
        context: Context,
        target: MediaFile,
        candidates: Collection<String>
    ): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val dir = File(target.path).parentFile ?: return emptySet()
        val others = try {
            dir.listFiles { f ->
                f.isFile &&
                    (f.name.endsWith(".m3u8", true) || f.name.endsWith(".m3u", true)) &&
                    f.absolutePath != target.path
            }?.toList()
        } catch (e: Exception) {
            null
        } ?: return emptySet()

        val shared = HashSet<String>()
        for (other in others) {
            val item = MediaFile(
                name = other.name,
                path = other.absolutePath,
                size = other.length(),
                uri = Uri.fromFile(other),
                lastModified = other.lastModified()
            )
            for (segment in localSegments(context, item)) {
                if (segment in candidates) shared.add(segment)
            }
        }
        return shared
    }

    /** 真正可以删的分片（存在且不被其他列表引用） */
    fun releasableSegments(context: Context, file: MediaFile): List<String> {
        val all = localSegments(context, file).filter { File(it).exists() }
        if (all.isEmpty()) return emptyList()
        val shared = sharedSegments(context, file, all)
        return all.filterNot { it in shared }
    }

    fun sizeOf(paths: Collection<String>): Long =
        paths.sumOf { path ->
            try {
                File(path).length()
            } catch (e: Exception) {
                0L
            }
        }

    fun delete(paths: Collection<String>): Int {
        var count = 0
        for (path in paths) {
            try {
                if (File(path).delete()) count++
            } catch (e: Exception) {
                // 单个失败不影响其余分片
            }
        }
        return count
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }
}
