package com.sidhu.androidautoglm.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sidhu.androidautoglm.data.TaskEndState
import com.sidhu.androidautoglm.data.entity.Conversation
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Conversation entities.
 */
@Dao
interface ConversationDao {
    /**
     * Get all conversations ordered by update time (newest first)
     */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    /**
     * Get all conversations as a list (for delete all operations)
     */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAllConversationsList(): List<Conversation>

    /**
     * Get a conversation by its ID
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationById(conversationId: Long): Conversation?

    /**
     * Get a conversation by its ID as Flow
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationByIdFlow(conversationId: Long): Flow<Conversation?>

    /**
     * Insert a new conversation
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation): Long

    /**
     * Update a conversation
     */
    @Update
    suspend fun updateConversation(conversation: Conversation)

    /**
     * Update the title of a conversation
     */
    @Query("UPDATE conversations SET title = :title WHERE id = :conversationId")
    suspend fun updateTitle(conversationId: Long, title: String)

    /**
     * Update the task state and step count of a conversation
     */
    @Query("UPDATE conversations SET lastTaskState = :taskState, lastStepCount = :stepCount WHERE id = :conversationId")
    suspend fun updateTaskState(conversationId: Long, taskState: TaskEndState?, stepCount: Int)

    /**
     * Update the last updated timestamp of a conversation
     */
    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :conversationId")
    suspend fun updateTimestamp(conversationId: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Delete a conversation (cascade will delete associated messages)
     */
    @Delete
    suspend fun deleteConversation(conversation: Conversation)

    /**
     * Delete a conversation by ID
     */
    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationById(conversationId: Long)

    /**
     * Delete all conversations (for cleanup/testing)
     */
    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    /**
     * Get the total count of conversations
     */
    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    /**
     * Delete oldest conversations to maintain maximum count
     */
    @Query("DELETE FROM conversations WHERE id IN (SELECT id FROM conversations ORDER BY updatedAt DESC LIMIT -1 OFFSET :maxCount)")
    suspend fun deleteOldestConversations(maxCount: Int)

    /**
     * Get the oldest conversation ID
     */
    @Query("SELECT id FROM conversations ORDER BY createdAt ASC LIMIT 1")
    suspend fun getOldestConversationId(): Long?
}
