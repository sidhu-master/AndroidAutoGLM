package com.sidhu.androidautoglm.memory

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * MemoryManager - 核心记忆管理器
 *
 * 负责管理AI的长期记忆，包括：
 * - soul.md: AI人格定义
 * - user.md: 用户偏好
 * - tools.md: 工具清单
 *
 * 记忆文件从assets拷贝到内部存储，之后读写都使用内部存储版本
 */
class MemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val MEMORY_DIR = "memory"
        private const val SOUL_FILE = "soul.md"
        private const val USER_FILE = "user.md"
        private const val TOOLS_FILE = "tools.md"
    }

    private val memoryDir: File by lazy {
        File(context.filesDir, MEMORY_DIR)
    }

    /**
     * 初始化记忆系统
     * 首次运行时将assets/memory/下的模板拷贝到内部存储
     */
    fun init() {
        Log.d(TAG, "Initializing memory system...")
        try {
            // 创建内存目录
            if (!memoryDir.exists()) {
                memoryDir.mkdirs()
            }

            // 如果内部存储的记忆文件不存在，从assets拷贝
            copyAssetToInternalStorage(SOUL_FILE)
            copyAssetToInternalStorage(USER_FILE)
            copyAssetToInternalStorage(TOOLS_FILE)

            Log.d(TAG, "Memory system initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize memory system", e)
        }
    }

    /**
     * 从assets拷贝文件到内部存储
     */
    private fun copyAssetToInternalStorage(fileName: String) {
        val targetFile = File(memoryDir, fileName)
        if (targetFile.exists()) {
            Log.d(TAG, "$fileName already exists in internal storage, skipping copy")
            return
        }

        try {
            val assetPath = "$MEMORY_DIR/$fileName"
            context.assets.open(assetPath).use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d(TAG, "Copied $fileName from assets to internal storage")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy $fileName from assets", e)
            // 如果assets中不存在，创建空文件
            targetFile.createNewFile()
        }
    }

    /**
     * 读取指定记忆文件的内容
     */
    fun loadMemoryFile(fileName: String): String {
        val file = File(memoryDir, fileName)
        return if (file.exists()) {
            file.readText()
        } else {
            Log.w(TAG, "Memory file $fileName not found")
            ""
        }
    }

    /**
     * 保存内容到指定记忆文件
     */
    fun saveMemoryFile(fileName: String, content: String) {
        val file = File(memoryDir, fileName)
        try {
            file.writeText(content)
            Log.d(TAG, "Saved content to $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $fileName", e)
        }
    }

    /**
     * 组装完整的系统提示词
     * 顺序：soul + user + tools
     */
    fun assembleSystemPrompt(): String {
        val soul = loadMemoryFile(SOUL_FILE)
        val user = loadMemoryFile(USER_FILE)
        val tools = loadMemoryFile(TOOLS_FILE)

        return buildString {
            appendLine("=== 角色定义 ===")
            appendLine(soul)
            appendLine()
            appendLine("=== 用户信息 ===")
            appendLine(user)
            appendLine()
            appendLine("=== 可用工具 ===")
            appendLine(tools)
        }
    }

    /**
     * 更新用户信息
     */
    fun updateUserInfo(key: String, value: String) {
        val userContent = loadMemoryFile(USER_FILE)
        // 简单的key-value更新，实际使用可以用更复杂的解析
        val newContent = if (userContent.contains(key)) {
            userContent.replace("$key：[待学习]", "$key：$value")
                .replace("$key：[记录用户的特殊偏好和习惯]", "$key：$value")
        } else {
            "$userContent\n- $key：$value"
        }
        saveMemoryFile(USER_FILE, newContent)
    }
}
