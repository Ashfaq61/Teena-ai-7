package com.example.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.core.security.EmergencyStopController
import com.example.core.security.SecurityAuditor
import com.example.system.TeenaScreenShareService
import io.livekit.android.LiveKit
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.RoomEvent
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RoomParticipantUi(
    val identity: String,
    val name: String,
    val isLocal: Boolean,
    val isSpeaking: Boolean = false,
    val micEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val screenShareEnabled: Boolean = false,
    val hasVideoTrack: Boolean = false
)

data class LiveKitMediaState(
    val connectionState: String = "DISCONNECTED", // DISCONNECTED | CONNECTING | CONNECTED | RECONNECTING | ERROR
    val roomName: String = "",
    val localIdentity: String = "",
    val micEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val screenShareEnabled: Boolean = false,
    val useFrontCamera: Boolean = true,
    val participants: List<RoomParticipantUi> = emptyList(),
    val videoRevision: Long = 0L,
    val statusMessage: String = "Join a LiveKit room to enable group voice, camera, and screen share."
)

/**
 * Manages real LiveKit WebRTC group voice, video tracks, camera flipping, and MediaProjection screen sharing.
 * Lazily instantiates the native WebRTC Room only on explicit user connect so unit/Robolectric runs and unconfigured startup never crash.
 */
class TeenaLiveKitRoomController(
    private val appContext: Context,
    private val emergencyStopController: EmergencyStopController? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LiveKitMediaState())
    val state: StateFlow<LiveKitMediaState> = _state.asStateFlow()

    @Volatile
    private var room: Room? = null
    private var eventsJob: Job? = null

    init {
        TeenaScreenShareService.onStopRequested = {
            scope.launch {
                setScreenShareEnabled(false, null)
            }
        }
    }

    suspend fun connect(
        wsUrl: String,
        jwtToken: String,
        roomName: String,
        participantIdentity: String
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        runCatching {
            emergencyStopController?.assertExternalNetworkAllowed("LiveKit WebRTC connect")
            require(VoiceRoomConnectorEngine.isValidWsEndpoint(wsUrl)) {
                "Invalid LiveKit WebSocket URL (wss:// required)."
            }
            require(jwtToken.isNotBlank()) { "LiveKit JWT token is empty." }

            disconnectInternal()

            _state.value = LiveKitMediaState(
                connectionState = "CONNECTING",
                roomName = roomName,
                localIdentity = participantIdentity,
                statusMessage = "Connecting LiveKit WebRTC room '$roomName'..."
            )

            val liveKitRoom = LiveKit.create(appContext)
            room = liveKitRoom

            eventsJob = scope.launch {
                liveKitRoom.events.collect { event ->
                    handleRoomEvent(liveKitRoom, event)
                }
            }

            liveKitRoom.connect(url = wsUrl.trim(), token = jwtToken.trim())

            val hasMicPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMicPerm) {
                runCatching { liveKitRoom.localParticipant.setMicrophoneEnabled(true) }
            }

            refreshSnapshot(
                liveKitRoom = liveKitRoom,
                connectionState = "CONNECTED",
                statusMessage = if (hasMicPerm) {
                    "LiveKit WebRTC connected in '$roomName'."
                } else {
                    "Connected to '$roomName' (grant RECORD_AUDIO to enable mic)."
                }
            )
        }.onFailure { err ->
            val safeMsg = SecurityAuditor.redactSecrets(err.localizedMessage ?: "LiveKit connection failed")
            _state.value = _state.value.copy(
                connectionState = "ERROR",
                statusMessage = safeMsg
            )
        }
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean): Result<Boolean> = withContext(Dispatchers.Main.immediate) {
        runCatching {
            if (enabled) {
                val granted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    throw SecurityException("RECORD_AUDIO permission required to enable microphone.")
                }
            }
            val r = room ?: throw IllegalStateException("Join an active LiveKit room first.")
            r.localParticipant.setMicrophoneEnabled(enabled)
            refreshSnapshot(r, statusMessage = if (enabled) "Microphone live" else "Microphone muted")
            enabled
        }
    }

    suspend fun setCameraEnabled(enabled: Boolean): Result<Boolean> = withContext(Dispatchers.Main.immediate) {
        runCatching {
            if (enabled) {
                val granted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    throw SecurityException("CAMERA permission required to publish video.")
                }
            }
            val r = room ?: throw IllegalStateException("Join an active LiveKit room first.")
            val pos = if (_state.value.useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK
            r.localParticipant.setCameraEnabled(
                enabled = enabled,
                options = LocalVideoTrackOptions(position = pos)
            )
            refreshSnapshot(r, statusMessage = if (enabled) "Camera video publishing" else "Camera stopped")
            enabled
        }
    }

    suspend fun switchCamera(): Result<Boolean> = withContext(Dispatchers.Main.immediate) {
        runCatching {
            val nextFront = !_state.value.useFrontCamera
            _state.value = _state.value.copy(useFrontCamera = nextFront)
            val r = room ?: return@runCatching nextFront
            val camPub = r.localParticipant.getTrackPublication(Track.Source.CAMERA)
            val localTrack = camPub?.track as? LocalVideoTrack
            if (localTrack != null) {
                localTrack.restartTrack(
                    LocalVideoTrackOptions(
                        position = if (nextFront) CameraPosition.FRONT else CameraPosition.BACK
                    )
                )
            }
            refreshSnapshot(r, statusMessage = "Switched to ${if (nextFront) "Front" else "Rear"} camera")
            nextFront
        }
    }

    suspend fun setScreenShareEnabled(
        enabled: Boolean,
        projectionResultData: Intent?
    ): Result<Boolean> = withContext(Dispatchers.Main.immediate) {
        runCatching {
            val r = room ?: throw IllegalStateException("Join an active LiveKit room before sharing screen.")
            if (enabled) {
                val data = projectionResultData
                    ?: throw IllegalStateException("MediaProjection user permission data required.")
                TeenaScreenShareService.start(appContext)
                val params = ScreenCaptureParams(
                    mediaProjectionPermissionResultData = data,
                    notificationId = TeenaScreenShareService.SCREEN_SHARE_NOTIF_ID,
                    notification = TeenaScreenShareService.buildOngoingNotification(appContext),
                    onStop = {
                        scope.launch {
                            TeenaScreenShareService.stop(appContext)
                            refreshSnapshot(r, statusMessage = "Screen share ended.")
                        }
                    }
                )
                r.localParticipant.setScreenShareEnabled(true, params)
                refreshSnapshot(r, statusMessage = "Sharing screen in room (MediaProjection FGS active)")
                true
            } else {
                runCatching { r.localParticipant.setScreenShareEnabled(false) }
                TeenaScreenShareService.stop(appContext)
                refreshSnapshot(r, statusMessage = "Screen share stopped")
                false
            }
        }.onFailure {
            if (!enabled || projectionResultData == null) {
                TeenaScreenShareService.stop(appContext)
            }
        }
    }

    fun initRenderer(renderer: TextureViewRenderer) {
        runCatching {
            room?.initVideoRenderer(renderer)
        }
    }

    fun findPrimaryVideoTrack(participantIdentity: String): VideoTrack? {
        val r = room ?: return null
        val participant = if (r.localParticipant.identity?.value == participantIdentity) {
            r.localParticipant
        } else {
            r.remoteParticipants.values.firstOrNull { it.identity?.value == participantIdentity }
        } ?: return null

        val screenTrack = participant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
        if (screenTrack != null) return screenTrack
        return participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    }

    fun disconnect() {
        scope.launch {
            disconnectInternal()
            _state.value = LiveKitMediaState()
        }
    }

    private suspend fun disconnectInternal() {
        eventsJob?.cancel()
        eventsJob = null
        TeenaScreenShareService.stop(appContext)
        val currentRoom = room
        room = null
        if (currentRoom != null) {
            runCatching { currentRoom.localParticipant.setScreenShareEnabled(false) }
            runCatching { currentRoom.localParticipant.setCameraEnabled(false) }
            runCatching { currentRoom.localParticipant.setMicrophoneEnabled(false) }
            runCatching { currentRoom.disconnect() }
            runCatching { currentRoom.release() }
        }
    }

    private fun handleRoomEvent(liveKitRoom: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected ->
                refreshSnapshot(liveKitRoom, statusMessage = "${event.participant.name ?: event.participant.identity?.value ?: "Participant"} joined")
            is RoomEvent.ParticipantDisconnected ->
                refreshSnapshot(liveKitRoom, statusMessage = "${event.participant.name ?: event.participant.identity?.value ?: "Participant"} left")
            is RoomEvent.TrackSubscribed,
            is RoomEvent.TrackUnsubscribed,
            is RoomEvent.TrackPublished,
            is RoomEvent.TrackUnpublished,
            is RoomEvent.TrackMuted,
            is RoomEvent.TrackUnmuted,
            is RoomEvent.ActiveSpeakersChanged ->
                refreshSnapshot(liveKitRoom)
            is RoomEvent.Reconnecting ->
                refreshSnapshot(liveKitRoom, connectionState = "RECONNECTING", statusMessage = "Reconnecting LiveKit room...")
            is RoomEvent.Reconnected ->
                refreshSnapshot(liveKitRoom, connectionState = "CONNECTED", statusMessage = "LiveKit reconnected")
            is RoomEvent.Disconnected -> {
                TeenaScreenShareService.stop(appContext)
                _state.value = _state.value.copy(
                    connectionState = "DISCONNECTED",
                    screenShareEnabled = false,
                    statusMessage = "Disconnected from LiveKit room."
                )
            }
            else -> Unit
        }
    }

    private fun refreshSnapshot(
        liveKitRoom: Room,
        connectionState: String? = null,
        statusMessage: String? = null
    ) {
        val local = liveKitRoom.localParticipant
        val speakerIds = liveKitRoom.activeSpeakers.mapNotNull { it.identity?.value }.toSet()

        fun toUi(p: io.livekit.android.room.participant.Participant, isLocal: Boolean): RoomParticipantUi {
            val id = p.identity?.value ?: if (isLocal) _state.value.localIdentity.ifBlank { "local" } else "remote"
            val camTrack = p.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            val scrTrack = p.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
            return RoomParticipantUi(
                identity = id,
                name = p.name?.ifBlank { id } ?: id,
                isLocal = isLocal,
                isSpeaking = id in speakerIds,
                micEnabled = p.isMicrophoneEnabled,
                cameraEnabled = p.isCameraEnabled,
                screenShareEnabled = p.isScreenShareEnabled,
                hasVideoTrack = camTrack != null || scrTrack != null
            )
        }

        val allParticipants = buildList {
            add(toUi(local, isLocal = true))
            liveKitRoom.remoteParticipants.values.forEach { add(toUi(it, isLocal = false)) }
        }

        _state.value = _state.value.copy(
            connectionState = connectionState ?: _state.value.connectionState,
            roomName = liveKitRoom.name ?: _state.value.roomName,
            localIdentity = local.identity?.value ?: _state.value.localIdentity,
            micEnabled = local.isMicrophoneEnabled,
            cameraEnabled = local.isCameraEnabled,
            screenShareEnabled = local.isScreenShareEnabled,
            participants = allParticipants,
            videoRevision = System.nanoTime(),
            statusMessage = statusMessage ?: _state.value.statusMessage
        )
    }
}
