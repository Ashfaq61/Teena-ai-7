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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.example.system.TeenaAccessibilityService
import com.example.system.TeenaNotificationListenerService
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

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
        HubTile("notifications", "Notifications", "Listener feed & Scheduled Reminders", Icons.Default.Notifications),
        HubTile("permissions", "Permissions", "Live OS permission & service status", Icons.Default.Security),
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
fun NotificationsAndRemindersScreen(viewModel: TeenaViewModel) {
    val context = LocalContext.current
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val listenerBound by viewModel.notificationListenerConnected.collectAsStateWithLifecycle()

    var remTitle by remember { mutableStateOf("") }
    var remDelayMin by remember { mutableStateOf("15") }
    var remLocation by remember { mutableStateOf("") }
    var replyDraft by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Notification Listener & Replies",
                    trailing = { StatusPill(if (listenerBound) "ACTIVE" else "PARTIAL") }
                )
                if (!listenerBound) {
                    Text("Grant Notification Access so Teena can detect important alerts and prepare authorized replies.", style = MaterialTheme.typography.bodySmall)
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

        item { SectionHeader("Captured Notifications (${notifications.size})") }
        if (notifications.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = { replyDraft = it },
                    label = { Text("Reply text for notifications with RemoteInput") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        items(notifications, key = { it.id }) { n ->
            NeuralCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${n.appLabel}: ${n.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (n.isImportant) StatusPill("IMPORTANT")
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
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: TeenaViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader("AI, Privacy & Background Controls")
                SettingToggleRow("Dark Neural Theme", "Obsidian & Cyan command interface", settings.darkTheme) { v ->
                    viewModel.updateSettings { it.copy(darkTheme = v) }
                }
                SettingToggleRow("Automatic Provider Failover", "Switch to next healthy provider if primary fails", settings.autoFailoverProviders) { v ->
                    viewModel.updateSettings { it.copy(autoFailoverProviders = v) }
                }
                SettingToggleRow("Confirm Sensitive Actions", "Require authorization before calls, social posts & tool installs", settings.confirmSensitiveActions) { v ->
                    viewModel.updateSettings { it.copy(confirmSensitiveActions = v) }
                }
                SettingToggleRow("Save Conversation Memory", "Persist long-term preferences & context locally", settings.saveConversationMemory) { v ->
                    viewModel.updateSettings { it.copy(saveConversationMemory = v) }
                }
                SettingToggleRow("Foreground Engine Service", "Run persistent Android foreground service within OS limits", settings.backgroundServiceEnabled) { v ->
                    viewModel.toggleForegroundService(v)
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
