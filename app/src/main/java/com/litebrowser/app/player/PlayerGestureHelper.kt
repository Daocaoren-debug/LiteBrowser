package com.litebrowser.app.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * 两个播放器共用的手势控制器，按主流视频 App 的交互设计：
 *
 * - 左右滑：以按下点为基准的相对位移，手指回到原点就回到原进度；
 *   提示同时显示"快进 30 秒 → 01:23"这样的目标时间。
 * - 上下滑：从当前音量/亮度起算的相对位移；有效区从屏幕边缘往里缩，
 *   滑到有效区顶部 = 100、底部 = 0，不会顶到物理屏幕边缘。
 * - 长按：2 倍速播放（主流做法），松手恢复原速，不用反复跳进。
 * - 单击：交给调用方（显示 / 隐藏控制条）。
 */
class PlayerGestureHelper(
    private val activity: Activity,
    private val hintView: TextView,
    private val currentPosition: () -> Long,
    private val duration: () -> Long,
    private val seekTo: (Long) -> Unit,
    private val setSpeed: (Float) -> Unit,
    private val onTap: () -> Unit
) {
    companion object {
        private const val LONG_PRESS_MS = 400L
        private const val FAST_FORWARD_SPEED = 2.0f
        /** 横划过整个有效宽度对应的时长 */
        private const val SWIPE_FULL_WIDTH_MS = 120_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val density by lazy { activity.resources.displayMetrics.density }
    private val touchSlop by lazy { (density * 12f).toInt() }

    private var downX = 0f
    private var downY = 0f
    /** 0 未判定 / 1 横向快进快退 / 2 纵向音量亮度 */
    private var mode = 0
    /** 1 右半屏(音量) / -1 左半屏(亮度) */
    private var verticalSide = 0
    private var longPressed = false
    private var speedChanged = false

    /** 按下瞬间的播放进度，左右滑以它为基准 */
    private var downPosition = 0L
    /** 开始纵向滑动时的当前值（0-100） */
    private var basePercent = 0f
    private var maxVolume = 15

    private val longPressTask = Runnable {
        if (mode != 0) return@Runnable
        longPressed = true
        speedChanged = true
        setSpeed(FAST_FORWARD_SPEED)
        showHint("快进 ${FAST_FORWARD_SPEED.toInt()}x")
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
                downPosition = currentPosition()
                mode = 0
                longPressed = false
                speedChanged = false
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                handler.removeCallbacks(longPressTask)
                handler.postDelayed(longPressTask, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalX = event.x - downX
                val totalY = event.y - downY

                if (mode == 0 && (abs(totalX) > touchSlop || abs(totalY) > touchSlop)) {
                    mode = if (abs(totalX) >= abs(totalY)) 1 else 2
                    verticalSide = if (downX >= view.width / 2f) 1 else -1
                    handler.removeCallbacks(longPressTask)
                    // 从按下时的真实值起算，避免"一碰屏幕就跳到某个值"
                    basePercent = if (verticalSide > 0) currentVolumePercent() else currentBrightnessPercent()
                    // 长按已经改变倍速的话，滑动时先恢复，避免两者叠加
                    if (speedChanged) {
                        setSpeed(1f)
                        speedChanged = false
                    }
                }

                when (mode) {
                    1 -> applySeek(view, event.x)
                    2 -> applyVolumeOrBrightness(view, event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasTap = mode == 0 && !longPressed
                finish()
                if (wasTap) onTap()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finish()
                return true
            }
        }
        return false
    }

    /** 左右滑：按下点为基准，松开回到原点即回到原进度 */
    private fun applySeek(view: View, x: Float) {
        val deltaMs = ((x - downX) / view.width * SWIPE_FULL_WIDTH_MS).toLong()
        val total = duration()
        val target = (downPosition + deltaMs).coerceAtLeast(0L).let {
            if (total > 0) it.coerceAtMost(total) else it
        }
        seekTo(target)
        val seconds = abs(deltaMs) / 1000
        showHint(
            (if (deltaMs > 0) "快进" else "快退") + " ${seconds}s  →  " + formatTime(target)
        )
    }

    /**
     * 上下滑：有效区从屏幕边缘往里缩（上 72dp、下 120dp），
     * 到有效区顶部即 100、底部即 0，手指不用贴到物理边缘。
     */
    private fun applyVolumeOrBrightness(view: View, y: Float) {
        val top = (72 * density).toInt()
        val bottom = (120 * density).toInt()
        val limitTop = top.toFloat()
        val limitBottom = (view.height - bottom).toFloat()
        if (limitBottom <= limitTop) return

        val curY = y.coerceIn(limitTop, limitBottom)
        val startY = downY.coerceIn(limitTop, limitBottom)
        val range = limitBottom - limitTop

        // 相对位移：从当前值开始按滑动比例增减
        var percent = basePercent + (startY - curY) / range * 100f
        // 触到有效区边界就直接给极值，保证划到顶/底能到 100 / 0
        if (curY <= limitTop + touchSlop) percent = 100f
        if (curY >= limitBottom - touchSlop) percent = 0f
        val value = percent.toInt().coerceIn(0, 100)

        if (verticalSide > 0) {
            val target = (value / 100f * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            showHint("音量 $value")
        } else {
            val lp = activity.window.attributes
            // 0f 会被系统当成"跟随系统亮度"，给个极小值保证真的最暗
            lp.screenBrightness = if (value == 0) 0.01f else value / 100f
            activity.window.attributes = lp
            showHint("亮度 $value")
        }
    }

    private fun currentVolumePercent(): Float {
        if (maxVolume <= 0) return 0f
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maxVolume
    }

    private fun currentBrightnessPercent(): Float {
        val windowBrightness = activity.window.attributes.screenBrightness
        if (windowBrightness >= 0f) return (windowBrightness * 100f).coerceIn(0f, 100f)
        // 尚未手动调过：跟随系统亮度
        return try {
            val system = Settings.System.getInt(
                activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
            )
            (system * 100f / 255f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            50f
        }
    }

    /** 松手 / 取消：恢复倍速并隐藏提示 */
    private fun finish() {
        handler.removeCallbacks(longPressTask)
        if (speedChanged) {
            setSpeed(1f)
            speedChanged = false
        }
        mode = 0
        hintView.visibility = View.GONE
    }

    /** 生命周期里调用：停止长按倍速并隐藏提示 */
    fun cancel() = finish()

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
