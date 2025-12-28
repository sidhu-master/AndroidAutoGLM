package com.sidhu.androidautoglm

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.OpenInNew
import android.content.Intent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.sidhu.androidautoglm.utils.SpeechRecognizerManager
import com.sidhu.androidautoglm.utils.SherpaModelManager
import com.sidhu.androidautoglm.ui.RecordingIndicator
import com.sidhu.androidautoglm.ui.VoiceReviewOverlay
import android.os.VibrationEffect
import android.os.Looper
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast

/**
 * Sealed class hierarchy representing the floating window state machine.
 * This provides type-safe state management and makes all states explicit.
 */
sealed class FloatingWindowState {
    /** Window is not attached to WindowManager */
    data object Hidden : FloatingWindowState()

    /** Window is visible and interactive on screen */
    data class Visible(
        val statusText: String,
        val isTaskRunning: Boolean,
        val onStopCallback: (() -> Unit)?
    ) : FloatingWindowState()

    /** Window is temporarily hidden for screenshots/gestures (size 0x0, not touchable) */
    data object ScreenshotMode : FloatingWindowState()

    /** Full-screen overlay is shown (voice recording/review) */
    data class OverlayShown(val focusable: Boolean) : FloatingWindowState()

    /** User explicitly dismissed the window via "Return to App" button */
    data object Dismissed : FloatingWindowState()
}

class FloatingWindowController(private val context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatView: ComposeView? = null
    private var isShowing = false
    private lateinit var windowParams: WindowManager.LayoutParams
    
    // State for the UI
    private var _statusText by mutableStateOf("")
    private var _isTaskRunning by mutableStateOf(true)
    private var _onStopClick: (() -> Unit)? = null

    // Tracks whether user has returned to app via floating window after task completion
    // When true, prevents auto-showing the window on app background
    private var userDismissed = false
    
    // Lifecycle components required for Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    // State machine for floating window visibility and interaction
    private val _stateFlow = MutableStateFlow<FloatingWindowState>(FloatingWindowState.Hidden)
    /** Public read-only state flow for observing floating window state changes */
    val stateFlow: StateFlow<FloatingWindowState> = _stateFlow.asStateFlow()

    // Coroutine scope for managing async operations
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        _statusText = context.getString(R.string.fw_ready)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Log.d("FloatingWindow", "Initial state: Hidden")
    }

    private var overlayView: ComposeView? = null

    fun showOverlay(focusable: Boolean = false, content: @Composable () -> Unit) {
        if (overlayView != null) hideOverlay()

        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
             WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

        overlayView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowController)
            setViewTreeViewModelStoreOwner(this@FloatingWindowController)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowController)
            setContent {
                MaterialTheme {
                     content()
                }
            }
        }

        try {
            windowManager.addView(overlayView, overlayParams)
            _stateFlow.value = FloatingWindowState.OverlayShown(focusable)
            Log.d("FloatingWindow", "Overlay shown (focusable=$focusable)")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hideOverlay() {
        if (overlayView == null) return
        try {
            windowManager.removeView(overlayView)
            overlayView = null
            // Restore previous state (likely Visible if we were showing an overlay during a task)
            if (isShowing) {
                _stateFlow.value = FloatingWindowState.Visible(_statusText, _isTaskRunning, _onStopClick)
                Log.d("FloatingWindow", "Overlay hidden, restored to Visible state")
            } else {
                _stateFlow.value = FloatingWindowState.Hidden
                Log.d("FloatingWindow", "Overlay hidden, state is Hidden")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resets the dismissed state for a new task.
     * Should be called when a new task starts.
     */
    fun resetForNewTask() {
        userDismissed = false
        Log.d("FloatingWindow", "Reset for new task, userDismissed=false")
    }

    /**
     * Dismisses the floating window and marks it as user-dismissed.
     * This prevents the window from auto-showing on app background until resetForNewTask() is called.
     */
    fun dismiss() {
        controllerScope.launch {
            Log.d("FloatingWindow", "dismiss() called")
            setState(FloatingWindowState.Dismissed)
        }
    }

    /**
     * Core state machine method for managing floating window state transitions.
     * This is the preferred method for all state changes going forward.
     *
     * @param newState The target state to transition to
     * @param onComplete Optional callback invoked after the transition is complete (on main thread)
     */
    suspend fun setState(
        newState: FloatingWindowState,
        onComplete: (() -> Unit)? = null
    ) = withContext(Dispatchers.Main) {
        val oldState = _stateFlow.value
        Log.d("FloatingWindow", "State transition: $oldState -> $newState")

        when (newState) {
            is FloatingWindowState.Hidden -> {
                // Remove window from WindowManager
                if (isShowing && floatView != null) {
                    try {
                        windowManager.removeView(floatView)
                        isShowing = false
                        floatView = null
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    } catch (e: Exception) {
                        Log.e("FloatingWindow", "Error hiding window", e)
                    }
                }
                _stateFlow.value = newState
                android.os.Handler(Looper.getMainLooper()).post { onComplete?.invoke() }
            }

            is FloatingWindowState.Visible -> {
                // Check if user has dismissed the window
                if (newState != oldState && userDismissed) {
                    Log.d("FloatingWindow", "setState(Visible) called but user has dismissed, skipping")
                    _stateFlow.value = FloatingWindowState.Dismissed
                    android.os.Handler(Looper.getMainLooper()).post { onComplete?.invoke() }
                    return@withContext
                }

                // Update state variables (for backward compatibility with existing UI code)
                _statusText = newState.statusText
                _isTaskRunning = newState.isTaskRunning
                _onStopClick = newState.onStopCallback

                if (!isShowing) {
                    // Create and add the window
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

                    windowParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )

                    windowParams.gravity = Gravity.BOTTOM or Gravity.START
                    windowParams.x = 0
                    windowParams.y = 20

                    floatView = ComposeView(context).apply {
                        setViewTreeLifecycleOwner(this@FloatingWindowController)
                        setViewTreeViewModelStoreOwner(this@FloatingWindowController)
                        setViewTreeSavedStateRegistryOwner(this@FloatingWindowController)

                        setContent {
                            FloatingWindowContent(
                                status = _statusText,
                                isTaskRunning = _isTaskRunning,
                                onAction = {
                                    if (_isTaskRunning) {
                                        _onStopClick?.invoke()
                                    } else {
                                        // Launch App and dismiss floating window
                                        try {
                                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                            if (intent != null) {
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                AutoGLMService.getInstance()?.dismissFloatingWindow()
                                            } else {
                                                Log.e("FloatingWindow", "Launch intent not found")
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
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
                                                                controllerScope.launch { hide() }
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
                                    try {
                                        windowManager.updateViewLayout(floatView, windowParams)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            )
                        }
                    }

                    try {
                        windowManager.addView(floatView, windowParams)
                        isShowing = true
                    } catch (e: Exception) {
                        Log.e("FloatingWindow", "Error showing window", e)
                    }
                } else if (oldState is FloatingWindowState.ScreenshotMode) {
                    // Restoring from ScreenshotMode - make visible again
                    try {
                        floatView?.visibility = android.view.View.VISIBLE
                        windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        windowParams.width = WindowManager.LayoutParams.WRAP_CONTENT
                        windowParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                        windowManager.updateViewLayout(floatView, windowParams)
                    } catch (e: Exception) {
                        Log.e("FloatingWindow", "Error restoring from ScreenshotMode", e)
                    }
                }

                _stateFlow.value = newState

                // Wait for layout if callback provided
                if (onComplete != null) {
                    val view = floatView
                    if (view != null) {
                        view.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                view.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                                android.os.Handler(Looper.getMainLooper()).post { onComplete.invoke() }
                            }
                        })
                    } else {
                        android.os.Handler(Looper.getMainLooper()).post { onComplete.invoke() }
                    }
                } else {
                    android.os.Handler(Looper.getMainLooper()).post { onComplete?.invoke() }
                }
            }

            is FloatingWindowState.ScreenshotMode -> {
                // Hide window for screenshots (size 0x0, not touchable)
                if (isShowing && floatView != null) {
                    try {
                        floatView?.visibility = android.view.View.GONE
                        windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        windowParams.width = 0
                        windowParams.height = 0
                        windowManager.updateViewLayout(floatView, windowParams)
                    } catch (e: Exception) {
                        Log.e("FloatingWindow", "Error entering ScreenshotMode", e)
                    }
                }
                _stateFlow.value = newState
                android.os.Handler(Looper.getMainLooper()).post { onComplete?.invoke() }
            }

            is FloatingWindowState.Dismissed -> {
                // User explicitly dismissed - same as Hidden but prevents auto-show
                userDismissed = true
                if (isShowing && floatView != null) {
                    try {
                        windowManager.removeView(floatView)
                        isShowing = false
                        floatView = null
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    } catch (e: Exception) {
                        Log.e("FloatingWindow", "Error dismissing window", e)
                    }
                }
                _stateFlow.value = newState
                android.os.Handler(Looper.getMainLooper()).post { onComplete?.invoke() }
            }

            is FloatingWindowState.OverlayShown -> {
                // Overlay state is managed separately by showOverlay/hideOverlay
                // Just update the stateFlow for observability
                _stateFlow.value = newState
                android.os.Handler(Looper.getMainLooper()).post { onComplete?.invoke() }
            }
        }
    }

    /**
     * Shows the floating window with the specified task running state.
     * @param onStop Callback when stop button is clicked
     * @param isRunning Whether the task is currently running (affects UI display)
     */
    fun show(onStop: () -> Unit, isRunning: Boolean = true) {
        // Launch in controllerScope since setState is a suspend function
        controllerScope.launch {
            if (userDismissed) {
                Log.d("FloatingWindow", "show() called but user has dismissed, skipping")
                return@launch
            }

            val currentState = _stateFlow.value
            if (currentState is FloatingWindowState.Visible) {
                // Already showing - update the callback and state if different
                _onStopClick = onStop
                if (_isTaskRunning != isRunning) {
                    Log.d("FloatingWindow", "Updating task running state from $_isTaskRunning to $isRunning")
                    _isTaskRunning = isRunning
                    // Update stateFlow
                    _stateFlow.value = currentState.copy(
                        statusText = _statusText,
                        isTaskRunning = isRunning,
                        onStopCallback = onStop
                    )
                }
                return@launch
            }

            // Transition to Visible state
            setState(FloatingWindowState.Visible(_statusText, isRunning, onStop))
        }
    }

    fun updateStatus(status: String) {
        _statusText = status
        // Also update stateFlow if currently Visible
        val currentState = _stateFlow.value
        if (currentState is FloatingWindowState.Visible) {
            _stateFlow.value = currentState.copy(statusText = status)
        }
    }

    fun setTaskRunning(running: Boolean) {
        _isTaskRunning = running
        // Also update stateFlow if currently Visible
        val currentState = _stateFlow.value
        if (currentState is FloatingWindowState.Visible) {
            _stateFlow.value = currentState.copy(isTaskRunning = running)
        }
    }

    /**
     * Sets screenshot mode for the floating window.
     *
     * @param isScreenshotting True to hide window, false to show
     * @param onComplete Optional callback invoked when layout is complete (if provided, waits for layout)
     */
    fun setScreenshotMode(isScreenshotting: Boolean, onComplete: (() -> Unit)? = null) {
        // Launch in controllerScope since setState is a suspend function
        controllerScope.launch {
            if (!isShowing || floatView == null) {
                onComplete?.invoke()
                return@launch
            }

            if (isScreenshotting) {
                // Enter ScreenshotMode
                setState(FloatingWindowState.ScreenshotMode, onComplete)
            } else {
                // Restore to Visible state - need current status text and callback
                setState(FloatingWindowState.Visible(_statusText, _isTaskRunning, _onStopClick), onComplete)
            }
        }
    }

    fun isOccupyingSpace(x: Float, y: Float): Boolean {
        // Only occupy space if window is actually visible (not in ScreenshotMode or Dismissed)
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

        val metrics = context.resources.displayMetrics
        val screenHeight = metrics.heightPixels

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
            try {
                windowManager.updateViewLayout(floatView, windowParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hide() {
        // Launch in controllerScope since setState is a suspend function
        controllerScope.launch {
            if (!isShowing) return@launch

            Log.d("FloatingWindow", "hide() called", Exception("Stack trace"))
            setState(FloatingWindowState.Hidden)
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
}

@Composable
fun FloatingWindowContent(
    status: String,
    isTaskRunning: Boolean,
    onAction: () -> Unit,
    onShowOverlay: (Boolean, @Composable () -> Unit) -> Unit,
    onHideOverlay: () -> Unit,
    onSendVoice: (String) -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Voice State
    var voiceResultText by remember { mutableStateOf("") }
    var showVoiceReview by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    
    // Speech Recognition
    val speechRecognizerManager = remember { SpeechRecognizerManager(context) }
    val isListening by speechRecognizerManager.isListening.collectAsState()
    val soundLevel by speechRecognizerManager.soundLevel.collectAsState()
    
    val modelState by SherpaModelManager.modelState.collectAsState()
    val isModelReady = modelState is SherpaModelManager.ModelState.Ready
    
    // Ensure model is initialized
    LaunchedEffect(Unit) {
         if (modelState is SherpaModelManager.ModelState.NotInitialized) {
            SherpaModelManager.initModel(context)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizerManager.destroy()
        }
    }

    // Effect to manage overlay based on state
    LaunchedEffect(isListening, showVoiceReview) {
        if (showVoiceReview) {
            onShowOverlay(true) { // Focusable
                 VoiceReviewOverlay(
                    text = voiceResultText,
                    onTextChange = { voiceResultText = it },
                    onCancel = {
                        showVoiceReview = false
                        voiceResultText = ""
                        onHideOverlay()
                    },
                    onSend = {
                        if (voiceResultText.isNotBlank()) {
                            onSendVoice(voiceResultText)
                        }
                        showVoiceReview = false
                        voiceResultText = ""
                        onHideOverlay()
                    }
                )
            }
        } else if (isListening) {
            onShowOverlay(false) { // Not focusable, but full screen for visual
                 RecordingIndicator(soundLevel = soundLevel)
            }
        } else {
            // If neither listening nor reviewing, hide overlay
            // But be careful not to hide if we are just transitioning
            // Logic: if both false, hide.
            onHideOverlay()
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .width(350.dp) // Fixed width for consistent dragging
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val isError = status.startsWith("Error") || status.startsWith("出错") || status.startsWith("运行异常")
                    val titleText = when {
                        isTaskRunning -> stringResource(R.string.fw_running)
                        isError -> stringResource(R.string.fw_error_title)
                        else -> stringResource(R.string.fw_ready_title)
                    }
                    val titleColor = when {
                        isTaskRunning -> Color.Gray
                        isError -> MaterialTheme.colorScheme.error
                        else -> Color(0xFF4CAF50)
                    }

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelSmall,
                        color = titleColor
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                }
                
                // Right side action button
                if (isTaskRunning) {
                     Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFEBEE),
                            contentColor = Color.Red
                        ),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop, 
                            contentDescription = null, 
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.fw_stop))
                    }
                } else {
                    // Not running: Show Mic Button AND Return Button (maybe?)
                    // Or just Mic button and if clicked -> text input?
                    // User requested "Send new task via voice".
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Mic Button (Hold to talk)
                         val vibrator = remember {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                                vibratorManager.defaultVibrator
                            } else {
                                @Suppress("DEPRECATION")
                                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFE3F2FD), androidx.compose.foundation.shape.CircleShape)
                                .pointerInput(isModelReady, modelState) {
                                     if (!isModelReady || modelState is SherpaModelManager.ModelState.Error || modelState is SherpaModelManager.ModelState.NotInitialized) {
                                        detectTapGestures(
                                            onTap = {
                                                if (modelState is SherpaModelManager.ModelState.NotInitialized || modelState is SherpaModelManager.ModelState.Error) {
                                                    Toast.makeText(context, "Initializing Voice Model...", Toast.LENGTH_SHORT).show()
                                                    scope.launch {
                                                        SherpaModelManager.initModel(context)
                                                    }
                                                } else if (modelState is SherpaModelManager.ModelState.Loading) {
                                                    Toast.makeText(context, "Voice Model Loading...", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        return@pointerInput
                                    }
                                    
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        
                                        // Check permission
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            Toast.makeText(context, "Requesting Microphone Permission...", Toast.LENGTH_SHORT).show()
                                            try {
                                                val intent = Intent(context, com.sidhu.androidautoglm.MainActivity::class.java).apply {
                                                    action = "ACTION_REQUEST_MIC_PERMISSION"
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                Toast.makeText(context, "Failed to launch app for permission", Toast.LENGTH_SHORT).show()
                                            }
                                            return@awaitEachGesture
                                        }

                                        // Start Listening
                                        val startJob = scope.launch(Dispatchers.Main) {
                                            voiceResultText = ""
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                                            } else {
                                                @Suppress("DEPRECATION")
                                                vibrator.vibrate(50)
                                            }
                                            speechRecognizerManager.startListening(
                                                onResultCallback = { result -> voiceResultText = result },
                                                onErrorCallback = { error -> 
                                                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }

                                        isCancelling = false
                                        var cancelled = false
                                        
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                if (change == null || !change.pressed) break
                                                
                                                val threshold = 50.dp.toPx()
                                                // If dragging up, cancel
                                                // Note: Window Y decreases as we go up.
                                                // change.position is relative to the element.
                                                // Dragging UP means negative Y.
                                                if (change.position.y < -threshold) {
                                                    if (!isCancelling) isCancelling = true
                                                } else {
                                                    if (isCancelling) isCancelling = false
                                                }
                                            }
                                        } catch (e: Exception) {
                                            cancelled = true
                                        }
                                        
                                        // Stopped Listening
                                        scope.launch(Dispatchers.Main) {
                                            startJob.join()
                                            if (cancelled || isCancelling) {
                                                speechRecognizerManager.cancel()
                                            } else {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    vibrator.vibrate(50)
                                                }
                                                speechRecognizerManager.stopListening()
                                                if (voiceResultText.isNotBlank()) {
                                                    showVoiceReview = true
                                                }
                                            }
                                            isCancelling = false
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                             Icon(
                                Icons.Default.Mic, 
                                contentDescription = null, 
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Return Button
                        Button(
                            onClick = onAction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE3F2FD),
                                contentColor = Color(0xFF2196F3)
                            ),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInNew, 
                                contentDescription = null, 
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.fw_return_app))
                        }
                    }
                }
            }
        }
    }
}

