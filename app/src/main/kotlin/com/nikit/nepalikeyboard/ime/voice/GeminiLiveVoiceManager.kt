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
import android.util.Log
import androidx.core.content.ContextCompat
import com.nikit.nepalikeyboard.FlorisImeService
import com.nikit.nepalikeyboard.app.FlorisPreferenceStore
import com.nikit.nepalikeyboard.editorInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * Wire protocol follows https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket
 * (v1beta BidiGenerateContent over WebSocket).
 */
object GeminiLiveVoiceManager {
    private const val TAG = "GeminiLiveVoice"

    private const val WS_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** Fixed streaming STT model used for Transcribe mode (85+ langs). */
    private const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"

    /** Fixed streaming speech-to-speech model used for Translate mode (70+ langs). */
    private const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"

    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    /** 100 ms of 16 kHz 16-bit mono PCM — the chunk size Google recommends. */
    private const val CHUNK_SIZE = 3200

    /** Give up with a diagnosable error if setupComplete never arrives. */
    private const val CONNECT_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val prefs by FlorisPreferenceStore

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
    private var connectionWatchdogJob: Job? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * The model is fixed per mode — there is no user-visible model setting.
     * Transcribe always uses the streaming STT model, Translate always uses
     * the streaming translation model.
     */
    private fun modelFor(mode: GeminiVoiceMode): String {
        return when (mode) {
            GeminiVoiceMode.TRANSCRIBE -> TRANSCRIBE_MODEL
            GeminiVoiceMode.TRANSLATE -> TRANSLATE_MODEL
        }
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

        val apiKey = prefs.geminiVoice.apiKey.get().trim()
        if (apiKey.isEmpty()) {
            _errorMessage.value = "Gemini API key is required. Tap settings to configure."
            _statusMessage.value = "API key missing"
            return
        }

        val mode = prefs.geminiVoice.mode.get()
        val translateTarget = prefs.geminiVoice.translateTarget.get()
        val autoStopSilence = prefs.geminiVoice.autoStopSilence.get()
        val model = modelFor(mode)

        _errorMessage.value = null
        _currentPreviewText.value = ""
        _amplitude.value = 0f
        _isConnecting.value = true
        _statusMessage.value = "Connecting to Gemini..."
        isSetupComplete = false

        // Watchdog: never leave the UI stuck on "Connecting..." — surface a
        // diagnosable error (model id included) if setupComplete never arrives.
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_isConnecting.value && !isSetupComplete) {
                Log.e(TAG, "Connect timeout; model=$model")
                _errorMessage.value =
                    "Still connecting after 15s. Check your API key and network, then retry."
                _statusMessage.value = "Connection timed out"
                stopListening()
            }
        }

        val requestUrl = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(requestUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentWebSocket = webSocket
                val setupMessage = buildSetupMessage(model, mode, translateTarget)
                Log.d(TAG, "WS open, sending setup for model=$model mode=$mode")
                webSocket.send(setupMessage)

                // Start recording and streaming audio
                startAudioStreaming(context, webSocket, autoStopSilence)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(context, text, mode, model)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    connectionWatchdogJob?.cancel()
                    val code = response?.code
                    val detail = t.localizedMessage ?: t.javaClass.simpleName
                    val errText = if (code != null && code != 101) {
                        "Connection failed (HTTP $code). Verify your API key and network."
                    } else {
                        "Network error ($detail). Check connection and retry."
                    }
                    Log.e(TAG, "WS failure code=$code model=$model", t)
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
                    connectionWatchdogJob?.cancel()
                    Log.w(TAG, "WS closed code=$code reason=$reason setupComplete=$isSetupComplete")
                    if (_isConnecting.value && !isSetupComplete) {
                        // Server hung up before setup finished — most commonly a
                        // rejected key or an invalid setup payload. Surface it
                        // instead of silently resetting to idle.
                        _errorMessage.value = if (reason.isNotBlank()) {
                            "Connection closed ($code): $reason"
                        } else {
                            "Connection closed before setup (code $code). Verify your API key and retry."
                        }
                        _statusMessage.value = "Connection error"
                    }
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

        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null

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
                // Signal end of the audio stream (server-side VAD path), then close.
                val endJson = JSONObject().apply {
                    val realtimeInput = JSONObject().apply {
                        put("audioStreamEnd", true)
                    }
                    put("realtimeInput", realtimeInput)
                }
                ws.send(endJson.toString())
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

    /**
     * Minimal setup payload per mode. The server validates strictly (close 1007
     * on any unknown field), so only documented fields are sent:
     * - Transcribe: model + generationConfig.responseModalities=[TEXT] +
     *   top-level inputAudioTranscription (empty = auto-detect).
     * - Translate: model + generationConfig{responseModalities=[AUDIO],
     *   translationConfig} + top-level input/outputAudioTranscription.
     * translationConfig lives ONLY inside generationConfig — a top-level copy
     * is rejected as "Unknown name translationConfig at 'setup'".
     */
    private fun buildSetupMessage(
        model: String,
        mode: GeminiVoiceMode,
        translateTarget: GeminiTranslateTarget
    ): String {
        val modelResource = if (model.startsWith("models/")) model else "models/$model"
        val root = JSONObject()
        val setup = JSONObject()
        setup.put("model", modelResource)

        val genConfig = JSONObject()
        val modalities = JSONArray()
        if (mode == GeminiVoiceMode.TRANSLATE) {
            modalities.put("AUDIO")
            // BCP-47 target codes: Nepali "ne", English "en".
            // echoTargetLanguage=true so the session never goes silently dead
            // when the spoken input already matches the target language.
            val targetCode = when (translateTarget) {
                GeminiTranslateTarget.NEPALI_TO_ENGLISH -> "en"
                GeminiTranslateTarget.ENGLISH_TO_NEPALI -> "ne"
            }
            genConfig.put(
                "translationConfig",
                JSONObject().apply {
                    put("targetLanguageCode", targetCode)
                    put("echoTargetLanguage", true)
                }
            )
        } else {
            modalities.put("TEXT")
        }
        genConfig.put("responseModalities", modalities)
        setup.put("generationConfig", genConfig)

        setup.put("inputAudioTranscription", JSONObject())
        if (mode == GeminiVoiceMode.TRANSLATE) {
            setup.put("outputAudioTranscription", JSONObject())
        }

        root.put("setup", setup)
        val message = root.toString()
        Log.d(TAG, "setup for model=$modelResource mode=$mode: $message")
        return message
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

                while (isActive && (_isListening.value || _isConnecting.value)) {
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

                    // Send audio chunk if setup is complete.
                    // v1beta wire shape is realtimeInput.audio (mediaChunks is
                    // deprecated and ignored by the server — sending it yields
                    // silence with no transcripts).
                    if (isSetupComplete) {
                        val base64 = Base64.encodeToString(audioBuffer, 0, bytesRead, Base64.NO_WRAP)
                        val chunkJson = JSONObject().apply {
                            val realtimeInput = JSONObject().apply {
                                val audio = JSONObject().apply {
                                    put("mimeType", "audio/pcm;rate=16000")
                                    put("data", base64)
                                }
                                put("audio", audio)
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

    private fun handleWebSocketMessage(context: Context, text: String, mode: GeminiVoiceMode, model: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                isSetupComplete = true
                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                scope.launch {
                    _isConnecting.value = false
                    _isListening.value = true
                    _statusMessage.value = when (mode) {
                        GeminiVoiceMode.TRANSCRIBE -> "Listening..."
                        GeminiVoiceMode.TRANSLATE -> "Listening & Translating..."
                    }
                }
                return
            }

            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")
                val deltaBuilder = StringBuilder()

                // Dedicated transcribe/translate models emit transcripts here
                // (no modelTurn). Accept camelCase and snake_case wire shapes.
                val transcriptKeys = arrayOf(
                    "outputTranscription", "output_transcription",
                    "inputTranscription", "input_transcription",
                    "interimInputTranscription", "interim_input_transcription",
                )
                for (key in transcriptKeys) {
                    val obj = serverContent.optJSONObject(key) ?: continue
                    val t = obj.optString("text", "")
                    if (t.isNotEmpty()) deltaBuilder.append(t)
                }

                // General Live models stream the answer as modelTurn text.
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (idx in 0 until parts.length()) {
                            val part = parts.optJSONObject(idx) ?: continue
                            // Text part or inline audio (translate model): use text,
                            // ignore raw audio bytes for keyboard insertion.
                            val partText = part.optString("text", "")
                            if (partText.isNotEmpty()) {
                                deltaBuilder.append(partText)
                            }
                        }
                    }
                }

                val delta = deltaBuilder.toString()
                if (delta.isNotEmpty()) {
                    scope.launch {
                        _currentPreviewText.value += delta
                        try {
                            val editor = context.editorInstance().value
                            editor.commitText(delta)
                        } catch (e: Exception) {
                            Log.e(TAG, "commitText failed", e)
                        }
                    }
                }

                // Turn bookkeeping (interrupted / turnComplete) is informational;
                // keep listening across turns — stop only via mic toggle, silence
                // auto-stop, or explicit error/close.
                return
            }

            if (json.has("error")) {
                val err = json.getJSONObject("error")
                val msg = err.optString("message", "Gemini Live API error")
                Log.e(TAG, "Server error model=$model msg=$msg")
                scope.launch {
                    _errorMessage.value = msg
                    _statusMessage.value = "Error: $msg"
                    stopListening()
                }
                return
            }

            if (json.has("goAway")) {
                Log.w(TAG, "Server goAway model=$model")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WS message parse failed model=$model", e)
        }
    }
}
