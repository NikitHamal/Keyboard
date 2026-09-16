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

    /** Dedicated streaming STT model (85+ langs, interim + finalized transcripts). */
    const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"

    /** Dedicated streaming speech-to-speech translation model (70+ langs). */
    const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"

    /** General conversational Live model — works for both modes via system prompt. */
    const val GENERAL_LIVE_MODEL = "gemini-3.1-flash-live-preview"

    /** Fallback general Live model. */
    const val FALLBACK_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

    /**
     * Model ids that are known-dead (e.g. gemini-2.0-flash-exp was shut down 2026-06-01)
     * or were never valid Live models. These auto-upgrade to the per-mode dedicated
     * model so users stuck on "Connecting..." recover without manual steps.
     */
    private val LEGACY_MODELS = setOf(
        "",
        "gemini-2.0-flash-exp",
        "models/gemini-2.0-flash-exp",
        "gemini-2.0-flash-live-001",
        "models/gemini-2.0-flash-live-001",
        "gemini-2.0-flash-live-preview-04-09",
        "models/gemini-2.0-flash-live-preview-04-09",
        "gemini-3.5-live-translate",
        "models/gemini-3.5-live-translate",
        "gemini-3.8-live",
        "models/gemini-3.8-live",
    )

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
     * Resolve the effective model id for [mode].
     *
     * Legacy / dead ids (incl. the old "gemini-2.0-flash-exp" default, shut down
     * 2026-06-01) and mismatched dedicated models (e.g. transcribe model while in
     * translate mode) auto-upgrade to the per-mode dedicated model. Explicit
     * modern custom models are respected untouched.
     */
    fun resolveModel(rawModel: String, mode: GeminiVoiceMode): String {
        val trimmed = rawModel.trim()
        val normalized = if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
        val id = normalized.removePrefix("models/")
        if (trimmed.isEmpty() || trimmed in LEGACY_MODELS || id in LEGACY_MODELS) {
            return when (mode) {
                GeminiVoiceMode.TRANSCRIBE -> TRANSCRIBE_MODEL
                GeminiVoiceMode.TRANSLATE -> TRANSLATE_MODEL
            }
        }
        // Dedicated transcribe model cannot translate and vice versa — swap instead
        // of letting the server hang up with close code 1008.
        if (mode == GeminiVoiceMode.TRANSLATE && (id == TRANSCRIBE_MODEL || id == "gemini-3.5-transcribe")) {
            return TRANSLATE_MODEL
        }
        if (mode == GeminiVoiceMode.TRANSCRIBE && (id == TRANSLATE_MODEL || id == TRANSLATE_MODEL.removeSuffix("-preview"))) {
            return TRANSCRIBE_MODEL
        }
        return id
    }

    fun isTranscribeLiveModel(model: String): Boolean {
        val id = model.removePrefix("models/")
        return id == TRANSCRIBE_MODEL || id == "gemini-3.5-transcribe"
    }

    fun isTranslateLiveModel(model: String): Boolean {
        val id = model.removePrefix("models/")
        return id == TRANSLATE_MODEL || id == TRANSLATE_MODEL.removeSuffix("-preview")
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

        val rawModel = prefs.geminiVoice.model.get().trim()
        val mode = prefs.geminiVoice.mode.get()
        val translateTarget = prefs.geminiVoice.translateTarget.get()
        val autoStopSilence = prefs.geminiVoice.autoStopSilence.get()
        // Auto-upgrade dead defaults (gemini-2.0-flash-exp was shut down 2026-06-01)
        // and mismatched dedicated models to the per-mode dedicated model.
        val model = resolveModel(rawModel, mode)

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
                    "Still connecting after 15s (model $model). Check API key, model id, and network, then retry."
                _statusMessage.value = "Connection timed out"
                stopListening()
            }
        }

        val requestUrl = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(requestUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentWebSocket = webSocket
                val systemPrompt = buildSystemPrompt(mode, translateTarget)
                val setupMessage = buildSetupMessage(model, mode, translateTarget, systemPrompt)
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
                        "Connection failed (HTTP $code, model $model). Verify API key and model id."
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
                        // Server hung up before setup finished — most commonly an
                        // unknown/dead model id (close 1008) or a rejected key.
                        // Surface it instead of silently resetting to idle.
                        _errorMessage.value = if (code == 1008) {
                            "Gemini rejected the session (model $model). Update the model id in Settings."
                        } else if (reason.isNotBlank()) {
                            "Connection closed ($code): $reason"
                        } else {
                            "Connection closed before setup (code $code, model $model). Verify API key and model."
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

    private fun buildSetupMessage(
        model: String,
        mode: GeminiVoiceMode,
        translateTarget: GeminiTranslateTarget,
        systemPrompt: String
    ): String {
        val modelResource = if (model.startsWith("models/")) model else "models/$model"
        val root = JSONObject()
        val setup = JSONObject()
        setup.put("model", modelResource)

        val isTranscribeLive = isTranscribeLiveModel(modelResource)
        val isTranslateLive = isTranslateLiveModel(modelResource)

        val genConfig = JSONObject()
        val modalities = JSONArray()
        // Dedicated translate model streams AUDIO (+ text transcripts); everything
        // else streams TEXT for direct keyboard insertion.
        modalities.put(if (isTranslateLive) "AUDIO" else "TEXT")
        genConfig.put("responseModalities", modalities)

        if (isTranslateLive) {
            // Dedicated speech-to-speech translation config. BCP-47 target codes:
            // Nepali "ne", English "en". echoTargetLanguage=true so the session
            // never goes silently dead when input already matches the target.
            val targetCode = when (translateTarget) {
                GeminiTranslateTarget.NEPALI_TO_ENGLISH -> "en"
                GeminiTranslateTarget.ENGLISH_TO_NEPALI -> "ne"
            }
            val translationConfig = JSONObject().apply {
                put("targetLanguageCode", targetCode)
                put("echoTargetLanguage", true)
            }
            // translationConfig lives inside generationConfig on the wire
            // (see live-translate WebSocket docs); also mirror it top-level in
            // setup for forward-compat with SDK-shaped payloads.
            genConfig.put("translationConfig", translationConfig)
            genConfig.put("inputAudioTranscription", JSONObject())
            genConfig.put("outputAudioTranscription", JSONObject())
            setup.put("translationConfig", translationConfig)
            setup.put("inputAudioTranscription", JSONObject())
            setup.put("outputAudioTranscription", JSONObject())
        } else {
            // Transcribe-live and general Live models: enable server-side input
            // transcription so interim/final transcripts arrive even when the
            // model streams TEXT. Empty languageCodes = auto-detect (85+ langs).
            val transcriptionConfig = JSONObject().apply {
                put("languageCodes", JSONArray())
            }
            setup.put("inputAudioTranscription", transcriptionConfig)
            if (!isTranscribeLive) {
                // General conversational Live models need the task prompt; the
                // dedicated transcribe model ignores system instructions.
                val sysInst = JSONObject()
                val parts = JSONArray()
                val textPart = JSONObject()
                textPart.put("text", systemPrompt)
                parts.put(textPart)
                sysInst.put("parts", parts)
                setup.put("systemInstruction", sysInst)
            }
        }

        setup.put("generationConfig", genConfig)

        // Server-side voice-activity detection (default behaviour); explicit so
        // push-to-talk vs auto-detection stays predictable across models.
        setup.put(
            "realtimeInputConfig",
            JSONObject().apply {
                put("automaticActivityDetection", JSONObject())
            }
        )

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
