package com.sidhu.androidautoglm.ui.model

import com.sidhu.androidautoglm.ui.UiMessage
import com.sidhu.androidautoglm.action.ActionParser
import com.sidhu.androidautoglm.action.ActionDescriber
import com.sidhu.androidautoglm.action.ActionType
import com.sidhu.androidautoglm.action.ParsedAction
import android.content.Context

/**
 * Extension functions for converting database messages to UI messages.
 */

/**
 * Converts a single [MessageWithImage] to a [UiMessage].
 * Returns null for unsupported message roles.
 *
 * For assistant messages, attempts to parse and format the response
 * into structured thinking + action components.
 *
 * @param context Android context for string resources (required for action formatting)
 */
fun com.sidhu.androidautoglm.data.repository.MessageWithImage.toUiMessage(context: Context? = null): UiMessage? {
    val msg = message
    return when (msg.role) {
        "user" -> UiMessage(
            role = "user",
            content = msg.content,
            image = bitmap,
            formattedContent = FormattedContent.TextContent(msg.content),
            timestamp = msg.timestamp
        )
        "assistant" -> {
            val formattedContent = parseAssistantContent(msg.content, context)
            UiMessage(
                role = "assistant",
                content = msg.content,
                image = bitmap,
                formattedContent = formattedContent,
                timestamp = msg.timestamp
            )
        }
        else -> null
    }
}

/**
 * Parses assistant message content into formatted content.
 * Uses ActionParser to extract thinking and ParsedAction, then formats for display.
 *
 * @param content The raw assistant message content
 * @param context Android context for string resources (optional, used for formatting)
 * @return FormattedContent (MixedContent with text+action, or TextContent as fallback)
 */
private fun parseAssistantContent(content: String, context: Context?): FormattedContent {
    if (context == null) {
        // No context available, return plain text content
        return FormattedContent.TextContent(content)
    }

    return try {
        // Use ActionParser to split the response into thinking and ParsedAction
        val (thinking, parsedAction) = ActionParser.parseResponsePartsToParsedAction(content)

        if (parsedAction != null) {
            // We have a parseable action - format it for display
            val actionContent = parsedAction.toFormattedContent(context)
            FormattedContent.MixedContent(thinking, actionContent)
        } else if (thinking.isNotEmpty()) {
            // Only thinking part, no valid action
            FormattedContent.TextContent(thinking)
        } else {
            // No parseable content, return as-is
            FormattedContent.TextContent(content)
        }
    } catch (e: Exception) {
        // Parsing failed, return raw content as text
        FormattedContent.TextContent(content)
    }
}

/**
 * Converts a list of [MessageWithImage] to a list of [UiMessage].
 * Filters out null values from unsupported message roles.
 *
 * @param context Android context for string resources (required for action formatting)
 */
fun List<com.sidhu.androidautoglm.data.repository.MessageWithImage>.toUiMessages(context: Context? = null): List<UiMessage> =
    mapNotNull { it.toUiMessage(context) }

/**
 * Extension function: Converts a ParsedAction to FormattedContent for UI display.
 *
 * This extension encapsulates the conversion logic, keeping it close to the
 * FormattedContent model while maintaining separation of concerns.
 * Description generation is delegated to ActionDescriber.
 *
 * @param context Android context for string resources
 * @return FormattedContent.ActionContent suitable for UI display
 */
fun ParsedAction.toFormattedContent(context: Context): FormattedContent.ActionContent {
    val description = ActionDescriber.describe(this, context)
    val details = this.buildDetails()

    return FormattedContent.ActionContent(
        actionType = this.type,
        description = description,
        icon = this.type.icon,
        details = details
    )
}

/**
 * Extension function: Builds additional details map for certain action types.
 *
 * Details are used for UI elements that show extra information beyond
 * the main description (e.g., coordinates for tap actions, start/end for swipes).
 *
 * @return Map of detail key-value pairs, or null if no details are applicable
 */
fun ParsedAction.buildDetails(): Map<String, String>? {
    return when (this.type) {
        ActionType.TAP, ActionType.DOUBLE_TAP, ActionType.LONG_PRESS -> {
            val element = this.getParam("element")
            if (element != null) mapOf("coordinates" to element) else null
        }
        ActionType.SWIPE -> {
            val start = this.getParam("start")
            val end = this.getParam("end")
            if (start != null && end != null) mapOf("start" to start, "end" to end) else null
        }
        ActionType.LAUNCH -> {
            val app = this.getParam("app")
            if (app != null) mapOf("app" to app) else null
        }
        ActionType.FINISH -> {
            val message = this.getParam("message")
            if (message != null && message.isNotEmpty()) mapOf("message" to message) else null
        }
        else -> null
    }
}
