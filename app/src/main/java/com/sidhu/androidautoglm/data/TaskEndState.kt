package com.sidhu.androidautoglm.data

/**
 * Represents the end state of the last AI task in a conversation.
 * Used to display task status badges in the UI.
 */
enum class TaskEndState {
    /** Task completed normally with Action.Finish */
    COMPLETED,

    /** Task reached maximum step limit (step >= maxSteps) */
    MAX_STEPS_REACHED,

    /** Task was stopped by user */
    USER_STOPPED,

    /** Task encountered an error */
    ERROR
}
