package com.sidhu.androidautoglm

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
import java.util.Locale

class MainActivity : ComponentActivity() {
    
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved locale before super.onCreate
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedLang = prefs.getString("language_code", "zh") ?: "zh"
        val locale = if (savedLang == "zh") Locale.CHINESE else Locale.ENGLISH
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val uiState by viewModel.uiState.collectAsState()

                    NavHost(navController = navController, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                viewModel = viewModel,
                                onOpenSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                apiKey = uiState.apiKey,
                                baseUrl = uiState.baseUrl,
                                isGemini = uiState.isGemini,
                                modelName = uiState.modelName,
                                currentLanguage = savedLang,
                                onLanguageChange = { newLang ->
                                    val editor = prefs.edit()
                                    editor.putString("language_code", newLang)
                                    editor.apply()
                                    // Recreate activity to apply locale change
                                    finish()
                                    startActivity(intent)
                                },
                                onSave = { newKey, newBaseUrl, newIsGemini, newModelName ->
                                    viewModel.updateSettings(newKey, newBaseUrl, newIsGemini, newModelName)
                                },
                                onBack = { navController.popBackStack() },
                                onOpenDocumentation = { navController.navigate("documentation") }
                            )
                        }
                        composable("documentation") {
                            MarkdownViewerScreen(
                                initialLanguage = savedLang,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setAppLocale(languageCode: String) {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        prefs.edit().putString("language_code", languageCode).apply()
        recreate()
    }
}