package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.RoutingCostStrategy
import com.example.system.DevStudioEngine
import com.example.system.DevStudioPhase
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun DevStudioScreen(viewModel: TeenaViewModel) {
    val state by viewModel.devStudioState.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val routerDecision by viewModel.devStudioRouterDecision.collectAsStateWithLifecycle()
    val aiReply by viewModel.devStudioAiReply.collectAsStateWithLifecycle()
    val aiError by viewModel.devStudioAiError.collectAsStateWithLifecycle()
    val isAiBusy by viewModel.isDevStudioAiBusy.collectAsStateWithLifecycle()

    var showCustomMirror by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }
    var customSha256 by remember { mutableStateOf("") }
    var newFileName by remember { mutableStateOf("sandbox_test.py") }
    var editorDraft by remember(state.selectedFilePath, state.selectedFileContent) {
        mutableStateOf(state.selectedFileContent)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDevStudioStatus()
    }

    val isWorking = state.phase in setOf(
        DevStudioPhase.CHECKING_PREREQUISITES,
        DevStudioPhase.DOWNLOADING,
        DevStudioPhase.VERIFYING_SHA256,
        DevStudioPhase.EXTRACTING,
        DevStudioPhase.REPAIRING
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dev_studio_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(
                borderColor = when (state.phase) {
                    DevStudioPhase.READY -> NeuralMint.copy(alpha = 0.7f)
                    DevStudioPhase.ERROR -> StatusCrimson.copy(alpha = 0.75f)
                    else -> NeuralCyan.copy(alpha = 0.55f)
                }
            ) {
                SectionHeader(
                    title = "Dev Studio Engine Setup Wizard",
                    subtitle = "${state.engineVersion} • On-demand sandboxed Linux rootfs",
                    trailing = {
                        StatusPill(
                            status = state.statusLabel,
                            modifier = Modifier.testTag("dev_studio_status_pill")
                        )
                    }
                )

                if (isWorking || state.progress.percent in 1..99) {
                    LinearProgressIndicator(
                        progress = { (state.progress.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .testTag("dev_studio_progress_bar"),
                        color = NeuralCyan
                    )
                }

                Text(
                    text = state.progress.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.progress.lastError != null) StatusCrimson else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("dev_studio_progress_text")
                )

                if (state.rootfsInstalled) {
                    Text(
                        text = "Rootfs v${state.rootfsVersion} • ${DevStudioEngine.formatBytes(state.rootfsSizeBytes)} • SHA-256 ${state.rootfsSha256.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralMint
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.installDevStudioEngine(customUrl, customSha256) },
                        enabled = !isWorking && !emergencyStopped && state.abi.isSupported && state.storage.hasEnoughSpace,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("dev_studio_install_btn")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.rootfsInstalled) "Reinstall" else "Install Dev Studio", maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.autoRepairDevStudio() },
                        enabled = !isWorking,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("dev_studio_repair_btn")
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Auto-Repair", maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.killAllDevStudioProcesses() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCrimson),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("dev_studio_kill_btn")
                    ) {
                        Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Kill Tasks (${state.activeProcesses.size})", maxLines = 1)
                    }

                    if (state.rootfsInstalled) {
                        OutlinedButton(
                            onClick = { viewModel.uninstallDevStudioRootfs() },
                            enabled = !isWorking,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("dev_studio_uninstall_btn")
                        ) {
                            Text("Remove Rootfs", maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.refreshDevStudioStatus() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("dev_studio_refresh_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Refresh", maxLines = 1)
                        }
                    }
                }

                TextButton(
                    onClick = { showCustomMirror = !showCustomMirror },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("dev_studio_mirror_toggle_btn")
                ) {
                    Text(
                        if (showCustomMirror) "Hide Custom Rootfs URL / SHA-256"
                        else "Custom Rootfs Mirror / SHA-256 (Optional)"
                    )
                }

                AnimatedVisibility(visible = showCustomMirror) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("Custom .tar.gz URL (blank = Alpine ${state.abi.primaryAbi})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("dev_studio_custom_url_input")
                        )
                        OutlinedTextField(
                            value = customSha256,
                            onValueChange = { customSha256 = it },
                            label = { Text("Expected SHA-256 Hex (64 chars, optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("dev_studio_custom_sha_input")
                        )
                    }
                }
            }
        }

        item {
            NeuralCard(modifier = Modifier.testTag("dev_studio_env_card")) {
                SectionHeader(
                    title = "Runtime Environment & Binaries",
                    subtitle = "ABI compatibility, StatFs storage guard & native binary probe",
                    trailing = {
                        StatusPill(if (state.foregroundServiceActive) "FGS_ACTIVE" else "IDLE")
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "Free Storage (Req ${state.storage.formattedRequired})",
                        value = state.storage.formattedAvailable,
                        accent = if (state.storage.hasEnoughSpace) NeuralMint else StatusCrimson,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dev_studio_storage_tile")
                    )
                    MetricTile(
                        label = "Device ABI (${state.abi.supportedAbis.size} reported)",
                        value = state.abi.primaryAbi,
                        accent = if (state.abi.isSupported) NeuralCyan else StatusAmber,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dev_studio_abi_tile")
                    )
                }

                Text(
                    text = state.abi.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("libproot.so", style = MaterialTheme.typography.labelLarge)
                    StatusPill(
                        if (state.nativeBinaries.hasProot) "BUNDLED" else "SANDBOX_FALLBACK",
                        modifier = Modifier.testTag("dev_studio_proot_pill")
                    )
                }

                Text(
                    text = state.nativeBinaries.fallbackModeReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            NeuralCard(modifier = Modifier.testTag("dev_studio_bridge_card")) {
                SectionHeader(
                    title = if (state.bridgeStatus.isRunning) {
                        "IDE Socket Bridge (127.0.0.1:${state.bridgeStatus.port})"
                    } else {
                        "IDE Socket Bridge"
                    },
                    subtitle = "Local loopback bridge (${state.bridgeStatus.mode})",
                    trailing = {
                        StatusPill(
                            if (state.bridgeStatus.isRunning) "LISTENING" else "STOPPED",
                            modifier = Modifier.testTag("dev_studio_bridge_pill")
                        )
                    }
                )

                Text(
                    text = "Bridge status: ${state.bridgeStatus.lastResponse}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = NeuralCyan,
                    modifier = Modifier.testTag("dev_studio_bridge_response")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (state.bridgeStatus.isRunning) viewModel.stopDevStudioBridge()
                            else viewModel.startDevStudioBridge()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("dev_studio_bridge_toggle_btn")
                    ) {
                        Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.bridgeStatus.isRunning) "Stop Bridge" else "Start Bridge")
                    }

                    OutlinedButton(
                        onClick = { viewModel.pingDevStudioBridge() },
                        enabled = state.bridgeStatus.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("dev_studio_bridge_ping_btn")
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Ping Bridge")
                    }
                }
            }
        }

        item {
            NeuralCard(modifier = Modifier.testTag("dev_studio_workspace_card")) {
                SectionHeader(
                    title = "Workspace Sandbox & Editor (${state.workspaceFiles.size})",
                    subtitle = state.workspacePath
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("Sandbox file path (e.g. app.py)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dev_studio_new_file_input")
                    )
                    Button(
                        onClick = {
                            viewModel.createDevStudioStarterFile(
                                fileName = newFileName,
                                content = "# Created inside Teena Dev Studio sandbox\nprint('Hello from Teena Dev Studio')\n"
                            )
                        },
                        enabled = newFileName.isNotBlank(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("dev_studio_create_file_btn")
                    ) {
                        Text("Create")
                    }
                }

                if (state.workspaceFiles.isEmpty()) {
                    Text(
                        text = "Workspace empty yet. Create a file or run Auto-Repair to seed hello_teena.py.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.workspaceFiles.take(15).forEach { node ->
                        val isSelected = node.relativePath == state.selectedFilePath
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !node.isDirectory) {
                                    viewModel.openDevStudioFile(node.relativePath)
                                }
                                .padding(vertical = 4.dp)
                                .testTag("dev_studio_workspace_file_${node.name}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (node.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (isSelected) NeuralMint else if (node.isDirectory) StatusAmber else NeuralCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = node.relativePath,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) NeuralMint else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (node.isDirectory) "DIR" else DevStudioEngine.formatBytes(node.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!node.isDirectory) {
                                IconButton(
                                    onClick = { viewModel.deleteDevStudioFile(node.relativePath) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete ${node.name}",
                                        tint = StatusCrimson.copy(alpha = 0.85f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.selectedFilePath.isNotBlank()) {
                    Text(
                        text = "Editing: ${state.selectedFilePath}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeuralMint,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("dev_studio_editor_title")
                    )
                    OutlinedTextField(
                        value = editorDraft,
                        onValueChange = { editorDraft = it },
                        minLines = 5,
                        maxLines = 10,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dev_studio_editor_field")
                    )
                    Button(
                        onClick = { viewModel.saveDevStudioFile(state.selectedFilePath, editorDraft) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("dev_studio_save_file_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Save ${state.selectedFilePath}")
                    }
                }
            }
        }

        item {
            DevStudioAiCopilotCard(
                selectedFilePath = state.selectedFilePath,
                targetFileFallback = newFileName,
                routingStrategy = RoutingCostStrategy.fromString(settings.routingCostStrategy),
                dynamicRoutingEnabled = settings.dynamicPromptRoutingEnabled,
                routerDecision = routerDecision,
                aiReply = aiReply,
                aiError = aiError,
                isBusy = isAiBusy,
                emergencyStopped = emergencyStopped,
                onCycleStrategy = { viewModel.cycleRoutingCostStrategy() },
                onGenerateCode = { prompt, target -> viewModel.generateDevStudioCodeWithRouter(prompt, target) },
                onApplyCodeToFile = { target -> viewModel.applyDevStudioAiCodeToFile(target) }
            )
        }

        item {
            DevStudioTerminalCard(
                isCommandRunning = state.isCommandRunning,
                history = state.terminalHistory,
                onRunCommand = { cmd -> viewModel.runDevStudioTerminalCommand(cmd) },
                onClearHistory = { viewModel.clearDevStudioTerminal() }
            )
        }

        item {
            NeuralCard(modifier = Modifier.testTag("dev_studio_logs_card")) {
                SectionHeader(
                    title = "Engine Diagnostic Logs (${state.logs.size}/100)",
                    subtitle = "Install, SHA-256 checks, socket bridge & Kill Tasks events"
                )
                if (state.logs.isEmpty()) {
                    Text(
                        text = "No engine events recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(state.logs.takeLast(25).reversed()) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
