package com.sidhu.androidautoglm

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper

class AutoGLMService : AccessibilityService() {

    companion object {
        private val _serviceInstance = MutableStateFlow<AutoGLMService?>(null)
        val serviceInstance = _serviceInstance.asStateFlow()
        
        fun getInstance(): AutoGLMService? = _serviceInstance.value
    }
    
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp = _currentApp.asStateFlow()

    private val _isTaskRunning = MutableStateFlow(false)
    val isTaskRunning = _isTaskRunning.asStateFlow()
    
    private var floatingWindowController: FloatingWindowController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        _serviceInstance.value = this
        Log.d("AutoGLMService", "Service connected")
        
        // Initialize Floating Window Controller
        floatingWindowController = FloatingWindowController(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        floatingWindowController?.hide()
        floatingWindowController = null
        _serviceInstance.value = null
        return super.onUnbind(intent)
    }
    
    /**
     * Shows the floating window. Creates the controller if needed.
     * @param onStop Optional callback when stop button is clicked
     * @param isRunning Whether the task is currently running (affects UI display)
     */
    fun showFloatingWindow(onStop: () -> Unit, isRunning: Boolean = true) {
        Handler(Looper.getMainLooper()).post {
            if (floatingWindowController == null) {
                floatingWindowController = FloatingWindowController(this)
            }
            Log.d("AutoGLMService", "showFloatingWindow called with isRunning=$isRunning")
            floatingWindowController?.show(onStop, isRunning)
        }
    }

    /**
     * Resets the floating window dismissed state for a new task.
     * Should be called when a new task starts.
     */
    fun resetFloatingWindowForNewTask() {
        Handler(Looper.getMainLooper()).post {
            if (floatingWindowController == null) {
                floatingWindowController = FloatingWindowController(this)
            }
            floatingWindowController?.resetForNewTask()
            Log.d("AutoGLMService", "Reset floating window for new task")
        }
    }

    /**
     * Hides and removes the floating window completely.
     * Use setFloatingWindowVisible(false) for temporary hiding (e.g., during screenshots).
     */
    fun hideFloatingWindow() {
        Handler(Looper.getMainLooper()).post {
            floatingWindowController?.hide()
        }
    }

    /**
     * Dismisses the floating window and marks it as user-dismissed.
     * The window will not auto-show on app background until resetFloatingWindowForNewTask() is called.
     */
    fun dismissFloatingWindow() {
        Handler(Looper.getMainLooper()).post {
            floatingWindowController?.dismiss()
        }
    }

    /**
     * Sets floating window visibility without removing it and waits for layout to complete.
     *
     * This is preferred over hideFloatingWindow() for temporary hiding during gestures/screenshots
     * where you need to ensure the layout is updated before proceeding.
     */
    private suspend fun setFloatingWindowVisibleAndWait(visible: Boolean) {
        suspendCoroutine<Unit> { continuation ->
            Handler(Looper.getMainLooper()).post {
                floatingWindowController?.setScreenshotMode(!visible) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    /**
     * Sets floating window visibility without removing it, returns immediately.
     *
     * This is a fire-and-forget version that doesn't wait for layout to complete.
     */
    private fun setFloatingWindowVisible(visible: Boolean) {
        Handler(Looper.getMainLooper()).post {
            floatingWindowController?.setScreenshotMode(!visible)
        }
    }
    
    fun updateFloatingStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            floatingWindowController?.updateStatus(text)
        }
    }

    fun setTaskRunning(running: Boolean) {
        _isTaskRunning.value = running
        Handler(Looper.getMainLooper()).post {
            floatingWindowController?.setTaskRunning(running)
        }
    }
    
    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let {
            val pkg = it.toString()
            if (_currentApp.value != pkg) {
                Log.d("AutoGLM_Trace", "Current App changed to: $pkg")
                _currentApp.value = pkg
            }
        }
    }

    override fun onInterrupt() {
        Log.w("AutoGLMService", "Service interrupted")
    }
    
    fun getScreenHeight(): Int {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        return windowManager.currentWindowMetrics.bounds.height()
    }
    
    fun getScreenWidth(): Int {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        return windowManager.currentWindowMetrics.bounds.width()
    }

    private fun showGestureAnimation(startX: Float, startY: Float, endX: Float? = null, endY: Float? = null, duration: Long = 1000) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val view = object : View(this) {
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
            
            init {
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

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (endX != null && endY != null) {
                    // Draw trail from start to current
                    canvas.drawLine(startX, startY, currentX, currentY, trailPaint)
                }
                canvas.drawCircle(currentX, currentY, currentRadius, paint)
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
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        try {
            windowManager.addView(view, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    // Ignore
                }
            }, duration + 200)
        } catch (e: Exception) {
            Log.e("AutoGLMService", "Failed to show gesture animation", e)
        }
    }

    /**
     * Takes a screenshot with callback-based completion and timeout.
     * Uses async callback for floating window state changes instead of hardcoded delays.
     *
     * @param timeoutMs Timeout for the screenshot operation in milliseconds (default: 5000ms)
     * @return The screenshot bitmap, or null if failed/timeout
     */
    suspend fun takeScreenshot(timeoutMs: Long = 5000): Bitmap? {
        // Use withTimeout to handle screenshot operation timeout
        return try {
            withTimeout(timeoutMs) {
                // 1. Hide Floating Window and wait for layout to complete
                setFloatingWindowVisibleAndWait(false)

                // 2. Take Screenshot with callback
                val screenshot = suspendCoroutine<Bitmap?> { continuation ->
                    val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                    val displayId = windowManager.defaultDisplay.displayId

                    takeScreenshot(
                        displayId,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                try {
                                    val bitmap = Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer,
                                        screenshot.colorSpace
                                    )
                                    // Copy to software bitmap for processing
                                    val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    screenshot.hardwareBuffer.close()
                                    continuation.resume(softwareBitmap)
                                } catch (e: Exception) {
                                    Log.e("AutoGLMService", "Error processing screenshot", e)
                                    continuation.resume(null)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                val errorMsg = when(errorCode) {
                                    ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
                                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
                                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
                                    ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
                                    else -> "UNKNOWN($errorCode)"
                                }
                                Log.e("AutoGLMService", "Screenshot failed: $errorMsg")
                                continuation.resume(null)
                            }
                        }
                    )
                }

                // 3. Restore Floating Window (fire-and-forget)
                setFloatingWindowVisible(true)

                screenshot
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("AutoGLMService", "Screenshot operation timed out after $timeoutMs ms")
            // Ensure floating window is restored even on timeout
            setFloatingWindowVisible(true)
            null
        }
    }

    suspend fun performTap(x: Float, y: Float): Boolean {
        val serviceWidth = getScreenWidth()
        val serviceHeight = getScreenHeight()
        Log.d("AutoGLMService", "performTap: Request($x, $y) vs Screen($serviceWidth, $serviceHeight)")

        if (x < 0 || x > serviceWidth || y < 0 || y > serviceHeight) {
            Log.w("AutoGLMService", "Tap coordinates ($x, $y) out of bounds")
            return false
        }

        Log.d("AutoGLMService", "Dispatching Gesture: Tap at ($x, $y)")

        // Check overlap and move if necessary
        Handler(Looper.getMainLooper()).post {
             floatingWindowController?.avoidArea(x, y)
        }

        // Hide floating window and wait for layout to complete BEFORE dispatching gesture
        setFloatingWindowVisibleAndWait(false)

        // Show visual indicator on UI thread
        Handler(Looper.getMainLooper()).post {
            showGestureAnimation(x, y)
        }

        // Dispatch gesture and wait for completion
        val result = suspendCoroutine<Boolean> { continuation ->
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

            val dispatchResult = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d("AutoGLMService", "Gesture Completed: Tap at ($x, $y)")
                    setFloatingWindowVisible(true)
                    continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w("AutoGLMService", "Gesture Cancelled: Tap at ($x, $y)")
                    setFloatingWindowVisible(true)
                    continuation.resume(false)
                }
            }, null)

            if (!dispatchResult) {
                // If dispatch failed immediately, restore window and return false
                setFloatingWindowVisible(true)
                continuation.resume(false)
            }
        }

        Log.d("AutoGLMService", "dispatchGesture result: $result")
        return result
    }

    suspend fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 1000): Boolean {
        // Check overlap and move if necessary (using start position)
        Handler(Looper.getMainLooper()).post {
             floatingWindowController?.avoidArea(startX, startY)
        }

        // Show visual indicator on UI thread
        Handler(Looper.getMainLooper()).post {
             showGestureAnimation(startX, startY, endX, endY, duration)
        }

        // Hide floating window and wait for layout to complete BEFORE dispatching gesture
        setFloatingWindowVisibleAndWait(false)

        // Dispatch gesture and wait for completion
        val result = suspendCoroutine<Boolean> { continuation ->
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val builder = GestureDescription.Builder()
            // Use a fixed shorter duration (500ms) for the actual gesture to ensure it registers as a fling/scroll
            // The animation will play slower (duration) to be visible to the user
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 500))

            val dispatchResult = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    setFloatingWindowVisible(true)
                    continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    setFloatingWindowVisible(true)
                    continuation.resume(false)
                }
            }, null)

            if (!dispatchResult) {
                // If dispatch failed immediately, restore window and return false
                setFloatingWindowVisible(true)
                continuation.resume(false)
            }
        }

        return result
    }

    suspend fun performLongPress(x: Float, y: Float, duration: Long = 1000): Boolean {
        Log.d("AutoGLMService", "Dispatching Gesture: Long Press at ($x, $y)")
        Handler(Looper.getMainLooper()).post {
            showGestureAnimation(x, y, null, null, duration)
        }
        // Long press is effectively a swipe from x,y to x,y with long duration
        return performSwipe(x, y, x, y, duration)
    }
    
    fun performGlobalBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
    
    fun performGlobalHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
