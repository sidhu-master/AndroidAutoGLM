package com.sidhu.androidautoglm.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages application preferences using SharedPreferences.
 * Handles persistence of conversation state and user settings.
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "app_preferences"
        private const val KEY_ACTIVE_CONVERSATION_ID = "active_conversation_id"
        private const val KEY_LAST_USED_CONVERSATION_ID = "last_used_conversation_id"
    }

    /**
     * Convenience method to save the current conversation ID to both active and last-used.
     * This is the recommended method to call when switching conversations.
     *
     * The two keys have different semantics:
     * - active: The conversation currently displayed in the UI
     * - lastUsed: Persisted for app restart recovery
     *
     * @param conversationId The conversation ID to save as both active and last-used
     */
    fun saveCurrentConversationId(conversationId: Long) {
        prefs.edit().apply {
            putLong(KEY_ACTIVE_CONVERSATION_ID, conversationId)
            putLong(KEY_LAST_USED_CONVERSATION_ID, conversationId)
        }.apply()
    }

    /**
     * Save the currently active conversation ID.
     * Use this when you only want to update the active conversation without affecting last-used.
     */
    fun saveActiveConversationId(conversationId: Long) {
        prefs.edit().putLong(KEY_ACTIVE_CONVERSATION_ID, conversationId).apply()
    }

    /**
     * Get the active conversation ID.
     * Returns null if no conversation is active.
     */
    fun getActiveConversationId(): Long? {
        val id = prefs.getLong(KEY_ACTIVE_CONVERSATION_ID, -1L)
        return if (id > 0) id else null
    }

    /**
     * Clear the active conversation ID.
     */
    fun clearActiveConversationId() {
        prefs.edit().remove(KEY_ACTIVE_CONVERSATION_ID).apply()
    }

    /**
     * Save the last used conversation ID (for quick restore on app start).
     * Use this when you only want to update the last-used without affecting active.
     */
    fun saveLastUsedConversationId(conversationId: Long) {
        prefs.edit().putLong(KEY_LAST_USED_CONVERSATION_ID, conversationId).apply()
    }

    /**
     * Get the last used conversation ID.
     * Returns null if no conversation was used.
     */
    fun getLastUsedConversationId(): Long? {
        val id = prefs.getLong(KEY_LAST_USED_CONVERSATION_ID, -1L)
        return if (id > 0) id else null
    }

    /**
     * Clear the last used conversation ID.
     */
    fun clearLastUsedConversationId() {
        prefs.edit().remove(KEY_LAST_USED_CONVERSATION_ID).apply()
    }
}
