package com.sidhu.androidautoglm.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sidhu.androidautoglm.data.TaskEndState

/**
 * Represents a chat conversation with its metadata and task state.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** User-visible title of the conversation */
    val title: String,

    /** Timestamp when the conversation was created (milliseconds since epoch) */
    val createdAt: Long,

    /** Timestamp when the conversation was last updated (milliseconds since epoch) */
    val updatedAt: Long,

    /**
     * The end state of the last task in this conversation.
     * Null means no task has been run yet or the conversation is fresh.
     */
    val lastTaskState: TaskEndState? = null,

    /** The step count when the last task ended */
    val lastStepCount: Int = 0
)
