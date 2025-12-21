package com.sidhu.androidautoglm.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

class SpeechRecognizerManager(private val context: Context) {

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Buffer to hold the entire utterance for offline recognition
    private val audioBuffer = ArrayList<Float>()
    
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        onResultCallback: (String) -> Unit,
        onErrorCallback: (String) -> Unit
    ) {
        val modelState = SherpaModelManager.modelState.value
        if (SherpaModelManager.recognizer == null) {
            if (modelState is SherpaModelManager.ModelState.Error) {
                onErrorCallback("Model Error: ${(modelState as SherpaModelManager.ModelState.Error).message}")
            } else {
                onErrorCallback("Model not loaded yet")
            }
            return
        }

        if (_isListening.value) return

        onResult = onResultCallback
        onError = onErrorCallback
        audioBuffer.clear()

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onErrorCallback("AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            _isListening.value = true
            
            startRecordingLoop()
            
        } catch (e: Exception) {
            e.printStackTrace()
            _isListening.value = false
            onErrorCallback(e.message ?: "Failed to start recording")
        }
    }

    private fun startRecordingLoop() {
        recordingJob = scope.launch {
            // Read in smaller chunks for smoother UI updates (e.g. 32ms @ 16kHz)
            val readSize = 512 
            val buffer = ShortArray(readSize)
            while (_isListening.value) {
                val read = audioRecord?.read(buffer, 0, readSize) ?: 0
                if (read > 0) {
                    // Convert to float and append to global buffer
                    val floatSamples = FloatArray(read)
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i] / 32768.0f
                        floatSamples[i] = sample
                        sum += sample * sample
                    }
                    
                    synchronized(audioBuffer) {
                        for (sample in floatSamples) {
                            audioBuffer.add(sample)
                        }
                    }

                    // Calculate RMS for UI
                    if (read > 0) {
                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else -50.0
                        _soundLevel.value = db.toFloat()
                    }
                }
            }
        }
    }

    suspend fun stopListening() {
        if (!_isListening.value) return

        _isListening.value = false
        _soundLevel.value = 0f
        
        try {
            recordingJob?.cancel()
            recordingJob?.join() // Wait for loop to finish
            
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            // Process the accumulated audio
            processAudio()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun processAudio() {
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
            
            if (result.text.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    onResult?.invoke(result.text)
                }
            }
            
            stream.release()
            
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onError?.invoke("Recognition failed: ${e.message}")
            }
        }
    }

    suspend fun cancel() {
        _isListening.value = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
    }

    fun destroy() {
        scope.launch { cancel() }
    }
}
