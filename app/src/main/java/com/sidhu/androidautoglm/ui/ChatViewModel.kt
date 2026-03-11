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
import java.text.SimpleDateFormat
import java.util.Date
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
import kotlinx.coroutines.channels.Channel

import com.sidhu.androidautoglm.BuildConfig
import com.sidhu.androidautoglm.data.AppDatabase
import com.sidhu.androidautoglm.data.ImageStorage
import com.sidhu.androidautoglm.memory.MemoryManager
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
    val imeEnabled: Boolean = false,
    val shizukuConnected: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val isGemini: Boolean = false,
    val modelName: String = "autoglm-phone",
    val activeConversationId: Long? = null,
    val currentConversation: DbConversation? = null,
    // Master model (planning): task list, dispatch，默认 MiniMax-M2.5，API Key 从 local.properties 的 MINIMAX_API_KEY 读取
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
    private val memoryManager by lazy { MemoryManager(getApplication()) }

    init {
        // Load config: support legacy (single model) and new (master/sub) structure
        val savedKeyRaw = prefs.getString("api_key", "") ?: ""
        val savedKey = if (savedKeyRaw.isNotBlank()) savedKeyRaw else BuildConfig.DEFAULT_API_KEY
        val savedBaseUrl = prefs.getString("base_url", "https://open.bigmodel.cn/api/paas/v4") ?: "https://open.bigmodel.cn/api/paas/v4"
        val savedIsGemini = prefs.getBoolean("is_gemini", false)
        val savedModelName = prefs.getString("model_name", "autoglm-phone") ?: "autoglm-phone"

        // Master config: 默认 MiniMax-M2.5，API Key 从 local.properties 的 MINIMAX_API_KEY 读取
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
        ActionManager.init(getApplication())

        // 收集语音命令（Shizuku 服务）
        viewModelScope.launch {
            AutoGLMShizukuService.voiceCommandFlow.collect { command ->
                Log.d("ChatViewModel", "Received voice command from Shizuku (wake-up): $command")
            val appContext = getApplication<Application>()
            val shizukuOk = ShizukuHelper.isShizukuFullyReady(appContext)
            val overlayOk = Settings.canDrawOverlays(appContext)
            val imeOk = isImeEnabled(appContext)
            _uiState.value = _uiState.value.copy(
                shizukuConnected = shizukuOk,
                missingOverlayPermission = !overlayOk,
                imeEnabled = imeOk
            )
            if (!shizukuOk || !overlayOk || !imeOk) {
                _uiState.value = _uiState.value.copy(
                    error = getApplication<Application>().getString(R.string.error_required_permissions_missing)
                )
                return@collect
            }
            sendMessage(command, isVoiceInput = true, startNewConversation = true)
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

    // 暂停状态：任务循环会在每次迭代开始处检查，暂停时等待用户点击继续
    private val _isPaused = MutableStateFlow(false)

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
            putString("master_api_key", if (masterApiKey == BuildConfig.MINIMAX_API_KEY || masterApiKey == BuildConfig.DEFAULT_API_KEY) "" else masterApiKey)
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
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val packageName = context.packageName
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        _uiState.value = _uiState.value.copy(missingBatteryExemption = !isIgnoring)
    }

    fun checkShizukuConnection(context: Context) {
        val isShizukuConnected = ShizukuHelper.isShizukuFullyReady(context)
        _uiState.value = _uiState.value.copy(shizukuConnected = isShizukuConnected)
    }

    fun checkImeEnabled(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
        _uiState.value = _uiState.value.copy(imeEnabled = enabled)
    }

    private fun isImeEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        return imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
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

    /** 暂停任务（循环会在每次迭代开始处等待） */
    private fun pauseTask() {
        _isPaused.value = true
        ActionManager.setPaused(true)
        ActionManager.updateStatus(getApplication<Application>().getString(R.string.status_paused))
    }

    /** 继续任务 */
    private fun resumeTask() {
        _isPaused.value = false
        ActionManager.setPaused(false)
        ActionManager.updateStatus(getApplication<Application>().getString(R.string.status_thinking))
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

    fun sendMessage(text: String, isVoiceInput: Boolean = false, startNewConversation: Boolean = false) {
        Log.d("AutoGLM_Trace", "sendMessage called with text: $text, isVoiceInput=$isVoiceInput, startNewConversation=$startNewConversation")
        // Skip blank check
        if (text.isBlank()) return
        
        ensureModelClients()

        // 语音唤醒启动的任务放入新会话
        if (startNewConversation) {
            viewModelScope.launch {
                val newId = conversationUseCase.createConversation()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        activeConversationId = newId,
                        messages = emptyList()
                    )
                    preferencesManager.saveCurrentConversationId(newId)
                }
                doSendMessage(text, isVoiceInput)
            }
            return
        }
        doSendMessage(text, isVoiceInput)
    }

    private fun doSendMessage(text: String, isVoiceInput: Boolean) {

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

            // 立即显示悬浮窗和「审查任务中」，再执行后续耗时操作
            ActionManager.resetFloatingWindow()
            _isPaused.value = false
            ActionManager.showFloatingWindow(
                onStop = { stopTask() },
                isRunning = true,
                onPauseResume = { if (_isPaused.value) resumeTask() else pauseTask() }
            )
            // 等待悬浮窗控制器就绪（Shizuku 模式下异步创建），再更新状态
            // 任务清单等主AI审查完成后再更新，避免先显示原始语音再替换
            delay(120)
            ActionManager.updateStatus(getApplication<Application>().getString(R.string.status_reviewing_task))

            val isAppInForeground = if (DEBUG_MODE) false else AppStateTracker.isAppInForeground(getApplication())
            if (isAppInForeground) {
                ActionManager.goHome()
            }

            // Refresh app mapping before each request
            AppMapper.refreshLauncherApps()

            val isExistingConversation = _uiState.value.activeConversationId != null
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

            // 纯子AI：系统提示 + 截图直送，模型输出 element=[x,y] 坐标
            // 新建会话不带历史记忆；同一会话内（如悬浮窗语音续发）带上本次会话记忆
            Log.d("AutoGLM_Debug", "Starting new conversation history (sub-AI only)")
            apiHistory.clear()

            val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
            val dateStr = getApplication<Application>().getString(R.string.prompt_date_prefix) + dateFormat.format(Date())
            val sessionMemory = if (isExistingConversation) memoryManager.loadTodayAndYesterdayMemory() else ""
            val systemContent = buildString {
                append(dateStr).append("\n").append(ModelClient.SYSTEM_PROMPT)
                if (sessionMemory.isNotBlank()) {
                    append("\n\n【本会话记忆】\n").append(sessionMemory)
                }
            }
            apiHistory.add(Message("system", systemContent))

            var currentPrompt = text
            var step = 0
            val maxSteps = 100
            val summarizeInterval = 5
            // Note 缓冲区：记录页面截图，供 Call_API 总结
            val noteBuffer = mutableListOf<Bitmap>()
            // Call_API 异步总结完成后的结果，下次迭代注入
            val callApiSummaryChannel = Channel<String>(1)

            Log.d("AutoGLM_Trace", "App in foreground: $isAppInForeground")

            var isFinished = false
            val client = modelClient
            if (client == null) {
                postError("请先在设置中配置子模型 API")
                return@launch
            }

            // 主模型审查任务：纠错字、整理、根据本机应用选择目标 App
            val masterClient = masterModelClient
            if (masterClient != null && text.isNotBlank()) {
                val installedApps = AppMapper.getInstalledAppNamesForPrompt()
                val refined = reviewTask(masterClient, text, installedApps)
                if (refined != null && !refined.startsWith("Error") && refined.isNotBlank()) {
                    currentPrompt = stripThinkTags(refined).trim()
                    if (currentPrompt.isNotEmpty()) {
                        Log.d("AutoGLM_Debug", "Task reviewed: $text -> $currentPrompt")
                    }
                }
            }
            ActionManager.updateTaskList(listOf(stripThinkTags(currentPrompt).takeIf { it.isNotEmpty() } ?: currentPrompt))

            try {
                loop@ while (isActive && step < maxSteps) {
                    // 等待暂停恢复（手动暂停或 Take_over/Interact 自动暂停）
                    while (_isPaused.value) {
                        delay(200)
                        ensureActive()
                    }
                    step++
                    Log.d("AutoGLM_Debug", "Step: $step")

                    // 注入上次 Call_API 异步完成的总结：替换被总结部分，不阻塞子模型
                    callApiSummaryChannel.tryReceive().getOrNull()?.let { payload ->
                        val sep = payload.indexOf('|')
                        if (sep > 0) {
                            val sizeAtCallApi = payload.substring(0, sep).toIntOrNull() ?: 0
                            val summary = payload.substring(sep + 1)
                            synchronized(apiHistory) {
                                if (sizeAtCallApi > 1 && apiHistory.size >= sizeAtCallApi) {
                                    for (i in sizeAtCallApi - 1 downTo 1) {
                                        apiHistory.removeAt(i)
                                    }
                                    apiHistory.add(1, Message("user", "【页面总结】$summary\n\n请根据总结继续执行下一步。"))
                                    Log.d("AutoGLM_Debug", "Call_API: 已替换被总结部分并注入")
                                }
                            }
                        }
                    }

                    ActionManager.updateStatus(getApplication<Application>().getString(R.string.status_thinking))

                    // 1. Take Screenshot（截图前显示「进行界面截图」，等待 UI 渲染后再截图）
                    val screenshotStatus = getApplication<Application>().getString(R.string.status_screenshot_taking)
                    ActionManager.updateStatus(screenshotStatus)
                    ActionManager.updateThinking(screenshotStatus)
                    delay(80)   // 等待状态文字渲染后再截图，缩短隐藏前等待
                    Log.d("AutoGLM_Debug", "Taking screenshot for step $step...")
                    val screenshot = if (DEBUG_MODE) {
                        Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                    } else {
                        ActionManager.takeScreenshot()
                    }

                    if (screenshot == null) {
                        Log.e("AutoGLM_Debug", "Screenshot failed")
                        postError(getApplication<Application>().getString(R.string.error_screenshot_failed))
                        break
                    }

                    val screenWidth = if (DEBUG_MODE) 1080 else DisplayUtils.getScreenWidth(getApplication())
                    val screenHeight = if (DEBUG_MODE) 2400 else DisplayUtils.getScreenHeight(getApplication())
                    val currentApp = if (DEBUG_MODE) "DebugApp" else (ActionManager.getCurrentApp().ifBlank { "Unknown" })

                    // 2. Build User Message: 图片 + 文本（子AI 直接看图）
                    val screenInfo = "{\"current_app\": \"$currentApp\"}"
                    val textPrompt = if (step == 1) {
                        "$currentPrompt\n\n$screenInfo"
                    } else {
                        "** Screen Info **\n\n$screenInfo"
                    }

                    val userContentItems = mutableListOf<ContentItem>()
                    userContentItems.add(ContentItem("image_url", imageUrl = ImageUrl("data:image/jpeg;base64,${ModelClient.bitmapToBase64(screenshot)}")))
                    userContentItems.add(ContentItem("text", text = textPrompt))
                    apiHistory.add(Message("user", userContentItems))

                    val conversationId = _uiState.value.activeConversationId

                    // 3. Call 子AI（带图）
                    Log.d("AutoGLM_Debug", "Sending request to sub ModelClient (step=$step, with image)...")
                    val responseText = client.sendRequest(apiHistory, screenshot)
                    val unescapedResponseText = unescapeResponse(responseText)
                    Log.d("AutoGLM_Debug", "Response received: $unescapedResponseText")

                    if (unescapedResponseText.startsWith("Error")) {
                        Log.e("AutoGLM_Debug", "API Error: $unescapedResponseText")
                        postError(unescapedResponseText)
                        break
                    }

                    val (thinking, parsedAction) = ActionParser.parseResponsePartsToParsedAction(unescapedResponseText)
                    val actionStr = ActionParser.extractActionString(unescapedResponseText)

                    Log.i("AutoGLM_Log", "\n==================================================")
                    Log.i("AutoGLM_Log", "Step $step | Think: $thinking")
                    Log.i("AutoGLM_Log", "Step $step | Action: $actionStr")
                    Log.i("AutoGLM_Log", "==================================================")

                    val assistantContent = buildAssistantContent(thinking, actionStr)
                    apiHistory.add(Message("assistant", assistantContent))

                    // DisplayFormattedContent：同时显示思考文字和动作卡片
                    if (parsedAction != null) {
                        ActionManager.updateThinking(thinking)
                        ActionManager.updateActionContent(parsedAction.toFormattedContent(getApplication()))
                    } else {
                        ActionManager.updateThinking(thinking)
                    }

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

                    if (DEBUG_MODE) {
                        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                        break
                    }

                    // 4. Parse Action（element 坐标格式，直接解析）
                    val action = ActionParser.parseAction(actionStr, screenWidth, screenHeight)
                    ActionManager.updateStatus(getActionDescription(action))

                    // 4.5 Note / Call_API / Take_over / Interact：在 ChatViewModel 内处理
                    when (action) {
                        is Action.TakeOver, Action.Interact -> {
                            // 需要人工干预：自动暂停，等待用户操作后点击继续
                            pauseTask()
                            continue@loop
                        }
                        is Action.Note -> {
                            val copy = screenshot.copy(screenshot.config ?: Bitmap.Config.ARGB_8888, false)
                            if (copy != null) {
                                if (noteBuffer.size >= 5) noteBuffer.removeAt(0)
                                noteBuffer.add(copy)
                            }
                            Log.d("AutoGLM_Debug", "Note: 已记录页面，缓冲区大小=${noteBuffer.size}")
                            removeImagesFromHistory()
                            delay(1000)
                            continue@loop
                        }
                        is Action.CallApi -> {
                            val masterClient = masterModelClient
                            if (masterClient != null) {
                                val instruction = action.instruction
                                val historyText = buildHistoryText(apiHistory)
                                val sizeAtCallApi = apiHistory.size
                                viewModelScope.launch(Dispatchers.IO) {
                                    val summary = processCallApiTextOnly(masterClient, instruction, historyText)
                                    if (summary != null && !summary.startsWith("Error")) {
                                        memoryManager.appendToDailyMemory("【Call_API】$summary")
                                        callApiSummaryChannel.trySend("$sizeAtCallApi|$summary")
                                        Log.d("AutoGLM_Debug", "Call_API: 异步总结完成，待下次请求替换注入")
                                    }
                                }
                            }
                            noteBuffer.clear()
                            removeImagesFromHistory()
                            delay(500)
                            continue@loop
                        }
                        else -> { /* 继续执行常规 action */ }
                    }

                    // 5. Execute Action
                    ensureActive()
                    val success = ActionManager.executeAction(action)

                    if (action is Action.Finish) {
                        isFinished = true
                        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false)
                        ActionManager.updateStatus(getApplication<Application>().getString(R.string.action_finish))
                        ActionManager.markTaskCompleted()
                        updateTaskState(TaskEndState.COMPLETED, step)
                        break
                    }

                    if (!success) {
                        apiHistory.add(Message("user", getApplication<Application>().getString(R.string.error_last_action_failed)))
                    }

                    removeImagesFromHistory()

                    // 主模型记忆管理：每 N 步摘要压缩历史，避免 token 爆炸
                    val masterClient = masterModelClient
                    if (masterClient != null && step % summarizeInterval == 0 && step > 0 && apiHistory.size > 4) {
                        val summary = summarizeHistory(apiHistory)
                        if (summary != null && !summary.startsWith("Error")) {
                            val lastAssistant = apiHistory.lastOrNull { it.role == "assistant" }
                            apiHistory.clear()
                            val summarizedSystemContent = buildString {
                                append(dateStr).append("\n").append(ModelClient.SYSTEM_PROMPT)
                                if (sessionMemory.isNotBlank()) {
                                    append("\n\n【本会话记忆】\n").append(sessionMemory)
                                }
                            }
                            apiHistory.add(Message("system", summarizedSystemContent))
                            apiHistory.add(Message("user", "【此前任务摘要】$summary\n\n请根据摘要继续执行下一步。"))
                            if (lastAssistant != null) {
                                apiHistory.add(lastAssistant)
                            }
                            Log.d("AutoGLM_Debug", "History summarized at step $step, context compressed")
                        }
                    }

                    delay(2000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("ChatViewModel", "Task was cancelled by user")
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    isLoading = false,
                    error = null
                )
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
                        ActionManager.markTaskCompleted()
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

    /** 将 apiHistory 转为纯文本（不含图片） */
    private fun buildHistoryText(history: List<Message>): String {
        return buildString {
            history.drop(1).forEach { msg ->
                when (msg.role) {
                    "user" -> {
                        append("[用户] ")
                        when (val c = msg.content) {
                            is String -> append(c)
                            is List<*> -> {
                                c.filterIsInstance<ContentItem>()
                                    .filter { it.type == "text" }
                                    .joinTo(this) { it.text ?: "" }
                            }
                            else -> {}
                        }
                        append("\n")
                    }
                    "assistant" -> {
                        append("[助手] ").append(msg.content.toString()).append("\n")
                    }
                    else -> {}
                }
            }
        }
    }

    /** 移除 <think> 标签及其内容，避免任务内容混入模型内部标签 */
    private fun stripThinkTags(text: String): String {
        return text
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</?think>[^<]*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * 主模型审查任务：纠错字、整理任务描述、根据本机应用选择目标 App。
     * 返回整理后的任务文本，失败时返回 null。
     */
    private suspend fun reviewTask(masterClient: ModelClient, rawTask: String, installedApps: String = ""): String? {
        if (rawTask.isBlank()) return null
        val appSection = if (installedApps.isNotBlank()) {
            "\n\n本机已安装应用：\n$installedApps\n"
        } else ""
        val taskReviewPrompt = memoryManager.getTaskReviewPrompt()
        val prompt = taskReviewPrompt + appSection + "\n用户任务：\n$rawTask"
        val history = listOf(Message("user", prompt))
        return masterClient.sendRequest(history, null)
    }

    /**
     * Call_API：主模型仅根据文字内容总结（不传图，不阻塞）。
     * 总结完成后通过 Channel 注入，子模型下次请求时可见。
     */
    private suspend fun processCallApiTextOnly(
        masterClient: ModelClient,
        instruction: String,
        historyText: String
    ): String? {
        if (historyText.isBlank()) return null
        val callApiPrompt = memoryManager.getCallApiPrompt()
        val prompt = "$callApiPrompt\n\n指令：$instruction\n\n---\n对话记录：\n$historyText"
        val history = listOf(Message("user", prompt))
        return masterClient.sendRequest(history, null)
    }

    /**
     * 主模型记忆管理：将 apiHistory 摘要压缩，返回摘要文本。
     * 用于每 N 步后压缩上下文，避免 token 爆炸。
     */
    private suspend fun summarizeHistory(history: List<Message>): String? {
        val masterClient = masterModelClient ?: return null
        val historyText = buildHistoryText(history)
        if (historyText.isBlank()) return null
        val summarizePrompt = memoryManager.getSummarizePrompt()
        val summarizeHistory = listOf(
            Message("user", "$summarizePrompt\n\n$historyText")
        )
        return masterClient.sendRequest(summarizeHistory, null)
    }

    /**
     * 从历史中移除上一条用户消息的图片，仅保留文本，以节省上下文空间。
     */
    private fun removeImagesFromHistory() {
        if (apiHistory.size < 2) return
        val lastUserIndex = apiHistory.size - 2
        if (lastUserIndex < 0) return
        val lastUserMsg = apiHistory[lastUserIndex]
        if (lastUserMsg.role == "user" && lastUserMsg.content is List<*>) {
            try {
                @Suppress("UNCHECKED_CAST")
                val contentList = lastUserMsg.content as List<*>
                val textOnlyList = contentList.filter { item ->
                    (item as? ContentItem)?.type == "text"
                }
                apiHistory[lastUserIndex] = lastUserMsg.copy(content = textOnlyList)
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
