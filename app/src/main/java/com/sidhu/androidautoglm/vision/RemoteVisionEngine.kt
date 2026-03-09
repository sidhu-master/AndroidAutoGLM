package com.sidhu.androidautoglm.vision

import android.graphics.Bitmap
import android.util.Log
import com.sidhu.androidautoglm.network.ModelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VisionEngine 使用远端大模型的 vision API 进行屏幕描述和元素定位。
 */
class RemoteVisionEngine(private val client: ModelClient?) : VisionEngine {

    companion object {
        private const val TAG = "RemoteVisionEngine"

        private const val PROMPT_DESCRIBE = """简要描述当前手机屏幕：
1) 应用名和页面类型
2) 主要UI元素（按钮、输入框、列表、图标等）及其大致位置
3) 屏幕上的关键文字内容
不超过200字，用中文回答。"""

        private const val PROMPT_LOCATE_TEMPLATE = """在这张手机截图中，找到「%s」的中心位置。
返回格式：(x,y)
坐标范围 0-999（左上角为(0,0)，右下角为(999,999)）。
只返回坐标，不要其他内容。"""

        private const val PROMPT_VERIFY_TEMPLATE = """根据这张手机截图，回答以下问题：
%s
用简短的中文回答。"""
    }

    override suspend fun initialize() {
        // 远端 API 无需初始化
    }

    override fun isReady(): Boolean = client != null && client.supportsVision()

    override suspend fun describeScreen(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val c = client
        if (c == null || !c.supportsVision()) {
            Log.e(TAG, "describeScreen: client not available or no vision support")
            return@withContext "（视觉模型不可用）"
        }
        val result = c.sendVisionRequest(PROMPT_DESCRIBE, bitmap)
        if (result.startsWith("Error")) {
            Log.e(TAG, "describeScreen API error: $result")
            "（描述失败: ${result.take(80)}）"
        } else {
            Log.d(TAG, "describeScreen: ${result.take(100)}...")
            result
        }
    }

    override suspend fun locateElement(bitmap: Bitmap, target: String): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            val c = client
            if (c == null || !c.supportsVision()) return@withContext null
            val prompt = String.format(PROMPT_LOCATE_TEMPLATE, target)
            val result = c.sendVisionRequest(prompt, bitmap)
            if (result.startsWith("Error")) {
                Log.e(TAG, "locateElement API error: $result")
                return@withContext null
            }
            Log.d(TAG, "locateElement '$target': $result")
            parseCoordinates(result)
        }

    override suspend fun verifyContent(bitmap: Bitmap, query: String): String =
        withContext(Dispatchers.IO) {
            val c = client
            if (c == null || !c.supportsVision()) return@withContext "（视觉模型不可用）"
            val prompt = String.format(PROMPT_VERIFY_TEMPLATE, query)
            val result = c.sendVisionRequest(prompt, bitmap)
            if (result.startsWith("Error")) "（验证失败）" else result
        }

    override fun destroy() {
        // 远端无资源需释放
    }

    private fun parseCoordinates(text: String): Pair<Int, Int>? {
        val regex = Regex("""[（(]\s*(\d+)\s*[,，]\s*(\d+)\s*[)）]""")
        val match = regex.find(text) ?: return null
        val x = match.groupValues[1].toIntOrNull() ?: return null
        val y = match.groupValues[2].toIntOrNull() ?: return null
        return Pair(x.coerceIn(0, 999), y.coerceIn(0, 999))
    }
}
