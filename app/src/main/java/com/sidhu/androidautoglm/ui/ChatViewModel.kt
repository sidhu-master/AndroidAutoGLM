package com.sidhu.androidautoglm.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sidhu.androidautoglm.action.Action
import com.sidhu.androidautoglm.action.ActionExecutor
import com.sidhu.androidautoglm.action.ActionParser
import com.sidhu.androidautoglm.network.ContentItem
import com.sidhu.androidautoglm.network.ImageUrl
import com.sidhu.androidautoglm.network.Message
import com.sidhu.androidautoglm.network.ModelClient
import com.sidhu.androidautoglm.AutoGLMService
import com.sidhu.androidautoglm.R
import java.text.SimpleDateFormat
import java.util.Date
import android.os.Build
import android.provider.Settings
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.content.ComponentName
import android.text.TextUtils

import com.sidhu.androidautoglm.BuildConfig

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isRunning: Boolean = false,
    val error: String? = null,
    val missingAccessibilityService: Boolean = false,
    val missingOverlayPermission: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4", // Official ZhipuAI Endpoint
    val isGemini: Boolean = false,
    val modelName: String = "autoglm-phone"
)

data class UiMessage(
    val role: String,
    val content: String,
    val image: Bitmap? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private var modelClient: ModelClient? = null

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    init {
        // Load API Key: Prefer saved key, fallback to BuildConfig default
        val savedKeyRaw = prefs.getString("api_key", "") ?: ""
        val savedKey = if (savedKeyRaw.isNotBlank()) savedKeyRaw else BuildConfig.DEFAULT_API_KEY
        
        val savedBaseUrl = prefs.getString("base_url", "https://open.bigmodel.cn/api/paas/v4") ?: "https://open.bigmodel.cn/api/paas/v4"
        val savedIsGemini = prefs.getBoolean("is_gemini", false)
        val savedModelName = prefs.getString("model_name", "autoglm-phone") ?: "autoglm-phone"
        
        _uiState.value = _uiState.value.copy(
            apiKey = savedKey,
            baseUrl = savedBaseUrl,
            isGemini = savedIsGemini,
            modelName = savedModelName
        )

        if (savedKey.isNotEmpty()) {
            modelClient = ModelClient(savedBaseUrl, savedKey, savedModelName, savedIsGemini)
        }
        
        // Observe service connection status
        viewModelScope.launch {
            AutoGLMService.serviceInstance.collect { service ->
                if (service != null) {
                    // Service connected, clear error if it was about accessibility
                    val currentError = _uiState.value.error
                    if (currentError != null && (currentError.contains("无障碍服务") || currentError.contains("Accessibility Service"))) {
                        _uiState.value = _uiState.value.copy(error = null)
                    }
                }
            }
        }
    }

    // Dynamic accessor for ActionExecutor
    private val actionExecutor: ActionExecutor?
        get() = AutoGLMService.getInstance()?.let { ActionExecutor(it) }
    
    // Debug Mode Flag - set to true to bypass permission checks and service requirements
    private val DEBUG_MODE = false

    // Conversation history for the API
    private val apiHistory = mutableListOf<Message>()

    fun updateSettings(apiKey: String, baseUrl: String, isGemini: Boolean, modelName: String) {
        val finalBaseUrl = if (baseUrl.isBlank()) {
            if (isGemini) "https://generativelanguage.googleapis.com" else "https://open.bigmodel.cn/api/paas/v4"
        } else baseUrl
        
        val finalModelName = if (modelName.isBlank()) {
            if (isGemini) "gemini-2.0-flash-exp" else "autoglm-phone"
        } else modelName
        
        // Save to SharedPreferences
        prefs.edit().apply {
            // If the key is the same as the default key, save empty string to indicate "use default"
            // Or if user explicitly cleared it (empty string), it also means use default.
            val keyToSave = if (apiKey == BuildConfig.DEFAULT_API_KEY) "" else apiKey
            putString("api_key", keyToSave)
            putString("base_url", finalBaseUrl)
            putBoolean("is_gemini", isGemini)
            putString("model_name", finalModelName)
            apply()
        }

        // Update UI State
        // IMPORTANT: In UI State, we must reflect the ACTUAL usable key (Default or Custom), 
        // not the empty string from storage, so that SettingsScreen can detect it matches DEFAULT_API_KEY.
        val effectiveKey = if (apiKey.isBlank()) BuildConfig.DEFAULT_API_KEY else apiKey
        
        _uiState.value = _uiState.value.copy(
            apiKey = effectiveKey,
            baseUrl = finalBaseUrl,
            isGemini = isGemini,
            modelName = finalModelName,
            error = null // Clear any previous errors
        )

        // Re-initialize ModelClient
        if (effectiveKey.isNotEmpty()) {
            modelClient = ModelClient(finalBaseUrl, effectiveKey, finalModelName, isGemini)
        }
    }

    fun updateApiKey(apiKey: String) {
        // Deprecated, use updateSettings instead but keeping for compatibility if needed temporarily
        updateSettings(apiKey, _uiState.value.baseUrl, _uiState.value.isGemini, _uiState.value.modelName)
    }

    fun checkServiceStatus() {
        val context = getApplication<Application>()
        if (isAccessibilityServiceEnabled(context, AutoGLMService::class.java)) {
            _uiState.value = _uiState.value.copy(missingAccessibilityService = false)
            val currentError = _uiState.value.error
            if (currentError != null && (currentError.contains("无障碍服务") || currentError.contains("Accessibility Service"))) {
                _uiState.value = _uiState.value.copy(error = null)
            }
        } else {
             _uiState.value = _uiState.value.copy(missingAccessibilityService = true)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName)
                return true
        }
        return false
    }

    fun checkOverlayPermission(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            _uiState.value = _uiState.value.copy(missingOverlayPermission = true)
        } else {
            _uiState.value = _uiState.value.copy(missingOverlayPermission = false)
            val currentError = _uiState.value.error
            if (currentError != null && (currentError.contains("悬浮窗权限") || currentError.contains("Overlay Permission"))) {
                _uiState.value = _uiState.value.copy(error = null)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun stopTask() {
        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
        val service = AutoGLMService.getInstance()
        service?.setTaskRunning(false)
        service?.updateFloatingStatus(getApplication<Application>().getString(R.string.status_stopped))
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(messages = emptyList())
        apiHistory.clear()
        
        // Add welcome message if needed, or keep empty
        // _uiState.value = _uiState.value.copy(messages = listOf(UiMessage("assistant", getApplication<Application>().getString(R.string.welcome_message))))
    }

    fun sendMessage(text: String) {
        Log.d("AutoGLM_Debug", "sendMessage called with text: $text")
        if (text.isBlank()) return
        
        if (modelClient == null) {
            Log.d("AutoGLM_Debug", "modelClient is null, initializing...")
            // Try to init with current state if not init
             modelClient = ModelClient(
                 _uiState.value.baseUrl, 
                 _uiState.value.apiKey, 
                 _uiState.value.modelName,
                 _uiState.value.isGemini
             )
             Log.d("AutoGLM_Debug", "modelClient initialized. isGemini: ${_uiState.value.isGemini}")
        } else {
             Log.d("AutoGLM_Debug", "modelClient already initialized")
        }

        if (_uiState.value.apiKey.isBlank()) {
            Log.d("AutoGLM_Debug", "API Key is blank")
            _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.error_api_key_missing))
            return
        }

        val service = AutoGLMService.getInstance()
        Log.d("AutoGLM_Debug", "Service instance: $service, DEBUG_MODE: $DEBUG_MODE")
        if (!DEBUG_MODE) {
            if (service == null) {
                val context = getApplication<Application>()
                if (isAccessibilityServiceEnabled(context, AutoGLMService::class.java)) {
                     _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.error_service_not_connected))
                } else {
                     _uiState.value = _uiState.value.copy(missingAccessibilityService = true)
                }
                return
            }

            // Check overlay permission again before starting
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(getApplication())) {
                 _uiState.value = _uiState.value.copy(missingOverlayPermission = true)
                 return
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            Log.d("AutoGLM_Debug", "Coroutine started")
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + UiMessage("user", text),
                isLoading = true,
                isRunning = true,
                error = null
            )
            
            apiHistory.clear()
            // Add System Prompt with Date matching Python logic
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
            val dateStr = getApplication<Application>().getString(R.string.prompt_date_prefix) + dateFormat.format(Date())
            apiHistory.add(Message("system", dateStr + "\n" + ModelClient.SYSTEM_PROMPT))

            var currentPrompt = text
            var step = 0
            val maxSteps = 20
            
            if (!DEBUG_MODE && service != null) {
                // Show floating window and minimize app
                withContext(Dispatchers.Main) {
                    service.showFloatingWindow {
                        stopTask()
                    }
                    service.setTaskRunning(true)
                    service.goHome()
                }
                delay(1000) // Wait for animation and window to appear
            }

            var isFinished = false

            try {
                while (_uiState.value.isRunning && step < maxSteps) {
                    step++
                    Log.d("AutoGLM_Debug", "Step: $step")
                    
                    if (!DEBUG_MODE && service != null) {
                        service.updateFloatingStatus(getApplication<Application>().getString(R.string.status_thinking))
                    }
                    
                    // 1. Take Screenshot
                    Log.d("AutoGLM_Debug", "Taking screenshot...")
                    val screenshot = if (DEBUG_MODE) {
                        Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                    } else {
                        service?.takeScreenshot()
                    }

                    if (screenshot == null) {
                        Log.e("AutoGLM_Debug", "Screenshot failed")
                        postError(getApplication<Application>().getString(R.string.error_screenshot_failed))
                        break
                    }
                    Log.d("AutoGLM_Debug", "Screenshot taken: ${screenshot.width}x${screenshot.height}")
                    
                    // Use service dimensions for consistency with coordinate system
                    val screenWidth = if (DEBUG_MODE) 1080 else service?.getScreenWidth() ?: 1080
                    val screenHeight = if (DEBUG_MODE) 2400 else service?.getScreenHeight() ?: 2400
                    
                    Log.d("ChatViewModel", "Screenshot size: ${screenshot.width}x${screenshot.height}")
                    Log.d("ChatViewModel", "Service screen size: ${screenWidth}x${screenHeight}")

                    // 2. Build User Message
                    val currentApp = if (DEBUG_MODE) "DebugApp" else (service?.currentApp?.value ?: "Unknown")
                    val screenInfo = "{\"current_app\": \"$currentApp\"}"
                    
                    val textPrompt = if (step == 1) {
                        "$currentPrompt\n\n$screenInfo"
                    } else {
                        "** Screen Info **\n\n$screenInfo"
                    }
                    
                    val userContentItems = mutableListOf<ContentItem>()
                    userContentItems.add(ContentItem("text", text = textPrompt))
                    userContentItems.add(ContentItem("image_url", imageUrl = ImageUrl("data:image/png;base64,${ModelClient.bitmapToBase64(screenshot)}")))
                    
                    val userMessage = Message("user", userContentItems)
                    apiHistory.add(userMessage)

                    // 3. Call API
                    Log.d("AutoGLM_Debug", "Sending request to ModelClient...")
                    val responseText = modelClient?.sendRequest(apiHistory, screenshot) ?: "Error: Client null"
                    Log.d("AutoGLM_Debug", "Response received: ${responseText.take(100)}...")
                    
                    if (responseText.startsWith("Error")) {
                        Log.e("AutoGLM_Debug", "API Error: $responseText")
                        postError(responseText)
                        break
                    }
                    
                    // Parse response parts
                    val (thinking, actionStr) = ActionParser.parseResponseParts(responseText)
                    
                    Log.i("AutoGLM_Log", "\n==================================================")
                    Log.i("AutoGLM_Log", "💭 思考过程:")
                    Log.i("AutoGLM_Log", thinking)
                    Log.i("AutoGLM_Log", "🎯 执行动作:")
                    Log.i("AutoGLM_Log", actionStr)
                    Log.i("AutoGLM_Log", "==================================================")

                    // Add Assistant response to history
                    apiHistory.add(Message("assistant", "<think>$thinking</think><answer>$actionStr</answer>"))
                    
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + UiMessage("assistant", responseText)
                    )

                    // If DEBUG_MODE, stop here after one round
                    if (DEBUG_MODE) {
                        Log.d("AutoGLM_Debug", "DEBUG_MODE enabled, stopping after one round")
                        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                        break
                    }

                    // 4. Parse Action
                    val action = ActionParser.parse(responseText, screenWidth, screenHeight)
                    
                    // Update Floating Window Status with friendly description
                    service?.updateFloatingStatus(getActionDescription(action))
                    
                    // 5. Execute Action
                    val executor = actionExecutor
                    if (executor == null) {
                         postError(getApplication<Application>().getString(R.string.error_executor_null))
                         break
                    }
                    
                    val success = executor.execute(action)
                    
                    if (action is Action.Finish) {
                        isFinished = true
                        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                        service?.setTaskRunning(false)
                        service?.updateFloatingStatus(getApplication<Application>().getString(R.string.action_finish))
                        break
                    }
                    
                    if (!success) {
                        apiHistory.add(Message("user", getApplication<Application>().getString(R.string.error_last_action_failed)))
                    }
                    
                    removeImagesFromHistory()
                    
                    delay(2000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AutoGLM_Debug", "Exception in sendMessage loop: ${e.message}", e)
                postError(getApplication<Application>().getString(R.string.error_runtime_exception, e.message))
            }
            
            if (!isFinished && _uiState.value.isRunning) {
                _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                if (!DEBUG_MODE) {
                    service?.setTaskRunning(false)
                    if (step >= maxSteps) {
                        service?.updateFloatingStatus(getApplication<Application>().getString(R.string.error_task_terminated_max_steps))
                    }
                }
            }
        }
    }

    private fun getActionDescription(action: Action): String {
        val context = getApplication<Application>()
        return when (action) {
            is Action.Tap -> context.getString(R.string.action_tap)
            is Action.DoubleTap -> context.getString(R.string.action_double_tap)
            is Action.LongPress -> context.getString(R.string.action_long_press)
            is Action.Swipe -> context.getString(R.string.action_swipe)
            is Action.Type -> context.getString(R.string.action_type, action.text)
            is Action.Launch -> context.getString(R.string.action_launch, action.appName)
            is Action.Back -> context.getString(R.string.action_back)
            is Action.Home -> context.getString(R.string.action_home)
            is Action.Wait -> context.getString(R.string.action_wait)
            is Action.Finish -> context.getString(R.string.action_finish)
            is Action.Error -> context.getString(R.string.action_error, action.reason)
            else -> context.getString(R.string.action_unknown)
        }
    }
    
    private fun postError(msg: String) {
        _uiState.value = _uiState.value.copy(error = msg, isRunning = false, isLoading = false)
        val service = AutoGLMService.getInstance()
        service?.setTaskRunning(false)
        service?.updateFloatingStatus(getApplication<Application>().getString(R.string.action_error, msg))
    }

    private fun removeImagesFromHistory() {
        // Python logic: Remove images from the last user message to save context space
        // The history is: [..., User(Image+Text), Assistant(Text)]
        // So we look at the second to last item.
        if (apiHistory.size < 2) return

        val lastUserIndex = apiHistory.size - 2
        if (lastUserIndex < 0) return

        val lastUserMsg = apiHistory[lastUserIndex]
        if (lastUserMsg.role == "user" && lastUserMsg.content is List<*>) {
            try {
                @Suppress("UNCHECKED_CAST")
                val contentList = lastUserMsg.content as List<ContentItem>
                // Filter out image items, keep only text
                val textOnlyList = contentList.filter { it.type == "text" }
                
                // Replace the message in history with the text-only version
                apiHistory[lastUserIndex] = lastUserMsg.copy(content = textOnlyList)
                // Log.d("ChatViewModel", "Removed image from history at index $lastUserIndex")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to remove image from history", e)
            }
        }
    }
}
