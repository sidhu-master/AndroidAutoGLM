package com.sidhu.androidautoglm.action

import android.content.Context
import com.sidhu.androidautoglm.R

/**
 * Unified service for generating human-readable action descriptions.
 *
 * This object provides a single source of truth for all action descriptions,
 * with a core method that maps ActionType + optional parameters to descriptions.
 * Convenience overloads are provided for Action (execution) and ParsedAction (display).
 *
 * Design rationale:
 * - Action and ParsedAction serve different purposes (execution vs display)
 * - Both require the same description mapping (ActionType + params → string)
 * - The core method eliminates duplication while keeping models independent
 *
 * @see Action Execution model with concrete properties
 * @see ParsedAction Display model with generic parameter maps
 */
object ActionDescriber {

    /**
     * Core method: Generates a description from ActionType and optional parameters.
     *
     * This is the single source of truth for all action descriptions.
     * All other describe() methods delegate to this core method.
     *
     * @param type The action type enum value
     * @param context Android context for string resources
     * @param params Optional parameters (e.g., element coordinates, text, app names)
     * @return Human-readable description string
     */
    fun describe(
        type: ActionType,
        context: Context,
        params: Map<String, String>? = null
    ): String {
        return when (type) {
            ActionType.TAP -> params?.get("element")?.let {
                context.getString(R.string.action_tap_with_element, it)
            } ?: context.getString(R.string.action_tap)

            ActionType.DOUBLE_TAP -> params?.get("element")?.let {
                context.getString(R.string.action_double_tap_with_element, it)
            } ?: context.getString(R.string.action_double_tap)

            ActionType.LONG_PRESS -> params?.get("element")?.let {
                context.getString(R.string.action_long_press_with_element, it)
            } ?: context.getString(R.string.action_long_press)

            ActionType.SWIPE -> {
                val start = params?.get("start")
                val end = params?.get("end")
                if (start != null && end != null) {
                    context.getString(R.string.action_swipe_with_coordinates, start, end)
                } else {
                    context.getString(R.string.action_swipe)
                }
            }

            ActionType.TYPE -> params?.get("text")?.let {
                context.getString(R.string.action_type_with_text, it)
            } ?: context.getString(R.string.action_type_type)

            ActionType.TYPE_NAME -> params?.get("text")?.let {
                context.getString(R.string.action_type_name_with_text, it)
            } ?: context.getString(R.string.action_type_type_name)

            ActionType.LAUNCH -> params?.get("app")?.let {
                context.getString(R.string.action_launch_with_app, it)
            } ?: context.getString(R.string.action_launch)

            ActionType.BACK -> context.getString(R.string.action_back)

            ActionType.HOME -> context.getString(R.string.action_home)

            ActionType.WAIT -> params?.get("duration")?.let {
                context.getString(R.string.action_wait_with_duration, it)
            } ?: context.getString(R.string.action_wait)

            ActionType.FINISH -> params?.get("message")?.let {
                context.getString(R.string.action_finish_with_message, it)
            } ?: context.getString(R.string.action_finish)

            ActionType.TAKE_OVER -> params?.get("message")?.let {
                context.getString(R.string.action_take_over_with_message, it)
            } ?: context.getString(R.string.action_type_take_over)

            ActionType.INTERACT -> context.getString(R.string.action_interact)

            ActionType.NOTE -> context.getString(R.string.action_note)

            ActionType.CALL_API -> params?.get("instruction")?.let {
                context.getString(R.string.action_call_api_with_instruction, it)
            } ?: context.getString(R.string.action_type_call_api)

            ActionType.UNKNOWN -> context.getString(R.string.action_unknown, "")
        }
    }

    /**
     * Generates a description for an Action object (execution model).
     *
     * Extracts relevant parameters from Action properties and delegates to the core method.
     * Used primarily for floating window status updates.
     *
     * @param action The Action to describe
     * @param context Android context for string resources
     * @return Human-readable description string
     */
    fun describe(action: Action, context: Context): String {
        val params = when (action) {
            is Action.Tap -> {
                val m = mutableMapOf("element" to "[${action.x},${action.y}]")
                action.confirmationMessage?.let { m["message"] = it }
                m
            }
            is Action.Type -> mapOf("text" to action.text)
            is Action.Launch -> mapOf("app" to action.appName)
            is Action.Finish -> action.message.takeIf { it.isNotEmpty() }?.let { mapOf("message" to it) }
            is Action.TakeOver -> mapOf("message" to action.message)
            is Action.CallApi -> mapOf("instruction" to action.instruction)
            is Action.Error -> mapOf("reason" to action.reason)
            else -> null
        }
        return describe(action.toActionType(), context, params)
    }

    /**
     * Generates a description for a ParsedAction object (display model).
     *
     * Uses ParsedAction's rawParams directly and delegates to the core method.
     * Used primarily for chat message display.
     *
     * @param parsed The ParsedAction to describe
     * @param context Android context for string resources
     * @return Human-readable description string with parameters
     */
    fun describe(parsed: ParsedAction, context: Context): String {
        return describe(parsed.type, context, parsed.rawParams)
    }
}

/**
 * Internal extension: Converts Action to ActionType.
 *
 * This is a private mapping used internally by ActionDescriber.
 * The Action sealed class and ActionType enum serve different purposes:
 * - Action is for execution (has concrete properties like absolute coordinates)
 * - ActionType is for display (has icons and description mappings)
 *
 * @return The corresponding ActionType enum value
 */
private fun Action.toActionType(): ActionType = when (this) {
    is Action.Tap -> ActionType.TAP
    is Action.DoubleTap -> ActionType.DOUBLE_TAP
    is Action.LongPress -> ActionType.LONG_PRESS
    is Action.Swipe -> ActionType.SWIPE
    is Action.Type -> ActionType.TYPE
    is Action.Launch -> ActionType.LAUNCH
    is Action.Back -> ActionType.BACK
    is Action.Home -> ActionType.HOME
    is Action.Wait -> ActionType.WAIT
    is Action.Finish -> ActionType.FINISH
    is Action.TakeOver -> ActionType.TAKE_OVER
    Action.Interact -> ActionType.INTERACT
    Action.Note -> ActionType.NOTE
    is Action.CallApi -> ActionType.CALL_API
    is Action.Error -> ActionType.UNKNOWN
    Action.Unknown -> ActionType.UNKNOWN
}
