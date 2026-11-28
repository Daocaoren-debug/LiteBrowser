package com.litebrowser.app.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * 内置播放器：ExoPlayer(Media3) + 自绘控制条。
 *
 * 交互：
 * - 点画面：显示 / 隐藏控制条（顶部标题栏、中间按钮、底部进度条一起）
 * - 左右滑动：以按下点为基准快退 / 快进，并显示目标时间
 * - 右半屏上下滑调音量，左半屏上下滑调亮度（均 0-100，从当前值起算、避开屏幕边缘）
 * - 长按画面：2 倍速播放，松手恢复
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
        private const val CONTROLS_TIMEOUT_MS = 4_000L  // 控制条无操作自动隐藏
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

    /** 共用手势控制器（横滑快进快退、纵滑音量亮度、长按快进） */
    private lateinit var gestureHelper: PlayerGestureHelper

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

        // 首次进入也按 4 秒自动收起控制条
        showControls()

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
            keepControlsAlive()
            seekBy(-SEEK_STEP_MS)
        }
        findViewById<ImageButton>(R.id.forward_button).setOnClickListener {
            keepControlsAlive()
            seekBy(SEEK_STEP_MS)
        }
        findViewById<Button>(R.id.retry_button).setOnClickListener {
            errorPanel.visibility = View.GONE
            startPlayer()
        }
        playPauseButton.setOnClickListener {
            keepControlsAlive()
            togglePlayPause()
        }

        // 共用手势：横滑快进快退、右半屏音量、左半屏亮度（0-100）、长按快进、单击切控制条
        gestureHelper = PlayerGestureHelper(
            activity = this,
            hintView = seekHint,
            currentPosition = { player?.currentPosition ?: 0L },
            duration = { player?.duration ?: 0L },
            seekTo = { target -> player?.seekTo(target) },
            setSpeed = { speed ->
                player?.playbackParameters =
                    androidx.media3.common.PlaybackParameters(speed)
            },
            onTap = { toggleControls() }
        )
        gestureHelper.attach(gestureLayer)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                positionText.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                keepControlsAlive()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                keepControlsAlive()
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

    // ---------------- 控制条显示 ----------------

    /** 控制条自动隐藏：显示后 4 秒无操作收起，与主流播放器一致 */
    private val hideControlsTask = Runnable { hideControls() }

    private fun showControls() {
        topBar.visibility = View.VISIBLE
        centerBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        // 下载/旋屏按钮跟控制条一起显示隐藏
        if (::playerActions.isInitialized) playerActions.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, CONTROLS_TIMEOUT_MS)
    }

    private fun hideControls() {
        topBar.visibility = View.GONE
        centerBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        if (::playerActions.isInitialized) playerActions.visibility = View.GONE
        handler.removeCallbacks(hideControlsTask)
    }

    /** 单击画面切换显示/隐藏；每次显示都重新计时 */
    private fun toggleControls() {
        if (topBar.visibility == View.VISIBLE) hideControls() else showControls()
    }

    /** 有按钮操作或拖动进度条时，重新计时，避免操作中途消失 */
    private fun keepControlsAlive() {
        if (topBar.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideControlsTask)
            handler.postDelayed(hideControlsTask, CONTROLS_TIMEOUT_MS)
        }
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
        // 同一个视频点了多次只入队一次，返回结果用来给准确提示
        val status = DownloadHelper.startDownload(
            context = this,
            url = mediaUrl,
            referer = videoReferer,
            userAgent = videoUa,
            contentDisposition = null,
            mimeType = null,
            nameHint = videoTitle
        )
        val message = when (status) {
            DownloadHelper.Status.STARTED -> R.string.download_added
            DownloadHelper.Status.ALREADY_DOWNLOADING -> R.string.download_duplicate_active
            DownloadHelper.Status.ALREADY_DONE -> R.string.download_duplicate_done
            DownloadHelper.Status.FAILED -> R.string.download_failed
        }
        android.widget.Toast.makeText(
            this, getString(message), android.widget.Toast.LENGTH_SHORT
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
        gestureHelper.cancel()
        handler.removeCallbacks(hideControlsTask)
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideControlsTask)
        gestureHelper.cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideControlsTask)
        gestureHelper.cancel()
        releasePlayer()
        super.onDestroy()
    }
}

private const val UA_FALLBACK =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
