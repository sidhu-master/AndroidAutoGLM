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
import com.sidhu.androidautoglm.AutoGLMShizukuService
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.utils.AppStateTracker
import com.sidhu.androidautoglm.utils.DisplayUtils
import com.sidhu.androidautoglm.utils.ShizukuHelper
import com.sidhu.androidautoglm.utils.ActionManager
import com.sidhu.androidautoglm.data.TaskEndState
import com.sidhu.androidautoglm.data.entity.Conversation as DbConversation
import com.sidhu.androidautoglm.memory.MemoryManager
import com.sidhu.androidautoglm.memory.TaskPlanParser
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

import com.sidhu.androidautoglm.BuildConfig
import com.sidhu.androidautoglm.data.AppDatabase
import com.sidhu.androidautoglm.data.ImageStorage
import com.sidhu.androidautoglm.data.repository.ConversationRepository
import com.sidhu.androidautoglm.ui.model.toUiMessages
import com.sidhu.androidautoglm.ui.model.FormattedContent
import com.sidhu.androidautoglm.ui.model.toFormattedContent
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
    val missingOverlayPermission: Boolean = false,
    val missingBatteryExemption: Boolean = false,
    val shizukuConnected: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val isGemini: Boolean = false,
    val modelName: String = "autoglm-phone",
    val activeConversationId: Long? = null,
    val currentConversation: DbConversation? = null,
    // Master model (planning): task list, dispatch
    val masterApiKey: String = "",
    val masterBaseUrl: String = "https://api.minimaxi.com/v1",
    val masterIsGemini: Boolean = false,
    val masterModelName: String = "MiniMax-M2.5",
    // Sub model (execution): Tap, Launch, etc. 默认使用 autoglm-phone，不勾选则独立配置
    val subUseMasterConfig: Boolean = false,
    val subApiKey: String = "",
    val subBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val subIsGemini: Boolean = false,
    val subModelName: String = "autoglm-phone"
) {
    // Convenience properties for grouping related state
    val taskState: TaskState get() = TaskState(isRunning, isLoading, error)
    val conversationState: ConversationState get() = ConversationState(activeConversationId, currentConversation, messages)
    val permissionState: PermissionState get() = PermissionState(missingOverlayPermission, missingBatteryExemption)
    val settingsState: SettingsState get() = SettingsState(
        apiKey, baseUrl, isGemini, modelName,
        masterApiKey, masterBaseUrl, masterIsGemini, masterModelName,
        subUseMasterConfig, subApiKey, subBaseUrl, subIsGemini, subModelName
    )

    /** Effective sub model config: master config if subUseMasterConfig else sub config */
    val effectiveSubConfig: SubConfig get() = if (subUseMasterConfig) {
        SubConfig(masterApiKey, masterBaseUrl, masterIsGemini, masterModelName)
    } else {
        SubConfig(subApiKey, subBaseUrl, subIsGemini, subModelName)
    }

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
    val modelName: String = "autoglm-phone",
    val masterApiKey: String = "",
    val masterBaseUrl: String = "https://api.minimaxi.com/v1",
    val masterIsGemini: Boolean = false,
    val masterModelName: String = "MiniMax-M2.5",
    val subUseMasterConfig: Boolean = false,
    val subApiKey: String = "",
    val subBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val subIsGemini: Boolean = false,
    val subModelName: String = "autoglm-phone"
)

/** Effective config for sub model (apiKey, baseUrl, isGemini, modelName) */
data class SubConfig(
    val apiKey: String,
    val baseUrl: String,
    val isGemini: Boolean,
    val modelName: String
)

data class UiMessage(
    val role: String,
    val content: String,
    val image: Bitmap? = null,
    val formattedContent: FormattedContent? = null,
    val timestamp: Long = 0L
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private val pendingMessagesByConversationId = MutableStateFlow<Map<Long, List<UiMessage>>>(emptyMap())

    private fun messageKey(message: UiMessage): Triple<String, String, Long> =
        Triple(message.role, message.content, message.timestamp)

    private var modelClient: ModelClient? = null
    private var masterModelClient: ModelClient? = null
    private var subModelClient: ModelClient? = null

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

    // Memory Manager for long-term task support
    private val memoryManager by lazy {
        MemoryManager(getApplication()).apply { init() }
    }

    init {
        // Load config: support legacy (single model) and new (master/sub) structure
        val savedKeyRaw = prefs.getString("api_key", "") ?: ""
        val savedKey = if (savedKeyRaw.isNotBlank()) savedKeyRaw else BuildConfig.DEFAULT_API_KEY
        val savedBaseUrl = prefs.getString("base_url", "https://open.bigmodel.cn/api/paas/v4") ?: "https://open.bigmodel.cn/api/paas/v4"
        val savedIsGemini = prefs.getBoolean("is_gemini", false)
        val savedModelName = prefs.getString("model_name", "autoglm-phone") ?: "autoglm-phone"

        // Master config: if not set, use MiniMax defaults; key from MINIMAX_API_KEY when blank
        val hasMasterConfig = prefs.contains("master_api_key") || prefs.contains("master_base_url")
        val masterKey = if (hasMasterConfig) (prefs.getString("master_api_key", "") ?: "").let {
            if (it.isBlank()) BuildConfig.MINIMAX_API_KEY else it
        } else BuildConfig.MINIMAX_API_KEY
        val masterBaseUrl = prefs.getString("master_base_url", null) ?: "https://api.minimaxi.com/v1"
        val masterIsGemini = prefs.getBoolean("master_is_gemini", false)
        val masterModelName = prefs.getString("master_model_name", null) ?: "MiniMax-M2.5"

        val subUseMaster = prefs.getBoolean("sub_use_master_config", false)
        val subKey = prefs.getString("sub_api_key", null) ?: savedKey
        val subBaseUrl = prefs.getString("sub_base_url", null) ?: savedBaseUrl
        val subIsGemini = prefs.getBoolean("sub_is_gemini", savedIsGemini)
        val subModelName = prefs.getString("sub_model_name", null) ?: savedModelName

        _uiState.value = _uiState.value.copy(
            apiKey = savedKey,
            baseUrl = savedBaseUrl,
            isGemini = savedIsGemini,
            modelName = savedModelName,
            masterApiKey = masterKey,
            masterBaseUrl = masterBaseUrl,
            masterIsGemini = masterIsGemini,
            masterModelName = masterModelName,
            subUseMasterConfig = subUseMaster,
            subApiKey = subKey,
            subBaseUrl = subBaseUrl,
            subIsGemini = subIsGemini,
            subModelName = subModelName
        )

        val effectiveSub = if (subUseMaster) SubConfig(masterKey, masterBaseUrl, masterIsGemini, masterModelName)
        else SubConfig(subKey, subBaseUrl, subIsGemini, subModelName)

        if (masterKey.isNotEmpty()) {
            masterModelClient = ModelClient(masterBaseUrl, masterKey, masterModelName, masterIsGemini)
        }
        if (effectiveSub.apiKey.isNotEmpty()) {
            subModelClient = ModelClient(effectiveSub.baseUrl, effectiveSub.apiKey, effectiveSub.modelName, effectiveSub.isGemini)
        }
        modelClient = subModelClient ?: masterModelClient

        // Observe Shizuku service connection status
        viewModelScope.launch {
            AutoGLMShizukuService.serviceInstance.collect { service ->
                if (service != null) {
                    // Set ActionExecutor for Shizuku mode
                    ActionManager.setActionExecutor(ActionExecutor(service))
                }
            }
        }

        // Initialize ActionManager
        val shizukuEnabled = prefs.getBoolean("shizuku_mode_enabled", false)
        ActionManager.init(getApplication(), shizukuEnabled)

        // 收集语音命令（Shizuku 服务）
        viewModelScope.launch {
            AutoGLMShizukuService.voiceCommandFlow.collect { command ->
                Log.d("ChatViewModel", "Received voice command from Shizuku: $command")
                sendMessage(command, isVoiceInput = true)
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
                            .map { messagesWithImages ->
                                conversationId to messagesWithImages.toUiMessages(getApplication())
                            }
                    } else {
                        flowOf(null to emptyList())
                    }
                }
                .collect { (conversationId, dbMessages) ->
                    if (conversationId == null) {
                        return@collect
                    }

                    val placeholderId = -1L
                    val mapBefore = pendingMessagesByConversationId.value
                    val placeholderPending = mapBefore[placeholderId].orEmpty()
                    if (placeholderPending.isNotEmpty()) {
                        pendingMessagesByConversationId.value = mapBefore.toMutableMap().apply {
                            this[conversationId] = (this[conversationId].orEmpty() + placeholderPending)
                            remove(placeholderId)
                        }
                    }

                    val pending = pendingMessagesByConversationId.value[conversationId].orEmpty()
                    val dbKeys = dbMessages.asSequence().map(::messageKey).toHashSet()
                    val remainingPending = pending.filterNot { dbKeys.contains(messageKey(it)) }

                    if (remainingPending.size != pending.size) {
                        val newMap = pendingMessagesByConversationId.value.toMutableMap()
                        if (remainingPending.isEmpty()) {
                            newMap.remove(conversationId)
                        } else {
                            newMap[conversationId] = remainingPending
                        }
                        pendingMessagesByConversationId.value = newMap
                    }

                    val merged = (dbMessages + remainingPending).sortedBy { it.timestamp }
                    _uiState.value = _uiState.value.copy(messages = merged)
                }
        }

        // Restore last used conversation on startup
        val lastUsedConversationId = preferencesManager.getLastUsedConversationId()
        if (lastUsedConversationId != null) {
            Log.d("ChatViewModel", "Restoring last used conversation: $lastUsedConversationId")
            loadConversation(lastUsedConversationId)
        }
    }

    // Debug Mode Flag - set to true to bypass permission checks and service requirements
    private companion object { const val DEBUG_MODE = false }

    // Job to manage the current task lifecycle - allows cancellation
    private var currentTaskJob: kotlinx.coroutines.Job? = null

    // Conversation history for the API - thread-safe (accessed from both Main and IO)
    private val apiHistory = java.util.Collections.synchronizedList(mutableListOf<Message>())

    fun updateSettings(apiKey: String, baseUrl: String, isGemini: Boolean, modelName: String) {
        updateSettingsFull(
            masterApiKey = _uiState.value.masterApiKey,
            masterBaseUrl = _uiState.value.masterBaseUrl,
            masterIsGemini = _uiState.value.masterIsGemini,
            masterModelName = _uiState.value.masterModelName,
            subUseMasterConfig = _uiState.value.subUseMasterConfig,
            subApiKey = apiKey,
            subBaseUrl = baseUrl,
            subIsGemini = isGemini,
            subModelName = modelName
        )
    }

    fun updateSettingsFull(
        masterApiKey: String,
        masterBaseUrl: String,
        masterIsGemini: Boolean,
        masterModelName: String,
        subUseMasterConfig: Boolean,
        subApiKey: String,
        subBaseUrl: String,
        subIsGemini: Boolean,
        subModelName: String
    ) {
        val finalMasterBaseUrl = when {
            masterBaseUrl.isNotBlank() -> masterBaseUrl
            masterIsGemini -> "https://generativelanguage.googleapis.com"
            else -> "https://api.minimaxi.com/v1"
        }
        val finalMasterModelName = when {
            masterModelName.isNotBlank() -> masterModelName
            masterIsGemini -> "gemini-2.0-flash-exp"
            else -> "MiniMax-M2.5"
        }

        val finalSubBaseUrl = when {
            subBaseUrl.isNotBlank() -> subBaseUrl
            subIsGemini -> "https://generativelanguage.googleapis.com"
            else -> "https://open.bigmodel.cn/api/paas/v4"
        }
        val finalSubModelName = when {
            subModelName.isNotBlank() -> subModelName
            subIsGemini -> "gemini-2.0-flash-exp"
            else -> "autoglm-phone"
        }

        prefs.edit().apply {
            putString("master_api_key", if (masterApiKey == BuildConfig.MINIMAX_API_KEY) "" else masterApiKey)
            putString("master_base_url", finalMasterBaseUrl)
            putBoolean("master_is_gemini", masterIsGemini)
            putString("master_model_name", finalMasterModelName)
            putBoolean("sub_use_master_config", subUseMasterConfig)
            putString("sub_api_key", if (subApiKey == BuildConfig.DEFAULT_API_KEY) "" else subApiKey)
            putString("sub_base_url", finalSubBaseUrl)
            putBoolean("sub_is_gemini", subIsGemini)
            putString("sub_model_name", finalSubModelName)
            putString("api_key", if (subApiKey == BuildConfig.DEFAULT_API_KEY) "" else subApiKey)
            putString("base_url", finalSubBaseUrl)
            putBoolean("is_gemini", subIsGemini)
            putString("model_name", finalSubModelName)
            apply()
        }

        val effectiveMasterKey = if (masterApiKey.isBlank()) BuildConfig.MINIMAX_API_KEY else masterApiKey
        val effectiveSubKey = if (subUseMasterConfig) effectiveMasterKey
        else (if (subApiKey.isBlank()) BuildConfig.DEFAULT_API_KEY else subApiKey)
        val effectiveSubBaseUrl = if (subUseMasterConfig) finalMasterBaseUrl else finalSubBaseUrl
        val effectiveSubModelName = if (subUseMasterConfig) finalMasterModelName else finalSubModelName
        val effectiveSubIsGemini = if (subUseMasterConfig) masterIsGemini else subIsGemini

        _uiState.value = _uiState.value.copy(
            masterApiKey = effectiveMasterKey,
            masterBaseUrl = finalMasterBaseUrl,
            masterIsGemini = masterIsGemini,
            masterModelName = finalMasterModelName,
            subUseMasterConfig = subUseMasterConfig,
            subApiKey = if (subUseMasterConfig) effectiveMasterKey else subApiKey,
            subBaseUrl = finalSubBaseUrl,
            subIsGemini = subIsGemini,
            subModelName = finalSubModelName,
            apiKey = effectiveSubKey,
            baseUrl = effectiveSubBaseUrl,
            isGemini = effectiveSubIsGemini,
            modelName = effectiveSubModelName,
            error = null
        )

        if (effectiveMasterKey.isNotEmpty()) {
            masterModelClient = ModelClient(finalMasterBaseUrl, effectiveMasterKey, finalMasterModelName, masterIsGemini)
        }
        if (effectiveSubKey.isNotEmpty()) {
            subModelClient = ModelClient(effectiveSubBaseUrl, effectiveSubKey, effectiveSubModelName, effectiveSubIsGemini)
        }
        modelClient = subModelClient ?: masterModelClient
    }

    fun updateApiKey(apiKey: String) {
        updateSettings(apiKey, _uiState.value.baseUrl, _uiState.value.isGemini, _uiState.value.modelName)
    }

    private fun ensureModelClients() {
        val s = _uiState.value
        val masterKey = if (s.masterApiKey.isBlank()) BuildConfig.MINIMAX_API_KEY else s.masterApiKey
        val effectiveSub = s.effectiveSubConfig
        val subKey = if (effectiveSub.apiKey.isBlank()) BuildConfig.DEFAULT_API_KEY else effectiveSub.apiKey

        if (masterModelClient == null && masterKey.isNotEmpty()) {
            masterModelClient = ModelClient(s.masterBaseUrl, masterKey, s.masterModelName, s.masterIsGemini)
            Log.d("AutoGLM_Debug", "masterModelClient initialized")
        }
        if (subModelClient == null && subKey.isNotEmpty()) {
            subModelClient = ModelClient(effectiveSub.baseUrl, subKey, effectiveSub.modelName, effectiveSub.isGemini)
            Log.d("AutoGLM_Debug", "subModelClient initialized")
        }
        modelClient = subModelClient ?: masterModelClient
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

    fun checkShizukuConnection(context: Context) {
        val shizukuModeEnabled = prefs.getBoolean("shizuku_mode_enabled", false)
        val isShizukuConnected = shizukuModeEnabled && ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(context)
        _uiState.value = _uiState.value.copy(shizukuConnected = isShizukuConnected)
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

        // Notify floating window controller that task is no longer running
        // This ensures isTaskRunning flag is properly synchronized before dismissal
        ActionManager.setTaskRunning(false)

        // Note: The floating window will be dismissed by the UI layer (FloatingWindowContent.kt)
        // which also launches the main app after the window is fully hidden
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

    fun sendMessage(text: String, isVoiceInput: Boolean = false) {
        Log.d("AutoGLM_Trace", "sendMessage called with text: $text, isVoiceInput: $isVoiceInput")
        // Skip blank check
        if (text.isBlank()) return
        
        ensureModelClients()

        val effectiveSub = _uiState.value.effectiveSubConfig
        val masterKey = if (_uiState.value.masterApiKey.isBlank()) BuildConfig.MINIMAX_API_KEY else _uiState.value.masterApiKey
        if (effectiveSub.apiKey.isBlank() && masterKey.isBlank()) {
            Log.d("AutoGLM_Debug", "API Key is blank")
            _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.error_api_key_missing))
            return
        }

        Log.d("AutoGLM_Debug", "DEBUG_MODE: $DEBUG_MODE")

        // Check if we can perform actions
        if (!DEBUG_MODE) {
            if (!ActionManager.canPerformActions()) {
                _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.error_service_not_connected))
                return
            }
        }

        val userTimestamp = System.currentTimeMillis()
        val pendingConversationId = _uiState.value.activeConversationId ?: -1L
        val userMessage = UiMessage(
            role = "user",
            content = text,
            formattedContent = FormattedContent.TextContent(text),
            timestamp = userTimestamp
        )
        pendingMessagesByConversationId.value =
            pendingMessagesByConversationId.value.toMutableMap().apply {
                this[pendingConversationId] = (this[pendingConversationId].orEmpty() + userMessage)
            }

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMessage,
            isLoading = true,
            isRunning = true,
            error = null
        )

        currentTaskJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d("AutoGLM_Debug", "Coroutine started, isVoiceInput=$isVoiceInput")

            // Refresh app mapping before each request
            AppMapper.refreshLauncherApps()

            val ensuredConversationId = _uiState.value.activeConversationId ?: run {
                val createdId = conversationUseCase.createConversation()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(activeConversationId = createdId)
                    preferencesManager.saveCurrentConversationId(createdId)
                }
                createdId
            }

            // Save user message to database
            if (ensuredConversationId != -1L) {
                try {
                    repository.saveUserMessage(ensuredConversationId, text, userTimestamp)
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

            // Use MemoryManager to assemble system prompt
            val memoryPrompt = memoryManager.assembleSystemPrompt()
            val fullSystemPrompt = buildString {
                append(dateStr).append("\n\n")
                append(memoryPrompt).append("\n\n")
                append(ModelClient.TASK_PLAN_INSTRUCTION)
            }
            apiHistory.add(Message("system", fullSystemPrompt))
            Log.d("AutoGLM_Debug", "Assembled system prompt from memory files")

            var currentPrompt = text
            var step = 0
            // Use large max steps to support long-running tasks
            // The task will continue until AI calls finish() action
            val maxSteps = 100
            // Max consecutive sub-steps before master re-engages to check progress
            val maxSubSteps = 5
            var subStepsCount = 0
            // 卡住检测：记录最近动作字符串，连续 3 次相同则判定卡住
            val recentActionStrings = mutableListOf<String>()

            // Check if app is in foreground (used for both goHome and screenshot decisions)
            val isAppInForeground = if (DEBUG_MODE) false else AppStateTracker.isAppInForeground(getApplication())
            Log.d("AutoGLM_Trace", "App in foreground: $isAppInForeground")

            // Reset floating window state for new task
            ActionManager.resetFloatingWindow()

            // Go home if app is in foreground
            if (isAppInForeground) {
                ActionManager.goHome()
            }

            // Show floating window (works for both accessibility and Shizuku modes)
            ActionManager.showFloatingWindow(onStop = { stopTask() }, isRunning = true)

            // Initialize task list with the user's request so at least 1 item is always visible
            var currentTaskList = listOf("- [/] $text")
            ActionManager.updateTaskList(currentTaskList)

            var isFinished = false

            try {
                while (isActive && step < maxSteps) {
                    step++
                    Log.d("AutoGLM_Debug", "Step: $step")

                    // Update status
                    ActionManager.updateStatus(getApplication<Application>().getString(R.string.status_thinking))

                    // 1. Take Screenshot
                    // Skip screenshot on first step if the request was initiated from our own app
                    val screenshot = if (step == 1 && isAppInForeground) {
                        Log.d("AutoGLM_Debug", "Step 1: Skipping screenshot (app in foreground)")
                        null
                    } else {
                        Log.d("AutoGLM_Debug", "Taking screenshot for step $step...")
                        if (DEBUG_MODE) {
                            Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                        } else {
                            // Use unified ActionManager for screenshot
                            ActionManager.takeScreenshot()
                        }
                    }

                    // Check screenshot failure (only if we expected to take one)
                    if (screenshot == null && !(step == 1 && isAppInForeground)) {
                        Log.e("AutoGLM_Debug", "Screenshot failed")
                        postError(getApplication<Application>().getString(R.string.error_screenshot_failed))
                        break
                    }

                    // Log screenshot success
                    if (screenshot != null) {
                        Log.d("ChatViewModel", "Screenshot size: ${screenshot.width}x${screenshot.height}")
                    }

                    // 2. Get screen dimensions for ActionParser coordinate system
                    val screenWidth = if (DEBUG_MODE) 1080 else DisplayUtils.getScreenWidth(getApplication())
                    val screenHeight = if (DEBUG_MODE) 2400 else DisplayUtils.getScreenHeight(getApplication())
                    Log.d("ChatViewModel", "Screen size: ${screenWidth}x${screenHeight}")

                    // 3. Build User Message - 实时查询当前前台 app，避免轮询缓存导致的延迟
                    val currentApp = try {
                        val (ok, out) = ShizukuHelper.executeShellCommandWithOutput(
                            "dumpsys window 2>/dev/null | grep -m1 'mFocusedApp'"
                        )
                        if (ok && out.isNotBlank()) {
                            out.trim().substringAfter("u0 ", "").substringBefore("/").trim()
                                .ifEmpty { ActionManager.getCurrentApp() }
                        } else ActionManager.getCurrentApp()
                    } catch (_: Exception) { ActionManager.getCurrentApp() }
                    val screenInfo = "{\"current_app\": \"$currentApp\"}"

                    // Step 1 = master (intent analysis + task plan + first action)
                    // 每 5 步或检测到卡住时主模型介入检查进度
                    val isStuck = recentActionStrings.size >= 3 &&
                            recentActionStrings.takeLast(3).distinct().size == 1
                    val isMasterStep = (step == 1) || (subStepsCount >= maxSubSteps) || isStuck
                    if (isMasterStep) {
                        subStepsCount = 0
                        if (isStuck) {
                            recentActionStrings.clear()
                            Log.w("AutoGLM_Debug", "Stuck detected! Same action repeated 3 times, master re-engaging")
                        }
                    } else {
                        subStepsCount++
                    }
                    val textPrompt = when {
                        step == 1 -> {
                            val inputLabel = if (isVoiceInput)
                                getApplication<Application>().getString(R.string.voice_input_label)
                            else
                                getApplication<Application>().getString(R.string.text_input_label)
                            "${getApplication<Application>().getString(R.string.master_intent_prompt)}\n\n$inputLabel：$currentPrompt\n\n$screenInfo"
                        }
                        isMasterStep -> {
                            val stuckHint = if (isStuck) "\n\n⚠ 执行代理疑似卡住：连续重复相同动作，请分析原因并给出纠正指令。" else ""
                            "${getApplication<Application>().getString(R.string.master_validation_prompt)}$stuckHint\n\n$screenInfo"
                        }
                        else -> "${getApplication<Application>().getString(R.string.sub_step_instruction)}\n\n** Screen Info **\n\n$screenInfo"
                    }

                    val userContentItems = mutableListOf<ContentItem>()
                    // 主模型不看截图，只接收纯文字（子任务返回）；子模型需要截图执行操作
                    if (!isMasterStep && screenshot != null) {
                        userContentItems.add(ContentItem("image_url", imageUrl = ImageUrl("data:image/jpeg;base64,${ModelClient.bitmapToBase64(screenshot)}")))
                    }
                    userContentItems.add(ContentItem("text", text = textPrompt))

                    val userMessage = Message("user", userContentItems)
                    apiHistory.add(userMessage)

                    // Get conversation ID for database operations
                    val conversationId = _uiState.value.activeConversationId

                    // 3. Call API: Master 纯文本 / Sub 带截图
                    val client = if (isMasterStep) (masterModelClient ?: subModelClient) else (subModelClient ?: masterModelClient)
                    val historyForRequest = when {
                        isMasterStep -> stripImagesFromHistory(apiHistory)
                        else -> buildSubModelHistory(apiHistory, dateStr, memoryPrompt)
                    }
                    Log.d("AutoGLM_Debug", "Sending request to ${if (isMasterStep) "Master" else "Sub"} ModelClient (step=$step, textOnly=${isMasterStep})...")
                    val responseText = client?.sendRequest(historyForRequest, if (isMasterStep) null else screenshot) ?: "Error: Client null"
                    // Unescape escape sequences like \n, \t, etc.
                    val unescapedResponseText = unescapeResponse(responseText)
                    Log.d("AutoGLM_Debug", "Response received: $unescapedResponseText")

                    if (unescapedResponseText.startsWith("Error")) {
                        Log.e("AutoGLM_Debug", "API Error: $unescapedResponseText")
                        postError(unescapedResponseText)
                        break
                    }

                    // Parse response parts for display
                    val (thinking, parsedAction) = ActionParser.parseResponsePartsToParsedAction(unescapedResponseText)

                    // Extract raw action string for logging and storage
                    val actionStr = ActionParser.extractActionString(unescapedResponseText)

                    Log.i("AutoGLM_Log", "\n==================================================")
                    Log.i("AutoGLM_Log", "💭 思考过程:")
                    Log.i("AutoGLM_Log", thinking)
                    Log.i("AutoGLM_Log", "🎯 执行动作:")
                    Log.i("AutoGLM_Log", actionStr)
                    Log.i("AutoGLM_Log", "==================================================")

                    // 缓存 assistant 内容，避免重复调用 buildAssistantContent
                    val assistantContent = buildAssistantContent(thinking, actionStr)

                    // Add Assistant response to history
                    apiHistory.add(Message("assistant", assistantContent))

                    // 更新悬浮窗：有 action 时显示 DisplayActionCard 内容，否则显示思考过程
                    if (parsedAction != null) {
                        ActionManager.updateActionContent(parsedAction.toFormattedContent(getApplication()))
                    } else {
                        ActionManager.updateThinking(thinking)
                    }

                    // Parse task plan from AI response
                    val taskPlan = TaskPlanParser.parse(unescapedResponseText)
                    if (taskPlan != null) {
                        val stepLines = taskPlan.lines()
                            .map { it.trim() }
                            .filter { it.startsWith("- [") }
                        currentTaskList = stepLines
                        ActionManager.updateTaskList(stepLines)
                        Log.d("AutoGLM_Debug", "Updated task plan from AI response")
                    }
                    
                    // Save assistant message to database with screenshot
                    if (conversationId != null) {
                        try {
                            repository.saveAssistantMessage(conversationId, assistantContent, screenshot)
                        } catch (e: Exception) {
                            Log.e("ChatViewModel", "Failed to save assistant message", e)
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + UiMessage(
                            role = "assistant",
                            content = unescapedResponseText,
                            timestamp = System.currentTimeMillis()
                        )
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
                    val actionDesc = getActionDescription(action)
                    ActionManager.updateStatus(actionDesc)

                    // 5. Execute Action
                    ensureActive()
                    val success = ActionManager.executeAction(action)

                    // 记录动作用于卡住检测
                    recentActionStrings.add(actionStr.trim())
                    if (recentActionStrings.size > 5) recentActionStrings.removeAt(0)

                    if (action is Action.Finish) {
                        isFinished = true
                        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                        ActionManager.updateStatus(getApplication<Application>().getString(R.string.action_finish))

                        // Mark task as completed in FloatingWindowController
                        ActionManager.markTaskCompleted()

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
                // Note: Do NOT call updateFloatingStatus() here as it creates a race condition
                // where isTaskRunning stays true while status shows "stopped", preventing
                // the window from hiding when app resumes. The window dismissal is handled
                // by the FloatingWindowContent.kt stop button onClick handler.
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
                        ActionManager.updateStatus(getApplication<Application>().getString(R.string.error_task_terminated_max_steps))
                        // Mark task as completed in FloatingWindowController
                        ActionManager.markTaskCompleted()

                        updateTaskState(TaskEndState.MAX_STEPS_REACHED, step)
                    }
                }
            }
        }
    }

    /**
     * 子模型步骤开始前：将首个 [ ] 标记为 [/]，若已有 [/] 则不变
     */
    private fun markFirstPendingAsInProgress(list: List<String>): List<String> {
        val result = list.toMutableList()
        for (i in result.indices) {
            val line = result[i].trim()
            if (line.startsWith("- [ ]")) {
                result[i] = line.replaceFirst("- [ ]", "- [/]")
                return result
            }
            if (line.startsWith("- [/]")) return list  // 已有进行中，不变
        }
        return list
    }

    /**
     * 子模型执行动作成功后：将当前步骤（[/] 或 [ ]）标记为 [x]，下一项 [ ] 标记为 [/]
     */
    private fun markCurrentTaskCompletedAndAdvance(list: List<String>): List<String> {
        val result = list.toMutableList()
        var currentIdx = -1
        for (i in result.indices) {
            val line = result[i].trim()
            if (line.startsWith("- [/]") || line.startsWith("- [ ]")) {
                currentIdx = i
                break
            }
        }
        if (currentIdx < 0) return list
        // 当前项标记为完成
        val currentLine = result[currentIdx]
        result[currentIdx] = when {
            currentLine.startsWith("- [/]") -> currentLine.replaceFirst("- [/]", "- [x]")
            currentLine.startsWith("- [ ]") -> currentLine.replaceFirst("- [ ]", "- [x]")
            else -> currentLine
        }
        // 下一项标记为进行中
        for (i in (currentIdx + 1) until result.size) {
            val line = result[i].trim()
            if (line.startsWith("- [ ]")) {
                result[i] = line.replaceFirst("- [ ]", "- [/]")
                break
            }
        }
        return result
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
        val service = ActionManager.getService()
        val context = getApplication<Application>()

        // Unified error handling for all modes
        val currentPkg = service?.currentApp?.value
        val myPkg = context.packageName

        if (currentPkg == myPkg) {
            // App is in foreground - hide floating window
            ActionManager.hideFloatingWindow()
        } else {
            // Show error status
            ActionManager.updateStatus(context.getString(R.string.action_error, msg))
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

    /** 主模型纯文本：从 history 中移除所有图片，只保留文字（子任务返回内容） */
    private fun stripImagesFromHistory(history: List<Message>): List<Message> {
        return history.map { msg ->
            if (msg.role != "user" || msg.content !is List<*>) return@map msg
            @Suppress("UNCHECKED_CAST")
            val contentList = msg.content as List<ContentItem>
            val textOnly = contentList.filter { it.type == "text" }
            if (textOnly.size == contentList.size) msg
            else Message(msg.role, if (textOnly.size == 1) textOnly.first().text ?: "" else textOnly)
        }
    }

    /** 子模型专用：不含任务清单，只关注当前步骤。系统提示不含任务计划指令和任务进度，assistant 消息中移除 task_plan 块 */
    private fun buildSubModelHistory(history: List<Message>, dateStr: String, memoryPrompt: String): List<Message> {
        val subSystemPrompt = buildString {
            append(dateStr).append("\n\n")
            append(memoryPrompt)
        }
        val taskPlanBlockRegex = Regex("<task_plan>[\\s\\S]*?</task_plan>", RegexOption.IGNORE_CASE)
        val taskPlanTagRegex = Regex("</?task_plan>", RegexOption.IGNORE_CASE)

        // ── 借鉴 openclaw limitHistoryTurns ────────────────────────────────────
        // 子模型历史最多保留最近 MAX_SUB_HISTORY_TURNS 轮（user+assistant 各算1条）
        // 但始终保留：
        //   [0] system prompt
        //   [1] 主模型规划的 user 消息（step 1 的用户消息）
        //   [2] 主模型规划的 assistant 回复（含 task_plan，供子模型参考）
        // 其余只保留最近 N 轮，防止长任务超出 token limit
        val anchorCount = 3  // system + master user msg + master assistant msg（含 task_plan）
        val maxRecentTurns = 6  // 保留最近 6 个 user+assistant 对（12 条消息）

        val limited: List<Message> = if (history.size <= anchorCount + maxRecentTurns * 2) {
            history
        } else {
            val anchor = history.take(anchorCount)
            val tail = history.drop(anchorCount)
            // 从尾部倒数，统计 user 消息轮数，保留最近 maxRecentTurns 轮
            var userCount = 0
            var cutIndex = tail.size
            for (i in tail.indices.reversed()) {
                if (tail[i].role == "user") {
                    userCount++
                    if (userCount > maxRecentTurns) {
                        cutIndex = i + 1
                        break
                    }
                }
            }
            val kept = tail.drop(cutIndex)
            val truncatedCount = tail.size - kept.size
            Log.d("ChatViewModel", "Sub history truncated: dropped $truncatedCount old messages, keeping $anchorCount anchor + ${kept.size} recent")
            // 在截断边界插入一条提示，告知模型历史已被裁剪
            val truncationHint = Message("user", "[历史已截断，请根据任务计划和最近操作继续执行。]")
            anchor + listOf(truncationHint) + kept
        }

        // 子模型需要看到主模型第一次回复中的任务计划（<task_plan>），以便自主完成后续步骤
        // 因此：第一条 assistant 消息（主模型的规划回复）保留 task_plan，其余 assistant 消息移除
        var firstAssistantSeen = false

        return limited.mapIndexed { index, msg ->
            when {
                index == 0 && msg.role == "system" -> Message("system", subSystemPrompt)
                msg.role == "assistant" && msg.content is String -> {
                    if (!firstAssistantSeen) {
                        firstAssistantSeen = true
                        msg  // 保留主模型初始任务计划，不剥离 task_plan
                    } else {
                        val stripped = taskPlanBlockRegex.replace(msg.content as String, "")
                            .let { taskPlanTagRegex.replace(it, "") }
                            .replace(Regex("\\n{3,}"), "\n\n")
                            .trim()
                        Message(msg.role, stripped)
                    }
                }
                else -> msg
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
