package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.CapabilitySummaryAnalyzer
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
        ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 8.dp) {
            Tab(
                selected = tabIndex == 0,
                onClick = { tabIndex = 0 },
                text = { Text("Marketplace (${tools.size})", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { tabIndex = 1 },
                text = { Text("10-Stage Pipeline", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (tabIndex == 0) {
                item {
                    NeuralCard(
                        borderColor = if (settings.autoActivateSafeDeclarativeTools) NeuralMint.copy(alpha = 0.5f) else NeuralCyan.copy(alpha = 0.35f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto Mode (Safe Declarative Tools)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Sirf zero-permission, zero-network, non-sensitive declarative tools test+scan pass hone par auto-activate (Undo + Notification ke sath).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.autoActivateSafeDeclarativeTools,
                                onCheckedChange = { enabled ->
                                    viewModel.updateSettings { it.copy(autoActivateSafeDeclarativeTools = enabled) }
                                },
                                modifier = Modifier.testTag("marketplace_auto_mode_switch")
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.detectGapsAndProposeTools() },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 140.dp, minHeight = 48.dp)
                                    .testTag("detect_gaps_propose_btn")
                            ) {
                                Text("Detect Gaps & Propose", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                item {
                    SectionHeader(
                        title = "Tool & Proposal Marketplace",
                        subtitle = "Proposer, License, Scan, Test, 10-Stage Pipeline, Capability Brief & History"
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
                    val parsedBrief = remember(tool.capabilitySummaryJson) {
                        CapabilitySummaryAnalyzer.fromJsonString(tool.capabilitySummaryJson)
                    }
                    var showHistory by remember(tool.toolId) { mutableStateOf(false) }

                    NeuralCard(
                        borderColor = if (tool.autoActivated) StatusAmber.copy(alpha = 0.65f) else NeuralCyan.copy(alpha = 0.35f)
                    ) {
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Proposed By: ${tool.proposedBy}", style = MaterialTheme.typography.labelMedium, color = NeuralMint, modifier = Modifier.weight(1f))
                            if (tool.autoActivated) {
                                StatusPill("AUTO-ACTIVATED")
                            }
                        }

                        Text(tool.description, style = MaterialTheme.typography.bodySmall)

                        if (parsedBrief != null) {
                            CapabilityBriefBlock(parsedBrief = parsedBrief)
                        }

                        Text("Security Scan: ${tool.scanResult} (${tool.securityScore}/100)", style = MaterialTheme.typography.labelSmall)
                        Text("Test Result: ${tool.testResult}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tool.branchName.isNotBlank()) {
                            Text("GitHub Branch: ${tool.branchName}", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                        }
                        if (tool.releaseUrl.isNotBlank()) {
                            Text("Release / CI: ${tool.releaseUrl}", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                        }

                        if (!tool.isBuiltIn && tool.pipelineStagesJson.isNotBlank() && tool.pipelineStagesJson != "[]") {
                            Text("10-Stage Pipeline:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
                                            text = "${st.stage.take(8)}:${st.status.take(4)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            when (tool.activationStatus) {
                                "PROPOSED" -> {
                                    Button(
                                        onClick = { viewModel.runSelfCodingToolCycle(tool.name, tool.toolKind, tool.toolId) },
                                        enabled = !isBusy,
                                        modifier = Modifier.defaultMinSize(minWidth = 112.dp, minHeight = 48.dp)
                                    ) { Text("Run Pipeline", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                                }
                                "WAITING_APPROVAL" -> {
                                    Button(
                                        onClick = { viewModel.requestApproveAndInstallTool(tool) },
                                        enabled = !isBusy,
                                        modifier = Modifier.defaultMinSize(minWidth = 118.dp, minHeight = 48.dp)
                                    ) { Text("Approve & Install", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                                }
                                FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name, FeatureStatus.ACTIVE.name -> {
                                    if (tool.toolKind != "BUILT_IN") {
                                        Button(
                                            onClick = { viewModel.verifyAndRunTool(tool, runtimeInput) },
                                            enabled = !isBusy,
                                            modifier = Modifier.defaultMinSize(minWidth = 118.dp, minHeight = 48.dp)
                                        ) { Text("Execute & Verify", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                                        if (tool.autoActivated) {
                                            OutlinedButton(
                                                onClick = { viewModel.undoAutoActivatedTool(tool) },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusAmber),
                                                modifier = Modifier
                                                    .defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                                                    .testTag("undo_auto_tool_${tool.toolId}")
                                            ) {
                                                Text("Undo", maxLines = 1, softWrap = false)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.rollbackMarketplaceTool(tool) },
                                            modifier = Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 48.dp)
                                        ) {
                                            Text("Rollback", maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                                else -> {
                                    if (!tool.isBuiltIn) {
                                        OutlinedButton(
                                            onClick = { viewModel.runSelfCodingToolCycle(tool.name, tool.toolKind, tool.toolId) },
                                            enabled = !isBusy,
                                            modifier = Modifier.defaultMinSize(minWidth = 104.dp, minHeight = 48.dp)
                                        ) { Text("Retry Pipeline", maxLines = 1, softWrap = false) }
                                    }
                                }
                            }
                            if (tool.historyLog.isNotBlank()) {
                                FilledTonalButton(
                                    onClick = { showHistory = !showHistory },
                                    modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                                ) {
                                    Text(if (showHistory) "Hide Log" else "History", maxLines = 1, softWrap = false)
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
                            title = "10-Stage Tool Creation Pipeline",
                            subtitle = "DISCOVER → ASK ADVISORS → DESIGN → CODE → BUILD → TEST → SECURITY SCAN → READY FOR APPROVAL → USER INSTALL → VERIFY"
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedKind == "DECLARATIVE_JSON",
                                onClick = { selectedKind = "DECLARATIVE_JSON" },
                                label = { Text("Safe Declarative JSON", maxLines = 1, softWrap = false) }
                            )
                            FilterChip(
                                selected = selectedKind == "KOTLIN_GITHUB_BRANCH",
                                onClick = { selectedKind = "KOTLIN_GITHUB_BRANCH" },
                                label = { Text("Kotlin Branch + CI APK", maxLines = 1, softWrap = false) }
                            )
                        }
                        OutlinedTextField(
                            value = capabilityGoal,
                            onValueChange = { capabilityGoal = it },
                            label = { Text("Describe capability or tool to build") },
                            modifier = Modifier.fillMaxWidth().testTag("self_code_goal_input")
                        )
                        Button(
                            onClick = {
                                viewModel.runSelfCodingToolCycle(capabilityGoal, selectedKind)
                                capabilityGoal = ""
                            },
                            enabled = capabilityGoal.isNotBlank() && !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("self_code_run_btn")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                            }
                            Text("Build Tool (Stages 1–7)", maxLines = 1, softWrap = false)
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
                            modifier = Modifier
                                .defaultMinSize(minWidth = 180.dp, minHeight = 48.dp)
                                .testTag("run_research_cycle_btn")
                        ) {
                            Text("Run Research Cycle Now", maxLines = 1, softWrap = false)
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
