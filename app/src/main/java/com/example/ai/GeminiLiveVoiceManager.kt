package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.security.SecurityAuditor
import com.example.data.remote.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

enum class LiveVoiceState {
    DISCONNECTED,
    BLOCKED,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    RECONNECTING,
    ERROR
}

data class VoiceOption(
    val id: String,
    val label: String,
    val gender: String
)

data class LiveTranscriptLine(
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Real-time bidirectional audio assistant over Gemini Live v1beta WebSocket API.
 * Uses API key from the PRIMARY Gemini slot in EncryptedSharedPreferences/Keystore,
 * auto-detects `bidiGenerateContent` models via ModelRegistry, streams 16kHz PCM16
 * mono microphone audio in, plays 24kHz PCM16 mono audio out, and immediately
 * flushes output audio on barge-in.
 */
class GeminiLiveVoiceManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val modelRegistry: ModelRegistry,
    private val resolvePrimaryGeminiKey: suspend () -> String?,
    private val onSessionLifecycleChanged: () -> Unit = {},
    private val onToolActionRequested: suspend (name: String, args: Map<String, String>) -> String
) {
    companion object {
        private const val TAG = "GeminiLiveVoice"
        private const val WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val INPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000

        val availableVoices = listOf(
            VoiceOption("Aoede", "Aoede (Warm, Clear)", "Female"),
            VoiceOption("Kore", "Kore (Crisp, Expressive)", "Female"),
            VoiceOption("Leda", "Leda (Calm, Natural)", "Female"),
            VoiceOption("Zephyr", "Zephyr (Bright, Energetic)", "Female"),
            VoiceOption("Fenrir", "Fenrir (Deep, Confident)", "Male"),
            VoiceOption("Puck", "Puck (Friendly, Agile)", "Male"),
            VoiceOption("Charon", "Charon (Steady, Grounded)", "Male"),
            VoiceOption("Orus", "Orus (Authoritative)", "Male")
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var activeWebSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var playbackChannel: Channel<ByteArray>? = null
    private val setupCompleted = AtomicBoolean(false)

    private val _state = MutableStateFlow(LiveVoiceState.DISCONNECTED)
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    private val _statusDetail = MutableStateFlow("Ready. Configure a Primary Gemini API key in API Manager to connect.")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _activeLiveModel = MutableStateFlow<String?>(null)
    val activeLiveModel: StateFlow<String?> = _activeLiveModel.asStateFlow()

    private val _transcript = MutableStateFlow<List<LiveTranscriptLine>>(emptyList())
    val transcript: StateFlow<List<LiveTranscriptLine>> = _transcript.asStateFlow()

    val isSessionActuallyConnected: Boolean
        get() = setupCompleted.get() && activeWebSocket != null &&
            (_state.value == LiveVoiceState.LISTENING ||
                _state.value == LiveVoiceState.THINKING ||
                _state.value == LiveVoiceState.SPEAKING)

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startLiveSession(voiceId: String) {
        if (!hasMicPermission()) {
            _state.value = LiveVoiceState.BLOCKED
            _statusDetail.value = "RECORD_AUDIO permission is required for Live Voice."
            onSessionLifecycleChanged()
            return
        }

        stopLiveSession(silent = true)
        _state.value = LiveVoiceState.CONNECTING
        _statusDetail.value = "Checking Primary Gemini API key..."
        _activeLiveModel.value = null
        onSessionLifecycleChanged()

        connectJob = scope.launch {
            try {
                val apiKey = resolvePrimaryGeminiKey()?.trim()
                if (apiKey.isNullOrEmpty()) {
                    _state.value = LiveVoiceState.BLOCKED
                    _statusDetail.value = "Gemini API key missing"
                    onSessionLifecycleChanged()
                    return@launch
                }

                _statusDetail.value = "Querying ModelRegistry for bidiGenerateContent models..."
                val liveModels = modelRegistry.autoDetectLiveModels(apiKey).getOrThrow()
                val chosenModel = liveModels.first()
                val canonicalVoice = normalizeVoiceName(voiceId)

                _statusDetail.value = "Opening Gemini Live WebSocket (${chosenModel.shortId})..."
                val request = Request.Builder()
                    .url("$WS_BASE?key=$apiKey")
                    .build()

                val wsClient = httpClient.newBuilder().build()
                val socket = wsClient.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            activeWebSocket = webSocket
                            val setupJson = buildSetupMessageJson(
                                modelResourceName = chosenModel.name,
                                voiceName = canonicalVoice
                            )
                            val sent = webSocket.send(setupJson.toString())
                            if (!sent) {
                                handleSocketError("Failed to transmit Gemini Live setup frame.")
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            handleIncomingFrame(text, chosenModel.shortId, canonicalVoice)
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            handleIncomingFrame(bytes.utf8(), chosenModel.shortId, canonicalVoice)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            val cleanReason = SecurityAuditor.redactSecrets(reason.ifBlank { "Closed ($code)" })
                            cleanupAudioAndSocket(closeSocket = false)
                            if (_state.value != LiveVoiceState.DISCONNECTED) {
                                _state.value = if (code == 1000) LiveVoiceState.DISCONNECTED else LiveVoiceState.ERROR
                                _statusDetail.value = if (code == 1000) "Gemini Live disconnected." else "Live WebSocket closed: $cleanReason"
                            }
                            onSessionLifecycleChanged()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            val httpNote = response?.code?.let { "HTTP $it: " }.orEmpty()
                            val msg = SecurityAuditor.redactSecrets("$httpNote${t.localizedMessage ?: "WebSocket connection failed"}")
                            Log.e(TAG, "Gemini Live WebSocket error: $msg", t)
                            handleSocketError(msg)
                        }
                    }
                )
                activeWebSocket = socket
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = SecurityAuditor.redactSecrets(e.localizedMessage ?: "Connection failed")
                Log.e(TAG, "Gemini Live setup error: $safeMsg", e)
                handleSocketError(safeMsg)
            }
        }
    }

    fun sendTextDuringLiveSession(text: String) {
        val clean = text.trim()
        val socket = activeWebSocket
        if (clean.isEmpty() || socket == null || !setupCompleted.get()) return

        interruptPlaybackNow()
        _state.value = LiveVoiceState.THINKING
        appendTranscript("user", clean)

        val payload = JSONObject().put(
            "clientContent",
            JSONObject().apply {
                put(
                    "turns",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", clean)))
                        }
                    )
                )
                put("turnComplete", true)
            }
        )
        socket.send(payload.toString())
    }

    fun interruptPlaybackNow() {
        val channel = playbackChannel
        if (channel != null) {
            while (channel.tryReceive().isSuccess) {
                // Drain queued audio packets immediately on barge-in
            }
        }
        runCatching {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                } else {
                    track.flush()
                }
            }
        }
        if (setupCompleted.get() && _state.value == LiveVoiceState.SPEAKING) {
            _state.value = LiveVoiceState.LISTENING
        }
    }

    fun reconnect(voiceId: String) {
        _state.value = LiveVoiceState.RECONNECTING
        _statusDetail.value = "Reconnecting Gemini Live session..."
        startLiveSession(voiceId)
    }

    fun stopLiveSession(silent: Boolean = false) {
        connectJob?.cancel()
        connectJob = null
        cleanupAudioAndSocket(closeSocket = true)
        if (!silent) {
            _state.value = LiveVoiceState.DISCONNECTED
            _statusDetail.value = "Gemini Live disconnected."
            onSessionLifecycleChanged()
        }
    }

    private fun handleSocketError(message: String) {
        cleanupAudioAndSocket(closeSocket = true)
        _state.value = LiveVoiceState.ERROR
        _statusDetail.value = "Gemini Live error: $message"
        appendTranscript("system", "Error: $message")
        onSessionLifecycleChanged()
    }

    private fun handleIncomingFrame(rawJson: String, modelShortId: String, voiceName: String) {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return

        if (root.has("setupComplete")) {
            if (setupCompleted.compareAndSet(false, true)) {
                _activeLiveModel.value = modelShortId
                startAudioPipelines()
                _state.value = LiveVoiceState.LISTENING
                _statusDetail.value = "Live audio active ($modelShortId • $voiceName). Speak in English, Urdu, or Roman Urdu."
                appendTranscript("system", "Live WebSocket setup complete ($modelShortId, voice $voiceName).")
                onSessionLifecycleChanged()
            }
            return
        }

        val serverContent = root.optJSONObject("serverContent")
        if (serverContent != null) {
            // Barge-in: server detected user speech interrupting AI audio
            if (serverContent.optBoolean("interrupted", false)) {
                interruptPlaybackNow()
                _state.value = LiveVoiceState.LISTENING
                _statusDetail.value = "Listening (barge-in interrupted previous audio)..."
            }

            // Input transcription from user's speech
            val inputTrans = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty().trim()
            if (inputTrans.isNotEmpty()) {
                appendOrMergeTranscript("user", inputTrans)
            }

            // Output transcription from model's speech
            val outputTrans = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty().trim()
            if (outputTrans.isNotEmpty()) {
                appendOrMergeTranscript("assistant", outputTrans)
            }

            // Audio / text parts in modelTurn
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val mime = inlineData.optString("mimeType")
                        val b64 = inlineData.optString("data")
                        if (mime.startsWith("audio/", ignoreCase = true) && b64.isNotBlank()) {
                            val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                            if (pcm != null && pcm.isNotEmpty()) {
                                _state.value = LiveVoiceState.SPEAKING
                                playbackChannel?.trySend(pcm)
                            }
                        }
                    }
                    val partText = part.optString("text").trim()
                    if (partText.isNotEmpty() && outputTrans.isEmpty()) {
                        appendOrMergeTranscript("assistant", partText)
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                if (_state.value != LiveVoiceState.DISCONNECTED && _state.value != LiveVoiceState.ERROR) {
                    _state.value = LiveVoiceState.LISTENING
                    _statusDetail.value = "Listening ($modelShortId)..."
                }
            }
        }

        val toolCall = root.optJSONObject("toolCall")
        val functionCalls = toolCall?.optJSONArray("functionCalls")
        if (functionCalls != null && functionCalls.length() > 0) {
            scope.launch {
                handleToolCalls(functionCalls)
            }
        }
    }

    private suspend fun handleToolCalls(calls: JSONArray) {
        val responses = JSONArray()
        _state.value = LiveVoiceState.THINKING

        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val callId = call.optString("id")
            val name = call.optString("name")
            val argsObj = call.optJSONObject("args") ?: JSONObject()
            val argMap = buildMap {
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, argsObj.optString(k))
                }
            }
            appendTranscript("system", "Tool call: $name($argMap)")
            val resultText = runCatching { onToolActionRequested(name, argMap) }
                .getOrElse { it.localizedMessage ?: "Tool execution error" }
            appendTranscript("assistant", "Tool result: $resultText")

            responses.put(
                JSONObject().apply {
                    if (callId.isNotBlank()) put("id", callId)
                    put("name", name)
                    put(
                        "response",
                        JSONObject().apply {
                            put("status", "ok")
                            put("result", resultText)
                        }
                    )
                }
            )
        }

        val frame = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", responses)
        )
        activeWebSocket?.send(frame.toString())
        if (_state.value == LiveVoiceState.THINKING) {
            _state.value = LiveVoiceState.LISTENING
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipelines() {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        playbackChannel = channel

        // 24kHz PCM16 Mono AudioTrack output
        val outMinBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(outMinBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        runCatching { track.play() }

        playbackJob = scope.launch {
            try {
                for (chunk in channel) {
                    if (!isActive || !setupCompleted.get()) break
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { track.play() }
                    }
                    track.write(chunk, 0, chunk.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack playback loop ended: ${e.localizedMessage}")
            }
        }

        // 16kHz PCM16 Mono AudioRecord input -> Base64 realtimeInput
        val inMinBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)
        val readBufSize = maxOf(inMinBuf * 2, 3200)

        var recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                readBufSize
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                readBufSize
            )
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            handleSocketError("Unable to initialize 16kHz PCM16 AudioRecord microphone.")
            return
        }

        audioRecord = recorder
        runCatching { recorder.startRecording() }

        captureJob = scope.launch {
            val buffer = ByteArray(3200)
            try {
                while (isActive && setupCompleted.get()) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val b64 = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP)
                        val chunkFrame = JSONObject().put(
                            "realtimeInput",
                            JSONObject().put(
                                "mediaChunks",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=16000")
                                        put("data", b64)
                                    }
                                )
                            )
                        )
                        val ok = activeWebSocket?.send(chunkFrame.toString()) ?: false
                        if (!ok) break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord capture loop ended: ${e.localizedMessage}")
            }
        }
    }

    private fun cleanupAudioAndSocket(closeSocket: Boolean) {
        setupCompleted.set(false)
        _activeLiveModel.value = null

        captureJob?.cancel()
        captureJob = null
        playbackJob?.cancel()
        playbackJob = null

        playbackChannel?.close()
        playbackChannel = null

        runCatching {
            audioRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        }
        audioRecord = null

        runCatching {
            audioTrack?.let { trk ->
                if (trk.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    trk.pause()
                    trk.flush()
                }
                trk.release()
            }
        }
        audioTrack = null

        if (closeSocket) {
            runCatching { activeWebSocket?.close(1000, "Session ended") }
        }
        activeWebSocket = null
    }

    private fun buildSetupMessageJson(modelResourceName: String, voiceName: String): JSONObject {
        return JSONObject().put(
            "setup",
            JSONObject().apply {
                put("model", modelResourceName)
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", voiceName)
                                )
                            )
                        )
                    }
                )
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "You are Teena AI, a warm, responsive, human-like bilingual Android voice assistant " +
                                    "fluent in English, Urdu, and Roman Urdu. Keep spoken replies natural and concise. " +
                                    "Use device function tools when asked to toggle flashlight, open apps, read the screen, or search the web."
                            )
                        )
                    )
                )
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("functionDeclarations", buildToolDeclarationsJson())
                    )
                )
            }
        )
    }

    private fun buildToolDeclarationsJson(): JSONArray {
        return JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "toggle_flashlight")
                    put("description", "Turn device camera flashlight on or off")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject().put(
                                    "enabled",
                                    JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "true to turn on, false to turn off")
                                    }
                                )
                            )
                            put("required", JSONArray().put("enabled"))
                        }
                    )
                }
            )
            put(
                JSONObject().apply {
                    put("name", "open_social_app")
                    put("description", "Open WhatsApp, Facebook, Instagram, TikTok, YouTube, or Telegram")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject().put(
                                    "app",
                                    JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "WHATSAPP, YOUTUBE, INSTAGRAM, FACEBOOK, TIKTOK, or TELEGRAM")
                                    }
                                )
                            )
                            put("required", JSONArray().put("app"))
                        }
                    )
                }
            )
            put(
                JSONObject().apply {
                    put("name", "read_current_screen")
                    put("description", "Read visible UI text from the currently open Android screen via AccessibilityService")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject())
                        }
                    )
                }
            )
            put(
                JSONObject().apply {
                    put("name", "search_web")
                    put("description", "Search the web for real-time facts and citations")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject().put(
                                    "query",
                                    JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Search query")
                                    }
                                )
                            )
                            put("required", JSONArray().put("query"))
                        }
                    )
                }
            )
        }
    }

    private fun normalizeVoiceName(raw: String): String {
        val trimmed = raw.trim()
        return availableVoices.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.id
            ?: trimmed.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Aoede" }
    }

    private fun appendTranscript(role: String, text: String) {
        _transcript.value = (_transcript.value + LiveTranscriptLine(role, text)).takeLast(40)
    }

    private fun appendOrMergeTranscript(role: String, deltaText: String) {
        val current = _transcript.value
        val last = current.lastOrNull()
        if (last != null && last.role == role && (System.currentTimeMillis() - last.timestamp) < 4000L) {
            val merged = (last.text.trimEnd() + " " + deltaText.trimStart()).trim()
            _transcript.value = current.dropLast(1) + last.copy(text = merged, timestamp = System.currentTimeMillis())
        } else {
            appendTranscript(role, deltaText)
        }
    }
}
