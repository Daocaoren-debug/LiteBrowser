package com.litebrowser.app.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
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
import kotlin.math.abs

/**
 * 内置播放器：ExoPlayer(Media3) + 自绘控制条。
 *
 * 交互：
 * - 点画面：显示 / 隐藏控制条（顶部标题栏、中间按钮、底部进度条一起）
 * - 长按屏幕左边缘：连续快退；长按右边缘：连续快进，松手停止
 * - 进度条：点哪跳哪，也可以直接拖动（给了 40dp 触摸区域）
 * - 中间按钮：±10 秒、播放暂停；右上角旋转按钮切横竖屏
 *
 * 实现要点：
 * - 手势跑在独立的 gesture_layer 上，它夹在 PlayerView 与控制条之间，
 *   所以点空白处能收起控制条，点按钮/进度条又不会被手势层截走；
 * - ticker 只在用户没按着进度条时才回写 progress，max 也只在时长变化时设置，
 *   否则每 500ms 重置一次会让拖动"弹回去"；
 * - 请求头带 Referer / User-Agent 绕防盗链；configChanges 保证旋转不重建、播放不断。
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_UA = "extra_ua"
        const val EXTRA_TITLE = "extra_title"

        private const val SEEK_STEP_MS = 10_000L      // 按钮 / 长按每次跳转的步长
        private const val HOLD_INTERVAL_MS = 320L     // 长按连续快进/快退的重复间隔
        private const val LONG_PRESS_MS = 400L        // 判定长按的时间
        private const val EDGE_RATIO = 0.35f          // 左右各 35% 为快进/快退热区
    }

    private lateinit var playerView: PlayerView
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var bufferingBar: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var seekHint: TextView
    private lateinit var topBar: View
    private lateinit var centerBar: View
    private lateinit var bottomBar: View
    private lateinit var gestureLayer: View

    private var player: ExoPlayer? = null
    private var mediaUrl: String = ""
    private var userSeeking = false

    // 手势状态
    private var downX = 0f
    private var holdDirection = 0          // -1 快退，1 快进，0 空闲
    private var longPressConsumed = false
    private val touchSlop by lazy { (resources.displayMetrics.density * 16f).toInt() }

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }

    /** 长按后持续跳转，直到松手 */
    private val holdSeekTask = object : Runnable {
        override fun run() {
            if (holdDirection == 0) return
            seekBy(holdDirection * SEEK_STEP_MS)
            updateSeekHint()
            handler.postDelayed(this, HOLD_INTERVAL_MS)
        }
    }

    /** 按下 400ms 后触发：落在左/右热区才开始连续快退/快进 */
    private val longPressTask = object : Runnable {
        override fun run() {
            val width = gestureLayer.width
            if (width <= 0 || player == null) return
            longPressConsumed = true
            when {
                downX < width * EDGE_RATIO -> startHoldSeek(-1)
                downX > width * (1f - EDGE_RATIO) -> startHoldSeek(1)
                else -> Unit   // 中间长按不做事，只是不再触发"点击收起控制条"
            }
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
        seekHint = findViewById(R.id.seek_hint)
        topBar = findViewById(R.id.top_bar)
        centerBar = findViewById(R.id.center_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        gestureLayer = findViewById(R.id.gesture_layer)

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

        // 手势层：点一下切控制条，长按左右边缘连续快退/快进
        gestureLayer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    longPressConsumed = false
                    view.postDelayed(longPressTask, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 明显横向移动（用户其实想拖进度条）就取消长按判定
                    if (!longPressConsumed && abs(event.x - downX) > touchSlop) {
                        view.removeCallbacks(longPressTask)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPressTask)
                    if (holdDirection != 0) {
                        stopHoldSeek()
                    } else if (!longPressConsumed) {
                        toggleControls()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressTask)
                    stopHoldSeek()
                    true
                }

                else -> false
            }
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
                val target = seekBar?.progress?.toLong() ?: 0L
                player?.seekTo(target)
                positionText.text = formatTime(target)
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
        if (!userSeeking) {
            seekBar.progress = exo.currentPosition.toInt()
            positionText.text = formatTime(exo.currentPosition)
        }
    }

    // ---------------- 手势 ----------------

    private fun toggleControls() {
        val show = topBar.visibility != View.VISIBLE
        val target = if (show) View.VISIBLE else View.GONE
        topBar.visibility = target
        centerBar.visibility = target
        bottomBar.visibility = target
    }

    private fun startHoldSeek(direction: Int) {
        holdDirection = direction
        handler.removeCallbacks(holdSeekTask)
        handler.post(holdSeekTask)
    }

    private fun stopHoldSeek() {
        holdDirection = 0
        handler.removeCallbacks(holdSeekTask)
        seekHint.visibility = View.GONE
    }

    private fun updateSeekHint() {
        val exo = player ?: return
        val action = if (holdDirection > 0) {
            getString(R.string.player_fast_forward)
        } else {
            getString(R.string.player_fast_rewind)
        }
        seekHint.text = "$action  " + formatTime(exo.currentPosition) +
            " / " + formatTime(exo.duration)
        seekHint.visibility = View.VISIBLE
    }

    // ---------------- 进度显示 ----------------

    private fun updatePlayPauseIcon(playing: Boolean) {
        playPauseButton.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun updateProgress() {
        val exo = player ?: return
        val duration = exo.duration
        if (duration > 0) {
            val max = duration.toInt()
            // max 只在时长变化时设置：每 500ms 重置一次会把正在拖动的拇指弹回去
            if (seekBar.max != max) {
                seekBar.max = max
            }
            durationText.text = formatTime(duration)
            seekBar.isEnabled = true
            // 用户按着或正在拖动时不要回写 progress，否则手指会被"抢"
            if (!userSeeking && !seekBar.isPressed) {
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
        stopHoldSeek()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        stopHoldSeek()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(longPressTask)
        stopHoldSeek()
        releasePlayer()
        super.onDestroy()
    }
}

private const val UA_FALLBACK =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
