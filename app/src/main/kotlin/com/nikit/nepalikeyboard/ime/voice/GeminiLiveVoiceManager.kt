/*
 * Copyright (C) 2026 Nikit Hamal / The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nikit.nepalikeyboard.ime.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.nikit.nepalikeyboard.FlorisImeService
import com.nikit.nepalikeyboard.app.FlorisPreferenceStore
import com.nikit.nepalikeyboard.editorInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Manages real-time bidirectional audio streaming to Google AI Studio / Gemini Live API
 * for ultra-low-latency voice typing (Transcribe) and real-time speech translation (Translate).
 */
object GeminiLiveVoiceManager {
    private const val WS_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val CHUNK_SIZE = 1024

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()

    private val _currentPreviewText = MutableStateFlow("")
    val currentPreviewText = _currentPreviewText.asStateFlow()

    private val _statusMessage = MutableStateFlow("Tap microphone to speak")
    val statusMessage = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private var currentWebSocket: WebSocket? = null
    private var isSetupComplete = false
    private var audioRecordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks whether RECORD_AUDIO permission is currently granted.
     */
    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts listening and streaming audio to Gemini Live API.
     */
    fun startListening(context: Context) {
        if (_isListening.value || _isConnecting.value) return

        if (!hasRecordAudioPermission(context)) {
            _errorMessage.value = "Microphone permission required."
            _statusMessage.value = "Permission required"
            VoicePermissionActivity.launch(context)
            return
        }

        val prefs = FlorisPreferenceStore.get()
        val apiKey = prefs.geminiVoice.apiKey.get().trim()
        if (apiKey.isEmpty()) {
            _errorMessage.value = "Gemini API key is required. Tap settings to configure."
            _statusMessage.value = "API key missing"
            return
        }

        val rawModel = prefs.geminiVoice.model.get().trim().ifEmpty { "gemini-2.0-flash-exp" }
        val model = if (rawModel.startsWith("models/")) rawModel else "models/$rawModel"
        val mode = prefs.geminiVoice.mode.get()
        val translateTarget = prefs.geminiVoice.translateTarget.get()
        val autoStopSilence = prefs.geminiVoice.autoStopSilence.get()

        _errorMessage.value = null
        _currentPreviewText.value = ""
        _amplitude.value = 0f
        _isConnecting.value = true
        _statusMessage.value = "Connecting to Gemini..."
        isSetupComplete = false

        val requestUrl = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(requestUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentWebSocket = webSocket
                val systemPrompt = buildSystemPrompt(mode, translateTarget)
                val setupMessage = buildSetupMessage(model, systemPrompt)
                webSocket.send(setupMessage)

                // Start recording and streaming audio
                startAudioStreaming(context, webSocket, autoStopSilence)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(context, text, mode)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    val code = response?.code
                    val errText = if (code != null) {
                        "Connection failed (HTTP $code). Verify your API key and model."
                    } else {
                        "Network error: ${t.localizedMessage ?: "Unable to connect"}"
                    }
                    _errorMessage.value = errText
                    _statusMessage.value = "Connection error"
                    stopListening()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    stopListening()
                }
            }
        }

        currentWebSocket = okHttpClient.newWebSocket(request, listener)
    }

    /**
     * Stops listening and cleans up the WebSocket connection and audio recorder.
     */
    fun stopListening() {
        _isListening.value = false
        _isConnecting.value = false
        _amplitude.value = 0f
        isSetupComplete = false

        audioRecordJob?.cancel()
        audioRecordJob = null

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {
        } finally {
            audioRecord = null
        }

        currentWebSocket?.let { ws ->
            try {
                // Signal client turn completion
                val turnCompleteJson = JSONObject().apply {
                    val clientContent = JSONObject().apply {
                        put("turnComplete", true)
                    }
                    put("clientContent", clientContent)
                }
                ws.send(turnCompleteJson.toString())
                ws.close(1000, "Normal closure")
            } catch (_: Exception) {
            }
        }
        currentWebSocket = null

        if (_errorMessage.value == null) {
            _statusMessage.value = "Tap microphone to speak"
        }
    }

    /**
     * Toggles listening on or off.
     */
    fun toggleListening(context: Context) {
        if (_isListening.value || _isConnecting.value) {
            stopListening()
        } else {
            startListening(context)
        }
    }

    private fun buildSystemPrompt(
        mode: GeminiVoiceMode,
        translateTarget: GeminiTranslateTarget
    ): String {
        return when (mode) {
            GeminiVoiceMode.TRANSCRIBE -> {
                "You are an ultra-fast, accurate speech-to-text transcriber for a mobile keyboard. " +
                "You will receive live audio chunks. Output ONLY the exact transcribed text in real-time. " +
                "If the speaker is speaking Nepali, transcribe in Nepali Devanagari script. " +
                "If speaking English, transcribe in English. " +
                "Do not output conversational greetings, notes, punctuation markup, or explanations."
            }
            GeminiVoiceMode.TRANSLATE -> {
                when (translateTarget) {
                    GeminiTranslateTarget.NEPALI_TO_ENGLISH -> {
                        "You are a real-time speech translator for a mobile keyboard. " +
                        "You will receive live Nepali audio chunks. Immediately output ONLY the accurate English translation. " +
                        "Do not output the Nepali transcription. Do not output explanations, greetings, or conversational filler."
                    }
                    GeminiTranslateTarget.ENGLISH_TO_NEPALI -> {
                        "You are a real-time speech translator for a mobile keyboard. " +
                        "You will receive live English audio chunks. Immediately output ONLY the accurate Nepali (नेपाली Devanagari script) translation. " +
                        "Do not output English text. Do not output explanations, greetings, or conversational filler."
                    }
                }
            }
        }
    }

    private fun buildSetupMessage(model: String, systemPrompt: String): String {
        val root = JSONObject()
        val setup = JSONObject()
        setup.put("model", model)

        val genConfig = JSONObject()
        val modalities = JSONArray()
        modalities.put("TEXT")
        genConfig.put("responseModalities", modalities)
        setup.put("generationConfig", genConfig)

        val sysInst = JSONObject()
        val parts = JSONArray()
        val textPart = JSONObject()
        textPart.put("text", systemPrompt)
        parts.put(textPart)
        sysInst.put("parts", parts)
        setup.put("systemInstruction", sysInst)

        root.put("setup", setup)
        return root.toString()
    }

    private fun startAudioStreaming(
        context: Context,
        webSocket: WebSocket,
        autoStopSilence: Boolean
    ) {
        audioRecordJob?.cancel()
        audioRecordJob = CoroutineScope(Dispatchers.IO).launch {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            val bufferSize = maxOf(minBufSize, CHUNK_SIZE * 4)

            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                audioRecord = record

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    scope.launch {
                        _errorMessage.value = "Failed to initialize microphone audio record."
                        _statusMessage.value = "Microphone error"
                        stopListening()
                    }
                    return@launch
                }

                record.startRecording()

                val audioBuffer = ByteArray(CHUNK_SIZE)
                var hasSpoken = false
                var silenceTimeMs = 0L
                val bytesPerMs = (SAMPLE_RATE * 2) / 1000 // 32 bytes per ms for 16kHz 16-bit mono

                while (isActive && _isListening.value || _isConnecting.value) {
                    val bytesRead = record.read(audioBuffer, 0, audioBuffer.size)
                    if (bytesRead <= 0) continue

                    // Calculate RMS amplitude
                    var sum = 0.0
                    var i = 0
                    while (i < bytesRead - 1) {
                        val sample = (audioBuffer[i].toInt() and 0xFF) or (audioBuffer[i + 1].toInt() shl 8)
                        val sampleShort = sample.toShort()
                        sum += sampleShort * sampleShort
                        i += 2
                    }
                    val samplesCount = bytesRead / 2
                    val rms = if (samplesCount > 0) sqrt(sum / samplesCount) else 0.0
                    val normalizedAmp = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                    _amplitude.value = normalizedAmp

                    // Silence detection (3.5 seconds of silence after speech detected)
                    if (autoStopSilence) {
                        val chunkDurationMs = bytesRead / bytesPerMs
                        if (normalizedAmp < 0.025f) {
                            if (hasSpoken) {
                                silenceTimeMs += chunkDurationMs
                                if (silenceTimeMs >= 3500) {
                                    scope.launch {
                                        stopListening()
                                    }
                                    break
                                }
                            }
                        } else {
                            hasSpoken = true
                            silenceTimeMs = 0L
                        }
                    }

                    // Send audio chunk if setup is complete
                    if (isSetupComplete) {
                        val base64 = Base64.encodeToString(audioBuffer, 0, bytesRead, Base64.NO_WRAP)
                        val chunkJson = JSONObject().apply {
                            val realtimeInput = JSONObject().apply {
                                val mediaChunks = JSONArray().apply {
                                    val chunk = JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=16000")
                                        put("data", base64)
                                    }
                                    put(chunk)
                                }
                                put("mediaChunks", mediaChunks)
                            }
                            put("realtimeInput", realtimeInput)
                        }
                        webSocket.send(chunkJson.toString())
                    }
                }
            } catch (e: Exception) {
                scope.launch {
                    _errorMessage.value = "Audio recording error: ${e.localizedMessage}"
                    _statusMessage.value = "Audio error"
                    stopListening()
                }
            }
        }
    }

    private fun handleWebSocketMessage(context: Context, text: String, mode: GeminiVoiceMode) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                isSetupComplete = true
                scope.launch {
                    _isConnecting.value = false
                    _isListening.value = true
                    _statusMessage.value = when (mode) {
                        GeminiVoiceMode.TRANSCRIBE -> "Listening..."
                        GeminiVoiceMode.TRANSLATE -> "Listening & Translating..."
                    }
                }
            }

            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        val deltaBuilder = StringBuilder()
                        for (idx in 0 until parts.length()) {
                            val part = parts.getJSONObject(idx)
                            val partText = part.optString("text", "")
                            if (partText.isNotEmpty()) {
                                deltaBuilder.append(partText)
                            }
                        }
                        val delta = deltaBuilder.toString()
                        if (delta.isNotEmpty()) {
                            scope.launch {
                                _currentPreviewText.value += delta
                                val editor = context.editorInstance()
                                editor.commitText(delta)
                            }
                        }
                    }
                }
            }

            if (json.has("error")) {
                val err = json.getJSONObject("error")
                val msg = err.optString("message", "Gemini Live API error")
                scope.launch {
                    _errorMessage.value = msg
                    _statusMessage.value = "Error: $msg"
                    stopListening()
                }
            }
        } catch (_: Exception) {
        }
    }
}
