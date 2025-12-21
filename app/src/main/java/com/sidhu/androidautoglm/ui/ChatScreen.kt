package com.sidhu.androidautoglm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape

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

@Composable
fun RecordingIndicator(soundLevel: Float) {
    // Infinite breathing animation to show activity even if sound level is low
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingScale"
    )

    // Sound level based scaling (more responsive)
    // Map -60dB (silence) to -10dB (loud speech) to 0.0 - 1.0
    // Using a wider range to capture softer voices too
    val normalizedLevel = ((soundLevel + 60f) / 50f).coerceIn(0f, 1f)
    
    // Apply non-linear curve to make small changes more visible
    // Map 0..1 to 1.0..2.0 range
    val volumeScale = 1f + (normalizedLevel * normalizedLevel * 1.0f)
    
    val animatedVolumeScale by animateFloatAsState(
        targetValue = volumeScale,
        animationSpec = tween(durationMillis = 50), // Faster response
        label = "volumeScale"
    )
    
    // Combine breathing and volume
    val finalScale = maxOf(breathingScale, animatedVolumeScale)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {}, // Block touches
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(200.dp) // Slightly larger container
                .background(Color.DarkGray.copy(alpha = 0.9f), shape = MaterialTheme.shapes.medium)
                .padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(100.dp)
            ) {
                // Outer ripple/volume effect
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer {
                            scaleX = finalScale
                            scaleY = finalScale
                            alpha = 0.4f
                        }
                        .background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape)
                )
                
                // Inner breathing circle (always active)
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer {
                            scaleX = breathingScale
                            scaleY = breathingScale
                            alpha = 0.6f
                        }
                        .background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape)
                )
                
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.voice_listening),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun VoiceReviewOverlay(
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {}, // Block clicks
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Text Bubble
            Surface(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = Color(0xFF95EC69), // WeChat Green
                shadowElevation = 8.dp
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Black)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Cancel Button
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color.White, shape = androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = stringResource(R.string.voice_cancel),
                        tint = Color.Gray,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Send Button
                IconButton(
                    onClick = onSend,
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color.White, shape = androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Check,
                        contentDescription = stringResource(R.string.send_button),
                        tint = Color(0xFF95EC69), // WeChat Green
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    
    // Voice Review State
    var showVoiceReview by remember { mutableStateOf(false) }
    var voiceResultText by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()

    // Speech Recognition
    val speechRecognizerManager = remember { SpeechRecognizerManager(context) }
    val isListening by speechRecognizerManager.isListening.collectAsState()
    val soundLevel by speechRecognizerManager.soundLevel.collectAsState()
    var isVoiceMode by remember { mutableStateOf(false) } // Toggle between Text and Voice mode

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
                    modifier = Modifier.clickable {
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
                    Text(
                        text = stringResource(R.string.chat_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.Black
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var showClearDialog by remember { mutableStateOf(false) }

                    if (showClearDialog) {
                        AlertDialog(
                            onDismissRequest = { showClearDialog = false },
                            title = { Text(stringResource(R.string.clear_chat_title)) },
                            text = { Text(stringResource(R.string.clear_chat_message)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        viewModel.clearMessages()
                                        showClearDialog = false
                                    }
                                ) {
                                    Text(stringResource(R.string.confirm))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showClearDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }

                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.clear_chat),
                            tint = Color.Gray
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
                        MessageItem(message)
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
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(
                                    color = if (isListening) Color.LightGray else Color.White,
                                    shape = MaterialTheme.shapes.medium
                                )
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
                                    
                                    detectTapGestures(
                                        onPress = {
                                            // Check permission first
                                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                                    context,
                                                    android.Manifest.permission.RECORD_AUDIO
                                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                            ) {
                                                try {
                                                    // Ensure running on Main thread for SpeechRecognizer
                                                    withContext(Dispatchers.Main) {
                                                        // Clear previous result
                                                        voiceResultText = ""
                                                        
                                                        speechRecognizerManager.startListening(
                                                            onResultCallback = { result ->
                                                                voiceResultText = result
                                                            },
                                                            onErrorCallback = { error ->
                                                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                                            }
                                                        )
                                                    }
                                                    
                                                    // Wait for release
                                                    tryAwaitRelease()
                                                    
                                                    // Stop listening
                                                    speechRecognizerManager.stopListening()
                                                    
                                                    // Show review overlay if text captured
                                                    if (voiceResultText.isNotBlank()) {
                                                        showVoiceReview = true
                                                    }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    speechRecognizerManager.cancel()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                }
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val text = when {
                                isListening -> stringResource(R.string.voice_release_to_send)
                                modelState is SherpaModelManager.ModelState.Loading -> stringResource(R.string.voice_model_loading)
                                modelState is SherpaModelManager.ModelState.Error -> stringResource(R.string.voice_model_load_failed)
                                !isModelReady -> stringResource(R.string.voice_model_initializing)
                                else -> stringResource(R.string.voice_hold_to_speak)
                            }
                            
                            Text(
                                text = text,
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
                            placeholder = { Text(stringResource(R.string.input_placeholder), color = Color.Gray) },
                            shape = MaterialTheme.shapes.medium,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                disabledContainerColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            maxLines = 3
                        )
                    }

                    // Send Button
                    IconButton(
                        onClick = {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        },
                        enabled = !uiState.isLoading && inputText.isNotBlank()
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (!uiState.isLoading && inputText.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Send, 
                                contentDescription = stringResource(R.string.send_button), 
                                tint = Color.White,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
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
fun MessageItem(message: UiMessage) {
    val isUser = message.role == "user"
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
                if (message.image != null) {
                    // Image display logic if needed
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor
                )
            }
        }
    }
}