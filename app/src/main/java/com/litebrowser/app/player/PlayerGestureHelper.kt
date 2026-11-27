package com.litebrowser.app.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * 两个播放器共用的手势控制器：
 * - 横向滑动：右划快进、左滑快退（划过整屏约 120 秒）
 * - 纵向滑动：右半屏调音量、左半屏调亮度，按触点在屏幕中的位置映射 0-100
 *   （手指在最底部 = 0，最顶部 = 100，与显示数字一致）
 * - 长按：持续快进，松手停止
 * - 单击：交给调用方处理（显示 / 隐藏控制条）
 *
 * 用 Activity 的 window 改屏幕亮度，只对当前窗口生效，退出播放器自动失效。
 */
class PlayerGestureHelper(
    private val activity: Activity,
    private val hintView: TextView,
    private val currentPosition: () -> Long,
    private val seekBy: (Long) -> Unit,
    private val onTap: () -> Unit
) {
    companion object {
        private const val LONG_PRESS_MS = 400L       // 按住多久算长按
        private const val HOLD_INTERVAL_MS = 350L    // 长按时每次快进的间隔
        private const val HOLD_STEP_MS = 8_000L      // 长按时每次快进的步长
        private const val SWIPE_FULL_WIDTH_MS = 120_000L  // 横划过整屏对应的时长
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val touchSlop by lazy {
        (activity.resources.displayMetrics.density * 12f).toInt()
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    /** 0 未判定 / 1 横向快进快退 / 2 纵向音量亮度 */
    private var mode = 0
    /** 1 右半屏(音量) / -1 左半屏(亮度) */
    private var verticalSide = 0
    private var longPressed = false
    private var holding = false
    private var maxVolume = 15

    private val longPressTask = Runnable {
        if (mode != 0) return@Runnable
        longPressed = true
        holding = true
        showHoldHint()
        handler.postDelayed(holdTask, HOLD_INTERVAL_MS)
    }

    private val holdTask = object : Runnable {
        override fun run() {
            if (!holding) return
            seekBy(HOLD_STEP_MS)
            showHoldHint()
            handler.postDelayed(this, HOLD_INTERVAL_MS)
        }
    }

    fun attach(view: View) {
        view.setOnTouchListener { v, event -> onTouch(v, event) }
    }

    /** @return true 表示事件已处理；调用方若需让出控制器区域，可在 DOWN 时不调用本方法 */
    fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                mode = 0
                longPressed = false
                holding = false
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                handler.removeCallbacks(longPressTask)
                handler.postDelayed(longPressTask, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val totalX = event.x - downX
                val totalY = event.y - downY

                if (mode == 0 && (abs(totalX) > touchSlop || abs(totalY) > touchSlop)) {
                    mode = if (abs(totalX) >= abs(totalY)) 1 else 2
                    verticalSide = if (downX >= view.width / 2f) 1 else -1
                    handler.removeCallbacks(longPressTask)
                    // 长按后又开始滑动：停止长按快进，改走滑动手势
                    if (holding) {
                        holding = false
                        handler.removeCallbacks(holdTask)
                    }
                }

                when (mode) {
                    1 -> {
                        // 右划快进、左划快退
                        val deltaMs = (dx / view.width * SWIPE_FULL_WIDTH_MS).toLong()
                        if (deltaMs != 0L) seekBy(deltaMs)
                        showHint(
                            (if (dx > 0) "快进" else "快退") + "  " + formatTime(currentPosition())
                        )
                    }
                    2 -> {
                        // 按触点绝对位置映射：底部 0、顶部 100
                        val percent = ((view.height - event.y) / view.height.toFloat() * 100)
                            .toInt().coerceIn(0, 100)
                        if (verticalSide > 0) {
                            val target = (percent / 100f * maxVolume).toInt()
                                .coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            showHint("音量 $percent")
                        } else {
                            val lp = activity.window.attributes
                            // 0f 会被系统当成"跟随系统亮度"，给个极小值保证真的最暗
                            lp.screenBrightness = if (percent == 0) 0.01f else percent / 100f
                            activity.window.attributes = lp
                            showHint("亮度 $percent")
                        }
                    }
                }
                lastX = event.x
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasTap = mode == 0 && !longPressed
                cancel()
                if (wasTap) onTap()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancel()
                return true
            }
        }
        return false
    }

    /** 生命周期里调用：停止长按快进并隐藏提示 */
    fun cancel() {
        handler.removeCallbacks(longPressTask)
        handler.removeCallbacks(holdTask)
        holding = false
        mode = 0
        hintView.visibility = View.GONE
    }

    private fun showHoldHint() {
        showHint("快进中  " + formatTime(currentPosition()))
    }

    private fun showHint(text: String) {
        hintView.text = text
        hintView.visibility = View.VISIBLE
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        return String.format(
            Locale.US, "%02d:%02d", (totalSeconds / 60) % 60, totalSeconds % 60
        )
    }
}
