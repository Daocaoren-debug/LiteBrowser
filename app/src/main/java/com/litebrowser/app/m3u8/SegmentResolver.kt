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

    /** 这份 m3u8 引用到的本地分片绝对路径（含 EXT-X-MAP 初始化段） */
    fun localSegments(context: Context, file: MediaFile): List<String> {
        if (file.uri.scheme != "file") return emptyList()
        val source = File(file.path)
        val base = source.parentFile ?: return emptyList()
        val text = try {
            source.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return emptyList()
        }

        val result = LinkedHashSet<String>()
        var previousWasVariant = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            var ref: String? = null
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
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
            if (lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("data:") || lower.startsWith("blob:")
            ) continue
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) continue

            val path = resolve(base, ref) ?: continue
            if (path == file.path) continue
            result.add(path)
        }
        return result.toList()
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
