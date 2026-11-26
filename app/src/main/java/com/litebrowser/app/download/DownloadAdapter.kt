package com.litebrowser.app.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litebrowser.app.R

class DownloadAdapter(
    private val onAction: (DownloadStore.Task, String) -> Unit,
    private val onOpen: (DownloadStore.Task) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.ViewHolder>() {

    private val items = ArrayList<DownloadStore.Task>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.dlName)
        val status: TextView = view.findViewById(R.id.dlStatus)
        val size: TextView = view.findViewById(R.id.dlSize)
        val progress: ProgressBar = view.findViewById(R.id.dlProgress)
        val action1: Button = view.findViewById(R.id.dlAction1)
        val action2: Button = view.findViewById(R.id.dlAction2)
    }

    fun submit(list: List<DownloadStore.Task>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name

        val (statusText, a1, a2) = when (item.state) {
            DownloadStore.State.QUEUED -> Triple("排队中", "暂停" to "pause", "取消" to "cancel")
            DownloadStore.State.RUNNING -> Triple("下载中 ${item.progress}%", "暂停" to "pause", "取消" to "cancel")
            DownloadStore.State.PAUSED -> Triple("已暂停 ${item.progress}%", "继续" to "resume", "取消" to "cancel")
            DownloadStore.State.DONE -> Triple("完成" + if (item.message.isNotBlank()) " · ${item.message}" else "", "打开" to "open", "重试" to "retry")
            DownloadStore.State.FAILED -> Triple("失败：${item.message}", "重试" to "retry", "取消" to "cancel")
            DownloadStore.State.CANCELED -> Triple("已取消", "重试" to "retry", "删除" to "remove")
        }
        holder.status.text = statusText
        holder.size.text = formatSize(item.bytes, item.total)
        holder.progress.max = 100
        holder.progress.progress = if (item.total > 0) item.progress else 0
        holder.progress.isIndeterminate = item.state == DownloadStore.State.RUNNING && item.total <= 0

        holder.action1.text = a1.first
        holder.action2.text = a2.first
        holder.action1.setOnClickListener {
            if (a1.second == "open") onOpen(item) else onAction(item, a1.second)
        }
        holder.action2.setOnClickListener { onAction(item, a2.second) }
        holder.itemView.setOnClickListener { onOpen(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatSize(bytes: Long, total: Long): String {
        fun fmt(b: Long) = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
            b < 1024 * 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
            else -> String.format("%.2f GB", b / 1073741824.0)
        }
        return if (total > 0) "${fmt(bytes)} / ${fmt(total)}" else fmt(bytes)
    }
}
