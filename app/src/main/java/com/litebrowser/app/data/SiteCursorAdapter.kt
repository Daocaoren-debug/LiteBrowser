package com.litebrowser.app.data

import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.ImageButton
import android.widget.TextView
import com.litebrowser.app.R

/**
 * 用 CursorAdapter 直接绑定数据库游标，避免一次性把全部历史读进内存（低端机友好）。
 * 删除回调同时给出 id 与 url：历史按 id 删，收藏按 url 删。
 */
class SiteCursorAdapter(
    context: Context,
    cursor: Cursor?,
    private val onDelete: (id: Long, url: String) -> Unit
) : CursorAdapter(context, cursor, 0) {

    private val inflater = LayoutInflater.from(context)

    override fun newView(context: Context?, cursor: Cursor?, parent: ViewGroup?): View {
        val view = inflater.inflate(R.layout.list_item_site, parent, false)
        view.tag = ViewHolder(
            view.findViewById(R.id.item_title),
            view.findViewById(R.id.item_url),
            view.findViewById(R.id.item_delete)
        )
        return view
    }

    override fun bindView(view: View?, context: Context?, cursor: Cursor?) {
        val holder = view?.tag as? ViewHolder ?: return
        cursor ?: return
        val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
        val url = cursor.getString(cursor.getColumnIndexOrThrow("url"))
        val id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))

        holder.title.text = if (title.isBlank()) url else title
        holder.url.text = url
        holder.delete.setOnClickListener { onDelete(id, url) }
    }

    private class ViewHolder(
        val title: TextView,
        val url: TextView,
        val delete: ImageButton
    )
}
