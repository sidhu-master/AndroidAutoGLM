package com.sidhu.androidautoglm.ui.floating

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.sidhu.androidautoglm.FloatingWindowController
import com.sidhu.androidautoglm.FloatingWindowState
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.utils.SpeechRecognizerManager
import com.sidhu.androidautoglm.utils.SherpaModelManager
import com.sidhu.androidautoglm.ui.RecordingIndicator
import com.sidhu.androidautoglm.ui.VoiceReviewOverlay
import kotlin.math.roundToInt

/**
 * Floating window content composable.
 * Displays the floating window UI with status, action buttons, and voice interaction.
 */
@Composable
fun FloatingWindowContent(
    floatingWindowController: FloatingWindowController,
    onShowOverlay: (Boolean, @Composable () -> Unit) -> Unit,
    onHideOverlay: () -> Unit,
    onSendVoice: (String) -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    // Reactively collect the floating window state
    val state by floatingWindowController.stateFlow.collectAsState()

    // Extract status, isTaskRunning, and onStopCallback from current state
    val status = when (val s = state) {
        is FloatingWindowState.Visible -> s.statusText
        else -> ""
    }
    val isTaskRunning = when (val s = state) {
        is FloatingWindowState.Visible -> s.isTaskRunning
        else -> false
    }
    val onStopCallback = when (val s = state) {
        is FloatingWindowState.Visible -> s.onStopCallback
        else -> null
    }
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
                    val isError = status.startsWith("Error") ||
                        status.startsWith("出错") ||
                        status.startsWith("运行异常") ||
                        status.startsWith("未输入正确的文本")
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
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }

                // Right side action button
                if (isTaskRunning || onStopCallback != null) {
                     Button(
                        onClick = {
                            // Stop button clicked - launch coroutine to properly sequence operations
                            scope.launch {
                                // 1. Call the stop callback to cancel the task
                                onStopCallback?.invoke()

                                // 2. Wait for floating window to be fully dismissed
                                // This ensures the window is removed before the app resumes
                                try {
                                    floatingWindowController.dismiss()
                                } catch (e: Exception) {
                                    Log.e("FloatingWindow", "Error dismissing window", e)
                                }

                                // 3. Launch the main app (after window is dismissed)
                                try {
                                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    if (intent != null) {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } else {
                                        Log.e("FloatingWindow", "Launch intent not found")
                                    }
                                } catch (e: Exception) {
                                    Log.e("FloatingWindow", "Error launching main app", e)
                                }
                            }
                        },
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
                                .background(Color(0xFFE3F2FD), CircleShape)
                                .pointerInput(isModelReady, modelState) {
                                     if (!isModelReady || modelState is SherpaModelManager.ModelState.Error || modelState is SherpaModelManager.ModelState.NotInitialized) {
                                        detectTapGestures(
                                            onTap = {
                                                if (modelState is SherpaModelManager.ModelState.NotInitialized || modelState is SherpaModelManager.ModelState.Error) {
                                                    Toast.makeText(context, context.getString(R.string.voice_model_initializing_toast), Toast.LENGTH_SHORT).show()
                                                    scope.launch {
                                                        SherpaModelManager.initModel(context)
                                                    }
                                                } else if (modelState is SherpaModelManager.ModelState.Loading) {
                                                    Toast.makeText(context, context.getString(R.string.voice_model_loading_toast), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        return@pointerInput
                                    }

                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)

                                        // Check permission
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            Toast.makeText(context, context.getString(R.string.requesting_microphone_permission_toast), Toast.LENGTH_SHORT).show()
                                            scope.launch {
                                                try {
                                                    floatingWindowController.forceDismiss()
                                                    val intent = Intent(context, com.sidhu.androidautoglm.MainActivity::class.java).apply {
                                                        action = "ACTION_REQUEST_MIC_PERMISSION"
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    Toast.makeText(context, context.getString(R.string.failed_launch_permission_toast), Toast.LENGTH_SHORT).show()
                                                }
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
                            onClick = {
                                scope.launch {
                                    try {
                                        floatingWindowController.forceDismiss()
                                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                        if (intent != null) {
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                            context.startActivity(intent)
                                        } else {
                                            Log.e("FloatingWindow", "Launch intent not found")
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
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
