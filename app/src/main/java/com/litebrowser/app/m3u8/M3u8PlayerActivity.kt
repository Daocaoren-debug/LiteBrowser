package com.litebrowser.app.m3u8

import com.litebrowser.app.R

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

/**
 * 本地 m3u8 播放器：ExoPlayer 自带控制器（拖动进度、±10 秒、暂停）+ 手动旋屏。
 *
 * 旋转只有两态——横屏 / 竖屏，点一次切换一次。
 * 刻意不再提供"跟随重力感应（FULL_SENSOR）"：那个模式连系统里关掉自动旋转都压不住，
 * 会违背用户的系统设置，也容易误触。这里完全由用户手动决定方向。
 * manifest 声明了 configChanges，切换时 Activity 不重建，播放不中断。
 */
class M3u8PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var rotateButton: ImageButton? = null

    /** true = 当前横屏，false = 当前竖屏；进入时按系统当前方向初始化 */
    private var landscape = false

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initPlayer(savedInstanceState)
        } catch (e: Throwable) {
            try {
                java.io.File(filesDir, "player_crash.txt").appendText(
                    e.stackTraceToString(), Charsets.UTF_8
                )
            } catch (ignored: Exception) {
            }
            Toast.makeText(this, "播放启动失败，详情见 player_crash.txt", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initPlayer(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_m3u8_player)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.app_name)
        findViewById<TextView>(R.id.playerTitle).text = name

        // 进入时跟随当前物理方向锁定，之后只由按钮手动切换
        landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        rotateButton = findViewById(R.id.rotateButton)
        rotateButton?.setOnClickListener {
            landscape = !landscape
            applyRotation()
        }
        applyRotation()

        playerView = findViewById<PlayerView>(R.id.playerView).apply {
            setShowFastForwardButton(true)   // 快进 10 秒
            setShowRewindButton(true)        // 快退 10 秒
            setShowPreviousButton(false)
            setShowNextButton(false)
            controllerShowTimeoutMs = 4000
        }

        val uri = Uri.parse(uriString)
        // 旧版本下载的 m3u8 把分片放在 xxx_contents/，但列表里只写了文件名，
        // 播放时会报 IO_FILE_NOT_FOUND。这里就地修正一次，用户无需重新下载。
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            if (!path.isNullOrBlank() &&
                (path.endsWith(".m3u8", true) || path.endsWith(".m3u", true))
            ) {
                repairLocalPlaylist(java.io.File(path))
            }
        }
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val mediaItem = MediaItem.fromUri(uri)
        // m3u8 走 HLS 源，转换出来的 mp4 走普通 progressive 源
        val lower = name.lowercase()
        val mediaSource = if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            this@M3u8PlayerActivity,
                            getString(R.string.m3u8_play_failed, error.errorCodeName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }

        playerView?.player = player
    }

    /**
     * 老版本下载的 m3u8：分片和密钥下载到了 xxx_contents/ 子目录，
     * 但写回列表时只留了文件名（如 "0"），播放器按相对路径去 m3u8 同目录找就报
     * IO_FILE_NOT_FOUND。这里把这类引用补上 dirName/ 前缀，就地修一次。
     * 已经有前缀的、完整 http(s) 的、以及 URI 中带路径的都不会动。
     */
    private fun repairLocalPlaylist(file: java.io.File) {
        try {
            if (!file.exists()) return
            val dirName = file.name.substringBeforeLast('.', file.name) + "_contents"
            val contents = java.io.File(file.parentFile, dirName)
            if (!contents.isDirectory) return

            val uriRegex = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            var changed = false
            val fixed = ArrayList<String>()
            file.readLines(Charsets.UTF_8).forEach { line ->
                val trimmed = line.trim()
                val newLine = when {
                    trimmed.startsWith("#EXT-X-KEY", true) ||
                        trimmed.startsWith("#EXT-X-MAP", true) -> {
                        val ref = uriRegex.find(line)?.groupValues?.getOrNull(1)
                        if (ref != null && needsPrefix(ref, file, contents)) {
                            changed = true
                            line.replace(
                                uriRegex,
                                "URI=\"$dirName/${ref.substringAfterLast('/')}\""
                            )
                        } else line
                    }

                    trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                        if (needsPrefix(trimmed, file, contents)) {
                            changed = true
                            "$dirName/${trimmed.substringAfterLast('/')}"
                        } else line
                    }

                    else -> line
                }
                fixed.add(newLine)
            }
            if (changed) file.writeText(fixed.joinToString("\n"), Charsets.UTF_8)
        } catch (e: Exception) {
            // 修复失败就保持原样，交给播放器给出明确错误
        }
    }

    /** 引用是裸文件名、同目录找不到、且 xxx_contents/ 下确实存在 → 需要补前缀 */
    private fun needsPrefix(
        ref: String,
        playlist: java.io.File,
        contents: java.io.File
    ): Boolean {
        if (ref.isEmpty() || ref.contains('/') || ref.contains("://")) return false
        if (ref.startsWith("data:", true) || ref.startsWith("blob:", true)) return false
        if (java.io.File(playlist.parentFile, ref).exists()) return false
        return java.io.File(contents, ref).exists()
    }

    /** 两态手动旋转：锁定横屏 / 锁定竖屏，不再有 FULL_SENSOR 自动跟随 */
    private fun applyRotation() {
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // 图标按钮不显示文字，用无障碍描述标注当前方向
        rotateButton?.contentDescription = getString(
            if (landscape) R.string.rotate_land else R.string.rotate_portrait
        )
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
