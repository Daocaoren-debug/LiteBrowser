package com.litebrowser.app.download

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 下载任务持久化：进度、状态、断点、请求头都落盘，App 被杀后也能恢复显示与续传。
 */
object DownloadStore {

    private const val FILE_NAME = "downloads.json"

    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

    /** 还在进行中的状态：同 URL 命中这些状态时不再重复建任务 */
    val ACTIVE_STATES = setOf(State.QUEUED, State.RUNNING, State.PAUSED)

    /**
     * 下载目录：统一放到应用专属外部存储的 Download 下。
     * 好处是任何版本都不需要存储权限就能写，且本应用能直接扫描/播放。
     * 注意它在 Android/data 里，公共扫描会跳过，所以本地视频库要显式补扫这个目录。
     */
    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        base.mkdirs()
        return base
    }

    /** 同 URL 的任务（用于去重）：优先返回仍在进行中的那条，其次是最新一条 */
    fun findByUrl(tasks: List<Task>, url: String): Task? =
        tasks.firstOrNull { it.url == url && it.state in ACTIVE_STATES }
            ?: tasks.lastOrNull { it.url == url }

    data class Task(
        val id: Long,
        val url: String,
        val name: String,
        val targetPath: String,
        /** 请求头，用于防盗链：Referer / Cookie / User-Agent */
        val headers: Map<String, String>,
        /** 是否 m3u8（需要下载分片并重写列表） */
        val m3u8: Boolean,
        var state: State = State.QUEUED,
        var bytes: Long = 0L,
        var total: Long = -1L,
        var message: String = ""
    ) {
        val progress: Int
            get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 99) else 0
    }

    fun load(context: Context): MutableList<Task> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return ArrayList()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray("tasks") ?: JSONArray()
            val list = ArrayList<Task>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val headers = HashMap<String, String>()
                o.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { key -> headers[key] = h.optString(key) }
                }
                list.add(
                    Task(
                        id = o.optLong("id"),
                        url = o.optString("url"),
                        name = o.optString("name"),
                        targetPath = o.optString("path"),
                        headers = headers,
                        m3u8 = o.optBoolean("m3u8"),
                        state = runCatching {
                            State.valueOf(o.optString("state", "QUEUED"))
                        }.getOrDefault(State.QUEUED),
                        bytes = o.optLong("bytes"),
                        total = o.optLong("total", -1L),
                        message = o.optString("message")
                    )
                )
            }
            list
        } catch (e: Exception) {
            ArrayList()
        }
    }

    fun save(context: Context, tasks: List<Task>) {
        try {
            val root = JSONObject()
            val array = JSONArray()
            for (t in tasks) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("url", t.url)
                o.put("name", t.name)
                o.put("path", t.targetPath)
                val h = JSONObject()
                t.headers.forEach { (k, v) -> h.put(k, v) }
                o.put("headers", h)
                o.put("m3u8", t.m3u8)
                o.put("state", t.state.name)
                o.put("bytes", t.bytes)
                o.put("total", t.total)
                o.put("message", t.message)
                array.put(o)
            }
            root.put("tasks", array)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            val target = File(context.filesDir, FILE_NAME)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            // 缓存写失败不影响下载本身
        }
    }

    fun nextId(tasks: List<Task>): Long =
        (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
}
