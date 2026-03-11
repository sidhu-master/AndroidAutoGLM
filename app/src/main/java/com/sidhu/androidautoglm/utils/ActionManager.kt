package com.sidhu.androidautoglm.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.sidhu.androidautoglm.AutoGLMShizukuService
import com.sidhu.androidautoglm.FloatingWindowController
import com.sidhu.androidautoglm.IGLMService
import com.sidhu.androidautoglm.action.Action
import com.sidhu.androidautoglm.action.ActionExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch

/**
 * ActionManager - 单例，统一管理所有 UI 操作
 *
 * 封装了所有 UI 操作（返回主页、截图、悬浮窗、执行动作等）
 * 自动根据配置选择使用无障碍服务或 Shizuku
 *
 * 使用方式：ActionManager.init(context) 初始化，然后调用各种方法
 */
object ActionManager {

    private const val TAG = "ActionManager"

    private lateinit var context: Context
    private var floatingWindowController: FloatingWindowController? = null

    // Action executor
    private var actionExecutor: ActionExecutor? = null

    /**
     * 初始化 ActionManager
     * 应在 Application 或 Activity 中调用
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        Log.d(TAG, "ActionManager initialized")
    }

    /**
     * 设置 ActionExecutor
     */
    fun setActionExecutor(executor: ActionExecutor) {
        this.actionExecutor = executor
    }

    /**
     * 检查是否可以使用 Shizuku
     */
    private fun canUseShizuku(): Boolean {
        return ShizukuHelper.isShizukuRunning()
    }

    /**
     * 检查是否可以执行操作
     */
    fun canPerformActions(): Boolean {
        return getService() != null || canUseShizuku()
    }

    /**
     * 获取当前的 Service 实例（IGLMService 接口）
     */
    fun getService(): IGLMService? {
        return try {
            val shizukuService = AutoGLMShizukuService.getInstance()
            if (shizukuService != null && shizukuService.isAvailable()) shizukuService else null
        } catch (e: Exception) {
            Log.w(TAG, "getService: Shizuku service not available", e)
            null
        }
    }

    /**
     * 获取 AutoGLMShizukuService 实例
     */
    fun getShizukuService(): AutoGLMShizukuService? {
        return try {
            AutoGLMShizukuService.getInstance()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 执行返回主页操作
     */
    fun goHome() {
        val service = getService()
        if (service != null) {
            service.goHome()
            Log.d(TAG, "goHome: via service")
        } else {
            Log.w(TAG, "goHome: no service available")
        }
    }

    /** 获取当前有效的悬浮窗控制器（优先用 service 的，避免双悬浮窗） */
    private fun getEffectiveFloatingWindowController(): FloatingWindowController? =
        getService()?.floatingWindowController ?: floatingWindowController

    /**
     * 显示悬浮窗（仅使用一个控制器：service 存在时用 service 的，否则用本地的）
     * @param onPauseResume 暂停/继续按钮点击回调（null 时不显示该按钮）
     */
    fun showFloatingWindow(onStop: () -> Unit, isRunning: Boolean = true, onPauseResume: (() -> Unit)? = null) {
        if (!hasOverlayPermission()) {
            Log.w(TAG, "showFloatingWindow: no overlay permission")
            return
        }
        var controller = getEffectiveFloatingWindowController()
        if (controller == null) {
            val service = getService()
            if (service != null) {
                service.resetFloatingWindowForNewTask()
                Handler(Looper.getMainLooper()).postDelayed({
                    (service.floatingWindowController ?: getEffectiveFloatingWindowController())?.let {
                        CoroutineScope(Dispatchers.Main).launch {
                            it.showAndWaitForLayout(onStop = onStop, isRunning = isRunning, onPauseResume = onPauseResume)
                        }
                    }
                }, 150)
            } else {
                var c: FloatingWindowController? = null
                val latch = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    c = FloatingWindowController(context)
                    floatingWindowController = c
                    latch.countDown()
                }
                latch.await()
                CoroutineScope(Dispatchers.Main).launch {
                    c?.showAndWaitForLayout(onStop = onStop, isRunning = isRunning, onPauseResume = onPauseResume)
                }
            }
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                controller.showAndWaitForLayout(onStop = onStop, isRunning = isRunning, onPauseResume = onPauseResume)
            }
        }
    }

    /** 设置悬浮窗暂停状态（用于更新暂停/继续按钮显示） */
    fun setPaused(paused: Boolean) {
        getEffectiveFloatingWindowController()?.setPaused(paused)
    }

    /**
     * 重置悬浮窗（用于新任务，仅操作一个控制器）
     */
    fun resetFloatingWindow() {
        val service = getService()
        if (service != null) {
            try {
                service.resetFloatingWindowForNewTask()
            } catch (e: Exception) {
                Log.w(TAG, "resetFloatingWindow: service failed", e)
            }
        } else {
            try {
                if (floatingWindowController == null) {
                    var controller: FloatingWindowController? = null
                    val latch = CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post {
                        controller = FloatingWindowController(context)
                        latch.countDown()
                    }
                    latch.await()
                    floatingWindowController = controller
                }
                floatingWindowController?.resetForNewTask()
            } catch (e: Exception) {
                Log.w(TAG, "resetFloatingWindow: local failed", e)
            }
        }
    }

    /** 更新悬浮窗状态文字 */
    fun updateStatus(status: String) {
        getEffectiveFloatingWindowController()?.updateStatus(status)
            ?: getService()?.updateFloatingStatus(status)
    }

    /** 更新悬浮窗任务清单 */
    fun updateTaskList(list: List<String>) {
        getEffectiveFloatingWindowController()?.updateTaskList(list)
    }

    /** 更新悬浮窗思考过程（最后3行跑马灯显示） */
    fun updateThinking(text: String) {
        getEffectiveFloatingWindowController()?.updateThinking(text)
    }

    /** 更新悬浮窗思考区域为 DisplayActionCard 内容 */
    fun updateActionContent(actionContent: com.sidhu.androidautoglm.ui.model.FormattedContent.ActionContent?) {
        getEffectiveFloatingWindowController()?.updateActionContent(actionContent)
    }

    /** 标记任务完成 */
    fun markTaskCompleted() {
        getEffectiveFloatingWindowController()?.let { ctrl ->
            CoroutineScope(Dispatchers.Main).launch { ctrl.markTaskCompleted() }
        } ?: getService()?.markTaskCompleted()
    }

    /** 设置任务运行状态 */
    fun setTaskRunning(isRunning: Boolean) {
        getEffectiveFloatingWindowController()?.setTaskRunning(isRunning)
            ?: getService()?.setTaskRunning(isRunning)
    }

    /** 关闭悬浮窗 */
    fun dismissFloatingWindow() {
        getEffectiveFloatingWindowController()?.let { ctrl ->
            CoroutineScope(Dispatchers.Main).launch { ctrl.dismiss() }
        } ?: getService()?.dismissFloatingWindow()
    }

    /** 隐藏悬浮窗（临时） */
    fun hideFloatingWindow() {
        getEffectiveFloatingWindowController()?.removeAndHide()
            ?: getService()?.hideFloatingWindow()
    }

    /**
     * 获取当前前台应用
     */
    fun getCurrentApp(): String {
        return getService()?.currentApp?.value?.takeIf { !it.isNullOrBlank() } ?: ""
    }

    /** 截图（由 service 在真正截屏时隐藏悬浮窗，准备阶段不隐藏） */
    suspend fun takeScreenshot(): Bitmap? {
        return ScreenshotHelper.takeScreenshot(getService())
    }

    /** 执行动作（Tap/Swipe/LongPress 隐藏悬浮窗，但仅在手势发出后立即恢复，不把 delay(1000) 包在隐藏范围内） */
    suspend fun executeAction(action: Action): Boolean {
        if (actionExecutor == null) {
            Log.e(TAG, "executeAction: no executor available")
            return false
        }
        val executor = actionExecutor!!
        val svc = getService()
        return when (action) {
            is Action.Tap -> {
                val ctrl = getEffectiveFloatingWindowController()
                ctrl?.avoidArea(action.x.toFloat(), action.y.toFloat())
                val success = if (ctrl != null && svc != null) {
                    ctrl.useWindowSuspension { svc.performTap(action.x.toFloat(), action.y.toFloat()) }
                } else {
                    executor.execute(action)
                    true
                }
                delay(1000) // 等待 App 响应，窗口已恢复可见
                success
            }
            is Action.DoubleTap -> {
                val ctrl = getEffectiveFloatingWindowController()
                ctrl?.avoidArea(action.x.toFloat(), action.y.toFloat())
                val success = if (ctrl != null && svc != null) {
                    ctrl.useWindowSuspension {
                        val s1 = svc.performTap(action.x.toFloat(), action.y.toFloat())
                        delay(150)
                        val s2 = svc.performTap(action.x.toFloat(), action.y.toFloat())
                        s1 && s2
                    }
                } else {
                    executor.execute(action)
                    true
                }
                delay(1000)
                success
            }
            is Action.Swipe -> {
                val ctrl = getEffectiveFloatingWindowController()
                ctrl?.avoidArea(action.startX.toFloat(), action.startY.toFloat())
                val success = if (ctrl != null && svc != null) {
                    ctrl.useWindowSuspension {
                        svc.performSwipe(
                            action.startX.toFloat(), action.startY.toFloat(),
                            action.endX.toFloat(), action.endY.toFloat()
                        )
                    }
                } else {
                    executor.execute(action)
                    true
                }
                delay(1000)
                success
            }
            is Action.LongPress -> {
                val ctrl = getEffectiveFloatingWindowController()
                ctrl?.avoidArea(action.x.toFloat(), action.y.toFloat())
                val success = if (ctrl != null && svc != null) {
                    ctrl.useWindowSuspension { svc.performLongPress(action.x.toFloat(), action.y.toFloat()) }
                } else {
                    executor.execute(action)
                    true
                }
                delay(1000)
                success
            }
            is Action.Launch -> executor.execute(action)
            else -> executor.execute(action)
        }
    }

    /**
     * 检查悬浮窗权限
     */
    fun hasOverlayPermission(): Boolean {
        return try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 清理资源
     */
    fun destroy() {
        try {
            floatingWindowController = null
            actionExecutor = null
            Log.d(TAG, "ActionManager destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "destroy failed", e)
        }
    }
}
