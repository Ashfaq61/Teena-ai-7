package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.security.RepoRiskLevel
import com.example.core.security.RepoRiskReport
import com.example.data.local.CodeVaultEntryEntity
import com.example.data.remote.GitHubRepoDetail
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.VaultInspectUiState

@Composable
fun GitHubRepoRiskCard(
    repo: GitHubRepoDetail,
    report: RepoRiskReport?,
    isBusy: Boolean,
    onInspectCode: () -> Unit,
    onSaveToVault: () -> Unit,
    onProposeTool: () -> Unit,
    onOpenUrl: () -> Unit
) {
    val activeReport = report
    val level = activeReport?.riskLevel ?: RepoRiskLevel.NOT_DONE
    val borderColor = when (level) {
        RepoRiskLevel.KHATARNAAK -> StatusCrimson
        RepoRiskLevel.EHTIYAT -> StatusAmber
        RepoRiskLevel.SAFE -> NeuralMint
        RepoRiskLevel.NOT_DONE -> NeuralCyan.copy(alpha = 0.45f)
    }

    NeuralCard(borderColor = borderColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(repo.fullName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "★ ${repo.stars} • ${repo.language} • License: ${repo.licenseSpdx} • Owner: ${repo.ownerLogin.ifBlank { "unknown" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeuralCyan
                )
            }
            val pillLabel = when (level) {
                RepoRiskLevel.NOT_DONE -> "NOT DONE"
                RepoRiskLevel.SAFE -> "SAFE (${activeReport?.riskScore ?: 0}/100)"
                RepoRiskLevel.EHTIYAT -> "EHTIYAT (${activeReport?.riskScore ?: 0}/100)"
                RepoRiskLevel.KHATARNAAK -> "KHATARNAAK"
            }
            StatusPill(pillLabel)
        }

        Text(repo.description, style = MaterialTheme.typography.bodySmall)

        // Honest Code Review Status + Roman Urdu Risk Banner
        if (activeReport == null || !activeReport.codeReviewed) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = StatusAmber.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Code review not done", tint = StatusAmber, modifier = Modifier.size(18.dp))
                    Text(
                        "Review Status: NOT DONE — Asli files abhi scan nahi huin. Bina asli code padhe ise 'Safe' nahi kaha ja sakta.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusAmber
                    )
                }
            }
        } else {
            val bannerColor = when (level) {
                RepoRiskLevel.KHATARNAAK -> StatusCrimson
                RepoRiskLevel.EHTIYAT -> StatusAmber
                RepoRiskLevel.SAFE -> NeuralMint
                RepoRiskLevel.NOT_DONE -> StatusAmber
            }
            val bannerIcon = when (level) {
                RepoRiskLevel.KHATARNAAK -> Icons.Default.Block
                RepoRiskLevel.EHTIYAT -> Icons.Default.Warning
                RepoRiskLevel.SAFE -> Icons.Default.VerifiedUser
                RepoRiskLevel.NOT_DONE -> Icons.Default.Shield
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = bannerColor.copy(alpha = 0.18f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("risk_banner_${repo.fullName}")
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(bannerIcon, contentDescription = level.label, tint = bannerColor, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = activeReport.headlineBanner,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = bannerColor
                        )
                        Text(
                            text = "Code Review: ${activeReport.reviewStatusLabel} • Commit: ${activeReport.commitSha.take(12)} • Owner Age: ${activeReport.ownerAccountAgeDays?.let { "${it}d" } ?: "N/A"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (activeReport.gplWarningRomanUrdu.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = StatusAmber.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = activeReport.gplWarningRomanUrdu,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusAmber,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            activeReport.findings.take(4).forEach { f ->
                val fColor = when (f.severity) {
                    RepoRiskLevel.KHATARNAAK -> StatusCrimson
                    RepoRiskLevel.EHTIYAT -> StatusAmber
                    else -> NeuralMint
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "• [${f.severity.label}] ${f.titleRomanUrdu}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = fColor
                    )
                    Text(
                        text = "${f.detailRomanUrdu} Mashwara: ${f.recommendationRomanUrdu}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = activeReport.passiveDataNotice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Action row: Khatarnaak blocks Vault & hides/disables Propose!
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onInspectCode,
                enabled = !isBusy,
                modifier = Modifier.testTag("inspect_repo_btn_${repo.fullName}")
            ) {
                Text(if (activeReport?.codeReviewed == true) "Re-Inspect Code" else "Inspect Real Code")
            }

            if (activeReport != null && activeReport.codeReviewed && activeReport.riskLevel != RepoRiskLevel.KHATARNAAK) {
                Button(
                    onClick = onSaveToVault,
                    enabled = !isBusy && activeReport.vaultSaveAllowed,
                    modifier = Modifier.testTag("save_vault_btn_${repo.fullName}")
                ) {
                    Text("Save Vault")
                }
            }

            OutlinedButton(onClick = onOpenUrl) {
                Text("Open")
            }
        }

        if (activeReport != null && activeReport.codeReviewed) {
            if (activeReport.riskLevel == RepoRiskLevel.KHATARNAAK) {
                Text(
                    text = "Vault Save aur Propose sakhti se BAND hain — sirf Khatarnaak warning aur report dikhai ja rahi hai.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusCrimson,
                    fontWeight = FontWeight.Bold
                )
            } else if (activeReport.proposeAllowed) {
                OutlinedButton(
                    onClick = onProposeTool,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("propose_tool_btn_${repo.fullName}")
                ) {
                    Text("Propose New Kotlin Tool (Vault Untouched)")
                }
            } else {
                Text(
                    text = "Propose band hai: license (${activeReport.licenseSpdx}) allow nahi karta.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusAmber
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OriginalCodeVaultTab(
    entries: List<CodeVaultEntryEntity>,
    inspectState: VaultInspectUiState?,
    isBusy: Boolean,
    onOpenAndVerify: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onCloseInspect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.5f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Read-Only Vault", tint = NeuralCyan)
                    SectionHeader(
                        title = "Original Code Vault (Bina Badle)",
                        subtitle = "Private storage mein exact Commit-SHA ZIP + metadata.json + LICENSE. Har open par SHA-256 check hota hai. Execution & compile hamesha BAND hain."
                    )
                }
            }
        }

        if (inspectState != null) {
            item {
                val border = if (inspectState.verified) NeuralMint else StatusCrimson
                NeuralCard(borderColor = border) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Read-Only Vault Viewer: ${inspectState.entry.repoFullName}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "Commit: ${inspectState.entry.commitSha} • License: ${inspectState.entry.licenseSpdx}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeuralCyan
                            )
                        }
                        StatusPill(if (inspectState.verified) "VERIFIED" else "FAILED")
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = (if (inspectState.verified) NeuralMint else StatusCrimson).copy(alpha = 0.16f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = inspectState.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (inspectState.verified) NeuralMint else StatusCrimson,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    if (!inspectState.verified) {
                        Text(
                            "Tamper Alert: SHA-256 verification fail hone ki wajah se is Vault snapshot ka read-only viewer BLOCK kar diya gaya hai.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusCrimson
                        )
                    } else {
                        Text(
                            "Select file to view read-only (${inspectState.files.size} entries — execution disabled):",
                            style = MaterialTheme.typography.labelSmall
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            inspectState.files.take(24).forEach { path ->
                                val shortLabel = path.substringAfter("/", path)
                                FilledTonalButton(
                                    onClick = { onSelectFile(path) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(shortLabel.take(28), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (inspectState.selectedFileContent.isNotBlank()) {
                            Text(
                                "File (Read-Only): ${inspectState.selectedFilePath}",
                                style = MaterialTheme.typography.labelMedium,
                                color = NeuralMint
                            )
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                            ) {
                                Text(
                                    text = inspectState.selectedFileContent,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                )
                            }
                        }
                    }

                    OutlinedButton(onClick = onCloseInspect) {
                        Text("Close Viewer")
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                NeuralCard {
                    Text(
                        "Abhi Vault khaali hai. GitHub tab mein repo search karein -> 'Inspect Real Code' chalayein -> Safe/Ehtiyat verified repo ko 'Save Vault' se mehfooz karein.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(entries, key = { it.vaultId }) { entry ->
                var showAttribution by remember(entry.vaultId) { mutableStateOf(false) }
                val isFailed = entry.verificationStatus.equals("FAILED", ignoreCase = true)

                NeuralCard(borderColor = if (isFailed) StatusCrimson else NeuralMint.copy(alpha = 0.45f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.repoFullName, style = MaterialTheme.typography.titleMedium)
                            Text(entry.repoUrl, style = MaterialTheme.typography.labelSmall, color = NeuralCyan)
                        }
                        StatusPill(entry.verificationStatus)
                    }

                    Text("Commit SHA: ${entry.commitSha}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "License: ${entry.licenseSpdx} • Risk: ${entry.riskLevel} (${entry.riskScore}/100) • Saved: ${entry.downloadedAtIso}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "ZIP SHA-256: ${entry.zipSha256}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = NeuralMint
                    )
                    Text(
                        "Status: ${entry.verificationMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFailed) StatusCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (entry.gplWarning.isNotBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = StatusAmber.copy(alpha = 0.14f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = entry.gplWarning,
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusAmber,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenAndVerify(entry.vaultId) },
                            enabled = !isBusy,
                            colors = if (isFailed) ButtonDefaults.buttonColors(containerColor = StatusCrimson) else ButtonDefaults.buttonColors(),
                            modifier = Modifier.testTag("verify_open_vault_${entry.vaultId}")
                        ) {
                            Text("Open & Verify SHA-256")
                        }
                        FilledTonalButton(onClick = { showAttribution = !showAttribution }) {
                            Text(if (showAttribution) "Hide License" else "License & Metadata")
                        }
                    }

                    if (showAttribution) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${entry.attributionText}\n\nMetadata File: ${entry.metadataFilePath}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
