package com.sidhu.androidautoglm.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Screenshot utility using Shizuku.
 * Uses stdout pipe to read screenshot data directly - no filesystem permissions needed.
 */
object ScreenshotHelper {

    private const val TAG = "ScreenshotHelper"


    /**
     * Take screenshot via Shizuku using stdout pipe.
     * This is the most reliable method - reads PNG data directly from screencap stdout.
     */
    suspend fun captureViaShizukuOnly(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")

            // Check Shizuku availability
            val pingBinderMethod = shizukuClass.getMethod("pingBinder")
            try {
                pingBinderMethod.invoke(null)
            } catch (e: Exception) {
                Log.w(TAG, "[Screenshot] Shizuku not available: ${e.message}")
                return@withContext null
            }

            // Check permission
            val checkPermissionMethod = shizukuClass.getMethod("checkSelfPermission")
            val permissionResult = checkPermissionMethod.invoke(null) as Int
            val packageManagerClass = Class.forName("android.content.pm.PackageManager")
            val permissionGranted = packageManagerClass.getField("PERMISSION_GRANTED").getInt(null)
            if (permissionResult != permissionGranted) {
                Log.w(TAG, "[Screenshot] Shizuku permission not granted")
                return@withContext null
            }

            // Get newProcess method
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            // Execute screencap command - output to stdout (not file)
            val cmd = "screencap -p"
            Log.d(TAG, "[Screenshot] Running: $cmd")

            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as Process

            // Read stdout FIRST (before waitFor - avoids buffer blocking)
            val stdout = process.inputStream
            val stderr = process.errorStream

            val byteArrayOutputStream = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int

            // Read stdout in chunks
            while (stdout.read(buffer).also { bytesRead = it } != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead)
            }

            // Also read stderr for debugging
            val stderrBytes = ByteArrayOutputStream()
            while (stderr.read(buffer).also { bytesRead = it } != -1) {
                stderrBytes.write(buffer, 0, bytesRead)
            }
            val stderrOutput = stderrBytes.toString().trim()
            if (stderrOutput.isNotEmpty()) {
                Log.w(TAG, "[Screenshot] stderr: $stderrOutput")
            }

            // Now wait for process to finish
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                Log.e(TAG, "[Screenshot] screencap failed with exit code: $exitCode")
                return@withContext null
            }

            val screenshotData = byteArrayOutputStream.toByteArray()
            if (screenshotData.isEmpty()) {
                Log.e(TAG, "[Screenshot] No data received")
                return@withContext null
            }

            // Decode PNG data to Bitmap
            val bitmap = BitmapFactory.decodeByteArray(screenshotData, 0, screenshotData.size)
            if (bitmap != null) {
                Log.d(TAG, "[Screenshot] Success: ${bitmap.width}x${bitmap.height}, size: ${screenshotData.size} bytes")
                return@withContext bitmap
            } else {
                Log.e(TAG, "[Screenshot] Failed to decode PNG data")
                return@withContext null
            }

        } catch (e: Exception) {
            Log.e(TAG, "[Screenshot] Error: ${e.message}", e)
            null
        }
    }

    /**
     * Check if Shizuku is available
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val pingBinderMethod = shizukuClass.getMethod("pingBinder")
            pingBinderMethod.invoke(null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Take screenshot using available method.
     * Priority: AutoGLMService (accessibility) > Shizuku
     *
     * @param service IGLMService instance, if available
     * @return Bitmap screenshot or null if failed
     */
    suspend fun takeScreenshot(service: com.sidhu.androidautoglm.IGLMService?): Bitmap? {
        // Try service first (could be either AutoGLMService or AutoGLMShizukuService)
        if (service != null) {
            try {
                val screenshot = service.takeScreenshot()
                if (screenshot != null) {
                    Log.d(TAG, "[Screenshot] Captured via service: ${screenshot.width}x${screenshot.height}")
                    return screenshot
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Screenshot] Service failed: ${e.message}")
            }
        }

        // Fallback to Shizuku
        return captureViaShizukuOnly()
    }
}
