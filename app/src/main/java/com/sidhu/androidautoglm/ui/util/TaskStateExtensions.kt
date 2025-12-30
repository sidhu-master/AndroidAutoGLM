package com.sidhu.androidautoglm.ui.util

import android.content.Context
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.data.TaskEndState

/**
 * Extension functions for displaying [TaskEndState] in the UI.
 */

/**
 * Returns the display text for a task state.
 */
fun TaskEndState.displayText(context: Context): String {
    return when (this) {
        TaskEndState.COMPLETED -> context.getString(R.string.task_state_completed)
        TaskEndState.MAX_STEPS_REACHED -> context.getString(R.string.task_state_max_steps)
        TaskEndState.USER_STOPPED -> context.getString(R.string.task_state_user_stopped)
        TaskEndState.ERROR -> context.getString(R.string.task_state_error)
    }
}

/**
 * Returns the display text for a nullable task state.
 * Returns empty string if the state is null.
 */
fun TaskEndState?.displayTextOrNull(context: Context): String {
    return this?.displayText(context) ?: ""
}

/**
 * Returns the color hex code for a task state badge.
 */
fun TaskEndState.displayColor(): String {
    return when (this) {
        TaskEndState.COMPLETED -> "#4CAF50"  // Green
        TaskEndState.MAX_STEPS_REACHED -> "#FF9800"  // Orange
        TaskEndState.USER_STOPPED -> "#2196F3"  // Blue
        TaskEndState.ERROR -> "#F44336"  // Red
    }
}

/**
 * Returns the color hex code for a nullable task state badge.
 * Returns grey color if the state is null.
 */
fun TaskEndState?.displayColorOrNull(): String {
    return this?.displayColor() ?: "#9E9E9E"  // Grey
}
