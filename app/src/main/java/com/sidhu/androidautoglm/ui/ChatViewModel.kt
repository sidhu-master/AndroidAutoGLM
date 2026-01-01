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
import com.sidhu.androidautoglm.action.AppMapper
import com.sidhu.androidautoglm.network.ContentItem
import com.sidhu.androidautoglm.network.ImageUrl
import com.sidhu.androidautoglm.network.Message
import com.sidhu.androidautoglm.network.ModelClient
import com.sidhu.androidautoglm.AutoGLMService
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.utils.AppStateTracker
import com.sidhu.androidautoglm.utils.DisplayUtils
import com.sidhu.androidautoglm.data.TaskEndState
import com.sidhu.androidautoglm.data.entity.Conversation as DbConversation
import java.text.SimpleDateFormat
import java.util.Date
import android.os.Build
import android.provider.Settings
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

import android.content.ComponentName
import android.text.TextUtils

import com.sidhu.androidautoglm.BuildConfig
import com.sidhu.androidautoglm.data.AppDatabase
import com.sidhu.androidautoglm.data.ImageStorage
import com.sidhu.androidautoglm.data.repository.ConversationRepository
import com.sidhu.androidautoglm.ui.model.toUiMessages
import com.sidhu.androidautoglm.ui.model.FormattedContent
import com.sidhu.androidautoglm.usecase.ConversationUseCase

/**
 * Nested state classes for better organization
 * Each class groups related state properties
 */
data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isRunning: Boolean = false,
    val error: String? = null,
    val missingAccessibilityService: Boolean = false,
    val missingOverlayPermission: Boolean = false,
    val missingBatteryExemption: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val isGemini: Boolean = false,
    val modelName: String = "autoglm-phone",
    val activeConversationId: Long? = null,
    val currentConversation: DbConversation? = null
) {
    // Convenience properties for grouping related state
    val taskState: TaskState get() = TaskState(isRunning, isLoading, error)
    val conversationState: ConversationState get() = ConversationState(activeConversationId, currentConversation, messages)
    val permissionState: PermissionState get() = PermissionState(missingAccessibilityService, missingOverlayPermission, missingBatteryExemption)
    val settingsState: SettingsState get() = SettingsState(apiKey, baseUrl, isGemini, modelName)

    // Helper methods for updating nested state
    fun withTaskState(update: TaskState.() -> TaskState): ChatUiState {
        val newTaskState = taskState.update()
        return copy(
            isRunning = newTaskState.isRunning,
            isLoading = newTaskState.isLoading,
            error = newTaskState.error
        )
    }

    fun withConversationState(update: ConversationState.() -> ConversationState): ChatUiState {
        val newConvState = conversationState.update()
        return copy(
            activeConversationId = newConvState.activeConversationId,
            currentConversation = newConvState.currentConversation,
            messages = newConvState.messages
        )
    }

    fun withPermissionState(update: PermissionState.() -> PermissionState): ChatUiState {
        val newPermState = permissionState.update()
        return copy(
            missingAccessibilityService = newPermState.missingAccessibilityService,
            missingOverlayPermission = newPermState.missingOverlayPermission,
            missingBatteryExemption = newPermState.missingBatteryExemption
        )
    }
}

/**
 * Task-related state
 */
data class TaskState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Conversation-related state
 */
data class ConversationState(
    val activeConversationId: Long? = null,
    val currentConversation: DbConversation? = null,
    val messages: List<UiMessage> = emptyList()
)

/**
 * Permission-related state
 */
data class PermissionState(
    val missingAccessibilityService: Boolean = false,
    val missingOverlayPermission: Boolean = false,
    val missingBatteryExemption: Boolean = false
)

/**
 * Settings-related state
 */
data class SettingsState(
    val apiKey: String = "",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val isGemini: Boolean = false,
    val modelName: String = "autoglm-phone"
)

data class UiMessage(
    val role: String,
    val content: String,
    val image: Bitmap? = null,
    val formattedContent: FormattedContent? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private var modelClient: ModelClient? = null

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    // Conversation management
    private val database by lazy { AppDatabase.getInstance(getApplication()) }
    private val imageStorage by lazy {
        // Pass messageDao to enable orphaned image cleanup on initialization
        ImageStorage(getApplication(), database.messageDao())
    }
    private val repository by lazy {
        ConversationRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            imageStorage = imageStorage
        )
    }
    private val conversationUseCase by lazy { ConversationUseCase(repository, getApplication()) }
    private val preferencesManager by lazy {
        com.sidhu.androidautoglm.data.preferences.PreferencesManager(getApplication())
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

        // Reactive message updates: automatically refresh UI messages when database changes
        // Using flatMapLatest to automatically cancel previous collection when conversationId changes
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _uiState.map { it.activeConversationId }
                .distinctUntilChanged()
                .flatMapLatest { conversationId ->
                    if (conversationId != null) {
                        repository.getMessagesWithImagesFlow(conversationId)
                            .map { messagesWithImages -> messagesWithImages.toUiMessages(getApplication()) }
                    } else {
                        flowOf(emptyList())
                    }
                }
                .collect { messages ->
                    _uiState.value = _uiState.value.copy(messages = messages)
                }
        }

        // Restore last used conversation on startup
        val lastUsedConversationId = preferencesManager.getLastUsedConversationId()
        if (lastUsedConversationId != null) {
            Log.d("ChatViewModel", "Restoring last used conversation: $lastUsedConversationId")
            loadConversation(lastUsedConversationId)
        }
    }

    // Dynamic accessor for ActionExecutor
    private val actionExecutor: ActionExecutor?
        get() = AutoGLMService.getInstance()?.let { ActionExecutor(it) }

    // Debug Mode Flag - set to true to bypass permission checks and service requirements
    private val DEBUG_MODE = false

    // Job to manage the current task lifecycle - allows cancellation
    private var currentTaskJob: kotlinx.coroutines.Job? = null

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

    fun checkBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val packageName = context.packageName
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            _uiState.value = _uiState.value.copy(missingBatteryExemption = !isIgnoring)
        } else {
            _uiState.value = _uiState.value.copy(missingBatteryExemption = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun stopTask() {
        // Cancel the current task job - this will propagate cancellation to all coroutines
        currentTaskJob?.cancel()
        currentTaskJob = null

        // Update UI state - explicitly clear error to avoid showing cancellation as error
        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false, error = null)
        val service = AutoGLMService.getInstance()
        service?.updateFloatingStatus(getApplication<Application>().getString(R.string.status_stopped))
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(messages = emptyList())
        apiHistory.clear()

        // Clear messages from database for current conversation
        val conversationId = _uiState.value.activeConversationId
        if (conversationId != null) {
            viewModelScope.launch {
                try {
                    repository.clearMessages(conversationId)
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Failed to clear messages from database", e)
                }
            }
        }

        // Add welcome message if needed, or keep empty
        // _uiState.value = _uiState.value.copy(messages = listOf(UiMessage("assistant", getApplication<Application>().getString(R.string.welcome_message))))
    }

    fun sendMessage(text: String) {
        Log.d("AutoGLM_Trace", "sendMessage called with text: $text")
        // Skip blank check
        if (text.isBlank()) return

        // Ensure we have an active conversation
        if (_uiState.value.activeConversationId == null) {
            viewModelScope.launch {
                val conversationId = conversationUseCase.createConversation()
                _uiState.value = _uiState.value.copy(activeConversationId = conversationId)
            }
        }

        if (modelClient == null) {
            Log.d("AutoGLM_Trace", "modelClient is null, initializing...")
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

        // Create a new Job for this task - allows cancellation via stopTask()
        currentTaskJob = kotlinx.coroutines.Job()

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + UiMessage("user", text),
            isLoading = true,
            isRunning = true,
            error = null
        )

        viewModelScope.launch(Dispatchers.IO + currentTaskJob!!) {
            Log.d("AutoGLM_Debug", "Coroutine started")

            // Refresh app mapping before each request
            AppMapper.refreshLauncherApps()

            // Save user message to database
            if (_uiState.value.activeConversationId != null) {
                try {
                    repository.saveUserMessage(_uiState.value.activeConversationId!!, text)
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Failed to save user message", e)
                }
            }

            // Start new conversation with system prompt
            Log.d("AutoGLM_Debug", "Starting new conversation history")
            apiHistory.clear()
            // Add System Prompt with Date matching Python logic
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
            val dateStr = getApplication<Application>().getString(R.string.prompt_date_prefix) + dateFormat.format(Date())
            apiHistory.add(Message("system", dateStr + "\n" + ModelClient.SYSTEM_PROMPT))

            var currentPrompt = text
            var step = 0
            val maxSteps = 20

            if (!DEBUG_MODE && service != null) {
                // Reset floating window state for new task
                service.resetFloatingWindowForNewTask()

                // Check if app is in foreground
                val isAppInForeground = AppStateTracker.isAppInForeground(getApplication())
                Log.d("AutoGLM_Trace", "App in foreground: $isAppInForeground")

                withContext(Dispatchers.Main) {
                    // Only go home if this app is in the foreground
                    if (isAppInForeground) {
                        Log.d("AutoGLM_Trace", "App is in foreground, executing goHome()")
                        service.goHome()
                    } else {
                        Log.d("AutoGLM_Trace", "App not in foreground, skipping goHome()")
                    }

                    // Show floating window immediately after starting goHome
                    // Note: takeScreenshot() will auto-hide the window via useWindowSuspension
                    service.showFloatingWindow(
                        onStop = { stopTask() },
                        isRunning = true
                    )
                }

                // Wait for goHome animation to complete (only if we executed goHome)
                if (isAppInForeground) {
                    val animationDelay = DisplayUtils.getAnimationDelay(getApplication())
                    if (animationDelay > 0) {
                        Log.d("AutoGLM_Trace", "Waiting for transition animation to complete: ${animationDelay}ms")
                        delay(animationDelay)
                    } else {
                        Log.d("AutoGLM_Trace", "Animations disabled, skipping animation delay")
                    }
                }
            }

            var isFinished = false

            try {
                while (isActive && step < maxSteps) {
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
                    // Doubao/OpenAI vision models often prefer Image first, then Text
                    userContentItems.add(ContentItem("image_url", imageUrl = ImageUrl("data:image/png;base64,${ModelClient.bitmapToBase64(screenshot)}")))
                    userContentItems.add(ContentItem("text", text = textPrompt))

                    val userMessage = Message("user", userContentItems)
                    apiHistory.add(userMessage)

                    // Get conversation ID for database operations
                    val conversationId = _uiState.value.activeConversationId

                    // 3. Call API
                    Log.d("AutoGLM_Debug", "Sending request to ModelClient...")
                    val responseText = modelClient?.sendRequest(apiHistory, screenshot) ?: "Error: Client null"
                    // Unescape escape sequences like \n, \t, etc.
                    val unescapedResponseText = unescapeResponse(responseText)
                    Log.d("AutoGLM_Debug", "Response received: $unescapedResponseText")

                    if (unescapedResponseText.startsWith("Error")) {
                        Log.e("AutoGLM_Debug", "API Error: $unescapedResponseText")
                        postError(unescapedResponseText)
                        break
                    }

                    // Parse response parts for display
                    val (thinking, _) = ActionParser.parseResponsePartsToParsedAction(unescapedResponseText)

                    // Extract raw action string for logging and storage
                    val actionStr = ActionParser.extractActionString(unescapedResponseText)

                    Log.i("AutoGLM_Log", "\n==================================================")
                    Log.i("AutoGLM_Log", "💭 思考过程:")
                    Log.i("AutoGLM_Log", thinking)
                    Log.i("AutoGLM_Log", "🎯 执行动作:")
                    Log.i("AutoGLM_Log", actionStr)
                    Log.i("AutoGLM_Log", "==================================================")

                    // Add Assistant response to history
                    apiHistory.add(Message("assistant", buildAssistantContent(thinking, actionStr)))
                    
                    // Save assistant message to database with screenshot
                    if (conversationId != null) {
                        try {
                            val assistantContent = buildAssistantContent(thinking, actionStr)
                            repository.saveAssistantMessage(conversationId, assistantContent, screenshot)
                        } catch (e: Exception) {
                            Log.e("ChatViewModel", "Failed to save assistant message", e)
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + UiMessage("assistant", unescapedResponseText)
                    )

                    // If DEBUG_MODE, stop here after one round
                    if (DEBUG_MODE) {
                        Log.d("AutoGLM_Debug", "DEBUG_MODE enabled, stopping after one round")
                        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                        break
                    }

                    // 4. Parse Action from the extracted action string (not the full response)
                    val action = ActionParser.parseAction(actionStr, screenWidth, screenHeight)
                    
                    // Update Floating Window Status with friendly description
                    service?.updateFloatingStatus(getActionDescription(action))
                    
                    // 5. Execute Action
                    val executor = actionExecutor
                    if (executor == null) {
                         postError(getApplication<Application>().getString(R.string.error_executor_null))
                         break
                    }

                    // ensureActive() will throw CancellationException if job was cancelled
                    ensureActive()

                    val success = executor.execute(action)

                    if (action is Action.Finish) {
                        isFinished = true
                        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                        service?.updateFloatingStatus(getApplication<Application>().getString(R.string.action_finish))
                        
                        // Mark task as completed in FloatingWindowController
                        val floatingWindow = AutoGLMService.getInstance()?.floatingWindowController
                        floatingWindow?.markTaskCompleted()
                        
                        updateTaskState(TaskEndState.COMPLETED, step)
                        break
                    }

                    if (!success) {
                        apiHistory.add(Message("user", getApplication<Application>().getString(R.string.error_last_action_failed)))
                    }

                    removeImagesFromHistory()

                    // delay() is cancellable - will respond to job cancellation
                    delay(2000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Task was cancelled by user - this is expected behavior
                // DO NOT show as error - clear any error state
                Log.d("ChatViewModel", "Task was cancelled by user")
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    isLoading = false,
                    error = null  // Explicitly clear any error
                )
                service?.updateFloatingStatus(getApplication<Application>().getString(R.string.status_stopped))
                updateTaskState(TaskEndState.USER_STOPPED, step)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AutoGLM_Debug", "Exception in sendMessage loop: ${e.message}", e)
                postError(getApplication<Application>().getString(R.string.error_runtime_exception, e.message))
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinished && !isActive && _uiState.value.error == null) {
                         _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                    }
                }
            }

            if (!isFinished && isActive) {
                _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                if (!DEBUG_MODE) {
                    if (step >= maxSteps) {
                        service?.updateFloatingStatus(getApplication<Application>().getString(R.string.error_task_terminated_max_steps))
                        
                        // Mark task as completed in FloatingWindowController
                        val floatingWindow = AutoGLMService.getInstance()?.floatingWindowController
                        floatingWindow?.markTaskCompleted()
                        
                        updateTaskState(TaskEndState.MAX_STEPS_REACHED, step)
                    }
                }
            }
        }
    }

    /**
     * Generates a description for an action using ActionDescriber.
     * This replaces the old getActionDescription() method.
     */
    private fun getActionDescription(action: Action): String {
        val context = getApplication<Application>()
        return com.sidhu.androidautoglm.action.ActionDescriber.describe(action, context)
    }
    
    private fun postError(msg: String) {
        _uiState.value = _uiState.value.copy(error = msg, isRunning = false, isLoading = false)
        val service = AutoGLMService.getInstance()

        val currentPkg = service?.currentApp?.value
        val myPkg = getApplication<Application>().packageName
        if (currentPkg == myPkg) {
            service?.hideFloatingWindow()
        } else {
            service?.updateFloatingStatus(getApplication<Application>().getString(R.string.action_error, msg))
        }
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
                val contentList = lastUserMsg.content as List<*>

                // Filter items keeping only text
                val textOnlyList = contentList.filter { item ->
                    (item as? com.sidhu.androidautoglm.network.ContentItem)?.type == "text"
                }

                // Replace the message in history with the text-only version
                apiHistory[lastUserIndex] = lastUserMsg.copy(content = textOnlyList)
                // Log.d("ChatViewModel", "Removed image from history at index $lastUserIndex")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to remove image from history", e)
            }
        }
    }

    /**
     * Unescapes escape sequences in the response text.
     * Converts literal escape sequences like \n, \t, \" to their actual characters.
     */
    private fun unescapeResponse(text: String): String {
        return text
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    /**
     * Builds assistant content for API history and database storage.
     * Format: {thinking}{action}
     * Example: "I need to launch the app.do(action=\"Launch\", app=\"美团\")"
     */
    private fun buildAssistantContent(thinking: String, action: String): String {
        return if (action.isNotEmpty()) {
            "$thinking$action"
        } else {
            thinking
        }
    }

    // ==================== Conversation Management Methods ====================

    /**
     * Load a conversation by ID and display its messages.
     * Messages are automatically updated via reactive Flow when database changes.
     */
    fun loadConversation(conversationId: Long) {
        viewModelScope.launch {
            try {
                val conversation = conversationUseCase.loadConversation(conversationId)
                if (conversation != null) {
                    // Single state update with all properties
                    _uiState.value = _uiState.value.copy(
                        activeConversationId = conversationId,
                        currentConversation = conversation
                    )

                    // Save conversation ID to preferences for next startup
                    preferencesManager.saveCurrentConversationId(conversationId)

                    // Messages will be automatically loaded by the reactive Flow in init block
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load conversation", e)
            }
        }
    }

    /**
     * Create a new conversation
     */
    fun createNewConversation() {
        viewModelScope.launch {
            try {
                val conversationId = conversationUseCase.createConversation()
                _uiState.value = _uiState.value.copy(
                    activeConversationId = conversationId,
                    currentConversation = null, // Will be loaded
                    messages = emptyList()
                )
                // Clear API history for fresh conversation
                apiHistory.clear()
                // Save conversation ID to preferences
                preferencesManager.saveCurrentConversationId(conversationId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to create conversation", e)
            }
        }
    }

    /**
     * Delete a conversation
     */
    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            try {
                conversationUseCase.deleteConversation(conversationId)

                // If deleted conversation was active, create a new one
                if (_uiState.value.activeConversationId == conversationId) {
                    createNewConversation()
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to delete conversation", e)
            }
        }
    }

    /**
     * Rename a conversation
     */
    fun renameConversation(conversationId: Long, newTitle: String) {
        viewModelScope.launch {
            try {
                conversationUseCase.renameConversation(conversationId, newTitle)

                // Update current conversation if it's the active one
                if (_uiState.value.activeConversationId == conversationId) {
                    _uiState.value = _uiState.value.copy(
                        currentConversation = _uiState.value.currentConversation?.copy(title = newTitle)
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to rename conversation", e)
            }
        }
    }

    /**
     * Update task state when a task ends
     */
    private fun updateTaskState(state: TaskEndState, stepCount: Int) {
        val conversationId = _uiState.value.activeConversationId ?: return
        viewModelScope.launch {
            try {
                conversationUseCase.updateTaskState(conversationId, state, stepCount)
                // Note: UI state (isRunning, isLoading) is already updated before calling this method
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to update task state", e)
            }
        }
    }
}
