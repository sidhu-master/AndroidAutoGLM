package com.sidhu.androidautoglm

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.sidhu.autoinput.GestureAnimator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

import com.sidhu.androidautoglm.utils.DisplayUtils
import android.view.WindowManager

class AutoGLMService : AccessibilityService() {

    companion object {
        private val _serviceInstance = MutableStateFlow<AutoGLMService?>(null)
        val serviceInstance = _serviceInstance.asStateFlow()
        
        fun getInstance(): AutoGLMService? = _serviceInstance.value
    }
    
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp = _currentApp.asStateFlow()

    private var _floatingWindowController: FloatingWindowController? = null

    /** Expose floating window controller for external access */
    val floatingWindowController: FloatingWindowController? get() = this._floatingWindowController

    /** Animation controller for gesture visual feedback */
    private var _animationController: AnimationController? = null

    // Coroutine scope for service operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lastImeVisible: Boolean = false
    private var lastImeCheckMs: Long = 0
    private var lastMoveWindowToTopMs: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        _serviceInstance.value = this
        Log.d("AutoGLMService", "Service connected")

        // Initialize controllers
        _floatingWindowController = FloatingWindowController(this)
        _animationController = AnimationController(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _floatingWindowController?.removeAndHide()
        _floatingWindowController = null
        _animationController = null
        _serviceInstance.value = null
        return super.onUnbind(intent)
    }
    
    /**
     * Shows the floating window and waits for layout to complete.
     * This is more reliable than blind delay for ensuring window is ready for operations like screenshot.
     *
     * @param onStop Optional callback when stop button is clicked
     * @param isRunning Whether the task is currently running (affects UI display)
     */
    suspend fun showFloatingWindowAndWait(onStop: () -> Unit, isRunning: Boolean = true) {
        withContext(Dispatchers.Main) {
            if (_floatingWindowController == null) {
                _floatingWindowController = FloatingWindowController(this@AutoGLMService)
            }
            Log.d("AutoGLMService", "showFloatingWindowAndWait called with isRunning=$isRunning")
            _floatingWindowController?.showAndWaitForLayout(onStop, isRunning)
        }
    }

    /**
     * Resets the floating window dismissed state for a new task.
     * Should be called when a new task starts.
     */
    fun resetFloatingWindowForNewTask() {
        serviceScope.launch {
            if (_floatingWindowController == null) {
                _floatingWindowController = FloatingWindowController(this@AutoGLMService)
            }
            _floatingWindowController?.resetForNewTask()
            Log.d("AutoGLMService", "Reset floating window for new task")
        }
    }

    /**
     * Hides and removes the floating window completely.
     * For temporary hiding during gestures/screenshots, use the useWindowSuspension() helper.
     */
    fun hideFloatingWindow() {
        serviceScope.launch {
            _floatingWindowController?.removeAndHide()
        }
    }

    /**
     * Dismisses the floating window and marks it as user-dismissed.
     * The window will not auto-show on app background until resetFloatingWindowForNewTask() is called.
     */
    fun dismissFloatingWindow() {
        serviceScope.launch {
            _floatingWindowController?.dismiss()
        }
    }

    fun updateFloatingStatus(text: String) {
        serviceScope.launch {
            _floatingWindowController?.updateStatus(text)
        }
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun isImeVisible(): Boolean {
        return try {
            val currentWindows = windows
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun handleImeVisibility() {
        val now = System.currentTimeMillis()
        val throttleMs = 250L
        if (now - lastImeCheckMs < throttleMs) return
        lastImeCheckMs = now

        val visible = isImeVisible()
        if (visible) {
            val debounceMs = 800L
            val isRisingEdge = !lastImeVisible
            val canRepeat = now - lastMoveWindowToTopMs >= debounceMs
            if (isRisingEdge || canRepeat) {
                lastMoveWindowToTopMs = now
                _floatingWindowController?.moveWindowToTop()
            }
        }
        lastImeVisible = visible
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handleImeVisibility()
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
    
    fun getScreenHeight(): Int = DisplayUtils.getScreenHeight(this)

    fun getScreenWidth(): Int = DisplayUtils.getScreenWidth(this)

    /**
     * Takes a screenshot with callback-based completion and timeout.
     * Uses useWindowSuspension helper for floating window state management.
     *
     * @param timeoutMs Timeout for the screenshot operation in milliseconds (default: 5000ms)
     * @return The screenshot bitmap, or null if failed/timeout
     */
    suspend fun takeScreenshot(timeoutMs: Long = 5000): Bitmap? {
        return _floatingWindowController?.useWindowSuspension {
            GestureAnimator.hideAllOverlays()
            delay(60)
            try {
                // Use withTimeout to handle screenshot operation timeout
                withTimeout(timeoutMs) {
                    // Take Screenshot with callback
                    suspendCoroutine<Bitmap?> { continuation ->
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
                }
            } finally {
                GestureAnimator.restoreAllOverlays()
            }
        } ?: run {
            Log.e("AutoGLMService", "FloatingWindowController not available for screenshot")
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
        serviceScope.launch {
            _floatingWindowController?.avoidArea(x, y)
        }

        return _floatingWindowController?.useWindowSuspension {
            // Show visual indicator
            _animationController?.showTapAnimation(x, y)

            // Dispatch gesture and wait for completion
            suspendCoroutine { continuation ->
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x, y)
                }
                val builder = GestureDescription.Builder()
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

                val dispatchResult = dispatchGesture(builder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d("AutoGLMService", "Gesture Completed: Tap at ($x, $y)")
                        continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w("AutoGLMService", "Gesture Cancelled: Tap at ($x, $y)")
                        continuation.resume(false)
                    }
                }, null)

                if (!dispatchResult) {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    suspend fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 1000): Boolean {
        // Check overlap and move if necessary (using start position)
        serviceScope.launch {
            _floatingWindowController?.avoidArea(startX, startY)
        }

        // Show visual indicator
        _animationController?.showSwipeAnimation(startX, startY, endX, endY, duration)

        return _floatingWindowController?.useWindowSuspension {
            // Dispatch gesture and wait for completion
            suspendCoroutine { continuation ->
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
                        continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        continuation.resume(false)
                    }
                }, null)

                if (!dispatchResult) {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    suspend fun performLongPress(x: Float, y: Float, duration: Long = 1000): Boolean {
        Log.d("AutoGLMService", "Dispatching Gesture: Long Press at ($x, $y)")
        // Show visual indicator
        _animationController?.showTapAnimation(x, y, duration)
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
