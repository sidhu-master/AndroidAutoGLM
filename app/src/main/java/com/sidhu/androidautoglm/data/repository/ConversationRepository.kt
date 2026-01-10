package com.sidhu.androidautoglm.data.repository

import android.graphics.Bitmap
import com.sidhu.androidautoglm.data.ImageStorage
import com.sidhu.androidautoglm.data.TaskEndState
import com.sidhu.androidautoglm.data.dao.ConversationDao
import com.sidhu.androidautoglm.data.dao.MessageDao
import com.sidhu.androidautoglm.data.entity.Conversation
import com.sidhu.androidautoglm.data.entity.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing conversations and messages.
 * Provides a clean API for the ViewModel layer to interact with the database.
 */
class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val imageStorage: ImageStorage
) {
    companion object {
        private const val MAX_MESSAGES_PER_CONVERSATION = 100
        private const val MAX_TOTAL_CONVERSATIONS = 50
    }

    // Conversation operations

    /**
     * Get all conversations as Flow
     */
    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
    }

    /**
     * Get a conversation by ID
     */
    suspend fun getConversationById(id: Long): Conversation? {
        return conversationDao.getConversationById(id)
    }

    /**
     * Create a new conversation
     */
    suspend fun createConversation(title: String = "新对话"): Long {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            title = title,
            createdAt = now,
            updatedAt = now
        )
        val id = conversationDao.insertConversation(conversation)

        // Enforce conversation limit
        enforceConversationLimit()

        return id
    }

    /**
     * Update conversation title
     */
    suspend fun updateConversationTitle(conversationId: Long, title: String) {
        conversationDao.updateTitle(conversationId, title)
    }

    /**
     * Update conversation timestamp
     */
    suspend fun updateConversationTimestamp(conversationId: Long) {
        conversationDao.updateTimestamp(conversationId)
    }

    /**
     * Update task state for a conversation
     */
    suspend fun updateTaskState(conversationId: Long, taskState: TaskEndState?, stepCount: Int) {
        conversationDao.updateTaskState(conversationId, taskState, stepCount)
    }

    /**
     * Delete a conversation and all its data (messages + images)
     */
    suspend fun deleteConversation(conversationId: Long) {
        // Delete associated images first
        imageStorage.deleteImagesForConversation(conversationId, messageDao)

        // Delete messages (will cascade) and conversation
        conversationDao.deleteConversationById(conversationId)
    }

    /**
     * Delete all conversations and their data
     */
    suspend fun deleteAllConversations() {
        // Get all conversations first to delete their images
        val allConversations = conversationDao.getAllConversationsList()
        for (conversation in allConversations) {
            imageStorage.deleteImagesForConversation(conversation.id, messageDao)
        }
        // Delete all conversations (will cascade to messages)
        conversationDao.deleteAllConversations()
    }

    // Message operations

    /**
     * Get all messages for a conversation
     */
    fun getMessagesForConversation(conversationId: Long): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    /**
     * Get messages for a conversation as a list
     */
    suspend fun getMessagesList(conversationId: Long): List<Message> {
        return messageDao.getMessagesForConversationList(conversationId)
    }

    /**
     * Get messages with their associated bitmaps
     */
    suspend fun getMessagesWithImages(conversationId: Long): List<MessageWithImage> {
        val messages = getMessagesList(conversationId)
        return messages.map { msg ->
            val bitmap = msg.imagePath?.let { imageStorage.loadImage(it) }
            MessageWithImage(msg, bitmap)
        }
    }

    /**
     * Get messages with their associated bitmaps as a Flow for reactive updates.
     * This flow automatically emits new values when the database changes.
     */
    fun getMessagesWithImagesFlow(conversationId: Long): Flow<List<MessageWithImage>> {
        return messageDao.getMessagesForConversation(conversationId)
            .map { messages ->
                messages.map { msg ->
                    val bitmap = msg.imagePath?.let { imageStorage.loadImage(it) }
                    MessageWithImage(msg, bitmap)
                }
            }
    }

    /**
     * Save a user message (text command only, no image)
     */
    suspend fun saveUserMessage(
        conversationId: Long,
        content: String,
        timestamp: Long = System.currentTimeMillis()
    ): Long {
        val message = Message(
            conversationId = conversationId,
            role = "user",
            content = content,
            imagePath = null,
            timestamp = timestamp
        )

        val messageId = messageDao.insertMessage(message)

        // Update conversation timestamp
        conversationDao.updateTimestamp(conversationId)

        // Enforce message limit
        enforceMessageLimit(conversationId)

        return messageId
    }

    /**
     * Save an assistant message.
     * Note: content is expected to be a String, not JSON-serialized, to avoid double-encoding.
     */
    suspend fun saveAssistantMessage(
        conversationId: Long,
        content: Any,
        image: Bitmap? = null
    ): Long {
        // Save image if provided
        val imagePath = image?.let { imageStorage.saveImage(conversationId, it) }

        // Assistant messages are plain strings, not JSON-serialized objects
        // Store them directly without JSON serialization to avoid double-encoding issues
        // Use toString() to safely convert any type to string without JSON escaping
        val contentString = if (content is String) content else content.toString()

        val message = Message(
            conversationId = conversationId,
            role = "assistant",
            content = contentString,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis()
        )

        val messageId = messageDao.insertMessage(message)

        // Update conversation timestamp
        conversationDao.updateTimestamp(conversationId)

        // Enforce message limit
        enforceMessageLimit(conversationId)

        return messageId
    }

    /**
     * Delete all messages for a conversation
     */
    suspend fun clearMessages(conversationId: Long) {
        // Delete associated images first
        imageStorage.deleteImagesForConversation(conversationId, messageDao)

        // Delete messages
        messageDao.deleteMessagesForConversation(conversationId)
    }

    /**
     * Enforce maximum messages per conversation
     */
    // Note: This will be enforced when saving messages to each conversation

    /**
     * Enforce message limit for a specific conversation
     */
    private suspend fun enforceMessageLimit(conversationId: Long) {
        val count = messageDao.getMessageCount(conversationId)
        if (count > MAX_MESSAGES_PER_CONVERSATION) {
            messageDao.deleteOldMessages(
                conversationId = conversationId,
                keepCount = MAX_MESSAGES_PER_CONVERSATION
            )
        }
    }

    /**
     * Enforce maximum total conversations
     */
    private suspend fun enforceConversationLimit() {
        val count = conversationDao.getConversationCount()
        if (count > MAX_TOTAL_CONVERSATIONS) {
            conversationDao.deleteOldestConversations(MAX_TOTAL_CONVERSATIONS)
        }
    }
}

/**
 * A message with its associated bitmap image
 */
data class MessageWithImage(
    val message: Message,
    val bitmap: Bitmap?
)
