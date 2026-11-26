package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.system.DeviceLocationInfo
import com.example.system.MapsLocationEngine
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapsScreen(viewModel: TeenaViewModel) {
    val location by viewModel.currentLocation.collectAsStateWithLifecycle()
    val perms by viewModel.permissionSnapshot.collectAsStateWithLifecycle()

    var destination by remember { mutableStateOf("") }
    var statusMessage by remember {
        mutableStateOf("Interactive WebView map loaded from map.html (Location permission optional).")
    }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val locPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.refreshSystemHealth()
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            statusMessage = "Location granted — centering map on GPS..."
            viewModel.fetchDeviceLocation()
        } else {
            statusMessage = "Location permission declined — map continues smoothly without GPS."
        }
    }

    LaunchedEffect(location) {
        val loc = location ?: return@LaunchedEffect
        runCatching {
            webViewRef?.evaluateJavascript(
                MapsLocationEngine.buildSetLocationJs(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracyMeters = loc.accuracyMeters,
                    label = "You"
                ),
                null
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("maps_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.5f)) {
                SectionHeader(
                    title = "Teena Maps (map.html)",
                    subtitle = "Interactive WebView map • Location permission is strictly optional",
                    trailing = {
                        StatusPill(if (perms.fineLocation) "GPS READY" else "OPTIONAL GPS")
                    }
                )
                Text(
                    text = location?.let {
                        "GPS: ${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)} (±${it.accuracyMeters.toInt()}m)"
                    } ?: statusMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = NeuralMint,
                    modifier = Modifier.testTag("maps_status_text")
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .testTag("maps_webview_container")
                ) {
                    TeenaMapWebView(
                        location = location,
                        reloadNonce = reloadNonce,
                        onWebViewReady = { wv -> webViewRef = wv }
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (perms.fineLocation) {
                                viewModel.fetchDeviceLocation()
                            } else {
                                locPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("maps_gps_btn")
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (perms.fineLocation) "Center GPS" else "My Location (Optional)")
                    }
                    OutlinedButton(
                        onClick = { reloadNonce++ },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("maps_reload_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Reload Map")
                    }
                }
            }
        }

        item {
            NeuralCard {
                SectionHeader("Pin Label, Search & Navigation")
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text("Place, address, or nearby query") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("maps_destination_input")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                webViewRef?.evaluateJavascript(
                                    MapsLocationEngine.buildSearchFocusJs(destination, location),
                                    null
                                )
                            }
                            statusMessage = "Updated map pin label: $destination"
                        },
                        enabled = destination.isNotBlank(),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("maps_pin_label_btn")
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Update Pin")
                    }
                    Button(
                        onClick = { viewModel.container.mapsEngine.launchNavigation(destination) },
                        enabled = destination.isNotBlank(),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("maps_navigate_btn")
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Directions")
                    }
                    FilledTonalButton(
                        onClick = { viewModel.container.mapsEngine.searchNearbyPlaces(destination, location) },
                        enabled = destination.isNotBlank(),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Nearby")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TeenaMapWebView(
    location: DeviceLocationInfo?,
    reloadNonce: Int,
    onWebViewReady: (WebView?) -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            onWebViewReady(null)
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            runCatching {
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = true
                        allowContentAccess = false
                        domStorageEnabled = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            return !url.startsWith("file:///android_asset/")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val loc = location ?: return
                            runCatching {
                                view?.evaluateJavascript(
                                    MapsLocationEngine.buildSetLocationJs(
                                        loc.latitude,
                                        loc.longitude,
                                        loc.accuracyMeters,
                                        "You"
                                    ),
                                    null
                                )
                            }
                        }
                    }
                    webViewRef = this
                    onWebViewReady(this)
                }
            }.getOrElse {
                WebView(ctx)
            }
        },
        update = { wv ->
            runCatching {
                val marker = "map_asset:$reloadNonce"
                if (wv.tag != marker) {
                    wv.tag = marker
                    wv.loadUrl(MapsLocationEngine.ASSET_MAP_URL)
                }
            }
        }
    )
}
