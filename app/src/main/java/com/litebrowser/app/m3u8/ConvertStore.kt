package com.litebrowser.app.m3u8

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 已转换记录。
 *
 * 不依赖"删掉源文件"来避免重复：即使保留源 m3u8，只要记录还在、输出 mp4 还在、
 * 源文件没被改动，就认定为已转换，列表里置灰、批量转换自动跳过。
 * 源文件被改动（大小/时间变了）或输出 mp4 被删掉，记录自动失效，可以重新转。
 */
object ConvertStore {

    private const val FILE_NAME = "converted.json"
    private const val VERSION = 1

    data class Record(
        val outputPath: String,
        val sourceSize: Long,
        val sourceMtime: Long,
        val convertedAt: Long
    )

    private var cache: MutableMap<String, Record>? = null

    private fun load(context: Context): MutableMap<String, Record> {
        cache?.let { return it }
        val result = LinkedHashMap<String, Record>()
        try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists()) {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                if (root.optInt("version", 0) == VERSION) {
                    val array = root.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val o = array.optJSONObject(i) ?: continue
                        val source = o.optString("source")
                        val output = o.optString("output")
                        if (source.isEmpty() || output.isEmpty()) continue
                        result[source] = Record(
                            outputPath = output,
                            sourceSize = o.optLong("size"),
                            sourceMtime = o.optLong("mtime"),
                            convertedAt = o.optLong("at")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // 记录损坏就当没有，最多导致多转一次，不会丢数据
        }
        cache = result
        return result
    }

    private fun save(context: Context) {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            val array = JSONArray()
            for ((source, rec) in load(context)) {
                val o = JSONObject()
                o.put("source", source)
                o.put("output", rec.outputPath)
                o.put("size", rec.sourceSize)
                o.put("mtime", rec.sourceMtime)
                o.put("at", rec.convertedAt)
                array.put(o)
            }
            root.put("items", array)
            val dir = context.applicationContext.filesDir
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            val target = File(dir, FILE_NAME)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            // 写失败不影响转换本身
        }
    }

    fun isConverted(context: Context, file: MediaFile): Boolean {
        if (file.kind != MediaFile.Kind.M3U8) return false
        // 兼容旧版本转出来的文件：没有记录但同名 mp4 在，也算已转换
        if (file.uri.scheme == "file" &&
            File(ConvertService.outputPathOf(file.path)).exists()
        ) return true
        val rec = load(context)[file.path] ?: return false
        if (rec.sourceSize != file.size) return false
        if (rec.sourceMtime != 0L && file.lastModified != 0L && rec.sourceMtime != file.lastModified) {
            return false
        }
        return try {
            File(rec.outputPath).exists()
        } catch (e: Exception) {
            false
        }
    }

    fun mark(
        context: Context,
        sourcePath: String,
        outputPath: String,
        sourceSize: Long,
        sourceMtime: Long
    ) {
        load(context)[sourcePath] = Record(
            outputPath = outputPath,
            sourceSize = sourceSize,
            sourceMtime = sourceMtime,
            convertedAt = System.currentTimeMillis()
        )
        save(context)
    }

    /** 源文件被删除（含转换后自动删除）时清掉记录 */
    fun removeSource(context: Context, sourcePath: String) {
        if (load(context).remove(sourcePath) != null) save(context)
    }

    /** 输出 mp4 被用户删掉时清掉记录，让源 m3u8 可以重新转换 */
    fun removeOutput(context: Context, outputPath: String) {
        val map = load(context)
        val keys = map.filter { it.value.outputPath == outputPath }.map { it.key }
        if (keys.isNotEmpty()) {
            keys.forEach { map.remove(it) }
            save(context)
        }
    }

    /** 一次性算出当前列表里哪些已经转过，避免每次绑定条目都读文件 */
    fun convertedKeys(context: Context, files: List<MediaFile>): Set<String> =
        files.filter { isConverted(context, it) }.map { it.uri.toString() }.toSet()
}
