package com.sidhu.androidautoglm

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sidhu.androidautoglm.utils.WindowUtils

/**
 * Manages all WindowManager operations for the floating window.
 *
 * Provides a clean separation between window management and state management.
 *
 * **Responsibilities:**
 * - Adding/removing views from WindowManager
 * - Updating view layout parameters
 * - Suspending/restoring windows (for screenshots/gestures)
 * - Creating overlay views for voice interaction
 *
 * **Usage Example:**
 * ```kotlin
 * val manager = FloatingWindowManager(context)
 *
 * // Create and add window
 * val params = manager.createWindowParams()
 * val view = manager.createComposeView(lifecycleOwner, viewModelStoreOwner, savedStateRegistryOwner) {
 *     MyComposableContent()
 * }
 * manager.addWindow(view, params)
 *
 * // Suspend for screenshot
 * manager.suspendWindow(view, params)
 * // ... perform screenshot ...
 * manager.restoreWindow(view, params)
 * ```
 *
 * @see FloatingWindowController
 * @see WindowUtils
 */
class FloatingWindowManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingWindowManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Creates and initializes WindowManager.LayoutParams for the floating window.
     *
     * Default configuration:
     * - Size: WRAP_CONTENT x WRAP_CONTENT
     * - Type: TYPE_APPLICATION_OVERLAY
     * - Flags: NOT_FOCUSABLE, WATCH_OUTSIDE_TOUCH, LAYOUT_NO_LIMITS
     * - Gravity: BOTTOM | START
     * - Position: (0, 20) from bottom-left corner
     *
     * @return configured LayoutParams
     */
    fun createWindowParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 20
        }
    }

    /**
     * Creates a ComposeView with proper lifecycle and ViewModel store setup.
     *
     * This is a convenience method that sets up all the required tree owners
     * for Compose to work correctly in an overlay window context.
     *
     * @param lifecycleOwner The lifecycle owner for the view
     * @param viewModelStoreOwner The ViewModel store owner for the view
     * @param savedStateRegistryOwner The saved state registry owner for the view
     * @param content The composable content
     * @return configured ComposeView
     */
    fun createComposeView(
        lifecycleOwner: LifecycleOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        savedStateRegistryOwner: SavedStateRegistryOwner,
        content: @Composable () -> Unit
    ): ComposeView {
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setContent {
                content()
            }
        }
    }

    /**
     * Adds a view to WindowManager.
     *
     * @param view The view to add
     * @param params The layout parameters for the view
     * @return true if successful, false otherwise
     */
    fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            Log.d(TAG, "Window added successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding window", e)
            false
        }
    }

    /**
     * Removes a view from WindowManager.
     * Uses WindowUtils.safeRemove for error handling.
     *
     * @param view The view to remove
     * @return true if successful, false otherwise
     */
    fun removeWindow(view: View?): Boolean {
        return WindowUtils.safeRemove(windowManager, view)
    }

    /**
     * Updates the layout parameters of a view in WindowManager.
     * Uses WindowUtils.safeUpdateLayout for error handling.
     *
     * @param view The view to update
     * @param params The new layout parameters
     * @return true if successful, false otherwise
     */
    fun updateWindowLayout(view: View?, params: WindowManager.LayoutParams): Boolean {
        return WindowUtils.safeUpdateLayout(windowManager, view, params)
    }

    /**
     * Suspends a window by making it 0x0 size and not touchable.
     *
     * Used during screenshots and gestures to temporarily hide the window.
     * The window is not removed from WindowManager, just made invisible and non-interactive.
     *
     * @param view The view to suspend
     * @param params The layout parameters to modify
     * @return true if successful, false otherwise
     */
    fun suspendWindow(view: View?, params: WindowManager.LayoutParams): Boolean {
        if (view == null) return false
        return try {
            view.visibility = View.GONE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.width = 0
            params.height = 0
            windowManager.updateViewLayout(view, params)
            Log.d(TAG, "Window suspended successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error suspending window", e)
            false
        }
    }

    /**
     * Restores a suspended window to its normal visible state.
     *
     * Counterpart to suspendWindow(). Restores the window to WRAP_CONTENT size
     * and makes it interactive again.
     *
     * @param view The view to restore
     * @param params The layout parameters to modify
     * @return true if successful, false otherwise
     */
    fun restoreWindow(view: View?, params: WindowManager.LayoutParams): Boolean {
        if (view == null) return false
        return try {
            view.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            windowManager.updateViewLayout(view, params)
            Log.d(TAG, "Window restored successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring window", e)
            false
        }
    }

    /**
     * Creates a full-screen overlay view for voice recording/review.
     *
     * @param lifecycleOwner The lifecycle owner for the view
     * @param viewModelStoreOwner The ViewModel store owner for the view
     * @param savedStateRegistryOwner The saved state registry owner for the view
     * @param focusable Whether the overlay should be focusable (true for review, false for recording)
     * @param content The composable content for the overlay
     * @return The created ComposeView with params stored in tag
     */
    fun createOverlayView(
        lifecycleOwner: LifecycleOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        savedStateRegistryOwner: SavedStateRegistryOwner,
        focusable: Boolean,
        content: @Composable () -> Unit
    ): ComposeView {
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setContent {
                androidx.compose.material3.MaterialTheme {
                    content()
                }
            }
            // Store params in tag for later use
            tag = overlayParams
        }
    }

    /**
     * Adds an overlay view to WindowManager.
     *
     * @param overlayView The overlay view to add
     * @return true if successful, false otherwise
     */
    fun addOverlay(overlayView: View): Boolean {
        val params = overlayView.tag as? WindowManager.LayoutParams
            ?: return false
        return try {
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay added successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay", e)
            false
        }
    }

    /**
     * Removes an overlay view from WindowManager.
     *
     * @param overlayView The overlay view to remove
     * @return true if successful, false otherwise
     */
    fun removeOverlay(overlayView: View?): Boolean {
        return WindowUtils.safeRemove(windowManager, overlayView)
    }
}
