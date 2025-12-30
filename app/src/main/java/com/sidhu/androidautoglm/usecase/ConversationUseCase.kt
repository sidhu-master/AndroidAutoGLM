package com.sidhu.androidautoglm.usecase

import android.content.Context
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.data.TaskEndState
import com.sidhu.androidautoglm.data.entity.Conversation
import com.sidhu.androidautoglm.data.repository.ConversationRepository
import com.sidhu.androidautoglm.data.repository.MessageWithImage
import com.sidhu.androidautoglm.network.ContentItem
import com.sidhu.androidautoglm.network.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case for managing conversations and their messages.
 * Encapsulates business logic for conversation CRUD operations.
 *
 * This class provides a clean abstraction layer between ViewModels and the Repository,
 * handling all conversation-related business operations.
 */
class ConversationUseCase(
    private val repository: ConversationRepository,
    private val context: Context
) {

    /**
     * Load a conversation by ID.
     *
     * @param conversationId The ID of the conversation to load
     * @return The conversation, or null if not found
     */
    suspend fun loadConversation(conversationId: Long): Conversation? = withContext(Dispatchers.IO) {
        repository.getConversationById(conversationId)
    }

    /**
     * Create a new conversation with default title.
     *
     * @return The ID of the newly created conversation
     */
    suspend fun createConversation(): Long = withContext(Dispatchers.IO) {
        repository.createConversation(title = context.getString(R.string.new_conversation))
    }

    /**
     * Delete a conversation and all its associated data (messages + images).
     *
     * @param conversationId The ID of the conversation to delete
     */
    suspend fun deleteConversation(conversationId: Long) = withContext(Dispatchers.IO) {
        repository.deleteConversation(conversationId)
    }

    /**
     * Rename a conversation.
     *
     * @param conversationId The ID of the conversation to rename
     * @param newTitle The new title for the conversation
     */
    suspend fun renameConversation(conversationId: Long, newTitle: String) = withContext(Dispatchers.IO) {
        repository.updateConversationTitle(conversationId, newTitle)
    }

    /**
     * Load all messages with images for a conversation.
     *
     * @param conversationId The ID of the conversation
     * @return List of messages with their associated bitmaps
     */
    suspend fun loadMessages(conversationId: Long): List<MessageWithImage> = withContext(Dispatchers.IO) {
        repository.getMessagesWithImages(conversationId)
    }

    /**
     * Rebuild API history from database messages for task continuation.
     *
     * This converts stored messages back to the API format expected by the model client.
     * User messages are converted to ContentItem format (text only, no screenshots).
     * Assistant messages are preserved as plain text.
     *
     * @param messagesWithImages List of messages from the database
     * @return Rebuilt API history as a list of Messages
     */
    fun rebuildApiHistory(messagesWithImages: List<MessageWithImage>): List<Message> {
        val apiHistory = mutableListOf<Message>()

        for (msgWithImage in messagesWithImages) {
            val msg = msgWithImage.message
            when (msg.role) {
                "user" -> {
                    // User messages are plain text, need to rebuild ContentItem format for API
                    val text = msg.content
                    if (text.isNotEmpty()) {
                        // Note: We can't restore the original screenshot for old user messages
                        // For continuation, new screenshots will be taken
                        val contentItems = listOf(ContentItem("text", text = text))
                        apiHistory.add(Message("user", contentItems))
                    }
                }
                "assistant" -> {
                    apiHistory.add(Message("assistant", msg.content))
                }
            }
        }

        return apiHistory
    }

    /**
     * Update the task state for a conversation.
     *
     * @param conversationId The ID of the conversation
     * @param taskState The final state of the task
     * @param stepCount The number of steps executed
     */
    suspend fun updateTaskState(
        conversationId: Long,
        taskState: TaskEndState?,
        stepCount: Int
    ) = withContext(Dispatchers.IO) {
        repository.updateTaskState(conversationId, taskState, stepCount)
    }
}
