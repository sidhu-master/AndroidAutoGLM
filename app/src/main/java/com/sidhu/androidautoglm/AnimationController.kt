package com.sidhu.androidautoglm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.os.Handler
import android.os.Looper
import com.sidhu.androidautoglm.utils.DisplayUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** 主色：柔和青蓝 */
private const val ACCENT_COLOR = 0xFF4FC3F7.toInt()
/** 高亮：白色光晕 (50% alpha) */
private val HIGHLIGHT_COLOR = Color.argb(128, 255, 255, 255)

/**
 * Controller for managing gesture animations displayed to the user.
 * Handles visual feedback for tap, swipe, and long press gestures.
 */
class AnimationController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun showTapAnimation(x: Float, y: Float, duration: Long = 1000) {
        showGestureAnimation(x, y, null, null, duration)
    }

    fun showSwipeAnimation(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 1000) {
        showGestureAnimation(startX, startY, endX, endY, duration)
    }

    private fun showGestureAnimation(
        startX: Float,
        startY: Float,
        endX: Float? = null,
        endY: Float? = null,
        duration: Long = 1000
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            animationScope.launch {
                showGestureAnimationOnMainThread(startX, startY, endX, endY, duration)
            }
        } else {
            showGestureAnimationOnMainThread(startX, startY, endX, endY, duration)
        }
    }

    private fun showGestureAnimationOnMainThread(
        startX: Float,
        startY: Float,
        endX: Float?,
        endY: Float?,
        duration: Long
    ) {
        val isSwipe = endX != null && endY != null

        val view = object : View(context) {
            val corePaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                color = ACCENT_COLOR
            }
            val ringPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                color = ACCENT_COLOR
                strokeWidth = 4f
            }
            val glowPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                color = HIGHLIGHT_COLOR
            }

            var trailFraction = 0f
            var tapRadius = 0f
            var tapRingRadius = 0f
            var tapAlpha = 255

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (isSwipe && endX != null && endY != null) {
                    val dx = endX - startX
                    val dy = endY - startY
                    val len = sqrt(dx * dx + dy * dy)
                    if (len > 1f) {
                        val t = trailFraction
                        val cx = startX + dx * t
                        val cy = startY + dy * t
                        val trailPaint = Paint().apply {
                            isAntiAlias = true
                            style = Paint.Style.STROKE
                            strokeWidth = 20f
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            color = ACCENT_COLOR
                            alpha = 180
                        }
                        canvas.drawLine(startX, startY, cx, cy, trailPaint)
                        glowPaint.alpha = (220 * (1 - t * 0.4f)).toInt().coerceIn(0, 255)
                        canvas.drawCircle(cx, cy, 26f, glowPaint)
                        corePaint.alpha = 230
                        canvas.drawCircle(cx, cy, 16f, corePaint)
                    }
                } else {
                    if (tapRadius > 0) {
                        corePaint.alpha = (tapAlpha * 0.55f).toInt().coerceIn(0, 255)
                        canvas.drawCircle(startX, startY, tapRadius, corePaint)
                    }
                    if (tapRingRadius > 0) {
                        ringPaint.alpha = (tapAlpha * 0.85f).toInt().coerceIn(0, 255)
                        canvas.drawCircle(startX, startY, tapRingRadius, ringPaint)
                    }
                    if (tapRadius > 0 || tapRingRadius > 0) {
                        glowPaint.alpha = (tapAlpha * 0.35f).toInt().coerceIn(0, 255)
                        canvas.drawCircle(startX, startY, (tapRadius + tapRingRadius) / 2f, glowPaint)
                    }
                }
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            getScreenWidth(),
            getScreenHeight(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(view, params)
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(80).start()

            if (isSwipe && endX != null && endY != null) {
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    this.duration = duration
                    interpolator = LinearInterpolator()
                    addUpdateListener { anim ->
                        view.trailFraction = anim.animatedValue as Float
                        view.invalidate()
                    }
                    start()
                }
            } else {
                val decelerate = DecelerateInterpolator()
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    this.duration = duration
                    addUpdateListener { anim ->
                        val f = anim.animatedValue as Float
                        val eased = decelerate.getInterpolation(f)
                        view.tapRadius = 10f + 52f * eased
                        view.tapRingRadius = 14f + 60f * eased
                        view.tapAlpha = (255 * (1 - f * 0.92f)).toInt().coerceIn(0, 255)
                        view.invalidate()
                    }
                    start()
                }
            }

            mainHandler.postDelayed({
                view.animate().alpha(0f).setDuration(120).withEndAction {
                    try { windowManager.removeView(view) } catch (_: Exception) {}
                }.start()
            }, duration + 80)
        } catch (e: Exception) {
            Log.e("AnimationController", "Failed to show gesture animation", e)
        }
    }

    private fun getScreenWidth(): Int = DisplayUtils.getScreenWidth(context)
    private fun getScreenHeight(): Int = DisplayUtils.getScreenHeight(context)
}
