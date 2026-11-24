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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    val advisors by viewModel.externalAdvisors.collectAsStateWithLifecycle()
    val health by viewModel.featureHealth.collectAsStateWithLifecycle()
    val apkInfo by viewModel.apkInspection.collectAsStateWithLifecycle()
    val builds by viewModel.buildRecords.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val primarySlot = slots.firstOrNull { it.isPrimary } ?: slots.firstOrNull()
    val verifiedCloudCount = slots.count { it.lastTestStatus == "ACTIVE" && it.providerType != "OFFLINE_LOCAL" }
    val activeAgents = agents.count { it.healthStatus == FeatureStatus.ACTIVE.name }
    val unverifiedAgents = agents.count { it.healthStatus == FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name }
    val activeTools = tools.count { it.isInstalled && it.activationStatus == FeatureStatus.ACTIVE.name }
    val readyAdvisors = advisors.count { it.isEnabled && it.status != FeatureStatus.BLOCKED.name }

    val activeCount = health.count { it.status == FeatureStatus.ACTIVE }
    val unverifiedCount = health.count { it.status == FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED }
    val partialCount = health.count { it.status == FeatureStatus.PARTIAL }
    val blockedCount = health.count { it.status == FeatureStatus.BLOCKED || it.status == FeatureStatus.FAILED }

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
                            text = "Honest Multi-Agent & Advisor Core",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        StatusPill(
                            status = when {
                                emergencyStopped -> "EMERGENCY_STOP"
                                activeCount > 0 && verifiedCloudCount > 0 -> "ACTIVE"
                                else -> FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name
                            }
                        )
                    }
                }
            }
        }

        // Emergency Stop & External AI Guardrail Card
        item {
            NeuralCard(
                borderColor = if (emergencyStopped) StatusCrimson else NeuralCyan.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (emergencyStopped) "Emergency Stop Engaged" else "Safety & Isolation Active",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (emergencyStopped) StatusCrimson else NeuralMint
                        )
                        Text(
                            text = "External AI Calls: ${if (settings.externalAiCallsEnabled) "ENABLED ($readyAdvisors advisor(s))" else "DISABLED"} • Screen/Web/Advisor text = Data Only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            if (emergencyStopped) viewModel.resumeFromEmergencyStop()
                            else viewModel.triggerEmergencyStop()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (emergencyStopped) NeuralMint else StatusCrimson,
                            contentColor = if (emergencyStopped) Color.Black else Color.White
                        ),
                        modifier = Modifier.testTag("dash_emergency_stop_btn")
                    ) {
                        Icon(
                            imageVector = if (emergencyStopped) Icons.Default.PlayArrow else Icons.Default.StopCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(if (emergencyStopped) "Resume" else "Emergency Stop")
                    }
                }
            }
        }

        // Honest Telemetry Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "PROBED PROVIDERS",
                    value = "$verifiedCloudCount / ${slots.size}",
                    accent = NeuralCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "AGENTS VERIFIED",
                    value = "$activeAgents / ${agents.size}",
                    accent = if (activeAgents > 0) NeuralMint else StatusAmber,
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
                    label = "VERIFIED TOOLS",
                    value = "$activeTools / ${tools.size}",
                    accent = NeuralViolet,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "UNVERIFIED AGENTS",
                    value = "$unverifiedAgents",
                    accent = if (unverifiedAgents == 0) NeuralMint else StatusAmber,
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
                    text = "Primary Model: ${primarySlot?.selectedModel?.ifBlank { "Pending ModelRegistry probe" } ?: "Not selected"} (${primarySlot?.label ?: "Configure in API Manager"})",
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

        // Honest Feature Health Classification
        item {
            SectionHeader(
                title = "Live Feature Health Matrix",
                subtitle = "ACTIVE: $activeCount • UNVERIFIED: $unverifiedCount • PARTIAL: $partialCount • BLOCKED: $blockedCount"
            )
        }

        items(health, key = { it.id }) { item ->
            val border = when (item.status) {
                FeatureStatus.ACTIVE -> NeuralMint.copy(alpha = 0.45f)
                FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED -> NeuralCyan.copy(alpha = 0.45f)
                FeatureStatus.PARTIAL -> StatusAmber.copy(alpha = 0.45f)
                FeatureStatus.BLOCKED, FeatureStatus.FAILED -> StatusCrimson.copy(alpha = 0.5f)
                FeatureStatus.NOT_IMPLEMENTED -> MaterialTheme.colorScheme.outline
            }
            NeuralCard(
                borderColor = border,
                modifier = Modifier.clickable {
                    when (item.id) {
                        "accessibility", "social_automation", "camera_media", "phone_calls", "maps_location" -> onNavigate("automation")
                        "gemini_live" -> onNavigate("voice")
                        "ai_brain" -> onNavigate("api_manager")
                        "apk_build" -> onNavigate("build_center")
                        "notifications" -> onNavigate("notifications")
                        "multi_agent" -> onNavigate("agents")
                        "self_coding" -> onNavigate("marketplace")
                        "github_hf" -> onNavigate("research")
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
