package com.sidhu.androidautoglm.vision

/**
 * Intermediate representation of an action output by the master AI.
 * Unlike [com.sidhu.androidautoglm.action.Action], coordinates are NOT yet resolved --
 * tap/press actions carry a natural-language [target] that must be located by the VisionEngine.
 */
sealed class MasterAction {

    /** Tap on the element described by [target]. */
    data class Tap(val target: String) : MasterAction()

    /** Double-tap on the element described by [target]. */
    data class DoubleTap(val target: String) : MasterAction()

    /** Long-press on the element described by [target]. */
    data class LongPress(val target: String) : MasterAction()

    /** Swipe in a cardinal [direction]: "up", "down", "left", "right". */
    data class Swipe(val direction: String) : MasterAction()

    /** Type [text] into the currently focused input field. */
    data class Type(val text: String) : MasterAction()

    /** Launch the app identified by [appName]. */
    data class Launch(val appName: String) : MasterAction()

    /** Press the system Back button. */
    object Back : MasterAction()

    /** Press the system Home button. */
    object Home : MasterAction()

    /** Wait for [durationSeconds] seconds. */
    data class Wait(val durationSeconds: Float) : MasterAction()

    /** Task finished with [message] summary. */
    data class Finish(val message: String) : MasterAction()

    /** Parsing failed with [reason]. */
    data class Error(val reason: String) : MasterAction()

    /** Unrecognized action. */
    object Unknown : MasterAction()

    /**
     * Whether this action requires coordinate resolution via VisionEngine.locateElement().
     */
    fun needsVisionLocate(): Boolean = this is Tap || this is DoubleTap || this is LongPress
}
