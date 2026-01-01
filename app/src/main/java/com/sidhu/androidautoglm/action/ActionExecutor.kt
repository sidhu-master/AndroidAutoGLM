package com.sidhu.androidautoglm.action

import android.content.Intent
import android.util.Log
import com.sidhu.androidautoglm.AutoGLMService
import kotlinx.coroutines.delay

class ActionExecutor(private val service: AutoGLMService) {

    private val textInputHandler = TextInputHandler(service)

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
                textInputHandler.inputText(action.text)
            }
            is Action.Launch -> {
                Log.d("ActionExecutor", "Launching ${action.appName}")
                // Need a map of App Name -> Package Name. For now, just try generic intent or implement a mapper.
                // Simplified: Assume appName IS packageName for this MVP or implement a small mapper
                // A real implementation needs the package mapper from the original project
                val packageName = AppMatcher.getPackageName(action.appName)
                if (packageName != null) {
                    val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        Log.d("ActionExecutor", "Found intent for $packageName, starting activity...")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            service.startActivity(intent)
                            Log.d("ActionExecutor", "Activity started successfully")
                            delay(2000)
                            true
                        } catch (e: Exception) {
                            Log.e("ActionExecutor", "Failed to start activity: ${e.message}")
                            false
                        }
                    } else {
                        Log.e("ActionExecutor", "Launch intent is null for $packageName")
                        false
                    }
                } else {
                    Log.e("ActionExecutor", "Unknown app: ${action.appName} (mapped to null)")
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
