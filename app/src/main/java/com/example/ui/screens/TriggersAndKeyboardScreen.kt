package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.system.TeenaKeyboardService
import com.example.system.TeenaTriggerLogic
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriggersScreen(viewModel: TeenaViewModel) {
    val context = LocalContext.current
    val triggers by viewModel.triggers.collectAsStateWithLifecycle()
    val monitorRunning by viewModel.triggerMonitorRunning.collectAsStateWithLifecycle()
    val monitorStatus by viewModel.triggerMonitorStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()

    var editingTriggerId by remember { mutableStateOf<Long?>(null) }
    var titleInput by remember { mutableStateOf("") }
    var triggerType by remember { mutableStateOf(TeenaTriggerLogic.TYPE_ONE_TIME) }
    var actionType by remember { mutableStateOf(TeenaTriggerLogic.ACTION_NOTIFY) }
    var payloadInput by remember { mutableStateOf("") }
    var delayInput by remember { mutableStateOf("15") }
    var imeTestDraft by remember { mutableStateOf("") }

    val timeFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("triggers_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Alarm & Boot Trigger Monitor",
                    subtitle = "Boot receiver auto-reschedules enabled alarm triggers after device reboot.",
                    trailing = { StatusPill(if (monitorRunning) "ACTIVE" else "READY") }
                )
                Text(
                    text = monitorStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuralCyan
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Trigger Monitor Foreground Service", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Keeps alarm trigger schedule synced cleanly in background",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.triggerMonitorEnabled,
                        onCheckedChange = { viewModel.toggleTriggerMonitor(it) },
                        modifier = Modifier.testTag("trigger_monitor_switch")
                    )
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader(
                    title = if (editingTriggerId == null) "Create Scheduled / Boot Trigger" else "Edit Trigger #$editingTriggerId",
                    trailing = {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = NeuralMint)
                    }
                )
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Trigger title (e.g. Morning Brief)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trigger_title_input")
                )

                Text("Schedule Type", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        TeenaTriggerLogic.TYPE_ONE_TIME to "One-Time",
                        TeenaTriggerLogic.TYPE_INTERVAL to "Interval",
                        TeenaTriggerLogic.TYPE_DAILY to "Daily",
                        TeenaTriggerLogic.TYPE_BOOT to "On Boot"
                    ).forEach { (typeKey, label) ->
                        FilterChip(
                            selected = triggerType == typeKey,
                            onClick = { triggerType = typeKey },
                            label = { Text(label, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("trigger_type_${typeKey.lowercase()}")
                        )
                    }
                }

                Text("Trigger Action", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        TeenaTriggerLogic.ACTION_NOTIFY to "Alert Notification",
                        TeenaTriggerLogic.ACTION_SPEAK_ALERT to "Speak Alert",
                        TeenaTriggerLogic.ACTION_VOICE_PROMPT to "Voice Prompt",
                        TeenaTriggerLogic.ACTION_AI_CHECK to "AI Check"
                    ).forEach { (actKey, label) ->
                        FilterChip(
                            selected = actionType == actKey,
                            onClick = { actionType = actKey },
                            label = { Text(label, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                if (triggerType != TeenaTriggerLogic.TYPE_BOOT) {
                    OutlinedTextField(
                        value = delayInput,
                        onValueChange = { delayInput = it.filter { ch -> ch.isDigit() } },
                        label = {
                            Text(
                                if (triggerType == TeenaTriggerLogic.TYPE_INTERVAL) {
                                    "Repeat every (minutes)"
                                } else {
                                    "Trigger after (minutes)"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("trigger_delay_input")
                    )
                }

                OutlinedTextField(
                    value = payloadInput,
                    onValueChange = { payloadInput = it },
                    label = { Text("Action message / prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trigger_payload_input")
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveTrigger(
                                id = editingTriggerId,
                                title = titleInput,
                                triggerType = triggerType,
                                actionType = actionType,
                                payload = payloadInput,
                                delayOrIntervalMinutes = delayInput.toIntOrNull() ?: 15
                            )
                            editingTriggerId = null
                            titleInput = ""
                            payloadInput = ""
                            delayInput = "15"
                        },
                        enabled = titleInput.isNotBlank(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("trigger_save_btn")
                    ) {
                        Text(if (editingTriggerId == null) "Save Trigger" else "Update Trigger")
                    }

                    if (editingTriggerId != null) {
                        OutlinedButton(
                            onClick = {
                                editingTriggerId = null
                                titleInput = ""
                                payloadInput = ""
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Cancel Edit")
                        }
                    }
                }
            }
        }

        item { SectionHeader("Configured Triggers (${triggers.size})") }

        if (triggers.isEmpty()) {
            item {
                NeuralCard {
                    Text(
                        "Abhi koi custom trigger save nahi hua hai. Upar se alarm ya boot trigger banayein.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(triggers, key = { it.id }) { trig ->
                NeuralCard(modifier = Modifier.testTag("trigger_card_${trig.id}")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(trig.title, style = MaterialTheme.typography.titleSmall, color = NeuralMint)
                            Text(
                                "${trig.triggerType} • ${trig.actionType} • ${
                                    if (trig.triggerType == TeenaTriggerLogic.TYPE_BOOT) "Runs on Reboot"
                                    else "Next: ${timeFmt.format(Date(trig.triggerAtMillis))}"
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeuralCyan
                            )
                        }
                        Switch(
                            checked = trig.enabled,
                            onCheckedChange = { viewModel.toggleTriggerEnabled(trig, it) },
                            modifier = Modifier.testTag("trigger_toggle_${trig.id}")
                        )
                    }
                    if (trig.actionPayload.isNotBlank()) {
                        Text(trig.actionPayload, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Status: ${trig.lastResult}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { viewModel.fireTriggerNow(trig) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("trigger_fire_${trig.id}")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Test Fire Trigger", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Test Fire")
                        }
                        OutlinedButton(
                            onClick = {
                                editingTriggerId = trig.id
                                titleInput = trig.title
                                triggerType = trig.triggerType
                                actionType = trig.actionType
                                payloadInput = trig.actionPayload
                                delayInput = trig.intervalMinutes.coerceAtLeast(1).toString()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("trigger_edit_${trig.id}")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Trigger", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Edit")
                        }
                        IconButton(
                            onClick = { viewModel.deleteTrigger(trig) },
                            modifier = Modifier.testTag("trigger_delete_${trig.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Trigger")
                        }
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader(
                    title = "Teena AI Keyboard (InputMethodService)",
                    subtitle = "Built-in soft keyboard with Mic dictation & Teena AI smart compose button.",
                    trailing = {
                        StatusPill(
                            when {
                                perms.teenaKeyboardSelected -> "ACTIVE"
                                perms.teenaKeyboardEnabled -> "READY"
                                else -> "PARTIAL"
                            }
                        )
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { TeenaKeyboardService.openInputMethodSettings(context) },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("keyboard_enable_settings_btn")
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Enable in Settings")
                    }
                    FilledTonalButton(
                        onClick = {
                            TeenaKeyboardService.showInputMethodPicker(context)
                            viewModel.refreshSystemHealth()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("keyboard_picker_btn")
                    ) {
                        Text("Switch Keyboard")
                    }
                }
                OutlinedTextField(
                    value = imeTestDraft,
                    onValueChange = { imeTestDraft = it },
                    label = { Text("Tap here to test Teena AI Keyboard (Mic & AI bar)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("keyboard_test_input")
                )
            }
        }
    }
}
