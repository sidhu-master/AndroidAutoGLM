package com.sidhu.androidautoglm.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 唤醒词检测器
 *
 * 功能：
 *  - 唤醒词模式（startWakeWordMode）：后台持续录音，检测到唤醒词后触发回调
 *  - 指令模式（startCommandMode）：带 VAD 静音检测，自动停止并返回最终文字
 *  - AEC 回声消除 + NoiseSuppressor 噪声抑制
 *  - 手动增益放大（兼容安静环境）
 *  - WakeLock 防止 CPU 休眠导致延迟
 *  - 录音时静音媒体音量（防止外放干扰）
 *
 * 使用：
 *  1. WakeWordDetector.initialize(context)
 *  2. WakeWordDetector.bindService(service) - 可选，用于唤醒后回调
 *  3. WakeWordDetector.onWakeUpAction = { ... } - 唤醒词检测成功后的回调
 *  4. WakeWordDetector.onCommandReceived = { text -> ... }
 *  5. WakeWordDetector.startWakeWordMode(context)
 */
object WakeWordDetector {

    /**
     * 唤醒词检测成功后的动作回调（替代 bindService）
     */
    var onWakeUpAction: (() -> Unit)? = null
    private const val TAG = "WakeWordDetector"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // 增益系数
    private const val WAKE_WORD_GAIN_FACTOR = 5.0f
    private const val COMMAND_GAIN_FACTOR = 4.0f

    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var detectionJob: Job? = null
    private var commandJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var _wakeWord = MutableStateFlow("皮皮虾")
    val wakeWord: StateFlow<String> = _wakeWord

    // 实时指令流（用于显示字幕）
    private val _realtimeCommand = MutableSharedFlow<String>()
    val realtimeCommand = _realtimeCommand.asSharedFlow()

    /** 检测到唤醒词时回调 */
    var onWakeWordDetected: (() -> Unit)? = null

    /** 成功识别到指令时回调（最终文本） */
    var onCommandReceived: ((String) -> Unit)? = null

    /** 错误时回调 */
    var onError: ((String) -> Unit)? = null

    // 绑定 Service（用于调用 handleWakeUpAction，仅 Shizuku 模式）
    private var boundService: com.sidhu.androidautoglm.AutoGLMShizukuService? = null

    // CPU 唤醒锁
    private var wakeLock: PowerManager.WakeLock? = null

    private var isWakeWordMode = false
    private var isCommandMode = false

    // VAD 参数
    private const val SILENCE_THRESHOLD_DB = -40.0f
    private const val SILENCE_DURATION_MS = 1500L        // 静音超过 1.5s 停止录音
    private const val NO_SPEECH_TIMEOUT_MS = 5000L       // 5s 未开口则超时

    // 唤醒词模式解码粒度（0.2s = 3200帧）
    private const val FRAMES_PER_DECODE_WAKE = 3200

    // 流重置阈值（防止 Sherpa 流内存泄漏）
    private const val SOFT_RESET_THRESHOLD_FRAMES = 16000 * 5   // 5s 静音时软重置
    private const val HARD_RESET_THRESHOLD_FRAMES = 16000 * 15  // 15s 强制重置

    /** 唤醒后冷却期：防止回声/幻听导致连续误触发（部分机型反馈会一直唤醒） */
    private const val WAKE_UP_COOLDOWN_MS = 4000L
    /** 启动后宽限期：刚进入唤醒词模式时忽略前 N 毫秒的识别结果，避免上一段音频残留 */
    private const val STARTUP_GRACE_MS = 2000L

    @Volatile
    private var lastWakeUpTimeMs: Long = 0

    private var audioManager: AudioManager? = null
    private var savedVolume: Int = -1
    private var isMuted = false
    private var focusRequest: AudioFocusRequest? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    // =========================================================
    // 初始化
    // =========================================================

    fun initialize(context: Context) {
        Log.d(TAG, "Initializing WakeWordDetector")
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AndroidAutoGLM:WakeWordDetector"
        )

        // 读取保存的唤醒词
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedWakeWord = prefs.getString("wake_word", "皮皮虾") ?: "皮皮虾"
        _wakeWord.value = savedWakeWord

        // 如果模型还未初始化则触发加载
        if (SherpaModelManager.modelState.value is SherpaModelManager.ModelState.NotInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                SherpaModelManager.initModel(context)
            }
        }
    }

    fun bindService(service: com.sidhu.androidautoglm.AutoGLMShizukuService) {
        this.boundService = service
    }

    fun unbindService() {
        this.boundService = null
    }

    // =========================================================
    // WakeLock 管理
    // =========================================================

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L) // 最多持有 10 分钟
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    // =========================================================
    // 1. 唤醒词模式
    // =========================================================

    @SuppressLint("MissingPermission")
    fun startWakeWordMode(context: Context) {
        // 防御性检查：确认设置里开关是打开的
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("wake_up_enabled", false)
        if (!isEnabled) {
            Log.i(TAG, "唤醒词功能已在设置中关闭，跳过启动")
            stopListening()
            return
        }

        if (isRunning.get()) {
            stopListening()
        }

        val recognizer = SherpaModelManager.recognizer
        if (recognizer == null) {
            val modelState = SherpaModelManager.modelState.value
            Log.w(TAG, "startWakeWordMode: recognizer=null, modelState=$modelState")
            if (modelState is SherpaModelManager.ModelState.Error) {
                onError?.invoke("模型加载失败，请重启 APP")
            } else {
                Log.i(TAG, "startWakeWordMode: 模型加载中，就绪后重试")
                SherpaModelManager.runWhenReady(scope, context) {
                    startWakeWordMode(context)
                }
            }
            return
        }

        isRunning.set(true)
        isWakeWordMode = true
        isCommandMode = false

        acquireWakeLock()
        val ww = _wakeWord.value
        if (ww.isBlank()) {
            Log.w(TAG, "唤醒词为空！text.contains(\"\") 会匹配任何文本导致误触发，请在设置中配置唤醒词")
        }
        Log.i(TAG, "Starting Wake Word Mode with wake word: \"$ww\"")

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBufferSize * 2, SAMPLE_RATE * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("麦克风初始化失败")
                isRunning.set(false)
                releaseWakeLock()
                return
            }

            initAudioEffects(audioRecord!!.audioSessionId)
            audioRecord?.startRecording()

        } catch (e: Exception) {
            Log.e(TAG, "startWakeWordMode recorder failed", e)
            onError?.invoke("无法启动录音: ${e.message}")
            isRunning.set(false)
            releaseWakeLock()
            return
        }

        detectionJob = scope.launch {
            val buffer = ShortArray(1024)
            var stream = recognizer.createStream()
            var framesSinceLastDecode = 0
            var framesInCurrentStream = 0
            val modeStartTimeMs = System.currentTimeMillis()

            try {
                while (isRunning.get() && isWakeWordMode) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        val floatSamples = FloatArray(read) { i ->
                            val raw = buffer[i] / 32768.0f
                            sum += raw * raw
                            var s = raw * WAKE_WORD_GAIN_FACTOR
                            s = s.coerceIn(-1.0f, 1.0f)
                            s
                        }

                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else -50.0

                        stream.acceptWaveform(floatSamples, SAMPLE_RATE)
                        framesSinceLastDecode += read
                        framesInCurrentStream += read

                        if (framesSinceLastDecode >= FRAMES_PER_DECODE_WAKE) {
                            val nowMs = System.currentTimeMillis()
                            // 冷却期：刚唤醒后一段时间内丢弃音频，防止连续误触发
                            if (nowMs - lastWakeUpTimeMs < WAKE_UP_COOLDOWN_MS) {
                                stream.release()
                                stream = recognizer.createStream()
                                framesSinceLastDecode = 0
                                framesInCurrentStream = 0
                                delay(20)
                                continue
                            }
                            // 启动宽限期：刚进入模式时丢弃音频并重置流，避免上一段音频残留/回声导致误触发
                            if (nowMs - modeStartTimeMs < STARTUP_GRACE_MS) {
                                stream.release()
                                stream = recognizer.createStream()
                                framesSinceLastDecode = 0
                                framesInCurrentStream = 0
                                delay(20)
                                continue
                            }

                            recognizer.decode(stream)
                            val text = recognizer.getResult(stream).text.trim()
                            val wakeWord = _wakeWord.value

                            // 关键词过滤：唤醒词为空时 text.contains("") 恒为 true，会误触发一切
                            val canTrigger = wakeWord.isNotBlank() &&
                                db >= SILENCE_THRESHOLD_DB &&  // 静音过滤：SenseVoice 在静音/噪声下会幻听
                                text.isNotEmpty() &&
                                text.contains(wakeWord, ignoreCase = true)

                            if (canTrigger) {
                                Log.i(TAG, "✅ 唤醒词 \"$wakeWord\" 检测到！(text=\"$text\", db=$db)")
                                lastWakeUpTimeMs = nowMs
                                stream.release()
                                handleWakeUpLogic(context)
                                stopListening()
                                break
                            }

                            // 流智能重置（防止内存泄漏和识别漂移）
                            val isSilence = db < SILENCE_THRESHOLD_DB
                            val shouldSoftReset =
                                (framesInCurrentStream > SOFT_RESET_THRESHOLD_FRAMES) && isSilence
                            val shouldHardReset =
                                framesInCurrentStream > HARD_RESET_THRESHOLD_FRAMES

                            if (shouldSoftReset || shouldHardReset) {
                                stream.release()
                                stream = recognizer.createStream()
                                framesInCurrentStream = 0
                            }

                            framesSinceLastDecode = 0
                        }
                    } else if (read < 0) {
                        break
                    }
                    delay(20)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wake word loop error", e)
            } finally {
                try { stream.release() } catch (e: Exception) {}
            }
        }
    }

    private suspend fun handleWakeUpLogic(context: Context) {
        Log.i(TAG, "handleWakeUpLogic: 唤醒词检测成功，准备回调")
        withContext(Dispatchers.Main) {
            onWakeWordDetected?.invoke()

            // 优先使用回调
            if (onWakeUpAction != null) {
                Log.i(TAG, "handleWakeUpLogic: 调用 onWakeUpAction")
                onWakeUpAction?.invoke()
            } else if (boundService != null) {
                Log.i(TAG, "handleWakeUpLogic: 调用 boundService.handleWakeUpAction")
                boundService?.handleWakeUpAction()
            } else {
                Log.i(TAG, "handleWakeUpLogic: 无 Service，直接 startCommandMode")
                startCommandMode(context)
            }
        }
    }

    // =========================================================
    // 2. 指令模式
    // =========================================================

    @SuppressLint("MissingPermission")
    fun startCommandMode(context: Context) {
        if (isRunning.get()) {
            stopListening()
        }

        val recognizer = SherpaModelManager.recognizer
        if (recognizer == null) {
            Log.e(TAG, "Recognizer not initialized for command mode")
            onError?.invoke("语音模型未就绪")
            return
        }

        Log.d(TAG, "startCommandMode: 即将 muteMedia (STREAM_MUSIC=0)")
        muteMedia()
        acquireWakeLock()

        isRunning.set(true)
        isWakeWordMode = false
        isCommandMode = true

        Log.i(TAG, "Starting Command Mode (after wake word)")

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBufferSize * 2, SAMPLE_RATE * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Command mode recorder init failed")
                unmuteMedia()
                releaseWakeLock()
                isRunning.set(false)
                return
            }

            initAudioEffects(audioRecord!!.audioSessionId)
            audioRecord?.startRecording()

        } catch (e: Exception) {
            Log.e(TAG, "startCommandMode recorder failed", e)
            unmuteMedia()
            releaseWakeLock()
            isRunning.set(false)
            return
        }

        commandJob = scope.launch {
            val buffer = ShortArray(1024)
            val localAudioBuffer = ArrayList<Float>()

            var hasSpeechStarted = false
            var lastActiveTime = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()

            var currentStream = recognizer.createStream()
            var partialFrames = 0

            try {
                while (isRunning.get() && isCommandMode) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            val raw = buffer[i]
                            var amplified = (raw * COMMAND_GAIN_FACTOR).toInt()
                            amplified = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            val f = amplified / 32768.0f
                            localAudioBuffer.add(f)
                            sum += f * f
                        }

                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else -50.0
                        val currentTime = System.currentTimeMillis()

                        if (db > SILENCE_THRESHOLD_DB) {
                            if (!hasSpeechStarted) {
                                hasSpeechStarted = true
                                Log.d(TAG, "Command speech started")
                            }
                            lastActiveTime = currentTime
                        }

                        // 实时识别（部分结果）
                        if (hasSpeechStarted) {
                            val floatChunk = FloatArray(read) { i ->
                                var s = (buffer[i] * COMMAND_GAIN_FACTOR).toInt()
                                s = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                s / 32768.0f
                            }
                            currentStream.acceptWaveform(floatChunk, SAMPLE_RATE)
                            partialFrames += read

                            if (partialFrames > 8000) {
                                recognizer.decode(currentStream)
                                val raw = recognizer.getResult(currentStream).text.trim()
                                val partial = stripFeedbackFromCommand(raw)
                                if (partial.isNotBlank()) {
                                    Log.i(TAG, "Command partial: $partial")
                                    _realtimeCommand.emit(partial)
                                }
                                partialFrames = 0
                            }
                        }

                        // VAD 超时判断
                        if (hasSpeechStarted) {
                            if (currentTime - lastActiveTime > SILENCE_DURATION_MS) {
                                Log.d(TAG, "Command: silence detected, stopping")
                                break
                            }
                        } else {
                            if (currentTime - startTime > NO_SPEECH_TIMEOUT_MS) {
                                Log.w(TAG, "Command: NO_SPEECH_TIMEOUT (${NO_SPEECH_TIMEOUT_MS}ms), no speech detected")
                                withContext(Dispatchers.Main) {
                                    onError?.invoke("未检测到指令，请重试")
                                }
                                stopListening()
                                return@launch
                            }
                        }
                    } else if (read < 0) {
                        break
                    }
                    delay(20)
                }

                // 发起最终完整识别
                if (hasSpeechStarted && localAudioBuffer.isNotEmpty()) {
                    currentStream.release()
                    val finalStream = recognizer.createStream()
                    finalStream.acceptWaveform(localAudioBuffer.toFloatArray(), SAMPLE_RATE)
                    recognizer.decode(finalStream)
                    val result = recognizer.getResult(finalStream)
                    finalStream.release()

                    var finalText = result.text.trim()
                    finalText = stripFeedbackFromCommand(finalText)
                    Log.i(TAG, "Command final result: \"$finalText\" (blank=${finalText.isBlank()}, hallucination=${isHallucination(finalText)})")

                    withContext(Dispatchers.Main) {
                        val tooShort = finalText.length < 2  // 防止「我在呢」后用户未说话就误触发
                        if (finalText.isBlank() || tooShort || isHallucination(finalText)) {
                            Log.w(TAG, "Command rejected: blank=${finalText.isBlank()}, tooShort=$tooShort, hallucination -> onError")
                            onError?.invoke("未能识别，请重试")
                        } else {
                            Log.i(TAG, "Command accepted -> onCommandReceived(\"$finalText\")")
                            onCommandReceived?.invoke(finalText)
                        }
                    }
                }

                stopListening()

            } catch (e: Exception) {
                Log.e(TAG, "Command loop error", e)
                withContext(Dispatchers.Main) { onError?.invoke("识别错误: ${e.message}") }
            } finally {
                try { currentStream.release() } catch (e: Exception) {}
            }
        }
    }

    // =========================================================
    // 音频效果
    // =========================================================

    private fun initAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio effects", e)
        }
    }

    private fun releaseAudioEffects() {
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
    }

    // =========================================================
    // 停止 / 清理
    // =========================================================

    fun stopListening() {
        Log.d(TAG, "Stopping WakeWordDetector")
        isRunning.set(false)
        isWakeWordMode = false
        isCommandMode = false

        detectionJob?.cancel()
        commandJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { /* ignore */ }
        audioRecord = null

        releaseAudioEffects()
        unmuteMedia()
        releaseWakeLock()
    }

    fun updateWakeWord(newWakeWord: String) {
        _wakeWord.value = newWakeWord
    }

    fun destroy() {
        stopListening()
        scope.cancel()
        onWakeWordDetected = null
        onCommandReceived = null
        onError = null
        boundService = null
        releaseWakeLock()
    }

    // =========================================================
    // 媒体静音（指令录音时防止外放干扰）
    // =========================================================

    private fun muteMedia() {
        if (isMuted) return
        try {
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            Log.d(TAG, "muteMedia: 当前 STREAM_MUSIC 音量=$currentVol, 即将设为 0")
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager?.requestAudioFocus(request)
            focusRequest = request

            if (currentVol > 0) {
                savedVolume = currentVol
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                Log.d(TAG, "muteMedia: 已保存音量 $currentVol 并设为 0")
            }

            isMuted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute media", e)
        }
    }

    private fun unmuteMedia() {
        if (!isMuted) return
        try {
            if (savedVolume != -1) {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                savedVolume = -1
            }

            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            focusRequest = null

            isMuted = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute media", e)
        }
    }

    // =========================================================
    // 反馈语/唤醒词去除（提前录音时可能被录入，需从结果开头去除）
    // =========================================================

    /** 从命令开头去除「我在呢」、唤醒词等反馈语，避免误当作用户指令 */
    private fun stripFeedbackFromCommand(text: String): String {
        if (text.isBlank()) return text
        var result = text.trim()
        val prefixes = listOf(
            "我在呢",
            "我在",
            _wakeWord.value
        ).filter { it.isNotBlank() }.sortedByDescending { it.length }
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (prefix.isBlank()) continue
                val lower = result.lowercase()
                val p = prefix.lowercase()
                if (lower.startsWith(p)) {
                    result = result.substring(prefix.length).trim()
                    if (result.startsWith("，") || result.startsWith(",")) result = result.substring(1).trim()
                    Log.d(TAG, "stripFeedbackFromCommand: 去除前缀 \"$prefix\" -> \"$result\"")
                    changed = true
                    break
                }
            }
        }
        return result
    }

    // =========================================================
    // 幻听过滤
    // =========================================================

    private fun isHallucination(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return true
        val fillers = setOf(".", "。", "，", ",", "呃", "嗯", "啊", "哦", "唉", "哎")
        return lower in fillers
    }
}
