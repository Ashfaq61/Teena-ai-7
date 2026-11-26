package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.CapabilitySummaryAnalyzer
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchCenterScreen(viewModel: TeenaViewModel) {
    val ghResults by viewModel.githubResults.collectAsStateWithLifecycle()
    val repoRiskReports by viewModel.repoRiskReports.collectAsStateWithLifecycle()
    val repoInspect by viewModel.repoInspectionOutput.collectAsStateWithLifecycle()
    val vaultEntries by viewModel.vaultEntries.collectAsStateWithLifecycle()
    val vaultInspectState by viewModel.vaultInspectState.collectAsStateWithLifecycle()
    val hfResults by viewModel.hfResults.collectAsStateWithLifecycle()
    val webResults by viewModel.webResults.collectAsStateWithLifecycle()
    val pageSummary by viewModel.webPageSummary.collectAsStateWithLifecycle()
    val history by viewModel.researchEntries.collectAsStateWithLifecycle()
    val capabilitySummaries by viewModel.capabilitySummaries.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val summaryMap = remember(capabilitySummaries) {
        capabilitySummaries.associateBy { it.itemKey }
    }

    var tabIndex by remember { mutableIntStateOf(0) }
    var ghQuery by remember { mutableStateOf("") }
    var hfQuery by remember { mutableStateOf("") }
    var hfTask by remember { mutableStateOf("") }
    var webQuery by remember { mutableStateOf("") }
    var urlToRead by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = tabIndex,
            edgePadding = 8.dp,
            modifier = Modifier.testTag("research_tabs")
        ) {
            Tab(
                selected = tabIndex == 0,
                onClick = { tabIndex = 0 },
                text = { Text("GitHub", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { tabIndex = 1 },
                text = { Text("Vault (${vaultEntries.size})", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = tabIndex == 2,
                onClick = { tabIndex = 2 },
                text = { Text("HuggingFace", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = tabIndex == 3,
                onClick = { tabIndex = 3 },
                text = { Text("Web", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            )
        }

        if (tabIndex == 1) {
            OriginalCodeVaultTab(
                entries = vaultEntries,
                inspectState = vaultInspectState,
                isBusy = isBusy,
                onOpenAndVerify = { viewModel.openAndVerifyVaultEntry(it) },
                onSelectFile = { viewModel.readVaultFileReadOnly(it) },
                onCloseInspect = { viewModel.closeVaultInspect() }
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tabIndex) {
                0 -> {
                    item {
                        NeuralCard {
                            SectionHeader("GitHub Repository & License Inspector")
                            OutlinedTextField(
                                value = ghQuery,
                                onValueChange = { ghQuery = it },
                                label = { Text("Search repos (e.g. 'android voice assistant')") },
                                modifier = Modifier.fillMaxWidth().testTag("github_search_input")
                            )
                            Button(
                                onClick = { viewModel.searchGitHub(ghQuery) },
                                enabled = ghQuery.isNotBlank() && !isBusy,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 140.dp, minHeight = 48.dp)
                                    .testTag("github_search_btn")
                            ) {
                                if (isBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Search GitHub", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    if (repoInspect.isNotBlank()) {
                        item {
                            NeuralCard(borderColor = NeuralCyan) {
                                SectionHeader("Dependency & Security Inspection")
                                Text(repoInspect, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    items(ghResults, key = { it.fullName }) { repo ->
                        GitHubRepoRiskCard(
                            repo = repo,
                            report = repoRiskReports[repo.fullName],
                            summary = summaryMap[CapabilitySummaryAnalyzer.githubItemKey(repo.fullName)],
                            isBusy = isBusy,
                            onInspectCode = { viewModel.inspectGitHubRepo(repo.fullName) },
                            onSaveToVault = { viewModel.requestSaveRepoToVault(repo.fullName) },
                            onProposeTool = { viewModel.proposeToolFromAuditedRepo(repo.fullName) },
                            onOpenUrl = { viewModel.openExternalUrl(repo.htmlUrl) }
                        )
                    }
                }

                2 -> {
                    item {
                        NeuralCard {
                            SectionHeader("Hugging Face Model Evaluator")
                            OutlinedTextField(
                                value = hfQuery,
                                onValueChange = { hfQuery = it },
                                label = { Text("Search models (e.g. 'whisper', 'ocr', 'gemma')") },
                                modifier = Modifier.fillMaxWidth().testTag("hf_search_input")
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("text-generation", "automatic-speech-recognition", "image-to-text").forEach { task ->
                                    FilterChip(
                                        selected = hfTask == task,
                                        onClick = { hfTask = if (hfTask == task) "" else task },
                                        label = { Text(task.substringBefore("-"), maxLines = 1, softWrap = false) }
                                    )
                                }
                            }
                            Button(
                                onClick = { viewModel.searchHuggingFace(hfQuery, hfTask) },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 140.dp, minHeight = 48.dp)
                                    .testTag("hf_search_btn")
                            ) {
                                Text("Search Models", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    items(hfResults, key = { it.modelId }) { m ->
                        val summary = summaryMap[CapabilitySummaryAnalyzer.huggingFaceItemKey(m.modelId)]
                        NeuralCard {
                            Text(m.modelId, style = MaterialTheme.typography.titleMedium, color = NeuralCyan)
                            Text("Task: ${m.pipelineTag} • Downloads: ${m.downloads} • License: ${m.license}", style = MaterialTheme.typography.labelSmall)
                            Text(m.compatibilityNotes, style = MaterialTheme.typography.bodySmall, color = NeuralMint)

                            CapabilityBriefBlock(summary = summary)

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilledTonalButton(
                                    onClick = { viewModel.inspectHuggingFaceModel(m) },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 104.dp, minHeight = 48.dp)
                                        .testTag("inspect_hf_btn_${m.modelId}")
                                ) {
                                    Text(
                                        text = if (summary?.status == "READY") "Re-Analyze" else "Analyze Card",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = { viewModel.proposeToolFromHuggingFace(m) },
                                    enabled = !isBusy,
                                    modifier = Modifier.defaultMinSize(minWidth = 104.dp, minHeight = 48.dp)
                                ) {
                                    Text("Propose Tool", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.openExternalUrl(m.url) },
                                    modifier = Modifier.defaultMinSize(minWidth = 84.dp, minHeight = 48.dp)
                                ) {
                                    Text("Open", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                else -> {
                    item {
                        NeuralCard {
                            SectionHeader("Live Web Search & Page Reader")
                            OutlinedTextField(
                                value = webQuery,
                                onValueChange = { webQuery = it },
                                label = { Text("Search DuckDuckGo & Wikipedia") },
                                modifier = Modifier.fillMaxWidth().testTag("web_search_input")
                            )
                            Button(
                                onClick = { viewModel.searchWebDeep(webQuery) },
                                enabled = webQuery.isNotBlank() && !isBusy,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 120.dp, minHeight = 48.dp)
                                    .testTag("web_search_btn")
                            ) { Text("Search Web", maxLines = 1, softWrap = false) }

                            Spacer(Modifier.size(6.dp))
                            OutlinedTextField(
                                value = urlToRead,
                                onValueChange = { urlToRead = it },
                                label = { Text("URL to read & summarize") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilledTonalButton(
                                onClick = { viewModel.readAndSummarizeUrl(urlToRead) },
                                enabled = urlToRead.isNotBlank() && !isBusy,
                                modifier = Modifier.defaultMinSize(minWidth = 140.dp, minHeight = 48.dp)
                            ) { Text("Read Page", maxLines = 1, softWrap = false) }
                            if (pageSummary.isNotBlank()) {
                                Text(pageSummary, style = MaterialTheme.typography.bodySmall, color = NeuralMint)
                            }
                        }
                    }

                    items(webResults) { res ->
                        NeuralCard {
                            Text("${res.title} (${res.source})", style = MaterialTheme.typography.titleSmall, color = NeuralCyan)
                            Text(res.snippet, style = MaterialTheme.typography.bodySmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilledTonalButton(
                                    onClick = { viewModel.readAndSummarizeUrl(res.url) },
                                    modifier = Modifier.defaultMinSize(minWidth = 84.dp, minHeight = 48.dp)
                                ) { Text("Read", maxLines = 1, softWrap = false) }
                                OutlinedButton(
                                    onClick = { viewModel.openExternalUrl(res.url) },
                                    modifier = Modifier.defaultMinSize(minWidth = 84.dp, minHeight = 48.dp)
                                ) { Text("Open", maxLines = 1, softWrap = false) }
                            }
                        }
                    }

                    if (history.isNotEmpty()) {
                        item { SectionHeader("Saved Research Knowledge Base") }
                        items(history.take(10), key = { it.id }) { h ->
                            NeuralCard {
                                Text("[${h.sourceType}] ${h.title}", style = MaterialTheme.typography.labelLarge)
                                Text(h.summary.take(180), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
