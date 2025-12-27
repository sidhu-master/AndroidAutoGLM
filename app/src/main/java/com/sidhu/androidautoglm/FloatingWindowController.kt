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
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast

class FloatingWindowController(private val context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatView: ComposeView? = null
    private var isShowing = false
    private lateinit var windowParams: WindowManager.LayoutParams
    
    // State for the UI
    private var _statusText by mutableStateOf("")
    private var _isTaskRunning by mutableStateOf(true)
    private var _onStopClick: (() -> Unit)? = null
    
    // Lifecycle components required for Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    init {
        _statusText = context.getString(R.string.fw_ready)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun hideOverlay() {
        if (overlayView == null) return
        try {
            windowManager.removeView(overlayView)
            overlayView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun show(onStop: () -> Unit) {
        if (isShowing) return
        _onStopClick = onStop
        _isTaskRunning = true
        
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val metrics = context.resources.displayMetrics
        val screenHeight = metrics.heightPixels

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
        windowParams.y = 20 // Initial position near bottom

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
                            // Do not hide immediately, wait for task to finish or user to click close
                        } else {
                            // Launch App
                            try {
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                    hide()
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
                            // Try to send broadcast first to avoid bringing Activity to front
                            val broadcastIntent = Intent("com.sidhu.androidautoglm.ACTION_VOICE_COMMAND_BROADCAST")
                            broadcastIntent.putExtra("voice_text", text)
                            broadcastIntent.setPackage(context.packageName)
                            
                            context.sendOrderedBroadcast(
                                broadcastIntent,
                                null,
                                object : android.content.BroadcastReceiver() {
                                    override fun onReceive(ctx: Context?, intent: Intent?) {
                                        if (resultCode != android.app.Activity.RESULT_OK) {
                                            // Fallback: Start Activity if broadcast was not handled (Activity dead)
                                            try {
                                                val activityIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                                if (activityIntent != null) {
                                                    activityIntent.action = "ACTION_VOICE_SEND"
                                                    activityIntent.putExtra("voice_text", text)
                                                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                    context.startActivity(activityIntent)
                                                    hide()
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
                    onDrag = { x, y ->
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
            e.printStackTrace()
        }
    }

    fun updateStatus(status: String) {
        _statusText = status
    }

    fun setTaskRunning(running: Boolean) {
        _isTaskRunning = running
    }

    /**
     * Sets screenshot mode for the floating window.
     *
     * @param isScreenshotting True to hide window, false to show
     * @param onComplete Optional callback invoked when layout is complete (if provided, waits for layout)
     */
    fun setScreenshotMode(isScreenshotting: Boolean, onComplete: (() -> Unit)? = null) {
        if (!isShowing || floatView == null) {
            onComplete?.invoke()
            return
        }

        try {
            floatView?.visibility = if (isScreenshotting) android.view.View.GONE else android.view.View.VISIBLE

            // Update flags to ensure touches pass through when hidden
            if (isScreenshotting) {
                windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowParams.width = 0
                windowParams.height = 0
            } else {
                windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowParams.width = WindowManager.LayoutParams.WRAP_CONTENT
                windowParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            windowManager.updateViewLayout(floatView, windowParams)

            // If callback provided, wait for layout to complete
            if (onComplete != null) {
                floatView?.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        // Remove listener to avoid multiple calls
                        floatView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                        // Post to end of queue to ensure frame is rendered
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onComplete.invoke()
                        }
                    }
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete?.invoke()
        }
    }

    fun isOccupyingSpace(x: Float, y: Float): Boolean {
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
        if (!isShowing) return
        
        Log.d("FloatingWindow", "hide() called", Exception("Stack trace"))

        try {
            windowManager.removeView(floatView)
            isShowing = false
            floatView = null
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        } catch (e: Exception) {
            e.printStackTrace()
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

