package com.litebrowser.app.download

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litebrowser.app.R
import java.io.File

/**
 * 下载管理：列出所有任务，支持暂停/继续/取消/重试，完成后可打开。
 */
class DownloadActivity : Activity() {

    private lateinit var adapter: DownloadAdapter
    private lateinit var emptyText: TextView

    private val handler = Handler(Looper.getMainLooper())
    /** 上次刷新的数据指纹，只有变化时才 submit，避免频繁 notifyDataSetChanged 影响点击 */
    private var lastSignature = ""

    /**
     * 前台定时轮询：部分机型前台广播会被延迟甚至丢失，光靠广播会出现
     * "盯着进度条不动、退出再进才更新"。这里每 400ms 主动读一次任务状态兜底。
     */
    private val pollTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 400L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_downloads)

            emptyText = findViewById(R.id.dlEmpty)
            adapter = DownloadAdapter(
                onAction = { task, action -> handleAction(task, action) },
                onOpen = { openTask(it) }
            )
            val recycler = findViewById<RecyclerView>(R.id.dlRecycler)
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = adapter
            refresh()
        } catch (e: Throwable) {
            // 任何初始化异常都写日志并给出提示，不再直接闪退
            try {
                File(filesDir, "download_crash.txt")
                    .writeText(e.stackTraceToString(), Charsets.UTF_8)
            } catch (ignored: Exception) {
            }
            Toast.makeText(this, "下载管理初始化失败，详情见 download_crash.txt", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            registerReceiver(receiver, IntentFilter(DownloadService.ACTION_UPDATE))
        } catch (e: Exception) {
            // 已注册等异常忽略，不影响页面显示
        }
        refresh()
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
    }

    override fun onStop() {
        handler.removeCallbacks(pollTask)
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        super.onStop()
    }

    private fun refresh() {
        try {
            val tasks = DownloadStore.load(this)
            // 状态/字节数/总大小任一变化才重绘，静止时不动列表
            val signature = tasks.joinToString("|") {
                "${it.id}:${it.state.name}:${it.bytes}:${it.total}:${it.message}"
            }
            if (signature == lastSignature) return
            lastSignature = signature
            adapter.submit(tasks)
            emptyText.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        } catch (e: Throwable) {
            try {
                File(filesDir, "download_crash.txt")
                    .writeText(e.stackTraceToString(), Charsets.UTF_8)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun command(action: String, task: DownloadStore.Task) {
        try {
            // 控制指令（暂停/继续/取消/重试）用 startService：Activity 在前台时合法，
            // 也不会因为没来得及 startForeground 被系统判成崩溃
            startService(Intent(this, DownloadService::class.java).apply {
                this.action = action
                putExtra(DownloadService.EXTRA_ID, task.id)
            })
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleAction(task: DownloadStore.Task, action: String) {
        when (action) {
            "pause", "resume", "cancel", "retry" -> command(
                when (action) {
                    "pause" -> DownloadService.ACTION_PAUSE
                    "resume" -> DownloadService.ACTION_RESUME
                    "cancel" -> DownloadService.ACTION_CANCEL
                    else -> DownloadService.ACTION_RETRY
                },
                task
            )
            "remove" -> askRemove(task)
            "open" -> openTask(task)
        }
    }

    /** 删除任务：先取消正在进行的下载，弹窗可选是否同时删除已下载的文件 */
    private fun askRemove(task: DownloadStore.Task) {
        val view = android.view.LayoutInflater.from(this)
            .inflate(R.layout.dialog_remove_download, null)
        val check = view.findViewById<android.widget.CheckBox>(R.id.removeFileCheck)
        val file = File(task.targetPath)
        val hasFile = file.exists()
        if (!hasFile) {
            check.isChecked = false
            check.isEnabled = false
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_download_title))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                removeTask(task, hasFile && check.isChecked)
            }
            .show()
    }

    private fun removeTask(task: DownloadStore.Task, deleteFile: Boolean) {
        // 正在下载/排队时先取消，避免服务继续写文件
        if (task.state == DownloadStore.State.RUNNING ||
            task.state == DownloadStore.State.QUEUED ||
            task.state == DownloadStore.State.PAUSED
        ) {
            command(DownloadService.ACTION_CANCEL, task)
        }
        val tasks = DownloadStore.load(this)
        tasks.removeAll { it.id == task.id }
        DownloadStore.save(this, tasks)
        if (deleteFile) {
            try {
                File(task.targetPath).delete()
                // m3u8 的 ts 分片目录一并清理
                if (task.m3u8) {
                    val dir = File(
                        File(task.targetPath).parentFile,
                        File(task.targetPath).name.substringBeforeLast('.') + "_contents"
                    )
                    dir.listFiles()?.forEach { it.delete() }
                    dir.delete()
                }
            } catch (e: Exception) {
            }
        }
        refresh()
    }

    private fun openTask(task: DownloadStore.Task) {
        if (task.state != DownloadStore.State.DONE) {
            Toast.makeText(this, getString(R.string.download_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(task.targetPath)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.download_file_missing), Toast.LENGTH_SHORT).show()
            return
        }
        // 视频文件用应用自己的播放器打开（m3u8/mp4 都支持，且自带防盗链/手势/旋屏）
        if (task.m3u8 || isVideoFile(file.name)) {
            try {
                startActivity(Intent(this, com.litebrowser.app.m3u8.M3u8PlayerActivity::class.java).apply {
                    putExtra(
                        com.litebrowser.app.m3u8.M3u8PlayerActivity.EXTRA_URI,
                        Uri.fromFile(file).toString()
                    )
                    putExtra(
                        com.litebrowser.app.m3u8.M3u8PlayerActivity.EXTRA_NAME,
                        file.name
                    )
                })
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.download_no_player), Toast.LENGTH_SHORT).show()
            }
        } else {
            // 非视频类型交给系统应用
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.download_no_player), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u") ||
            lower.endsWith(".mp4") || lower.endsWith(".m4v") ||
            lower.endsWith(".webm") || lower.endsWith(".mkv")
    }
}
