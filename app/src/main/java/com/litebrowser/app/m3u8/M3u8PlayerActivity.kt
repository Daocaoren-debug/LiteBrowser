package com.litebrowser.app.m3u8

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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
    private var rotateButton: Button? = null

    /** true = 当前横屏，false = 当前竖屏；进入时按系统当前方向初始化 */
    private var landscape = false

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))

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

    /** 两态手动旋转：锁定横屏 / 锁定竖屏，不再有 FULL_SENSOR 自动跟随 */
    private fun applyRotation() {
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        rotateButton?.text = getString(if (landscape) R.string.rotate_land else R.string.rotate_portrait)
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
