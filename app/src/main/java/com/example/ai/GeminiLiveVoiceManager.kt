package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

enum class LiveVoiceState {
    DISCONNECTED,
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

@OptIn(PublicPreviewAPI::class)
class GeminiLiveVoiceManager(
    private val context: Context,
    private val onToolActionRequested: suspend (name: String, args: Map<String, String>) -> String
) {
    companion object {
        private const val TAG = "GeminiLiveVoice"
        private const val LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

        val availableVoices = listOf(
            VoiceOption("AOEDE", "Aoede (Warm, Clear)", "Female"),
            VoiceOption("KORE", "Kore (Crisp, Expressive)", "Female"),
            VoiceOption("LEDA", "Leda (Calm, Natural)", "Female"),
            VoiceOption("ZEPHYR", "Zephyr (Bright, Energetic)", "Female"),
            VoiceOption("FENRIR", "Fenrir (Deep, Confident)", "Male"),
            VoiceOption("PUCK", "Puck (Friendly, Agile)", "Male"),
            VoiceOption("CHARON", "Charon (Steady, Grounded)", "Male"),
            VoiceOption("ORUS", "Orus (Authoritative)", "Male")
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSession: LiveSession? = null
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow(LiveVoiceState.DISCONNECTED)
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    private val _statusDetail = MutableStateFlow("Ready to connect to Gemini Live Audio.")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _transcript = MutableStateFlow<List<LiveTranscriptLine>>(emptyList())
    val transcript: StateFlow<List<LiveTranscriptLine>> = _transcript.asStateFlow()

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startLiveSession(voiceId: String) {
        if (!hasMicPermission()) {
            _state.value = LiveVoiceState.ERROR
            _statusDetail.value = "RECORD_AUDIO permission is required for Gemini Live audio conversation."
            return
        }
        stopLiveSession()
        _state.value = LiveVoiceState.CONNECTING
        _statusDetail.value = "Connecting to Gemini Live ($voiceId)..."

        sessionJob = scope.launch {
            try {
                val tools = buildLiveFunctionTools()
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                    modelName = LIVE_MODEL,
                    generationConfig = liveGenerationConfig {
                        responseModality = ResponseModality.AUDIO
                        speechConfig = SpeechConfig(voice = Voice(voiceId))
                    },
                    systemInstruction = content {
                        text(
                            "You are Teena AI, a warm, responsive, human-like bilingual Android voice assistant " +
                                "fluent in English, Urdu, and Roman Urdu. Keep spoken responses concise and natural. " +
                                "Use your provided device tools when asked to toggle flashlight, open apps, read screen, or search."
                        )
                    },
                    tools = listOf(tools)
                )

                val session = model.connect()
                activeSession = session
                _state.value = LiveVoiceState.LISTENING
                _statusDetail.value = "Live two-way audio active ($voiceId). Speak naturally in English, Urdu, or Roman Urdu."
                appendTranscript("system", "Gemini Live session connected with voice $voiceId.")

                session.startAudioConversation(::handleLiveFunctionCall)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Live session error", e)
                _state.value = LiveVoiceState.ERROR
                _statusDetail.value = "Gemini Live error: ${e.localizedMessage ?: "Connection interrupted"}"
                appendTranscript("system", "Session error: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun sendTextDuringLiveSession(text: String) {
        val session = activeSession ?: return
        scope.launch {
            runCatching {
                _state.value = LiveVoiceState.THINKING
                appendTranscript("user", text)
                session.send(text)
                _state.value = LiveVoiceState.SPEAKING
            }.onFailure { e ->
                _statusDetail.value = "Failed to send prompt to live session: ${e.localizedMessage}"
            }
        }
    }

    fun reconnect(voiceId: String) {
        _state.value = LiveVoiceState.RECONNECTING
        _statusDetail.value = "Reconnecting Gemini Live session..."
        startLiveSession(voiceId)
    }

    fun stopLiveSession() {
        sessionJob?.cancel()
        sessionJob = null
        val session = activeSession
        activeSession = null
        if (session != null) {
            scope.launch {
                runCatching { session.stopAudioConversation() }
                runCatching { session.close() }
            }
        }
        _state.value = LiveVoiceState.DISCONNECTED
        _statusDetail.value = "Gemini Live disconnected."
    }

    private fun handleLiveFunctionCall(functionCall: FunctionCallPart): FunctionResponsePart {
        _state.value = LiveVoiceState.THINKING
        val argMap = functionCall.args.mapValues { (_, v) ->
            runCatching { v.jsonPrimitive.content }.getOrDefault(v.toString())
        }
        appendTranscript("system", "Tool call: ${functionCall.name}(${argMap.entries.joinToString { "${it.key}=${it.value}" }})")
        val resultText = kotlinx.coroutines.runBlocking {
            onToolActionRequested(functionCall.name, argMap)
        }
        appendTranscript("assistant", "Tool result: $resultText")
        _state.value = LiveVoiceState.LISTENING
        return FunctionResponsePart(
            functionCall.name,
            JsonObject(
                mapOf(
                    "status" to JsonPrimitive("ok"),
                    "result" to JsonPrimitive(resultText)
                )
            )
        )
    }

    private fun buildLiveFunctionTools(): Tool {
        val torchDecl = FunctionDeclaration(
            name = "toggle_flashlight",
            description = "Turn device camera flashlight on or off",
            parameters = mapOf("enabled" to Schema.boolean("true to turn on, false to turn off"))
        )
        val openAppDecl = FunctionDeclaration(
            name = "open_social_app",
            description = "Open WhatsApp, Facebook, Instagram, TikTok, YouTube, or Telegram",
            parameters = mapOf("app" to Schema.string("App name such as WHATSAPP, YOUTUBE, INSTAGRAM, FACEBOOK, TIKTOK, TELEGRAM"))
        )
        val readScreenDecl = FunctionDeclaration(
            name = "read_current_screen",
            description = "Read visible UI text from the currently open Android screen via AccessibilityService",
            parameters = emptyMap()
        )
        val searchWebDecl = FunctionDeclaration(
            name = "search_web",
            description = "Search the web for real-time facts and citations",
            parameters = mapOf("query" to Schema.string("Search query"))
        )
        return Tool.functionDeclarations(listOf(torchDecl, openAppDecl, readScreenDecl, searchWebDecl))
    }

    private fun appendTranscript(role: String, text: String) {
        _transcript.value = (_transcript.value + LiveTranscriptLine(role, text)).takeLast(40)
    }
}
