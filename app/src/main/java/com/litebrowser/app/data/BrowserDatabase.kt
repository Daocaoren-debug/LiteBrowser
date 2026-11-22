package com.litebrowser.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 轻量本地存储：收藏 + 历史记录。
 * 直接使用系统 SQLiteOpenHelper，无 Room、无 AndroidX 依赖，降低方法数与包体。
 */
class BrowserDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "browser.db"
        private const val DB_VERSION = 1

        const val TABLE_BOOKMARK = "bookmarks"
        const val TABLE_HISTORY = "history"

        // 历史记录上限，防止低端机数据库无限膨胀
        const val MAX_HISTORY = 500
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_BOOKMARK (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "url TEXT NOT NULL UNIQUE, " +
                "time INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE $TABLE_HISTORY (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "url TEXT NOT NULL, " +
                "time INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_history_time ON $TABLE_HISTORY(time DESC)")
        db.execSQL("CREATE INDEX idx_bookmark_time ON $TABLE_BOOKMARK(time DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BOOKMARK")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    // ---------------- 收藏 ----------------

    fun addBookmark(title: String, url: String): Boolean {
        if (isBookmarked(url)) return false
        val values = ContentValues().apply {
            put("title", title.ifEmpty { url })
            put("url", url)
            put("time", System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE_BOOKMARK, null, values) != -1L
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete(TABLE_BOOKMARK, "url = ?", arrayOf(url))
    }

    fun isBookmarked(url: String): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_BOOKMARK WHERE url = ? LIMIT 1", arrayOf(url)
        )
        return cursor.use { it.count > 0 }
    }

    fun queryBookmarks(): Cursor = readableDatabase.query(
        TABLE_BOOKMARK, null, null, null, null, null, "time DESC"
    )

    // ---------------- 历史 ----------------

    fun addHistory(title: String, url: String) {
        if (url.isEmpty()) return
        val db = writableDatabase
        // 同一 URL 短时间内重复访问则更新记录，避免刷屏
        val cursor = db.rawQuery(
            "SELECT _id FROM $TABLE_HISTORY WHERE url = ? ORDER BY time DESC LIMIT 1",
            arrayOf(url)
        )
        val now = System.currentTimeMillis()
        val existingId = cursor.use { if (it.moveToFirst()) it.getLong(0) else -1L }

        if (existingId > 0) {
            val values = ContentValues().apply {
                put("title", title.ifEmpty { url })
                put("time", now)
            }
            db.update(TABLE_HISTORY, values, "_id = ?", arrayOf(existingId.toString()))
        } else {
            val values = ContentValues().apply {
                put("title", title.ifEmpty { url })
                put("url", url)
                put("time", now)
            }
            db.insert(TABLE_HISTORY, null, values)
        }
        trimHistory()
    }

    fun queryHistory(): Cursor = readableDatabase.query(
        TABLE_HISTORY, null, null, null, null, null, "time DESC"
    )

    fun deleteHistory(id: Long) {
        writableDatabase.delete(TABLE_HISTORY, "_id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }

    /** 保留最近 MAX_HISTORY 条，删除更旧的记录，控制数据库体积 */
    private fun trimHistory() {
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_HISTORY WHERE _id NOT IN (" +
                "SELECT _id FROM $TABLE_HISTORY ORDER BY time DESC LIMIT $MAX_HISTORY)"
        )
    }
}
