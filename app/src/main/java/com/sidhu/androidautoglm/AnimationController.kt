package com.sidhu.androidautoglm

import android.content.Context
import com.sidhu.androidautoglm.utils.DisplayUtils
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Controller for managing gesture animations displayed to the user.
 * Handles visual feedback for tap, swipe, and long press gestures.
 *
 * Separated from AutoGLMService to follow single responsibility principle.
 * All animation operations are executed on the main thread.
 */
class AnimationController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Shows a tap animation (pulsing circle) at the specified coordinates.
     *
     * @param x X coordinate of the tap
     * @param y Y coordinate of the tap
     * @param duration Duration of the animation in milliseconds
     */
    fun showTapAnimation(x: Float, y: Float, duration: Long = 1000) {
        showGestureAnimation(x, y, null, null, duration)
    }

    /**
     * Shows a swipe animation (line trail + circle) from start to end coordinates.
     *
     * @param startX Starting X coordinate
     * @param startY Starting Y coordinate
     * @param endX Ending X coordinate
     * @param endY Ending Y coordinate
     * @param duration Duration of the animation in milliseconds
     */
    fun showSwipeAnimation(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 1000) {
        showGestureAnimation(startX, startY, endX, endY, duration)
    }

    /**
     * Shows a gesture animation at the specified coordinates.
     * Supports both tap (single point) and swipe (start to end) animations.
     * This method ensures all operations run on the main thread.
     *
     * @param startX Starting X coordinate
     * @param startY Starting Y coordinate
     * @param endX Ending X coordinate (null for tap animation)
     * @param endY Ending Y coordinate (null for tap animation)
     * @param duration Duration of the animation in milliseconds
     */
    private fun showGestureAnimation(
        startX: Float,
        startY: Float,
        endX: Float? = null,
        endY: Float? = null,
        duration: Long = 1000
    ) {
        // Ensure we're on the main thread
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
        endX: Float? = null,
        endY: Float? = null,
        duration: Long = 1000
    ) {
        val view = object : View(context) {
            private val paint = Paint().apply {
                color = Color.YELLOW
                style = Paint.Style.FILL
                alpha = 150
            }

            // For swipe trail
            private val trailPaint = Paint().apply {
                color = Color.YELLOW
                style = Paint.Style.STROKE
                strokeWidth = 20f
                alpha = 100
                strokeCap = Paint.Cap.ROUND
            }

            private var currentX = startX
            private var currentY = startY
            private var currentRadius = 30f

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (endX != null && endY != null) {
                    // Draw trail from start to current
                    canvas.drawLine(startX, startY, currentX, currentY, trailPaint)
                }
                canvas.drawCircle(currentX, currentY, currentRadius, paint)
            }

            fun startAnimation() {
                if (endX != null && endY != null) {
                    // Swipe Animation
                    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
                    animator.duration = duration
                    animator.addUpdateListener { animation ->
                        val fraction = animation.animatedValue as Float
                        currentX = startX + (endX - startX) * fraction
                        currentY = startY + (endY - startY) * fraction
                        invalidate()
                    }
                    animator.start()
                } else {
                    // Tap Animation: Pulse effect
                    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
                    animator.duration = duration
                    animator.addUpdateListener { animation ->
                        val fraction = animation.animatedValue as Float
                        // Expand and fade
                        currentRadius = 30f + 30f * fraction
                        paint.alpha = (150 * (1 - fraction)).toInt()
                        invalidate()
                    }
                    animator.start()
                }
            }
        }

        val params = WindowManager.LayoutParams(
            getScreenWidth(),
            getScreenHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.x = 0
        params.y = 0

        try {
            windowManager.addView(view, params)
            // Start animation after view is added
            view.startAnimation()
            // Remove view after animation completes
            mainHandler.postDelayed({
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    // Ignore - view may already be removed
                }
            }, duration + 200)
        } catch (e: Exception) {
            Log.e("AnimationController", "Failed to show gesture animation", e)
        }
    }

    private fun getScreenWidth(): Int = DisplayUtils.getScreenWidth(context)

    private fun getScreenHeight(): Int = DisplayUtils.getScreenHeight(context)
}
