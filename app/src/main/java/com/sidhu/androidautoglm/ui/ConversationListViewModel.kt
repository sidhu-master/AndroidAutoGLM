package com.sidhu.androidautoglm.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.data.AppDatabase
import com.sidhu.androidautoglm.data.ImageStorage
import com.sidhu.androidautoglm.data.TaskEndState
import com.sidhu.androidautoglm.data.entity.Conversation
import com.sidhu.androidautoglm.data.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.sidhu.androidautoglm.ui.util.displayTextOrNull
import com.sidhu.androidautoglm.ui.util.displayColorOrNull

/**
 * ViewModel for the conversation list screen.
 * Manages the list of all conversations and provides methods for CRUD operations.
 */
class ConversationListViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val imageStorage = ImageStorage(application, database.messageDao())
    private val repository = ConversationRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        imageStorage = imageStorage
    )

    // UI State
    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    // Conversations list flow
    val conversations = repository.getAllConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Load conversations on init
        loadConversations()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                Log.e("ConversationListViewModel", "Failed to load conversations", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * Delete a conversation after confirmation
     */
    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteConversation(conversationId)
            } catch (e: Exception) {
                Log.e("ConversationListViewModel", "Failed to delete conversation", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * Delete all conversations
     */
    fun deleteAllConversations() {
        viewModelScope.launch {
            try {
                repository.deleteAllConversations()
            } catch (e: Exception) {
                Log.e("ConversationListViewModel", "Failed to delete all conversations", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Rename a conversation
     */
    fun renameConversation(conversationId: Long, newTitle: String) {
        if (newTitle.isBlank()) {
            _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.conversation_title_empty_error))
            return
        }

        viewModelScope.launch {
            try {
                repository.updateConversationTitle(conversationId, newTitle)
                _uiState.value = _uiState.value.copy(error = null)
            } catch (e: Exception) {
                Log.e("ConversationListViewModel", "Failed to rename conversation", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * Format timestamp for display
     */
    fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000}分钟前"
            diff < 86400_000 -> "${diff / 3600_000}小时前"
            diff < 604800_000 -> "${diff / 86400_000}天前"
            else -> {
                val sdf = SimpleDateFormat("MM月dd日", Locale.CHINA)
                sdf.format(Date(timestamp))
            }
        }
    }
}

/**
 * UI State for conversation list screen
 */
data class ConversationListUiState(
    val isLoading: Boolean = true,
    val error: String? = null
)
