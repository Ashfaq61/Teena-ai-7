package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.security.EmergencyStopController
import com.example.core.security.SecurityAuditor
import com.example.core.security.UntrustedContentSanitizer
import com.example.data.remote.GeminiModelInfo
import com.example.data.remote.ModelRegistry
import com.example.system.TeenaForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

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

data class LiveControlEvent(
    val hasSetupComplete: Boolean = false,
    val resumptionHandleUpdate: String? = null,
    val isResumable: Boolean = false,
    val goAwayTimeLeft: String? = null,
    val serverErrorMessage: String? = null
)

data class VoiceLatencyStats(
    val connectLatencyMs: Long = 0L,
    val lastTurnLatencyMs: Long = 0L,
    val avgTurnLatencyMs: Long = 0L,
    val sampleCount: Int = 0
)

/**
 * Prevents duplicate tool execution if Gemini Live repeats a callId or identical tool+args within [windowMs].
 */
class ToolCallDeduplicator(private val windowMs: Long = 8_000L) {
    private data class CachedCall(val timestampMs: Long, val output: String)
    private val seenByCallId = LinkedHashMap<String, CachedCall>()
    private val seenBySignature = LinkedHashMap<String, CachedCall>()

    @Synchronized
    fun lookupDuplicate(
        callId: String,
        toolName: String,
        args: Map<String, String>,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        pruneExpired(nowMs)
        if (callId.isNotBlank()) {
            val byId = seenByCallId[callId]
            if (byId != null && (nowMs - byId.timestampMs) <= windowMs) {
                return byId.output
            }
        }
        val sig = canonicalSignature(toolName, args)
        val bySig = seenBySignature[sig]
        if (bySig != null && (nowMs - bySig.timestampMs) <= windowMs) {
            return bySig.output
        }
        return null
    }

    @Synchronized
    fun recordResult(
        callId: String,
        toolName: String,
        args: Map<String, String>,
        output: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        pruneExpired(nowMs)
        val entry = CachedCall(nowMs, output)
        if (callId.isNotBlank()) seenByCallId[callId] = entry
        seenBySignature[canonicalSignature(toolName, args)] = entry
    }

    @Synchronized
    fun clear() {
        seenByCallId.clear()
        seenBySignature.clear()
    }

    private fun canonicalSignature(toolName: String, args: Map<String, String>): String {
        val sortedArgs = args.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value.trim()}" }
        return "$toolName|$sortedArgs"
    }

    private fun pruneExpired(nowMs: Long) {
        seenByCallId.entries.removeAll { (nowMs - it.value.timestampMs) > windowMs }
        seenBySignature.entries.removeAll { (nowMs - it.value.timestampMs) > windowMs }
    }
}

/**
 * Real-time bidirectional audio assistant over Gemini Live v1beta WebSocket API.
 * - Uses API keys from enabled Gemini slots in Keystore vault (primary first, with automatic quota key rotation).
 * - Queries ModelRegistry strictly for `bidiGenerateContent` models.
 * - Includes AcousticEchoCanceler + playback Echo Guard holdoff to prevent self-hearing.
 * - Tracks debug voice turn latency, deduplicates Live tool calls, and binds microphone ForegroundService.
 * - Supports `sessionResumption` handles, proactive `goAway` reconnects BEFORE drop,
 *   and exponential backoff auto-reconnect on network drops.
 * - Immediately stops everything on Emergency Stop.
 */
class GeminiLiveVoiceManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val modelRegistry: ModelRegistry,
    private val resolveGeminiKeySlots: suspend () -> List<Pair<String, String>>,
    private val emergencyStopController: EmergencyStopController? = null,
    private val keepMonitorServiceEnabled: () -> Boolean = { false },
    private val onSessionLifecycleChanged: () -> Unit = {},
    private val onToolActionRequested: suspend (name: String, args: Map<String, String>) -> String
) {
    companion object {
        private const val TAG = "GeminiLiveVoice"
        private const val WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val INPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000
        const val ECHO_GUARD_HOLDOFF_MS = 320L
        val BACKOFF_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)

        fun shouldTransmitMicAudio(
            echoGuardEnabled: Boolean,
            isAssistantSpeaking: Boolean,
            nowMs: Long,
            lastPlaybackAtMs: Long,
            holdoffMs: Long = ECHO_GUARD_HOLDOFF_MS
        ): Boolean {
            if (!echoGuardEnabled) return true
            if (isAssistantSpeaking) return false
            if (lastPlaybackAtMs > 0L && (nowMs - lastPlaybackAtMs) < holdoffMs) return false
            return true
        }

        fun computeNormalizedPcm16Rms(bytes: ByteArray, length: Int = bytes.size): Float {
            val usable = length.coerceAtMost(bytes.size)
            val sampleCount = usable / 2
            if (sampleCount <= 0) return 0f
            var sumSq = 0.0
            var i = 0
            while (i + 1 < usable) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                val sample = (hi shl 8) or lo
                val norm = sample / 32768.0
                sumSq += norm * norm
                i += 2
            }
            val rms = sqrt(sumSq / sampleCount).toFloat()
            if (rms < 0.008f) return 0f
            return (rms * 3.8f).coerceIn(0f, 1f)
        }

        fun buildRealtimeJpegFrameJson(jpegBase64: String): JSONObject {
            return JSONObject().put(
                "realtimeInput",
                JSONObject().put(
                    "mediaChunks",
                    JSONArray().put(
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", jpegBase64)
                        }
                    )
                )
            )
        }

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

        fun buildSetupMessageJson(
            modelResourceName: String,
            voiceName: String,
            resumptionHandle: String? = null,
            identityProfile: TeenaIdentityProfile = TeenaIdentityProfile()
        ): JSONObject {
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
                        "sessionResumption",
                        JSONObject().apply {
                            if (!resumptionHandle.isNullOrBlank()) {
                                put("handle", resumptionHandle)
                            }
                        }
                    )
                    put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    AiBrainEngine.buildSharedCoreInstruction(
                                        lang = "Auto (Hindi / Hinglish / Roman Urdu / Urdu / English)",
                                        identityProfile = identityProfile
                                    ) +
                                        " Keep spoken replies natural, concise, and warm. " +
                                        "When asked what is on the screen ('ye dekho', 'screen par kya hai', 'ye button kya karta hai', 'kaun sa app khula hai'), call tool screen_describe or read_screen. " +
                                        "For direct accessibility navigation use read_screen, tap_text, type_text, scroll_screen, go_back, go_home. " +
                                        "When asked to perform multi-step goals across apps or screens ('ye karo', 'YouTube par search karo', etc.), call tool screen_act(goal) and report progress and results in speech. " +
                                        "For WhatsApp or social posts/reels/stories, use whatsapp_task_control or social_task_control (ALWAYS ask explicit user confirmation before sending/posting — never auto-submit silently). " +
                                        "When asked to read recent WhatsApp messages/notifications aloud, call read_whatsapp_notifications (never read unsolicited messages automatically). " +
                                        "Use device function tools when asked to toggle flashlight, open apps, or search the web."
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

        fun parseControlEvent(root: JSONObject): LiveControlEvent {
            val resUpdate = root.optJSONObject("sessionResumptionUpdate")
            val newHandle = resUpdate?.optString("newHandle")?.takeIf { it.isNotBlank() }
            val resumable = resUpdate?.optBoolean("resumable", newHandle != null) ?: false
            val goAwayObj = root.optJSONObject("goAway")
            val timeLeft = goAwayObj?.optString("timeLeft")?.takeIf { it.isNotBlank() }
                ?: if (goAwayObj != null) "soon" else null
            val errObj = root.optJSONObject("error")
            val errMsg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: if (errObj != null) errObj.toString() else null

            return LiveControlEvent(
                hasSetupComplete = root.has("setupComplete"),
                resumptionHandleUpdate = newHandle,
                isResumable = resumable,
                goAwayTimeLeft = timeLeft,
                serverErrorMessage = errMsg
            )
        }

        fun isQuotaOrRateLimitReason(reason: String): Boolean {
            return ModelRegistry.isQuotaError(IllegalStateException(reason)) ||
                reason.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                reason.contains("429", ignoreCase = true) ||
                reason.contains("quota", ignoreCase = true)
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
                        put("name", "screen_describe")
                        put("description", "Take a live screenshot and UI tree of the current Android screen, analyze with Gemini Vision, and answer what is on screen, which app is open, or what a button does.")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().put(
                                        "question",
                                        JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Optional user question about the screen (e.g. 'screen par kya hai' or 'ye button kya karta hai')")
                                        }
                                    )
                                )
                            }
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "screen_act")
                        put("description", "Execute an autonomous observe-think-act-verify multi-step goal on the user's Android screen using Gemini Vision + AccessibilityService (respecting secure screens and asking confirmation for sensitive actions).")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().put(
                                        "goal",
                                        JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Clear user goal to accomplish on screen")
                                        }
                                    )
                                )
                                put("required", JSONArray().put("goal"))
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
                put(
                    JSONObject().apply {
                        put("name", "read_screen")
                        put("description", "Read current screen UI hierarchy (package, visible texts, clickable buttons) via TeenaAccessibilityService")
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
                        put("name", "tap_text")
                        put("description", "Tap a visible button or UI element matching text/label via TeenaAccessibilityService")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put(
                                            "text",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Visible text or contentDescription of target to tap")
                                            }
                                        )
                                        put(
                                            "view_id",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Optional Android viewId resource name")
                                            }
                                        )
                                    }
                                )
                                put("required", JSONArray().put("text"))
                            }
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "type_text")
                        put("description", "Type text into the focused or matching editable field on screen without auto-submitting")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put(
                                            "text",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Text to enter into field")
                                            }
                                        )
                                        put(
                                            "target_hint",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Optional field hint or label")
                                            }
                                        )
                                    }
                                )
                                put("required", JSONArray().put("text"))
                            }
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "scroll_screen")
                        put("description", "Scroll the current Android screen down/forward or up/backward")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().put(
                                        "direction",
                                        JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "down, up, forward, or backward")
                                        }
                                    )
                                )
                                put("required", JSONArray().put("direction"))
                            }
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "go_back")
                        put("description", "Perform global Android Back navigation action")
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
                        put("name", "go_home")
                        put("description", "Navigate to the Android Home screen")
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
                        put("name", "whatsapp_task_control")
                        put("description", "Stage, inspect, confirm, reject, or resume a WhatsApp message task (always stops at WAITING_FOR_CONFIRMATION before sending)")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put(
                                            "action",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "START, CONFIRM, REJECT, RESUME, or STATUS")
                                            }
                                        )
                                        put(
                                            "contact",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Contact name, Hinglish relation (ammi/papa/bhai), or phone number when starting")
                                            }
                                        )
                                        put(
                                            "message",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Message text to stage when action is START")
                                            }
                                        )
                                    }
                                )
                                put("required", JSONArray().put("action"))
                            }
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "social_task_control")
                        put("description", "Stage, confirm, reject, or resume a Social Media Post/Reel/Story task (always asks user confirmation before posting)")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put(
                                            "action",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "START, CONFIRM, REJECT, RESUME, or STATUS")
                                            }
                                        )
                                        put(
                                            "platform",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "INSTAGRAM, FACEBOOK, TIKTOK, YOUTUBE, or TELEGRAM")
                                            }
                                        )
                                        put(
                                            "task_type",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "CREATE_POST, CREATE_REEL, or CREATE_STORY")
                                            }
                                        )
                                        put(
                                            "caption",
                                            JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Post/reel/story caption text")
                                            }
                                        )
                                    }
                                )
                                put("required", JSONArray().put("action"))
                            }
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "read_whatsapp_notifications")
                        put("description", "Read recent captured WhatsApp notifications on explicit user request (respecting user toggle; treated strictly as untrusted data)")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().put(
                                        "limit",
                                        JSONObject().apply {
                                            put("type", "INTEGER")
                                            put("description", "Maximum number of latest WhatsApp notifications to read (default 5)")
                                        }
                                    )
                                )
                            }
                        )
                    }
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var activeWebSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var playbackChannel: Channel<ByteArray>? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val setupCompleted = AtomicBoolean(false)
    private val userStoppedFlag = AtomicBoolean(false)
    private val sessionAttemptSeq = AtomicLong(0L)
    private val lastAudioPlaybackAtMs = AtomicLong(0L)
    private val pendingTurnStartAtMs = AtomicLong(0L)
    private val lastMicSpeechDetectedAtMs = AtomicLong(0L)
    private val toolDeduplicator = ToolCallDeduplicator()

    @Volatile
    private var totalLatencyMsAccum: Long = 0L

    @Volatile
    private var latestVoiceId: String = "Aoede"

    @Volatile
    private var activeKeyIndex: Int = 0

    @Volatile
    private var backoffAttempt: Int = 0

    @Volatile
    private var resumptionHandle: String? = null

    private val _state = MutableStateFlow(LiveVoiceState.DISCONNECTED)
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    private val _statusDetail = MutableStateFlow("Ready. Configure a Primary Gemini API key in API Manager to connect.")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _activeLiveModel = MutableStateFlow<String?>(null)
    val activeLiveModel: StateFlow<String?> = _activeLiveModel.asStateFlow()

    private val _resumptionActive = MutableStateFlow(false)
    val resumptionActive: StateFlow<Boolean> = _resumptionActive.asStateFlow()

    private val _echoGuardEnabled = MutableStateFlow(true)
    val echoGuardEnabled: StateFlow<Boolean> = _echoGuardEnabled.asStateFlow()

    private val _activeIdentity = MutableStateFlow(TeenaIdentityProfile())
    val activeIdentity: StateFlow<TeenaIdentityProfile> = _activeIdentity.asStateFlow()

    private val _latencyStats = MutableStateFlow(VoiceLatencyStats())
    val latencyStats: StateFlow<VoiceLatencyStats> = _latencyStats.asStateFlow()

    private val _micAudioLevel = MutableStateFlow(0f)
    val micAudioLevel: StateFlow<Float> = _micAudioLevel.asStateFlow()

    private val _playbackAudioLevel = MutableStateFlow(0f)
    val playbackAudioLevel: StateFlow<Float> = _playbackAudioLevel.asStateFlow()

    private val _transcript = MutableStateFlow<List<LiveTranscriptLine>>(emptyList())
    val transcript: StateFlow<List<LiveTranscriptLine>> = _transcript.asStateFlow()

    val isSessionActuallyConnected: Boolean
        get() = setupCompleted.get() && activeWebSocket != null &&
            (_state.value == LiveVoiceState.LISTENING ||
                _state.value == LiveVoiceState.THINKING ||
                _state.value == LiveVoiceState.SPEAKING)

    init {
        TeenaForegroundService.onVoiceStopRequested = {
            stopLiveSession(silent = false)
        }
        emergencyStopController?.addStopListener {
            stopLiveSession(silent = false)
            _state.value = LiveVoiceState.DISCONNECTED
            _statusDetail.value = "Halted by Emergency Stop."
        }
    }

    fun setEchoGuardEnabled(enabled: Boolean) {
        _echoGuardEnabled.value = enabled
    }

    fun updateIdentityProfile(profile: TeenaIdentityProfile) {
        _activeIdentity.value = profile
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startLiveSession(voiceId: String, preserveResumptionHandle: Boolean = false) {
        if (emergencyStopController?.isStopped() == true) {
            _state.value = LiveVoiceState.BLOCKED
            _statusDetail.value = "Blocked: Emergency Stop is active."
            onSessionLifecycleChanged()
            return
        }
        if (!hasMicPermission()) {
            _state.value = LiveVoiceState.BLOCKED
            _statusDetail.value = "RECORD_AUDIO permission is required for Live Voice."
            onSessionLifecycleChanged()
            return
        }

        latestVoiceId = voiceId
        if (!preserveResumptionHandle) {
            resumptionHandle = null
            _resumptionActive.value = false
            activeKeyIndex = 0
            backoffAttempt = 0
        }

        stopLiveSession(silent = true, clearResumption = !preserveResumptionHandle)
        userStoppedFlag.set(false)
        val attemptId = sessionAttemptSeq.incrementAndGet()
        _state.value = if (preserveResumptionHandle) LiveVoiceState.RECONNECTING else LiveVoiceState.CONNECTING
        _statusDetail.value = if (preserveResumptionHandle && !resumptionHandle.isNullOrBlank()) {
            "Resuming Gemini Live session with sessionResumption handle..."
        } else {
            "Checking Gemini API key slots..."
        }
        _activeLiveModel.value = null
        onSessionLifecycleChanged()

        connectJob = scope.launch {
            try {
                val keySlots = resolveGeminiKeySlots()
                if (keySlots.isEmpty()) {
                    _state.value = LiveVoiceState.BLOCKED
                    _statusDetail.value = "Gemini API key missing"
                    onSessionLifecycleChanged()
                    return@launch
                }

                val safeIdx = activeKeyIndex.coerceIn(0, keySlots.lastIndex)
                val (slotLabel, apiKey) = keySlots[safeIdx]
                _statusDetail.value = "Resolving bidiGenerateContent models ($slotLabel)..."
                val candidates = modelRegistry.getRankedLiveCandidates(apiKey, forceRefresh = false).getOrThrow()
                val canonicalVoice = normalizeVoiceName(voiceId)

                connectWithCandidate(
                    attemptId = attemptId,
                    keySlots = keySlots,
                    keySlotIndex = safeIdx,
                    canonicalVoice = canonicalVoice,
                    candidates = candidates,
                    triedModels = mutableSetOf(),
                    refreshedRegistry = false,
                    accumulatedErrors = mutableListOf()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = SecurityAuditor.redactSecrets(e.localizedMessage ?: "Connection failed")
                Log.e(TAG, "Gemini Live setup error: $safeMsg", e)
                if (isQuotaOrRateLimitReason(safeMsg)) {
                    tryRotateKeyAndReconnect(safeMsg)
                } else {
                    handleSocketError(safeMsg, allowAutoReconnect = true)
                }
            }
        }
    }

    private fun connectWithCandidate(
        attemptId: Long,
        keySlots: List<Pair<String, String>>,
        keySlotIndex: Int,
        canonicalVoice: String,
        candidates: List<GeminiModelInfo>,
        triedModels: MutableSet<String>,
        refreshedRegistry: Boolean,
        accumulatedErrors: MutableList<String>
    ) {
        if (sessionAttemptSeq.get() != attemptId || userStoppedFlag.get() || emergencyStopController?.isStopped() == true) {
            return
        }

        val (slotLabel, apiKey) = keySlots[keySlotIndex]
        val nextModel = candidates.firstOrNull { it.shortId !in triedModels }
        if (nextModel == null) {
            if (keySlotIndex + 1 < keySlots.size) {
                val nextIdx = keySlotIndex + 1
                activeKeyIndex = nextIdx
                val (nextLabel, nextKey) = keySlots[nextIdx]
                appendTranscript("system", "Rotating Gemini Live key to $nextLabel...")
                scope.launch {
                    val freshCandidates = modelRegistry.getRankedLiveCandidates(nextKey, forceRefresh = false)
                        .getOrDefault(candidates)
                    connectWithCandidate(
                        attemptId = attemptId,
                        keySlots = keySlots,
                        keySlotIndex = nextIdx,
                        canonicalVoice = canonicalVoice,
                        candidates = freshCandidates,
                        triedModels = mutableSetOf(),
                        refreshedRegistry = false,
                        accumulatedErrors = accumulatedErrors
                    )
                }
                return
            }
            handleSocketError(
                accumulatedErrors.joinToString(" | ").ifBlank { "No bidiGenerateContent Live model succeeded." },
                allowAutoReconnect = false
            )
            return
        }

        triedModels.add(nextModel.shortId)
        val connectStartMs = System.currentTimeMillis()
        _state.value = if (resumptionHandle != null) LiveVoiceState.RECONNECTING else LiveVoiceState.CONNECTING
        _statusDetail.value = "Connecting Live WebSocket (${nextModel.shortId} • $slotLabel)..."

        val request = Request.Builder()
            .url("$WS_BASE?key=$apiKey")
            .build()

        val wsClient = httpClient.newBuilder().build()
        val handshakeDone = AtomicBoolean(false)

        val socket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (sessionAttemptSeq.get() != attemptId || userStoppedFlag.get()) {
                        webSocket.close(1000, "Superseded")
                        return
                    }
                    activeWebSocket = webSocket
                    val setupJson = buildSetupMessageJson(
                        modelResourceName = nextModel.name,
                        voiceName = canonicalVoice,
                        resumptionHandle = resumptionHandle,
                        identityProfile = _activeIdentity.value
                    )
                    val sent = webSocket.send(setupJson.toString())
                    if (!sent) {
                        handlePreSetupFailure("Failed to transmit Gemini Live setup frame.")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (sessionAttemptSeq.get() != attemptId) return
                    handleRawServerPayload(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (sessionAttemptSeq.get() != attemptId) return
                    handleRawServerPayload(bytes.utf8())
                }

                private fun handleRawServerPayload(raw: String) {
                    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
                    val ctrl = parseControlEvent(root)

                    if (ctrl.serverErrorMessage != null) {
                        val safeErr = SecurityAuditor.redactSecrets(ctrl.serverErrorMessage)
                        if (!handshakeDone.get()) {
                            handlePreSetupFailure(safeErr)
                            return
                        }
                        if (isQuotaOrRateLimitReason(safeErr)) {
                            appendTranscript("system", "Quota hit on $slotLabel ($safeErr); rotating Gemini key...")
                            scope.launch { tryRotateKeyAndReconnect(safeErr) }
                            return
                        }
                    }

                    if (ctrl.resumptionHandleUpdate != null) {
                        resumptionHandle = ctrl.resumptionHandleUpdate
                        _resumptionActive.value = ctrl.isResumable
                    }

                    if (ctrl.goAwayTimeLeft != null) {
                        appendTranscript(
                            "system",
                            "Server sent goAway (timeLeft=${ctrl.goAwayTimeLeft}); proactively reconnecting with sessionResumption..."
                        )
                        scheduleProactiveGoAwayReconnect()
                        return
                    }

                    if (ctrl.hasSetupComplete && handshakeDone.compareAndSet(false, true)) {
                        backoffAttempt = 0
                        val connectLatency = (System.currentTimeMillis() - connectStartMs).coerceAtLeast(1L)
                        _latencyStats.value = _latencyStats.value.copy(connectLatencyMs = connectLatency)
                        scope.launch {
                            modelRegistry.markModelWorking(apiKey, nextModel.shortId, connectLatency)
                        }
                    }

                    handleIncomingFrame(root, nextModel.shortId, canonicalVoice, slotLabel)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (sessionAttemptSeq.get() != attemptId) return
                    val cleanReason = SecurityAuditor.redactSecrets(reason.ifBlank { "Closed ($code)" })
                    if (!handshakeDone.get() && code != 1000) {
                        handlePreSetupFailure("WebSocket $code: $cleanReason")
                        return
                    }
                    cleanupAudioAndSocket(closeSocket = false)
                    if (!userStoppedFlag.get() && emergencyStopController?.isStopped() != true && code != 1000) {
                        if (isQuotaOrRateLimitReason(cleanReason)) {
                            scope.launch { tryRotateKeyAndReconnect(cleanReason) }
                        } else {
                            scheduleBackoffReconnect("WebSocket closed ($code: $cleanReason)")
                        }
                    } else if (_state.value != LiveVoiceState.DISCONNECTED && _state.value != LiveVoiceState.RECONNECTING) {
                        _state.value = if (code == 1000) LiveVoiceState.DISCONNECTED else LiveVoiceState.ERROR
                        _statusDetail.value = if (code == 1000) "Gemini Live disconnected." else "Live WebSocket closed: $cleanReason"
                        onSessionLifecycleChanged()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (sessionAttemptSeq.get() != attemptId) return
                    val httpNote = response?.code?.let { "HTTP $it: " }.orEmpty()
                    val msg = SecurityAuditor.redactSecrets("$httpNote${t.localizedMessage ?: "WebSocket connection failed"}")
                    if (!handshakeDone.get()) {
                        handlePreSetupFailure(msg)
                    } else {
                        Log.e(TAG, "Gemini Live WebSocket drop: $msg", t)
                        if (isQuotaOrRateLimitReason(msg)) {
                            scope.launch { tryRotateKeyAndReconnect(msg) }
                        } else {
                            handleSocketError(msg, allowAutoReconnect = true)
                        }
                    }
                }

                private fun handlePreSetupFailure(reason: String) {
                    if (sessionAttemptSeq.get() != attemptId) return
                    handshakeDone.set(true)
                    cleanupAudioAndSocket(closeSocket = true)
                    accumulatedErrors.add("${nextModel.shortId}: $reason")
                    Log.w(TAG, "Live candidate '${nextModel.shortId}' rejected before setupComplete: $reason")

                    scope.launch {
                        if (sessionAttemptSeq.get() != attemptId) return@launch
                        if (isQuotaOrRateLimitReason(reason) && keySlotIndex + 1 < keySlots.size) {
                            activeKeyIndex = keySlotIndex + 1
                            val (nextLabel, nextKey) = keySlots[activeKeyIndex]
                            appendTranscript("system", "Quota hit on $slotLabel; switching Gemini key to $nextLabel")
                            val fresh = modelRegistry.getRankedLiveCandidates(nextKey, forceRefresh = false)
                                .getOrDefault(candidates)
                            connectWithCandidate(
                                attemptId = attemptId,
                                keySlots = keySlots,
                                keySlotIndex = activeKeyIndex,
                                canonicalVoice = canonicalVoice,
                                candidates = fresh,
                                triedModels = mutableSetOf(),
                                refreshedRegistry = false,
                                accumulatedErrors = accumulatedErrors
                            )
                            return@launch
                        }

                        val isUnavailable = ModelRegistry.isModelUnavailableError(IllegalStateException(reason)) ||
                            reason.contains("1008", ignoreCase = true) ||
                            reason.contains("unsupported", ignoreCase = true)

                        if (isUnavailable) {
                            modelRegistry.markModelBlacklisted(apiKey, nextModel.shortId, reason)
                        }

                        var updatedCandidates = candidates
                        var nextRefreshed = refreshedRegistry
                        if (!refreshedRegistry && isUnavailable) {
                            nextRefreshed = true
                            val fresh = modelRegistry.getRankedLiveCandidates(apiKey, forceRefresh = true).getOrNull()
                            if (!fresh.isNullOrEmpty()) {
                                updatedCandidates = fresh
                            }
                        }

                        connectWithCandidate(
                            attemptId = attemptId,
                            keySlots = keySlots,
                            keySlotIndex = keySlotIndex,
                            canonicalVoice = canonicalVoice,
                            candidates = updatedCandidates,
                            triedModels = triedModels,
                            refreshedRegistry = nextRefreshed,
                            accumulatedErrors = accumulatedErrors
                        )
                    }
                }
            }
        )
        activeWebSocket = socket
    }

    private fun scheduleProactiveGoAwayReconnect() {
        if (userStoppedFlag.get() || emergencyStopController?.isStopped() == true) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _state.value = LiveVoiceState.RECONNECTING
            _statusDetail.value = "Refreshing Gemini Live session proactively before goAway..."
            onSessionLifecycleChanged()
            delay(150L)
            startLiveSession(latestVoiceId, preserveResumptionHandle = true)
        }
    }

    private suspend fun tryRotateKeyAndReconnect(reason: String) {
        if (userStoppedFlag.get() || emergencyStopController?.isStopped() == true) return
        val slots = resolveGeminiKeySlots()
        if (slots.size > 1 && activeKeyIndex + 1 < slots.size) {
            activeKeyIndex++
            val (nextLabel, _) = slots[activeKeyIndex]
            _state.value = LiveVoiceState.RECONNECTING
            _statusDetail.value = "Quota on active key; rotating to $nextLabel..."
            appendTranscript("system", "Quota error ($reason) -> rotated to $nextLabel")
            onSessionLifecycleChanged()
            startLiveSession(latestVoiceId, preserveResumptionHandle = true)
        } else {
            handleSocketError("Gemini quota exhausted across configured key slots: $reason", allowAutoReconnect = false)
        }
    }

    private fun scheduleBackoffReconnect(reason: String) {
        if (userStoppedFlag.get() || emergencyStopController?.isStopped() == true) return
        if (backoffAttempt >= BACKOFF_DELAYS_MS.size) {
            _state.value = LiveVoiceState.ERROR
            _statusDetail.value = "Live reconnect failed after ${BACKOFF_DELAYS_MS.size} retries: $reason"
            onSessionLifecycleChanged()
            return
        }
        val waitMs = BACKOFF_DELAYS_MS[backoffAttempt]
        backoffAttempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _state.value = LiveVoiceState.RECONNECTING
            _statusDetail.value = "Network dropped ($reason). Reconnecting in ${waitMs / 1000}s (attempt $backoffAttempt/${BACKOFF_DELAYS_MS.size})..."
            onSessionLifecycleChanged()
            delay(waitMs)
            if (!userStoppedFlag.get() && emergencyStopController?.isStopped() != true) {
                startLiveSession(latestVoiceId, preserveResumptionHandle = true)
            }
        }
    }

    fun sendTextDuringLiveSession(text: String) {
        val clean = text.trim()
        val socket = activeWebSocket
        if (clean.isEmpty() || socket == null || !setupCompleted.get()) return
        if (emergencyStopController?.isStopped() == true) return

        interruptPlaybackNow()
        pendingTurnStartAtMs.set(System.currentTimeMillis())
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

    suspend fun sendImageFrameDuringLiveSession(
        bitmap: Bitmap,
        promptHint: String? = null
    ): Result<String> = withContext(Dispatchers.Default) {
        if (emergencyStopController?.isStopped() == true) {
            return@withContext Result.failure(SecurityException("Blocked: Emergency Stop is active."))
        }
        val socket = activeWebSocket
        if (socket == null || !setupCompleted.get()) {
            return@withContext Result.failure(IllegalStateException("Start a Gemini Live Voice session before sending visual frames."))
        }

        val maxDim = 768
        val scale = minOf(
            1f,
            maxDim.toFloat() / maxOf(bitmap.width, 1),
            maxDim.toFloat() / maxOf(bitmap.height, 1)
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val b64 = try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }

        val frameJson = buildRealtimeJpegFrameJson(b64)
        val sent = socket.send(frameJson.toString())
        if (!sent) {
            return@withContext Result.failure(IllegalStateException("WebSocket failed to transmit visual frame."))
        }

        val cleanHint = promptHint?.trim().orEmpty()
        appendTranscript(
            "system",
            if (cleanHint.isNotBlank()) "Visual frame sent to Live Voice: $cleanHint" else "Visual frame sent to Live Voice"
        )
        if (cleanHint.isNotBlank()) {
            pendingTurnStartAtMs.set(System.currentTimeMillis())
            _state.value = LiveVoiceState.THINKING
            val hintPayload = JSONObject().put(
                "clientContent",
                JSONObject().apply {
                    put(
                        "turns",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("text", cleanHint)))
                            }
                        )
                    )
                    put("turnComplete", true)
                }
            )
            socket.send(hintPayload.toString())
        }
        Result.success("Visual frame sent (${bitmap.width}x${bitmap.height})")
    }

    fun interruptPlaybackNow() {
        lastAudioPlaybackAtMs.set(0L)
        _playbackAudioLevel.value = 0f
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
        startLiveSession(voiceId, preserveResumptionHandle = true)
    }

    fun stopLiveSession(silent: Boolean = false, clearResumption: Boolean = true) {
        userStoppedFlag.set(true)
        sessionAttemptSeq.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        if (clearResumption) {
            resumptionHandle = null
            _resumptionActive.value = false
            toolDeduplicator.clear()
        }
        cleanupAudioAndSocket(closeSocket = true)
        TeenaForegroundService.stopVoiceMode(context, keepMonitorIfEnabled = keepMonitorServiceEnabled())
        if (!silent) {
            _state.value = LiveVoiceState.DISCONNECTED
            _statusDetail.value = "Gemini Live disconnected."
            onSessionLifecycleChanged()
        }
    }

    private fun handleSocketError(message: String, allowAutoReconnect: Boolean) {
        cleanupAudioAndSocket(closeSocket = true)
        if (allowAutoReconnect && !userStoppedFlag.get() && emergencyStopController?.isStopped() != true) {
            appendTranscript("system", "Connection drop: $message")
            scheduleBackoffReconnect(message)
        } else {
            _state.value = LiveVoiceState.ERROR
            _statusDetail.value = "Gemini Live error: $message"
            appendTranscript("system", "Error: $message")
            onSessionLifecycleChanged()
        }
    }

    private fun handleIncomingFrame(
        root: JSONObject,
        modelShortId: String,
        voiceName: String,
        slotLabel: String
    ) {
        if (root.has("setupComplete")) {
            if (setupCompleted.compareAndSet(false, true)) {
                _activeLiveModel.value = modelShortId
                TeenaForegroundService.startVoiceMode(context)
                startAudioPipelines()
                _state.value = LiveVoiceState.LISTENING
                val resTag = if (resumptionHandle != null) " • resumed" else ""
                _statusDetail.value = "Live audio active ($modelShortId • $voiceName • $slotLabel$resTag)."
                appendTranscript("system", "Live WebSocket active ($modelShortId, voice $voiceName, $slotLabel$resTag).")
                onSessionLifecycleChanged()
            }
            return
        }

        val serverContent = root.optJSONObject("serverContent")
        if (serverContent != null) {
            if (serverContent.optBoolean("interrupted", false)) {
                interruptPlaybackNow()
                _state.value = LiveVoiceState.LISTENING
                _statusDetail.value = "Listening (barge-in interrupted previous audio)..."
            }

            val inputTrans = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty().trim()
            if (inputTrans.isNotEmpty()) {
                pendingTurnStartAtMs.set(System.currentTimeMillis())
                appendOrMergeTranscript("user", inputTrans)
            }

            val outputTrans = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty().trim()
            if (outputTrans.isNotEmpty()) {
                recordTurnLatencyIfPending()
                appendOrMergeTranscript("assistant", outputTrans)
            }

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
                                recordTurnLatencyIfPending()
                                lastAudioPlaybackAtMs.set(System.currentTimeMillis())
                                _state.value = LiveVoiceState.SPEAKING
                                playbackChannel?.trySend(pcm)
                            }
                        }
                    }
                    val partText = part.optString("text").trim()
                    if (partText.isNotEmpty() && outputTrans.isEmpty()) {
                        recordTurnLatencyIfPending()
                        appendOrMergeTranscript("assistant", partText)
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                _playbackAudioLevel.value = 0f
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
            val duplicateCached = toolDeduplicator.lookupDuplicate(callId, name, argMap)
            val resultText = if (duplicateCached != null) {
                appendTranscript("system", "Tool call deduplicated: $name")
                duplicateCached
            } else {
                appendTranscript("system", "Tool call: $name($argMap)")
                val computed = runCatching {
                    emergencyStopController?.assertAutomationAllowed("voice tool '$name'")
                    val raw = onToolActionRequested(name, argMap)
                    if (name == "read_screen" ||
                        name == "read_current_screen" ||
                        name == "search_web" ||
                        name == "screen_describe" ||
                        name == "screen_act" ||
                        name == "read_whatsapp_notifications"
                    ) {
                        UntrustedContentSanitizer.wrapAsUntrustedData("tool:$name", raw, 2200)
                    } else {
                        raw
                    }
                }.getOrElse { it.localizedMessage ?: "Tool execution error" }
                toolDeduplicator.recordResult(callId, name, argMap, computed)
                computed
            }

            appendTranscript("assistant", "Tool result: ${resultText.take(240)}")

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
        pendingTurnStartAtMs.set(System.currentTimeMillis())
        activeWebSocket?.send(frame.toString())
        if (_state.value == LiveVoiceState.THINKING) {
            _state.value = LiveVoiceState.LISTENING
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipelines() {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        playbackChannel = channel

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
                    _playbackAudioLevel.value = computeNormalizedPcm16Rms(chunk, chunk.size)
                    lastAudioPlaybackAtMs.set(System.currentTimeMillis())
                    track.write(chunk, 0, chunk.size)
                    lastAudioPlaybackAtMs.set(System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack playback loop ended: ${e.localizedMessage}")
            } finally {
                _playbackAudioLevel.value = 0f
            }
        }

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
            handleSocketError("Unable to initialize 16kHz PCM16 AudioRecord microphone.", allowAutoReconnect = false)
            return
        }

        audioRecord = recorder
        val sessionId = recorder.audioSessionId
        if (sessionId > 0) {
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                }
            }
            runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }
            }
        }
        runCatching { recorder.startRecording() }

        captureJob = scope.launch {
            val buffer = ByteArray(3200)
            try {
                while (isActive && setupCompleted.get() && emergencyStopController?.isStopped() != true) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val now = System.currentTimeMillis()
                        val allowedByEchoGuard = shouldTransmitMicAudio(
                            echoGuardEnabled = _echoGuardEnabled.value,
                            isAssistantSpeaking = _state.value == LiveVoiceState.SPEAKING,
                            nowMs = now,
                            lastPlaybackAtMs = lastAudioPlaybackAtMs.get()
                        )
                        if (!allowedByEchoGuard) {
                            _micAudioLevel.value = 0f
                            continue
                        }
                        _micAudioLevel.value = computeNormalizedPcm16Rms(buffer, bytesRead)
                        if (now - lastMicSpeechDetectedAtMs.get() > 1500L) {
                            pendingTurnStartAtMs.compareAndSet(0L, now)
                        }
                        lastMicSpeechDetectedAtMs.set(now)

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
            } finally {
                _micAudioLevel.value = 0f
            }
        }
    }

    private fun recordTurnLatencyIfPending() {
        val started = pendingTurnStartAtMs.getAndSet(0L)
        if (started <= 0L) return
        val delta = (System.currentTimeMillis() - started).coerceIn(1L, 60_000L)
        val prev = _latencyStats.value
        val nextCount = prev.sampleCount + 1
        totalLatencyMsAccum += delta
        _latencyStats.value = prev.copy(
            lastTurnLatencyMs = delta,
            avgTurnLatencyMs = totalLatencyMsAccum / nextCount,
            sampleCount = nextCount
        )
    }

    private fun cleanupAudioAndSocket(closeSocket: Boolean) {
        setupCompleted.set(false)
        _activeLiveModel.value = null
        _micAudioLevel.value = 0f
        _playbackAudioLevel.value = 0f
        lastAudioPlaybackAtMs.set(0L)
        pendingTurnStartAtMs.set(0L)

        captureJob?.cancel()
        captureJob = null
        playbackJob?.cancel()
        playbackJob = null

        playbackChannel?.close()
        playbackChannel = null

        runCatching { acousticEchoCanceler?.release() }
        acousticEchoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

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

    private fun normalizeVoiceName(raw: String): String {
        val trimmed = raw.trim()
        return availableVoices.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.id
            ?: trimmed.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Aoede" }
    }

    fun appendSystemNote(text: String) {
        appendTranscript("system", text)
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
