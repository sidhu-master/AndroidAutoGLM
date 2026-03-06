package com.sidhu.androidautoglm.action

import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import com.sidhu.androidautoglm.IGLMService
import kotlinx.coroutines.delay

class ActionExecutor(private val service: IGLMService) {

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun execute(action: Action): Boolean {
        return when (action) {
            is Action.Tap -> {
                Log.d("ActionExecutor", "Tapping ${action.x}, ${action.y}")
                val success = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success
            }
            is Action.DoubleTap -> {
                Log.d("ActionExecutor", "Double Tapping ${action.x}, ${action.y}")
                // Execute two taps with a short delay
                val success1 = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(150) 
                val success2 = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success1 && success2
            }
            is Action.LongPress -> {
                Log.d("ActionExecutor", "Long Pressing ${action.x}, ${action.y}")
                val success = service.performLongPress(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success
            }
            is Action.Swipe -> {
                Log.d("ActionExecutor", "Swiping ${action.startX},${action.startY} -> ${action.endX},${action.endY}")
                val success = service.performSwipe(
                    action.startX.toFloat(), action.startY.toFloat(),
                    action.endX.toFloat(), action.endY.toFloat()
                )
                delay(1000)
                success
            }
            is Action.Type -> {
                Log.d("ActionExecutor", "Typing ${action.text}")
                service.performDirectTextInput(action.text)
            }
            is Action.Launch -> {
                Log.d("ActionExecutor", "Launching ${action.appName}")
                val packageName = AppMatcher.getPackageName(action.appName)
                if (packageName != null) {
                    val success = service.launchApp(packageName)
                    if (success) delay(2000)
                    else Log.e("ActionExecutor", "Failed to launch $packageName")
                    success
                } else {
                    Log.e("ActionExecutor", "Unknown app: ${action.appName}")
                    false
                }
            }
            is Action.Back -> {
                service.performGlobalBack()
                delay(1000)
                true
            }
            is Action.Home -> {
                service.performGlobalHome()
                delay(1000)
                true
            }
            is Action.Wait -> {
                delay(action.durationMs)
                true
            }
            is Action.Finish -> {
                Log.i("ActionExecutor", "Task Finished: ${action.message}")
                true
            }
            is Action.Error -> {
                Log.e("ActionExecutor", "Error: ${action.reason}")
                false
            }
            Action.Unknown -> false
        }
    }
}
