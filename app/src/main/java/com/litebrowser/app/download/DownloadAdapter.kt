package com.litebrowser.app.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.litebrowser.app.R
import java.io.File

class DownloadAdapter(
    private val onAction: (DownloadStore.Task, String) -> Unit,
    private val onOpen: (DownloadStore.Task) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.ViewHolder>() {

    private val items = ArrayList<DownloadStore.Task>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.dlName)
        val status: TextView = view.findViewById(R.id.dlStatus)
        val path: TextView = view.findViewById(R.id.dlPath)
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

        // 第二个按钮统一为"删除任务"，点后弹窗可选是否同时删除已下载的文件
        val (statusText, a1) = when (item.state) {
            DownloadStore.State.QUEUED -> "排队中" to ("暂停" to "pause")
            DownloadStore.State.RUNNING -> "下载中 ${item.progress}%" to ("暂停" to "pause")
            DownloadStore.State.PAUSED -> "已暂停 ${item.progress}%" to ("继续" to "resume")
            DownloadStore.State.DONE -> ("完成" + if (item.message.isNotBlank()) " · ${item.message}" else "") to ("打开" to "open")
            DownloadStore.State.FAILED -> "失败：${item.message}" to ("重试" to "retry")
            DownloadStore.State.CANCELED -> "已取消" to ("重试" to "retry")
        }
        val a2 = "删除" to "remove"
        holder.status.text = statusText
        holder.path.text = holder.itemView.context.getString(
            R.string.download_path, prettyPath(File(item.targetPath).parent.orEmpty())
        )
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
        // 长按复制完整保存路径，方便去文件管理器找
        holder.itemView.setOnLongClickListener {
            copyPath(holder.itemView.context, item.targetPath)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    /** 路径太长时只保留尾部，界面上一行放得下；完整路径长按可复制 */
    private fun prettyPath(dir: String): String {
        val readable = dir.replace("/storage/emulated/0", "内部存储")
        return if (readable.length > 40) "…" + readable.takeLast(39) else readable
    }

    private fun copyPath(context: Context, path: String) {
        try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("path", path))
            Toast.makeText(context, R.string.download_path_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }

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
