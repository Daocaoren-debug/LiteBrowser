package com.litebrowser.app.m3u8

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litebrowser.app.R

class FileAdapter(
    private val onClick: (MediaFile) -> Unit,
    private val onConvert: (MediaFile) -> Unit,
    private val onDelete: (MediaFile) -> Unit,
    private val onPath: (MediaFile) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val items = ArrayList<MediaFile>()

    /** 多选模式开启时点条目只切换勾选，不打开播放器 */
    var selectionMode: Boolean = false
    val selected: MutableSet<String> = HashSet()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val check: CheckBox = view.findViewById(R.id.fileCheck)
        val thumb: ImageView = view.findViewById(R.id.fileThumb)
        val name: TextView = view.findViewById(R.id.fileName)
        val path: TextView = view.findViewById(R.id.filePath)
        val size: TextView = view.findViewById(R.id.fileSize)
        val type: TextView = view.findViewById(R.id.fileType)
        val actionArea: LinearLayout = view.findViewById(R.id.actionArea)
        val convert: Button = view.findViewById(R.id.convertButton)
        val delete: Button = view.findViewById(R.id.deleteButton)
    }

    fun submitList(list: List<MediaFile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
        onSelectionChanged(selectedCount())
    }

    fun selectedCount(): Int = selected.size

    /** 只保留当前筛选结果里的选中项，切筛选时不会误删看不见的文件 */
    fun pruneSelection() {
        val visible = items.map { it.uri.toString() }.toHashSet()
        selected.retainAll(visible)
        onSelectionChanged(selected.size)
    }

    fun selectAll() {
        if (selected.size == items.size) {
            selected.clear()
        } else {
            items.forEach { selected.add(it.uri.toString()) }
        }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun currentItems(): List<MediaFile> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_m3u8_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val key = item.uri.toString()

        holder.name.text = item.name
        holder.path.text = item.path
        holder.size.text = formatSize(item.size)
        holder.type.text = if (item.kind == MediaFile.Kind.M3U8) "M3U8" else "MP4"

        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.check.isChecked = key in selected
        holder.actionArea.visibility = if (selectionMode) View.GONE else View.VISIBLE
        holder.convert.visibility =
            if (!selectionMode && item.convertible) View.VISIBLE else View.GONE
        holder.delete.visibility = if (selectionMode) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggle(key)
                holder.check.isChecked = key in selected
            } else {
                onClick(item)
            }
        }
        holder.path.setOnClickListener { onPath(item) }
        holder.convert.setOnClickListener { onConvert(item) }
        holder.delete.setOnClickListener { onDelete(item) }

        // 防止 RecyclerView 复用时旧回调把图设错行
        holder.thumb.tag = key
        val cached = ThumbnailLoader.get(key)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
        } else {
            holder.thumb.setImageResource(android.R.drawable.ic_menu_gallery)
            ThumbnailLoader.load(holder.itemView.context, item) { bitmap ->
                if (holder.thumb.tag == key && bitmap != null) {
                    holder.thumb.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun toggle(key: String) {
        if (!selected.add(key)) selected.remove(key)
        onSelectionChanged(selected.size)
    }

    override fun getItemCount(): Int = items.size

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }
}
