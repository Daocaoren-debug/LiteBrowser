package com.litebrowser.app.download

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
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
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        super.onStop()
    }

    private fun refresh() {
        try {
            val tasks = DownloadStore.load(this)
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
        startService(Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_ID, task.id)
        })
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
        // m3u8 交给本地视频库的播放器；其它类型用系统能处理的方式打开
        if (task.m3u8 || file.name.endsWith(".mp4", true) || file.name.endsWith(".m3u8", true)) {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } catch (e: Exception) {
                    Uri.fromFile(file)
                }
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (file.name.endsWith(".m3u8", true)) "application/vnd.apple.mpegurl" else "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.download_no_player), Toast.LENGTH_SHORT).show()
            }
        } else {
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
}
