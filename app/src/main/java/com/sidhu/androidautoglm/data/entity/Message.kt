package com.sidhu.androidautoglm.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single message in a conversation.
 * Messages are persisted to support conversation history and task resumption.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** ID of the conversation this message belongs to */
    val conversationId: Long,

    /** Role of the message sender: "user", "assistant", or "system" */
    val role: String,

    /**
     * JSON serialized content of the message.
     * Can contain text, images, or multimodal content.
     */
    val content: String,

    /**
     * File path to the image associated with this message, if any.
     * Null for text-only messages.
     */
    val imagePath: String? = null,

    /** Timestamp when the message was created (milliseconds since epoch) */
    val timestamp: Long
)
