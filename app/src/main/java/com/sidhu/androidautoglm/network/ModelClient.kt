package com.sidhu.androidautoglm.network

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

interface OpenAIApi {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): retrofit2.Response<ChatResponse>
}

class ModelClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelName: String,
    private val isGemini: Boolean = false
) {

    private val openAiApi: OpenAIApi?
    private val geminiApi: GeminiApi?
    private val doubaoApi: DoubaoApi?

    // 缓存 isDoubao 判断，避免 sendRequest 每次重复计算
    private val isDoubao = modelName.contains("doubao", ignoreCase = true) ||
            baseUrl.contains("volces.com", ignoreCase = true)

    init {
        val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        Log.d("AutoGLM_Debug", "ModelClient initialized with Base URL: $finalBaseUrl")
        
        val retrofit = Retrofit.Builder()
            .baseUrl(finalBaseUrl)
            .client(sharedHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        if (isGemini) {
            openAiApi = null
            geminiApi = retrofit.create(GeminiApi::class.java)
            doubaoApi = null
        } else if (isDoubao) {
            openAiApi = null
            geminiApi = null
            doubaoApi = retrofit.create(DoubaoApi::class.java)
        } else {
            openAiApi = retrofit.create(OpenAIApi::class.java)
            geminiApi = null
            doubaoApi = null
        }
    }

    suspend fun sendRequest(history: List<Message>, screenshot: Bitmap?): String {
        Log.d("AutoGLM_Debug", "ModelClient.sendRequest called. isGemini: $isGemini")
        return if (isGemini) {
            sendGeminiRequest(history)
        } else if (isDoubao) {
            sendDoubaoRequest(history)
        } else {
            sendOpenAIRequest(history)
        }
    }

    /** 是否支持视觉 API（用于屏幕描述、元素定位） */
    fun supportsVision(): Boolean {
        if (isGemini || isDoubao) return true
        val isDeepSeek = modelName.contains("deepseek", ignoreCase = true) ||
            baseUrl.contains("deepseek", ignoreCase = true)
        return !isDeepSeek
    }

    /** 发送单次视觉请求：图片 + 文本提示，返回模型回复 */
    suspend fun sendVisionRequest(prompt: String, bitmap: Bitmap): String {
        val base64 = bitmapToBase64(bitmap)
        val imageUrl = "data:image/jpeg;base64,$base64"
        val content = listOf(
            ContentItem(type = "text", text = prompt),
            ContentItem(type = "image_url", imageUrl = ImageUrl(url = imageUrl))
        )
        val history = listOf(Message("user", content))
        return sendRequest(history, null)
    }

    private suspend fun sendGeminiRequest(history: List<Message>): String {
        Log.d("AutoGLM_Debug", "sendGeminiRequest called")
        if (geminiApi == null) {
            Log.e("AutoGLM_Debug", "Gemini API not initialized")
            return "Error: Gemini API not initialized"
        }

        val geminiContents = mutableListOf<GeminiContent>()
        var currentRole: String? = null
        var currentParts = mutableListOf<GeminiPart>()

        history.forEach { msg ->
            val mappedRole = if (msg.role == "system" || msg.role == "user") "user" else "model"
            
            val parts = mutableListOf<GeminiPart>()
            if (msg.role == "system") {
                 parts.add(GeminiPart(text = "System Instruction: ${msg.content}"))
            } else if (msg.content is String) {
                parts.add(GeminiPart(text = msg.content as String))
            } else if (msg.content is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val list = msg.content as List<ContentItem>
                list.forEach { item ->
                    if (item.type == "text") {
                        parts.add(GeminiPart(text = item.text))
                    } else if (item.type == "image_url") {
                        val url = item.imageUrl?.url ?: ""
                        if (url.startsWith("data:")) {
                            val commaIndex = url.indexOf(",")
                            if (commaIndex != -1) {
                                val base64Data = url.substring(commaIndex + 1)
                                val mimeType = url.substring(5, url.indexOf(";"))
                                parts.add(GeminiPart(inline_data = GeminiInlineData(mimeType, base64Data)))
                            }
                        }
                    }
                }
            }

            if (mappedRole == currentRole) {
                currentParts.addAll(parts)
            } else {
                if (currentRole != null) {
                    geminiContents.add(GeminiContent(role = currentRole!!, parts = currentParts.toList()))
                }
                currentRole = mappedRole
                currentParts = parts.toMutableList()
            }
        }
        
        if (currentRole != null) {
            geminiContents.add(GeminiContent(role = currentRole!!, parts = currentParts.toList()))
        }

        val request = GeminiRequest(
            contents = geminiContents,
            generationConfig = GeminiGenerationConfig(temperature = 0.0)
        )
        Log.d("AutoGLM_Debug", "Gemini Request prepared. Content count: ${geminiContents.size}")

        try {
            // Default model fallback if not specified properly for Gemini
            val model = if (modelName.isBlank() || modelName == "autoglm-phone") "gemini-1.5-flash-latest" else modelName
            Log.d("AutoGLM_Debug", "Calling Gemini API with model: $model")
            val response = geminiApi.generateContent(model, apiKey, request)
            if (response.isSuccessful) {
                Log.d("AutoGLM_Debug", "Gemini API success")
                val text = response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                return text ?: ""
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("AutoGLM_Debug", "Gemini API Error: $errorBody")
                val errorMessage = try {
                    val json = org.json.JSONObject(errorBody ?: "")
                    val errorObj = json.optJSONObject("error")
                    errorObj?.optString("message") ?: errorBody
                } catch (e: Exception) {
                    errorBody
                }
                return "Error: ${response.code()} $errorMessage"
            }
        } catch (e: CancellationException) {
            // Rethrow cancellation to let the ViewModel handle it properly
            throw e
        } catch (e: Exception) {
            Log.e("AutoGLM_Debug", "Gemini API Exception", e)
            val errorMessage = when (e) {
                is javax.net.ssl.SSLHandshakeException -> "Network Error: SSL Handshake failed. Check your VPN or Proxy settings."
                is java.net.SocketTimeoutException -> "Network Error: Connection timed out. Check your internet connection."
                is java.net.UnknownHostException -> "Network Error: Unknown host. Check the Base URL."
                else -> "Error: ${e.message}"
            }
            return errorMessage
        }
    }

    private suspend fun sendDoubaoRequest(history: List<Message>): String {
        Log.d("AutoGLM_Debug", "sendDoubaoRequest called")
        if (doubaoApi == null) {
            Log.e("AutoGLM_Debug", "Doubao API not initialized")
            return "Error: Doubao API not initialized"
        }

        // Convert Message to DoubaoMessage
        val doubaoMessages = history.map { msg ->
            if (msg.content is String) {
                DoubaoMessage(msg.role, msg.content)
            } else {
                @Suppress("UNCHECKED_CAST")
                val contentList = msg.content as List<ContentItem>
                val doubaoContentList = contentList.map { item ->
                    if (item.type == "text") {
                        DoubaoContentItem(type = "text", text = item.text)
                    } else {
                        DoubaoContentItem(type = "image_url", imageUrl = DoubaoImageUrl(item.imageUrl?.url ?: ""))
                    }
                }
                DoubaoMessage(msg.role, doubaoContentList)
            }
        }

        val request = DoubaoRequest(
            model = modelName,
            messages = doubaoMessages,
            maxCompletionTokens = 65535, // Increased to match user example
            stream = false,
            reasoningEffort = "medium" // Added per user example
        )

        try {
            val response = doubaoApi.chatCompletion("Bearer $apiKey", request)
            if (response.isSuccessful) {
                return response.body()?.choices?.firstOrNull()?.message?.content ?: ""
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("AutoGLM_Debug", "Doubao API Error: $errorBody")
                val errorMessage = try {
                    val json = org.json.JSONObject(errorBody ?: "")
                    val errorObj = json.optJSONObject("error")
                    errorObj?.optString("message") ?: errorBody
                } catch (e: Exception) {
                    errorBody
                }
                return "Error: ${response.code()} $errorMessage"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AutoGLM_Debug", "Doubao API Exception", e)
            return "Error: ${e.message}"
        }
    }

    private suspend fun sendOpenAIRequest(history: List<Message>): String {
        Log.d("AutoGLM_Debug", "sendOpenAIRequest called")
        if (openAiApi == null) {
            Log.e("AutoGLM_Debug", "OpenAI API not initialized")
            return "Error: OpenAI API not initialized"
        }
        
        // DeepSeek API (and some others) might not support image_url or the array content format.
        // Specifically, the error "unknown variant image_url, expected text" implies the API expects a simple string content.
        val isDeepSeek = modelName.contains("deepseek", ignoreCase = true) || baseUrl.contains("deepseek", ignoreCase = true)
        
        val finalMessages = if (isDeepSeek) {
             Log.w("AutoGLM_Debug", "DeepSeek detected, stripping images from request to avoid API error.")
             history.map { msg ->
                if (msg.content is List<*>) {
                    val textContent = (msg.content as List<*>)
                        .filterIsInstance<ContentItem>()
                        .filter { it.type == "text" }
                        .joinToString("\n") { it.text ?: "" }
                    Message(msg.role, textContent)
                } else {
                    msg
                }
            }
        } else {
            history
        }
        
        val request = ChatRequest(
            model = modelName,
            messages = finalMessages,
            maxTokens = 3000,
            temperature = 0.0,
            topP = 0.85,
            frequencyPenalty = 0.2
        )
        
        try {
            val response = openAiApi.chatCompletion("Bearer $apiKey", request)
            if (response.isSuccessful) {
                return response.body()?.choices?.firstOrNull()?.message?.content ?: ""
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("ModelClient", "API Error: $errorBody")
                val errorMessage = try {
                    val json = org.json.JSONObject(errorBody ?: "")
                    val errorObj = json.optJSONObject("error")
                    errorObj?.optString("message") ?: errorBody
                } catch (e: Exception) {
                    errorBody
                }
                return "Error: ${response.code()} $errorMessage"
            }
        } catch (e: CancellationException) {
            // Rethrow cancellation to let the ViewModel handle it properly
            throw e
        } catch (e: Exception) {
            Log.e("ModelClient", "Exception", e)
            return "Error: ${e.message}"
        }
    }
    
    companion object {
        /** 子AI系统提示（借鉴 Open-AutoGLM prompts_zh.py）：完整操作说明 + 18条规则 */
        const val SYSTEM_PROMPT = """
你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。你必须严格按照要求输出以下格式：
<think>{think}</think>
<answer>{action}</answer>

其中：{think} 是对你为何选择这个操作的简短推理说明；{action} 是本次执行的具体操作指令，必须严格遵循以下定义的指令格式。

操作指令及其作用如下：
- do(action="Launch", app="xxx") 启动目标app，比主屏幕导航更快
- do(action="Tap", element=[x,y]) 点击屏幕上的特定点，坐标0-999相对坐标
- do(action="Tap", element=[x,y], message="重要操作") 基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时使用
- do(action="Type", text="xxx") 在当前聚焦的输入框中输入文本，使用前请确保输入框已聚焦
- do(action="Type_Name", text="xxx") 输入人名的操作，基本功能同Type
- do(action="Swipe", start=[x1,y1], end=[x2,y2]) 滑动，从起始坐标拖动到结束坐标
- do(action="Long Press", element=[x,y]) 长按
- do(action="Double Tap", element=[x,y]) 双击
- do(action="Back") 返回上一页或关闭对话框
- do(action="Home") 回到系统桌面
- do(action="Wait", duration="x seconds") 等待页面加载
- do(action="Take_over", message="xxx") 登录或验证码等需要用户协助时使用
- do(action="Interact") 有多个满足条件的选项时，询问用户如何选择
- do(action="Note", message="True") 记录当前页面内容以便后续总结
- do(action="Call_API", instruction="xxx") 总结或评论当前页面或已记录的内容
- finish(message="xxx") 结束任务

坐标系统从左上角(0,0)到右下角(999,999)。

必须遵循的规则：
1. 执行任何操作前，先检查当前app是否是目标app，如果不是先执行Launch
2. 进入无关页面时先执行Back；Back无效时点击左上角返回键或右上角X关闭
3. 页面未加载出内容时，最多连续Wait三次，否则Back重新进入
4. 网络问题时点击重新加载
5. 找不到目标信息时尝试Swipe滑动查找
6. 筛选条件可放宽要求
7. 小红书总结类任务要筛选图文笔记
8. 购物车任务：已有选中商品时先全选再取消全选，再找目标商品
9. 外卖任务：店铺购物车有其它商品时先清空再购买指定外卖
10. 严格遵循用户意图，可多次搜索、滑动查找
11. 日期选择时若滑动方向与预期相反，向反方向滑动
12. 多个项目栏时逐个查找，不要在同一项目栏多次查找陷入死循环
13. 执行前检查上一步是否生效，未生效可等待、调整位置重试或跳过并在finish说明
14. 滑动不生效时调整起始点、增大距离；可能已滑到底，向反方向滑动
15. 游戏战斗页面有自动战斗时开启
16. 搜索无结果时返回上一级重试，三次后仍无结果则finish说明原因
17. 结束前仔细检查任务是否完整准确完成，错选漏选多选需返回纠正
18. 发送/提交类任务：Type 后 Tap 发送/提交按钮，然后根据下一步屏幕描述验证界面已有反应（如消息在聊天记录中、评论已显示），确认成功才能 finish。界面未变化可 Wait 重试或 finish 说明未生效
"""

        /** 主模型用于任务审查：纠错字、整理任务描述、根据本机应用选择目标 App */
        const val TASK_REVIEW_PROMPT = """
你负责审查并整理用户任务，供后续自动化执行。请完成：

1) 检查错别字并修正
2) 若用户未指定应用，但任务需要特定 App（如订外卖、购物、打车、看视频等），根据「本机已安装应用」列表选择最合适的一个，并在任务中明确写出应用名。例如："帮我订外卖" → "在美团上帮我订外卖"（若本机有美团）
3) 整理成清晰、可执行的任务描述

输出要求：只输出整理后的任务内容，不要其他说明。若任务已清晰且已指定应用，可原样或微调输出。
"""

        /** 主模型用于 Call_API 的总结/评论提示（仅文字，不传图） */
        const val CALL_API_PROMPT = """
你是一个内容总结助手。根据用户指令和对话记录中的文字内容，进行总结或评论。
要求：用中文，简洁准确，100字以内。只输出总结内容，不要其他说明。
"""

        /** 主模型用于记忆管理的摘要提示 */
        const val SUMMARIZE_PROMPT = """
请将以下任务执行历史总结为简洁摘要。必须保留：
1) 用户原始目标
2) 已完成的步骤（列出关键操作）
3) 当前进度/状态

用中文，200字以内。只输出摘要内容，不要其他说明。
"""

        // 全局共享的 OkHttpClient，复用连接池和线程池，避免每次实例化重建
        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun bitmapToBase64(bitmap: Bitmap): String {
            // 1. Resize if too large (max dimension 1024) to avoid server 500 errors
            val maxDimension = 1024
            val scale = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            } else {
                1.0f
            }
            
            val isScaled = scale < 1.0f
            val finalBitmap = if (isScaled) {
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Log.d("AutoGLM_Debug", "Resizing image from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            return try {
                val outputStream = ByteArrayOutputStream()
                // quality=80 在 AI 视觉识别质量不变的前提下，体积比 100 缩小约 5 倍
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val bytes = outputStream.toByteArray()
                Log.d("AutoGLM_Debug", "Image Base64 size: ${bytes.size / 1024} KB")
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                // 仅回收缩放后的新 Bitmap，不回收调用方传入的原图
                if (isScaled) finalBitmap.recycle()
            }
        }
    }
}
