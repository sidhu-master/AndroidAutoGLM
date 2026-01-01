package com.sidhu.androidautoglm.utils

import android.app.ActivityManager
import android.content.Context

/**
 * Utility object for tracking application state.
 *
 * Provides methods to check if the app is in the foreground.
 *
 * **Usage Example:**
 * ```kotlin
 * if (AppStateTracker.isAppInForeground(context)) {
 *     // App is in foreground
 * }
 * ```
 */
object AppStateTracker {

    /**
     * Checks if the app is currently in the foreground.
     *
     * @param context Application or activity context
     * @return true if the app is in the foreground, false otherwise
     */
    fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return false

        return runningProcesses.any { process ->
            process.processName == context.packageName &&
            process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}
