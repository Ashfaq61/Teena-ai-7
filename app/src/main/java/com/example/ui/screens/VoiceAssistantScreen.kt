package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.GeminiLiveVoiceManager
import com.example.ai.LiveVoiceState
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun VoiceAssistantScreen(viewModel: TeenaViewModel) {
    val voiceState by viewModel.liveVoiceState.collectAsStateWithLifecycle()
    val statusDetail by viewModel.liveVoiceStatus.collectAsStateWithLifecycle()
    val transcript by viewModel.liveTranscript.collectAsStateWithLifecycle()
    val voiceHistory by viewModel.voiceHistory.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()

    var liveTextInject by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshSystemHealth()
        if (granted) {
            viewModel.liveVoiceManager.startLiveSession(settings.selectedVoice)
        }
    }

    val isLiveActive = voiceState == LiveVoiceState.LISTENING ||
        voiceState == LiveVoiceState.THINKING ||
        voiceState == LiveVoiceState.SPEAKING ||
        voiceState == LiveVoiceState.CONNECTING

    val pulse = rememberInfiniteTransition(label = "voice_orb")
    val scale by pulse.animateFloat(
        initialValue = 0.94f,
        targetValue = if (isLiveActive) 1.12f else 1.0f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "orb_scale"
    )

    val orbColor = when (voiceState) {
        LiveVoiceState.LISTENING -> NeuralCyan
        LiveVoiceState.SPEAKING -> NeuralMint
        LiveVoiceState.THINKING, LiveVoiceState.CONNECTING, LiveVoiceState.RECONNECTING -> StatusAmber
        LiveVoiceState.ERROR -> StatusCrimson
        LiveVoiceState.DISCONNECTED -> NeuralViolet
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "Gemini Live Voice Assistant",
                subtitle = "Real-time bidirectional audio-to-audio (Urdu, Roman Urdu & English)",
                trailing = { StatusPill(voiceState.name) }
            )
        }

        // Animated Live Orb
        item {
            NeuralCard(borderColor = orbColor.copy(alpha = 0.55f)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(148.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(orbColor.copy(alpha = 0.85f), orbColor.copy(alpha = 0.15f))
                                )
                            )
                            .clickable {
                                if (isLiveActive) {
                                    viewModel.liveVoiceManager.stopLiveSession()
                                } else if (perms.recordAudio) {
                                    viewModel.liveVoiceManager.startLiveSession(settings.selectedVoice)
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLiveActive) Icons.Default.CallEnd else Icons.Default.Mic,
                            contentDescription = if (isLiveActive) "Stop Gemini Live" else "Start Gemini Live",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Text(
                        text = statusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

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
                        Text("Urdu, Roman Urdu & English switching", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.autoDetectLanguage,
                        onCheckedChange = { a -> viewModel.updateSettings { it.copy(autoDetectLanguage = a) } }
                    )
                }
            }
        }

        // Optional text injection during active Live session
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
