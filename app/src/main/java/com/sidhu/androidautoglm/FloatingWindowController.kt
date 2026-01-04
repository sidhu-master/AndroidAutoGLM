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
        val onStopCallback: (() -> Unit)? = null
    ) : FloatingWindowState()

    /** Task has completed naturally (not user cancelled) */
    data class TaskCompleted(
        val statusText: String
    ) : FloatingWindowState()

    /**
     * Window is temporarily hidden for screenshots/gestures (size 0x0, not touchable)
     * Explicitly caches the Visible state data for restoration.
     */
    data class TemporarilyHidden(
        val cachedStatusText: String,
        val cachedIsTaskRunning: Boolean,
        val cachedOnStopCallback: (() -> Unit)?
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

            // Handle TaskCompleted state by first transitioning to Hidden
            if (_stateFlow.value is FloatingWindowState.TaskCompleted) {
                setState(FloatingWindowState.Hidden)
            }

            // Now transition to Visible state with task running
            setState(FloatingWindowState.Visible(defaultStatus, true))
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
            val currentStatus = if (currentState is FloatingWindowState.Visible) {
                currentState.statusText
            } else {
                context.getString(R.string.fw_ready)
            }
            setState(FloatingWindowState.TaskCompleted(currentStatus))
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
                                                            context.startActivity(activityIntent)
                                                            controllerScope.launch { removeAndHide() }
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
                                windowParams.x += x.roundToInt()
                                windowParams.y -= y.roundToInt()
                                floatingWindowManager.updateWindowLayout(floatView, windowParams)
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
                    // Restoring from TemporarilyHidden - make visible again
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
                if (isShowing && floatView != null) {
                    floatingWindowManager.suspendWindow(floatView, windowParams)
                }
                _stateFlow.value = newState

                // Wait for 2 frames + 16ms delay to ensure window is fully removed from screen
                // This prevents the floating window from being captured in screenshots
                // Reference: https://github.com/sidhu-master/AndroidAutoGLM/pull/15
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback { _ ->
                    // First frame rendered
                    choreographer.postFrameCallback {
                        // Second frame rendered - add 16ms safety margin
                        Handler(Looper.getMainLooper()).postDelayed({
                            onComplete?.invoke()
                        }, 16)
                    }
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
     */
    suspend fun showAndWaitForLayout(onStop: () -> Unit, isRunning: Boolean = true) {
        val currentState = _stateFlow.value

        if (currentState is FloatingWindowState.Visible) {
            // Already showing - update using unified method
            updateVisibleState(
                isTaskRunning = isRunning,
                onStopCallback = onStop,
                reason = "showAndWait"
            )
            // Already visible, no need to wait for layout
            return
        }

        // Transition to Visible state and wait for layout
        val defaultStatus = context.getString(R.string.fw_ready)
        val layoutComplete = CompletableDeferred<Unit>()
        setState(FloatingWindowState.Visible(defaultStatus, isRunning, onStop)) {
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
        reason: String
    ) {
        val currentState = _stateFlow.value

        // Build new state with updated values
        val oldStatusText = (currentState as? FloatingWindowState.Visible)?.statusText ?: ""
        val oldIsTaskRunning = (currentState as? FloatingWindowState.Visible)?.isTaskRunning ?: true
        val oldCallback = (currentState as? FloatingWindowState.Visible)?.onStopCallback

        val newStatusText = statusText ?: oldStatusText
        val newIsTaskRunning = isTaskRunning ?: oldIsTaskRunning
        val newCallback = onStopCallback ?: oldCallback

        // Log the state transition
        Log.d("FloatingWindow", "updateVisibleState [$reason]: " +
            "status=\"$oldStatusText\"->\"$newStatusText\"${if (statusText != null) "" else " (unchanged)"}, " +
            "isTaskRunning=$oldIsTaskRunning->$newIsTaskRunning${if (isTaskRunning != null) "" else " (unchanged)"}")

        // Go through setState for validation (single point of synchronization)
        val newState = FloatingWindowState.Visible(
            statusText = newStatusText,
            isTaskRunning = newIsTaskRunning,
            onStopCallback = newCallback
        )
        setState(newState)
    }

    fun updateStatus(status: String) {
        controllerScope.launch {
            updateVisibleState(statusText = status, reason = "updateStatus")
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
    fun setTemporarilyHidden(isHidden: Boolean, onComplete: (() -> Unit)? = null) {
        // Launch in controllerScope since setState is a suspend function
        controllerScope.launch {
            if (!isShowing || floatView == null) {
                onComplete?.invoke()
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
                        cachedOnStopCallback = currentState.onStopCallback
                    ),
                    onComplete
                )
            } else if (!isHidden && currentState is FloatingWindowState.TemporarilyHidden) {
                // Restore to Visible state using cached values
                Log.d("FloatingWindow", "setTemporarilyHidden: Restoring from TemporarilyHidden to Visible")
                setState(
                    FloatingWindowState.Visible(
                        statusText = currentState.cachedStatusText,
                        isTaskRunning = currentState.cachedIsTaskRunning,
                        onStopCallback = currentState.cachedOnStopCallback
                    ),
                    onComplete
                )
            } else {
                onComplete?.invoke()
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
    suspend fun <T> useWindowSuspension(operation: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        setTemporarilyHidden(true) {
            // Window is now hidden, proceed with operation
            controllerScope.launch {
                try {
                    val opResult = operation()
                    // Restore window after operation completes
                    setTemporarilyHidden(false) {
                        result.complete(opResult)
                    }
                } catch (e: Throwable) {
                    // Restore window even on error
                    setTemporarilyHidden(false) {
                        result.completeExceptionally(e)
                    }
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

    fun avoidArea(targetX: Float, targetY: Float) {
        // Only avoid area if window is in Visible state
        if (_stateFlow.value !is FloatingWindowState.Visible) return
        if (!isOccupyingSpace(targetX, targetY)) return

        val screenHeight = DisplayUtils.getScreenHeight(context)

        // If target is in bottom half, move window to top. Else move to bottom.
        val targetInBottomHalf = targetY > screenHeight / 2

        val newY = if (targetInBottomHalf) {
            screenHeight - 300 // Top (distance from bottom)
        } else {
            20 // Bottom (distance from bottom)
        }

        // Only update if significantly different
        if (kotlin.math.abs(windowParams.y - newY) > 200) {
            windowParams.y = newY
            floatingWindowManager.updateWindowLayout(floatView, windowParams)
        }
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
