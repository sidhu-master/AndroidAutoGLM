package com.sidhu.androidautoglm

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sidhu.androidautoglm.AutoGLMShizukuService
import com.sidhu.androidautoglm.ui.ChatScreen
import com.sidhu.androidautoglm.ui.ChatViewModel
import com.sidhu.androidautoglm.ui.SettingsScreen
import com.sidhu.androidautoglm.ui.MarkdownViewerScreen
import com.sidhu.androidautoglm.ui.WebViewScreen
import com.sidhu.androidautoglm.ui.ConversationListScreen
import com.sidhu.androidautoglm.ui.ConversationListViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidhu.androidautoglm.network.UpdateInfo
import com.sidhu.androidautoglm.utils.UpdateManager
import com.sidhu.androidautoglm.utils.ShizukuHelper
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import rikka.shizuku.Shizuku
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 0xCA07
    }

    private val viewModel: ChatViewModel by viewModels()

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainActivity", "Shizuku permission granted, starting service")
                    AutoGLMShizukuService.startService(this@MainActivity)
                    viewModel.checkShizukuConnection(this@MainActivity)
                    android.widget.Toast.makeText(this@MainActivity, R.string.shizuku_permission_granted_toast, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this@MainActivity, R.string.shizuku_permission_denied_toast, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val voiceCommandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sidhu.androidautoglm.ACTION_VOICE_COMMAND_BROADCAST") {
                val text = intent.getStringExtra("voice_text")
                Log.d("AutoGLM_Trace", "BroadcastReceiver received voice command: $text")
                if (!text.isNullOrBlank()) {
                    viewModel.sendMessage(text, isVoiceInput = true)
                    resultCode = android.app.Activity.RESULT_OK
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            android.widget.Toast.makeText(this, getString(R.string.microphone_permission_granted_toast), android.widget.Toast.LENGTH_SHORT).show()
            // 若为唤醒词触发的权限请求，授权成功即开启唤醒词
            if (pendingWakeWordEnable) {
                pendingWakeWordEnable = false
                enableWakeWordAfterPermissionGranted()
            }
        } else {
            pendingWakeWordEnable = false
            android.widget.Toast.makeText(this, getString(R.string.microphone_permission_denied_toast), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingWakeWordEnable = false

    private fun enableWakeWordAfterPermissionGranted() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        prefs.edit().putBoolean("wake_up_enabled", true).apply()
        isWakeWordEnabledState.value = true
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.sidhu.androidautoglm.utils.SherpaModelManager.initModel(this@MainActivity)
        }
        AutoGLMShizukuService.getInstance()?.startWakeWordListening()
    }

    private val isWakeWordEnabledState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved locale before super.onCreate
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedLang = prefs.getString("language_code", "zh") ?: "zh"

        // 读取唤醒词设置
        isWakeWordEnabledState.value = prefs.getBoolean("wake_up_enabled", false)
        var isWakeWordEnabled by isWakeWordEnabledState
        var wakeWord by mutableStateOf(prefs.getString("wake_word", "皮皮虾") ?: "皮皮虾")
        // 读取Shizuku模式设置
        var isShizukuModeEnabled by mutableStateOf(prefs.getBoolean("shizuku_mode_enabled", false))

        // 如果 Shizuku 模式已启用，启动服务
        if (isShizukuModeEnabled) {
            Log.d("MainActivity", "Shizuku mode enabled on startup, starting service")
            AutoGLMShizukuService.startService(this)
        }

        val locale = if (savedLang == "zh") Locale.CHINESE else Locale.ENGLISH
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        
        handleIntent(intent)
        
        // Register Broadcast Receiver for background voice commands
        val filter = android.content.IntentFilter("com.sidhu.androidautoglm.ACTION_VOICE_COMMAND_BROADCAST")
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            voiceCommandReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Register Shizuku permission result listener (app must request to appear in Shizuku's auth list)
        try {
            if (!Shizuku.isPreV11()) {
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Shizuku addRequestPermissionResultListener failed", e)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val uiState by viewModel.uiState.collectAsState()

                    // Auto Check Update
                    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                    LaunchedEffect(Unit) {
                        UpdateManager.checkUpdate(
                            context = this@MainActivity,
                            onUpdateAvailable = { info -> updateInfo = info }
                        )
                    }

                    NavHost(navController = navController, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                viewModel = viewModel,
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenConversationList = { navController.navigate("conversation_list") }
                            )
                        }
                        composable("conversation_list") {
                            val conversationListViewModel: ConversationListViewModel = viewModel()
                            ConversationListScreen(
                                onConversationSelected = { conversationId ->
                                    viewModel.loadConversation(conversationId)
                                    navController.popBackStack()
                                },
                                onNewConversation = {
                                    viewModel.createNewConversation()
                                    navController.popBackStack()
                                },
                                onBack = { navController.popBackStack() },
                                viewModel = conversationListViewModel
                            )
                        }
                        composable("settings") {
                            // Check battery status when entering settings or resuming
                            DisposableEffect(Unit) {
                                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                        viewModel.checkBatteryOptimization(this@MainActivity)
                                    }
                                }
                                this@MainActivity.lifecycle.addObserver(observer)
                                onDispose {
                                    this@MainActivity.lifecycle.removeObserver(observer)
                                }
                            }

                            LaunchedEffect(Unit) {
                                viewModel.checkBatteryOptimization(this@MainActivity)
                            }

                            // 应用保活关闭时自动关闭语音唤醒
                            LaunchedEffect(uiState.missingBatteryExemption) {
                                if (uiState.missingBatteryExemption && isWakeWordEnabled) {
                                    isWakeWordEnabled = false
                                    prefs.edit().putBoolean("wake_up_enabled", false).apply()
                                    AutoGLMShizukuService.getInstance()?.stopWakeWordListening()
                                }
                            }

                            SettingsScreen(
                                apiKey = uiState.apiKey,
                                baseUrl = uiState.baseUrl,
                                isGemini = uiState.isGemini,
                                modelName = uiState.modelName,
                                masterApiKey = uiState.masterApiKey,
                                masterBaseUrl = uiState.masterBaseUrl,
                                masterIsGemini = uiState.masterIsGemini,
                                masterModelName = uiState.masterModelName,
                                subUseMasterConfig = uiState.subUseMasterConfig,
                                subApiKey = uiState.subApiKey,
                                subBaseUrl = uiState.subBaseUrl,
                                subIsGemini = uiState.subIsGemini,
                                subModelName = uiState.subModelName,
                                onSaveFull = { mKey, mBase, mGemini, mModel, subUseMaster, sKey, sBase, sGemini, sModel ->
                                    viewModel.updateSettingsFull(mKey, mBase, mGemini, mModel, subUseMaster, sKey, sBase, sGemini, sModel)
                                },
                                appUpdateInfo = updateInfo,
                                currentLanguage = savedLang,
                                onLanguageChange = { newLang ->
                                    val editor = prefs.edit()
                                    editor.putString("language_code", newLang)
                                    editor.apply()
                                    // Recreate activity to apply locale change
                                    finish()
                                    startActivity(intent)
                                },
                                isBatteryOptimizationIgnored = !uiState.missingBatteryExemption,
                                onBatteryOptimizationToggle = { wantEnabled ->
                                    if (wantEnabled) {
                                        // 开启：弹出请求豁免对话框
                                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:$packageName")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        startActivity(intent)
                                    } else {
                                        // 关闭：跳转到电池详情页，用户手动取消豁免
                                        val batteryDetailIntent = Intent().apply {
                                            component = ComponentName("com.android.settings", "com.android.settings.fuelgauge.AdvancedPowerUsageDetailActivity")
                                            data = Uri.parse("package:$packageName")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        try {
                                            startActivity(batteryDetailIntent)
                                        } catch (_: Exception) {
                                            val fallback = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:$packageName")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            startActivity(fallback)
                                        }
                                    }
                                },
                                onSave = { newKey, newBaseUrl, newIsGemini, newModelName ->
                                    viewModel.updateSettings(newKey, newBaseUrl, newIsGemini, newModelName)
                                },
                                onBack = { navController.popBackStack() },
                                onOpenDocumentation = { navController.navigate("documentation") },
                                onOpenUrl = { url ->
                                    val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                                    navController.navigate("webview/$encodedUrl")
                                },
                                // 唤醒词参数
                                isWakeWordEnabled = isWakeWordEnabled,
                                onWakeWordToggle = { enabled ->
                                    // 检查麦克风权限
                                    if (enabled && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        pendingWakeWordEnable = true
                                        requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        return@SettingsScreen
                                    }
                                    isWakeWordEnabled = enabled
                                    prefs.edit().putBoolean("wake_up_enabled", enabled).apply()
                                    if (enabled) {
                                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            com.sidhu.androidautoglm.utils.SherpaModelManager.initModel(this@MainActivity)
                                        }
                                    }
                                    // 通知 Shizuku 服务启动/停止唤醒词
                                    val shizukuService = AutoGLMShizukuService.getInstance()
                                    if (enabled) {
                                        shizukuService?.startWakeWordListening()
                                    } else {
                                        shizukuService?.stopWakeWordListening()
                                    }
                                },
                                wakeWord = wakeWord,
                                onWakeWordChange = { newWord ->
                                    wakeWord = newWord
                                    prefs.edit().putString("wake_word", newWord).apply()
                                    // 通知 Shizuku 服务更新唤醒词
                                    val shizukuService = AutoGLMShizukuService.getInstance()
                                    shizukuService?.stopWakeWordListening()
                                    com.sidhu.androidautoglm.utils.WakeWordDetector.updateWakeWord(newWord)
                                    if (isWakeWordEnabled) {
                                        shizukuService?.startWakeWordListening()
                                    }
                                },
                                // Shizuku模式参数
                                isShizukuModeEnabled = isShizukuModeEnabled,
                                onShizukuModeToggle = { enabled ->
                                    isShizukuModeEnabled = enabled
                                    prefs.edit().putBoolean("shizuku_mode_enabled", enabled).apply()

                                    if (enabled) {
                                        when {
                                            !ShizukuHelper.isShizukuAvailable() -> {
                                                // Shizuku 未安装或未运行，打开 Shizuku 应用
                                                try {
                                                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                                    if (intent != null) {
                                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        startActivity(intent)
                                                        android.widget.Toast.makeText(this@MainActivity, R.string.shizuku_open_app_toast, android.widget.Toast.LENGTH_LONG).show()
                                                    } else {
                                                        val uri = Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                                                        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                            ShizukuHelper.checkPermission(this@MainActivity) -> {
                                                // 已有授权，直接启动服务
                                                Log.d("MainActivity", "Shizuku permission OK, starting service")
                                                AutoGLMShizukuService.startService(this@MainActivity)
                                                viewModel.checkShizukuConnection(this@MainActivity)
                                            }
                                            else -> {
                                                // 需要请求授权，会弹出 Shizuku 授权界面，应用将出现在 Shizuku 授权列表中
                                                try {
                                                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                                                } catch (e: Exception) {
                                                    Log.e("MainActivity", "Shizuku requestPermission failed", e)
                                                    android.widget.Toast.makeText(this@MainActivity, R.string.shizuku_request_failed_toast, android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    } else {
                                        Log.d("MainActivity", "Stopping Shizuku service")
                                        AutoGLMShizukuService.stopService(this@MainActivity)
                                    }
                                }
                            )
                        }
                        composable("documentation") {
                            MarkdownViewerScreen(
                                initialLanguage = savedLang,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "webview/{url}",
                            arguments = listOf(navArgument("url") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val url = backStackEntry.arguments?.getString("url") ?: ""
                            WebViewScreen(
                                url = url,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }

        // Handle Floating Window Visibility based on App Lifecycle using State Machine
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            val service = AutoGLMShizukuService.getInstance()
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    val state = viewModel.uiState.value
                    if (state.isRunning || state.isLoading) {
                        viewModel.stopTask()
                    }
                    lifecycleScope.launch {
                        service?.floatingWindowController?.forceDismiss()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    val shown = service?.floatingWindowController?.handleAppPaused() ?: false
                    Log.d("MainActivity", "ON_PAUSE: Window shown=$shown")
                }
                else -> {}
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(voiceCommandReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "ACTION_VOICE_SEND") {
            val text = intent.getStringExtra("voice_text")
            Log.d("AutoGLM_Trace", "handleIntent received voice command: $text")
            if (!text.isNullOrBlank()) {
                viewModel.sendMessage(text, isVoiceInput = true)
                moveTaskToBack(true)
                // Clear the intent action so it doesn't trigger again on rotation/recreation if we were to rely on intent state
                intent.action = "" 
            }
        } else if (intent?.action == "ACTION_REQUEST_MIC_PERMISSION") {
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            intent.action = ""
        }
    }

}
