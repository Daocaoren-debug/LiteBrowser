package com.litebrowser.app.download

import android.app.Activity
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
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(DownloadService.ACTION_UPDATE))
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
        val tasks = DownloadStore.load(this)
        adapter.submit(tasks)
        emptyText.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
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
            "remove" -> {
                // 已取消：从列表移除，删掉半成品
                val tasks = DownloadStore.load(this)
                tasks.removeAll { it.id == task.id }
                DownloadStore.save(this, tasks)
                try {
                    File(task.targetPath).delete()
                } catch (e: Exception) {
                }
                refresh()
            }
            "open" -> openTask(task)
        }
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
