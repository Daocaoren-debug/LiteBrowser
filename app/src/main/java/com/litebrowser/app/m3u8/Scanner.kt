package com.litebrowser.app.m3u8

import android.net.Uri
import android.os.Environment
import java.io.File

object Scanner {

    // 跳过系统/应用数据目录，避免无意义遍历，低端机上更快
    private val SKIP_DIRS = setOf(
        "Android", "data", "obb", "cache", "temp", "tmp", "LOST.DIR", ".git"
    )

    /**
     * 扫描外部存储。
     *
     * @param knownPaths 已记录过的文件路径，命中直接跳过（增量）
     * @param since      保留参数以兼容调用方；不再用它做目录剪枝。
     *                   原因：新建/改名的文件只更新其直接父目录的 mtime，祖先目录 mtime 不变，
     *                   按目录 mtime 剪枝会把整个子树跳过（例如转换出的 mp4 和源 m3u8 同目录，
     *                   但其上级目录 mtime 早于上次扫描，导致"扫描新增"永远扫不到它）。
     *                   现在只靠 knownPaths 做文件级去重，正确且仍能跳过已知文件。
     */
    fun scanStorage(knownPaths: Set<String>, since: Long): List<MediaFile> {
        val result = ArrayList<MediaFile>()
        val root = Environment.getExternalStorageDirectory()
        traverse(root, result, 0, knownPaths, since)
        return result
    }

    /** 本地视频库关心的类型：HLS 播放列表和常见 MP4 容器 */
    fun isMediaName(lowerCaseName: String): Boolean =
        lowerCaseName.endsWith(".m3u8") || lowerCaseName.endsWith(".m3u") ||
            lowerCaseName.endsWith(".mp4") || lowerCaseName.endsWith(".m4v")

    private fun traverse(
        dir: File,
        out: MutableList<MediaFile>,
        depth: Int,
        knownPaths: Set<String>,
        since: Long
    ) {
        if (depth > 12) return

        // 不做目录 mtime 剪枝：深层目录里新建的文件不会更新祖先目录 mtime，
        // 剪枝会漏掉新转出的 mp4。文件级去重（knownPaths）已足够。

        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        } ?: return

        for (file in children) {
            try {
                if (file.isDirectory) {
                    val n = file.name
                    if (n.startsWith(".") || n in SKIP_DIRS) continue
                    traverse(file, out, depth + 1, knownPaths, since)
                } else {
                    val lower = file.name.lowercase()
                    if (!isMediaName(lower)) continue
                    val path = file.absolutePath
                    if (path in knownPaths) continue
                    out.add(
                        MediaFile(
                            name = file.name,
                            path = path,
                            size = file.length(),
                            uri = Uri.fromFile(file),
                            lastModified = file.lastModified()
                        )
                    )
                }
            } catch (e: Exception) {
                // 单个文件不可读时跳过，不影响整体扫描
            }
        }
    }
}
