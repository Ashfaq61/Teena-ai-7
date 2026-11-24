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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

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
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var tabIndex by remember { mutableIntStateOf(0) }
    var ghQuery by remember { mutableStateOf("") }
    var hfQuery by remember { mutableStateOf("") }
    var hfTask by remember { mutableStateOf("") }
    var webQuery by remember { mutableStateOf("") }
    var urlToRead by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("GitHub") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Vault (${vaultEntries.size})") })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("HuggingFace") })
            Tab(selected = tabIndex == 3, onClick = { tabIndex = 3 }, text = { Text("Web") })
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
                                modifier = Modifier.testTag("github_search_btn")
                            ) {
                                if (isBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Search & Audit GitHub")
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
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("text-generation", "automatic-speech-recognition", "image-to-text").forEach { task ->
                                    FilterChip(
                                        selected = hfTask == task,
                                        onClick = { hfTask = if (hfTask == task) "" else task },
                                        label = { Text(task.substringBefore("-")) }
                                    )
                                }
                            }
                            Button(
                                onClick = { viewModel.searchHuggingFace(hfQuery, hfTask) },
                                enabled = !isBusy,
                                modifier = Modifier.testTag("hf_search_btn")
                            ) {
                                Text("Evaluate Models")
                            }
                        }
                    }
                    items(hfResults) { m ->
                        NeuralCard {
                            Text(m.modelId, style = MaterialTheme.typography.titleMedium, color = NeuralCyan)
                            Text("Task: ${m.pipelineTag} • Downloads: ${m.downloads} • License: ${m.license}", style = MaterialTheme.typography.labelSmall)
                            Text(m.compatibilityNotes, style = MaterialTheme.typography.bodySmall, color = NeuralMint)
                            OutlinedButton(onClick = { viewModel.openExternalUrl(m.url) }) { Text("View Model Card") }
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
                                modifier = Modifier.testTag("web_search_btn")
                            ) { Text("Search Web") }

                            Spacer(Modifier.size(6.dp))
                            OutlinedTextField(
                                value = urlToRead,
                                onValueChange = { urlToRead = it },
                                label = { Text("URL to read & summarize") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilledTonalButton(
                                onClick = { viewModel.readAndSummarizeUrl(urlToRead) },
                                enabled = urlToRead.isNotBlank() && !isBusy
                            ) { Text("Fetch & Summarize Page") }
                            if (pageSummary.isNotBlank()) {
                                Text(pageSummary, style = MaterialTheme.typography.bodySmall, color = NeuralMint)
                            }
                        }
                    }

                    items(webResults) { res ->
                        NeuralCard {
                            Text("${res.title} (${res.source})", style = MaterialTheme.typography.titleSmall, color = NeuralCyan)
                            Text(res.snippet, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { viewModel.readAndSummarizeUrl(res.url) }) { Text("Read") }
                                OutlinedButton(onClick = { viewModel.openExternalUrl(res.url) }) { Text("Open Browser") }
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
