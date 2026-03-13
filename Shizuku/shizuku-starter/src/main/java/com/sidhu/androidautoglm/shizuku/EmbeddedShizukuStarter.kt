package com.sidhu.androidautoglm.shizuku

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.sidhu.androidautoglm.shizuku.adb.AdbClient
import com.sidhu.androidautoglm.shizuku.adb.AdbKey
import com.sidhu.androidautoglm.shizuku.adb.AdbKeyException
import com.sidhu.androidautoglm.shizuku.adb.AdbPairingClient
import com.sidhu.androidautoglm.shizuku.adb.PreferenceAdbKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置 Shizuku 启动器，供主工程通过无线调试启动 Shizuku 服务。
 *
 * 注意：libshizuku.so 需从 Shizuku manager 构建产物复制到 app 的 jniLibs。
 * 构建 manager: cd Shizuku && ./gradlew :manager:assembleDebug
 * 然后从 manager/build/.../jni/arm64-v8a/ 复制 libshizuku.so 到 app/src/main/jniLibs/arm64-v8a/
 */
object EmbeddedShizukuStarter {

    private const val TAG = "EmbeddedShizukuStarter"
    private const val KEY_STORE_NAME = "shizuku_starter"
    private const val KEY_DEVICE_PAIRED = "device_paired"

    /**
     * 获取启动命令。libshizuku.so 位于 nativeLibraryDir（需已打包进 APK）。
     */
    fun getInternalCommand(context: Context): String {
        val libDir = context.applicationInfo.nativeLibraryDir
        val starterFile = File(libDir, "libshizuku.so")
        val apkPath = context.applicationInfo.sourceDir
        return "${starterFile.absolutePath} --apk=$apkPath"
    }

    /**
     * 通过无线 ADB 启动 Shizuku。
     * @param host 通常为 "127.0.0.1"
     * @param port 无线调试端口（可通过 AdbMdns 发现）
     * @param prefs 用于存储 AdbKey 的 SharedPreferences，可为 null 则使用默认
     */
    suspend fun startViaWirelessAdb(
        context: Context,
        host: String,
        port: Int,
        prefs: SharedPreferences? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val preferences = prefs ?: context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
            val key = try {
                AdbKey(PreferenceAdbKeyStore(preferences), "shizuku")
            } catch (e: Throwable) {
                Log.e(TAG, "AdbKey init failed", e)
                return@withContext Result.failure(AdbKeyException(e))
            }

            val command = getInternalCommand(context)
            Log.d(TAG, "Connecting to $host:$port, running: $command")

            val output = StringBuilder()
            AdbClient(host, port, key).use { client ->
                client.connect()
                client.shellCommand(command) { bytes ->
                    output.append(String(bytes))
                }
                Log.d(TAG, "Output: $output")
            }
            Result.success(output.toString())
        } catch (e: Exception) {
            Log.e(TAG, "startViaWirelessAdb failed", e)
            Result.failure(e)
        }
    }

    /**
     * 使用配对码与设备配对（首次使用无线调试需先配对）。
     * 用户需在「开发者选项」→「无线调试」→「使用配对码配对设备」中获取六位配对码。
     * @param pairCode 六位配对码
     * @param port 配对服务端口（通过 AdbMdns TLS_PAIRING 发现）
     */
    suspend fun pairWithDevice(
        context: Context,
        pairCode: String,
        port: Int,
        prefs: SharedPreferences? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!AdbPairingClient.available()) {
                return@withContext Result.failure(IllegalStateException("libadb not loaded"))
            }
            val preferences = prefs ?: context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
            val key = try {
                AdbKey(PreferenceAdbKeyStore(preferences), "shizuku")
            } catch (e: Throwable) {
                Log.e(TAG, "AdbKey init failed", e)
                return@withContext Result.failure(AdbKeyException(e))
            }
            AdbPairingClient("127.0.0.1", port, pairCode, key).use { client ->
                if (client.start()) {
                    preferences.edit().putBoolean(KEY_DEVICE_PAIRED, true).apply()
                    Result.success(Unit)
                } else Result.failure(Exception("Pairing failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "pairWithDevice failed", e)
            Result.failure(e)
        }
    }

    /**
     * 检查是否已配对（读取持久化状态，通知配对或界面配对成功后会写入）
     */
    fun isDevicePaired(context: Context): Boolean {
        val p = context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_DEVICE_PAIRED, false)
    }

    /**
     * 标记设备已配对（界面内配对成功时调用，与 pairWithDevice 内部写入保持一致）
     */
    fun markDevicePaired(context: Context) {
        context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEVICE_PAIRED, true).apply()
    }
}
