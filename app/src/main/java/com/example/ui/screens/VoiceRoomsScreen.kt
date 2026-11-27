package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.TeenaLiveKitRoomController
import com.example.ai.VoiceRoomConnectorEngine
import com.example.ui.components.LiveKitDiagnosticsCard
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceRoomsScreen(
    viewModel: TeenaViewModel,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val roomsState by viewModel.voiceRoomsState.collectAsStateWithLifecycle()
    val liveKitState by viewModel.liveKitMediaState.collectAsStateWithLifecycle()
    val firebaseState by viewModel.firebaseAuthState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()

    var roomsUrlInput by remember(settings.voiceRoomsServerUrl) { mutableStateOf(settings.voiceRoomsServerUrl) }
    var tokenUrlInput by remember(settings.liveKitTokenEndpoint) { mutableStateOf(settings.liveKitTokenEndpoint) }
    var wsUrlInput by remember(settings.liveKitWsUrl) { mutableStateOf(settings.liveKitWsUrl) }
    var participantInput by remember(settings.voiceRoomParticipantName, settings.userName) {
        mutableStateOf(settings.voiceRoomParticipantName.ifBlank { settings.userName })
    }
    var newRoomName by remember { mutableStateOf("") }
    var newRoomTopic by remember { mutableStateOf("") }
    var directJoinRoom by remember { mutableStateOf("") }
    var directJwtInput by remember { mutableStateOf("") }
    var fbEmailInput by remember { mutableStateOf("") }
    var fbPasswordInput by remember { mutableStateOf("") }

    var showConfigEditor by remember(roomsUrlInput, tokenUrlInput, wsUrlInput) {
        mutableStateOf(
            !VoiceRoomConnectorEngine.isValidHttpEndpoint(settings.voiceRoomsServerUrl) &&
                !VoiceRoomConnectorEngine.isValidHttpEndpoint(settings.liveKitTokenEndpoint) &&
                !VoiceRoomConnectorEngine.isValidWsEndpoint(settings.liveKitWsUrl)
        )
    }

    val mediaPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemHealth()
    }

    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startRoomScreenShare(result.data!!)
        } else {
            viewModel.setStatusBannerMessage("Screen share permission cancelled.")
        }
    }

    LaunchedEffect(settings.voiceRoomsServerUrl, settings.liveKitTokenEndpoint, settings.liveKitWsUrl) {
        viewModel.syncVoiceRoomsStatus()
    }

    val hasRoomsServer = VoiceRoomConnectorEngine.isValidHttpEndpoint(settings.voiceRoomsServerUrl)
    val hasTokenServer = VoiceRoomConnectorEngine.isValidHttpEndpoint(settings.liveKitTokenEndpoint)
    val hasLiveKitWs = VoiceRoomConnectorEngine.isValidWsEndpoint(settings.liveKitWsUrl)
    val activeSession = roomsState.activeSession

    val statusBorderColor = when (roomsState.status) {
        "CONNECTED" -> NeuralMint
        "READY" -> NeuralCyan
        "ERROR" -> StatusCrimson
        else -> StatusAmber
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("voice_rooms_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(borderColor = statusBorderColor.copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Teena Rooms — Group Voice, Video & Screen Share",
                    subtitle = "LiveKit WebRTC media + MediaProjection foreground service + optional Firebase Auth/FCM",
                    trailing = { StatusPill(roomsState.status) }
                )
                if (roomsState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeuralCyan)
                }
                Text(
                    text = roomsState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (roomsState.status == "ERROR") StatusCrimson else MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showConfigEditor = !showConfigEditor },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("voice_rooms_toggle_config_btn")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (showConfigEditor) "Hide Endpoints" else "Endpoint Config")
                    }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Settings")
                    }
                }
            }
        }

        item {
            LiveKitDiagnosticsCard(mediaState = liveKitState)
        }

        if (showConfigEditor) {
            item {
                NeuralCard(modifier = Modifier.testTag("voice_rooms_config_card")) {
                    SectionHeader(
                        title = "External Rooms & LiveKit Endpoints",
                        subtitle = "App runs normally when blank (feature disabled). Configure HTTPS / WSS endpoints to activate."
                    )
                    OutlinedTextField(
                        value = roomsUrlInput,
                        onValueChange = { roomsUrlInput = it },
                        label = { Text("Rooms Server URL (e.g. https://rooms.example.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("voice_rooms_server_input")
                    )
                    OutlinedTextField(
                        value = tokenUrlInput,
                        onValueChange = { tokenUrlInput = it },
                        label = { Text("LiveKit Token Endpoint (e.g. https://rooms.example.com/livekit/token)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("livekit_token_endpoint_input")
                    )
                    OutlinedTextField(
                        value = wsUrlInput,
                        onValueChange = { wsUrlInput = it },
                        label = { Text("LiveKit WebSocket URL (e.g. wss://your-livekit.livekit.cloud)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("livekit_ws_url_input")
                    )
                    OutlinedTextField(
                        value = participantInput,
                        onValueChange = { participantInput = it },
                        label = { Text("Participant Display Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("voice_room_participant_input")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveVoiceRoomEndpoints(
                                    roomsUrl = roomsUrlInput,
                                    tokenEndpoint = tokenUrlInput,
                                    wsUrl = wsUrlInput,
                                    participantName = participantInput
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("voice_rooms_save_config_btn")
                        ) {
                            Text("Save Endpoints")
                        }
                        OutlinedButton(
                            onClick = {
                                roomsUrlInput = ""
                                tokenUrlInput = ""
                                wsUrlInput = ""
                                viewModel.clearVoiceRoomEndpoints()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("rooms_clear_endpoints_btn")
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear endpoints", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Disable")
                        }
                    }
                }
            }
        }

        // Active Group Call Stage (LiveKit WebRTC Voice + Video + Screen Share)
        if (activeSession != null || liveKitState.connectionState == "CONNECTED" || liveKitState.connectionState == "CONNECTING") {
            item {
                NeuralCard(borderColor = NeuralMint.copy(alpha = 0.7f)) {
                    SectionHeader(
                        title = "Active Room: ${liveKitState.roomName.ifBlank { activeSession?.roomName ?: "Room" }}",
                        subtitle = "Identity: ${activeSession?.participantIdentity ?: liveKitState.localIdentity} • ${liveKitState.statusMessage}",
                        trailing = { StatusPill(liveKitState.connectionState.ifBlank { "CONNECTED" }) }
                    )
                    activeSession?.let { sess ->
                        Text(
                            text = "JWT: ${sess.maskedTokenPreview} (${sess.tokenLength} chars) • Server: ${sess.liveKitWsUrl.ifBlank { "Endpoint-provided" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralCyan
                        )
                    }

                    // Render active video/screen-share participant tracks
                    val videoParticipants = liveKitState.participants.filter { it.hasVideoTrack }
                    if (videoParticipants.isNotEmpty()) {
                        videoParticipants.forEach { p ->
                            NeuralCard(borderColor = if (p.isSpeaking) NeuralMint else NeuralCyan.copy(alpha = 0.4f)) {
                                Text(
                                    text = "${p.name}${if (p.isLocal) " (You)" else ""} • ${if (p.screenShareEnabled) "Screen Share" else "Camera"}${if (p.isSpeaking) " • Speaking" else ""}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (p.isSpeaking) NeuralMint else NeuralCyan
                                )
                                LiveKitParticipantVideoBox(
                                    controller = viewModel.container.liveKitRoomController,
                                    participantIdentity = p.identity,
                                    revision = liveKitState.videoRevision
                                )
                            }
                        }
                    }

                    // Participant roster chips
                    if (liveKitState.participants.isNotEmpty()) {
                        Text("Participants (${liveKitState.participants.size})", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            liveKitState.participants.forEach { p ->
                                StatusPill(
                                    "${p.name}${if (p.isLocal) "(You)" else ""}: " +
                                        listOfNotNull(
                                            if (p.micEnabled) "MIC" else "MUTED",
                                            if (p.cameraEnabled) "CAM" else null,
                                            if (p.screenShareEnabled) "SCREEN" else null,
                                            if (p.isSpeaking) "SPEAKING" else null
                                        ).joinToString("/")
                                )
                            }
                        }
                    }

                    // Media Controls: Mic, Camera, Flip, MediaProjection Screen Share, Leave
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (!perms.recordAudio) {
                                    mediaPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                } else {
                                    viewModel.toggleRoomMic()
                                }
                            },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_mic_btn")
                        ) {
                            Icon(
                                if (liveKitState.micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(if (liveKitState.micEnabled) "Mute Mic" else "Unmute Mic")
                        }

                        FilledTonalButton(
                            onClick = {
                                if (!perms.camera) {
                                    mediaPermLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                } else {
                                    viewModel.toggleRoomCamera()
                                }
                            },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_camera_btn")
                        ) {
                            Icon(
                                if (liveKitState.cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(if (liveKitState.cameraEnabled) "Stop Video" else "Start Video")
                        }

                        OutlinedButton(
                            onClick = { viewModel.switchRoomCamera() },
                            enabled = liveKitState.cameraEnabled,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_flip_cam_btn")
                        ) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Flip")
                        }

                        FilledTonalButton(
                            onClick = {
                                if (liveKitState.screenShareEnabled) {
                                    viewModel.stopRoomScreenShare()
                                } else {
                                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                                    if (mgr != null) {
                                        screenShareLauncher.launch(mgr.createScreenCaptureIntent())
                                    } else {
                                        viewModel.setStatusBannerMessage("MediaProjection service unavailable on this device.")
                                    }
                                }
                            },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_screen_share_btn")
                        ) {
                            Icon(
                                if (liveKitState.screenShareEnabled) Icons.Default.StopScreenShare else Icons.Default.PresentToAll,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(if (liveKitState.screenShareEnabled) "Stop Share" else "Share Screen")
                        }
                    }

                    Button(
                        onClick = { viewModel.leaveVoiceRoom() },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusCrimson),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("voice_rooms_leave_btn")
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Leave Room")
                    }
                }
            }
        }

        // Room Directory + Token Endpoint Join + Direct WSS+JWT Join
        item {
            NeuralCard {
                SectionHeader(
                    title = "Room Directory & Group Call Join",
                    subtitle = if (hasRoomsServer || hasTokenServer || hasLiveKitWs) {
                        "Real LiveKit WebRTC & HTTP room requests only when Emergency Stop is off"
                    } else {
                        "Configure endpoints above to enable live rooms (disabled cleanly otherwise)"
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = directJoinRoom,
                        onValueChange = { directJoinRoom = it },
                        label = { Text("Room name to join") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("voice_rooms_direct_join_input")
                    )
                    Button(
                        onClick = {
                            if (!hasTokenServer && hasLiveKitWs && directJwtInput.isNotBlank()) {
                                viewModel.joinLiveKitDirect(settings.liveKitWsUrl, directJwtInput, directJoinRoom)
                            } else {
                                viewModel.joinVoiceRoom(directJoinRoom)
                            }
                        },
                        enabled = (hasTokenServer || (hasLiveKitWs && directJwtInput.isNotBlank())) &&
                            directJoinRoom.isNotBlank() && !emergencyStopped && !roomsState.isLoading,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("voice_rooms_direct_join_btn")
                    ) {
                        Text("Join")
                    }
                }

                if (hasLiveKitWs && !hasTokenServer) {
                    OutlinedTextField(
                        value = directJwtInput,
                        onValueChange = { directJwtInput = it },
                        label = { Text("Direct LiveKit JWT Token (optional if no Token Endpoint)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rooms_direct_jwt_input")
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.refreshVoiceRooms() },
                    enabled = hasRoomsServer && !emergencyStopped && !roomsState.isLoading,
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("voice_rooms_refresh_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Refresh Rooms")
                }

                OutlinedTextField(
                    value = newRoomName,
                    onValueChange = { newRoomName = it },
                    label = { Text("New Room Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("voice_room_name_input")
                )
                OutlinedTextField(
                    value = newRoomTopic,
                    onValueChange = { newRoomTopic = it },
                    label = { Text("Topic / Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("voice_room_topic_input")
                )
                FilledTonalButton(
                    onClick = {
                        viewModel.createVoiceRoom(newRoomName, newRoomTopic)
                        newRoomName = ""
                        newRoomTopic = ""
                    },
                    enabled = hasRoomsServer && newRoomName.isNotBlank() && !emergencyStopped && !roomsState.isLoading,
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("voice_room_create_btn")
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Create Voice/Video Room")
                }
            }
        }

        // Optional Firebase Auth + FCM (strictly conditional on app/google-services.json)
        item {
            NeuralCard(modifier = Modifier.testTag("rooms_firebase_card")) {
                SectionHeader(
                    title = "Optional Firebase Auth & Room Push (FCM)",
                    subtitle = "Activates automatically only when app/google-services.json is supplied",
                    trailing = {
                        StatusPill(
                            firebaseState.statusLabel,
                            modifier = Modifier.testTag("rooms_firebase_status_pill")
                        )
                    }
                )
                Text(
                    text = firebaseState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (firebaseState.hasFcmToken) {
                    Text(
                        text = "FCM Token: ${firebaseState.fcmTokenPreview}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralMint
                    )
                }
                if (firebaseState.lastPushTitle.isNotBlank()) {
                    Text(
                        text = "Last Invite Push: ${firebaseState.lastPushTitle} — ${firebaseState.lastPushBody}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralCyan
                    )
                }

                if (firebaseState.googleServicesPresent && !firebaseState.signedIn) {
                    OutlinedTextField(
                        value = fbEmailInput,
                        onValueChange = { fbEmailInput = it },
                        label = { Text("Firebase Account Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rooms_firebase_email_input")
                    )
                    OutlinedTextField(
                        value = fbPasswordInput,
                        onValueChange = { fbPasswordInput = it },
                        label = { Text("Password (6+ chars)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("rooms_firebase_password_input")
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!firebaseState.signedIn) {
                        Button(
                            onClick = { viewModel.signInFirebaseEmail(fbEmailInput, fbPasswordInput) },
                            enabled = firebaseState.googleServicesPresent && fbEmailInput.isNotBlank() && fbPasswordInput.length >= 6,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_firebase_signin_btn")
                        ) {
                            Text("Sign In")
                        }
                        OutlinedButton(
                            onClick = { viewModel.registerFirebaseEmail(fbEmailInput, fbPasswordInput) },
                            enabled = firebaseState.googleServicesPresent && fbEmailInput.isNotBlank() && fbPasswordInput.length >= 6,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_firebase_register_btn")
                        ) {
                            Text("Create Account")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.signOutFirebase() },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_firebase_signout_btn")
                        ) {
                            Text("Sign Out (${firebaseState.displayName})")
                        }
                    }
                    FilledTonalButton(
                        onClick = { viewModel.refreshFirebaseFcmToken() },
                        enabled = firebaseState.googleServicesPresent,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("rooms_firebase_fcm_btn")
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Sync FCM Token")
                    }
                }
            }
        }

        item {
            SectionHeader("Discovered Rooms (${roomsState.rooms.size})")
        }

        if (roomsState.rooms.isEmpty()) {
            item {
                NeuralCard {
                    Text(
                        text = if (hasRoomsServer) "No rooms returned yet. Tap Refresh Rooms or create a new room." else "Rooms feature is safely disabled (no server URL supplied). App stability unaffected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(roomsState.rooms, key = { it.id }) { room ->
                NeuralCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(room.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NeuralCyan)
                            if (room.topic.isNotBlank()) {
                                Text(room.topic, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "${room.participantCount} participant(s) • ${if (room.isLive) "Live" else "Idle"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { viewModel.joinVoiceRoom(room.name) },
                            enabled = hasTokenServer && !emergencyStopped && !roomsState.isLoading,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.MeetingRoom, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Join")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveKitParticipantVideoBox(
    controller: TeenaLiveKitRoomController,
    participantIdentity: String,
    revision: Long
) {
    val trackState = remember(participantIdentity, revision) {
        controller.findPrimaryVideoTrack(participantIdentity)
    }
    var attachedTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var rendererRef by remember { mutableStateOf<TextureViewRenderer?>(null) }

    DisposableEffect(participantIdentity, trackState) {
        val renderer = rendererRef
        if (renderer != null && trackState != null && attachedTrack != trackState) {
            runCatching { attachedTrack?.removeRenderer(renderer) }
            runCatching { trackState.addRenderer(renderer) }
            attachedTrack = trackState
        }
        onDispose {
            val r = rendererRef
            if (r != null && attachedTrack != null) {
                runCatching { attachedTrack?.removeRenderer(r) }
                attachedTrack = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(MaterialTheme.shapes.medium)
    ) {
        AndroidView(
            factory = { ctx ->
                TextureViewRenderer(ctx).apply {
                    controller.initRenderer(this)
                    rendererRef = this
                    trackState?.let { track ->
                        runCatching {
                            track.addRenderer(this)
                            attachedTrack = track
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
