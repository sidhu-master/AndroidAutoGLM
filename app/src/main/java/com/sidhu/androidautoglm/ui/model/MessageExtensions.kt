package com.sidhu.androidautoglm.ui.model

import com.sidhu.androidautoglm.ui.UiMessage

/**
 * Extension functions for converting database messages to UI messages.
 */

/**
 * Converts a single [MessageWithImage] to a [UiMessage].
 * Returns null for unsupported message roles.
 */
fun com.sidhu.androidautoglm.data.repository.MessageWithImage.toUiMessage(): UiMessage? {
    val msg = message
    return when (msg.role) {
        "user" -> UiMessage("user", msg.content, bitmap)
        "assistant" -> UiMessage("assistant", msg.content, bitmap)
        else -> null
    }
}

/**
 * Converts a list of [MessageWithImage] to a list of [UiMessage].
 * Filters out null values from unsupported message roles.
 */
fun List<com.sidhu.androidautoglm.data.repository.MessageWithImage>.toUiMessages(): List<UiMessage> =
    mapNotNull { it.toUiMessage() }
