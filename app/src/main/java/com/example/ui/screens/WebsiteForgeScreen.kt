package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.WebsiteForgeEngine
import com.example.ai.WebsiteForgeProject
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun WebsiteForgeScreen(
    viewModel: TeenaViewModel,
    onOpenApiManager: () -> Unit = {}
) {
    val projects by viewModel.websiteForgeProjects.collectAsStateWithLifecycle()
    val activeId by viewModel.activeWebsiteProjectId.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isWebsiteGenerating.collectAsStateWithLifecycle()
    val lastError by viewModel.websiteForgeError.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()

    val activeProject = projects.firstOrNull { it.id == activeId } ?: projects.firstOrNull()
    val templates = remember { WebsiteForgeEngine.starterTemplates() }

    var promptInput by remember { mutableStateOf("") }
    var includeWebGl by remember { mutableStateOf(true) }
    var showCodeEditor by remember { mutableStateOf(false) }
    var editableSource by remember(activeProject?.id, activeProject?.htmlSource) {
        mutableStateOf(activeProject?.htmlSource.orEmpty())
    }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var projectPendingExport by remember { mutableStateOf<WebsiteForgeProject?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val pending = projectPendingExport
        projectPendingExport = null
        if (uri != null && pending != null) {
            viewModel.exportWebsiteHtmlToUri(pending, uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("website_forge_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Teena Website Forge & WebGL Preview",
                    subtitle = "Generate responsive HTML5 + CSS + WebGL sites or preview code from 'website banao' chat",
                    trailing = {
                        StatusPill(
                            if (isGenerating) "FORGING"
                            else if (activeProject?.hasWebGlCanvas == true) "WEBGL"
                            else "READY"
                        )
                    }
                )
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    label = { Text("Describe the website or 3D scene (any language)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("website_forge_prompt_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include Interactive WebGL 3D Canvas", style = MaterialTheme.typography.titleSmall)
                        Text("Instructs AI to embed self-contained WebGL shaders & animation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = includeWebGl, onCheckedChange = { includeWebGl = it })
                }

                if (isGenerating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeuralCyan)
                }

                if (!lastError.isNullOrBlank()) {
                    Text(lastError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = StatusCrimson)
                    if (lastError!!.contains("key", ignoreCase = true)) {
                        OutlinedButton(onClick = onOpenApiManager, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Text("Open API Manager")
                        }
                    }
                }

                Button(
                    onClick = {
                        viewModel.generateWebsiteForgeProject(promptInput, includeWebGl)
                    },
                    enabled = promptInput.isNotBlank() && !isGenerating && !emergencyStopped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("website_forge_generate_btn")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (isGenerating) "Forging Website..." else "Generate Website with AI")
                }

                Text("Built-in Offline Blueprints", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(templates, key = { it.id }) { tpl ->
                        AssistChip(
                            onClick = { viewModel.loadStarterWebsiteTemplate(tpl.id) },
                            label = { Text(tpl.title) }
                        )
                    }
                }
            }
        }

        if (activeProject != null) {
            item {
                NeuralCard(borderColor = NeuralViolet.copy(alpha = 0.55f)) {
                    SectionHeader(
                        title = activeProject.title,
                        subtitle = "${activeProject.templateKind} • ${if (activeProject.hasWebGlCanvas) "WebGL Enabled" else "HTML5/CSS/JS"}",
                        trailing = {
                            IconButton(
                                onClick = {
                                    projectPendingExport = activeProject
                                    exportLauncher.launch("${activeProject.title.replace(" ", "_").lowercase()}.html")
                                }
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Export HTML")
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showCodeEditor,
                            onClick = { showCodeEditor = false },
                            label = { Text("Live Preview") },
                            leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f).testTag("website_forge_tab_preview")
                        )
                        FilterChip(
                            selected = showCodeEditor,
                            onClick = { showCodeEditor = true },
                            label = { Text("HTML / JS Source") },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f).testTag("website_forge_tab_code")
                        )
                    }

                    if (!showCodeEditor) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .testTag("website_forge_webview_container")
                        ) {
                            SandboxedWebsiteWebView(
                                htmlSource = activeProject.htmlSource,
                                reloadNonce = reloadNonce
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { reloadNonce++ },
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Reload Canvas")
                            }
                            FilledTonalButton(
                                onClick = {
                                    projectPendingExport = activeProject
                                    exportLauncher.launch("${activeProject.title.replace(" ", "_").lowercase()}.html")
                                },
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Save .HTML")
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = editableSource,
                            onValueChange = { editableSource = it },
                            label = { Text("HTML5 / CSS / WebGL Source") },
                            minLines = 10,
                            maxLines = 18,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().testTag("website_forge_source_editor")
                        )
                        FilledTonalButton(
                            onClick = {
                                viewModel.updateWebsiteForgeSource(activeProject.id, editableSource)
                                reloadNonce++
                                showCodeEditor = false
                            },
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                        ) {
                            Text("Apply Edits & Render Preview")
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Saved Forged Projects (${projects.size})")
        }

        items(projects, key = { it.id }) { proj ->
            val selected = proj.id == activeProject?.id
            NeuralCard(borderColor = if (selected) NeuralMint.copy(alpha = 0.65f) else NeuralCyan.copy(alpha = 0.25f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(proj.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (selected) NeuralMint else NeuralCyan)
                        Text(proj.prompt.ifBlank { proj.templateKind }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!selected) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.selectWebsiteForgeProject(proj.id)
                                    reloadNonce++
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Preview")
                            }
                        } else {
                            StatusPill("ACTIVE")
                        }
                        IconButton(onClick = { viewModel.deleteWebsiteForgeProject(proj.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${proj.title}")
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedWebsiteWebView(
    htmlSource: String,
    reloadNonce: Int
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
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
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        databaseEnabled = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme?.lowercase().orEmpty()
                            return scheme != "https" && scheme != "about" && scheme != "data"
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            val scheme = uri?.scheme?.lowercase().orEmpty()
                            return scheme != "https" && scheme != "about" && scheme != "data"
                        }
                    }
                    webViewRef = this
                }
            }.getOrElse {
                // Safe fallback View if WebView factory throws in minimal headless instrumentation
                WebView(ctx)
            }
        },
        update = { wv ->
            runCatching {
                val safeHtml = WebsiteForgeEngine.sanitizePreviewHtml(htmlSource)
                val tagMarker = "${safeHtml.hashCode()}:$reloadNonce"
                if (wv.tag != tagMarker) {
                    wv.tag = tagMarker
                    wv.loadDataWithBaseURL(
                        "https://teena-forge.local/",
                        safeHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        }
    )
}
