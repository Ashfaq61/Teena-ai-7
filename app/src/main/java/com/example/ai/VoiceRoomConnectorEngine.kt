package com.example.ai

import com.example.core.security.EmergencyStopController
import com.example.core.security.SecurityAuditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class VoiceRoomSummary(
    val id: String,
    val name: String,
    val participantCount: Int = 0,
    val topic: String = "",
    val isLive: Boolean = true,
    val createdAtLabel: String = ""
)

data class LiveKitTokenSession(
    val roomName: String,
    val participantIdentity: String,
    val liveKitWsUrl: String,
    val maskedTokenPreview: String,
    val tokenLength: Int,
    val connectedAtMs: Long,
    val signalStatus: String
)

data class VoiceRoomsConnectorState(
    val status: String = "DISABLED", // DISABLED | READY | CONNECTED | ERROR
    val message: String = "Voice Rooms & LiveKit disabled. Set Rooms Server URL or LiveKit Token Endpoint in Settings to enable.",
    val rooms: List<VoiceRoomSummary> = emptyList(),
    val activeSession: LiveKitTokenSession? = null,
    val isLoading: Boolean = false,
    val lastSyncAtMs: Long = 0L
)

class VoiceRoomConnectorEngine(
    private val httpClient: OkHttpClient,
    private val emergencyStopController: EmergencyStopController? = null
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isValidHttpEndpoint(url: String): Boolean {
            val clean = url.trim()
            if (clean.isEmpty()) return false
            return clean.startsWith("https://", ignoreCase = true) ||
                clean.startsWith("http://localhost", ignoreCase = true) ||
                clean.startsWith("http://127.0.0.1", ignoreCase = true) ||
                clean.startsWith("http://10.0.2.2", ignoreCase = true)
        }

        fun isValidWsEndpoint(url: String): Boolean {
            val clean = url.trim()
            if (clean.isEmpty()) return false
            return clean.startsWith("wss://", ignoreCase = true) ||
                clean.startsWith("ws://localhost", ignoreCase = true) ||
                clean.startsWith("ws://127.0.0.1", ignoreCase = true) ||
                clean.startsWith("ws://10.0.2.2", ignoreCase = true)
        }

        fun normalizeRoomsDirectoryUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/rooms", ignoreCase = true)) trimmed else "$trimmed/rooms"
        }

        fun maskJwtToken(token: String): String {
            val clean = token.trim()
            return if (clean.length <= 12) "***(${clean.length})" else "${clean.take(6)}...${clean.takeLast(4)}"
        }

        fun buildLiveKitSignalUrl(wsBaseUrl: String, jwtToken: String): String {
            val cleanBase = wsBaseUrl.trim().trimEnd('/')
            val rtcBase = if (cleanBase.endsWith("/rtc", ignoreCase = true)) cleanBase else "$cleanBase/rtc"
            val encodedToken = URLEncoder.encode(jwtToken.trim(), "UTF-8")
            return "$rtcBase?access_token=$encodedToken&protocol=12"
        }

        fun parseRoomsJson(rawJson: String): List<VoiceRoomSummary> {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) return emptyList()
            val arr: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("rooms")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("items")
                        ?: JSONArray()
                }
            }
            return (0 until arr.length()).mapNotNull { idx ->
                val item = arr.optJSONObject(idx) ?: return@mapNotNull null
                val name = item.optString("name")
                    .ifBlank { item.optString("roomName") }
                    .ifBlank { item.optString("title") }
                    .trim()
                if (name.isEmpty()) return@mapNotNull null
                val id = item.optString("id").ifBlank { item.optString("sid") }.ifBlank { name }
                VoiceRoomSummary(
                    id = id,
                    name = name,
                    participantCount = item.optInt("numParticipants", item.optInt("participantCount", 0)).coerceAtLeast(0),
                    topic = item.optString("topic").ifBlank { item.optString("metadata") },
                    isLive = item.optBoolean("isLive", true),
                    createdAtLabel = item.optString("createdAt")
                )
            }
        }

        fun parseLiveKitTokenResponse(
            rawJson: String,
            fallbackRoomName: String,
            fallbackIdentity: String,
            fallbackWsUrl: String
        ): Result<Pair<LiveKitTokenSession, String>> = runCatching {
            val obj = JSONObject(rawJson)
            val token = obj.optString("token")
                .ifBlank { obj.optString("accessToken") }
                .ifBlank { obj.optString("jwt") }
                .trim()
            if (token.isEmpty()) {
                throw IllegalStateException("LiveKit token endpoint response did not include a 'token' field.")
            }
            val wsUrl = obj.optString("serverUrl")
                .ifBlank { obj.optString("wsUrl") }
                .ifBlank { obj.optString("url") }
                .ifBlank { obj.optString("livekitUrl") }
                .ifBlank { fallbackWsUrl }
                .trim()
            val roomName = obj.optString("roomName").ifBlank { fallbackRoomName }.trim()
            val identity = obj.optString("participantIdentity")
                .ifBlank { obj.optString("identity") }
                .ifBlank { fallbackIdentity }
                .trim()
            val session = LiveKitTokenSession(
                roomName = roomName,
                participantIdentity = identity,
                liveKitWsUrl = wsUrl,
                maskedTokenPreview = maskJwtToken(token),
                tokenLength = token.length,
                connectedAtMs = System.currentTimeMillis(),
                signalStatus = if (isValidWsEndpoint(wsUrl)) "Connecting RTC Signal..." else "Token Issued (Set wss:// LiveKit URL to connect signal)"
            )
            session to token
        }
    }

    private val _state = MutableStateFlow(VoiceRoomsConnectorState())
    val state: StateFlow<VoiceRoomsConnectorState> = _state.asStateFlow()

    @Volatile
    private var signalSocket: WebSocket? = null

    fun syncConfigurationStatus(
        roomsServerUrl: String,
        liveKitTokenEndpoint: String,
        liveKitWsUrl: String
    ) {
        val hasRooms = isValidHttpEndpoint(roomsServerUrl)
        val hasTokenEndpoint = isValidHttpEndpoint(liveKitTokenEndpoint)
        if (!hasRooms && !hasTokenEndpoint) {
            if (_state.value.activeSession == null) {
                _state.value = _state.value.copy(
                    status = "DISABLED",
                    message = "Voice Rooms disabled until you configure Rooms Server URL and/or LiveKit Token Endpoint (https://) in Settings."
                )
            }
            return
        }
        val active = _state.value.activeSession
        if (active == null) {
            val parts = buildList {
                if (hasRooms) add("Rooms Server")
                if (hasTokenEndpoint) add("LiveKit Token Endpoint")
                if (isValidWsEndpoint(liveKitWsUrl)) add("LiveKit WS")
            }.joinToString(" + ")
            _state.value = _state.value.copy(
                status = "READY",
                message = "Configured: $parts. Refresh rooms or join a room below."
            )
        }
    }

    suspend fun refreshRooms(
        roomsServerUrl: String,
        authBearerToken: String? = null
    ): Result<List<VoiceRoomSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertExternalNetworkAllowed("Voice Rooms directory refresh")
            if (!isValidHttpEndpoint(roomsServerUrl)) {
                _state.value = _state.value.copy(
                    status = "DISABLED",
                    message = "Rooms Server URL is missing or invalid. Enter an https:// URL in Settings -> Voice Rooms."
                )
                throw IllegalStateException("Rooms Server URL not configured")
            }
            _state.value = _state.value.copy(isLoading = true)
            val targetUrl = normalizeRoomsDirectoryUrl(roomsServerUrl)
            val req = Request.Builder()
                .url(targetUrl)
                .apply {
                    val bearer = authBearerToken?.trim().orEmpty()
                    if (bearer.isNotEmpty()) header("Authorization", "Bearer $bearer")
                }
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = SecurityAuditor.redactSecrets("Rooms server HTTP ${resp.code}: ${body.take(180)}")
                    _state.value = _state.value.copy(status = "ERROR", message = errMsg, isLoading = false)
                    throw IllegalStateException(errMsg)
                }
                val rooms = parseRoomsJson(body)
                _state.value = _state.value.copy(
                    status = if (_state.value.activeSession != null) "CONNECTED" else "READY",
                    message = if (rooms.isEmpty()) "Connected to Rooms server (0 active rooms)." else "Loaded ${rooms.size} room(s) from server.",
                    rooms = rooms,
                    isLoading = false,
                    lastSyncAtMs = System.currentTimeMillis()
                )
                rooms
            }
        }.onFailure { err ->
            _state.value = _state.value.copy(
                status = if (!isValidHttpEndpoint(roomsServerUrl)) "DISABLED" else "ERROR",
                message = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Failed to refresh rooms"),
                isLoading = false
            )
        }
    }

    suspend fun createRoom(
        roomsServerUrl: String,
        roomName: String,
        topic: String,
        hostName: String,
        authBearerToken: String? = null
    ): Result<VoiceRoomSummary> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertExternalNetworkAllowed("Create Voice Room")
            if (!isValidHttpEndpoint(roomsServerUrl)) {
                throw IllegalStateException("Rooms Server URL not configured (https:// required).")
            }
            val cleanName = roomName.trim()
            require(cleanName.isNotEmpty()) { "Enter a room name first." }
            _state.value = _state.value.copy(isLoading = true)
            val targetUrl = normalizeRoomsDirectoryUrl(roomsServerUrl)
            val payload = JSONObject().apply {
                put("name", cleanName)
                put("topic", topic.trim())
                put("host", hostName.trim().ifBlank { "Teena User" })
            }
            val req = Request.Builder()
                .url(targetUrl)
                .apply {
                    val bearer = authBearerToken?.trim().orEmpty()
                    if (bearer.isNotEmpty()) header("Authorization", "Bearer $bearer")
                }
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(SecurityAuditor.redactSecrets("Create room HTTP ${resp.code}: ${body.take(180)}"))
                }
                val obj = runCatching { JSONObject(body) }.getOrNull()
                val created = VoiceRoomSummary(
                    id = obj?.optString("id")?.ifBlank { cleanName } ?: cleanName,
                    name = obj?.optString("name")?.ifBlank { cleanName } ?: cleanName,
                    participantCount = obj?.optInt("numParticipants", 0) ?: 0,
                    topic = obj?.optString("topic").orEmpty().ifBlank { topic.trim() },
                    isLive = true
                )
                val updatedList = listOf(created) + _state.value.rooms.filterNot { it.id == created.id || it.name == created.name }
                _state.value = _state.value.copy(
                    status = if (_state.value.activeSession != null) "CONNECTED" else "READY",
                    message = "Room '${created.name}' created on server.",
                    rooms = updatedList,
                    isLoading = false
                )
                created
            }
        }.onFailure { err ->
            _state.value = _state.value.copy(
                status = "ERROR",
                message = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Create room failed"),
                isLoading = false
            )
        }
    }

    suspend fun joinRoomWithLiveKitToken(
        liveKitTokenEndpoint: String,
        fallbackWsUrl: String,
        roomName: String,
        participantName: String,
        authBearerToken: String? = null
    ): Result<LiveKitTokenSession> =
        joinRoomWithTokenCredentials(
            liveKitTokenEndpoint = liveKitTokenEndpoint,
            fallbackWsUrl = fallbackWsUrl,
            roomName = roomName,
            participantName = participantName,
            authBearerToken = authBearerToken,
            openFallbackSignalSocket = true
        ).map { it.first }

    suspend fun joinRoomWithTokenCredentials(
        liveKitTokenEndpoint: String,
        fallbackWsUrl: String,
        roomName: String,
        participantName: String,
        authBearerToken: String? = null,
        openFallbackSignalSocket: Boolean = false
    ): Result<Pair<LiveKitTokenSession, String>> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertExternalNetworkAllowed("LiveKit room token join")
            if (!isValidHttpEndpoint(liveKitTokenEndpoint)) {
                _state.value = _state.value.copy(
                    status = "DISABLED",
                    message = "LiveKit Token Endpoint missing or invalid. Add an https:// endpoint in Settings -> Voice Rooms."
                )
                throw IllegalStateException("LiveKit Token Endpoint not configured")
            }
            val cleanRoom = roomName.trim()
            require(cleanRoom.isNotEmpty()) { "Room name is required to join." }
            val cleanParticipant = participantName.trim().ifBlank { "teena-user" }

            _state.value = _state.value.copy(isLoading = true)
            val payload = JSONObject().apply {
                put("roomName", cleanRoom)
                put("room", cleanRoom)
                put("participantName", cleanParticipant)
                put("identity", cleanParticipant)
            }
            val req = Request.Builder()
                .url(liveKitTokenEndpoint.trim())
                .apply {
                    val bearer = authBearerToken?.trim().orEmpty()
                    if (bearer.isNotEmpty()) header("Authorization", "Bearer $bearer")
                }
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(SecurityAuditor.redactSecrets("LiveKit token endpoint HTTP ${resp.code}: ${body.take(180)}"))
                }
                val (parsedSession, rawToken) = parseLiveKitTokenResponse(
                    rawJson = body,
                    fallbackRoomName = cleanRoom,
                    fallbackIdentity = cleanParticipant,
                    fallbackWsUrl = fallbackWsUrl
                ).getOrThrow()

                leaveSignalSocketOnly()
                _state.value = _state.value.copy(
                    status = "CONNECTED",
                    message = "Token issued for '${parsedSession.roomName}' (${parsedSession.signalStatus}).",
                    activeSession = parsedSession,
                    isLoading = false
                )

                if (openFallbackSignalSocket && isValidWsEndpoint(parsedSession.liveKitWsUrl)) {
                    openLiveKitSignalSocket(parsedSession.liveKitWsUrl, rawToken)
                }
                parsedSession to rawToken
            }
        }.onFailure { err ->
            _state.value = _state.value.copy(
                status = if (!isValidHttpEndpoint(liveKitTokenEndpoint)) "DISABLED" else "ERROR",
                message = SecurityAuditor.redactSecrets(err.localizedMessage ?: "LiveKit join failed"),
                isLoading = false
            )
        }
    }

    fun registerDirectLiveKitSession(
        roomName: String,
        participantIdentity: String,
        wsUrl: String,
        rawJwt: String
    ): LiveKitTokenSession {
        val session = LiveKitTokenSession(
            roomName = roomName.trim().ifBlank { "teena-room" },
            participantIdentity = participantIdentity.trim().ifBlank { "teena-user" },
            liveKitWsUrl = wsUrl.trim(),
            maskedTokenPreview = maskJwtToken(rawJwt),
            tokenLength = rawJwt.trim().length,
            connectedAtMs = System.currentTimeMillis(),
            signalStatus = "LiveKit WebRTC Connecting..."
        )
        _state.value = _state.value.copy(
            status = "CONNECTED",
            message = "Connecting LiveKit WebRTC session for '${session.roomName}'.",
            activeSession = session,
            isLoading = false
        )
        return session
    }

    fun updateActiveSignalStatus(signalStatus: String) {
        _state.value.activeSession?.let { active ->
            _state.value = _state.value.copy(
                activeSession = active.copy(signalStatus = signalStatus)
            )
        }
    }

    private fun openLiveKitSignalSocket(wsUrl: String, jwtToken: String) {
        runCatching {
            val signalUrl = buildLiveKitSignalUrl(wsUrl, jwtToken)
            val request = Request.Builder().url(signalUrl).build()
            signalSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value.activeSession?.let { active ->
                        _state.value = _state.value.copy(
                            status = "CONNECTED",
                            message = "LiveKit RTC Signal socket open for '${active.roomName}'.",
                            activeSession = active.copy(signalStatus = "RTC Signal Socket Open")
                        )
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _state.value.activeSession?.let { active ->
                        _state.value = _state.value.copy(
                            activeSession = active.copy(signalStatus = "Signal Closed ($code)")
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val safeReason = SecurityAuditor.redactSecrets(t.localizedMessage ?: "Signal connection error")
                    _state.value.activeSession?.let { active ->
                        _state.value = _state.value.copy(
                            message = "Token issued, but LiveKit signal WS error: $safeReason",
                            activeSession = active.copy(signalStatus = "Signal Error: ${safeReason.take(60)}")
                        )
                    }
                }
            })
        }
    }

    private fun leaveSignalSocketOnly() {
        runCatching { signalSocket?.close(1000, "User left room") }
        signalSocket = null
    }

    fun leaveActiveRoom() {
        leaveSignalSocketOnly()
        val prev = _state.value.activeSession
        _state.value = _state.value.copy(
            status = if (_state.value.rooms.isNotEmpty()) "READY" else _state.value.status.let { if (it == "CONNECTED") "READY" else it },
            message = prev?.let { "Left voice room '${it.roomName}'." } ?: "Disconnected from room.",
            activeSession = null
        )
    }
}
