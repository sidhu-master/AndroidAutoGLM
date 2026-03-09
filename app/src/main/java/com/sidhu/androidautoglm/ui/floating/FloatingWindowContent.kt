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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.sidhu.androidautoglm.ui.model.FormattedContent
import com.sidhu.androidautoglm.action.ActionType
import kotlin.math.roundToInt

/**
 * Floating window content composable.
 * Displays the floating window UI with status, task list, thinking process, and voice interaction.
 */
@OptIn(ExperimentalFoundationApi::class)
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

    // Extract state from Visible or TaskCompleted
    val status = when (val s = state) {
        is FloatingWindowState.Visible -> s.statusText
        is FloatingWindowState.TaskCompleted -> s.statusText
        else -> ""
    }
    val isTaskRunning = when (val s = state) {
        is FloatingWindowState.Visible -> s.isTaskRunning
        else -> false
    }
    val isPaused = when (val s = state) {
        is FloatingWindowState.Visible -> s.isPaused
        else -> false
    }
    val onPauseResumeCallback = when (val s = state) {
        is FloatingWindowState.Visible -> s.onPauseResumeCallback
        else -> null
    }
    val taskList = when (val s = state) {
        is FloatingWindowState.Visible -> s.taskList
        is FloatingWindowState.TaskCompleted -> s.taskList
        else -> emptyList()
    }
    val thinkingLines = when (val s = state) {
        is FloatingWindowState.Visible -> s.thinkingLines
        is FloatingWindowState.TaskCompleted -> s.thinkingLines
        else -> emptyList()
    }
    val actionContent = when (val s = state) {
        is FloatingWindowState.Visible -> s.actionContent
        is FloatingWindowState.TaskCompleted -> s.actionContent
        else -> null
    }
    val isTaskCompleted = state is FloatingWindowState.TaskCompleted
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 窗口已用 MATCH_PARENT 撑满宽度，Surface 用 fillMaxWidth 填满
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
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 500.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .heightIn(min = 388.dp)
            ) {
                // Title + Status + Action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            isTaskRunning -> Color.White.copy(alpha = 0.8f)
                            isError -> Color(0xFFFFCDD2)
                            else -> Color(0xFF81C784)
                        }

                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = titleColor
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                    }

                    // Right side: 任务运行时 [暂停/继续] [关闭]，否则 [返回应用] [关闭]
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isTaskRunning && onPauseResumeCallback != null) {
                            Button(
                                onClick = { onPauseResumeCallback.invoke() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.25f),
                                    contentColor = if (isPaused) Color(0xFF81C784) else Color.White
                                ),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(if (isPaused) R.string.fw_resume else R.string.fw_pause))
                            }
                        }
                        if (!isTaskRunning) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            floatingWindowController.forceDismiss()
                                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                            if (intent != null) {
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                context.startActivity(intent)
                                            }
                                        } catch (e: Exception) {
                                            Log.e("FloatingWindow", "Error launching main app", e)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.25f),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.fw_return_app))
                            }
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        floatingWindowController.dismiss()
                                    } catch (e: Exception) {
                                        Log.e("FloatingWindow", "Error dismissing window", e)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.fw_close),
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 任务清单 + 思考过程：可滚动，保证底部语音按钮不被挤出
                val middleScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(middleScrollState)
                        .fillMaxWidth()
                ) {
                    if (taskList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.fw_task_list),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            taskList.take(8).forEach { line ->
                                val (icon, cleanText) = parseTaskLine(line)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = icon,
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(18.dp)
                                    )
                                    Text(
                                        text = cleanText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.9f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // 思考过程：任务完成时只显示 action，不显示上方思考文字
                    if (actionContent != null || (!isTaskCompleted && thinkingLines.isNotEmpty())) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!isTaskCompleted) {
                            Text(
                                text = stringResource(R.string.fw_thinking),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 任务进行中：显示思考文字；任务完成时：不显示
                            if (!isTaskCompleted && thinkingLines.isNotEmpty()) {
                                FloatingThinkingText(
                                    lines = thinkingLines,
                                    maxVisibleLines = 5,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (actionContent != null) {
                                FloatingActionCard(
                                    action = actionContent,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // 底部：与悬浮窗同宽的语音输入按钮（长按说话），仅在暂停或任务完成时显示
                if (!isTaskRunning || isPaused) {
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
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
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
                                        if (change.position.y < -threshold) {
                                            if (!isCancelling) isCancelling = true
                                        } else {
                                            if (isCancelling) isCancelling = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    cancelled = true
                                }

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.fw_voice_input),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
                }
            }
        }
    }
}

/** 悬浮窗内显示的动作卡片（适配深色半透明背景） */
@Composable
private fun FloatingActionCard(
    action: FormattedContent.ActionContent,
    modifier: Modifier = Modifier
) {
    val backgroundColor = Color.White.copy(alpha = 0.2f)
    val textColor = when (action.actionType) {
        ActionType.FINISH -> Color(0xFF4CAF50)   // 绿
        ActionType.UNKNOWN -> Color(0xFFD32F2F)  // 红
        else -> Color.White
    }
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (action.icon != null) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = action.description,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

/** 移除任务文本中的 <think> 标签及其内容，避免显示模型内部标签 */
private fun stripThinkTags(text: String): String {
    return text
        .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("</?think>[^<]*", RegexOption.IGNORE_CASE), "")
        .trim()
}

private fun parseTaskLine(line: String): Pair<String, String> {
    val stripped = stripThinkTags(line.trim())
    if (stripped.isEmpty()) return "•" to ""
    return when {
        stripped.startsWith("- [x]") || stripped.startsWith("- [X]") ->
            "✅" to stripped.removePrefix("- [x]").removePrefix("- [X]").trimStart()
        stripped.startsWith("- [/]") ->
            "🔄" to stripped.removePrefix("- [/]").trimStart()
        stripped.startsWith("- [ ]") ->
            "⬜" to stripped.removePrefix("- [ ]").trimStart()
        stripped.startsWith("- [") -> {
            val afterBracket = stripped.indexOf(']')
            if (afterBracket > 0) "⬜" to stripped.substring(afterBracket + 1).trimStart()
            else "•" to stripped
        }
        else -> "•" to stripped
    }
}

/** 思考文字：最多显示 maxVisibleLines 行，超出可上下滚动 */
@Composable
private fun FloatingThinkingText(
    lines: List<String>,
    maxVisibleLines: Int = 5,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) return

    val lineHeightDp = 18.dp
    val spacingDp = 2.dp
    val maxHeightDp = lineHeightDp * maxVisibleLines + spacingDp * (maxVisibleLines - 1).coerceAtLeast(0)
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .heightIn(max = maxHeightDp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(spacingDp)
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.9f),
                lineHeight = lineHeightDp.value.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lineHeightDp)
            )
        }
    }
}
