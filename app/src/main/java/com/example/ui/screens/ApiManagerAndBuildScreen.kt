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
import com.example.data.remote.ModelRegistry
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun ApiManagerScreen(viewModel: TeenaViewModel) {
    val slots by viewModel.providerSlots.collectAsStateWithLifecycle()
    val modelCache by viewModel.geminiModelCache.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredModels.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf(ProviderType.GEMINI_DIRECT) }
    var slotLabel by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var baseUrlInput by remember { mutableStateOf("") }
    var editingKeySlotId by remember { mutableStateOf<String?>(null) }
    var editingKeyDraft by remember { mutableStateOf("") }

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
                            ProviderType.GEMINI_DIRECT,
                            ProviderType.GROQ,
                            ProviderType.OPENROUTER,
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
                    label = { Text("Slot Label (e.g. 'Gemini Key #1' or 'Groq Key')") },
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
                    label = {
                        Text(
                            if (selectedType == ProviderType.GEMINI_DIRECT) {
                                "Model ID (optional — leaves ModelRegistry selection)"
                            } else {
                                "Model ID (leave blank for default)"
                            }
                        )
                    },
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
            items(discovered.take(20)) { m ->
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
            val isOffline = slot.providerType == ProviderType.OFFLINE_LOCAL.name
            val isGemini = slot.providerType == ProviderType.GEMINI_DIRECT.name
            val masked = when {
                isOffline -> "On-device deterministic engine (no key needed)"
                hasSecret -> viewModel.container.vault.maskedPreview(slot.slotId)
                isGemini -> "Gemini API key missing"
                else -> "No key stored"
            }

            val keyFingerprint = if (isGemini && hasSecret) {
                viewModel.container.vault.readSecret(slot.slotId)?.let { ModelRegistry.keyFingerprint(it) }.orEmpty()
            } else ""
            val cachedForKey = if (keyFingerprint.isNotEmpty()) {
                modelCache.filter { it.keyFingerprint == keyFingerprint }
            } else emptyList()
            val blacklistedForKey = cachedForKey.filter { it.probeStatus == "BLACKLISTED" }
            val workingForKey = cachedForKey
                .filter { it.supportsGenerateContent && it.probeStatus == "WORKING" }
                .minByOrNull { it.lastLatencyMs }
                ?.modelId

            val displayedModel = slot.selectedModel.trim().ifBlank {
                workingForKey ?: "Not selected (tap Test to probe ModelRegistry)"
            }

            NeuralCard(borderColor = if (slot.isPrimary) NeuralCyan else MaterialTheme.colorScheme.outline) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Model: $displayedModel",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeuralCyan
                        )
                        Text("Key: $masked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (slot.lastLatencyMs > 0) {
                            Text("Latency: ${slot.lastLatencyMs}ms", style = MaterialTheme.typography.labelSmall, color = NeuralMint)
                        }
                    }
                    StatusPill(slot.lastTestStatus)
                }
                Text(slot.lastTestMessage, style = MaterialTheme.typography.bodySmall)

                if (blacklistedForKey.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Blacklisted Models (${blacklistedForKey.size}):",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusCrimson
                        )
                        blacklistedForKey.take(5).forEach { b ->
                            Text(
                                text = "• ${b.modelId}: ${b.probeError.ifBlank { "Unavailable" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (editingKeySlotId == slot.slotId) {
                    OutlinedTextField(
                        value = editingKeyDraft,
                        onValueChange = { editingKeyDraft = it },
                        label = { Text("Enter API Key for ${slot.label}") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("edit_slot_key_input_${slot.slotId}")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.updateSlotApiKey(slot.slotId, editingKeyDraft)
                                editingKeySlotId = null
                                editingKeyDraft = ""
                            },
                            enabled = !isBusy
                        ) { Text("Save & Test") }
                        OutlinedButton(onClick = {
                            editingKeySlotId = null
                            editingKeyDraft = ""
                        }) { Text("Cancel") }
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilledTonalButton(onClick = { viewModel.testProviderSlot(slot) }) {
                            Text("Test")
                        }
                    }
                    if (!isOffline) {
                        item {
                            OutlinedButton(onClick = {
                                editingKeySlotId = if (editingKeySlotId == slot.slotId) null else slot.slotId
                                editingKeyDraft = ""
                            }) {
                                Text(if (hasSecret) "Edit Key" else "Set Key")
                            }
                        }
                    }
                    if (!slot.isPrimary) {
                        item {
                            OutlinedButton(onClick = { viewModel.setPrimarySlot(slot.slotId) }) {
                                Text("Set Primary")
                            }
                        }
                    } else {
                        item { StatusPill("PRIMARY") }
                    }
                    if (slot.providerType == ProviderType.GROQ.name ||
                        slot.providerType == ProviderType.GEMINI_DIRECT.name ||
                        slot.providerType == ProviderType.OPENROUTER.name
                    ) {
                        item {
                            OutlinedButton(onClick = {
                                viewModel.discoverModels(ProviderType.valueOf(slot.providerType), slot.slotId)
                            }) { Text("Models") }
                        }
                    }
                    if (blacklistedForKey.isNotEmpty()) {
                        item {
                            OutlinedButton(onClick = { viewModel.resetGeminiBlacklist(slot.slotId) }) {
                                Text("Clear Blacklist")
                            }
                        }
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
    val apkBuildState by viewModel.devApkBuildState.collectAsStateWithLifecycle()

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
                OutlinedTextField(value = workflow, onValueChange = { workflow = it }, label = { Text("Workflow File (e.g. build.yml)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = ghToken,
                    onValueChange = { ghToken = it },
                    label = { Text("GitHub PAT (saved: ${apkBuildState.maskedGithubToken}, encrypted in Keystore)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        viewModel.saveGitHubCiConfig(owner, repo, workflow, ghToken)
                        ghToken = ""
                    }) { Text("Save") }
                    OutlinedButton(
                        onClick = { viewModel.testDevGitHubToken(ghToken) },
                        enabled = !apkBuildState.isTestingGithubToken
                    ) { Text(if (apkBuildState.isTestingGithubToken) "Testing..." else "Test Token") }
                    OutlinedButton(onClick = { viewModel.fetchGitHubCiRuns() }, enabled = !isBusy) {
                        Text("Check Runs")
                    }
                }
                Button(onClick = { viewModel.triggerGitHubCiBuild() }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Trigger CI Build")
                }
                if (!apkBuildState.githubTokenTestResult.isNullOrBlank()) {
                    Text(
                        text = apkBuildState.githubTokenTestResult.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (apkBuildState.githubTokenTestSucceeded == true) NeuralMint else StatusCrimson
                    )
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
                Text("SHA256: ${b.sha256Checksum.take(36)}... • Size: ${b.apkSizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                Text(b.logs.take(240), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (b.apkLocalPath.isNotBlank()) {
                        FilledTonalButton(onClick = { viewModel.reinstallFromBuildRecord(b) }) { Text("Re-install") }
                    }
                    if (b.downloadUrl.isNotBlank()) {
                        OutlinedButton(onClick = { viewModel.openExternalUrl(b.downloadUrl) }) { Text("Open URL") }
                    }
                    OutlinedButton(onClick = { viewModel.deleteBuildRecord(b) }) { Text("Delete") }
                }
            }
        }
    }
}
