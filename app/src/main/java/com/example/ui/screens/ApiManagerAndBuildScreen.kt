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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ProviderType
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun ApiManagerScreen(viewModel: TeenaViewModel) {
    val slots by viewModel.providerSlots.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredModels.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf(ProviderType.GROQ) }
    var slotLabel by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var baseUrlInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Add Encrypted AI Provider Slot",
                    subtitle = "Keys encrypted with Android Keystore AES-256-GCM"
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        listOf(
                            ProviderType.GROQ,
                            ProviderType.OPENROUTER,
                            ProviderType.GEMINI_DIRECT,
                            ProviderType.CUSTOM_OPENAI
                        )
                    ) { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t.displayName.substringBefore(" ")) }
                        )
                    }
                }
                OutlinedTextField(
                    value = slotLabel,
                    onValueChange = { slotLabel = it },
                    label = { Text("Slot Label (e.g. 'Groq Key #1')") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key (stored encrypted, never logged)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("api_key_input")
                )
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    label = { Text("Model ID (leave blank for default)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (selectedType == ProviderType.CUSTOM_OPENAI) {
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("Base URL (e.g. https://api.together.xyz/v1)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.addProviderSlot(selectedType, slotLabel, modelInput, apiKeyInput, baseUrlInput)
                            apiKeyInput = ""
                            slotLabel = ""
                        },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("add_provider_slot_btn")
                    ) { Text("Save & Test Key") }

                    FilledTonalButton(
                        onClick = { viewModel.discoverModels(ProviderType.OPENROUTER, "") },
                        enabled = !isBusy
                    ) { Text("Discover OpenRouter Free") }
                }
            }
        }

        if (discovered.isNotEmpty()) {
            item { SectionHeader("Discovered Models (${discovered.size})") }
            items(discovered.take(15)) { m ->
                NeuralCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.id, style = MaterialTheme.typography.titleSmall, color = NeuralCyan)
                            Text("${m.provider} • Context: ${m.contextLength} • ${m.pricingSummary}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (m.isFree) StatusPill("FREE")
                    }
                    val matchingSlot = slots.firstOrNull {
                        it.providerType.contains(m.provider, ignoreCase = true)
                    }
                    if (matchingSlot != null) {
                        FilledTonalButton(onClick = { viewModel.selectModelForSlot(matchingSlot, m.id) }) {
                            Text("Use on ${matchingSlot.label}")
                        }
                    }
                }
            }
        }

        item { SectionHeader("Configured Provider Slots (${slots.size})") }

        items(slots, key = { it.slotId }) { slot ->
            val hasSecret = viewModel.container.vault.hasSecret(slot.slotId)
            val masked = if (slot.providerType == ProviderType.GEMINI_AI_LOGIC.name) {
                "Keyless App Check Attestation"
            } else if (hasSecret) {
                viewModel.container.vault.maskedPreview(slot.slotId)
            } else {
                "No key stored"
            }

            NeuralCard(borderColor = if (slot.isPrimary) NeuralCyan else MaterialTheme.colorScheme.outline) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.label, style = MaterialTheme.typography.titleMedium)
                        Text("Model: ${slot.selectedModel}", style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                        Text("Key: $masked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(slot.lastTestStatus)
                }
                Text(slot.lastTestMessage, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { viewModel.testProviderSlot(slot) }) {
                        Text("Test")
                    }
                    if (!slot.isPrimary) {
                        OutlinedButton(onClick = { viewModel.setPrimarySlot(slot.slotId) }) {
                            Text("Set Primary")
                        }
                    } else {
                        StatusPill("PRIMARY")
                    }
                    if (slot.providerType == ProviderType.GROQ.name || slot.providerType == ProviderType.GEMINI_AI_LOGIC.name) {
                        OutlinedButton(onClick = {
                            viewModel.discoverModels(ProviderType.valueOf(slot.providerType), slot.slotId)
                        }) { Text("Models") }
                    }
                }
            }
        }
    }
}

@Composable
fun ApkBuildCenterScreen(viewModel: TeenaViewModel) {
    val apk by viewModel.apkInspection.collectAsStateWithLifecycle()
    val builds by viewModel.buildRecords.collectAsStateWithLifecycle()
    val runs by viewModel.workflowRuns.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var owner by remember(settings.githubOwner) { mutableStateOf(settings.githubOwner) }
    var repo by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }
    var workflow by remember(settings.githubWorkflowId) { mutableStateOf(settings.githubWorkflowId) }
    var ghToken by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.6f)) {
                SectionHeader(
                    title = "Live Signed APK Verification",
                    subtitle = "Preserves package identity & signing certificate",
                    trailing = { StatusPill("VERIFIED") }
                )
                Text("Package: ${apk?.packageName ?: "com.aistudio.teenaai.qx7mzk"}", style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                Text("Version: ${apk?.versionName ?: "1.0"} (code ${apk?.versionCode ?: 1}) • Debuggable: ${apk?.isDebuggable}", style = MaterialTheme.typography.bodySmall)
                Text("Size: ${(apk?.apkSizeBytes ?: 0L) / 1024} KB", style = MaterialTheme.typography.bodySmall)
                Text("APK SHA-256: ${apk?.apkSha256 ?: "Inspecting..."}", style = MaterialTheme.typography.labelSmall)
                Text("Signing Cert SHA-256: ${apk?.signingCertSha256 ?: "Inspecting..."}", style = MaterialTheme.typography.labelSmall, color = NeuralMint)
                Button(
                    onClick = { viewModel.verifyInstalledApkAndSaveRecord() },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("verify_apk_btn")
                ) { Text("Verify & Record APK Checksum") }
            }
        }

        item {
            NeuralCard {
                SectionHeader("GitHub Actions Autonomous Build Pipeline")
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("GitHub Owner / Org") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("Repository Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = workflow, onValueChange = { workflow = it }, label = { Text("Workflow File (e.g. android-build.yml)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = ghToken,
                    onValueChange = { ghToken = it },
                    label = { Text("GitHub PAT (Actions scope, encrypted in Keystore)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        viewModel.saveGitHubCiConfig(owner, repo, workflow, ghToken)
                        ghToken = ""
                    }) { Text("Save Config") }
                    OutlinedButton(onClick = { viewModel.fetchGitHubCiRuns() }, enabled = !isBusy) {
                        Text("Check Runs")
                    }
                    Button(onClick = { viewModel.triggerGitHubCiBuild() }, enabled = !isBusy) {
                        Text("Trigger CI Build")
                    }
                }
            }
        }

        if (runs.isNotEmpty()) {
            item { SectionHeader("GitHub Actions Workflow Runs") }
            items(runs) { r ->
                NeuralCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${r.name} (#${r.id})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusPill(r.conclusion.ifBlank { r.status })
                    }
                    Text("Created: ${r.createdAt}", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { viewModel.openExternalUrl(r.htmlUrl) }) { Text("View Logs & Artifacts") }
                }
            }
        }

        item { SectionHeader("Build & Release History") }
        items(builds, key = { it.id }) { b ->
            NeuralCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${b.buildSource} v${b.versionName} (${b.versionCode})", style = MaterialTheme.typography.titleSmall)
                    StatusPill(b.status)
                }
                Text("SHA256: ${b.sha256Checksum.take(36)}...", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                Text(b.logs, style = MaterialTheme.typography.bodySmall)
                if (b.downloadUrl.isNotBlank()) {
                    OutlinedButton(onClick = { viewModel.openExternalUrl(b.downloadUrl) }) { Text("Open Release") }
                }
            }
        }
    }
}
