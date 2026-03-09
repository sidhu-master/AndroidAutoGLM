package com.sidhu.androidautoglm.vision

import android.graphics.Bitmap
import android.util.Log
import com.sidhu.androidautoglm.action.Action

/**
 * Resolves a [MasterAction] (with natural-language targets) into an executable [Action]
 * (with pixel coordinates) by calling the [VisionEngine] when needed.
 */
object ActionResolver {

    private const val TAG = "ActionResolver"

    /**
     * @param masterAction The parsed master AI output.
     * @param screenshot   Current screen bitmap (needed only when [MasterAction.needsVisionLocate]).
     * @param visionEngine Engine to call for coordinate resolution.
     * @param screenWidth  Device screen width in pixels.
     * @param screenHeight Device screen height in pixels.
     */
    suspend fun resolve(
        masterAction: MasterAction,
        screenshot: Bitmap?,
        visionEngine: VisionEngine,
        screenWidth: Int,
        screenHeight: Int
    ): Action {
        return when (masterAction) {
            is MasterAction.Tap -> {
                val coords = locateOrError(masterAction.target, screenshot, visionEngine, screenWidth, screenHeight)
                    ?: return Action.Error("Vision: cannot locate '${masterAction.target}'")
                Action.Tap(coords.first, coords.second)
            }
            is MasterAction.DoubleTap -> {
                val coords = locateOrError(masterAction.target, screenshot, visionEngine, screenWidth, screenHeight)
                    ?: return Action.Error("Vision: cannot locate '${masterAction.target}'")
                Action.DoubleTap(coords.first, coords.second)
            }
            is MasterAction.LongPress -> {
                val coords = locateOrError(masterAction.target, screenshot, visionEngine, screenWidth, screenHeight)
                    ?: return Action.Error("Vision: cannot locate '${masterAction.target}'")
                Action.LongPress(coords.first, coords.second)
            }
            is MasterAction.Swipe -> directionToSwipe(masterAction.direction, screenWidth, screenHeight)
            is MasterAction.Type -> Action.Type(masterAction.text)
            is MasterAction.Launch -> Action.Launch(masterAction.appName)
            is MasterAction.Back -> Action.Back
            is MasterAction.Home -> Action.Home
            is MasterAction.Wait -> Action.Wait((masterAction.durationSeconds * 1000).toLong())
            is MasterAction.Finish -> Action.Finish(masterAction.message)
            is MasterAction.Error -> Action.Error(masterAction.reason)
            MasterAction.Unknown -> Action.Unknown
        }
    }

    private suspend fun locateOrError(
        target: String,
        screenshot: Bitmap?,
        visionEngine: VisionEngine,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int>? {
        if (screenshot == null) {
            Log.e(TAG, "No screenshot available for locating '$target'")
            return null
        }
        val relCoords = visionEngine.locateElement(screenshot, target)
        if (relCoords == null) {
            Log.e(TAG, "VisionEngine failed to locate '$target'")
            return null
        }
        val pixelX = (relCoords.first.toFloat() / 999f * screenWidth).toInt().coerceIn(0, screenWidth)
        val pixelY = (relCoords.second.toFloat() / 999f * screenHeight).toInt().coerceIn(0, screenHeight)
        Log.d(TAG, "Resolved '$target' → rel(${relCoords.first},${relCoords.second}) → px($pixelX,$pixelY)")
        return Pair(pixelX, pixelY)
    }

    private fun directionToSwipe(direction: String, screenWidth: Int, screenHeight: Int): Action {
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        val offsetX = screenWidth / 3
        val offsetY = screenHeight / 3
        return when (direction.lowercase().trim()) {
            "up" -> Action.Swipe(cx, cy + offsetY, cx, cy - offsetY)
            "down" -> Action.Swipe(cx, cy - offsetY, cx, cy + offsetY)
            "left" -> Action.Swipe(cx + offsetX, cy, cx - offsetX, cy)
            "right" -> Action.Swipe(cx - offsetX, cy, cx + offsetX, cy)
            else -> {
                Log.w(TAG, "Unknown swipe direction: $direction, defaulting to up")
                Action.Swipe(cx, cy + offsetY, cx, cy - offsetY)
            }
        }
    }
}
