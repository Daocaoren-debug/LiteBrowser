package com.litebrowser.app.player

/**
 * 极简资源嗅探器：只做"记录 + 判定"，不做网络请求。
 *
 * 两个来源同时喂给它：
 * 1. WebViewClient.shouldInterceptRequest —— 页面上真实发生的每一个请求（含播放器自己拉的分片）；
 * 2. 页面内 JS 探针 —— <video> 的 currentSrc 与 performance 资源计时表，能补上被 XHR 拉起来、
 *    但拦截层只看到 .ts 分片的 m3u8 主播放列表。
 *
 * 注意：shouldInterceptRequest 在 WebView 的子线程回调，所以这里的方法全部加锁。
 */
object MediaSniffer {

    private const val MAX_ITEMS = 40

    /** 按扩展名判定，m3u8/mpd 是播放列表，其余是可直接播放的媒体文件 */
    private val MEDIA_SUFFIXES = listOf(
        ".mp4", ".m4v", ".webm", ".mkv", ".flv", ".mov", ".3gp", ".m3u8", ".mpd"
    )

    private val found = LinkedHashSet<String>()

    @Synchronized
    fun clear() {
        found.clear()
    }

    /** 线程安全的收集入口，非媒体链接会被直接丢弃 */
    @Synchronized
    fun add(url: String) {
        if (url.isEmpty() || found.size >= MAX_ITEMS) return
        if (!isMediaUrl(url)) return
        // 去掉分片地址，避免列表被几百个 .ts 片段塞满
        if (url.substringBefore('?').endsWith(".ts", true)) return
        found.add(url)
    }

    @Synchronized
    fun snapshot(): List<String> = found.toList()

    /** 链接是否像媒体文件：忽略 query / fragment，只看路径扩展名 */
    fun isMediaUrl(url: String): Boolean {
        if (!url.startsWith("http", true)) return false
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return MEDIA_SUFFIXES.any { path.endsWith(it) }
    }

    /** 列表里展示用的短名字：host + 文件名 */
    fun prettyName(url: String): String {
        return try {
            val noQuery = url.substringBefore('?').substringBefore('#')
            val host = noQuery.substringAfter("//").substringBefore('/')
            val file = noQuery.substringAfterLast('/')
            if (file.isEmpty()) host else "$host/$file"
        } catch (e: Exception) {
            url
        }
    }
}
