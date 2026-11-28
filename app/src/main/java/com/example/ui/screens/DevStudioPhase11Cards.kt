package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.RouterDecision
import com.example.ai.RoutingCostStrategy
import com.example.system.DevStudioTerminalEntry
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import java.util.Locale

@Composable
fun DevStudioAiCopilotCard(
    selectedFilePath: String,
    targetFileFallback: String,
    activeGeminiModel: String,
    routingStrategy: RoutingCostStrategy,
    dynamicRoutingEnabled: Boolean,
    routerDecision: RouterDecision?,
    aiReply: String,
    aiError: String?,
    isBusy: Boolean,
    hasLastPrompt: Boolean,
    emergencyStopped: Boolean,
    onCycleStrategy: () -> Unit,
    onGenerateCode: (prompt: String, targetFileName: String) -> Unit,
    onRetry: () -> Unit,
    onApplyCodeToFile: (targetFileName: String) -> Unit
) {
    var aiPrompt by remember { mutableStateOf("") }
    val activeTarget = selectedFilePath.ifBlank { targetFileFallback.ifBlank { "app.py" } }

    NeuralCard(
        borderColor = NeuralViolet.copy(alpha = 0.65f),
        modifier = Modifier.testTag("dev_studio_ai_card")
    ) {
        SectionHeader(
            title = "Phase 11 AI Code Co-Pilot",
            subtitle = "Shared Gemini Engine ($activeGeminiModel) • Same key as Chat • Target: $activeTarget",
            trailing = {
                AssistChip(
                    onClick = onCycleStrategy,
                    label = {
                        Text(
                            if (dynamicRoutingEnabled) routingStrategy.name.replace("_", " ")
                            else "MANUAL",
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    modifier = Modifier.testTag("dev_studio_router_pill")
                )
            }
        )

        if (routerDecision != null) {
            val selectedProfile = routerDecision.costProfiles[routerDecision.selectedProvider]
            val costText = when {
                selectedProfile == null -> activeGeminiModel
                selectedProfile.isFreeTier -> "FREE ($0.00)"
                else -> "~$${"%.5f".format(Locale.US, selectedProfile.estimatedCostUsd)}"
            }
            Text(
                text = "Routed: ${routerDecision.selectedProvider.displayName} ($activeGeminiModel) • ${routerDecision.evaluation.complexityTier.name} • $costText",
                style = MaterialTheme.typography.labelMedium,
                color = NeuralCyan,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("dev_studio_ai_decision_text")
            )
        } else {
            Text(
                text = "Gemini ready ($activeGeminiModel). Ask a coding question or request a script.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("dev_studio_ai_decision_text")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "Write a Python CLI JSON formatter",
                "Create a quick HTTP health check script",
                "Add unit tests & error handling"
            ).forEach { quick ->
                AssistChip(
                    onClick = { aiPrompt = quick },
                    label = { Text(quick, maxLines = 1, softWrap = false) }
                )
            }
        }

        OutlinedTextField(
            value = aiPrompt,
            onValueChange = { aiPrompt = it },
            label = { Text("Ask Gemini for $activeTarget...") },
            maxLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dev_studio_ai_prompt_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onGenerateCode(aiPrompt, activeTarget) },
                enabled = aiPrompt.isNotBlank() && !isBusy && !emergencyStopped,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("dev_studio_ai_run_btn")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.size(6.dp))
                Text(if (isBusy) "Asking..." else "Ask", maxLines = 1)
            }

            FilledTonalButton(
                onClick = { onApplyCodeToFile(activeTarget) },
                enabled = aiReply.isNotBlank() && !isBusy,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dev_studio_ai_apply_btn")
            ) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Save to File", maxLines = 1)
            }
        }

        if (!aiError.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StatusCrimson.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dev_studio_ai_error")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = aiError,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusCrimson,
                        modifier = Modifier.testTag("dev_studio_ai_error_text")
                    )
                    if (hasLastPrompt && !emergencyStopped) {
                        OutlinedButton(
                            onClick = onRetry,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("dev_studio_ai_retry_btn")
                        ) {
                            Text("Retry", maxLines = 1)
                        }
                    }
                }
            }
        }

        if (aiReply.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dev_studio_ai_output")
            ) {
                Text(
                    text = aiReply.take(2400),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
fun DevStudioTerminalCard(
    isCommandRunning: Boolean,
    history: List<DevStudioTerminalEntry>,
    onRunCommand: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    var cmdInput by remember { mutableStateOf("") }

    NeuralCard(
        borderColor = NeuralCyan.copy(alpha = 0.6f),
        modifier = Modifier.testTag("dev_studio_terminal_card")
    ) {
        SectionHeader(
            title = "Sandbox Terminal (${history.size})",
            subtitle = "Runs sandbox built-ins & proot/ProcessBuilder commands inside workspace",
            trailing = {
                StatusPill(if (isCommandRunning) "RUNNING" else "READY")
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("help", "ls", "pwd", "env", "bridge ping").forEach { preset ->
                AssistChip(
                    onClick = { onRunCommand(preset) },
                    label = { Text(preset, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("dev_studio_term_preset_${preset.replace(" ", "_")}")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = cmdInput,
                onValueChange = { cmdInput = it },
                label = { Text("Command (e.g. ls, cat hello_teena.py, env)") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("dev_studio_terminal_input")
            )
            Button(
                onClick = {
                    val toRun = cmdInput
                    cmdInput = ""
                    onRunCommand(toRun)
                },
                enabled = cmdInput.isNotBlank() && !isCommandRunning,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dev_studio_terminal_run_btn")
            ) {
                Icon(
                    if (isCommandRunning) Icons.AutoMirrored.Filled.Send else Icons.Default.PlayArrow,
                    contentDescription = "Run Command",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text("Run", maxLines = 1)
            }
        }

        if (history.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("dev_studio_terminal_output"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                history.takeLast(8).forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "$ ${entry.command}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = NeuralMint,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = entry.output,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (entry.exitCode == 0) MaterialTheme.colorScheme.onSurface else StatusAmber
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onClearHistory,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dev_studio_terminal_clear_btn")
            ) {
                Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Clear Terminal")
            }
        }
    }
}
