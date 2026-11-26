package com.litebrowser.app.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
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
import com.litebrowser.app.download.DownloadHelper
import java.util.Locale
import kotlin.math.abs

/**
 * 内置播放器：ExoPlayer(Media3) + 自绘控制条。
 *
 * 交互：
 * - 点画面：显示 / 隐藏控制条（顶部标题栏、中间按钮、底部进度条一起）
 * - 左右滑动：快退 / 快进；右半屏上下滑调音量，左半屏上下滑调亮度
 * - 进度条：点哪跳哪，也可以直接拖动（给了 40dp 触摸区域）
 * - 中间按钮：±10 秒、播放暂停；右下角下载 / 旋转按钮
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

        private const val SEEK_STEP_MS = 10_000L      // ±10 秒按钮的跳转步长
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
    private lateinit var playerActions: LinearLayout

    private var player: ExoPlayer? = null
    private var mediaUrl: String = ""
    private var userSeeking = false
    private var videoReferer: String? = null
    private var videoUa: String? = null
    private var videoTitle: String = ""
    /** 当前是否横屏，自己维护，避免依赖 requestedOrientation 的 sensor/初值坑 */
    private var landscape: Boolean = false

    // 手势状态
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** 手势类型：0 未定，1 水平快进/快退，2 垂直音量/亮度 */
    private var gestureMode = 0
    private var verticalSide = 0            // 1 右半屏(音量)，-1 左半屏(亮度)
    private var longPressConsumed = false
    private val touchSlop by lazy { (resources.displayMetrics.density * 12f).toInt() }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var baseVolume = 0
    private var maxVolume = 0
    private var baseBrightness = 0.5f

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

        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyActionLayout()

        videoReferer = intent.getStringExtra(EXTRA_REFERER)
        videoUa = intent.getStringExtra(EXTRA_UA)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        videoTitle = if (title.isEmpty()) MediaSniffer.prettyName(mediaUrl) else title
        findViewById<TextView>(R.id.title_text).text = videoTitle

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
        playerActions = findViewById(R.id.player_actions)

        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun bindActions() {
        findViewById<ImageButton>(R.id.close_button).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.rotate_button).setOnClickListener { toggleOrientation() }
        findViewById<ImageButton>(R.id.download_button).setOnClickListener { downloadCurrent() }
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
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    gestureMode = 0
                    longPressConsumed = false
                    // 记录当前音量/亮度作为滑动起点
                    maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    baseVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    baseBrightness = window.attributes.screenBrightness.let {
                        if (it < 0f) 0.5f else it
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    val totalX = event.x - downX
                    val totalY = event.y - downY

                    if (gestureMode == 0 &&
                        (abs(totalX) > touchSlop || abs(totalY) > touchSlop)
                    ) {
                        // 首段位移判定方向：横向多→快进快退；纵向多→音量/亮度
                        gestureMode = if (abs(totalX) >= abs(totalY)) 1 else 2
                        verticalSide = if (downX >= view.width / 2f) 1 else -1
                        longPressConsumed = true
                    }

                    when (gestureMode) {
                        1 -> {
                            // 右划快进、左划快退：按位移比例换算毫秒
                            val deltaMs = (dx / view.width * 120_000f).toLong()
                            if (deltaMs != 0L) seekBy(deltaMs)
                            showGestureHint(
                                getString(
                                    if (dx > 0) R.string.player_fast_forward else R.string.player_fast_rewind
                                ) + "  " + formatTime(player?.currentPosition ?: 0L)
                            )
                        }
                        2 -> {
                            val step = -dy / view.height   // 上滑为正
                            if (verticalSide > 0) {
                                // 右半屏：音量
                                val target = (baseVolume + step * maxVolume).toInt()
                                    .coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC, target, 0
                                )
                                showGestureHint(
                                    getString(R.string.player_volume) + " $target/$maxVolume"
                                )
                            } else {
                                // 左半屏：亮度
                                val target = (baseBrightness + step).coerceIn(0.05f, 1f)
                                val lp = window.attributes
                                lp.screenBrightness = target
                                window.attributes = lp
                                showGestureHint(
                                    getString(R.string.player_brightness) +
                                        " ${(target * 100).toInt()}%"
                                )
                            }
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (gestureMode == 0 && !longPressConsumed) toggleControls()
                    gestureMode = 0
                    seekHint.visibility = View.GONE
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    gestureMode = 0
                    seekHint.visibility = View.GONE
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
        val referer = videoReferer
        val ua = videoUa
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
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

    /** 显示滑动手势提示（快进/音量/亮度） */
    private fun showGestureHint(text: String) {
        seekHint.text = text
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

    /**
     * 旋转按钮：横屏 / 竖屏来回切。
     * 用自己维护的 landscape 状态判断，不能读 requestedOrientation——
     * manifest 里若声明 sensor，其初值是 SENSOR 而非 LANDSCAPE，会把"横屏点一下"判成"设横屏"，
     * 于是从反向横屏翻到正向横屏，看起来像转了 180°。
     */
    private fun toggleOrientation() {
        landscape = !landscape
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        applyActionLayout()
    }

    /** 竖屏时下载/旋屏按钮横向排列，横屏时纵向排列 */
    private fun applyActionLayout() {
        if (!::playerActions.isInitialized) return
        playerActions.orientation = if (landscape) {
            LinearLayout.VERTICAL
        } else {
            LinearLayout.HORIZONTAL
        }
        val rotate = findViewById<ImageButton>(R.id.rotate_button)
        val lp = rotate.layoutParams as LinearLayout.LayoutParams
        lp.leftMargin = if (landscape) 0 else (10 * resources.displayMetrics.density).toInt()
        lp.topMargin = if (landscape) (10 * resources.displayMetrics.density).toInt() else 0
        rotate.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyActionLayout()
        applyImmersive()
    }

    /** 下载当前视频：带上页面的 Referer/UA/Cookie 与标题，交给自研下载服务 */
    private fun downloadCurrent() {
        if (mediaUrl.startsWith("blob:", true) || mediaUrl.startsWith("data:", true)) {
            android.widget.Toast.makeText(
                this, getString(R.string.download_unsupported), android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        DownloadHelper.startDownload(
            context = this,
            url = mediaUrl,
            referer = videoReferer,
            userAgent = videoUa,
            contentDisposition = null,
            mimeType = null,
            nameHint = videoTitle
        )
        android.widget.Toast.makeText(
            this, getString(R.string.download_added), android.widget.Toast.LENGTH_SHORT
        ).show()
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
        seekHint.visibility = View.GONE
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        seekHint.visibility = View.GONE
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        seekHint.visibility = View.GONE
        releasePlayer()
        super.onDestroy()
    }
}

private const val UA_FALLBACK =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
