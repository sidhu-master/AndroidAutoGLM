package com.sidhu.androidautoglm.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sidhu.androidautoglm.data.entity.Message
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Message entities.
 */
@Dao
interface MessageDao {
    /**
     * Get all messages for a conversation ordered by timestamp
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<Message>>

    /**
     * Get all messages for a conversation as a list (suspend function)
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationList(conversationId: Long): List<Message>

    /**
     * Get messages for a conversation with pagination
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaginated(conversationId: Long, limit: Int, offset: Int): List<Message>

    /**
     * Get a message by its ID
     */
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): Message?

    /**
     * Insert a new message
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    /**
     * Insert multiple messages
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)

    /**
     * Update a message
     */
    @Query("UPDATE messages SET content = :content, imagePath = :imagePath WHERE id = :messageId")
    suspend fun updateMessage(messageId: Long, content: String, imagePath: String? = null)

    /**
     * Delete a message
     */
    @Delete
    suspend fun deleteMessage(message: Message)

    /**
     * Delete a message by ID
     */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    /**
     * Delete all messages for a conversation
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)

    /**
     * Delete all messages for a conversation except the last N
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id NOT IN (SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun deleteOldMessages(conversationId: Long, keepCount: Int)

    /**
     * Get the message count for a conversation
     */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int

    /**
     * Get all messages with image paths for a conversation
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND imagePath IS NOT NULL ORDER BY timestamp ASC")
    suspend fun getMessagesWithImages(conversationId: Long): List<Message>

    /**
     * Delete all messages (for cleanup/testing)
     */
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    /**
     * Get all image paths from all messages (for orphaned file cleanup)
     * @return List of all non-null image paths in the database
     */
    @Query("SELECT imagePath FROM messages WHERE imagePath IS NOT NULL")
    suspend fun getAllImagePaths(): List<String>
}
