package com.sidhu.androidautoglm

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.res.stringResource
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.ui.VoiceOrb
import com.sidhu.androidautoglm.utils.SpeechRecognizerManager
import com.sidhu.androidautoglm.utils.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
// 引入主题色列表
// 引入主题色列表 (已替换为本地变量)

class WakeUpInputController(private val context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private companion object {
        private const val TAG = "WakeUpInput"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatView: ComposeView? = null
    private var isShowing = false
    private lateinit var windowParams: WindowManager.LayoutParams
    
    private var _uiState by mutableStateOf<InputUIState>(InputUIState.Initializing)
    private var _recognizedText by mutableStateOf("")
    
    // 控制键盘模式的状态
    private var _isKeyboardMode by mutableStateOf(false)

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoCloseJob: Job? = null
    private var listenErrorRunnable: Runnable? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    
    private var mediaPlayer: MediaPlayer? = null

    // 屏幕真实物理尺寸
    private var screenRealWidth = 0
    private var screenRealHeight = 0

    enum class InputUIState {
        Initializing,
        WakeSuccess,
        Listening,
        Processing,
        Closing
    }

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        updateScreenDimensions()
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenRealWidth = metrics.widthPixels
        screenRealHeight = metrics.heightPixels
    }

    private fun playSound(soundName: String, onCompletion: (() -> Unit)? = null) {
        Log.d(TAG, "playSound: 开始播放 $soundName")
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            val assetFileDescriptor = context.assets.openFd("sounds/$soundName")
            Log.d(TAG, "playSound: 已打开 assets/sounds/$soundName, fd=${assetFileDescriptor.fileDescriptor}")
            val mp = MediaPlayer().apply {
                setDataSource(assetFileDescriptor.fileDescriptor,
                             assetFileDescriptor.startOffset,
                             assetFileDescriptor.length)
                assetFileDescriptor.close()
                setAudioStreamType(AudioManager.STREAM_NOTIFICATION) // 不受 muteMedia 影响
                prepare()
                start()
                Log.d(TAG, "playSound: MediaPlayer.start() 已调用, stream=STREAM_NOTIFICATION")
                setOnCompletionListener {
                    Log.d(TAG, "playSound: $soundName 播放完成")
                    it.release()
                    mediaPlayer = null
                    onCompletion?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "playSound: MediaPlayer 错误 what=$what extra=$extra")
                    onCompletion?.invoke()
                    false
                }
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "playSound: 加载/播放失败, 使用 ToneGenerator 备用", e)
            assetNotFoundFallbackTone(onCompletion)
        }
    }

    /** 当 assets/sounds/ 下无音频文件时，使用系统提示音作为备用 */
    private fun assetNotFoundFallbackTone(onCompletion: (() -> Unit)? = null) {
        try {
            Log.d(TAG, "assetNotFoundFallbackTone: 播放 ToneGenerator 备用音")
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            mainHandler.postDelayed({
                tg.release()
                onCompletion?.invoke()
            }, 250)
        } catch (e: Exception) {
            Log.e(TAG, "assetNotFoundFallbackTone: 失败", e)
            onCompletion?.invoke()
        }
    }

    fun show(onCommandFinalized: (String) -> Unit) {
        Log.d(TAG, "show: 被调用, isShowing=$isShowing")
        if (isShowing) return
        updateScreenDimensions()
        
        _uiState = InputUIState.Listening  // 立即显示「正在聆听」UI，给用户即时反馈
        _recognizedText = ""
        _isKeyboardMode = false // 默认语音模式
        autoCloseJob?.cancel()
        listenErrorRunnable?.let { mainHandler.removeCallbacks(it); listenErrorRunnable = null }
        
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // 立即显示 UI 并启动麦克风，音效后台播放；提前录音避免漏掉用户命令的第一个字
        // 识别结果中的「我在呢」/唤醒词会在 WakeWordDetector 中自动去除
        initWindowParams()
        initView()
        try {
            windowManager.addView(floatView, windowParams)
            isShowing = true
            Log.d(TAG, "show: 已添加 UI, 立即启动麦克风监听（音效后台播放）")
            // 立即启动录音，不再等待音效结束，避免用户开口时尚未开始录音导致漏字
            if (!_isKeyboardMode) {
                WakeWordDetector.startCommandMode(context)
                coroutineScope.launch {
                    WakeWordDetector.realtimeCommand.collect { partial ->
                        if (_uiState == InputUIState.Listening && !_isKeyboardMode && !partial.isNullOrEmpty()) {
                            _recognizedText = partial
                        }
                    }
                }
            }
            playSound("1.mp3") { /* 音效仅作提示，不再阻塞录音启动 */ }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 切换输入模式（语音 <-> 键盘）
    private fun toggleInputMode(isKeyboard: Boolean) {
        _isKeyboardMode = isKeyboard
        
        if (isKeyboard) {
            // 键盘模式：
            // 1. 取消任何可能存在的自动关闭任务 (关键：输入框一直显示)
            autoCloseJob?.cancel()
            listenErrorRunnable?.let { mainHandler.removeCallbacks(it); listenErrorRunnable = null }
            
            // 2. 停止语音识别
            WakeWordDetector.stopListening()
            
            // 3. 允许窗口获取焦点，以便弹出输入法
            windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // 设置软键盘模式为 Visible + Resize (顶起布局)
            windowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or 
                                         WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            // 语音模式：
            // 1. 恢复为不可获取焦点
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            
            // 2. 重新开始语音监听 (这里会触发 muteMedia，这是语音模式预期的行为)
            WakeWordDetector.startCommandMode(context)
            _recognizedText = "" 
        }
        
        // 4. 立即更新窗口布局
        if (isShowing && floatView != null) {
            windowManager.updateViewLayout(floatView, windowParams)
        }
    }
    
    // 处理最终的文本输入（无论是语音还是键盘提交）
    private fun handleFinalInput(text: String) {
        if (text.isBlank()) return
        
        _recognizedText = text
        _uiState = InputUIState.Processing
        
        // 确保收起键盘
        if (_isKeyboardMode) {
             // 【核心修复】：
             // 这里不调用 toggleInputMode(false)，因为它会重启语音监听从而导致静音。
             // 我们只手动恢复 FLAG_NOT_FOCUSABLE，这样系统会自动收起键盘，但不会静音。
             
             windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
             
             // 更新窗口以让改动生效
             if (isShowing && floatView != null) {
                 windowManager.updateViewLayout(floatView, windowParams)
             }
        }
        
        mainHandler.postDelayed({
            hide()
            if (context is AutoGLMShizukuService) {
                context.executeCommand(text)
            }
        }, 1000)
    }
    
    fun onCommandReceived(command: String) {
        if (!isShowing) return
        // 只有在非键盘模式下才自动处理语音结果
        if (!_isKeyboardMode) {
            handleFinalInput(command)
        }
    }
    
    fun onTaskSuccess() {
        playSound("2.mp3") { restartWakeWordDetection() }
    }
    
    fun onTaskFailed() {
        playSound("3.mp3") { restartWakeWordDetection() }
    }
    
    fun onUserStopped() {
        restartWakeWordDetection()
    }
    
    private fun restartWakeWordDetection() {
        WakeWordDetector.stopListening()
        // 延长至 2s，避免音效/回声未消散时立即监听导致连续误唤醒（部分机型反馈）
        mainHandler.postDelayed({
            WakeWordDetector.startWakeWordMode(context)
        }, 2000)
    }
    
    fun cleanup() {
        mediaPlayer?.release()
        mediaPlayer = null
        autoCloseJob?.cancel()
        if (isShowing) hide()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
    
    fun checkAndRestartWakeWord() {
        restartWakeWordDetection()
    }
    
    fun onListenError() {
         autoCloseJob?.cancel()
         listenErrorRunnable?.let { mainHandler.removeCallbacks(it) }
         if (!_isKeyboardMode) {
             _recognizedText = context.getString(R.string.wake_no_command_heard)
             listenErrorRunnable = Runnable {
                 if (!_isKeyboardMode) {
                     hide()
                     restartWakeWordDetection()
                 }
                 listenErrorRunnable = null
             }
             mainHandler.postDelayed(listenErrorRunnable!!, 15000)
         }
    }

    fun hide() {
        if (!isShowing) return
        try {
            autoCloseJob?.cancel()
            listenErrorRunnable?.let { mainHandler.removeCallbacks(it); listenErrorRunnable = null }
            windowManager.removeView(floatView)
            isShowing = false
            floatView = null
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // 隐藏后重启唤醒词监听
            restartWakeWordDetection()
        }
    }

    private fun initWindowParams() {
        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, 
            PixelFormat.TRANSLUCENT
        )
        windowParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        
        windowParams.gravity = Gravity.TOP or Gravity.START
        windowParams.x = 0
        windowParams.y = 0
    }

    private fun initView() {
        floatView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@WakeUpInputController)
            setViewTreeViewModelStoreOwner(this@WakeUpInputController)
            setViewTreeSavedStateRegistryOwner(this@WakeUpInputController)
            
            setContent {
                WakeUpInputContent(
                    uiState = _uiState,
                    text = _recognizedText,
                    isKeyboardMode = _isKeyboardMode,
                    onToggleKeyboard = { toggleInputMode(!_isKeyboardMode) },
                    onSendText = { handleFinalInput(it) },
                    onClose = {
                        hide()
                        restartWakeWordDetection()
                    }
                )
            }
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
}

val AppThemeColors = listOf(
    Color(0xFFE53935), 
    Color(0xFFFB8C00), 
    Color(0xFFFFA000), 
    Color(0xFF43A047), 
    Color(0xFF00ACC1), 
    Color(0xFF2196F3), 
    Color(0xFF8E24AA)  
)

fun getInterpolatedColor(progress: Float, colors: List<Color>): Color {
    if (colors.isEmpty()) return Color.White
    if (colors.size == 1) return colors[0]
    
    val scaledProgress = progress * (colors.size - 1)
    val index = scaledProgress.toInt().coerceIn(0, colors.size - 2)
    val remainder = scaledProgress - index
    
    val c1 = colors[index]
    val c2 = colors[index + 1]
    
    return Color(
        red = c1.red + (c2.red - c1.red) * remainder,
        green = c1.green + (c2.green - c1.green) * remainder,
        blue = c1.blue + (c2.blue - c1.blue) * remainder,
        alpha = c1.alpha + (c2.alpha - c1.alpha) * remainder
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeUpInputContent(
    uiState: WakeUpInputController.InputUIState,
    text: String,
    isKeyboardMode: Boolean,
    onToggleKeyboard: () -> Unit,
    onSendText: (String) -> Unit,
    onClose: () -> Unit
) {
    // ------------------- 颜色定义 (硬编码原始配色，不跟随主题) -------------------
    val isDark = isSystemInDarkTheme()
    
    val neonCyan = Color(0xFF00FFFF)
    val neonBlue = Color(0xFF2979FF)
    val neonPurple = Color(0xFFD500F9)
    val neonPink = Color(0xFFFF00FF)
    val neonYellow = Color(0xFFFFEA00)
    
    // 获取当前上下文
    val context = LocalContext.current
    
    // 【关键新增】从 SharedPreferences 读取当前主题色索引，并映射到颜色
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    val themeIndex = remember { prefs.getInt("theme_color_index", 5) } // 默认为5 (蓝色)
    val themeColor = AppThemeColors.getOrElse(themeIndex) { Color(0xFF2196F3) }
    
    // 半透明背景
    val inputAreaBackground = if (isDark) Color(0xFF424242).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.9f)
    
    val inputTextColor = if (isDark) Color.White else Color.Black
    val iconColor = if (isDark) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.9f) 

    val borderColors = listOf(
        neonCyan, neonBlue, neonPurple, neonPink, neonYellow, neonCyan
    )
    
    // 自定义文本选择颜色 (使用主题色)
    val customTextSelectionColors = TextSelectionColors(
        handleColor = themeColor,
        backgroundColor = themeColor.copy(alpha = 0.4f)
    )

    // ------------------- 动画 -------------------
    val infiniteTransition = rememberInfiniteTransition(label = "effects")
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val currentThemeColor = remember(rotation) {
        val progress = (rotation / 360f).coerceIn(0f, 1f)
        getInterpolatedColor(progress, borderColors)
    }

    val isListening = uiState == WakeUpInputController.InputUIState.Listening || 
                      uiState == WakeUpInputController.InputUIState.Processing

    // 呼出动画
    val appearanceAnim = remember { Animatable(0f) }
    LaunchedEffect(isListening) {
        if (isListening) {
            appearanceAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
            )
        } else {
            appearanceAnim.snapTo(0f)
        }
    }
    val appearanceProgress = appearanceAnim.value

    // 屏幕参数
    val view = LocalView.current
    val density = LocalDensity.current
    var cornerRadiusPx by remember { mutableFloatStateOf(0f) }
    
    LaunchedEffect(view) {
        var radius = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val insets = view.rootWindowInsets
                // 使用完全限定名称，避免导入问题
                val topLeft = insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
                if (topLeft != null) {
                    radius = topLeft.radius.toFloat()
                }
            } catch (e: Exception) { }
        }
        if (radius <= 0) {
            radius = with(density) { 48.dp.toPx() }
        }
        cornerRadiusPx = radius
    }
    
    // 焦点请求器 (用于输入法)
    val focusRequester = remember { FocusRequester() }

    // ------------------- UI 结构 -------------------
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        // 1. 黑色遮罩
        if (isListening) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f * appearanceProgress))
            )
        }

        // ==========================================
        // 【全屏流光边框】固定在屏幕层 (最上层)
        // ==========================================
        if (isListening && cornerRadiusPx > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val visibleHeight = size.height * appearanceProgress
                clipRect(
                    left = 0f,
                    top = size.height - visibleHeight,
                    right = size.width,
                    bottom = size.height
                ) {
                    val strokeWidth = 5.dp.toPx()
                    val outerRadius = cornerRadiusPx
                    val innerRadius = max(0f, outerRadius - strokeWidth)

                    val outerPath = Path().apply {
                        addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(outerRadius)))
                    }
                    val innerPath = Path().apply {
                        addRoundRect(RoundRect(Rect(strokeWidth, strokeWidth, size.width - strokeWidth, size.height - strokeWidth), CornerRadius(innerRadius)))
                    }
                    val borderPath = Path().apply {
                        op(outerPath, innerPath, PathOperation.Difference)
                    }

                    clipPath(borderPath) {
                        rotate(rotation) {
                            val maxSize = max(size.width, size.height) * 2.5f
                            drawCircle(
                                brush = Brush.sweepGradient(borderColors),
                                radius = maxSize / 2,
                                center = center
                            )
                        }
                    }
                    
                    drawPath(
                        path = borderPath,
                        color = Color.White.copy(alpha = 0.3f),
                        blendMode = BlendMode.Overlay
                    )
                }
            }
        }

        // 2. 底部向上移动的容器 (使用 imePadding 来避让键盘)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.ime),
            contentAlignment = Alignment.BottomCenter
        ) {
            
            // 3. 光效层 (Canvas) - 光束背景
            if (isListening) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    
                    val currentBeamHeight = h * appearanceProgress
                    val beamTopY = h - currentBeamHeight
                    val beamBottomY = h
                    
                    val dynamicBeamColors = listOf(
                        Color.Transparent,
                        currentThemeColor.copy(alpha = 0.1f),  
                        currentThemeColor.copy(alpha = 0.4f),  
                        currentThemeColor.copy(alpha = 0.85f)  
                    )

                    // 绘制矩形光幕
                    if (currentBeamHeight > 0) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = dynamicBeamColors,
                                startY = beamTopY,
                                endY = beamBottomY
                            ),
                            topLeft = Offset(0f, beamTopY),
                            size = androidx.compose.ui.geometry.Size(w, currentBeamHeight),
                            blendMode = BlendMode.Screen
                        )
                    }
                    
                    // 绘制底部光源核心
                    val coreHeight = 80.dp.toPx() * pulse
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.95f * appearanceProgress),      
                                currentThemeColor.copy(alpha = 0.4f * appearanceProgress), 
                                Color.Transparent                     
                            ),
                            center = Offset(w / 2, h), 
                            radius = w * 0.8f
                        ),
                        topLeft = Offset(-w * 0.2f, h - coreHeight / 2),
                        size = androidx.compose.ui.geometry.Size(w * 1.4f, coreHeight),
                        blendMode = BlendMode.Screen
                    )
                }
            }

            // 5. 底部实体灯条
            if (isListening) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent, 
                                    currentThemeColor, 
                                    Color.White, 
                                    currentThemeColor,
                                    Color.Transparent
                                )
                            )
                        )
                        .graphicsLayer { 
                            alpha = pulse * appearanceProgress 
                        }
                )
            }

            // 6. 内容区域：文字 或 输入框
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 给底部留点空间
                    .padding(bottom = 30.dp, start = 16.dp, end = 16.dp)
                    .graphicsLayer {
                        alpha = appearanceProgress
                        // 从下方上浮动画
                        translationY = (1f - appearanceProgress) * 50.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                
                // 如果是键盘模式，显示完全复刻的主界面输入框
                if (isKeyboardMode) {
                    // 自动请求焦点
                    LaunchedEffect(Unit) {
                        delay(100) 
                        focusRequester.requestFocus()
                    }
                    
                    CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
                        // 防止点击输入框关闭窗口
                        Box(modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(inputAreaBackground, RoundedCornerShape(28.dp))
                                    // 复刻主界面的霓虹边框逻辑
                                    .drawWithContent {
                                        drawContent()
                                        
                                        val strokeWidth = 2.dp.toPx()
                                        val cornerRadius = 28.dp.toPx()
                                        
                                        val outerPath = Path().apply {
                                            addRoundRect(RoundRect(rect = Rect(offset = Offset.Zero, size = size), cornerRadius = CornerRadius(cornerRadius)))
                                        }
                                        val innerPath = Path().apply {
                                            addRoundRect(RoundRect(
                                                left = strokeWidth, top = strokeWidth, 
                                                right = size.width - strokeWidth, bottom = size.height - strokeWidth,
                                                cornerRadius = CornerRadius(cornerRadius - strokeWidth)
                                            ))
                                        }
                                        val borderPath = Path().apply {
                                            op(outerPath, innerPath, PathOperation.Difference)
                                        }
                                        
                                        clipPath(borderPath) {
                                            rotate(rotation) {
                                                val maxSize = max(size.width, size.height) * 2.5f
                                                drawCircle(
                                                    brush = Brush.sweepGradient(borderColors),
                                                    radius = maxSize / 2,
                                                    center = center
                                                )
                                            }
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                
                                var inputText by remember { mutableStateOf("") }

                                TextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp)
                                        .heightIn(min = 50.dp, max = 150.dp) // 高度自适应
                                        .focusRequester(focusRequester), // 绑定焦点请求器
                                    placeholder = { 
                                        Text(stringResource(R.string.wake_input_placeholder), color = Color.Gray, fontSize = 16.sp) 
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        cursorColor = themeColor, // 光标跟随主题色
                                        focusedTextColor = inputTextColor,
                                        unfocusedTextColor = inputTextColor
                                    ),
                                    maxLines = 5,
                                    singleLine = false
                                )
                                
                                if (inputText.isNotBlank()) {
                                    IconButton(
                                        onClick = { onSendText(inputText) },
                                        modifier = Modifier.padding(end = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Send, 
                                            contentDescription = "Send", 
                                            tint = themeColor // 【修改】发送按钮跟随主题色
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 语音模式：显示键盘图标 + 文字 + 语音球
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // 左侧键盘图标
                        IconButton(
                            onClick = onToggleKeyboard,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                // 防止点击穿透触发关闭
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggleKeyboard)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = "Switch to Keyboard",
                                tint = iconColor,
                                // 修改：调整为 26.dp
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // 中间文字
                        val displayText = when {
                            text.isNotEmpty() -> text
                            uiState == WakeUpInputController.InputUIState.Processing -> stringResource(R.string.wake_confirming)
                            uiState == WakeUpInputController.InputUIState.Listening -> stringResource(R.string.wake_listening)
                            else -> ""
                        }
                        
                        if (displayText.isNotEmpty()) {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold, 
                                    fontSize = 14.sp, // 保持 14.sp
                                    letterSpacing = 1.sp,
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.8f),
                                        offset = Offset(0f, 2f),
                                        blurRadius = 4f
                                    )
                                ),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 48.dp) // 给两侧图标留出空间
                            )
                        }
                        
                        // 语音球 (显示在底部)
                        // 关键：这里直接传入固定的蓝色 (0xFF2196F3)，因为在 ChatScreen.kt 的 VoiceOrb 实现中
                        // 我们对 0xFF2196F3 做了特殊判断，会使用硬编码的 neon blue 质感，保证和原来一样。
                        VoiceOrb(
                            isListening = uiState == WakeUpInputController.InputUIState.Listening,
                            soundLevel = 0f, 
                            baseColor = Color(0xFF2196F3), 
                            onClick = {},
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 50.dp) // 稍微下移，仅露出一部分作为视觉装饰
                                .size(40.dp)
                                .graphicsLayer { alpha = 0f } // 暂时隐藏（或者你可以设为1f显示出来），根据你之前的逻辑
                        )
                    }
                }
            }
        }
    }
}