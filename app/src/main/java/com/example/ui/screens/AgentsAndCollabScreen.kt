package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentsAndCollabScreen(viewModel: TeenaViewModel) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val collabs by viewModel.collaborations.collectAsStateWithLifecycle()
    val advisors by viewModel.externalAdvisors.collectAsStateWithLifecycle()
    val externalMessages by viewModel.externalCollabMessages.collectAsStateWithLifecycle()
    val gaps by viewModel.capabilityGaps.collectAsStateWithLifecycle()
    val comparisonReport by viewModel.advisorComparisonReport.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val report by viewModel.diagnosticReport.collectAsStateWithLifecycle()

    var tabIndex by remember { mutableIntStateOf(0) }
    var missionInput by remember { mutableStateOf("") }

    var advisorQuestionTopic by remember { mutableStateOf("") }
    var advisorQuestionBody by remember { mutableStateOf("") }

    var customAdvisorName by remember { mutableStateOf("") }
    var customAdvisorType by remember { mutableStateOf("MCP_SERVER") }
    var customAdvisorUrl by remember { mutableStateOf("") }
    var customAdvisorToken by remember { mutableStateOf("") }

    val timeFmt = remember { SimpleDateFormat("MMM dd HH:mm:ss", Locale.US) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Global External AI Calls Privacy Switch Bar
        NeuralCard(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            borderColor = if (settings.externalAiCallsEnabled) NeuralMint.copy(alpha = 0.45f) else StatusAmber.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("External AI Calls (Advisors / MCP / A2A)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Privacy lock: Never sends API keys, memory, contacts, or screen text — technical questions only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.externalAiCallsEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings { it.copy(externalAiCallsEnabled = enabled) }
                    },
                    modifier = Modifier.testTag("external_ai_calls_switch")
                )
            }
        }

        ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 8.dp) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("17 Agents") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("External Advisors (${advisors.size})") })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Advisor Collab (${externalMessages.size})") })
            Tab(selected = tabIndex == 3, onClick = { tabIndex = 3 }, text = { Text("Internal Logs") })
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tabIndex) {
                0 -> {
                    item {
                        NeuralCard {
                            SectionHeader(
                                title = "Autonomous Multi-Agent Mission",
                                subtitle = "Dispatches real API tasks across Research, GitHub, Security & Main Agents"
                            )
                            OutlinedTextField(
                                value = missionInput,
                                onValueChange = { missionInput = it },
                                label = { Text("Enter mission goal (e.g. 'Android OCR libraries')") },
                                modifier = Modifier.fillMaxWidth().testTag("agent_mission_input")
                            )
                            Button(
                                onClick = {
                                    viewModel.runMultiAgentMission(missionInput)
                                    missionInput = ""
                                },
                                enabled = missionInput.isNotBlank() && !isBusy,
                                modifier = Modifier.testTag("agent_run_mission_btn")
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text("Execute Multi-Agent Mission")
                            }
                            if (report.isNotBlank()) {
                                Text(report, style = MaterialTheme.typography.bodySmall, color = NeuralMint)
                            }
                        }
                    }

                    items(agents, key = { it.agentId }) { agent ->
                        NeuralCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(agent.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(agent.specialty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusPill(agent.healthStatus)
                            }
                            Text(
                                "Status: ${agent.status} • Task: ${agent.currentTask}",
                                style = MaterialTheme.typography.labelMedium,
                                color = NeuralCyan
                            )
                            Text(
                                "Completed: ${agent.tasksCompleted} | Failed: ${agent.tasksFailed} | ${agent.healthReason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                1 -> {
                    item {
                        NeuralCard {
                            SectionHeader(
                                title = "Add MCP Server or A2A Agent",
                                subtitle = "Connect external AI advisors via MCP JSON-RPC 2.0 or A2A (/.well-known/agent.json)"
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = customAdvisorType == "MCP_SERVER",
                                    onClick = { customAdvisorType = "MCP_SERVER" },
                                    label = { Text("MCP Server") }
                                )
                                FilterChip(
                                    selected = customAdvisorType == "A2A_AGENT",
                                    onClick = { customAdvisorType = "A2A_AGENT" },
                                    label = { Text("A2A Agent") }
                                )
                            }
                            OutlinedTextField(
                                value = customAdvisorName,
                                onValueChange = { customAdvisorName = it },
                                label = { Text("Advisor Name (e.g. 'Kotlin MCP Advisor')") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = customAdvisorUrl,
                                onValueChange = { customAdvisorUrl = it },
                                label = { Text("HTTPS Server URL") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = customAdvisorToken,
                                onValueChange = { customAdvisorToken = it },
                                label = { Text("Optional Bearer Token (stored in Keystore)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    viewModel.addMcpOrA2aAdvisor(customAdvisorName, customAdvisorType, customAdvisorUrl, customAdvisorToken)
                                    customAdvisorName = ""
                                    customAdvisorUrl = ""
                                    customAdvisorToken = ""
                                },
                                enabled = customAdvisorUrl.isNotBlank() && !isBusy,
                                modifier = Modifier.testTag("add_external_advisor_btn")
                            ) {
                                Text("Add & Probe ${if (customAdvisorType == "MCP_SERVER") "MCP" else "A2A"} Advisor")
                            }
                        }
                    }

                    item {
                        SectionHeader(
                            title = "Live External AI Advisors",
                            subtitle = "Provider models from ModelRegistry + configured MCP/A2A endpoints"
                        )
                    }

                    items(advisors, key = { it.advisorId }) { adv ->
                        NeuralCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(adv.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${adv.sourceType} • Model: ${adv.modelId.ifBlank { "auto/rpc" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeuralCyan
                                    )
                                }
                                StatusPill(adv.status)
                            }
                            Text(
                                "Last Response Time: ${if (adv.lastResponseTimeMs > 0) "${adv.lastResponseTimeMs} ms" else "Not measured"} • ${adv.lastStatusMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (adv.endpointUrl.isNotBlank()) {
                                Text("Endpoint: ${adv.endpointUrl}", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { viewModel.probeExternalAdvisor(adv.advisorId) },
                                        enabled = !isBusy
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.size(4.dp))
                                        Text("Probe")
                                    }
                                    if (adv.sourceType != "PROVIDER_MODEL") {
                                        IconButton(onClick = { viewModel.deleteExternalAdvisor(adv.advisorId) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove Advisor")
                                        }
                                    }
                                }
                                Switch(
                                    checked = adv.isEnabled,
                                    onCheckedChange = { viewModel.toggleExternalAdvisor(adv.advisorId, it) }
                                )
                            }
                        }
                    }
                }

                2 -> {
                    item {
                        NeuralCard {
                            SectionHeader(
                                title = "Detect Gaps & Ask >=2 External Advisors",
                                subtitle = "Teena asks technical questions (tool, library, design), compares >=2 real API replies & stores in Room",
                                trailing = {
                                    OutlinedButton(onClick = { viewModel.detectAndRefreshGaps() }) {
                                        Text("Scan Gaps")
                                    }
                                }
                            )
                            if (gaps.isNotEmpty()) {
                                Text("Detected Capability Gaps (${gaps.size}):", style = MaterialTheme.typography.labelMedium, color = StatusAmber)
                                gaps.take(3).forEach { gap ->
                                    NeuralCard(borderColor = StatusAmber.copy(alpha = 0.45f)) {
                                        Text(gap.title, style = MaterialTheme.typography.titleSmall)
                                        Text(gap.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.consultExternalAdvisors(
                                                    topic = gap.title,
                                                    question = gap.suggestedTechnicalQuestion,
                                                    gapReason = gap.reason
                                                )
                                            },
                                            enabled = !isBusy && settings.externalAiCallsEnabled
                                        ) {
                                            Text("Ask >=2 Advisors About This Gap")
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = advisorQuestionTopic,
                                onValueChange = { advisorQuestionTopic = it },
                                label = { Text("Topic (e.g. 'PDF Table Extraction Tool')") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = advisorQuestionBody,
                                onValueChange = { advisorQuestionBody = it },
                                label = { Text("Technical question (keys/PII stripped automatically)") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth().testTag("advisor_question_input")
                            )
                            Button(
                                onClick = {
                                    viewModel.consultExternalAdvisors(advisorQuestionTopic, advisorQuestionBody)
                                    advisorQuestionBody = ""
                                },
                                enabled = advisorQuestionBody.isNotBlank() && !isBusy && settings.externalAiCallsEnabled,
                                modifier = Modifier.testTag("consult_advisors_btn")
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text("Ask >=2 Advisors & Compare")
                            }
                            if (!settings.externalAiCallsEnabled) {
                                Text(
                                    "External AI Calls switch is OFF above. Enable it to query external advisors.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusCrimson
                                )
                            }
                            if (comparisonReport.isNotBlank()) {
                                Text(comparisonReport, style = MaterialTheme.typography.bodySmall, color = NeuralMint)
                            }
                        }
                    }

                    item {
                        SectionHeader(
                            title = "Saved Advisor Collaborations (${externalMessages.size})",
                            subtitle = "All replies treated strictly as Suggestions Only (never executed as commands)"
                        )
                    }

                    if (externalMessages.isEmpty()) {
                        item {
                            NeuralCard {
                                Text(
                                    "No external advisor collaborations saved yet. Scan capability gaps or ask a technical question above.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    } else {
                        items(externalMessages, key = { it.id }) { msg ->
                            NeuralCard(
                                borderColor = if (msg.succeeded) NeuralCyan.copy(alpha = 0.45f) else StatusCrimson.copy(alpha = 0.45f)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${msg.advisorName} (${msg.advisorModel})", style = MaterialTheme.typography.titleSmall, color = NeuralCyan)
                                        Text(
                                            "${msg.protocolUsed} • ${msg.responseTimeMs}ms • ${timeFmt.format(Date(msg.createdAt))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusPill(if (msg.succeeded) "SUGGESTION" else "FAILED")
                                }
                                Text("Topic: ${msg.topic}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("Question: ${msg.questionSent}", style = MaterialTheme.typography.bodySmall)
                                if (msg.succeeded) {
                                    Text("Advisor Reply (Data/Suggestion Only):", style = MaterialTheme.typography.labelSmall, color = NeuralMint)
                                    Text(msg.rawResponse, style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Error: ${msg.errorMessage}", style = MaterialTheme.typography.bodySmall, color = StatusCrimson)
                                }
                                if (msg.comparisonNotes.isNotBlank()) {
                                    Text("Comparison: ${msg.comparisonNotes}", style = MaterialTheme.typography.labelSmall, color = StatusAmber)
                                }
                            }
                        }
                    }
                }

                else -> {
                    if (collabs.isEmpty()) {
                        item {
                            NeuralCard {
                                Text("No internal inter-agent protocol messages recorded yet.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        items(collabs, key = { it.id }) { log ->
                            NeuralCard {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${log.fromAgentId} → ${log.toAgentId}", style = MaterialTheme.typography.labelLarge, color = NeuralCyan)
                                    StatusPill(log.messageType)
                                }
                                Text(log.protocolPayload, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
