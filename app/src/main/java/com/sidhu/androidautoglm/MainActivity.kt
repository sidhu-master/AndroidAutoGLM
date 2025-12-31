package com.sidhu.androidautoglm

import android.content.Context
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
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : ComponentActivity() {
    
    private val viewModel: ChatViewModel by viewModels()

    private val voiceCommandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sidhu.androidautoglm.ACTION_VOICE_COMMAND_BROADCAST") {
                val text = intent.getStringExtra("voice_text")
                Log.d("AutoGLM_Trace", "BroadcastReceiver received voice command: $text")
                if (!text.isNullOrBlank()) {
                    viewModel.sendMessage(text)
                    resultCode = android.app.Activity.RESULT_OK
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, maybe move task to back or show success
            android.widget.Toast.makeText(this, getString(R.string.microphone_permission_granted_toast), android.widget.Toast.LENGTH_SHORT).show()
            // Optional: immediately hide activity if it was just for permission?
            // moveTaskToBack(true) // User might want to stay in app, let them decide or just let standard lifecycle handle it
        } else {
            android.widget.Toast.makeText(this, getString(R.string.microphone_permission_denied_toast), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved locale before super.onCreate
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedLang = prefs.getString("language_code", "zh") ?: "zh"
        val locale = if (savedLang == "zh") Locale.CHINESE else Locale.ENGLISH
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        
        handleIntent(intent)
        
        // Register Broadcast Receiver for background voice commands
        val filter = android.content.IntentFilter("com.sidhu.androidautoglm.ACTION_VOICE_COMMAND_BROADCAST")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(voiceCommandReceiver, filter)
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

                            SettingsScreen(
                                apiKey = uiState.apiKey,
                                baseUrl = uiState.baseUrl,
                                isGemini = uiState.isGemini,
                                modelName = uiState.modelName,
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
                                onRequestBatteryOptimization = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    val isIgnored = !uiState.missingBatteryExemption
                                    val intent = if (isIgnored) {
                                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    } else {
                                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:$packageName")
                                        }
                                    }
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(intent)
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

        // Handle Floating Window Visibility based on App Lifecycle
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            val service = AutoGLMService.getInstance()
            if (service != null) {
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                        // App is visible, hide floating window
                        service.hideFloatingWindow()
                        // Messages are automatically updated via reactive Flow when database changes
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                        // App is backgrounded, show floating window
                        // Note: If user has dismissed the window via "Return to App" button,
                        // the controller will be null and a new one won't be created
                        // until a new task starts (with forceCreate=true)
                        service.showFloatingWindow(
                            onStop = { viewModel.stopTask() },
                            isRunning = service.isTaskRunning.value
                        )
                    }
                    else -> {}
                }
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
                viewModel.sendMessage(text)
                moveTaskToBack(true)
                // Clear the intent action so it doesn't trigger again on rotation/recreation if we were to rely on intent state
                intent.action = "" 
            }
        } else if (intent?.action == "ACTION_REQUEST_MIC_PERMISSION") {
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            intent.action = ""
        }
    }

    private fun setAppLocale(languageCode: String) {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        prefs.edit().putString("language_code", languageCode).apply()
        recreate()
    }
}
