package com.litebrowser.app.m3u8

import android.net.Uri

data class MediaFile(
    val name: String,
    val path: String,
    val size: Long,
    val uri: Uri,
    val lastModified: Long = 0L
) {
    enum class Kind { M3U8, MP4 }

    /** 类型只从文件名推导，旧缓存 JSON 不需要迁移 */
    val kind: Kind
        get() = if (name.lowercase().let {
                it.endsWith(".m3u8") || it.endsWith(".m3u")
            }
        ) Kind.M3U8 else Kind.MP4

    /** 转换只支持真实文件路径，SAF 的 content:// Uri 无法直接交给 FFmpeg */
    val convertible: Boolean
        get() = kind == Kind.M3U8 && uri.scheme == "file"
}
