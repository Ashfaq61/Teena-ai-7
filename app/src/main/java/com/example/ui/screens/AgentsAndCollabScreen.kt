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
fun AgentsAndCollabScreen(viewModel: TeenaViewModel) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val collabs by viewModel.collaborations.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val report by viewModel.diagnosticReport.collectAsStateWithLifecycle()

    var tabIndex by remember { mutableIntStateOf(0) }
    var missionInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("17 AI Agents") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Collaboration Feed") })
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                NeuralCard {
                    SectionHeader(
                        title = "Autonomous Multi-Agent Mission",
                        subtitle = "Dispatches real API protocol tasks across Research, GitHub, Security & Main Agents"
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

            if (tabIndex == 0) {
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
            } else {
                if (collabs.isEmpty()) {
                    item {
                        NeuralCard {
                            Text("No inter-agent protocol messages recorded yet. Run a mission above to watch real agent handoffs.", style = MaterialTheme.typography.bodySmall)
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
