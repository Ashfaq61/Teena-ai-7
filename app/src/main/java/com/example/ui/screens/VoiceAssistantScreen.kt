package com.example.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.ai.GeminiLiveVoiceManager
import com.example.ai.LiveVoiceState
import com.example.ai.TeenaIdentityEngine
import com.example.system.TeenaFaceDetectionEngine
import com.example.system.TeenaForegroundService
import com.example.system.TransientFaceDetectionState
import com.example.ui.components.FaceDetectionOverlay
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun VoiceAssistantScreen(viewModel: TeenaViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val voiceState by viewModel.liveVoiceState.collectAsStateWithLifecycle()
    val statusDetail by viewModel.liveVoiceStatus.collectAsStateWithLifecycle()
    val liveVoiceModel by viewModel.liveVoiceModel.collectAsStateWithLifecycle()
    val transcript by viewModel.liveTranscript.collectAsStateWithLifecycle()
    val voiceHistory by viewModel.voiceHistory.collectAsStateWithLifecycle()
    val latencyStats by viewModel.liveLatencyStats.collectAsStateWithLifecycle()
    val micLevel by viewModel.micAudioLevel.collectAsStateWithLifecycle()
    val playbackLevel by viewModel.playbackAudioLevel.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()

    var liveTextInject by remember { mutableStateOf("") }
    var visionHint by remember { mutableStateOf("") }
    var showLiveCamera by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var liveFaceDetectionEnabled by remember { mutableStateOf(false) }
    var liveFaceOverlayState by remember { mutableStateOf(TransientFaceDetectionState()) }
    val faceEngine = remember(context) { TeenaFaceDetectionEngine(context) }
    val faceExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val faceAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            faceEngine.closeDetector()
            liveFaceOverlayState = TransientFaceDetectionState()
            runCatching { faceExecutor.shutdown() }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshSystemHealth()
        if (granted) {
            viewModel.liveVoiceManager.startLiveSession(settings.selectedVoice)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshSystemHealth()
        if (granted) {
            showLiveCamera = true
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.sendLiveVisionPhotoUri(
                uri = uri,
                promptHint = visionHint.ifBlank { "Is tasveer mein kya hai?" }
            )
        }
    }

    val isLiveActive = voiceState == LiveVoiceState.LISTENING ||
        voiceState == LiveVoiceState.THINKING ||
        voiceState == LiveVoiceState.SPEAKING ||
        voiceState == LiveVoiceState.CONNECTING

    val orbColor = when (voiceState) {
        LiveVoiceState.LISTENING -> NeuralCyan
        LiveVoiceState.SPEAKING -> NeuralMint
        LiveVoiceState.THINKING, LiveVoiceState.CONNECTING, LiveVoiceState.RECONNECTING -> StatusAmber
        LiveVoiceState.ERROR, LiveVoiceState.BLOCKED -> StatusCrimson
        LiveVoiceState.DISCONNECTED -> NeuralViolet
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("voice_assistant_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "Gemini Live Voice Assistant",
                subtitle = "Direct WebSocket audio-to-audio via ModelRegistry (Hindi, Hinglish, Roman Urdu, Urdu & English)",
                trailing = { StatusPill(voiceState.name) }
            )
        }

        // Audio-Reactive Holographic Live Orb + RMS Meters
        item {
            NeuralCard(borderColor = orbColor.copy(alpha = 0.55f)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TeenaHolographicOrb(
                        voiceState = voiceState,
                        personalityId = settings.personalityId,
                        micLevel = micLevel,
                        playbackLevel = playbackLevel,
                        isLiveActive = isLiveActive,
                        onClick = {
                            if (isLiveActive) {
                                viewModel.liveVoiceManager.stopLiveSession()
                            } else if (perms.recordAudio) {
                                viewModel.liveVoiceManager.startLiveSession(settings.selectedVoice)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mic Energy: ${(micLevel * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeuralCyan
                            )
                            LinearProgressIndicator(
                                progress = { micLevel.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = NeuralCyan
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Voice Output: ${(playbackLevel * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeuralMint
                            )
                            LinearProgressIndicator(
                                progress = { playbackLevel.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = NeuralMint
                            )
                        }
                    }

                    Text(
                        text = liveVoiceModel?.let { "Connected Live Model: $it" }
                            ?: "Live Model: Resolved from ModelRegistry (bidiGenerateContent)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (liveVoiceModel != null) NeuralMint else NeuralCyan
                    )

                    Text(
                        text = statusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (voiceState == LiveVoiceState.BLOCKED || voiceState == LiveVoiceState.ERROR) {
                            StatusCrimson
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    if (isLiveActive || TeenaForegroundService.isVoiceForegroundActive) {
                        Text(
                            text = "Voice Foreground Service active (Notification Stop available)",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralMint
                        )
                    }

                    if (BuildConfig.DEBUG) {
                        val latencyText = if (latencyStats.sampleCount > 0 || latencyStats.connectLatencyMs > 0L) {
                            "Debug Latency • Connect: ${latencyStats.connectLatencyMs}ms • Last turn: ${latencyStats.lastTurnLatencyMs}ms • Avg: ${latencyStats.avgTurnLatencyMs}ms (${latencyStats.sampleCount} turns)"
                        } else {
                            "Debug Latency • Connect & turn timing armed"
                        }
                        Text(
                            text = latencyText,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralCyan,
                            modifier = Modifier.testTag("voice_debug_latency_text")
                        )
                    }

                    if (voiceState == LiveVoiceState.BLOCKED) {
                        Text(
                            text = "Add a Gemini API key in More -> API Manager (Primary Gemini slot) to enable Live Voice.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (voiceState == LiveVoiceState.SPEAKING) {
                        OutlinedButton(
                            onClick = { viewModel.liveVoiceManager.interruptPlaybackNow() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("voice_barge_in_btn")
                        ) {
                            Text("Stop Speech (Barge-In)")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isLiveActive) {
                                    viewModel.liveVoiceManager.stopLiveSession()
                                } else if (perms.recordAudio) {
                                    viewModel.liveVoiceManager.startLiveSession(settings.selectedVoice)
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            colors = if (isLiveActive) ButtonDefaults.buttonColors(containerColor = StatusCrimson)
                            else ButtonDefaults.buttonColors(),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("voice_toggle_btn")
                        ) {
                            Text(if (isLiveActive) "Disconnect" else if (perms.recordAudio) "Start Live Audio" else "Grant Mic & Start")
                        }
                        OutlinedButton(
                            onClick = { viewModel.liveVoiceManager.reconnect(settings.selectedVoice) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("voice_reconnect_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
                            Spacer(Modifier.size(4.dp))
                            Text("Reconnect")
                        }
                    }
                }
            }
        }

        // Teena Personality Selector
        item {
            val activePreset = TeenaIdentityEngine.presetFor(settings.personalityId)
            val userBadge = settings.userName.trim().ifBlank { settings.userPreferredAddressing }
            SectionHeader(
                "Teena Personality (${activePreset.title})",
                "${activePreset.tagline} • Addressing: $userBadge"
            )
            PersonalityPresetStrip(
                selectedPersonalityId = settings.personalityId,
                onSelect = { viewModel.selectPersonality(it) }
            )
        }

        // Voice Selection (Male & Female Voices)
        item {
            SectionHeader("Voice Persona (Male & Female)", "Gemini Live native speech voices")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GeminiLiveVoiceManager.availableVoices) { v ->
                    FilterChip(
                        selected = settings.selectedVoice == v.id,
                        onClick = { viewModel.updateSettings { it.copy(selectedVoice = v.id) } },
                        label = { Text("${v.id} (${v.gender})") }
                    )
                }
            }
        }

        // Controls: Continuous listening & auto language detection
        item {
            NeuralCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Continuous Conversation Mode", style = MaterialTheme.typography.titleSmall)
                        Text("Keep bidirectional VAD audio open between turns", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.continuousListening,
                        onCheckedChange = { c -> viewModel.updateSettings { it.copy(continuousListening = c) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic Language Detection", style = MaterialTheme.typography.titleSmall)
                        Text("Hindi, Hinglish, Roman Urdu, Urdu & English", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.autoDetectLanguage,
                        onCheckedChange = { a -> viewModel.updateSettings { it.copy(autoDetectLanguage = a) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Echo Guard (Anti-Feedback)", style = MaterialTheme.typography.titleSmall)
                        Text("Prevent mic from hearing Teena's own voice", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.echoGuardEnabled,
                        onCheckedChange = { eg -> viewModel.updateSettings { it.copy(echoGuardEnabled = eg) } },
                        modifier = Modifier.testTag("voice_echo_guard_switch")
                    )
                }
            }
        }

        // Optional text injection + Live Multimodal Visual HUD (Camera / Photo -> Live)
        item {
            NeuralCard(modifier = Modifier.testTag("voice_live_vision_card")) {
                SectionHeader(
                    title = "Live Visual Input (Camera & Photo)",
                    subtitle = if (isLiveActive) "Send a live camera frame or photo into Gemini Live (RAM-only)" else "Connect Live Audio first to stream camera or photo context",
                    trailing = {
                        StatusPill(if (showLiveCamera) "ACTIVE" else if (isLiveActive) "READY" else "IDLE")
                    }
                )
                OutlinedTextField(
                    value = visionHint,
                    onValueChange = { visionHint = it },
                    label = { Text("Optional question about camera/photo (e.g. 'Ye kya hai?')") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (showLiveCamera) {
                                showLiveCamera = false
                            } else if (perms.camera) {
                                showLiveCamera = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("voice_camera_toggle_btn")
                    ) {
                        Icon(
                            if (showLiveCamera) Icons.Default.Close else Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(if (showLiveCamera) "Close Camera" else "Live Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = isLiveActive,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("voice_photo_live_btn")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Photo -> Live")
                    }
                }

                if (showLiveCamera && perms.camera) {
                    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
                    DisposableEffect(useFrontCamera, liveFaceDetectionEnabled) {
                        onDispose {
                            faceAnalysis.clearAnalyzer()
                            liveFaceOverlayState = TransientFaceDetectionState(permissionGranted = perms.camera)
                            runCatching { cameraProviderFuture.get().unbindAll() }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                            },
                            update = { previewView ->
                                cameraProviderFuture.addListener(
                                    {
                                        runCatching {
                                            val provider = cameraProviderFuture.get()
                                            val preview = Preview.Builder().build().also {
                                                it.surfaceProvider = previewView.surfaceProvider
                                            }
                                            val selector = if (useFrontCamera) {
                                                CameraSelector.DEFAULT_FRONT_CAMERA
                                            } else {
                                                CameraSelector.DEFAULT_BACK_CAMERA
                                            }
                                            provider.unbindAll()
                                            if (liveFaceDetectionEnabled && TeenaFaceDetectionEngine.hasExplicitCameraPermission(context)) {
                                                faceAnalysis.setAnalyzer(
                                                    faceExecutor,
                                                    faceEngine.createAnalyzer(
                                                        isFrontCameraProvider = { useFrontCamera },
                                                        isEnabledProvider = { liveFaceDetectionEnabled }
                                                    ) { next -> liveFaceOverlayState = next }
                                                )
                                                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, faceAnalysis)
                                            } else {
                                                faceAnalysis.clearAnalyzer()
                                                liveFaceOverlayState = TransientFaceDetectionState(permissionGranted = true)
                                                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                                            }
                                        }
                                    },
                                    ContextCompat.getMainExecutor(context)
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        FaceDetectionOverlay(state = liveFaceOverlayState)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = liveFaceDetectionEnabled,
                            onClick = {
                                liveFaceDetectionEnabled = !liveFaceDetectionEnabled
                                if (!liveFaceDetectionEnabled) {
                                    liveFaceOverlayState = TransientFaceDetectionState(permissionGranted = true)
                                    faceEngine.closeDetector()
                                }
                            },
                            label = { Text("On-Device Face Box (No Storage)") },
                            modifier = Modifier.testTag("voice_face_detection_chip")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                imageCapture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bmp = liveImageProxyToBitmap(image)
                                            image.close()
                                            if (bmp != null) {
                                                viewModel.sendLiveVisionBitmap(
                                                    bitmap = bmp,
                                                    promptHint = visionHint.ifBlank { "Camera mein kya dikh raha hai?" }
                                                )
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            viewModel.setStatusBannerMessage("Camera capture error: ${exception.message}")
                                        }
                                    }
                                )
                            },
                            enabled = isLiveActive,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("voice_snap_ask_btn")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Snap & Ask Live")
                        }
                        OutlinedButton(
                            onClick = { useFrontCamera = !useFrontCamera },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera")
                        }
                    }
                }
            }
        }

        if (isLiveActive) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = liveTextInject,
                        onValueChange = { liveTextInject = it },
                        label = { Text("Send text into Live session") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (liveTextInject.isNotBlank()) {
                                viewModel.liveVoiceManager.sendTextDuringLiveSession(liveTextInject)
                                liveTextInject = ""
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Send")
                    }
                }
            }
        }

        // Live Session Transcript & Voice Tool History
        item {
            SectionHeader("Live Session Events & Voice History")
        }

        if (transcript.isEmpty() && voiceHistory.isEmpty()) {
            item {
                NeuralCard {
                    Text(
                        "No active Gemini Live events yet. Tap 'Start Live Audio' to speak with Teena AI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(transcript.reversed()) { line ->
                NeuralCard {
                    Text(line.role.uppercase(), style = MaterialTheme.typography.labelSmall, color = NeuralCyan, fontWeight = FontWeight.Bold)
                    Text(line.text, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(voiceHistory, key = { it.id }) { msg ->
                NeuralCard {
                    Text("${msg.providerUsed} • ${msg.modelUsed}", style = MaterialTheme.typography.labelSmall, color = NeuralMint)
                    Text(msg.content, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun liveImageProxyToBitmap(proxy: ImageProxy): Bitmap? {
    return runCatching {
        val buffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }.getOrNull()
}
