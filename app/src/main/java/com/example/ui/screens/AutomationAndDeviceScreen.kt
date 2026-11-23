package com.example.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.system.SocialPlatform
import com.example.system.TeenaAccessibilityService
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun AutomationAndDeviceScreen(
    viewModel: TeenaViewModel,
    onNavigateToChat: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Screen UI", "Social (6)", "Phone & Maps", "Camera & Media")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }

        when (selectedTab) {
            0 -> AccessibilityTab(viewModel)
            1 -> SocialAutomationTab(viewModel)
            2 -> PhoneAndMapsTab(viewModel)
            3 -> CameraAndMediaTab(viewModel, onNavigateToChat)
        }
    }
}

@Composable
private fun AccessibilityTab(viewModel: TeenaViewModel) {
    val context = LocalContext.current
    val isBound by viewModel.accessibilityConnected.collectAsStateWithLifecycle()
    val snapshot by viewModel.screenSnapshot.collectAsStateWithLifecycle()
    val logs by viewModel.automationLog.collectAsStateWithLifecycle()

    var tapTarget by remember { mutableStateOf("") }
    var typePayload by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "Android Accessibility Automation",
                    subtitle = "Inspect live UI trees and execute verified actions",
                    trailing = { StatusPill(if (isBound) "ACTIVE" else "BLOCKED") }
                )
                if (!isBound) {
                    Text(
                        "TeenaAccessibilityService is currently disabled in Android Settings. Enable it to inspect active windows and verify gestures.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = { TeenaAccessibilityService.openAccessibilitySettings(context) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("enable_accessibility_btn")
                    ) {
                        Text("Open Accessibility Settings")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.refreshScreenSnapshot() }) {
                            Text("Capture Screen Tree")
                        }
                        OutlinedButton(onClick = { viewModel.runAccessibilityAction { it.goBackVerified() } }) {
                            Text("Back")
                        }
                        OutlinedButton(onClick = { viewModel.runAccessibilityAction { it.goHomeVerified() } }) {
                            Text("Home")
                        }
                    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.runAccessibilityAction { it.tapByTextOrDesc(tapTarget) } },
                        enabled = tapTarget.isNotBlank()
                    ) { Text("Tap") }
                    FilledTonalButton(
                        onClick = { viewModel.runAccessibilityAction { it.longPressByText(tapTarget) } },
                        enabled = tapTarget.isNotBlank()
                    ) { Text("Long Press") }
                }
                OutlinedTextField(
                    value = typePayload,
                    onValueChange = { typePayload = it },
                    label = { Text("Text to type into active editable field") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.runAccessibilityAction { it.typeIntoFocusedOrEditable(typePayload) } },
                    enabled = typePayload.isNotBlank()
                ) { Text("Type & Verify") }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("UP", "DOWN", "LEFT", "RIGHT").forEach { dir ->
                        OutlinedButton(
                            onClick = { viewModel.runAccessibilityAction { it.swipeDirection(dir) } },
                            modifier = Modifier.weight(1f)
                        ) { Text(dir) }
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

@Composable
private fun SocialAutomationTab(viewModel: TeenaViewModel) {
    val installedApps = remember { viewModel.container.socialEngine.checkInstalledApps() }
    var selectedPlatform by remember { mutableStateOf(SocialPlatform.WHATSAPP) }
    var searchQuery by remember { mutableStateOf("") }
    var draftText by remember { mutableStateOf("") }
    var phoneTarget by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader("Supported Social Workflows", "Launch, search, and prepare user-authorized drafts")
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
                    FilledTonalButton(onClick = {
                        selectedPlatform = status.platform
                        viewModel.container.socialEngine.openPlatform(status.platform)
                    }) {
                        Text("Open")
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Authorized Content Search & Post Composer (${selectedPlatform.displayName})")
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search topic or channel on ${selectedPlatform.displayName}") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.container.socialEngine.searchPlatformContent(selectedPlatform, searchQuery) },
                    enabled = searchQuery.isNotBlank()
                ) { Text("Search in ${selectedPlatform.displayName}") }

                if (selectedPlatform == SocialPlatform.WHATSAPP) {
                    OutlinedTextField(
                        value = phoneTarget,
                        onValueChange = { phoneTarget = it },
                        label = { Text("Optional WhatsApp phone (with country code)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    label = { Text("Draft reply / post caption") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.requestAuthorizedSocialSend(selectedPlatform, draftText, phoneTarget) },
                    enabled = draftText.isNotBlank(),
                    modifier = Modifier.testTag("social_authorize_send_btn")
                ) {
                    Text("Authorize & Prepare ${selectedPlatform.displayName} Draft")
                }
            }
        }
    }
}

@Composable
private fun PhoneAndMapsTab(viewModel: TeenaViewModel) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val callLogs by viewModel.callLogs.collectAsStateWithLifecycle()
    val liveCall by viewModel.liveCallInfo.collectAsStateWithLifecycle()
    val location by viewModel.currentLocation.collectAsStateWithLifecycle()
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()

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
            }
        }
    }
}

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
    var volumeSlider by remember { mutableFloatStateOf(viewModel.container.cameraMediaEngine.getMediaVolumePercent().toFloat()) }
    var brightnessSlider by remember { mutableFloatStateOf(viewModel.container.cameraMediaEngine.getSystemBrightnessPercent().toFloat()) }
    var cameraFeedback by remember { mutableStateOf("") }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context) }

    val camPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshSystemHealth() }

    if (perms.camera) {
        DisposableEffect(useFrontCamera, lifecycleOwner) {
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
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                }.onFailure { e ->
                    cameraFeedback = "CameraX error: ${e.localizedMessage}"
                }
            }, executor)
            onDispose {
                runCatching { providerFuture.get().unbindAll() }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard {
                SectionHeader(
                    title = "CameraX Live Vision",
                    subtitle = if (useFrontCamera) "Front Camera Active" else "Rear Camera Active",
                    trailing = { StatusPill(if (perms.camera) "ACTIVE" else "BLOCKED") }
                )
                if (!perms.camera) {
                    Button(onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(MaterialTheme.shapes.medium)
                    ) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
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
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Capture & Analyze")
                        }
                        OutlinedButton(onClick = { useFrontCamera = !useFrontCamera }) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Front/Rear")
                            Spacer(Modifier.size(4.dp))
                            Text("Flip")
                        }
                    }
                }
                if (cameraFeedback.isNotBlank()) {
                    Text(cameraFeedback, style = MaterialTheme.typography.bodySmall)
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
