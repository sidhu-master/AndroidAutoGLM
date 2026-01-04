package com.sidhu.androidautoglm.utils

import android.content.Context
import android.provider.Settings
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

    /**
     * Calculates appropriate delay for transition animation based on system settings.
     * Respects user's developer options for animation speed.
     *
     * Uses a hybrid approach to handle ROM compatibility:
     * - Reads system animation scale setting
     * - Applies safety margins for custom ROMs that may not respect settings
     *
     * @param context Application or activity context
     * @return Delay in milliseconds (min 750ms for ROM compatibility + launcher icon animations)
     */
    fun getAnimationDelay(context: Context): Long {
        val transitionScale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f
            )
        } catch (e: Settings.SettingNotFoundException) {
            1.0f
        }

        // Base animation duration (AOSP Standard)
        val baseDelay = 300L

        // Use extra safety delay for ROM compatibility
        // Also accounts for launcher icon animations on some devices
        val scaledDelay = (baseDelay * transitionScale).toLong() + 500L

        // Cap at reasonable limits: min 750ms, max 2000ms
        return scaledDelay.coerceIn(750, 2000)
    }
}
