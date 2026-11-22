package com.litebrowser.app.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.litebrowser.app.R
import java.util.Locale

/**
 * 内置播放器：ExoPlayer(Media3) + 自绘控制条。
 *
 * 为什么不用 PlayerView 自带控制器：需求要 ±10 秒快进快退和旋转按钮，
 * 自带控制器没有，所以关掉它，用一套更直观的控制条（进度条可拖动、时间显示、重试）。
 *
 * 关键实现点：
 * - 请求头带上 Referer / User-Agent，绕开常见站点的防盗链；
 * - 清单里声明 configChanges，横竖屏切换不重建 Activity，播放不中断；
 * - 旋转按钮 + 重力感应（manifest 里 screenOrientation="sensor"）双支持；
 * - 隐藏系统栏 + 屏幕常亮，看完自动停在结尾。
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_UA = "extra_ua"
        const val EXTRA_TITLE = "extra_title"

        private const val SEEK_STEP_MS = 10_000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var bufferingBar: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var controls: View

    private var player: ExoPlayer? = null
    private var mediaUrl: String = ""
    private var userSeeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            bufferingBar.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) {
                errorPanel.visibility = View.GONE
                updateProgress()
            }
            if (playbackState == Player.STATE_ENDED) {
                updatePlayPauseIcon(false)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            bufferingBar.visibility = View.GONE
            showError(error.errorCodeName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        mediaUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (mediaUrl.isEmpty()) {
            finish()
            return
        }

        bindViews()
        bindActions()

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<TextView>(R.id.title_text).text =
            if (title.isEmpty()) MediaSniffer.prettyName(mediaUrl) else title

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()

        startPlayer()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        playPauseButton = findViewById(R.id.play_pause_button)
        seekBar = findViewById(R.id.seek_bar)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        bufferingBar = findViewById(R.id.buffering_bar)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        controls = findViewById(R.id.controls)

        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun bindActions() {
        findViewById<ImageButton>(R.id.close_button).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.rotate_button).setOnClickListener { toggleOrientation() }
        findViewById<ImageButton>(R.id.rewind_button).setOnClickListener {
            seekBy(-SEEK_STEP_MS)
        }
        findViewById<ImageButton>(R.id.forward_button).setOnClickListener {
            seekBy(SEEK_STEP_MS)
        }
        findViewById<Button>(R.id.retry_button).setOnClickListener {
            errorPanel.visibility = View.GONE
            startPlayer()
        }
        playPauseButton.setOnClickListener { togglePlayPause() }

        // 点画面空白处显示/隐藏控制条，跟主流播放器一致
        playerView.setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                positionText.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                player?.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })
    }

    // ---------------- 播放器 ----------------

    private fun startPlayer() {
        releasePlayer()

        val headers = HashMap<String, String>()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
        val ua = intent.getStringExtra(EXTRA_UA)
        if (!ua.isNullOrEmpty()) headers["User-Agent"] = ua

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(if (ua.isNullOrEmpty()) UA_FALLBACK else ua)
            .setAllowCrossProtocolRedirects(true)   // 很多站点 http -> https 跳转
            .setDefaultRequestProperties(headers)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player = exoPlayer
        playerView.player = exoPlayer
        exoPlayer.addListener(playerListener)
        exoPlayer.setMediaItem(MediaItem.fromUri(mediaUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        bufferingBar.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = exo.duration
        exo.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        updateProgress()
    }

    private fun updatePlayPauseIcon(playing: Boolean) {
        playPauseButton.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun updateProgress() {
        val exo = player ?: return
        val duration = exo.duration
        if (duration > 0) {
            seekBar.max = duration.toInt()
            durationText.text = formatTime(duration)
            if (!userSeeking) {
                val position = exo.currentPosition
                seekBar.progress = position.toInt()
                positionText.text = formatTime(position)
            }
        } else {
            // 直播流没有总时长，进度条置灰
            seekBar.isEnabled = false
            durationText.text = getString(R.string.player_live)
        }
    }

    private fun showError(reason: String) {
        val message = getString(R.string.player_error_message)
        errorText.text = if (reason.isEmpty()) message else "$message\n$reason"
        errorPanel.visibility = View.VISIBLE
    }

    /** 旋转按钮：横屏 / 竖屏来回切（manifest 声明了 configChanges，不会重建界面） */
    private fun toggleOrientation() {
        requestedOrientation =
            if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        releasePlayer()
        super.onDestroy()
    }
}

private const val UA_FALLBACK =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
