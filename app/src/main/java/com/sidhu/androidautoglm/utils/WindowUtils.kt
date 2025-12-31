package com.sidhu.androidautoglm.utils

import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Utility object for safe window operations.
 *
 * Provides error handling for common WindowManager operations to prevent crashes.
 *
 * **Usage Example:**
 * ```kotlin
 * // Safe removal
 * WindowUtils.safeRemove(windowManager, view)
 *
 * // Safe layout update
 * WindowUtils.safeUpdateLayout(windowManager, view, params)
 * ```
 *
 * @see FloatingWindowController
 * @see FloatingWindowManager
 */
object WindowUtils {

    private const val TAG = "WindowUtils"

    /**
     * Safely removes a view from WindowManager.
     *
     * If the removal fails, the exception is logged but the application continues running.
     * This prevents crashes from WindowManager exceptions (e.g., "View not attached to window manager").
     *
     * @param windowManager The WindowManager instance
     * @param view The view to remove
     * @return true if removal succeeded, false otherwise
     */
    fun safeRemove(windowManager: WindowManager, view: View?): Boolean {
        if (view == null) return false
        return try {
            windowManager.removeView(view)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removing view from WindowManager", e)
            false
        }
    }

    /**
     * Safely updates the layout parameters of a view in WindowManager.
     *
     * If the update fails, the exception is logged but the application continues running.
     * This prevents crashes from WindowManager exceptions (e.g., "View not attached to window manager").
     *
     * @param windowManager The WindowManager instance
     * @param view The view to update
     * @param params The new layout parameters
     * @return true if update succeeded, false otherwise
     */
    fun safeUpdateLayout(windowManager: WindowManager, view: View?, params: WindowManager.LayoutParams): Boolean {
        if (view == null) return false
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating view layout in WindowManager", e)
            false
        }
    }
}
