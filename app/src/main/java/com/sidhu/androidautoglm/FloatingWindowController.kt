package com.sidhu.androidautoglm

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt
import kotlin.coroutines.resume

import com.sidhu.androidautoglm.utils.DisplayUtils
import com.sidhu.androidautoglm.ui.floating.FloatingWindowContent
import com.sidhu.androidautoglm.ui.RecordingIndicator
import com.sidhu.androidautoglm.ui.VoiceReviewOverlay

/**
 * Sealed class hierarchy representing the floating window state machine.
 *
 * State Transition Rules:
 * - Hidden -> Visible: New task starts
 * - Visible -> TaskCompleted: Task finishes naturally
 * - TaskCompleted -> Hidden: User opens app
 * - Visible -> Hidden: User stops task or window dismissed
 * - Visible <-> TemporarilyHidden: Screenshots/gestures
 * - Visible <-> RecordingOverlayShown: Voice recording
 * - RecordingOverlayShown <-> ReviewOverlayShown: Review recording
 * - RecordingOverlayShown/ReviewOverlayShown -> Visible: Overlay dismissed
 * - Visible -> Visible: Update status/isTaskRunning (no full transition)
 */
sealed class FloatingWindowState {
    /** Window is not attached to WindowManager */
    data object Hidden : FloatingWindowState()

    /**
     * Window is visible and task is running (or completed but window shown)
     * Contains all state data needed for the visible window.
     */
    data class Visible(
        val statusText: String,
        val isTaskRunning: Boolean = true,
        val onStopCallback: (() -> Unit)? = null,
        val isPaused: Boolean = false,
        val onPauseResumeCallback: (() -> Unit)? = null,
        val taskList: List<String> = emptyList(),
        val thinkingLines: List<String> = emptyList(),
        val actionContent: com.sidhu.androidautoglm.ui.model.FormattedContent.ActionContent? = null
    ) : FloatingWindowState()

    /** Task has completed naturally (not user cancelled). Preserves task list and thinking for display. */
    data class TaskCompleted(
        val statusText: String,
        val taskList: List<String> = emptyList(),
        val thinkingLines: List<String> = emptyList(),
        val actionContent: com.sidhu.androidautoglm.ui.model.FormattedContent.ActionContent? = null
    ) : FloatingWindowState()

    /**
     * Window is temporarily hidden for screenshots/gestures (size 0x0, not touchable)
     * Explicitly caches the Visible state data for restoration.
     */
    data class TemporarilyHidden(
        val cachedStatusText: String,
        val cachedIsTaskRunning: Boolean,
        val cachedOnStopCallback: (() -> Unit)?,
        val cachedIsPaused: Boolean = false,
        val cachedOnPauseResumeCallback: (() -> Unit)? = null,
        val cachedTaskList: List<String> = emptyList(),
        val cachedThinkingLines: List<String> = emptyList(),
        val cachedActionContent: com.sidhu.androidautoglm.ui.model.FormattedContent.ActionContent? = null
    ) : FloatingWindowState()

    /**
     * Voice recording overlay is shown (not focusable, full-screen visual feedback)
     * Preserves the underlying Visible state for proper restoration.
     */
    data class RecordingOverlayShown(
        val underlyingState: Visible
    ) : FloatingWindowState()

    /**
     * Voice review overlay is shown (focusable, allows text editing)
     * Contains the recognized text and callbacks for user actions.
     * Preserves the underlying Visible state for proper restoration.
     */
    data class ReviewOverlayShown(
        val underlyingState: Visible,
        val text: String,
        val onTextChange: (String) -> Unit,
        val onSend: () -> Unit,
        val onCancel: () -> Unit
    ) : FloatingWindowState()
}

class FloatingWindowController(private val context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val floatingWindowManager = FloatingWindowManager(context)
    private var floatView: ComposeView? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    /**
     * Mutex to ensure state transitions are atomic.
     * This prevents race conditions during concurrent state changes.
     */
    private val stateMutex = Mutex()

    /** 记录 suspendWindow 调用时刻，用于统计用户体感的隐藏时长 */
    private var lastSuspendTimeMs: Long = 0
    /** 记录 operation 开始时刻（delay(80) 之后），用于拆分日志 */
    private var operationStartTimeMs: Long = 0

    /**
     * Whether the floating window is currently attached to WindowManager.
     * This is derived from the state machine - true when window is shown,
     * temporarily hidden, or has overlays (recording/review).
     */
    private val isShowing: Boolean
        get() = _stateFlow.value is FloatingWindowState.Visible ||
                _stateFlow.value is FloatingWindowState.TaskCompleted ||
                _stateFlow.value is FloatingWindowState.TemporarilyHidden ||
                _stateFlow.value is FloatingWindowState.RecordingOverlayShown ||
                _stateFlow.value is FloatingWindowState.ReviewOverlayShown
    
    // Lifecycle components required for Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    // State machine for floating window visibility and interaction
    private val _stateFlow = MutableStateFlow<FloatingWindowState>(FloatingWindowState.Hidden)
    /** Public read-only state flow for observing floating window state changes */
    val stateFlow: StateFlow<FloatingWindowState> = _stateFlow.asStateFlow()

    /**
     * Validates if a state transition is allowed.
     * Returns true if the transition is valid, false otherwise.
     */
    private fun isValidTransition(from: FloatingWindowState, to: FloatingWindowState): Boolean {
        return when (from) {
            is FloatingWindowState.Hidden -> {
                to is FloatingWindowState.Visible
            }
            is FloatingWindowState.Visible -> {
                to is FloatingWindowState.Hidden ||
                to is FloatingWindowState.TemporarilyHidden ||
                to is FloatingWindowState.RecordingOverlayShown ||
                to is FloatingWindowState.ReviewOverlayShown ||
                to is FloatingWindowState.TaskCompleted ||
                to is FloatingWindowState.Visible  // Allow update
            }
            is FloatingWindowState.TemporarilyHidden -> {
                to is FloatingWindowState.Visible
            }
            is FloatingWindowState.RecordingOverlayShown -> {
                to is FloatingWindowState.Visible || to is FloatingWindowState.ReviewOverlayShown
            }
            is FloatingWindowState.ReviewOverlayShown -> {
                to is FloatingWindowState.Visible
            }
            is FloatingWindowState.TaskCompleted -> {
                to is FloatingWindowState.Hidden
            }
        }
    }

    // Coroutine scope for managing async operations
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Log.d("FloatingWindow", "Initial state: Hidden")
    }

    private var overlayView: ComposeView? = null

    /**
     * Helper method to show an overlay view with the given content.
     * @param focusable Whether the overlay should be focusable
     * @param content The composable content to display
     */
    private fun showOverlayView(focusable: Boolean, content: @Composable () -> Unit) {
        if (overlayView != null) hideOverlayView()

        overlayView = floatingWindowManager.createOverlayView(
            this@FloatingWindowController,
            this@FloatingWindowController,
            this@FloatingWindowController,
            focusable,
            content
        )

        floatingWindowManager.addOverlay(overlayView!!)
    }

    /**
     * Helper method to hide and remove the overlay view.
     */
    private fun hideOverlayView() {
        floatingWindowManager.removeOverlay(overlayView)
        overlayView = null
    }

    fun showOverlay(focusable: Boolean = false, content: @Composable () -> Unit) {
        // Legacy method - kept for backward compatibility during transition
        // TODO: Remove after FloatingWindowContent is updated to use state-based overlays
        showOverlayView(focusable, content)
    }

    fun hideOverlay() {
        hideOverlayView()
        // Restore underlying state from overlay states
        val currentState = _stateFlow.value
        val restoredState = when (currentState) {
            is FloatingWindowState.RecordingOverlayShown -> currentState.underlyingState
            is FloatingWindowState.ReviewOverlayShown -> currentState.underlyingState
            else -> {
                Log.d("FloatingWindow", "hideOverlay: no overlay state to restore from")
                return
            }
        }
        _stateFlow.value = restoredState
        Log.d("FloatingWindow", "Overlay hidden, restored to underlying state")
    }

    /**
     * Resets for a new task start.
     * Transition: Any state -> Visible (with task running)
     *
     * State Machine Rule: Starting a new task always transitions to Visible state,
     * regardless of previous state.
     *
     * For TaskCompleted state, we first transition to Hidden, then to Visible,
     * to maintain proper state machine transitions.
     */
    fun resetForNewTask() {
        controllerScope.launch {
            Log.d("FloatingWindow", "resetForNewTask() called, current state: ${_stateFlow.value}")
            val defaultStatus = context.getString(R.string.fw_ready)
            val preservedOnStopCallback = when (val s = _stateFlow.value) {
                is FloatingWindowState.Visible -> s.onStopCallback
                is FloatingWindowState.TemporarilyHidden -> s.cachedOnStopCallback
                is FloatingWindowState.RecordingOverlayShown -> s.underlyingState.onStopCallback
                is FloatingWindowState.ReviewOverlayShown -> s.underlyingState.onStopCallback
                is FloatingWindowState.Hidden,
                is FloatingWindowState.TaskCompleted -> null
            }
            val preservedOnPauseResumeCallback = when (val s = _stateFlow.value) {
                is FloatingWindowState.Visible -> s.onPauseResumeCallback
                is FloatingWindowState.TemporarilyHidden -> s.cachedOnPauseResumeCallback
                is FloatingWindowState.RecordingOverlayShown -> s.underlyingState.onPauseResumeCallback
                is FloatingWindowState.ReviewOverlayShown -> s.underlyingState.onPauseResumeCallback
                is FloatingWindowState.Hidden,
                is FloatingWindowState.TaskCompleted -> null
            }

            // Handle TaskCompleted state by first transitioning to Hidden
            if (_stateFlow.value is FloatingWindowState.TaskCompleted) {
                setState(FloatingWindowState.Hidden)
            }

            // Now transition to Visible state with task running (isPaused=false for new task)
            setState(FloatingWindowState.Visible(defaultStatus, true, preservedOnStopCallback, false, preservedOnPauseResumeCallback))
        }
    }

    /**
     * Marks the current task as completed naturally.
     * Should be called when a task completes naturally (not user cancelled).
     * Transition: Visible -> TaskCompleted
     *
     * State Machine Rule: Natural completion moves to TaskCompleted state,
     * which should be hidden when user opens app.
     */
    fun markTaskCompleted() {
        controllerScope.launch {
            Log.d("FloatingWindow", "markTaskCompleted() called")
            val currentState = _stateFlow.value
            val (currentStatus, taskList, thinkingLines) = when (currentState) {
                is FloatingWindowState.Visible -> Triple(
                    currentState.statusText,
                    currentState.taskList,
                    currentState.thinkingLines
                )
                else -> Triple(context.getString(R.string.fw_ready), emptyList(), emptyList())
            }
            val actionContent = (currentState as? FloatingWindowState.Visible)?.actionContent
            setState(FloatingWindowState.TaskCompleted(currentStatus, taskList, thinkingLines, actionContent))
        }
    }

    /**
     * Transitions to Hidden state when app is opened.
     * State Machine Rule: Hide window if task is not running or task is completed.
     *
     * @return true if transition occurred, false if window should stay visible
     */
    fun handleAppResumed(): Boolean {
        val currentState = _stateFlow.value
        return when (currentState) {
            is FloatingWindowState.Visible -> {
                // Check if task is still running
                if (currentState.isTaskRunning) {
                    // Task is running - keep window visible
                    Log.d("FloatingWindow", "handleAppResumed: Task running (${currentState.statusText}), keeping window visible")
                    false
                } else {
                    // Task not running - hide window
                    // This handles cases like:
                    // - User requested microphone permission (no task started yet)
                    // - Task was stopped but window hasn't been dismissed yet
                    Log.d("FloatingWindow", "handleAppResumed: Task not running (${currentState.statusText}), hiding window")
                    controllerScope.launch { setState(FloatingWindowState.Hidden) }
                    true
                }
            }
            is FloatingWindowState.TaskCompleted -> {
                // Task completed - hide window
                Log.d("FloatingWindow", "handleAppResumed: Task completed, hiding window")
                controllerScope.launch { setState(FloatingWindowState.Hidden) }
                true
            }
            is FloatingWindowState.Hidden,
            is FloatingWindowState.TemporarilyHidden,
            is FloatingWindowState.RecordingOverlayShown,
            is FloatingWindowState.ReviewOverlayShown -> {
                // Already hidden or in special mode
                false
            }
        }
    }

    /**
     * Transitions to Visible state when app is backgrounded.
     * State Machine Rule: Do NOT auto-show window. Window should only be shown
     * when a task explicitly starts via showFloatingWindowAndWait().
     *
     * @return true if transition occurred, false otherwise
     */
    fun handleAppPaused(): Boolean {
        val currentState = _stateFlow.value
        return when (currentState) {
            is FloatingWindowState.Visible -> {
                // Already visible
                false
            }
            is FloatingWindowState.Hidden,
            is FloatingWindowState.TaskCompleted,
            is FloatingWindowState.TemporarilyHidden,
            is FloatingWindowState.RecordingOverlayShown,
            is FloatingWindowState.ReviewOverlayShown -> {
                // Don't auto-show - window should only be shown when a task starts
                Log.d("FloatingWindow", "handleAppPaused: Not showing window (no active task)")
                false
            }
        }
    }

    /**
     * Core state machine method for managing floating window state transitions.
     * This is the preferred method for all state changes going forward.
     *
     * Uses Mutex to ensure atomic state transitions, preventing race conditions.
     *
     * @param newState The target state to transition to
     * @param onComplete Optional callback invoked after the transition is complete (on main thread)
     */
    suspend fun setState(
        newState: FloatingWindowState,
        onComplete: (() -> Unit)? = null
    ) = stateMutex.withLock {
        withContext(Dispatchers.Main) {
        val oldState = _stateFlow.value

        // Validate state transition
        if (!isValidTransition(oldState, newState)) {
            Log.e("FloatingWindow", "Invalid state transition: $oldState -> $newState")
            return@withContext
        }

        Log.d("FloatingWindow", "State transition: $oldState -> $newState")

        when (newState) {
            is FloatingWindowState.Hidden -> {
                hideOverlayView()
                // Remove window from WindowManager
                if (isShowing && floatView != null) {
                    floatingWindowManager.removeWindow(floatView)
                    floatView = null
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
                _stateFlow.value = newState
                onComplete?.invoke()
            }

            is FloatingWindowState.Visible -> {
                // Log the state transition
                val oldStatusText = (oldState as? FloatingWindowState.Visible)?.statusText ?: "N/A"
                val oldIsTaskRunning = (oldState as? FloatingWindowState.Visible)?.isTaskRunning
                Log.d("FloatingWindow", "setState: Transition to Visible - " +
                    "status=\"$oldStatusText\"->\"${newState.statusText}\", " +
                    "isTaskRunning=$oldIsTaskRunning->${newState.isTaskRunning}")

                if (!isShowing) {
                    // Create and add the window
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

                    windowParams = floatingWindowManager.createWindowParams()

                    floatView = floatingWindowManager.createComposeView(
                        this@FloatingWindowController,
                        this@FloatingWindowController,
                        this@FloatingWindowController
                    ) {
                        FloatingWindowContent(
                            floatingWindowController = this@FloatingWindowController,
                            onShowOverlay = { focusable, content ->
                                showOverlay(focusable, content)
                            },
                            onHideOverlay = {
                                hideOverlay()
                            },
                            onSendVoice = { text ->
                                try {
                                    Log.d("AutoGLM_Trace", "FloatingWindow: Sending voice command broadcast: $text")
                                    val broadcastIntent = Intent("com.sidhu.androidautoglm.ACTION_VOICE_COMMAND_BROADCAST")
                                    broadcastIntent.putExtra("voice_text", text)
                                    broadcastIntent.setPackage(context.packageName)

                                    context.sendOrderedBroadcast(
                                        broadcastIntent,
                                        null,
                                        object : android.content.BroadcastReceiver() {
                                            override fun onReceive(ctx: Context?, intent: Intent?) {
                                                if (resultCode != android.app.Activity.RESULT_OK) {
                                                    try {
                                                        val activityIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                                        if (activityIntent != null) {
                                                            activityIntent.action = "ACTION_VOICE_SEND"
                                                            activityIntent.putExtra("voice_text", text)
                                                            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                            controllerScope.launch {
                                                                forceDismiss()
                                                                context.startActivity(activityIntent)
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            }
                                        },
                                        null,
                                        android.app.Activity.RESULT_CANCELED,
                                        null,
                                        null
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            onDrag = { x: Float, y: Float ->
                                val view = floatView
                                if (view != null) {
                                    val screenWidth = DisplayUtils.getScreenWidth(context)
                                    val screenHeight = DisplayUtils.getScreenHeight(context)
                                    val maxX = (screenWidth - view.width).coerceAtLeast(0)
                                    val maxY = (screenHeight - view.height).coerceAtLeast(0)
                                    windowParams.x = (windowParams.x + x.roundToInt()).coerceIn(0, maxX)
                                    windowParams.y = (windowParams.y - y.roundToInt()).coerceIn(0, maxY)
                                    floatingWindowManager.updateWindowLayout(view, windowParams)
                                }
                            }
                        )
                    }

                    if (floatingWindowManager.addWindow(floatView!!, windowParams)) {
                        // isShowing will be true after state transition
                    }

                    _stateFlow.value = newState

                    // Wait for layout if callback provided
                    if (onComplete != null) {
                        val view = floatView
                        if (view != null) {
                            view.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                                override fun onGlobalLayout() {
                                    view.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                                    onComplete.invoke()
                                }
                            })
                        } else {
                            onComplete.invoke()
                        }
                    }
                } else if (oldState is FloatingWindowState.TemporarilyHidden) {
                    val tRestore = System.currentTimeMillis()
                    val hiddenDurationMs = if (lastSuspendTimeMs > 0) tRestore - lastSuspendTimeMs else -1
                    val opDurationMs = if (operationStartTimeMs > 0) tRestore - operationStartTimeMs else -1
                    Log.i("FloatingWindow", "Window restored: 体感隐藏=${hiddenDurationMs}ms | operation耗时≈${opDurationMs}ms | suspend=$lastSuspendTimeMs restore=$tRestore")
                    floatingWindowManager.restoreWindow(floatView, windowParams)
                    _stateFlow.value = newState
                    onComplete?.invoke()
                } else {
                    // Just update state (already visible)
                    _stateFlow.value = newState
                    onComplete?.invoke()
                }
            }

            is FloatingWindowState.TemporarilyHidden -> {
                // Hide window for screenshots/gestures (size 0x0, not touchable)
                lastSuspendTimeMs = System.currentTimeMillis()
                if (isShowing && floatView != null) {
                    floatingWindowManager.suspendWindow(floatView, windowParams)
                }
                Log.d("FloatingWindow", "setState TemporarilyHidden: suspendWindow done, window now GONE (t0=$lastSuspendTimeMs)")
                _stateFlow.value = newState

                // 1 frame 即可（与无障碍模式一致，快速恢复）
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback {
                    Log.d("FloatingWindow", "setState TemporarilyHidden: postFrameCallback fired, onComplete invoked")
                    onComplete?.invoke()
                }
            }

            is FloatingWindowState.RecordingOverlayShown -> {
                // Show recording overlay (not focusable)
                showOverlayView(false) { RecordingOverlayContent() }
                _stateFlow.value = newState
                onComplete?.invoke()
            }

            is FloatingWindowState.ReviewOverlayShown -> {
                // Show review overlay (focusable) with review content
                val reviewState = newState as FloatingWindowState.ReviewOverlayShown
                showOverlayView(true) {
                    VoiceReviewOverlayContent(
                        text = reviewState.text,
                        onTextChange = reviewState.onTextChange,
                        onSend = reviewState.onSend,
                        onCancel = reviewState.onCancel
                    )
                }
                _stateFlow.value = newState
                onComplete?.invoke()
            }

            is FloatingWindowState.TaskCompleted -> {
                // Task completed - keep window visible but mark state
                _stateFlow.value = newState
                onComplete?.invoke()
            }
        }
    }
    }

    /**
     * Shows the floating window and waits for layout to complete.
     * This is more reliable than blind delay for ensuring window is ready for operations like screenshot.
     *
     * @param onStop Callback when stop button is clicked
     * @param isRunning Whether the task is currently running (affects UI display)
     * @param onPauseResume Callback when pause/resume button is clicked (null to hide the button)
     */
    suspend fun showAndWaitForLayout(onStop: () -> Unit, isRunning: Boolean = true, onPauseResume: (() -> Unit)? = null) {
        val currentState = _stateFlow.value

        if (currentState is FloatingWindowState.Visible) {
            // Already showing - update using unified method
            updateVisibleState(
                isTaskRunning = isRunning,
                onStopCallback = onStop,
                onPauseResumeCallback = onPauseResume,
                reason = "showAndWait"
            )
            // Already visible, no need to wait for layout
            return
        }

        // Transition to Visible state and wait for layout
        val defaultStatus = context.getString(R.string.fw_ready)
        val layoutComplete = CompletableDeferred<Unit>()
        setState(FloatingWindowState.Visible(defaultStatus, isRunning, onStop, false, onPauseResume)) {
            layoutComplete.complete(Unit)
        }
        layoutComplete.await()
        Log.d("FloatingWindow", "showAndWaitForLayout: Window layout completed")
    }

    /**
     * Unified method to update Visible state.
     * This is the single entry point for all internal Visible state updates.
     * Now goes through setState() for proper validation.
     *
     * @param statusText New status text (null to keep current)
     * @param isTaskRunning New task running state (null to keep current)
     * @param onStopCallback New stop callback (null to keep current)
     * @param reason Calling context for logging (e.g., "updateStatus", "setTaskRunning")
     */
    private suspend fun updateVisibleState(
        statusText: String? = null,
        isTaskRunning: Boolean? = null,
        onStopCallback: (() -> Unit)? = null,
        isPaused: Boolean? = null,
        onPauseResumeCallback: (() -> Unit)? = null,
        taskList: List<String>? = null,
        thinkingLines: List<String>? = null,
        actionContent: com.sidhu.androidautoglm.ui.model.FormattedContent.ActionContent? = null,
        reason: String
    ) {
        val currentState = _stateFlow.value

        // Build new state with updated values
        val oldVisible = currentState as? FloatingWindowState.Visible
        val newStatusText = statusText ?: (oldVisible?.statusText ?: "")
        val newIsTaskRunning = isTaskRunning ?: (oldVisible?.isTaskRunning ?: true)
        val newCallback = onStopCallback ?: oldVisible?.onStopCallback
        val newIsPaused = isPaused ?: (oldVisible?.isPaused ?: false)
        val newPauseResumeCallback = onPauseResumeCallback ?: oldVisible?.onPauseResumeCallback
        val newTaskList = taskList ?: (oldVisible?.taskList ?: emptyList())
        val newThinkingLines = thinkingLines ?: (oldVisible?.thinkingLines ?: emptyList())
        val newActionContent = when {
            actionContent != null -> actionContent
            // 更新 thinking 时保留 actionContent，以支持 DisplayFormattedContent 同时显示文字和动作卡片
            else -> oldVisible?.actionContent
        }

        Log.d("FloatingWindow", "updateVisibleState [$reason]: status=\"$newStatusText\", taskList=${newTaskList.size}, actionContent=${newActionContent != null}, isPaused=$newIsPaused")

        val newState = FloatingWindowState.Visible(
            statusText = newStatusText,
            isTaskRunning = newIsTaskRunning,
            onStopCallback = newCallback,
            isPaused = newIsPaused,
            onPauseResumeCallback = newPauseResumeCallback,
            taskList = newTaskList,
            thinkingLines = newThinkingLines,
            actionContent = newActionContent
        )
        setState(newState)
    }

    /** 设置暂停状态（由 ActionManager 转发，用于悬浮窗按钮显示） */
    fun setPaused(paused: Boolean) {
        controllerScope.launch {
            updateVisibleState(isPaused = paused, reason = "setPaused")
        }
    }

    fun updateStatus(status: String) {
        controllerScope.launch {
            updateVisibleState(statusText = status, reason = "updateStatus")
        }
    }

    /** 更新任务清单（步骤列表） */
    fun updateTaskList(list: List<String>) {
        controllerScope.launch {
            updateVisibleState(taskList = list, reason = "updateTaskList")
        }
    }

    /** 更新思考过程，清理标签后保留所有行用于上下滚动 */
    fun updateThinking(text: String) {
        controllerScope.launch {
            val cleaned = text
                .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<task_plan>[\\s\\S]*?</task_plan>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("</?task_plan>", RegexOption.IGNORE_CASE), "")
            val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
            updateVisibleState(thinkingLines = lines, actionContent = null, reason = "updateThinking")
        }
    }

    /** 更新思考区域为 DisplayActionCard 内容（优先于 thinkingLines） */
    fun updateActionContent(actionContent: com.sidhu.androidautoglm.ui.model.FormattedContent.ActionContent?) {
        controllerScope.launch {
            updateVisibleState(actionContent = actionContent, reason = "updateActionContent")
        }
    }

    fun setTaskRunning(running: Boolean) {
        controllerScope.launch {
            updateVisibleState(isTaskRunning = running, reason = "setTaskRunning")
        }
    }

    /**
     * Sets temporarily hidden mode for the floating window.
     * Used during screenshots and gesture operations to temporarily hide the window.
     *
     * @param isHidden True to hide window, false to show
     * @param onComplete Optional callback invoked when layout is complete (if provided, waits for layout)
     */
    /** @param onComplete 回调，参数 didActuallySuspend：本次是否真正执行了 suspend（嵌套调用时已 hidden 则为 false） */
    fun setTemporarilyHidden(isHidden: Boolean, onComplete: ((didActuallySuspend: Boolean) -> Unit)? = null) {
        controllerScope.launch {
            if (!isShowing || floatView == null) {
                onComplete?.invoke(false)
                return@launch
            }

            val currentState = _stateFlow.value

            if (isHidden && currentState is FloatingWindowState.Visible) {
                // Enter TemporarilyHidden - cache current Visible state
                Log.d("FloatingWindow", "setTemporarilyHidden: Entering TemporarilyHidden mode")
                setState(
                    FloatingWindowState.TemporarilyHidden(
                        cachedStatusText = currentState.statusText,
                        cachedIsTaskRunning = currentState.isTaskRunning,
                        cachedOnStopCallback = currentState.onStopCallback,
                        cachedIsPaused = currentState.isPaused,
                        cachedOnPauseResumeCallback = currentState.onPauseResumeCallback,
                        cachedTaskList = currentState.taskList,
                        cachedThinkingLines = currentState.thinkingLines,
                        cachedActionContent = currentState.actionContent
                    )
                ) { onComplete?.invoke(true) }
            } else if (!isHidden && currentState is FloatingWindowState.TemporarilyHidden) {
                Log.d("FloatingWindow", "setTemporarilyHidden: Restoring from TemporarilyHidden to Visible")
                setState(
                    FloatingWindowState.Visible(
                        statusText = currentState.cachedStatusText,
                        isTaskRunning = currentState.cachedIsTaskRunning,
                        onStopCallback = currentState.cachedOnStopCallback,
                        isPaused = currentState.cachedIsPaused,
                        onPauseResumeCallback = currentState.cachedOnPauseResumeCallback,
                        taskList = currentState.cachedTaskList,
                        thinkingLines = currentState.cachedThinkingLines,
                        actionContent = currentState.cachedActionContent
                    )
                ) { onComplete?.invoke(true) }
            } else {
                Log.d("FloatingWindow", "setTemporarilyHidden: already hidden (nested), skip delay")
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Helper function for the common pattern of suspending window visibility during an operation.
     * The window is hidden before the operation and restored after completion.
     * If the operation throws, the window is still restored.
     *
     * @param operation The suspend operation to perform while window is hidden
     * @return The result of the operation
     */
    /**
     * 在后台执行 operation，避免 Shizuku executeShellCommand 等阻塞调用导致主线程 ANR。
     * setTemporarilyHidden 的 UI 更新仍在 Main，仅 operation 在 Default 上执行。
     */
    suspend fun <T> useWindowSuspension(operation: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        val tRequest = System.currentTimeMillis()
        Log.d("FloatingWindow", "useWindowSuspension: requesting hide (tRequest=$tRequest)...")
        setTemporarilyHidden(true) { didActuallySuspend ->
            val tAfterSuspend = System.currentTimeMillis()
            Log.d("FloatingWindow", "useWindowSuspension: window hidden, didActuallySuspend=$didActuallySuspend, 请求耗时=${tAfterSuspend - tRequest}ms")
            controllerScope.launch {
                try {
                    // 仅首次 suspend 时等待窗口完全移除；嵌套调用（已 hidden）则跳过
                    if (didActuallySuspend) {
                        delay(30)  // 1 frame(~16ms) + 少量缓冲，缩短悬浮窗隐藏时长
                        Log.d("FloatingWindow", "useWindowSuspension: delay(30) done")
                    }
                    operationStartTimeMs = System.currentTimeMillis()
                    val opResult = withContext(Dispatchers.Default) { operation() }
                    Log.d("FloatingWindow", "useWindowSuspension: operation done, restoring")
                    // Restore window after operation completes (on Main)
                    setTemporarilyHidden(false) { _ -> result.complete(opResult) }
                } catch (e: Throwable) {
                    setTemporarilyHidden(false) { _ -> result.completeExceptionally(e) }
                }
            }
        }
        return result.await()
    }

    fun isOccupyingSpace(x: Float, y: Float): Boolean {
        // Only occupy space if window is actually visible (not TemporarilyHidden)
        if (_stateFlow.value !is FloatingWindowState.Visible) return false
        if (!isShowing || floatView == null || floatView?.visibility != android.view.View.VISIBLE) return false

        val location = IntArray(2)
        floatView?.getLocationOnScreen(location)
        val viewX = location[0]
        val viewY = location[1]
        val width = floatView?.width ?: 0
        val height = floatView?.height ?: 0

        return x >= viewX && x <= (viewX + width) && y >= viewY && y <= (viewY + height)
    }

    /** 暂不移动悬浮窗位置，保持初始位置 */
    @Suppress("UNUSED_PARAMETER")
    fun avoidArea(targetX: Float, targetY: Float) {
        // No-op: 不改变位置
    }

    /** 暂不移动悬浮窗位置 */
    fun moveWindowToTop() {
        // No-op: 不改变位置
    }
    


    /**
     * Removes the floating window from WindowManager and hides it.
     * This is a permanent hide - the window is completely removed.
     * Use setTemporarilyHidden() for temporary hiding during screenshots/gestures.
     */
    fun removeAndHide() {
        // Launch in controllerScope since setState is a suspend function
        controllerScope.launch {
            if (!isShowing) return@launch

            Log.d("FloatingWindow", "removeAndHide() called", Exception("Stack trace"))
            setState(FloatingWindowState.Hidden)
        }
    }

    /**
     * Dismisses the floating window and transitions to Hidden state.
     * This should be called when user explicitly dismisses the window (e.g., via Stop button).
     * This suspend function waits for the window to be fully removed before returning.
     */
    suspend fun dismiss() = withContext(Dispatchers.Main) {
        Log.d("FloatingWindow", "dismiss() called")
        val completed = CompletableDeferred<Unit>()
        setState(FloatingWindowState.Hidden) {
            completed.complete(Unit)
        }
        completed.await()
    }

    suspend fun forceDismiss() = stateMutex.withLock {
        withContext(Dispatchers.Main) {
            hideOverlayView()
            if (isShowing && floatView != null) {
                floatingWindowManager.removeWindow(floatView)
                floatView = null
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            _stateFlow.value = FloatingWindowState.Hidden
        }
    }

    override val lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
}

/**
 * Voice overlay content composables.
 * These are internal wrappers for the state-based voice overlay system.
 */

@Composable
private fun RecordingOverlayContent() {
    // Placeholder for recording overlay
    // The actual implementation will be managed by FloatingWindowContent
    // which has access to SpeechRecognizerManager
    RecordingIndicator(soundLevel = 0f)
}

@Composable
private fun VoiceReviewOverlayContent(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    // Delegate to the existing VoiceReviewOverlay component
    VoiceReviewOverlay(
        text = text,
        onTextChange = onTextChange,
        onCancel = onCancel,
        onSend = onSend
    )
}
