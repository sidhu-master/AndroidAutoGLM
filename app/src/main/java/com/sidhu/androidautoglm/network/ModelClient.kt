package com.sidhu.androidautoglm.network

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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

    init {
        // val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE }
        val client = OkHttpClient.Builder()
            // .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        Log.d("AutoGLM_Debug", "ModelClient initialized with Base URL: $finalBaseUrl")
        
        val retrofit = Retrofit.Builder()
            .baseUrl(finalBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val isDoubao = modelName.contains("doubao", ignoreCase = true) || baseUrl.contains("volces.com", ignoreCase = true)

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
        val isDoubao = modelName.contains("doubao", ignoreCase = true) || baseUrl.contains("volces.com", ignoreCase = true)
        return if (isGemini) {
            sendGeminiRequest(history)
        } else if (isDoubao) {
            sendDoubaoRequest(history)
        } else {
            sendOpenAIRequest(history)
        }
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
        const val SYSTEM_PROMPT = """
你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
你必须严格按照要求输出以下格式：
<think>{think}</think>
<answer>{action}</answer>

其中：
- {think} 是对你为什么选择这个操作的简短推理说明。
- {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

**重要提示：**
- 屏幕底部的悬浮窗是运行你的载体，请**绝对不要**关闭它，也不要对其进行任何点击操作（例如停止按钮）。
- 你的任务是操作其他应用，忽略悬浮窗的存在。

操作指令及其作用如下：
- do(action="Launch", app="xxx")  
    Launch是启动目标app的操作，这比通过主屏幕导航更快。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y])  
    Tap是点击操作，点击屏幕上的特定点。可用此操作点击按钮、选择项目、从主屏幕打开应用程序，或与任何可点击的用户界面元素进行交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y], message="重要操作")  
    基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
- do(action="Type", text="xxx")  
    Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前，请确保输入框已被聚焦（先点击它）。输入的文本将像使用键盘输入一样输入。重要提示：手机可能正在使用 ADB 键盘，该键盘不会像普通键盘那样占用屏幕空间。要确认键盘已激活，请查看屏幕底部是否显示 'ADB Keyboard {ON}' 类似的文本，或者检查输入框是否处于激活/高亮状态。不要仅仅依赖视觉上的键盘显示。自动清除文本：当你使用输入操作时，输入框中现有的任何文本（包括占位符文本和实际输入）都会在输入新文本前自动清除。你无需在输入前手动清除文本——直接使用输入操作输入所需文本即可。操作完成后，你将自动收到结果状态的截图。
- do(action="Type_Name", text="xxx")  
    Type_Name是输入人名的操作，基本功能同Type。
- do(action="Interact")  
    Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])  
    Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。可用于滚动内容、在屏幕之间导航、下拉通知栏以及项目栏或进行基于手势的导航。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。滑动持续时间会自动调整以实现自然的移动。此操作完成后，您将自动收到结果状态的截图。
- do(action="Note", message="True")  
    记录当前页面内容以便后续总结。
- do(action="Call_API", instruction="xxx")  
    总结或评论当前页面或已记录的内容。
- do(action="Long Press", element=[x,y])  
    Long Pres是长按操作，在屏幕上的特定点长按指定时间。可用于触发上下文菜单、选择文本或激活长按交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的屏幕截图。
- do(action="Double Tap", element=[x,y])  
    Double Tap在屏幕上的特定点快速连续点按两次。使用此操作可以激活双击交互，如缩放、选择文本或打开项目。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Take_over", message="xxx")  
    Take_over是接管操作，表示在登录和验证阶段需要用户协助。
- do(action="Back")  
    导航返回到上一个屏幕或关闭当前对话框。相当于按下 Android 的返回按钮。使用此操作可以从更深的屏幕返回、关闭弹出窗口或退出当前上下文。此操作完成后，您将自动收到结果状态的截图。
- do(action="Home") 
    Home是回到系统桌面的操作，相当于按下 Android 主屏幕按钮。使用此操作可退出当前应用并返回启动器，或从已知状态启动新任务。此操作完成后，您将自动收到结果状态的截图。
- do(action="Wait", duration="x seconds")  
    等待页面加载，x为需要等待多少秒。
- finish(message="xxx")  
    finish是结束任务的操作，表示准确完整完成任务，message是终止信息。 

必须遵循的规则：
1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入。
4. 如果页面显示网络问题，需要重新加载，请点击重新加载。
5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
7. 在做小红书总结类任务时一定要筛选图文笔记。
8. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时，如果购物车里已经有商品被选中时，你需要点击全选后再点击取消全选，再去找需要购买或者删除的商品。
9. 在做外卖任务时，如果相应店铺购物车里已经有其他商品你需要先把购物车清空再去购买用户指定的外卖。
10. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买，如果无法找到可以下单，并说明某个商品未找到。
11. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索，滑动查找。比如（i）用户要求点一杯咖啡，要咸的，你可以直接搜索咸咖啡，或者搜索咖啡后滑动查找咸的咖啡，比如海盐咖啡。（ii）用户要找到XX群，发一条消息，你可以先搜索XX群，找不到结果后，将"群"字去掉，搜索XX重试。（iii）用户要找到宠物友好的餐厅，你可以搜索餐厅，找到筛选，找到设施，选择可带宠物，或者直接搜索可带宠物，必要时可以使用AI搜索。
12. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找。
13. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务，一定不要在同一项目栏多次查找，从而陷入死循环。
14. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效，可能因为app反应较慢，请先稍微等待一下，如果还是不生效请调整一下点击位置重试，如果仍然不生效请跳过这一步继续任务，并在finish message说明点击不生效。
15. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试，如果还是不生效，有可能是已经滑到底了，请继续向反方向滑动，直到顶部或底部，如果仍然没有符合要求的结果，请跳过这一步继续任务，并在finish message说明但没找到要求的项目。
16. 在做游戏任务时如果在战斗页面如果有自动战斗一定要开启自动战斗，如果多轮历史状态相似要检查自动战斗是否开启。
17. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索，如果尝试三次返回上一级搜索后仍然没有符合要求的结果，执行 finish(message="原因")。
18. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正。
"""

        fun bitmapToBase64(bitmap: Bitmap): String {
            // 1. Resize if too large (max dimension 1024) to avoid server 500 errors
            val maxDimension = 1024
            val scale = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
                ratio
            } else {
                1.0f
            }
            
            val finalBitmap = if (scale < 1.0f) {
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Log.d("AutoGLM_Debug", "Resizing image from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            // Switch to JPEG with 70% quality to reduce payload size and avoid 500 errors
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val bytes = outputStream.toByteArray()
            Log.d("AutoGLM_Debug", "Image Base64 size: ${bytes.size / 1024} KB")
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}
