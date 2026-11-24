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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.example.ai.ToolCreationPipelineEngine
import com.example.data.local.FeatureStatus
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketplaceAndSelfImproveScreen(viewModel: TeenaViewModel) {
    val tools by viewModel.marketplaceTools.collectAsStateWithLifecycle()
    val logs by viewModel.improvementLogs.collectAsStateWithLifecycle()
    val lastToolOutput by viewModel.lastToolExecutionOutput.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var tabIndex by remember { mutableIntStateOf(0) }
    var capabilityGoal by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf("DECLARATIVE_JSON") }
    var runtimeInput by remember { mutableStateOf("kotlin android") }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Marketplace (${tools.size})") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("11-Stage Pipeline") })
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (tabIndex == 0) {
                item {
                    SectionHeader(
                        title = "Tool & Proposal Marketplace",
                        subtitle = "All tools track Proposer, Version, License, Scan, Test, Pipeline Stage, Status & History"
                    )
                    OutlinedTextField(
                        value = runtimeInput,
                        onValueChange = { runtimeInput = it },
                        label = { Text("Runtime test/verify input (for declarative tools)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (lastToolOutput.isNotBlank()) {
                        NeuralCard(borderColor = NeuralMint.copy(alpha = 0.5f)) {
                            Text("Latest Runtime Verification Output:", style = MaterialTheme.typography.labelMedium, color = NeuralMint)
                            Text(lastToolOutput, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                items(tools, key = { it.toolId }) { tool ->
                    val parsedStages = remember(tool.pipelineStagesJson) {
                        ToolCreationPipelineEngine.parseStages(tool.pipelineStagesJson)
                    }
                    var showHistory by remember(tool.toolId) { mutableStateOf(false) }

                    NeuralCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${tool.name} v${tool.version}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${tool.category} • ${tool.toolKind} • License: ${tool.licenseSpdx}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeuralCyan
                                )
                            }
                            StatusPill(tool.activationStatus)
                        }

                        Text("Proposed By: ${tool.proposedBy}", style = MaterialTheme.typography.labelMedium, color = NeuralMint)
                        Text(tool.description, style = MaterialTheme.typography.bodySmall)
                        Text("Security Scan: ${tool.scanResult} (${tool.securityScore}/100)", style = MaterialTheme.typography.labelSmall)
                        Text("Test Result: ${tool.testResult}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tool.branchName.isNotBlank()) {
                            Text("GitHub Branch: ${tool.branchName}", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                        }
                        if (tool.releaseUrl.isNotBlank()) {
                            Text("Release / CI: ${tool.releaseUrl}", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                        }

                        if (!tool.isBuiltIn && tool.pipelineStagesJson.isNotBlank() && tool.pipelineStagesJson != "[]") {
                            Text("11-Stage Pipeline:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                parsedStages.forEach { st ->
                                    val c = when (st.status) {
                                        "PASSED" -> NeuralMint
                                        "WAITING_APPROVAL" -> StatusAmber
                                        "BLOCKED", "FAILED" -> StatusCrimson
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = c.copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            text = "${st.stage.take(7)}:${st.status.take(4)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (tool.activationStatus) {
                                "PROPOSED" -> {
                                    Button(
                                        onClick = { viewModel.runSelfCodingToolCycle(tool.name, tool.toolKind, tool.toolId) },
                                        enabled = !isBusy
                                    ) { Text("Run 11-Stage Pipeline") }
                                }
                                "WAITING_APPROVAL" -> {
                                    Button(
                                        onClick = { viewModel.requestApproveAndInstallTool(tool) },
                                        enabled = !isBusy
                                    ) { Text("Approve & Install") }
                                }
                                FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name, FeatureStatus.ACTIVE.name -> {
                                    if (tool.toolKind != "BUILT_IN") {
                                        Button(
                                            onClick = { viewModel.verifyAndRunTool(tool, runtimeInput) },
                                            enabled = !isBusy
                                        ) { Text("Execute & Verify") }
                                        OutlinedButton(onClick = { viewModel.rollbackMarketplaceTool(tool) }) {
                                            Text("Rollback")
                                        }
                                    }
                                }
                                else -> {
                                    if (!tool.isBuiltIn) {
                                        OutlinedButton(
                                            onClick = { viewModel.runSelfCodingToolCycle(tool.name, tool.toolKind, tool.toolId) },
                                            enabled = !isBusy
                                        ) { Text("Retry Pipeline") }
                                    }
                                }
                            }
                            if (tool.historyLog.isNotBlank()) {
                                FilledTonalButton(onClick = { showHistory = !showHistory }) {
                                    Text(if (showHistory) "Hide History" else "History")
                                }
                            }
                        }

                        if (showHistory && tool.historyLog.isNotBlank()) {
                            Text(
                                text = tool.historyLog,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    NeuralCard {
                        SectionHeader(
                            title = "11-Stage Tool Creation Pipeline",
                            subtitle = "DISCOVER → ASK ADVISORS → DESIGN → CODE → BUILD → TEST → SECURITY → USER APPROVAL → INSTALL → VERIFY → ACTIVATE"
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedKind == "DECLARATIVE_JSON",
                                onClick = { selectedKind = "DECLARATIVE_JSON" },
                                label = { Text("Safe Declarative JSON") }
                            )
                            FilterChip(
                                selected = selectedKind == "KOTLIN_GITHUB_BRANCH",
                                onClick = { selectedKind = "KOTLIN_GITHUB_BRANCH" },
                                label = { Text("Kotlin Branch + CI APK") }
                            )
                        }
                        OutlinedTextField(
                            value = capabilityGoal,
                            onValueChange = { capabilityGoal = it },
                            label = { Text("Describe capability or tool to build") },
                            modifier = Modifier.fillMaxWidth().testTag("self_code_goal_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.runSelfCodingToolCycle(capabilityGoal, selectedKind)
                                    capabilityGoal = ""
                                },
                                enabled = capabilityGoal.isNotBlank() && !isBusy,
                                modifier = Modifier.weight(1f).testTag("self_code_run_btn")
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text("Build Tool (Stages 1–7)")
                            }
                        }
                    }
                }

                item {
                    NeuralCard {
                        SectionHeader(
                            title = "Scheduled Self-Improvement Research",
                            subtitle = "WorkManager auto-schedule: ${if (settings.autoSelfImproveEnabled) "ON (every ${settings.selfImproveIntervalHours}h)" else "OFF (configure in Settings)"}"
                        )
                        Button(
                            onClick = { viewModel.runSelfImprovementResearchNow() },
                            enabled = !isBusy,
                            modifier = Modifier.testTag("run_research_cycle_btn")
                        ) {
                            Text("Run Research Cycle Now (Propose Tools)")
                        }
                    }
                }

                items(logs, key = { it.id }) { item ->
                    NeuralCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.cycleTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            StatusPill(if (item.verified) "VERIFIED" else if (item.rolledBack) "ROLLED_BACK" else "LOGGED")
                        }
                        Text("Stage: ${item.stageReached} • ${item.actionTaken}", style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                        Text(item.findings, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
