package com.sidhu.androidautoglm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler

import android.net.Uri
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.utils.SpeechRecognizerManager
import com.sidhu.androidautoglm.utils.SherpaModelManager

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import android.widget.Toast

import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.sidhu.androidautoglm.ui.util.displayText
import com.sidhu.androidautoglm.ui.util.displayColor
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager


fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun toggleLanguage(context: Context) {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val current = prefs.getString("language_code", "zh")
    val next = if (current == "zh") "en" else "zh"
    prefs.edit().putString("language_code", next).apply()
    context.findActivity()?.recreate()
}

@Composable
fun LanguageSwitchButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = { toggleLanguage(context) },
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.switch_language),
            color = Color.White,
            textDecoration = TextDecoration.Underline
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenConversationList: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }

    // Fullscreen Image State
    var fullscreenImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Voice Review State
    var showVoiceReview by remember { mutableStateOf(false) }
    var voiceResultText by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Speech Recognition
    val speechRecognizerManager = remember { SpeechRecognizerManager(context) }
    val isListening by speechRecognizerManager.isListening.collectAsState()
    val soundLevel by speechRecognizerManager.soundLevel.collectAsState()
    var isVoiceMode by remember { mutableStateOf(false) } // Toggle between Text and Voice mode
    var isCancelling by remember { mutableStateOf(false) }

    // Sherpa Model Initialization
    val modelState by SherpaModelManager.modelState.collectAsState()
    
    // Check if model is ready
    val isModelReady = modelState is SherpaModelManager.ModelState.Ready

    // Auto-init model
    LaunchedEffect(Unit) {
        SherpaModelManager.initModel(context)
    }

    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizerManager.destroy()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            val text = context.getString(R.string.voice_permission_granted)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } else {
            val text = context.getString(R.string.voice_permission_denied)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    // Check service status when app resumes (e.g. returning from Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkServiceStatus()
                viewModel.checkOverlayPermission(context)
                viewModel.checkBatteryOptimization(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initial check on composition
    LaunchedEffect(Unit) {
        viewModel.checkServiceStatus()
        viewModel.checkOverlayPermission(context)
        viewModel.checkBatteryOptimization(context)
    }

    // Handle back button press when fullscreen image is shown
    BackHandler(enabled = fullscreenImage != null) {
        fullscreenImage = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FA)) // Light gray background like Doubao
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar Area (Minimal)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            scope.launch {
                                if (listState.firstVisibleItemIndex > 0) {
                                    listState.animateScrollToItem(0)
                                }
                            }
                        }
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = uiState.currentConversation?.title ?: stringResource(R.string.chat_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            // Task state badge
                            uiState.currentConversation?.lastTaskState?.let { state ->
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = Color(android.graphics.Color.parseColor(state.displayColor()))
                                ) {
                                    Text(
                                        state.displayText(context),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        // Step count display
                        if (uiState.currentConversation != null && uiState.currentConversation!!.lastStepCount > 0) {
                            Text(
                                text = stringResource(R.string.step_count_label) + uiState.currentConversation!!.lastStepCount,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var showDeleteDialog by remember { mutableStateOf(false) }

                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text(stringResource(R.string.delete_current_conversation_title)) },
                            text = { Text(stringResource(R.string.delete_current_conversation_message)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        uiState.activeConversationId?.let {
                                            viewModel.deleteConversation(it)
                                        }
                                        showDeleteDialog = false
                                    },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text(stringResource(R.string.delete_conversation))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }

                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = !uiState.isTaskRunning
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete_current_conversation_title),
                            tint = if (uiState.isTaskRunning) Color.Gray.copy(alpha = 0.5f) else Color.Gray
                        )
                    }
                    IconButton(
                        onClick = onOpenConversationList,
                        enabled = !uiState.isTaskRunning
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.conversation_list_title),
                            tint = if (uiState.isTaskRunning) Color.Gray.copy(alpha = 0.5f) else Color.Gray
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title), tint = Color.Gray)
                    }
                }
            }

            // Message List Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                    reverseLayout = false,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.messages) { message ->
                        MessageItem(
                            message = message,
                            onShowImage = { bitmap -> fullscreenImage = bitmap }
                        )
                    }
                }

                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            // Input Area (Bottom)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 8.dp,
                color = Color(0xFFF5F5F5) // Very Light Gray background
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Switch Mode Button
                    IconButton(
                        onClick = { isVoiceMode = !isVoiceMode }
                    ) {
                        Icon(
                            imageVector = if (isVoiceMode) Icons.Filled.Keyboard else Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.switch_input_mode),
                            tint = Color.Gray
                        )
                    }

                    if (isVoiceMode) {
                        // Hold to Talk Button
                        val buttonText = when {
                            isCancelling -> stringResource(R.string.voice_release_to_cancel)
                            isListening -> stringResource(R.string.voice_release_to_send)
                            modelState is SherpaModelManager.ModelState.Loading -> stringResource(R.string.voice_model_loading)
                            modelState is SherpaModelManager.ModelState.Error -> stringResource(R.string.voice_model_load_failed)
                            !isModelReady -> stringResource(R.string.voice_model_initializing)
                            else -> stringResource(R.string.voice_hold_to_speak)
                        }

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
                                .weight(1f)
                                .height(56.dp)
                                .background(
                                    color = if (isListening) Color.LightGray else Color.White,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    contentDescription = buttonText
                                }
                                .pointerInput(isModelReady, modelState) {
                                    if (!isModelReady || modelState is SherpaModelManager.ModelState.Error || modelState is SherpaModelManager.ModelState.NotInitialized) {
                                        detectTapGestures(
                                            onTap = {
                                                // Only allow tap if not loading
                                                if (modelState is SherpaModelManager.ModelState.NotInitialized || modelState is SherpaModelManager.ModelState.Error) {
                                                    scope.launch {
                                                        SherpaModelManager.initModel(context)
                                                    }
                                                }
                                            }
                                        )
                                        return@pointerInput
                                    }
                                    
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        
                                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                                context,
                                                android.Manifest.permission.RECORD_AUDIO
                                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        ) {
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
                                                    onErrorCallback = { error -> Toast.makeText(context, error, Toast.LENGTH_SHORT).show() }
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
                                                    if (voiceResultText.isNotBlank()) showVoiceReview = true
                                                }
                                                isCancelling = false
                                            }
                                        } else {
                                            scope.launch(Dispatchers.Main) {
                                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = buttonText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black
                            )
                        }
                    } else {
                        // Text Input Field
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f),
                            enabled = !uiState.isTaskRunning,
                            placeholder = { Text(stringResource(R.string.input_placeholder), color = Color.Gray) },
                            shape = MaterialTheme.shapes.medium,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                disabledContainerColor = Color.White,
                                disabledTextColor = Color.Gray,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            maxLines = 3
                        )
                    }

                    // Send Button / Continue Button
                    if (uiState.currentConversation?.lastTaskState != null) {
                        // Continue Task Button
                        Button(
                            onClick = { viewModel.continueTask() },
                            enabled = !uiState.isLoading && !uiState.isRunning && !uiState.isTaskRunning,
                            modifier = Modifier.size(80.dp, 48.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!uiState.isLoading && !uiState.isRunning && !uiState.isTaskRunning)
                                    MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        ) {
                            Text(stringResource(R.string.continue_task), color = Color.White)
                        }
                    } else {
                        // Send Button (normal task)
                        IconButton(
                            onClick = {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            },
                            enabled = !uiState.isLoading && !uiState.isTaskRunning && inputText.isNotBlank()
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                color = if (!uiState.isLoading && inputText.isNotBlank())
                                    MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.send_button),
                                    tint = Color.White,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen Image Overlay (highest priority)
        if (fullscreenImage != null) {
            FullscreenImageOverlay(
                imageBitmap = fullscreenImage!!,
                onDismiss = { fullscreenImage = null }
            )
        }

        if (isListening) {
            RecordingIndicator(soundLevel = soundLevel)
        }

        // Voice Review Overlay
        if (showVoiceReview) {
            VoiceReviewOverlay(
                text = voiceResultText,
                onTextChange = { voiceResultText = it },
                onCancel = { 
                    showVoiceReview = false
                    voiceResultText = ""
                },
                onSend = {
                    if (voiceResultText.isNotBlank()) {
                        viewModel.sendMessage(voiceResultText)
                    }
                    showVoiceReview = false
                    voiceResultText = ""
                }
            )
        }

        // Error / Service Check Overlay (Topmost)
        // Error / Service Check Overlay (Topmost)
        if (uiState.error != null || uiState.missingAccessibilityService || uiState.missingOverlayPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LanguageSwitchButton()
                    if (uiState.missingAccessibilityService) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            shadowElevation = 8.dp
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.accessibility_error),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            context.startActivity(intent)
                                        }
                                    ) {
                                        Text(stringResource(R.string.enable_action))
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.missingOverlayPermission) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            shadowElevation = 8.dp
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.overlay_error),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            context.startActivity(intent)
                                        }
                                    ) {
                                        Text(stringResource(R.string.grant_action))
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.error != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            shadowElevation = 8.dp
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = uiState.error!!,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { viewModel.clearError() }) {
                                        Text(stringResource(R.string.close))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageItem(
    message: UiMessage,
    onShowImage: (android.graphics.Bitmap) -> Unit = {}
) {
    val isUser = message.role == "user"
    val isAssistant = message.role == "assistant"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) MaterialTheme.colorScheme.primary else Color.White
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else Color.Black

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = if (isUser)
                MaterialTheme.shapes.large.copy(
                    bottomEnd = androidx.compose.foundation.shape.CornerSize(
                        0.dp
                    )
                )
            else
                MaterialTheme.shapes.large.copy(
                    bottomStart = androidx.compose.foundation.shape.CornerSize(
                        0.dp
                    )
                ),
            color = containerColor,
            shadowElevation = if (isUser) 0.dp else 2.dp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(context, context.getString(R.string.text_copied), Toast.LENGTH_SHORT).show()
                        }
                    )
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // User messages show inline image, assistant messages show button
                if (isUser && message.image != null) {
                    androidx.compose.foundation.Image(
                        bitmap = message.image!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.screenshot_cd),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .padding(bottom = 8.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor
                )
            }
        }

        // Show "View Image" button for assistant messages with images
        if (isAssistant && message.image != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { onShowImage(message.image!!) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = stringResource(R.string.view_image_cd),
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.view_image_button),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Fullscreen image viewing overlay
 */
@Composable
fun FullscreenImageOverlay(
    imageBitmap: android.graphics.Bitmap,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onDismiss()
            }
    ) {
        // Close button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.close_cd),
                tint = Color.White
            )
        }

        // Image in center
        androidx.compose.foundation.Image(
            bitmap = imageBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.fullscreen_screenshot_cd),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    }
}
