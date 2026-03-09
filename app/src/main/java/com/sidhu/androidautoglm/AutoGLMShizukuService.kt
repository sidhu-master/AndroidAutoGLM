package com.sidhu.androidautoglm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.sidhu.androidautoglm.utils.SherpaModelManager
import com.sidhu.androidautoglm.utils.ShizukuHelper
import com.sidhu.androidautoglm.utils.ScreenshotHelper
import com.sidhu.androidautoglm.utils.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AutoGLMShizukuService - 基于 Shizuku 的前台服务
 *
 * 基于 Shizuku 的前台服务。
 * 作为前台服务运行，支持语音唤醒功能。
 *
 * 功能：
 * - 截图（通过 Shizuku）
 * - 执行动作（通过 Shizuku）
 * - 悬浮窗显示
 * - 语音唤醒（通过 WakeWordDetector）
 */
class AutoGLMShizukuService : Service(), IGLMService {

    companion object {
        private const val TAG = "AutoGLMShizukuService"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "AutoGLM_Shizuku_Service_Channel"

        private val _serviceInstance = MutableStateFlow<AutoGLMShizukuService?>(null)
        val serviceInstance = _serviceInstance.asStateFlow()

        /**
         * 唤醒词识别成功后的语音指令流
         */
        val voiceCommandFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

        fun getInstance(): AutoGLMShizukuService? = _serviceInstance.value

        /**
         * 启动 Shizuku 服务
         */
        fun startService(context: Context) {
            val intent = Intent(context, AutoGLMShizukuService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止 Shizuku 服务
         */
        fun stopService(context: Context) {
            val intent = Intent(context, AutoGLMShizukuService::class.java)
            context.stopService(intent)
        }
    }

    private val _currentApp = MutableStateFlow<String?>(null)
    override val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    private var _floatingWindowController: FloatingWindowController? = null
    override val floatingWindowController: FloatingWindowController? get() = _floatingWindowController

    /** 手势动画（黄色圆圈） */
    private var _animationController: AnimationController? = null

    // 唤醒词输入控制器（全屏UI）
    private var wakeUpInputController: WakeUpInputController? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 是否已初始化
    private var isInitialized = false

    // 1×1 像素保活悬浮窗（防止后台被降权/杀进程）
    private var keepAliveView: View? = null

    override fun onCreate() {
        super.onCreate()
        _serviceInstance.value = this
        Log.d(TAG, "Service created")

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 初始化
        initialize()

        isInitialized = true
    }

    private fun initialize() {
        _animationController = AnimationController(this)
        // 初始化悬浮窗控制器
        _floatingWindowController = FloatingWindowController(this)

        // 初始化唤醒词输入控制器（全屏UI）
        wakeUpInputController = WakeUpInputController(this)

        // 启动保活窗口
        setupKeepAliveWindow()

        // 初始化并绑定唤醒词检测器
        WakeWordDetector.initialize(this)
        // 使用回调替代直接绑定（兼容两种 Service）
        WakeWordDetector.onWakeUpAction = {
            handleWakeUpAction()
        }
        WakeWordDetector.onWakeWordDetected = {
            Log.i(TAG, "Wake word detected! -> starting command mode")
        }
        WakeWordDetector.onCommandReceived = { text ->
            Log.i(TAG, "Wake word command received: \"$text\"")
            // 路由指令给控制器，如果有全屏 UI，由 UI 判断
            if (wakeUpInputController != null) {
                wakeUpInputController?.onCommandReceived(text)
            } else {
                executeCommand(text)
            }
        }
        WakeWordDetector.onError = { error ->
            Log.w(TAG, "WakeWordDetector onError: \"$error\"")
            wakeUpInputController?.onListenError()
        }

        // 如果设置中已启用唤醒词，模型就绪后开始监听
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("wake_up_enabled", false)) {
            SherpaModelManager.runWhenReady(serviceScope, this) {
                WakeWordDetector.startWakeWordMode(this@AutoGLMShizukuService)
            }
        }

        // 启动当前应用监听
        startCurrentAppMonitoring()
    }

    // =========================================================
    // 保活 1×1 悬浮窗
    // =========================================================

    private fun setupKeepAliveWindow() {
        try {
            if (keepAliveView != null) return
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.START or Gravity.TOP
            params.alpha = 0.01f
            val view = View(this)
            wm.addView(view, params)
            keepAliveView = view
            Log.d(TAG, "Keep-alive window added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add keep-alive window", e)
        }
    }

    private fun removeKeepAliveWindow() {
        try {
            keepAliveView?.let {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
                keepAliveView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove keep-alive window", e)
        }
    }

    // =========================================================
    // 唤醒词：Service 端处理方法
    // =========================================================

    /**
     * 唤醒词检测成功后，Service 端的处理逻辑。
     * 显示全屏 WakeUpInputController UI
     */
    fun handleWakeUpAction() {
        Log.d(TAG, "handleWakeUpAction called, wakeUpInputController=${wakeUpInputController != null}")
        // 先立即显示 UI，再并行执行熄屏唤醒（不阻塞 UI 弹出）
        serviceScope.launch(Dispatchers.Main) {
            if (wakeUpInputController != null) {
                Log.d(TAG, "handleWakeUpAction: 调用 show()")
                wakeUpInputController?.show { command ->
                    executeCommand(command)
                }
            } else {
                Log.w(TAG, "handleWakeUpAction: wakeUpInputController 为 null，无法显示 UI")
            }
        }
        wakeUpAndUnlock { }
    }

    /**
     * 执行具体指令（直接发送给大模型处理）
     */
    fun executeCommand(text: String) {
        if (text.isBlank()) return
        serviceScope.launch { voiceCommandFlow.emit(text) }
    }

    /**
     * 开始/停止唤醒词监听（供 SettingsScreen 的开关调用）
     */
    fun startWakeWordListening() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("wake_up_enabled", true).apply()
        serviceScope.launch(Dispatchers.IO) {
            WakeWordDetector.startWakeWordMode(this@AutoGLMShizukuService)
        }
    }

    fun stopWakeWordListening() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("wake_up_enabled", false).apply()
        WakeWordDetector.stopListening()
    }

    /**
     * 更新唤醒词
     */
    fun updateWakeWord(newWord: String) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("wake_word", newWord).apply()
        WakeWordDetector.updateWakeWord(newWord)
    }

    // =========================================================
    // 熄屏唤醒
    // =========================================================

    private fun wakeUpAndUnlock(onComplete: () -> Unit) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "AutoGLMShizuku:WakeUpLock"
                )
                wakeLock.acquire(3000)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onComplete, 100)
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post(onComplete)
            }
        }
    }

    private fun startCurrentAppMonitoring() {
        serviceScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    // mFocusedApp 直接表示当前聚焦的应用，不受 task stack 排序影响
                    // 格式: mFocusedApp=ActivityRecord{hash u0 com.xxx.yyy/.Activity t123}
                    val (success, output) = ShizukuHelper.executeShellCommandWithOutput(
                        "dumpsys window 2>/dev/null | grep -m1 'mFocusedApp'"
                    )
                    if (success && output.isNotBlank()) {
                        val pkg = output.trim()
                            .substringAfter("u0 ", "")
                            .substringBefore("/")
                            .trim()
                        if (pkg.isNotEmpty()) {
                            _currentApp.value = pkg
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get current app: ${e.message}")
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        WakeWordDetector.destroy()
        removeKeepAliveWindow()
        _floatingWindowController = null
        wakeUpInputController?.cleanup()
        wakeUpInputController = null
        _serviceInstance.value = null
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoGLM Shizuku Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AutoGLM Shizuku mode service running"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoGLM Running")
            .setContentText("Shizuku mode active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    // ========== IGLMService 实现 ==========

    override fun isAvailable(): Boolean = isInitialized && ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(this)

    override suspend fun performTap(x: Float, y: Float): Boolean {
        _animationController?.showTapAnimation(x, y, 300)
        return try {
            val result = ShizukuHelper.executeShellCommand("input tap ${x.toInt()} ${y.toInt()}")
            Log.d(TAG, "performTap: ($x, $y) -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performTap failed: ${e.message}")
            false
        }
    }

    override suspend fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean {
        _animationController?.showSwipeAnimation(startX, startY, endX, endY, duration + 100)
        return try {
            // duration in ms, convert to swipe speed (lower = slower)
            val speed = (duration / 10).toInt().coerceIn(10, 1000)
            val result = ShizukuHelper.executeShellCommand("input swipe ${startX.toInt()} ${startY.toInt()} ${endX.toInt()} ${endY.toInt()} $speed")
            Log.d(TAG, "performSwipe: ($startX,$startY) -> ($endX,$endY) duration=$duration -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performSwipe failed: ${e.message}")
            false
        }
    }

    override suspend fun performLongPress(x: Float, y: Float, duration: Long): Boolean {
        return try {
            // Long press via swipe with same start and end
            val result = ShizukuHelper.executeShellCommand("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} ${duration.toInt()}")
            Log.d(TAG, "performLongPress: ($x, $y) duration=$duration -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performLongPress failed: ${e.message}")
            false
        }
    }

    override fun performGlobalBack(): Boolean {
        return try {
            val result = ShizukuHelper.executeShellCommand("input keyevent 4")
            Log.d(TAG, "performGlobalBack: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performGlobalBack failed: ${e.message}")
            false
        }
    }

    override fun performGlobalHome(): Boolean {
        return try {
            val result = ShizukuHelper.executeShellCommand("input keyevent 3")
            Log.d(TAG, "performGlobalHome: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performGlobalHome failed: ${e.message}")
            false
        }
    }

    /**
     * Shizuku 模式文字输入：仅使用内置 AdbIME broadcast，支持清空后输入。
     * 需在设置中启用「AutoGLM 输入」键盘。
     */
    override suspend fun performDirectTextInput(text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            // 从系统获取本应用 IME 的实际 ID（ime 命令对 ID 格式敏感，需用系统返回的格式）
            val imeId = resolveOurImeId()
            Log.d(TAG, "performDirectTextInput: imeId=$imeId")

            val (_, imeOut) = ShizukuHelper.executeShellCommandWithOutput(
                "settings get secure default_input_method"
            )
            val currentIme = imeOut.trim()
            val isOurIme = currentIme.contains("adbkeyboard", ignoreCase = true) ||
                currentIme.contains("androidautoglm", ignoreCase = true)
            Log.d(TAG, "performDirectTextInput: currentIme=$currentIme, isOurIme=$isOurIme")

            var switched = false
            if (!isOurIme) {
                val enableOk = ShizukuHelper.executeShellCommand("ime enable $imeId")
                val setOk = ShizukuHelper.executeShellCommand("ime set $imeId")
                switched = enableOk && setOk
                Log.d(TAG, "performDirectTextInput: ime enable=$enableOk, set=$setOk")
                if (!switched) {
                    Log.e(TAG, "performDirectTextInput: 无法切换 IME。请到 设置→系统→语言和输入→屏幕键盘→管理屏幕键盘 中勾选「AutoGLM 输入」")
                    return@performDirectTextInput false
                }
                // IME 切换后需等待 InputConnection 建立（微信等 app 可能较慢）
                delay(600)
            }

            // 使用 -p 指定包名，确保广播送达本应用；重试一次以应对 InputConnection 建立较慢
            val broadcastPrefix = "am broadcast -p $packageName"
            val escapedMsg = text.replace("\\", "\\\\").replace("\"", "\\\"")
            repeat(2) { attempt ->
                ShizukuHelper.executeShellCommand("$broadcastPrefix -a ADB_CLEAR_TEXT")
                delay(120)
                ShizukuHelper.executeShellCommandWithOutput(
                    "$broadcastPrefix -a ADB_INPUT_TEXT --es msg \"$escapedMsg\""
                )
                Log.d(TAG, "performDirectTextInput: broadcast attempt=${attempt + 1}")
                if (attempt == 0) delay(400)
            }
            val ok = true
            delay(200)

            if (switched && currentIme.isNotBlank() && !currentIme.contains("androidautoglm", ignoreCase = true)) {
                ShizukuHelper.executeShellCommand("ime set $currentIme")
                Log.d(TAG, "performDirectTextInput: restored IME to $currentIme")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "performDirectTextInput failed: ${e.message}", e)
            false
        }
    }

    /**
     * 使用 Shizuku 启动应用
     *
     * 策略：1) am start --user 0 2) resolve-activity + am start -n 3) monkey 回退
     */
    override fun launchApp(packageName: String): Boolean {
        return try {
            var (exitOk, output) = ShizukuHelper.executeShellCommandWithOutput(
                "am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
            )
            var failed = output.contains("Error: Activity not started", ignoreCase = true) ||
                output.contains("unable to resolve Intent", ignoreCase = true)
            var success = exitOk && !failed

            if (!success) {
                val (resolveOk, resolveOut) = ShizukuHelper.executeShellCommandWithOutput(
                    "cmd package resolve-activity --brief --user 0 -c android.intent.category.LAUNCHER $packageName"
                )
                val component = parseResolveActivityComponent(resolveOut)
                if (resolveOk && component != null) {
                    val (startOk, startOut) = ShizukuHelper.executeShellCommandWithOutput("am start --user 0 -n $component")
                    failed = startOut.contains("Error: Activity not started", ignoreCase = true) ||
                        startOut.contains("unable to resolve Intent", ignoreCase = true)
                    success = startOk && !failed
                }
            }

            if (!success) {
                success = ShizukuHelper.executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            }
            Log.d(TAG, "launchApp: $packageName -> $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed: $packageName", e)
            false
        }
    }

    /** 解析本应用 IME 的 ID，优先用 InputMethodManager 获取系统格式 */
    private fun resolveOurImeId(): String {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.enabledInputMethodList?.find { it.packageName == packageName }?.id?.let { id ->
                Log.d(TAG, "resolveOurImeId: from IMM -> $id")
                return id
            }
        }
        val (ok, out) = ShizukuHelper.executeShellCommandWithOutput("ime list -a")
        if (ok && out.isNotBlank()) {
            val idPattern = Regex("""($packageName/[^\s:]+AdbIME[^\s:]*)""")
            idPattern.find(out)?.groupValues?.get(1)?.let { id ->
                Log.d(TAG, "resolveOurImeId: from ime list -> $id")
                return id
            }
        }
        val fallback = "$packageName/com.sidhu.androidautoglm.input.AdbIME"
        Log.d(TAG, "resolveOurImeId: using fallback $fallback")
        return fallback
    }

    /** 从 cmd package resolve-activity 输出解析 component */
    private fun parseResolveActivityComponent(output: String): String? {
        val nameRegex = Regex("""name=([^\s]+/[^\s]+)""")
        nameRegex.find(output)?.groupValues?.get(1)?.takeIf { it.contains("/") }?.let { return it }
        return output.lines().lastOrNull { it.contains("/") && it.trim().matches(Regex("""\S+/\S+""")) }?.trim()
    }

    override fun goHome() {
        serviceScope.launch {
            try {
                ShizukuHelper.executeShellCommand("input keyevent 3")
                Log.d(TAG, "goHome: executed via Shizuku")
            } catch (e: Exception) {
                Log.e(TAG, "goHome failed: ${e.message}")
            }
        }
    }

    override suspend fun takeScreenshot(timeoutMs: Long): Bitmap? {
        Log.d(TAG, "takeScreenshot: start, timeoutMs=$timeoutMs")
        // 准备阶段不隐藏；仅在真正执行 screencap 时隐藏
        val prepared = ScreenshotHelper.prepareScreencap() ?: return null
        val rawBytes = _floatingWindowController?.useWindowSuspension {
            delay(30)
            ScreenshotHelper.executeScreencap(prepared)
        } ?: run {
            delay(30)
            ScreenshotHelper.executeScreencap(prepared)
        }
        return rawBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    override fun updateFloatingStatus(text: String) {
        serviceScope.launch {
            _floatingWindowController?.updateStatus(text)
        }
    }

    override fun resetFloatingWindowForNewTask() {
        serviceScope.launch {
            if (_floatingWindowController == null) {
                _floatingWindowController = FloatingWindowController(this@AutoGLMShizukuService)
            }
            _floatingWindowController?.resetForNewTask()
        }
    }

    override fun hideFloatingWindow() {
        serviceScope.launch {
            _floatingWindowController?.removeAndHide()
        }
    }

    override fun dismissFloatingWindow() {
        serviceScope.launch {
            _floatingWindowController?.dismiss()
        }
    }

    override fun setTaskRunning(isRunning: Boolean) {
        serviceScope.launch {
            _floatingWindowController?.setTaskRunning(isRunning)
        }
    }

    override fun markTaskCompleted() {
        serviceScope.launch {
            _floatingWindowController?.markTaskCompleted()
        }
    }
}
