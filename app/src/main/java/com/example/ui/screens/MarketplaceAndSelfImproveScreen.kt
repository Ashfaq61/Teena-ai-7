package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun MarketplaceAndSelfImproveScreen(viewModel: TeenaViewModel) {
    val tools by viewModel.marketplaceTools.collectAsStateWithLifecycle()
    val logs by viewModel.improvementLogs.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var tabIndex by remember { mutableIntStateOf(0) }
    var capabilityGoal by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Tool Marketplace") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Self-Improvement") })
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (tabIndex == 0) {
                item {
                    SectionHeader(
                        title = "Teena Tool & Agent Marketplace",
                        subtitle = "Security-audited, license-verified capabilities"
                    )
                }
                items(tools, key = { it.toolId }) { tool ->
                    NeuralCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${tool.name} v${tool.version}", style = MaterialTheme.typography.titleMedium)
                                Text("${tool.category} • License: ${tool.licenseSpdx}", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                            }
                            StatusPill(tool.activationStatus)
                        }
                        Text(tool.description, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Security: ${tool.securityScore}/100 • Test: ${tool.testResult}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralMint
                        )
                        Text("Source: ${tool.sourceRepo}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { viewModel.toggleToolActivation(tool) }) {
                            Text(if (tool.isInstalled) "Uninstall / Disable" else "Verify & Install")
                        }
                    }
                }
            } else {
                item {
                    NeuralCard {
                        SectionHeader(
                            title = "Self-Coding & Tool Creation Engine",
                            subtitle = "DISCOVER → ANALYZE → DESIGN → CODE → BUILD → TEST → SECURITY → INSTALL → VERIFY → ACTIVATE"
                        )
                        OutlinedTextField(
                            value = capabilityGoal,
                            onValueChange = { capabilityGoal = it },
                            label = { Text("Describe missing tool or capability to build") },
                            modifier = Modifier.fillMaxWidth().testTag("self_code_goal_input")
                        )
                        Button(
                            onClick = {
                                viewModel.runSelfCodingToolCycle(capabilityGoal)
                                capabilityGoal = ""
                            },
                            enabled = capabilityGoal.isNotBlank() && !isBusy,
                            modifier = Modifier.testTag("self_code_run_btn")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                            }
                            Text("Run Autonomous Creation Pipeline")
                        }
                    }
                }

                items(logs, key = { it.id }) { item ->
                    NeuralCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.cycleTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            StatusPill(if (item.verified) "VERIFIED" else if (item.rolledBack) "BLOCKED" else "FAILED")
                        }
                        Text("Stage: ${item.stageReached} • ${item.actionTaken}", style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                        Text(item.findings, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
