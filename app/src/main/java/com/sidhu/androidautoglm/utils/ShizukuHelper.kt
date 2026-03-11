package com.sidhu.androidautoglm.utils

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuHelper {
    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE = 0xCA07
    /** 与 ServerConstants.MANAGER_APPLICATION_ID 一致，本应用即 Manager */
    private const val MANAGER_PACKAGE = "com.sidhu.androidautoglm"
    private var appContext: Context? = null
    private val permissionRequested = AtomicBoolean(false)
    private val binderListenerRegistered = AtomicBoolean(false)
    private val binderAvailable = AtomicBoolean(false)
    private val binderListener = Shizuku.OnBinderReceivedListener {
        binderAvailable.set(true)
        requestPermissionOnce()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAvailable.set(false)
    }

    fun init(context: Context? = null) {
        context?.let { appContext = it.applicationContext }
        if (Shizuku.isPreV11()) return
        if (binderListenerRegistered.compareAndSet(false, true)) {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            runCatching { Shizuku.getBinder() }
        }
    }

    /**
     * 启动时检查：若已配对且 Shizuku 进程存在，但 binder 未收到，则标记 connect_failed，
     * 让设置界面立即显示「未运行」，促用户去重连。应在 init 后延迟 2～3 秒调用。
     */
    fun checkBinderOnStartup(context: Context) {
        if (Shizuku.isPreV11()) return
        if (!isDevicePaired(context)) return
        val processExists = try {
            Runtime.getRuntime().exec("pgrep -f shizuku").inputStream.bufferedReader().use { it.readText().isNotBlank() }
        } catch (e: Exception) { false }
        if (!processExists) return
        val binderReady = runCatching { Shizuku.getBinder() }.getOrNull()?.pingBinder() == true
        if (!binderReady) {
            setConnectFailed(context, true)
            binderAvailable.set(false)
        }
    }

    // 检查 Shizuku 是否可用
    fun isShizukuAvailable(): Boolean {
        if (binderAvailable.get()) return true
        return try {
            Shizuku.pingBinder()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        if (isShizukuAvailable()) return true
        return try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec("pgrep -f shizuku")
            process.inputStream.bufferedReader().use { reader ->
                reader.readText().isNotBlank()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuProcessRunning(context: Context): Boolean {
        val running = isShizukuRunning()
        val paired = isDevicePaired(context)
        val failed = isConnectFailed(context)
        return running && paired && !failed
    }

    fun isShizukuReady(): Boolean {
        if (!isShizukuAvailable()) return false
        if (!isShizukuBinderReady()) {
            binderUnavailable()
            return false
        }
        return checkPermission()
    }

    /** 通知 binder 已失效（供其他模块在捕获 binder 异常时调用）。同时标记 connect_failed，让设置界面显示「未运行」促用户重连。 */
    fun binderUnavailable() {
        binderAvailable.set(false)
        appContext?.let { setConnectFailed(it, true) }
    }

    fun isDevicePaired(context: Context): Boolean {
        return context.getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE)
            .getBoolean("device_paired", false)
    }

    fun isConnectFailed(context: Context): Boolean {
        return context.getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE)
            .getBoolean("connect_failed", false)
    }

    fun setConnectFailed(context: Context, failed: Boolean) {
        context.getSharedPreferences("shizuku_starter", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("connect_failed", failed)
            .apply()
    }

    fun getAdbTcpPortFromSystem(context: Context): Int {
        val propPort = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getInt = clazz.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            val servicePort = getInt.invoke(null, "service.adb.tcp.port", -1) as Int
            val persistPort = getInt.invoke(null, "persist.adb.tcp.port", -1) as Int
            listOf(servicePort, persistPort).firstOrNull { it in 1..65535 } ?: -1
        }.getOrElse {
            -1
        }
        if (propPort in 1..65535) return propPort
        val cr = context.contentResolver
        val globalPort = runCatching {
            listOf("adb_wifi_port", "adb_port", "adb_tcp_port").firstNotNullOfOrNull { key ->
                runCatching { Settings.Global.getInt(cr, key, -1) }.getOrNull()?.takeIf { it in 1..65535 }
            } ?: -1
        }.getOrElse {
            -1
        }
        if (globalPort in 1..65535) return globalPort
        val securePort = runCatching {
            listOf("adb_wifi_port", "adb_port", "adb_tcp_port").firstNotNullOfOrNull { key ->
                runCatching { Settings.Secure.getInt(cr, key, -1) }.getOrNull()?.takeIf { it in 1..65535 }
            } ?: -1
        }.getOrElse {
            -1
        }
        return securePort.takeIf { it in 1..65535 } ?: -1
    }

    fun isShizukuUiRunning(context: Context): Boolean {
        if (!isShizukuAvailable()) return false
        if (!isDevicePaired(context)) return false
        if (isConnectFailed(context)) return false
        return true
    }

    fun isShizukuFullyReady(context: Context): Boolean {
        return isShizukuProcessRunning(context)
    }

    // 检查是否已授权。本应用即 Manager（内置 Shizuku），binder 就绪即视为已授权，无需走服务端检查。
    fun checkPermission(): Boolean {
        if (Shizuku.isPreV11()) return false
        val isManager = appContext?.packageName == MANAGER_PACKAGE
        if (isManager && isShizukuBinderReady()) return true
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message?.contains("binder") == true) {
                binderUnavailable()
            }
            false
        }
    }

    /** 严格检查 binder 是否就绪（getBinder 非空且可 ping），避免 requireService 抛异常 */
    private fun isShizukuBinderReady(): Boolean {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        return binder != null && binder.pingBinder()
    }

    // 请求权限（必须先 binder 就绪，否则 requireService 会抛异常）
    fun requestPermission(code: Int) {
        if (Shizuku.isPreV11()) return
        if (!isShizukuBinderReady()) return
        try {
            Shizuku.requestPermission(code)
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message?.contains("binder") == true) {
                binderUnavailable()
            }
            Log.e(TAG, "Request permission failed", e)
        }
    }

    fun requestPermissionOnce() {
        if (Shizuku.isPreV11()) return
        if (checkPermission()) return
        if (!isShizukuBinderReady()) return
        if (!permissionRequested.compareAndSet(false, true)) return
        requestPermission(REQUEST_CODE)
    }

    // 执行 Shell 命令
    fun executeShellCommand(command: String): Boolean {
        return executeShellCommandWithOutput(command).first
    }

    /**
     * 执行 Shell 命令并返回输出
     * @return Pair<success: Boolean, output: String>
     */
    fun executeShellCommandWithOutput(command: String): Pair<Boolean, String> {
        if (!isShizukuAvailable()) return Pair(false, "Shizuku not available")

        // 严格检查：requireService 需要 service 非空，pingBinder 可能通过但 service 在竞态下已失效
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (binder == null || !binder.pingBinder()) {
            binderAvailable.set(false)
            return Pair(false, "Shizuku binder not ready")
        }

        return try {
            val process: Process = try {
                // 优先尝试公开 API 签名
                val publicMethod = Shizuku::class.java.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                publicMethod.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as Process
            } catch (e: NoSuchMethodException) {
                // 回退到反射私有方法（旧版本）
                val declared = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                declared.isAccessible = true
                declared.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as Process
            }

            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val output = stdout + if (stderr.isNotEmpty()) "\n$stderr" else ""
            Pair(exitCode == 0, output)
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message?.contains("binder") == true) {
                binderAvailable.set(false)
                Log.w(TAG, "Shizuku binder not available: ${e.message}")
            } else {
                Log.e(TAG, "Execute command failed: $command", e)
            }
            Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * 使用 Shizuku 自动开启本应用的无障碍服务
     * 原理：修改 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
     */
    fun enableAccessibilityService(context: Context): Boolean {
        if (!isShizukuAvailable() || !checkPermission()) return false

        try {
            val packageName = context.packageName
            // 完整的服务组件名
            val serviceComponent = "$packageName/.AutoGLMService"
            
            // 1. 获取当前已开启的服务列表 (使用标准 API 读取即可)
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // 如果已经包含，直接返回成功
            if (currentServices.contains(serviceComponent)) {
                Log.d(TAG, "Accessibility service already enabled")
                return true
            }

            // 2. 拼接新的服务列表 (注意使用冒号分隔)
            val newServices = if (currentServices.isBlank()) {
                serviceComponent
            } else {
                "$currentServices:$serviceComponent"
            }

            Log.d(TAG, "Enabling accessibility via Shizuku. New list: $newServices")

            // 3. 构造 Shell 命令写入 Settings
            val cmdPutServices = "settings put secure enabled_accessibility_services '$newServices'"
            val cmdEnableMaster = "settings put secure accessibility_enabled 1"

            val success1 = executeShellCommand(cmdPutServices)
            val success2 = executeShellCommand(cmdEnableMaster)

            return success1 && success2

        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable accessibility", e)
            return false
        }
    }
}
