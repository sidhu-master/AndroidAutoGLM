package com.sidhu.androidautoglm.utils

import android.content.Context
import android.view.WindowManager

/**
 * Utility object for accessing display dimensions.
 *
 * Provides centralized access to screen width and height to eliminate code duplication.
 *
 * **Usage Example:**
 * ```kotlin
 * val width = DisplayUtils.getScreenWidth(context)
 * val height = DisplayUtils.getScreenHeight(context)
 * ```
 *
 * @see FloatingWindowController
 * @see AnimationController
 * @see AutoGLMService
 */
object DisplayUtils {

    /**
     * Returns the current screen width in pixels.
     *
     * Uses WindowManager.currentWindowMetrics for accurate dimensions
     * that respect multi-window mode and display cutouts.
     *
     * @param context Application or activity context
     * @return Screen width in pixels
     */
    fun getScreenWidth(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.currentWindowMetrics.bounds.width()
    }

    /**
     * Returns the current screen height in pixels.
     *
     * Uses WindowManager.currentWindowMetrics for accurate dimensions
     * that respect multi-window mode and display cutouts.
     *
     * @param context Application or activity context
     * @return Screen height in pixels
     */
    fun getScreenHeight(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.currentWindowMetrics.bounds.height()
    }
}
