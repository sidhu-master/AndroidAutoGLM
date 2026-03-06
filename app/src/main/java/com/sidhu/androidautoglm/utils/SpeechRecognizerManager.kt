package com.sidhu.androidautoglm.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 语音识别管理器（阶段1升级版）
 * 相比原版新增：
 *  - VAD 静音检测（自动停止录音）
 *  - AEC 回声消除
 *  - NoiseSuppressor 噪声抑制
 *  - AGC 自动增益
 *  - 手动增益放大（兼容安静环境）
 *  - 实时部分结果回调
 */
class SpeechRecognizerManager(private val context: Context) {

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel

    // 实时识别部分结果（流式显示字幕）
    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // 音频效果
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    // 完整音频缓冲，用于最终离线识别
    private val audioBuffer = ArrayList<Float>()

    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    // VAD 参数（与 xiaoan 对齐）
    private val SILENCE_THRESHOLD_DB = -40.0f    // 低于该分贝视为静音
    private val SILENCE_DURATION_MS = 1500L      // 持续静音超过 1.5s 自动停止
    private val NO_SPEECH_TIMEOUT_MS = 8000L     // 8s 未检测到语音则超时
    private val COMMAND_GAIN_FACTOR = 4.0f       // 手动增益系数（弥补麦克风敏感度不足）

    companion object {
        private const val TAG = "SpeechRecognizerManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        onResultCallback: (String) -> Unit,
        onErrorCallback: (String) -> Unit,
        onPartialResultCallback: ((String) -> Unit)? = null
    ) {
        val modelState = SherpaModelManager.modelState.value
        if (SherpaModelManager.recognizer == null) {
            if (modelState is SherpaModelManager.ModelState.Error) {
                onErrorCallback("模型错误: ${modelState.message}")
            } else {
                onErrorCallback("语音模型尚未加载完成，请稍后重试")
            }
            return
        }

        if (_isListening.value) return

        onResult = onResultCallback
        onError = onErrorCallback
        _partialResult.value = ""
        audioBuffer.clear()

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 语音通信模式，AEC 更有效
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBufferSize * 2, SAMPLE_RATE * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onErrorCallback("麦克风初始化失败")
                return
            }

            // 初始化音频效果
            initAudioEffects(audioRecord!!.audioSessionId)

            audioRecord?.startRecording()
            _isListening.value = true

            startRecordingLoop(onPartialResultCallback)

        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            _isListening.value = false
            onErrorCallback(e.message ?: "启动录音失败")
        }
    }

    private fun initAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                Log.d(TAG, "AEC enabled")
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "NoiseSuppressor enabled")
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)
                gainControl?.enabled = true
                Log.d(TAG, "AGC enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio effects", e)
        }
    }

    private fun releaseAudioEffects() {
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
        gainControl?.release(); gainControl = null
    }

    private fun startRecordingLoop(onPartialResultCallback: ((String) -> Unit)?) {
        val recognizer = SherpaModelManager.recognizer ?: return

        recordingJob = scope.launch {
            val readSize = 1024
            val buffer = ShortArray(readSize)

            var hasSpeechStarted = false
            var lastActiveTime = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()

            var partialStream = recognizer.createStream()
            var partialFrames = 0

            try {
                while (_isListening.value) {
                    val read = audioRecord?.read(buffer, 0, readSize) ?: 0
                    if (read > 0) {
                        var sum = 0.0

                        // 带增益的样本处理
                        val floatSamples = FloatArray(read) { i ->
                            val raw = buffer[i]
                            var amplified = (raw * COMMAND_GAIN_FACTOR).toInt()
                            amplified = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            val f = amplified / 32768.0f
                            sum += f * f
                            f
                        }

                        // 计算音量分贝（用于 UI 反馈）
                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else -60.0
                        withContext(Dispatchers.Main) { _soundLevel.value = db.toFloat() }

                        val currentTime = System.currentTimeMillis()

                        // VAD：检测到声音时更新激活时间
                        if (db > SILENCE_THRESHOLD_DB) {
                            if (!hasSpeechStarted) {
                                hasSpeechStarted = true
                                Log.d(TAG, "Speech started")
                            }
                            lastActiveTime = currentTime
                        }

                        // 追加到全量缓冲
                        synchronized(audioBuffer) {
                            floatSamples.forEach { audioBuffer.add(it) }
                        }

                        // 实时部分识别（如果有语音）
                        if (hasSpeechStarted) {
                            partialStream.acceptWaveform(floatSamples, SAMPLE_RATE)
                            partialFrames += read

                            if (partialFrames > 8000) { // 约 0.5s 解码一次
                                recognizer.decode(partialStream)
                                val partial = recognizer.getResult(partialStream).text.trim()
                                if (partial.isNotBlank()) {
                                    Log.i(TAG, "SpeechRecognizer partial: \"$partial\"")
                                    withContext(Dispatchers.Main) {
                                        _partialResult.value = partial
                                        onPartialResultCallback?.invoke(partial)
                                    }
                                }
                                partialFrames = 0
                            }
                        }

                        // VAD 超时判断
                        if (hasSpeechStarted) {
                            if (currentTime - lastActiveTime > SILENCE_DURATION_MS) {
                                Log.d(TAG, "Silence detected, stopping")
                                break
                            }
                        } else {
                            if (currentTime - startTime > NO_SPEECH_TIMEOUT_MS) {
                                withContext(Dispatchers.Main) {
                                    onError?.invoke("未检测到语音，请重试")
                                }
                                _isListening.value = false
                                return@launch
                            }
                        }
                    } else if (read < 0) {
                        break
                    }
                    delay(20)
                }
            } finally {
                try { partialStream.release() } catch (e: Exception) {}
            }

            // 最终识别
            _isListening.value = false
            withContext(Dispatchers.Main) { _soundLevel.value = 0f }

            cleanupRecorder()

            if (hasSpeechStarted) {
                processFullAudio()
            }
        }
    }

    private suspend fun processFullAudio() {
        val recognizer = SherpaModelManager.recognizer ?: return

        val samples: FloatArray
        synchronized(audioBuffer) {
            if (audioBuffer.isEmpty()) return
            samples = audioBuffer.toFloatArray()
        }

        try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()

            val text = result.text.trim()
            Log.i(TAG, "SpeechRecognizer final: \"$text\" (blank=${text.isBlank()}, hallucination=${isHallucination(text)})")
            if (text.isNotBlank() && !isHallucination(text)) {
                Log.i(TAG, "SpeechRecognizer -> onResult(\"$text\")")
                withContext(Dispatchers.Main) { onResult?.invoke(text) }
            } else {
                Log.w(TAG, "SpeechRecognizer -> onError (rejected)")
                withContext(Dispatchers.Main) { onError?.invoke("未能识别内容，请重试") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFullAudio failed", e)
            withContext(Dispatchers.Main) { onError?.invoke("识别失败: ${e.message}") }
        }
    }

    private fun isHallucination(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.isBlank() || lower == "." || lower == "。" || lower == "，"
    }

    suspend fun stopListening() {
        if (!_isListening.value) return
        _isListening.value = false
        val job = recordingJob
        recordingJob = null
        job?.cancel()
        job?.join()
        cleanupRecorder()
        processFullAudio()
    }

    suspend fun cancel() {
        _isListening.value = false
        recordingJob?.cancel()
        recordingJob = null
        cleanupRecorder()
        synchronized(audioBuffer) { audioBuffer.clear() }
        withContext(Dispatchers.Main) { _soundLevel.value = 0f; _partialResult.value = "" }
    }

    private fun cleanupRecorder() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "cleanupRecorder: ${e.message}")
        }
        audioRecord = null
        releaseAudioEffects()
    }

    fun destroy() {
        scope.launch { cancel() }
    }
}
