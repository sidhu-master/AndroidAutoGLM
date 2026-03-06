package com.sidhu.androidautoglm

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * IGLMService - 统一服务接口
 *
 * 定义 AutoGLMShizukuService 需要实现的通用功能，
 * 供 ViewModel 和 ActionExecutor 统一调用。
 */
interface IGLMService {

    /**
     * 获取当前前台应用包名
     */
    val currentApp: StateFlow<String?>

    /**
     * 悬浮窗控制器（如果可用）
     */
    val floatingWindowController: FloatingWindowController?

    /**
     * 执行返回主页操作
     */
    fun goHome()

    /**
     * 截图
     * @param timeoutMs 超时时间（毫秒）
     * @return 截图 Bitmap 或 null
     */
    suspend fun takeScreenshot(timeoutMs: Long = 5000): Bitmap?

    /**
     * 更新悬浮窗状态文字
     */
    fun updateFloatingStatus(text: String)

    /**
     * 重置悬浮窗（用于新任务）
     */
    fun resetFloatingWindowForNewTask()

    /**
     * 隐藏悬浮窗（临时）
     */
    fun hideFloatingWindow()

    /**
     * 设置任务运行状态
     */
    fun setTaskRunning(isRunning: Boolean)

    /**
     * 标记任务完成
     */
    fun markTaskCompleted()

    /**
     * 关闭悬浮窗
     */
    fun dismissFloatingWindow()

    /**
     * 检查服务是否可用
     */
    fun isAvailable(): Boolean

    /**
     * 执行点击
     */
    suspend fun performTap(x: Float, y: Float): Boolean

    /**
     * 执行滑动
     */
    suspend fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 1000): Boolean

    /**
     * 执行长按
     */
    suspend fun performLongPress(x: Float, y: Float, duration: Long = 1000): Boolean

    /**
     * 执行全局返回
     */
    fun performGlobalBack(): Boolean

    /**
     * 执行全局主页
     */
    fun performGlobalHome(): Boolean

    /**
     * 启动应用
     * @param packageName 应用包名
     * @return 是否成功
     */
    fun launchApp(packageName: String): Boolean

    /**
     * 直接输入文本（ADB Keyboard broadcast 或剪贴板+粘贴）
     * @param text 要输入的文本
     * @return 是否成功
     */
    suspend fun performDirectTextInput(text: String): Boolean = false
}
