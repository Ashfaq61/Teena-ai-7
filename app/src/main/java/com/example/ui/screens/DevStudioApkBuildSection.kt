package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ai.ErrorDoctorEngine
import com.example.data.local.BuildRecordEntity
import com.example.data.local.ErrorDoctorFixEntity
import com.example.data.local.TeenaSettings
import com.example.system.DevBuildMode
import com.example.system.DevBuildType
import com.example.system.DevStudioEngine
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.DevApkBuildPhase
import com.example.ui.viewmodel.DevApkBuildUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DevStudioApkToolbarCard(
    buildState: DevApkBuildUiState,
    settings: TeenaSettings,
    emergencyStopped: Boolean,
    onBuildApk: () -> Unit,
    onCancelBuild: () -> Unit,
    onSelectMode: (DevBuildMode) -> Unit,
    onSelectBuildType: (DevBuildType) -> Unit,
    onSaveGitHubConfig: (owner: String, repo: String, isPrivate: Boolean, token: String) -> Unit,
    onTestGitHubToken: (draftToken: String) -> Unit
) {
    var showGitHubPanel by remember { mutableStateOf(false) }
    var ownerDraft by remember(settings.githubOwner) { mutableStateOf(settings.githubOwner) }
    var repoDraft by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }
    var isPrivateDraft by remember(settings.githubPrivateRepo) { mutableStateOf(settings.githubPrivateRepo) }
    var tokenDraft by remember { mutableStateOf("") }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when (buildState.phase) {
                DevApkBuildPhase.SUCCESS -> NeuralMint.copy(alpha = 0.8f)
                DevApkBuildPhase.FAILED -> StatusCrimson.copy(alpha = 0.8f)
                else -> NeuralCyan.copy(alpha = 0.75f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dev_studio_apk_toolbar_card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBuildApk,
                enabled = !buildState.isActive && !emergencyStopped,
                colors = ButtonDefaults.buttonColors(containerColor = NeuralCyan, contentColor = Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("dev_studio_build_apk_btn")
            ) {
                if (buildState.isActive) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Icon(Icons.Default.Build, contentDescription = "Build APK", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    if (buildState.isActive) "Building (${buildState.elapsedSeconds}s)" else "Build APK",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            if (buildState.isActive) {
                Button(
                    onClick = onCancelBuild,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCrimson, contentColor = Color.White),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("dev_studio_build_cancel_btn")
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = "Cancel Build", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Cancel", maxLines = 1)
                }
            } else {
                FilledTonalButton(
                    onClick = { showGitHubPanel = !showGitHubPanel },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("dev_studio_gh_toggle_btn")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "GitHub Config", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (showGitHubPanel) "Close" else "GitHub", maxLines = 1)
                }
            }

            StatusPill(
                status = when {
                    buildState.isActive -> buildState.phase.name
                    buildState.phase == DevApkBuildPhase.SUCCESS -> "APK_READY"
                    buildState.phase == DevApkBuildPhase.FAILED -> "FAILED"
                    else -> if (buildState.mode == DevBuildMode.CLOUD_GITHUB) "CLOUD" else "LOCAL"
                },
                modifier = Modifier.testTag("dev_studio_apk_phase_pill")
            )
        }

        AnimatedVisibility(visible = showGitHubPanel) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = buildState.mode == DevBuildMode.CLOUD_GITHUB,
                        onClick = { onSelectMode(DevBuildMode.CLOUD_GITHUB) },
                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Cloud Build (Free)", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("dev_studio_mode_cloud_chip")
                    )
                    FilterChip(
                        selected = buildState.mode == DevBuildMode.LOCAL_ON_DEVICE,
                        onClick = { onSelectMode(DevBuildMode.LOCAL_ON_DEVICE) },
                        leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Local Build (Adv)", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("dev_studio_mode_local_chip")
                    )
                    FilterChip(
                        selected = buildState.buildType == DevBuildType.DEBUG,
                        onClick = { onSelectBuildType(DevBuildType.DEBUG) },
                        label = { Text("Debug", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("dev_studio_type_debug_chip")
                    )
                    FilterChip(
                        selected = buildState.buildType == DevBuildType.RELEASE,
                        onClick = { onSelectBuildType(DevBuildType.RELEASE) },
                        label = { Text("Release", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("dev_studio_type_release_chip")
                    )
                }

                Text(
                    text = "Fine-grained PAT scopes on 1 repo: Contents, Actions, Workflows. Stored encrypted in Keystore.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeuralCyan
                )
                OutlinedTextField(
                    value = ownerDraft,
                    onValueChange = { ownerDraft = it },
                    label = { Text("GitHub Username / Owner") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dev_studio_gh_owner_input")
                )
                OutlinedTextField(
                    value = repoDraft,
                    onValueChange = { repoDraft = it },
                    label = { Text("Repository Name (created automatically if missing)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dev_studio_gh_repo_input")
                )
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    label = {
                        Text(
                            if (buildState.hasGithubTokenSaved) "Fine-grained GitHub Token (saved: ${buildState.maskedGithubToken})"
                            else "Fine-grained GitHub Token (encrypted, never logged)"
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dev_studio_gh_token_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Private Repository", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Public = unlimited free GitHub Actions minutes; Private = 2,000 free min/mo.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPrivateDraft,
                        onCheckedChange = { isPrivateDraft = it },
                        modifier = Modifier.testTag("dev_studio_gh_private_switch")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            onSaveGitHubConfig(ownerDraft, repoDraft, isPrivateDraft, tokenDraft)
                            tokenDraft = ""
                        },
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_gh_save_btn")
                    ) {
                        Text("Save Encrypted", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onTestGitHubToken(tokenDraft) },
                        enabled = !buildState.isTestingGithubToken,
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_gh_test_btn")
                    ) {
                        Text(if (buildState.isTestingGithubToken) "Testing..." else "Test Token", maxLines = 1)
                    }
                }
                if (!buildState.githubTokenTestResult.isNullOrBlank()) {
                    val tone = when (buildState.githubTokenTestSucceeded) {
                        true -> NeuralMint
                        false -> StatusCrimson
                        null -> NeuralCyan
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tone.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = buildState.githubTokenTestResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = tone,
                            modifier = Modifier.padding(10.dp).testTag("dev_studio_gh_test_result")
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun DevStudioBuildLiveAndOutcomeSection(
    buildState: DevApkBuildUiState,
    doctorFixes: List<ErrorDoctorFixEntity>,
    onCancelBuild: () -> Unit,
    onInstallApk: () -> Unit,
    onShareApk: () -> Unit,
    onOpenUnknownAppsSettings: () -> Unit,
    onAskGeminiDoctor: () -> Unit,
    onApplyDoctorPatchAndRebuild: () -> Unit,
    onSwitchToCloudBuild: () -> Unit
) {
    // 1. Live Build Status Card
    if (buildState.phase != DevApkBuildPhase.IDLE) {
        NeuralCard(
            borderColor = when (buildState.phase) {
                DevApkBuildPhase.SUCCESS -> NeuralMint.copy(alpha = 0.8f)
                DevApkBuildPhase.FAILED -> StatusCrimson.copy(alpha = 0.8f)
                DevApkBuildPhase.CANCELLED -> StatusAmber.copy(alpha = 0.7f)
                else -> NeuralCyan.copy(alpha = 0.7f)
            },
            modifier = Modifier.testTag("dev_studio_build_status_card")
        ) {
            SectionHeader(
                title = "APK Build Status",
                subtitle = "Elapsed: ${buildState.elapsedSeconds}s • ${buildState.mode.label}",
                trailing = {
                    StatusPill(buildState.phase.name, modifier = Modifier.testTag("dev_studio_build_elapsed"))
                }
            )
            if (buildState.isActive) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = NeuralCyan
                )
            }
            Text(
                text = buildState.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (buildState.phase == DevApkBuildPhase.FAILED) StatusCrimson else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("dev_studio_build_status_msg")
            )
            buildState.pushSummary?.let { push ->
                Text(
                    text = "GitHub Push: ${push.pushedFilesCount} changed, ${push.skippedUnchangedCount} unchanged (SHA-1 blob diff)",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeuralCyan
                )
            }
            if (buildState.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Workflow Steps:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    buildState.steps.forEach { step ->
                        val stepColor = when {
                            step.conclusion == "success" -> NeuralMint
                            step.conclusion == "failure" -> StatusCrimson
                            step.status == "in_progress" -> NeuralCyan
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = "• ${step.number}. ${step.name} [${step.conclusion.ifBlank { step.status }}]",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = stepColor
                        )
                    }
                }
            }
            if (buildState.isActive) {
                OutlinedButton(
                    onClick = onCancelBuild,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCrimson),
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                ) {
                    Text("Cancel Active Build")
                }
            }
        }
    }

    // 2. Local Build Pre-flight Card (shown when Local mode selected)
    if (buildState.mode == DevBuildMode.LOCAL_ON_DEVICE && buildState.localPreflight != null) {
        val pre = buildState.localPreflight
        NeuralCard(
            borderColor = if (pre.canAttemptLocalBuild) NeuralMint.copy(alpha = 0.6f) else StatusAmber.copy(alpha = 0.7f),
            modifier = Modifier.testTag("dev_studio_local_preflight_card")
        ) {
            SectionHeader(
                title = "Local On-Device Build Pre-Flight (11D-2)",
                subtitle = pre.etaNote,
                trailing = { StatusPill(if (pre.canAttemptLocalBuild) "READY" else "WARN") }
            )
            Text(
                text = "Free Space: ${DevStudioEngine.formatBytes(pre.freeSpaceBytes)} / 6.0 GB min • RAM: ${"%.1f".format(pre.totalRamGb)} GB • ABI: ${pre.primaryAbi} • Battery: ${pre.batteryPercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Cached .gradle offline cache: ${DevStudioEngine.formatBytes(pre.offlineCacheBytes)} • aapt2: ${pre.aapt2Path.ifBlank { "not detected" }}",
                style = MaterialTheme.typography.labelSmall,
                color = NeuralCyan
            )
            pre.warnings.forEach { warn ->
                Text("• $warn", style = MaterialTheme.typography.bodySmall, color = StatusAmber)
            }
            if (!pre.canAttemptLocalBuild) {
                Button(
                    onClick = onSwitchToCloudBuild,
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("dev_studio_switch_cloud_btn")
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Use Free Cloud Build (GitHub Actions)")
                }
            }
        }
    }

    // 3. Success APK Install & Share Card
    buildState.builtApk?.let { apk ->
        NeuralCard(
            borderColor = NeuralMint.copy(alpha = 0.85f),
            modifier = Modifier.testTag("dev_studio_apk_success_card")
        ) {
            SectionHeader(
                title = "APK Built Successfully",
                subtitle = "${apk.fileName} • ${DevStudioEngine.formatBytes(apk.sizeBytes)}",
                trailing = { StatusPill("INSTALLABLE") }
            )
            Text(
                text = "Package: ${apk.packageName} • Version: ${apk.versionName} (${apk.versionCode})",
                style = MaterialTheme.typography.bodySmall,
                color = NeuralMint
            )
            Text(
                text = "SHA-256: ${apk.sha256.take(36)}...",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = NeuralCyan
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onInstallApk,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_apk_install_btn")
                ) {
                    Icon(Icons.Default.InstallMobile, contentDescription = "Install APK", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Install", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onShareApk,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_apk_share_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share APK", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Share", maxLines = 1)
                }
            }
            if (buildState.unknownAppsPermissionMissing) {
                OutlinedButton(
                    onClick = onOpenUnknownAppsSettings,
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("dev_studio_apk_unknown_apps_btn")
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Allow 'Install Unknown Apps' in Settings")
                }
            }
        }
    }

    // 4. Error Banner & Error Doctor (11E) Card
    if (!buildState.errorMessage.isNullOrBlank() || buildState.errorDoctorReport != null) {
        val report = buildState.errorDoctorReport
        NeuralCard(
            borderColor = StatusCrimson.copy(alpha = 0.85f),
            modifier = Modifier.testTag("dev_studio_error_doctor_card")
        ) {
            SectionHeader(
                title = "Error Doctor (11E)",
                subtitle = if (report != null) {
                    "Auto-fix loop: ${report.attemptNumber}/${ErrorDoctorEngine.MAX_ATTEMPTS} • Learned fixes: ${doctorFixes.size}"
                } else {
                    "Clear failure diagnosis"
                },
                trailing = { StatusPill(if (report?.exhaustedAutoLoop == true) "MAX_LOOPS" else "DIAGNOSED") }
            )
            Text(
                text = buildState.errorMessage ?: report?.summaryHinglish.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = StatusCrimson,
                modifier = Modifier.testTag("dev_studio_error_summary_text")
            )
            if (report != null) {
                if (report.errorLines.isNotEmpty()) {
                    Text("Detected e: file:// lines:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    report.errorLines.take(5).forEach { err ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${err.relativeWorkspacePath.ifBlank { err.filePath }}:${err.lineNumber} — ${err.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = StatusCrimson
                                )
                                Text(
                                    text = "Hinglish: ${err.hinglishExplanation}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                if (report.whatWentWrongBlock.isNotBlank()) {
                    Text(
                        text = report.whatWentWrongBlock.take(600),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = StatusAmber
                    )
                }
                val patch = report.patchProposal
                if (patch != null) {
                    Text(
                        text = "Proposed Patch (${patch.strategy}): ${patch.hinglishSummary}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeuralMint,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth().testTag("dev_studio_doctor_diff_box")
                    ) {
                        Text(
                            text = patch.diffPreview,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (patch != null && !report.exhaustedAutoLoop) {
                        Button(
                            onClick = onApplyDoctorPatchAndRebuild,
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_doctor_apply_btn")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Apply & Rebuild (${report.attemptNumber}/3)", maxLines = 1)
                        }
                    }
                    if (!report.exhaustedAutoLoop) {
                        FilledTonalButton(
                            onClick = onAskGeminiDoctor,
                            enabled = !buildState.isAskingGeminiDoctor,
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_doctor_ask_gemini_btn")
                        ) {
                            Icon(Icons.Default.Healing, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(if (buildState.isAskingGeminiDoctor) "Asking..." else "Ask Gemini Fix", maxLines = 1)
                        }
                    }
                }
                if (report.exhaustedAutoLoop) {
                    Text(
                        text = "Max 3 auto-fix loops poore ho chuke hain. Upar ki error summary ke hisaab se code edit karein ya logs check karein.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusAmber
                    )
                }
            }
        }
    }
}

@Composable
fun DevStudioKeystoreAndBuildHistorySection(
    buildState: DevApkBuildUiState,
    settings: TeenaSettings,
    builds: List<BuildRecordEntity>,
    onToggleAutoBump: (Boolean) -> Unit,
    onGenerateKeystore: () -> Unit,
    onExportKeystoreUri: (Uri) -> Unit,
    onReinstallRecord: (BuildRecordEntity) -> Unit,
    onDeleteRecord: (BuildRecordEntity) -> Unit
) {
    val exportKeystoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12")
    ) { uri ->
        if (uri != null) onExportKeystoreUri(uri)
    }

    var viewingLogsRecord by remember { mutableStateOf<BuildRecordEntity?>(null) }
    val dateFmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.US) }

    viewingLogsRecord?.let { rec ->
        AlertDialog(
            onDismissRequest = { viewingLogsRecord = null },
            title = { Text("Build Logs • v${rec.versionName} (${rec.versionCode})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Mode: ${rec.buildMode} • Status: ${rec.status}", style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                    if (rec.errorSummary.isNotBlank()) {
                        Text(rec.errorSummary, style = MaterialTheme.typography.bodySmall, color = StatusCrimson)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rec.logs.takeLast(1800),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingLogsRecord = null }) { Text("Close") }
            }
        )
    }

    NeuralCard(modifier = Modifier.testTag("dev_studio_keystore_card")) {
        SectionHeader(
            title = "Signing Keystore & Version Bump",
            subtitle = "PKCS12 key encrypted in Android Keystore",
            trailing = { StatusPill(if (buildState.keystoreStatus.isGenerated) "READY" else "PENDING") }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-bump versionCode / versionName", style = MaterialTheme.typography.labelLarge)
                Text("Current: v${settings.devVersionName} (${settings.devVersionCode})", style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
            }
            Switch(
                checked = settings.devAutoBumpVersion,
                onCheckedChange = onToggleAutoBump,
                modifier = Modifier.testTag("dev_studio_autobump_switch")
            )
        }
        Text(
            text = "Keystore Alias: ${buildState.keystoreStatus.alias} • Cert SHA-256: ${buildState.keystoreStatus.certSha256.take(28)}...",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().background(StatusAmber.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(16.dp))
            Text(
                text = buildState.keystoreStatus.warning,
                style = MaterialTheme.typography.labelSmall,
                color = StatusAmber
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onGenerateKeystore,
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_keystore_gen_btn")
            ) {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(if (buildState.keystoreStatus.isGenerated) "Verify Key" else "Init Keystore", maxLines = 1)
            }
            OutlinedButton(
                onClick = { exportKeystoreLauncher.launch("teena_devstudio_release.p12") },
                enabled = buildState.keystoreStatus.isGenerated,
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("dev_studio_keystore_export_btn")
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Backup Key", maxLines = 1)
            }
        }
    }

    NeuralCard(modifier = Modifier.testTag("dev_studio_build_history_card")) {
        SectionHeader(
            title = "APK Build History (${builds.size})",
            subtitle = "Time, mode, result, size, logs, re-install & delete"
        )
        if (builds.isEmpty()) {
            Text(
                text = "Koi APK build history abhi nahi hai. Upar 'Build APK' tap karein.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            builds.take(10).forEach { rec ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "v${rec.versionName} (${rec.versionCode}) • ${rec.buildMode}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            StatusPill(rec.status)
                        }
                        Text(
                            text = "${dateFmt.format(Date(rec.timestamp))} • Size: ${if (rec.apkSizeBytes > 0) DevStudioEngine.formatBytes(rec.apkSizeBytes) else "N/A"} • ${rec.elapsedMs / 1000L}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { viewingLogsRecord = rec },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) { Text("Logs", maxLines = 1) }

                            if (rec.apkLocalPath.isNotBlank()) {
                                FilledTonalButton(
                                    onClick = { onReinstallRecord(rec) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) { Text("Re-install", maxLines = 1) }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { onDeleteRecord(rec) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Build Record", tint = StatusCrimson)
                            }
                        }
                    }
                }
            }
        }
    }
}
