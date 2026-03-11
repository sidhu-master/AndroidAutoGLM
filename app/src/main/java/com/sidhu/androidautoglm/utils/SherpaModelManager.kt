package com.sidhu.androidautoglm.utils

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object SherpaModelManager {
    private const val TAG = "SherpaModelManager"

    /** 防止多处并发调用 initModel 导致竞态（多线程同时复制/加载同一文件） */
    private val initMutex = Mutex()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotInitialized)
    val modelState: StateFlow<ModelState> = _modelState

    var recognizer: OfflineRecognizer? = null
        private set

    sealed class ModelState {
        object NotInitialized : ModelState()
        object Loading : ModelState()
        object Ready : ModelState()
        data class Error(val message: String) : ModelState()
    }

    // Initialize the model. 
    // Assumes model files are in assets/sherpa-model/
    // Required files for SenseVoice: model.onnx, tokens.txt
    suspend fun initModel(context: Context) {
        if (recognizer != null) {
            _modelState.value = ModelState.Ready
            return
        }

        initMutex.withLock {
            if (recognizer != null) {
                _modelState.value = ModelState.Ready
                return
            }
            _modelState.value = ModelState.Loading

        withContext(Dispatchers.IO) {
            try {
                val modelDir = File(context.filesDir, "sherpa-model")
                if (!modelDir.exists()) modelDir.mkdirs()

                val modelName = "model.onnx"
                val tokensName = "tokens.txt"
                
                val modelFile = File(modelDir, modelName)
                val tokensFile = File(modelDir, tokensName)

                // Ensure files are copied and valid
                copyAsset(context, "sherpa-model/$modelName", modelFile)
                copyAsset(context, "sherpa-model/$tokensName", tokensFile)

                if (!modelFile.exists() || !tokensFile.exists() || modelFile.length() == 0L || tokensFile.length() == 0L) {
                    _modelState.value = ModelState.Error("Model files missing or invalid in internal storage")
                    return@withContext
                }

                val modelSize = modelFile.length()
                if (modelSize < MIN_MODEL_SIZE_BYTES) {
                    val msg = "模型文件不完整: ${modelSize / 1024 / 1024}MB（需≥${MIN_MODEL_SIZE_BYTES / 1024 / 1024}MB）。请清除应用数据后重试，或重新构建 APK 确保 downloadModel 下载完整"
                    Log.e(TAG, msg)
                    _modelState.value = ModelState.Error(msg)
                    return@withContext
                }
                Log.i(TAG, "Sherpa 加载: model=${modelFile.absolutePath} size=${modelSize / 1024 / 1024}MB")

                val config = OfflineRecognizerConfig(
                    featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelFile.absolutePath,
                            language = "zh", // 强制中文，提升识别准确率
                            useInverseTextNormalization = true // 开启逆文本规范化（数字/标点更自然）
                        ),
                        tokens = tokensFile.absolutePath,
                        debug = false,
                        numThreads = 2,
                        modelType = "sense_voice"
                    )
                )

                // Pass assetManager = null to force loading from file paths (filesDir)
                // If we pass context.assets, Sherpa tries to load paths relative to assets
                recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.d(TAG, "Sherpa-ONNX initialized successfully")
                _modelState.value = ModelState.Ready

            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Failed to init Sherpa-ONNX: ${e.message}")
                _modelState.value = ModelState.Error(e.message ?: "Unknown error during initialization")
            }
        }
        }
    }

    /** SenseVoice int8 模型约 228MB，小于此值视为截断/损坏 */
    private const val MIN_MODEL_SIZE_BYTES = 100L * 1024 * 1024

    private fun copyAsset(context: Context, assetPath: String, outFile: File) {
        try {
            val existingSize = outFile.takeIf { it.exists() }?.length() ?: 0L
            val isModelFile = assetPath.endsWith("model.onnx")
            val sizeOk = if (isModelFile) existingSize >= MIN_MODEL_SIZE_BYTES else existingSize > 0

            if (outFile.exists() && sizeOk) {
                Log.d(TAG, "copyAsset: 跳过 $assetPath，已有 $existingSize bytes")
                return
            }
            if (outFile.exists() && isModelFile && existingSize < MIN_MODEL_SIZE_BYTES) {
                Log.w(TAG, "copyAsset: 模型仅 $existingSize bytes（应≥${MIN_MODEL_SIZE_BYTES / 1024 / 1024}MB），删除并重新复制")
                outFile.delete()
            }

            Log.d(TAG, "copyAsset: 复制 $assetPath -> ${outFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "copyAsset: 完成 $assetPath, 写入 ${outFile.length()} bytes")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset: $assetPath", e)
            // Delete partial file if failed
            if (outFile.exists()) {
                outFile.delete()
            }
        }
    }
    
    fun destroy() {
        recognizer?.release()
        recognizer = null
        _modelState.value = ModelState.NotInitialized
    }

    /** 模型就绪时执行回调；若已就绪则立即执行，否则等待 Ready 后执行 */
    fun runWhenReady(scope: CoroutineScope, context: Context, block: () -> Unit) {
        when (val state = _modelState.value) {
            is ModelState.Ready -> block()
            is ModelState.Error -> { /* 不执行 */ }
            else -> scope.launch(Dispatchers.Main) {
                if (state is ModelState.NotInitialized) initModel(context)
                val final = _modelState.first { it is ModelState.Ready || it is ModelState.Error }
                if (final is ModelState.Ready) block()
            }
        }
    }
}
