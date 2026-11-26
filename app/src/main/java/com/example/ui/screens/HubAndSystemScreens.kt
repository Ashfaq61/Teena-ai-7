package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.ai.TeenaIdentityEngine
import com.example.data.local.toIdentityProfile
import com.example.system.TeenaAccessibilityService
import com.example.system.TeenaNotificationListenerService
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HubTile(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

@Composable
fun HubDirectoryScreen(onNavigate: (String) -> Unit) {
    val tiles = listOf(
        HubTile("automation", "Screen Automation", "Accessibility, Social, Phone, Camera, Maps", Icons.Default.TouchApp),
        HubTile("marketplace", "Tool Marketplace", "Verified tools & Self-Coding Engine", Icons.Default.Extension),
        HubTile("research", "Research Center", "GitHub, HuggingFace, Web & PDFs", Icons.Default.Science),
        HubTile("api_manager", "API Manager", "Gemini, Groq, OpenRouter & Keystore", Icons.Default.Key),
        HubTile("memory", "Memory & History", "Long-term knowledge & SAF Backups", Icons.Default.History),
        HubTile("build_center", "APK Build Center", "Signed APK check & GitHub Actions", Icons.Default.Build),
        HubTile("notifications", "Notifications", "WhatsApp reader, read aloud & reminders", Icons.Default.Notifications),
        HubTile("triggers", "Triggers & Keyboard", "Alarm/Boot triggers, monitor & Teena IME", Icons.Default.Alarm),
        HubTile("permissions", "Permissions", "Live OS permission & service status", Icons.Default.Security),
        HubTile("identity", "Identity & Persona", "Teena personalities & your user profile", Icons.Default.AutoAwesome),
        HubTile("voice_rooms", "Voice Rooms", "LiveKit token connector & audio rooms", Icons.Default.MeetingRoom),
        HubTile("music_studio", "Music Studio", "Lyria 3 clips (30s) & full tracks", Icons.Default.MusicNote),
        HubTile("website_forge", "Website Forge", "Live HTML5 & WebGL 3D site preview", Icons.Default.Web),
        HubTile("maps", "Interactive Maps", "map.html WebView & optional GPS", Icons.Default.Map),
        HubTile("sketch", "Sketch Pad", "Draw, save & share via SketchFileProvider", Icons.Default.Draw),
        HubTile("settings", "Settings", "Voice, failover, privacy & services", Icons.Default.Settings),
        HubTile("about", "About Teena", "Architecture & honest status report", Icons.Default.Info)
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize().testTag("hub_grid"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tiles) { tile ->
            NeuralCard(
                modifier = Modifier
                    .clickable { onNavigate(tile.route) }
                    .minimumInteractiveComponentSize()
                    .testTag("hub_tile_${tile.route}")
            ) {
                Icon(tile.icon, contentDescription = tile.title, tint = NeuralCyan, modifier = Modifier.size(28.dp))
                Text(tile.title, style = MaterialTheme.typography.titleMedium)
                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MemoryAndHistoryScreen(viewModel: TeenaViewModel) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()

    var keyInput by remember { mutableStateOf("") }
    var valInput by remember { mutableStateOf("") }
    var catInput by remember { mutableStateOf("Knowledge") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackupToUri(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importBackupFromUri(uri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader("Long-Term Knowledge & Backup")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportLauncher.launch("teena_ai_backup.json") }) {
                        Text("Export JSON Backup")
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Text("Restore Backup")
                    }
                }
                OutlinedTextField(value = catInput, onValueChange = { catInput = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, label = { Text("Memory Key") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = valInput, onValueChange = { valInput = it }, label = { Text("Fact / Preference") }, modifier = Modifier.fillMaxWidth())
                FilledTonalButton(
                    onClick = {
                        viewModel.addMemoryFact(catInput, keyInput, valInput)
                        keyInput = ""
                        valInput = ""
                    },
                    enabled = keyInput.isNotBlank() && valInput.isNotBlank()
                ) { Text("Save Memory Fact") }
            }
        }

        item { SectionHeader("Saved Memories (${memories.size})") }
        items(memories, key = { it.id }) { m ->
            NeuralCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("[${m.category}] ${m.factKey}", style = MaterialTheme.typography.titleSmall, color = NeuralCyan)
                        Text(m.factValue, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { viewModel.deleteMemoryFact(m.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Memory")
                    }
                }
            }
        }

        item { SectionHeader("Conversations (${conversations.size}) & Security Audit (${auditLogs.size})") }
        items(auditLogs.take(12), key = { it.id }) { log ->
            NeuralCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("[${log.category}] ${log.action}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    StatusPill(log.outcome)
                }
                Text(log.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun NotificationsAndRemindersScreen(
    viewModel: TeenaViewModel,
    onOpenTriggers: () -> Unit = {}
) {
    val context = LocalContext.current
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val whatsappNotifications by viewModel.whatsappNotifications.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val listenerBound by viewModel.notificationListenerConnected.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isTtsSpeaking.collectAsStateWithLifecycle()

    var remTitle by remember { mutableStateOf("") }
    var remDelayMin by remember { mutableStateOf("15") }
    var remLocation by remember { mutableStateOf("") }
    var replyDraft by remember { mutableStateOf("") }
    var whatsappFilterOnly by remember { mutableStateOf(true) }

    val displayedNotifications = if (whatsappFilterOnly) whatsappNotifications else notifications

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("notifications_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "WhatsApp Reader & Notification Listener",
                    trailing = { StatusPill(if (listenerBound) "ACTIVE" else "PARTIAL") }
                )
                SettingToggleRow(
                    title = "Read WhatsApp Notifications",
                    desc = "User-controlled capture of incoming WhatsApp messages (read aloud only when requested)",
                    checked = settings.whatsappNotifReaderEnabled
                ) { viewModel.toggleWhatsAppNotifReader(it) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.readWhatsAppNotificationsAloud() },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("whatsapp_read_aloud_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Read WhatsApp Aloud")
                    }
                    if (isSpeaking) {
                        OutlinedButton(
                            onClick = { viewModel.stopReadAloud() },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Stop")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onOpenTriggers,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("open_triggers_from_notif_btn")
                        ) {
                            Text("Triggers")
                        }
                    }
                }
                if (!listenerBound) {
                    Text(
                        "Grant Notification Access so Teena can capture WhatsApp alerts & prepare authorized replies.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { TeenaNotificationListenerService.openNotificationListenerSettings(context) }) {
                        Text("Open Notification Access Settings")
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Schedule Reminder / Task")
                OutlinedTextField(value = remTitle, onValueChange = { remTitle = it }, label = { Text("Reminder title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = remDelayMin, onValueChange = { remDelayMin = it }, label = { Text("Trigger after (minutes)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = remLocation, onValueChange = { remLocation = it }, label = { Text("Optional location note") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        viewModel.addReminder(remTitle, "Scheduled via Teena AI", remDelayMin.toLongOrNull() ?: 15L, remLocation)
                        remTitle = ""
                    },
                    enabled = remTitle.isNotBlank()
                ) { Text("Schedule Alarm Reminder") }
            }
        }

        if (reminders.isNotEmpty()) {
            item { SectionHeader("Scheduled Reminders (${reminders.size})") }
            items(reminders, key = { it.id }) { r ->
                NeuralCard {
                    Text(r.title, style = MaterialTheme.typography.titleSmall, color = NeuralMint)
                    Text("${r.notes} ${if (r.locationLabel.isNotBlank()) "@ ${r.locationLabel}" else ""}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionHeader(
                title = "Captured Notifications (${displayedNotifications.size})",
                subtitle = "Notification text is strictly isolated as passive <untrusted_data> (never executed as commands)"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = whatsappFilterOnly,
                    onClick = { whatsappFilterOnly = true },
                    label = { Text("WhatsApp (${whatsappNotifications.size})") }
                )
                FilterChip(
                    selected = !whatsappFilterOnly,
                    onClick = { whatsappFilterOnly = false },
                    label = { Text("All (${notifications.size})") }
                )
                if (notifications.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.clearCapturedNotifications() }) {
                        Text("Clear")
                    }
                }
            }
        }
        if (displayedNotifications.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = { replyDraft = it },
                    label = { Text("Reply text for notifications with RemoteInput") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        items(displayedNotifications, key = { it.id }) { n ->
            NeuralCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${n.appLabel}: ${n.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (n.isImportant) StatusPill("IMPORTANT")
                    IconButton(onClick = { viewModel.readWhatsAppNotificationsAloud(n) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Read notification aloud")
                    }
                }
                Text(n.text, style = MaterialTheme.typography.bodySmall)
                if (n.hasReplyAction && replyDraft.isNotBlank()) {
                    FilledTonalButton(onClick = { viewModel.replyToNotification(n.notificationKey, replyDraft) }) {
                        Text("Authorize Reply")
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsScreen(viewModel: TeenaViewModel) {
    val context = LocalContext.current
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemHealth()
    }

    val requiredRuntimePerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader("Runtime & Special Android Permissions")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runtimePermLauncher.launch(requiredRuntimePerms) }) {
                        Text("Request Core Permissions")
                    }
                    OutlinedButton(onClick = { viewModel.refreshSystemHealth() }) {
                        Text("Refresh")
                    }
                }
            }
        }

        val itemsList = listOf(
            "Microphone (RECORD_AUDIO)" to perms.recordAudio,
            "Camera (CAMERA)" to perms.camera,
            "Location (FINE/COARSE)" to perms.fineLocation,
            "Contacts (READ_CONTACTS)" to perms.readContacts,
            "Place Calls (CALL_PHONE)" to perms.callPhone,
            "Call History (READ_CALL_LOG)" to perms.readCallLog,
            "Answer Calls (ANSWER_PHONE_CALLS)" to perms.answerCalls,
            "Notifications (POST_NOTIFICATIONS)" to perms.postNotifications,
            "Accessibility Service" to perms.accessibilityEnabled,
            "Notification Listener Service" to perms.notificationListenerEnabled,
            "Teena AI Keyboard Enabled" to perms.teenaKeyboardEnabled,
            "Device Admin (Optional, Manual)" to perms.deviceAdminActive,
            "Modify System Settings (Brightness)" to perms.writeSettingsAllowed,
            "Default Phone / InCall Role" to perms.isDefaultDialer
        )

        items(itemsList) { (label, granted) ->
            NeuralCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    StatusPill(if (granted) "ACTIVE" else "BLOCKED")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { TeenaAccessibilityService.openAccessibilitySettings(context) }, modifier = Modifier.weight(1f)) {
                    Text("Accessibility")
                }
                FilledTonalButton(onClick = { TeenaNotificationListenerService.openNotificationListenerSettings(context) }, modifier = Modifier.weight(1f)) {
                    Text("Notif Listener")
                }
                FilledTonalButton(onClick = { com.example.system.TeenaKeyboardService.openInputMethodSettings(context) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Keyboard")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: TeenaViewModel,
    onOpenIdentity: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val identityProfile = settings.toIdentityProfile()
    val activePreset = TeenaIdentityEngine.presetFor(settings.personalityId)

    val lastRunFormatted = remember(settings.lastSelfImproveRunAt) {
        if (settings.lastSelfImproveRunAt <= 0L) "Never run yet"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(settings.lastSelfImproveRunAt))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Teena Personality & User Identity",
                    subtitle = TeenaIdentityEngine.buildPersonalizedGreeting(identityProfile),
                    trailing = { StatusPill(activePreset.title) }
                )
                PersonalityPresetStrip(
                    selectedPersonalityId = settings.personalityId,
                    onSelect = { viewModel.selectPersonality(it) }
                )
                OutlinedButton(
                    onClick = onOpenIdentity,
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("settings_open_identity_btn")
                ) {
                    Text("Edit Name, Addressing & Custom Tone")
                }
            }
        }

        item {
            var roomsUrl by remember(settings.voiceRoomsServerUrl) { mutableStateOf(settings.voiceRoomsServerUrl) }
            var tokenUrl by remember(settings.liveKitTokenEndpoint) { mutableStateOf(settings.liveKitTokenEndpoint) }
            var wsUrl by remember(settings.liveKitWsUrl) { mutableStateOf(settings.liveKitWsUrl) }
            val firebaseState by viewModel.firebaseAuthState.collectAsStateWithLifecycle()
            val configured = com.example.ai.VoiceRoomConnectorEngine.isValidHttpEndpoint(settings.voiceRoomsServerUrl) ||
                com.example.ai.VoiceRoomConnectorEngine.isValidHttpEndpoint(settings.liveKitTokenEndpoint) ||
                com.example.ai.VoiceRoomConnectorEngine.isValidWsEndpoint(settings.liveKitWsUrl)
            NeuralCard {
                SectionHeader(
                    title = "Voice/Video Rooms & LiveKit Endpoints",
                    subtitle = "Optional external endpoints & Firebase Auth/FCM (app runs normally when disabled)",
                    trailing = {
                        StatusPill(
                            if (configured) "READY" else "DISABLED",
                            modifier = Modifier.testTag("settings_voice_rooms_status_pill")
                        )
                    }
                )
                OutlinedTextField(
                    value = roomsUrl,
                    onValueChange = { roomsUrl = it },
                    label = { Text("Rooms Server URL (https://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_rooms_url_input")
                )
                OutlinedTextField(
                    value = tokenUrl,
                    onValueChange = { tokenUrl = it },
                    label = { Text("LiveKit Token Endpoint (https://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_livekit_token_input")
                )
                OutlinedTextField(
                    value = wsUrl,
                    onValueChange = { wsUrl = it },
                    label = { Text("LiveKit WebSocket URL (wss://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_livekit_ws_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.saveVoiceRoomEndpoints(
                                roomsUrl = roomsUrl,
                                tokenEndpoint = tokenUrl,
                                wsUrl = wsUrl,
                                participantName = settings.voiceRoomParticipantName
                            )
                        },
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("settings_save_voice_rooms_btn")
                    ) {
                        Text("Save Endpoints")
                    }
                    OutlinedButton(
                        onClick = {
                            roomsUrl = ""
                            tokenUrl = ""
                            wsUrl = ""
                            viewModel.clearVoiceRoomEndpoints()
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("settings_clear_voice_rooms_btn")
                    ) {
                        Text("Disable / Clear")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Optional Firebase Auth & FCM", style = MaterialTheme.typography.titleSmall)
                        Text(
                            firebaseState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        firebaseState.statusLabel,
                        modifier = Modifier.testTag("settings_firebase_status_pill")
                    )
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader(
                    title = "Emergency Stop & Prompt-Injection Isolation",
                    subtitle = "Screen UI, web pages, notifications & advisor replies are wrapped strictly as <untrusted_data> (never commands)",
                    trailing = { StatusPill(if (emergencyStopped) "EMERGENCY_STOP" else "READY") }
                )
                Button(
                    onClick = {
                        if (emergencyStopped) viewModel.resumeFromEmergencyStop()
                        else viewModel.triggerEmergencyStop()
                    },
                    colors = if (emergencyStopped) {
                        ButtonDefaults.buttonColors(containerColor = NeuralMint)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("settings_emergency_stop_btn")
                ) {
                    Icon(
                        if (emergencyStopped) Icons.Default.PlayArrow else Icons.Default.StopCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (emergencyStopped) "Resume Operations" else "EMERGENCY STOP (Halt All Automation & Calls)")
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("AI, Privacy & Auto Mode Controls")
                SettingToggleRow("Dark Neural Theme", "Obsidian & Cyan command interface", settings.darkTheme) { v ->
                    viewModel.updateSettings { it.copy(darkTheme = v) }
                }
                SettingToggleRow("Automatic Provider Failover", "Switch to next healthy provider if primary fails", settings.autoFailoverProviders) { v ->
                    viewModel.updateSettings { it.copy(autoFailoverProviders = v) }
                }
                SettingToggleRow(
                    "External AI Calls (Advisors / MCP / A2A)",
                    "Allow asking enabled external AI advisors sanitized technical questions (no keys or personal data)",
                    settings.externalAiCallsEnabled
                ) { v ->
                    viewModel.updateSettings { it.copy(externalAiCallsEnabled = v) }
                }
                Text("Advisor Hourly Rate Limit", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24, 48).forEach { lim ->
                        FilterChip(
                            selected = settings.advisorHourlyRateLimit == lim,
                            onClick = { viewModel.updateSettings { it.copy(advisorHourlyRateLimit = lim) } },
                            label = { Text("$lim/hr", maxLines = 1, softWrap = false) }
                        )
                    }
                }
                SettingToggleRow(
                    "Auto Mode (Safe Declarative Tools)",
                    "Auto-activate ONLY zero-permission, zero-network, non-sensitive declarative tools after tests & scan pass (Undo + Notification included)",
                    settings.autoActivateSafeDeclarativeTools
                ) { v ->
                    viewModel.updateSettings { it.copy(autoActivateSafeDeclarativeTools = v) }
                }
                SettingToggleRow("Confirm Sensitive Actions", "Require authorization before calls, social posts & tool installs", settings.confirmSensitiveActions) { v ->
                    viewModel.updateSettings { it.copy(confirmSensitiveActions = v) }
                }
                SettingToggleRow(
                    "Direct Accessibility & Social Agent",
                    "Enable Accessibility tools (read/tap/type/scroll/back/home) and WhatsApp/Social task state machine",
                    settings.directAccessibilityAgentEnabled
                ) { v ->
                    viewModel.updateSettings { it.copy(directAccessibilityAgentEnabled = v) }
                }
                SettingToggleRow(
                    "WhatsApp Notification Reader",
                    "Allow NotificationListenerService to capture incoming WhatsApp notifications (read aloud only on request)",
                    settings.whatsappNotifReaderEnabled
                ) { v ->
                    viewModel.toggleWhatsAppNotifReader(v)
                }
                SettingToggleRow(
                    "Trigger Monitor Service",
                    "Run background alarm & boot trigger monitor service",
                    settings.triggerMonitorEnabled
                ) { v ->
                    viewModel.toggleTriggerMonitor(v)
                }
                SettingToggleRow(
                    "Device Admin Receiver (Direct, Optional)",
                    "Allow optional screen lock when user manually enables Teena Device Admin in Android Settings",
                    settings.directDeviceAdminEnabled
                ) { v ->
                    viewModel.toggleDirectDeviceAdmin(v)
                }
                SettingToggleRow("Save Conversation Memory", "Persist long-term preferences & context locally", settings.saveConversationMemory) { v ->
                    viewModel.updateSettings { it.copy(saveConversationMemory = v) }
                }
                SettingToggleRow("Foreground Engine Service", "Run persistent Android foreground service within OS limits", settings.backgroundServiceEnabled) { v ->
                    viewModel.toggleForegroundService(v)
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader(
                    title = "Scheduled Self-Improvement (WorkManager)",
                    subtitle = "Researches Gemini models, GitHub/HF tools & creates PROPOSED Marketplace items (never auto-installed except safe zero-perm Auto Mode)",
                    trailing = { StatusPill(if (settings.autoSelfImproveEnabled) "ACTIVE" else "PARTIAL") }
                )
                SettingToggleRow(
                    "Periodic Research Schedule",
                    "Runs automatically in background on connected network",
                    settings.autoSelfImproveEnabled
                ) { v ->
                    viewModel.updateSettings { it.copy(autoSelfImproveEnabled = v) }
                }
                Text("Research Interval", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24).forEach { hrs ->
                        FilterChip(
                            selected = settings.selfImproveIntervalHours == hrs,
                            onClick = { viewModel.updateSettings { it.copy(selfImproveIntervalHours = hrs) } },
                            label = { Text("Every ${hrs}h", maxLines = 1, softWrap = false) }
                        )
                    }
                }
                Text(
                    "Last research cycle: $lastRunFormatted",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuralCyan
                )
                FilledTonalButton(
                    onClick = { viewModel.runSelfImprovementResearchNow() },
                    enabled = !isBusy && !emergencyStopped,
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("settings_run_research_btn")
                ) {
                    Text("Run Research Cycle Now", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AboutTeenaScreen(viewModel: TeenaViewModel) {
    val hw by viewModel.hardwareProfile.collectAsStateWithLifecycle()
    val apk by viewModel.apkInspection.collectAsStateWithLifecycle()
    val health by viewModel.featureHealth.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader("TEENA AI • Autonomous Multi-Agent Assistant")
                Text("Package: ${apk?.packageName ?: BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                Text("Version: ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                Text("Hardware: ${"%.1f".format(hw.totalRamGb)} GB RAM (${"%.1f".format(hw.availableRamGb)} GB free), ${hw.cpuCores} CPU cores, AICore=${hw.hasAiCore}", style = MaterialTheme.typography.bodySmall)
                Text(hw.recommendation, style = MaterialTheme.typography.bodySmall, color = NeuralMint)
            }
        }
        item { SectionHeader("Honest Capability Audit") }
        items(health, key = { it.id }) { h ->
            NeuralCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(h.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    StatusPill(h.status.name)
                }
                Text(h.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
