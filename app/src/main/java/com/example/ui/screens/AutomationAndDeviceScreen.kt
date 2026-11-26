package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.data.local.FeatureStatus
import com.example.system.CameraVideoTarget
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.SocialTaskControl
import com.example.ai.SocialTaskKind
import com.example.ai.SocialTaskStatus
import com.example.system.SocialPlatform
import com.example.system.TeenaAccessibilityService
import com.example.system.TeenaFaceDetectionEngine
import com.example.system.TransientFaceDetectionState
import com.example.ui.components.FaceDetectionOverlay
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel
import java.util.concurrent.Executors

@Composable
fun AutomationAndDeviceScreen(
    viewModel: TeenaViewModel,
    onNavigateToChat: () -> Unit,
    onOpenMaps: () -> Unit = {}
) {
    val requestedTab by viewModel.requestedAutomationTab.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(requestedTab) }
    LaunchedEffect(requestedTab) {
        selectedTab = requestedTab
    }
    val tabs = listOf("Screen UI", "Social (6)", "Phone & Maps", "Camera & Media")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = {
                        selectedTab = idx
                        viewModel.setAutomationTab(idx)
                    },
                    modifier = Modifier.testTag("automation_tab_$idx"),
                    text = { Text(title, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }

        when (selectedTab) {
            0 -> AccessibilityTab(viewModel)
            1 -> SocialAutomationTab(viewModel)
            2 -> PhoneAndMapsTab(viewModel, onOpenMaps)
            3 -> CameraAndMediaTab(viewModel, onNavigateToChat)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessibilityTab(viewModel: TeenaViewModel) {
    val context = LocalContext.current
    val isBound by viewModel.accessibilityConnected.collectAsStateWithLifecycle()
    val snapshot by viewModel.screenSnapshot.collectAsStateWithLifecycle()
    val logs by viewModel.automationLog.collectAsStateWithLifecycle()
    val screenLogs by viewModel.screenAutomationLogs.collectAsStateWithLifecycle()
    val screenWatchState by viewModel.screenWatchState.collectAsStateWithLifecycle()
    val pendingScreenConfirm by viewModel.pendingScreenConfirmation.collectAsStateWithLifecycle()
    val screenVisionAnswer by viewModel.screenVisionAnswer.collectAsStateWithLifecycle()
    val screenGoalResult by viewModel.screenGoalResult.collectAsStateWithLifecycle()
    val providerSlots by viewModel.providerSlots.collectAsStateWithLifecycle()

    val hasGeminiKey by produceState(initialValue = false, providerSlots) {
        value = viewModel.container.aiBrain.hasPrimaryGeminiApiKey()
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isScreenshotApiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val blockedReasons = buildList {
        if (!settings.directAccessibilityAgentEnabled) add("Direct Accessibility Agent Settings mein OFF hai")
        if (!isBound) add("Teena AccessibilityService Android Settings mein enable nahi hai")
        if (!isScreenshotApiSupported) add("Screen screenshot (takeScreenshot) ke liye Android 11+ (API 30) zaroori hai")
        if (!hasGeminiKey) add("Gemini API key missing (API Manager mein configure karein)")
    }
    val honestStatus = when {
        blockedReasons.isNotEmpty() -> FeatureStatus.BLOCKED.name
        TeenaAccessibilityService.runtimeVerifiedOnDevice -> FeatureStatus.ACTIVE.name
        else -> FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name
    }

    var visionQuestion by remember { mutableStateOf("Screen par kya hai aur kaun sa app khula hai?") }
    var agentGoal by remember { mutableStateOf("") }
    var tapTarget by remember { mutableStateOf("") }
    var typePayload by remember { mutableStateOf("") }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_automation_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Android Screen Vision & Automation",
                    subtitle = "Gemini Vision + Accessibility UI tree (Screenshot strictly RAM-only)",
                    trailing = {
                        StatusPill(
                            status = honestStatus,
                            modifier = Modifier.testTag("screen_automation_status_pill")
                        )
                    }
                )
                if (blockedReasons.isNotEmpty()) {
                    Text(
                        text = "BLOCKED: ${blockedReasons.joinToString("; ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusCrimson,
                        modifier = Modifier.testTag("screen_blocked_reason_text")
                    )
                } else if (!TeenaAccessibilityService.runtimeVerifiedOnDevice) {
                    Text(
                        text = "IMPLEMENTED NOT DEVICE-VERIFIED: Accessibility bound hai aur Gemini key mojood hai; pehla asli screen action ya vision call chalne par ACTIVE hoga.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusAmber
                    )
                }

                if (!isBound) {
                    Button(
                        onClick = { TeenaAccessibilityService.openAccessibilitySettings(context) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("enable_accessibility_btn")
                    ) {
                        Text("Open Accessibility Settings", maxLines = 1, softWrap = false)
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.refreshScreenSnapshot() },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text("Capture Screen Tree", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { viewModel.runAccessibilityAction { it.goBackVerified() } },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text("Back", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { viewModel.runAccessibilityAction { it.goHomeVerified() } },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text("Home", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader(
                    title = "Screen Vision (Dekho aur Batao)",
                    subtitle = "Screenshot + UI tree sirf Gemini Vision model ko bhejta hai",
                    trailing = if (screenWatchState.isWatching) {
                        { StatusPill("RUNNING") }
                    } else null
                )
                OutlinedTextField(
                    value = visionQuestion,
                    onValueChange = { visionQuestion = it },
                    label = { Text("Screen ke bare mein sawal poochein") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("screen_question_input")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "Screen par kya hai?",
                        "Ye button kya karta hai?",
                        "Kaun sa app khula hai?"
                    ).forEach { preset ->
                        FilterChip(
                            selected = visionQuestion == preset,
                            onClick = { visionQuestion = preset },
                            label = { Text(preset, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                Button(
                    onClick = { viewModel.describeScreenWithVision(visionQuestion) },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("screen_describe_btn")
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Dekho aur Batao", maxLines = 1, softWrap = false)
                }
                if (screenVisionAnswer.isNotBlank()) {
                    Text(
                        text = screenVisionAnswer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                    )
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader(
                    title = "Autonomous Screen Agent",
                    subtitle = "Observe → Think → Act → Verify (Max 25 steps, 3 min, Secure Guard)",
                    trailing = {
                        if (screenWatchState.isWatching) {
                            StatusPill("THINKING")
                        }
                    }
                )
                if (screenWatchState.isWatching) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusAmber.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Teena screen dekh rahi hai • Step ${screenWatchState.stepNumber}/${screenWatchState.maxSteps}",
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                OutlinedTextField(
                    value = agentGoal,
                    onValueChange = { agentGoal = it },
                    label = { Text("Goal (e.g. YouTube par Urdu naat search karo)") },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("screen_goal_input")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.runScreenAgentGoal(agentGoal) },
                        enabled = agentGoal.isNotBlank(),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("screen_goal_run_btn")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Goal Chalao", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopScreenAgentGoal() },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("screen_goal_stop_btn")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Stop", maxLines = 1, softWrap = false)
                    }
                }
                pendingScreenConfirm?.let { pending ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusAmber.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(pending.title, style = MaterialTheme.typography.labelLarge, color = StatusAmber)
                        Text(pending.exactActionDescription, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.respondToPendingScreenConfirmation(true) },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Text("Authorize", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = { viewModel.respondToPendingScreenConfirmation(false) },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Text("Cancel", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                if (screenGoalResult.isNotBlank()) {
                    Text(
                        text = screenGoalResult,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                            .testTag("screen_goal_output_text")
                    )
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Verified Gesture & Input Pad")
                OutlinedTextField(
                    value = tapTarget,
                    onValueChange = { tapTarget = it },
                    label = { Text("Button text or label to tap / long-press") },
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.runAccessibilityAction { it.tapByTextOrDesc(tapTarget) } },
                        enabled = tapTarget.isNotBlank(),
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) { Text("Tap", maxLines = 1, softWrap = false) }
                    FilledTonalButton(
                        onClick = { viewModel.runAccessibilityAction { it.longPressByText(tapTarget) } },
                        enabled = tapTarget.isNotBlank(),
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) { Text("Long Press", maxLines = 1, softWrap = false) }
                }
                OutlinedTextField(
                    value = typePayload,
                    onValueChange = { typePayload = it },
                    label = { Text("Text to type into active editable field") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.runAccessibilityAction { it.typeIntoFocusedOrEditable(typePayload) } },
                    enabled = typePayload.isNotBlank(),
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) { Text("Type & Verify", maxLines = 1, softWrap = false) }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("UP", "DOWN", "LEFT", "RIGHT").forEach { dir ->
                        OutlinedButton(
                            onClick = { viewModel.runAccessibilityAction { it.swipeDirection(dir) } },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) { Text(dir, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Active Window Hierarchy")
                if (snapshot == null) {
                    Text("No active UI snapshot captured yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Package: ${snapshot?.packageName}", style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                    Text(
                        "Visible text (${snapshot?.visibleText?.size ?: 0}): ${snapshot?.visibleText?.take(12)?.joinToString(" • ").orEmpty()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Screen Step Log (Text Only — No Screenshots Saved)",
                subtitle = "Sirf step timestamp, package, samajh, action aur verification save hota hai",
                trailing = if (screenLogs.isNotEmpty()) {
                    {
                        OutlinedButton(
                            onClick = { viewModel.clearScreenAutomationLogs() },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text("Clear", maxLines = 1, softWrap = false)
                        }
                    }
                } else null
            )
            if (screenLogs.isEmpty()) {
                NeuralCard {
                    Text(
                        "Abhi tak koi Screen Vision ya Screen Agent step log nahi hua.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(screenLogs, key = { "slog_${it.id}" }) { entry ->
            NeuralCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Step #${entry.stepNumber} • ${entry.actionTaken}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    StatusPill(entry.verificationResult)
                }
                Text(
                    "${timeFormatter.format(Date(entry.screenshotTimestamp))} • ${entry.packageName.ifBlank { "system" }}${if (entry.modelUsed.isNotBlank()) " • ${entry.modelUsed}" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeuralCyan
                )
                Text("Samajh: ${entry.understoodSummary}", style = MaterialTheme.typography.bodySmall)
                if (entry.details.isNotBlank()) {
                    Text(
                        entry.details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(logs) { res ->
            NeuralCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(res.action, style = MaterialTheme.typography.labelLarge)
                    StatusPill(if (res.verified) "VERIFIED" else if (res.success) "PARTIAL" else "FAILED")
                }
                Text(res.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SocialAutomationTab(viewModel: TeenaViewModel) {
    val installedApps = remember { viewModel.container.socialEngine.checkInstalledApps() }
    val taskState by viewModel.socialTaskState.collectAsStateWithLifecycle()
    var selectedPlatform by remember { mutableStateOf(SocialPlatform.INSTAGRAM) }
    var selectedTaskKind by remember { mutableStateOf(SocialTaskKind.CREATE_POST) }
    var searchQuery by remember { mutableStateOf("") }
    var draftText by remember { mutableStateOf("") }
    var phoneTarget by remember { mutableStateOf("") }
    var manualStepFirst by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("social_automation_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Social & WhatsApp Task State Machine",
                    subtitle = "Safety Lock: Hamesha CONFIRM ke baad hi send/post hoga — kabhi silent auto-submit nahi",
                    trailing = {
                        StatusPill(
                            status = taskState.status.name,
                            modifier = Modifier.testTag("social_task_status_pill")
                        )
                    }
                )
                Text(
                    text = "Active: ${taskState.platform.displayName} • ${taskState.taskKind.displayLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeuralCyan
                )
                Text(
                    text = taskState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("social_task_status_message")
                )
                if (taskState.confirmationPrompt.isNotBlank() &&
                    taskState.status == SocialTaskStatus.WAITING_FOR_CONFIRMATION
                ) {
                    Text(
                        text = "Confirm Action: ${taskState.confirmationPrompt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusAmber,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusAmber.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    )
                }
                if (taskState.resolvedContactInfo.isNotBlank()) {
                    Text(
                        text = taskState.resolvedContactInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralMint
                    )
                }
                if (taskState.errorMessage.isNotBlank()) {
                    Text(
                        text = taskState.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusCrimson
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.controlSocialTask(SocialTaskControl.CONFIRM) },
                        enabled = taskState.status == SocialTaskStatus.WAITING_FOR_CONFIRMATION,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("social_task_confirm_btn")
                    ) {
                        Text("CONFIRM", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { viewModel.controlSocialTask(SocialTaskControl.REJECT) },
                        enabled = taskState.status == SocialTaskStatus.WAITING_FOR_CONFIRMATION ||
                            taskState.status == SocialTaskStatus.WAITING_FOR_USER ||
                            taskState.status == SocialTaskStatus.RUNNING,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("social_task_reject_btn")
                    ) {
                        Text("REJECT", maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.controlSocialTask(SocialTaskControl.RESUME) },
                        enabled = taskState.status == SocialTaskStatus.WAITING_FOR_USER,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("social_task_resume_btn")
                    ) {
                        Text("RESUME", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { viewModel.resetSocialTask() },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("social_task_reset_btn")
                    ) {
                        Text("Reset", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Stage Social Post / Reel / Story or WhatsApp Task")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SocialTaskKind.entries.forEach { kind ->
                        FilterChip(
                            selected = selectedTaskKind == kind,
                            onClick = {
                                selectedTaskKind = kind
                                if (kind == SocialTaskKind.WHATSAPP_MESSAGE) {
                                    selectedPlatform = SocialPlatform.WHATSAPP
                                } else if (selectedPlatform == SocialPlatform.WHATSAPP) {
                                    selectedPlatform = SocialPlatform.INSTAGRAM
                                }
                            },
                            modifier = Modifier.testTag("social_kind_${kind.name.lowercase()}"),
                            label = { Text(kind.displayLabel, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                if (selectedTaskKind != SocialTaskKind.WHATSAPP_MESSAGE) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            SocialPlatform.INSTAGRAM,
                            SocialPlatform.FACEBOOK,
                            SocialPlatform.TIKTOK,
                            SocialPlatform.YOUTUBE,
                            SocialPlatform.TELEGRAM
                        ).forEach { plat ->
                            FilterChip(
                                selected = selectedPlatform == plat,
                                onClick = { selectedPlatform = plat },
                                label = { Text(plat.displayName, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    FilterChip(
                        selected = manualStepFirst,
                        onClick = { manualStepFirst = !manualStepFirst },
                        label = { Text("Wait for manual media pick first (WAITING_FOR_USER)", maxLines = 1) }
                    )
                } else {
                    OutlinedTextField(
                        value = phoneTarget,
                        onValueChange = { phoneTarget = it },
                        label = { Text("WhatsApp Contact / Relation (ammi, bhai) or Phone") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("whatsapp_task_contact_input")
                    )
                }

                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    label = {
                        Text(
                            if (selectedTaskKind == SocialTaskKind.WHATSAPP_MESSAGE) "WhatsApp message text"
                            else "Post / Reel / Story caption"
                        )
                    },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("social_task_text_input")
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (selectedTaskKind == SocialTaskKind.WHATSAPP_MESSAGE) {
                                viewModel.startWhatsAppTask(phoneTarget, draftText)
                            } else {
                                viewModel.startSocialTask(
                                    platform = selectedPlatform,
                                    taskKind = selectedTaskKind,
                                    caption = draftText,
                                    requireManualUserPrep = manualStepFirst
                                )
                            }
                        },
                        enabled = draftText.isNotBlank(),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("social_stage_task_btn")
                    ) {
                        Text(
                            if (selectedTaskKind == SocialTaskKind.WHATSAPP_MESSAGE) "Stage WhatsApp Task"
                            else "Stage ${selectedTaskKind.displayLabel}",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestAuthorizedSocialSend(selectedPlatform, draftText, phoneTarget) },
                        enabled = draftText.isNotBlank(),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("social_authorize_send_btn")
                    ) {
                        Text("Quick Composer Dialog", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        item {
            SectionHeader("Supported Social Platforms", "Launch, search, and open authorized composers")
        }

        items(installedApps) { status ->
            NeuralCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(status.platform.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            status.activePackage ?: "Web portal fallback",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(if (status.installed) "ACTIVE" else "PARTIAL")
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(
                        onClick = {
                            selectedPlatform = status.platform
                            viewModel.container.socialEngine.openPlatform(status.platform)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text("Open")
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Search on ${selectedPlatform.displayName}")
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search topic or channel on ${selectedPlatform.displayName}") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.container.socialEngine.searchPlatformContent(selectedPlatform, searchQuery) },
                    enabled = searchQuery.isNotBlank(),
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) { Text("Search in ${selectedPlatform.displayName}") }
            }
        }
    }
}

@Composable
private fun PhoneAndMapsTab(
    viewModel: TeenaViewModel,
    onOpenMaps: () -> Unit = {}
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val callLogs by viewModel.callLogs.collectAsStateWithLifecycle()
    val liveCall by viewModel.liveCallInfo.collectAsStateWithLifecycle()
    val location by viewModel.currentLocation.collectAsStateWithLifecycle()
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var contactQuery by remember { mutableStateOf("") }
    var dialNumber by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var speakerOn by remember { mutableStateOf(viewModel.container.phoneEngine.isSpeakerphoneOn()) }

    val phonePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemHealth()
        viewModel.loadContactsAndCallLogs(contactQuery)
    }

    val locPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemHealth()
        viewModel.fetchDeviceLocation()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Telecom & InCall Control",
                    subtitle = "Default Dialer=${perms.isDefaultDialer} • Call=${perms.callPhone}",
                    trailing = { StatusPill(liveCall.stateLabel) }
                )
                if (liveCall.number.isNotBlank()) {
                    Text("Active Call: ${liveCall.number} (${liveCall.stateLabel})", style = MaterialTheme.typography.titleSmall, color = NeuralCyan)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { viewModel.container.phoneEngine.answerIncomingCall() }) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Answer")
                    }
                    FilledTonalButton(onClick = { viewModel.container.phoneEngine.rejectOrEndCall() }) {
                        Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Reject/End")
                    }
                    OutlinedButton(onClick = {
                        speakerOn = !speakerOn
                        viewModel.container.phoneEngine.setSpeakerphone(speakerOn)
                    }) {
                        Text(if (speakerOn) "Speaker ON" else "Speaker OFF")
                    }
                }
                OutlinedTextField(
                    value = dialNumber,
                    onValueChange = { dialNumber = it },
                    label = { Text("Phone number to call") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.requestAuthorizedCall(dialNumber) },
                        enabled = dialNumber.isNotBlank()
                    ) { Text("Call Number") }
                    OutlinedButton(onClick = {
                        phonePermLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.ANSWER_PHONE_CALLS
                            )
                        )
                    }) { Text("Sync Contacts & Logs") }
                }
            }
        }

        if (contacts.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = contactQuery,
                    onValueChange = {
                        contactQuery = it
                        viewModel.loadContactsAndCallLogs(it)
                    },
                    label = { Text("Filter contacts") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            items(contacts.take(8)) { c ->
                NeuralCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(c.phoneNumber, style = MaterialTheme.typography.labelMedium, color = NeuralCyan)
                        }
                        FilledTonalButton(onClick = { viewModel.requestAuthorizedCall(c.phoneNumber, c.name) }) {
                            Text("Call")
                        }
                    }
                }
            }
        }

        if (callLogs.isNotEmpty()) {
            item { SectionHeader("Recent Call History") }
            items(callLogs.take(5)) { log ->
                NeuralCard {
                    Text("${log.cachedName.ifBlank { log.number }} • ${log.typeLabel} (${log.durationSec}s)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Google Maps & Location
        item {
            NeuralCard {
                SectionHeader("Google Maps & GPS Location")
                Text(
                    location?.let { "Lat: ${"%.5f".format(it.latitude)}, Lng: ${"%.5f".format(it.longitude)} (±${it.accuracyMeters.toInt()}m)" }
                        ?: "Location not fetched yet.",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeuralMint
                )
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text("Destination or nearby place (e.g. 'Nearest pharmacy')") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (perms.fineLocation) viewModel.fetchDeviceLocation()
                        else locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Get GPS")
                    }
                    Button(
                        onClick = { viewModel.container.mapsEngine.launchNavigation(destination) },
                        enabled = destination.isNotBlank()
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Navigate")
                    }
                    FilledTonalButton(
                        onClick = { viewModel.container.mapsEngine.searchNearbyPlaces(destination, location) },
                        enabled = destination.isNotBlank()
                    ) { Text("Nearby") }
                }
                OutlinedButton(
                    onClick = onOpenMaps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("automation_open_map_html_btn")
                ) {
                    Text("Open Interactive Map (map.html WebView)")
                }
            }
        }

        // Optional Device Admin Receiver (Direct Mode, manual activation only)
        item {
            NeuralCard {
                SectionHeader(
                    title = "Optional Device Admin (Direct Mode)",
                    subtitle = "User must enable manually in Android Settings — never auto-activated",
                    trailing = {
                        StatusPill(
                            when {
                                perms.deviceAdminActive -> "ACTIVE"
                                settings.directDeviceAdminEnabled -> "PARTIAL"
                                else -> "OPTIONAL"
                            }
                        )
                    }
                )
                Text(
                    text = if (perms.deviceAdminActive) {
                        "Teena Device Admin is active. You can lock screen or revoke access anytime."
                    } else {
                        "Currently inactive. Enable option and confirm manually in Android Device Admin prompt if desired."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!perms.deviceAdminActive) {
                        Button(
                            onClick = {
                                viewModel.toggleDirectDeviceAdmin(true)
                                viewModel.requestEnableDeviceAdmin()
                            },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("device_admin_enable_btn")
                        ) {
                            Text("Enable Manually")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.lockScreenWithDeviceAdmin() },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("device_admin_lock_btn")
                        ) {
                            Text("Lock Screen")
                        }
                        OutlinedButton(
                            onClick = { viewModel.disableDeviceAdmin() },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("device_admin_disable_btn")
                        ) {
                            Text("Disable Admin")
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraAndMediaTab(
    viewModel: TeenaViewModel,
    onNavigateToChat: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
    val torchOn by viewModel.torchEnabled.collectAsStateWithLifecycle()

    var useFrontCamera by remember { mutableStateOf(false) }
    var cameraBound by remember { mutableStateOf(false) }
    var volumeSlider by remember { mutableFloatStateOf(viewModel.container.cameraMediaEngine.getMediaVolumePercent().toFloat()) }
    var brightnessSlider by remember { mutableFloatStateOf(viewModel.container.cameraMediaEngine.getSystemBrightnessPercent().toFloat()) }
    var cameraFeedback by remember { mutableStateOf("") }
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var lastVideoUri by remember { mutableStateOf<Uri?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    var faceDetectionEnabled by remember { mutableStateOf(false) }
    var faceOverlayState by remember { mutableStateOf(TransientFaceDetectionState()) }

    val faceEngine = remember(context) { TeenaFaceDetectionEngine(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val previewView = remember { PreviewView(context) }

    val camPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.refreshSystemHealth()
        val camGranted = result[Manifest.permission.CAMERA] == true
        if (!camGranted) {
            faceDetectionEnabled = false
            faceOverlayState = TransientFaceDetectionState(
                enabled = false,
                permissionGranted = false,
                statusNote = "Camera permission denied — face detection stays disabled."
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            faceEngine.closeDetector()
            faceOverlayState = TransientFaceDetectionState()
            runCatching { analysisExecutor.shutdown() }
        }
    }

    LaunchedEffect(activeRecording != null) {
        if (activeRecording != null) {
            recordingSeconds = 0L
            while (activeRecording != null) {
                delay(1000L)
                recordingSeconds++
            }
        }
    }

    if (perms.camera) {
        DisposableEffect(useFrontCamera, faceDetectionEnabled, lifecycleOwner) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val executor = ContextCompat.getMainExecutor(context)
            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    provider.unbindAll()

                    if (faceDetectionEnabled && activeRecording == null && TeenaFaceDetectionEngine.hasExplicitCameraPermission(context)) {
                        imageAnalysis.setAnalyzer(
                            analysisExecutor,
                            faceEngine.createAnalyzer(
                                isFrontCameraProvider = { useFrontCamera },
                                isEnabledProvider = { faceDetectionEnabled }
                            ) { nextState ->
                                faceOverlayState = nextState
                            }
                        )
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, imageAnalysis)
                    } else {
                        imageAnalysis.clearAnalyzer()
                        faceOverlayState = TransientFaceDetectionState(permissionGranted = true)
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, videoCapture)
                    }
                    cameraBound = true
                }.onFailure { e ->
                    cameraBound = false
                    cameraFeedback = "CameraX error: ${e.localizedMessage}"
                }
            }, executor)
            onDispose {
                imageAnalysis.clearAnalyzer()
                faceOverlayState = TransientFaceDetectionState(permissionGranted = perms.camera)
                runCatching { activeRecording?.stop() }
                activeRecording = null
                cameraBound = false
                runCatching { providerFuture.get().unbindAll() }
            }
        }
    } else if (faceDetectionEnabled) {
        faceDetectionEnabled = false
        faceOverlayState = TransientFaceDetectionState(permissionGranted = false)
    }

    val (hasFront, hasBack) = remember { viewModel.container.cameraMediaEngine.hasFrontAndBackCamera() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("camera_media_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "CameraX Photo, Video & Face Detection",
                    subtitle = "${if (useFrontCamera) "Front" else "Rear"} Camera • Front=$hasFront, Rear=$hasBack",
                    trailing = {
                        StatusPill(
                            when {
                                !perms.camera -> "BLOCKED"
                                cameraBound -> "ACTIVE"
                                else -> "IMPLEMENTED_NOT_DEVICE_VERIFIED"
                            }
                        )
                    }
                )
                if (!perms.camera) {
                    Button(
                        onClick = {
                            camPermLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("grant_camera_perm_btn")
                    ) {
                        Text("Grant Camera & Mic Permission")
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(MaterialTheme.shapes.medium)
                    ) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                        FaceDetectionOverlay(state = faceOverlayState)
                    }
                    if (activeRecording != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.FiberManualRecord,
                                contentDescription = "Recording active",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Recording video: ${recordingSeconds}s (Audio=${perms.recordAudio})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val (outputOpts, fallbackUri) = viewModel.container.cameraMediaEngine.createPhotoOutputOptions()
                                imageCapture.takePicture(
                                    outputOpts,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            val saved = outputFileResults.savedUri ?: fallbackUri
                                            lastPhotoUri = saved
                                            cameraFeedback = "Photo saved: ${saved?.toString() ?: "MediaStore Pictures/TeenaAI"}"
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            cameraFeedback = "Photo error: ${exception.message}"
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("camera_take_photo_btn")
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Photo")
                        }
                        Button(
                            onClick = {
                                val currentRec = activeRecording
                                if (currentRec != null) {
                                    currentRec.stop()
                                    activeRecording = null
                                } else {
                                    if (faceDetectionEnabled) {
                                        faceDetectionEnabled = false
                                        cameraFeedback = "Paused face detection to start video capture."
                                    } else {
                                        val target = viewModel.container.cameraMediaEngine.createVideoOutputTarget()
                                        val pending = when (target) {
                                            is CameraVideoTarget.MediaStoreTarget -> recorder.prepareRecording(context, target.options)
                                            is CameraVideoTarget.FileTarget -> recorder.prepareRecording(context, target.options)
                                        }.apply {
                                            if (perms.recordAudio) withAudioEnabled()
                                        }
                                        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                                            when (event) {
                                                is VideoRecordEvent.Start -> {
                                                    cameraFeedback = "Video recording started..."
                                                }
                                                is VideoRecordEvent.Finalize -> {
                                                    activeRecording = null
                                                    if (event.hasError()) {
                                                        cameraFeedback = "Video error (${event.error}): ${event.cause?.localizedMessage ?: "Failed"}"
                                                    } else {
                                                        val uri = event.outputResults.outputUri
                                                        lastVideoUri = uri
                                                        cameraFeedback = "Video saved: $uri"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            colors = if (activeRecording != null) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("camera_record_video_btn")
                        ) {
                            Icon(
                                if (activeRecording != null) Icons.Default.Stop else Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(if (activeRecording != null) "Stop" else "Video")
                        }
                        OutlinedButton(
                            onClick = {
                                if (activeRecording != null) {
                                    cameraFeedback = "Stop video recording before switching front/rear camera."
                                } else {
                                    useFrontCamera = !useFrontCamera
                                }
                            },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("camera_flip_btn")
                        ) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Front/Rear")
                            Spacer(Modifier.size(4.dp))
                            Text("Flip")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                imageCapture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bmp = imageProxyToBitmap(image)
                                            image.close()
                                            if (bmp != null) {
                                                viewModel.attachBitmap(bmp, "Captured from Teena CameraX")
                                                onNavigateToChat()
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            cameraFeedback = "Capture error: ${exception.message}"
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("camera_analyze_ai_btn")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Analyze in AI")
                        }
                        lastPhotoUri?.let { uri ->
                            OutlinedButton(
                                onClick = { viewModel.container.cameraMediaEngine.openCapturedMediaUri(uri, "image/jpeg") },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Open Photo")
                            }
                        }
                        lastVideoUri?.let { uri ->
                            OutlinedButton(
                                onClick = { viewModel.container.cameraMediaEngine.openCapturedMediaUri(uri, "video/mp4") },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Open Video")
                            }
                        }
                    }
                }
                if (cameraFeedback.isNotBlank()) {
                    Text(cameraFeedback, style = MaterialTheme.typography.bodySmall, color = NeuralCyan)
                }
            }
        }

        // On-Device ML Kit Face Detection Card (strictly transient RAM only, requires CAMERA permission)
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.45f)) {
                SectionHeader(
                    title = "ML Kit Face Detection (On-Device)",
                    subtitle = TeenaFaceDetectionEngine.PRIVACY_GUARANTEE,
                    trailing = {
                        StatusPill(
                            when {
                                !perms.camera -> "BLOCKED"
                                faceDetectionEnabled -> "ACTIVE"
                                else -> "OFF"
                            },
                            modifier = Modifier.testTag("camera_face_detection_status")
                        )
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Live Face Bounding Box Overlay", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (perms.camera) faceOverlayState.statusNote
                            else "Grant CAMERA permission first to run on-device face detection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = faceDetectionEnabled && perms.camera,
                        onCheckedChange = { wantEnable ->
                            if (!perms.camera) {
                                faceDetectionEnabled = false
                                camPermLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                            } else {
                                faceDetectionEnabled = wantEnable
                                if (!wantEnable) {
                                    faceOverlayState = TransientFaceDetectionState(permissionGranted = true)
                                    faceEngine.closeDetector()
                                }
                            }
                        },
                        modifier = Modifier.testTag("camera_face_detection_switch")
                    )
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Hardware, Flashlight, Volume & Brightness")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        viewModel.container.cameraMediaEngine.toggleFlashlight(!torchOn)
                    }) {
                        Icon(if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (torchOn) "Flashlight OFF" else "Flashlight ON")
                    }
                    IconButton(onClick = { viewModel.mediaPrevious() }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Track")
                    }
                    IconButton(onClick = { viewModel.mediaPlayPause() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play / Pause Media")
                    }
                    IconButton(onClick = { viewModel.mediaNext() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Track")
                    }
                }

                Text("Media Volume: ${volumeSlider.toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = volumeSlider,
                    onValueChange = {
                        volumeSlider = it
                        viewModel.container.cameraMediaEngine.setMediaVolumePercent(it.toInt())
                    },
                    valueRange = 0f..100f
                )

                Text(
                    "System Brightness: ${brightnessSlider.toInt()}% (WriteSettings=${perms.writeSettingsAllowed})",
                    style = MaterialTheme.typography.labelMedium
                )
                if (!perms.writeSettingsAllowed) {
                    OutlinedButton(onClick = { viewModel.container.cameraMediaEngine.openWriteSettingsPermission() }) {
                        Text("Allow Modify System Settings")
                    }
                } else {
                    Slider(
                        value = brightnessSlider,
                        onValueChange = {
                            brightnessSlider = it
                            viewModel.container.cameraMediaEngine.setSystemBrightnessPercent(it.toInt())
                        },
                        valueRange = 5f..100f
                    )
                }
            }
        }
    }
}

private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
    return runCatching {
        val buffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }.getOrNull()
}
