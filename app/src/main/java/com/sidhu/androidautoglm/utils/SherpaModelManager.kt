package com.sidhu.androidautoglm.utils

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object SherpaModelManager {
    private const val TAG = "SherpaModelManager"
    
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

                val config = OfflineRecognizerConfig(
                    featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelFile.absolutePath,
                            language = "" // Auto detect
                        ),
                        tokens = tokensFile.absolutePath,
                        debug = true,
                        numThreads = 1,
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

    private fun copyAsset(context: Context, assetPath: String, outFile: File) {
        try {
            // Check if file exists and has content. 
            // Ideally we should check size match, but asset size is hard to get if compressed.
            // We assume if it exists and > 0, it's fine. 
            // To be safer, we could force copy if it's the first run or version changed, 
            // but for now let's rely on existence.
            if (outFile.exists() && outFile.length() > 0) {
                return
            }

            Log.d(TAG, "Copying asset $assetPath to ${outFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
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
}
