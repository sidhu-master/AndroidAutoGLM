package com.sidhu.androidautoglm.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

/**
 * Screenshot utility using Shizuku.
 * Uses stdout pipe to read screenshot data directly - no filesystem permissions needed.
 */
object ScreenshotHelper {

    private const val TAG = "ScreenshotHelper"

    /** 准备阶段结果，供 execute 使用。准备阶段不隐藏悬浮窗。 */
    data class PreparedScreencap(val newProcessMethod: Method)

    /**
     * 准备截屏环境（Shizuku 检查、权限、反射），不隐藏悬浮窗。
     * 返回 null 表示不可用，调用方无需再隐藏。
     * 使用 ShizukuHelper 统一检查，避免 "binder haven't been received" 竞态。
     */
    fun prepareScreencap(): PreparedScreencap? {
        if (!ShizukuHelper.isShizukuAvailable()) {
            Log.w(TAG, "[Screenshot] Shizuku not available")
            return null
        }
        if (!ShizukuHelper.isShizukuReady()) {
            Log.w(TAG, "[Screenshot] Shizuku not ready (binder or permission)")
            return null
        }
        val binder = runCatching { rikka.shizuku.Shizuku.getBinder() }.getOrNull()
        if (binder == null || !binder.pingBinder()) {
            ShizukuHelper.binderUnavailable()
            return null
        }
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            PreparedScreencap(newProcessMethod)
        } catch (e: Exception) {
            Log.e(TAG, "[Screenshot] Prepare error: ${e.message}", e)
            null
        }
    }

    /**
     * 执行截屏（需在悬浮窗已隐藏后调用）。
     * 仅包含 screencap 启动 + 读 stdout，无解码。
     */
    suspend fun executeScreencap(prepared: PreparedScreencap): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!ShizukuHelper.isShizukuAvailable()) {
                Log.w(TAG, "[Screenshot] Shizuku unavailable at execute time")
                return@withContext null
            }
            val cmd = "screencap -p"
            Log.d(TAG, "[Screenshot] Running: $cmd")
            val process = prepared.newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as Process
            val stdout = process.inputStream
            val stderr = process.errorStream
            val byteArrayOutputStream = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stdout.read(buffer).also { bytesRead = it } != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead)
            }
            val stderrBytes = ByteArrayOutputStream()
            while (stderr.read(buffer).also { bytesRead = it } != -1) {
                stderrBytes.write(buffer, 0, bytesRead)
            }
            val stderrOutput = stderrBytes.toString().trim()
            if (stderrOutput.isNotEmpty()) Log.w(TAG, "[Screenshot] stderr: $stderrOutput")
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "[Screenshot] screencap failed with exit code: $exitCode")
                return@withContext null
            }
            val data = byteArrayOutputStream.toByteArray()
            if (data.isEmpty()) {
                Log.e(TAG, "[Screenshot] No data received")
                return@withContext null
            }
            Log.d(TAG, "[Screenshot] Raw capture: ${data.size} bytes")
            data
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            if (cause is IllegalStateException && cause.message?.contains("binder") == true) {
                ShizukuHelper.binderUnavailable()
                Log.w(TAG, "[Screenshot] Shizuku binder lost: ${cause.message}")
            } else {
                Log.e(TAG, "[Screenshot] Execute error: ${e.message}", e)
            }
            null
        }
    }

    /**
     * 完整流程：准备 + 执行 + 解码。用于无 service 时的 fallback。
     */
    suspend fun captureViaShizukuOnlyRaw(): ByteArray? {
        val prepared = prepareScreencap() ?: return null
        return executeScreencap(prepared)
    }

    suspend fun captureViaShizukuOnly(): Bitmap? {
        val data = captureViaShizukuOnlyRaw() ?: return null
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        return if (bitmap != null) {
            Log.d(TAG, "[Screenshot] Decoded: ${bitmap.width}x${bitmap.height}")
            bitmap
        } else {
            Log.e(TAG, "[Screenshot] Failed to decode image data")
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
