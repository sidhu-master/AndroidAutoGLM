package com.sidhu.androidautoglm.memory

import android.content.Context
import android.util.Log
import com.sidhu.androidautoglm.network.ModelClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * MemoryManager - 核心记忆管理器
 *
 * 负责管理AI的长期记忆：
 * - memory/YYYY-MM-DD.md: 每日日志（按日追加）
 * - 主AI提示词：使用 ModelClient 常量（纯内存，无 md 文件）
 */
class MemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val MEMORY_DIR = "memory"
    }

    private val memoryDir: File by lazy {
        File(context.filesDir, MEMORY_DIR)
    }

    /** 禁止添加检查屏幕等元步骤 */
    private val FORBIDDEN_META_STEPS = """
**禁止**：不要添加「检查屏幕」「查看当前界面」「首先让我检查」「我需要先检查」等元步骤——系统会在每步自动截图，任务描述应直接写用户要完成的操作。
""".trim()

    /**
     * 初始化记忆系统（创建 memory 目录，用于每日日志）
     */
    fun init() {
        Log.d(TAG, "Initializing memory system...")
        try {
            if (!memoryDir.exists()) {
                memoryDir.mkdirs()
            }
            Log.d(TAG, "Memory system initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize memory system", e)
        }
    }

    /** 主AI - 任务审查提示词（纯内存，来自 ModelClient 常量） */
    fun getTaskReviewPrompt(): String =
        ModelClient.TASK_REVIEW_PROMPT.trim() + "\n\n" + FORBIDDEN_META_STEPS

    /** 主AI - Call_API 总结提示词 */
    fun getCallApiPrompt(): String = ModelClient.CALL_API_PROMPT.trim()

    /** 主AI - 历史摘要提示词 */
    fun getSummarizePrompt(): String = ModelClient.SUMMARIZE_PROMPT.trim()

    private fun dailyFileName(date: Date = Date()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date) + ".md"
    }

    /**
     * 追加内容到当日记忆文件
     */
    fun appendToDailyMemory(content: String, date: Date = Date()) {
        if (content.isBlank()) return
        if (!memoryDir.exists()) memoryDir.mkdirs()
        val fileName = dailyFileName(date)
        val file = File(memoryDir, fileName)
        try {
            val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            val entry = "\n\n---\n[$timestamp] $content"
            file.appendText(entry)
            Log.d(TAG, "Appended to daily memory: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to $fileName", e)
        }
    }

    /**
     * 加载今日和昨日的记忆
     */
    fun loadTodayAndYesterdayMemory(): String {
        val cal = Calendar.getInstance()
        val today = cal.time
        cal.add(Calendar.DAY_OF_MONTH, -1)
        val yesterday = cal.time
        val todayFile = File(memoryDir, dailyFileName(today))
        val yesterdayFile = File(memoryDir, dailyFileName(yesterday))
        return buildString {
            if (yesterdayFile.exists()) {
                append("【昨日记忆】\n").append(yesterdayFile.readText().trim()).append("\n\n")
            }
            if (todayFile.exists()) {
                append("【今日记忆】\n").append(todayFile.readText().trim())
            }
        }.trim()
    }
}
