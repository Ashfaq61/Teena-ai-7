package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.FeatureStatus
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun DashboardScreen(
    viewModel: TeenaViewModel,
    onNavigate: (String) -> Unit
) {
    val slots by viewModel.providerSlots.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val tools by viewModel.marketplaceTools.collectAsStateWithLifecycle()
    val health by viewModel.featureHealth.collectAsStateWithLifecycle()
    val apkInfo by viewModel.apkInspection.collectAsStateWithLifecycle()
    val builds by viewModel.buildRecords.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val primarySlot = slots.firstOrNull { it.isPrimary } ?: slots.firstOrNull()
    val connectedCount = slots.count { it.lastTestStatus == "ACTIVE" || it.lastTestStatus == "READY" }
    val activeAgents = agents.count { it.healthStatus == "ACTIVE" }
    val failedTasks = agents.sumOf { it.tasksFailed }
    val activeTools = tools.count { it.isInstalled && it.activationStatus == "ACTIVE" }

    val activeCount = health.count { it.status == FeatureStatus.ACTIVE }
    val partialCount = health.count { it.status == FeatureStatus.PARTIAL }
    val blockedCount = health.count { it.status == FeatureStatus.BLOCKED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(MaterialTheme.shapes.large)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_neural),
                    contentDescription = "Teena AI Neural Command Banner",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x33060A14),
                                    Color(0xE6060A14)
                                )
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_teena_avatar),
                        contentDescription = "Teena AI Orb Avatar",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TEENA AI",
                            style = MaterialTheme.typography.headlineMedium,
                            color = NeuralCyan
                        )
                        Text(
                            text = "Autonomous Multi-Agent Core",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        StatusPill(
                            status = if (connectedCount > 0) "ACTIVE" else "PARTIAL"
                        )
                    }
                }
            }
        }

        // Real Telemetry Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "PROVIDERS READY",
                    value = "$connectedCount / ${slots.size}",
                    accent = NeuralCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "AGENTS HEALTHY",
                    value = "$activeAgents / ${agents.size}",
                    accent = NeuralMint,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "ACTIVE TOOLS",
                    value = "$activeTools / ${tools.size}",
                    accent = NeuralViolet,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "FAILED TASKS",
                    value = "$failedTasks",
                    accent = if (failedTasks == 0) NeuralMint else StatusCrimson,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Active Model & Build Summary Card
        item {
            NeuralCard {
                SectionHeader(
                    title = "Active Intelligence & Build",
                    trailing = {
                        StatusPill(primarySlot?.lastTestStatus ?: "UNTESTED")
                    }
                )
                Text(
                    text = "Primary Model: ${primarySlot?.selectedModel ?: "gemini-3.8-flash"} (${primarySlot?.label ?: "Gemini AI Logic"})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val lastBuild = builds.firstOrNull()
                Text(
                    text = "APK v${apkInfo?.versionName ?: "1.0"} (${apkInfo?.packageName ?: "com.aistudio.teenaai.qx7mzk"})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (lastBuild != null) {
                        "Last Verified Build: ${lastBuild.status} • SHA256 ${lastBuild.sha256Checksum.take(18)}..."
                    } else {
                        "APK Checksum: ${apkInfo?.apkSha256?.take(20) ?: "Tap Verify in Build Center"}..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NeuralCyan
                )
            }
        }

        // Quick Actions
        item {
            SectionHeader("Command Shortcuts", "Launch core Teena engines")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onNavigate("voice") },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("dash_voice_btn")
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Live Voice")
                }
                FilledTonalButton(
                    onClick = { onNavigate("chat") },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("dash_chat_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("AI Chat")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onNavigate("automation") },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("dash_automation_btn")
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Automation")
                }
                FilledTonalButton(
                    onClick = { viewModel.runLiveSelfDiagnosticSuite() },
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("dash_diagnostic_btn")
                ) {
                    Icon(Icons.Default.HealthAndSafety, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Run Self-Test")
                }
            }
        }

        // Feature Health Classification (Honest ACTIVE / PARTIAL / BLOCKED)
        item {
            SectionHeader(
                title = "Live Feature Health Matrix",
                subtitle = "ACTIVE: $activeCount  •  PARTIAL: $partialCount  •  BLOCKED: $blockedCount"
            )
        }

        items(health, key = { it.id }) { item ->
            val border = when (item.status) {
                FeatureStatus.ACTIVE -> NeuralMint.copy(alpha = 0.4f)
                FeatureStatus.PARTIAL -> StatusAmber.copy(alpha = 0.45f)
                FeatureStatus.BLOCKED, FeatureStatus.FAILED -> StatusCrimson.copy(alpha = 0.5f)
                FeatureStatus.NOT_IMPLEMENTED -> MaterialTheme.colorScheme.outline
            }
            NeuralCard(
                borderColor = border,
                modifier = Modifier.clickable {
                    when (item.id) {
                        "accessibility", "social_automation" -> onNavigate("automation")
                        "gemini_live" -> onNavigate("voice")
                        "ai_brain" -> onNavigate("api_manager")
                        "apk_build" -> onNavigate("build_center")
                        "notifications" -> onNavigate("notifications")
                        else -> onNavigate("permissions")
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    StatusPill(item.status.name)
                }
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
