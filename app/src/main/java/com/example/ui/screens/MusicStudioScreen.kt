package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.LyriaGeneratedTrack
import com.example.ai.LyriaModelMode
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun MusicStudioScreen(
    viewModel: TeenaViewModel,
    onOpenApiManager: () -> Unit = {}
) {
    val tracks by viewModel.musicTracks.collectAsStateWithLifecycle()
    val playback by viewModel.musicPlaybackState.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isMusicGenerating.collectAsStateWithLifecycle()
    val lastError by viewModel.musicLastError.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()

    var selectedMode by remember { mutableStateOf(LyriaModelMode.CLIP_30S) }
    var promptText by remember { mutableStateOf("") }
    var selectedGenre by remember { mutableStateOf("") }
    var selectedBpm by remember { mutableStateOf<Int?>(null) }
    var instrumentalOnly by remember { mutableStateOf(false) }
    var trackPendingExport by remember { mutableStateOf<LyriaGeneratedTrack?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mpeg")
    ) { uri ->
        val pending = trackPendingExport
        trackPendingExport = null
        if (uri != null && pending != null) {
            viewModel.exportMusicTrackToUri(pending, uri)
        }
    }

    val moods = listOf("Lo-Fi Chill", "Desi Fusion", "Sufi Acoustic", "Cinematic", "Upbeat Pop", "Synthwave")
    val bpmOptions = listOf(80, 100, 120, 140)
    val activeTrack = tracks.firstOrNull { it.id == playback.activeTrackId }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("music_studio_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(borderColor = NeuralViolet.copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Teena Music Studio (Lyria 3)",
                    subtitle = "Generate stereo music with lyria-3-clip-preview (≤30s clips) or lyria-3-pro-preview (full tracks)",
                    trailing = {
                        StatusPill(if (isGenerating) "GENERATING" else if (lastError?.startsWith("BLOCKED") == true) "BLOCKED" else "READY")
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == LyriaModelMode.CLIP_30S,
                        onClick = { selectedMode = LyriaModelMode.CLIP_30S },
                        label = { Text("Short Clip (30s)") },
                        modifier = Modifier.weight(1f).testTag("music_mode_clip_btn")
                    )
                    FilterChip(
                        selected = selectedMode == LyriaModelMode.FULL_PRO,
                        onClick = { selectedMode = LyriaModelMode.FULL_PRO },
                        label = { Text("Full Track (Pro)") },
                        modifier = Modifier.weight(1f).testTag("music_mode_pro_btn")
                    )
                }
                Text(
                    text = "Model: ${selectedMode.modelId} • ${selectedMode.description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeuralCyan
                )
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Describe instruments, vibe, vocals, or theme (any language)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("music_prompt_input")
                )

                Text("Style / Mood", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(moods) { mood ->
                        FilterChip(
                            selected = selectedGenre == mood,
                            onClick = { selectedGenre = if (selectedGenre == mood) "" else mood },
                            label = { Text(mood) }
                        )
                    }
                }

                Text("Tempo (Optional BPM)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bpmOptions.forEach { bpm ->
                        FilterChip(
                            selected = selectedBpm == bpm,
                            onClick = { selectedBpm = if (selectedBpm == bpm) null else bpm },
                            label = { Text("$bpm BPM", maxLines = 1, softWrap = false) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Instrumental Only", style = MaterialTheme.typography.titleSmall)
                        Text("Skip singing/lyrics and compose instrumental arrangement", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = instrumentalOnly, onCheckedChange = { instrumentalOnly = it })
                }

                if (isGenerating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeuralViolet)
                }

                if (!lastError.isNullOrBlank()) {
                    Text(
                        text = lastError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusCrimson
                    )
                    if (lastError!!.contains("API key", ignoreCase = true)) {
                        OutlinedButton(onClick = onOpenApiManager, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Text("Open API Manager")
                        }
                    }
                }

                Button(
                    onClick = {
                        viewModel.generateLyriaMusic(
                            mode = selectedMode,
                            rawPrompt = promptText,
                            moodOrGenre = selectedGenre,
                            bpm = selectedBpm,
                            instrumentalOnly = instrumentalOnly
                        )
                    },
                    enabled = promptText.isNotBlank() && !isGenerating && !emergencyStopped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("music_generate_btn")
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (isGenerating) "Composing with ${selectedMode.modelId}..."
                        else "Generate ${selectedMode.shortTitle}"
                    )
                }
            }
        }

        if (activeTrack != null) {
            item {
                NeuralCard(borderColor = NeuralMint.copy(alpha = 0.6f)) {
                    SectionHeader(
                        title = "Now Playing: ${activeTrack.title}",
                        subtitle = "${activeTrack.modelId} • ${activeTrack.durationTag}",
                        trailing = { StatusPill(if (playback.isPlaying) "PLAYING" else "PAUSED") }
                    )
                    val durationMs = playback.durationMs.coerceAtLeast(1L)
                    Slider(
                        value = playback.positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        onValueChange = { viewModel.seekMusicTo(it.toLong()) },
                        valueRange = 0f..durationMs.toFloat()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatSeconds(playback.positionMs)} / ${formatSeconds(durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralMint
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { viewModel.togglePlayMusicTrack(activeTrack) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playback.isPlaying) "Pause" else "Play"
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.stopMusicPlayback() },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop")
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Generated Library (${tracks.size})")
        }

        if (tracks.isEmpty()) {
            item {
                NeuralCard {
                    Text(
                        text = "No generated tracks yet. Enter a prompt above and choose lyria-3-clip-preview (up to 30s) or lyria-3-pro-preview (full track).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(tracks, key = { it.id }) { track ->
                val isCurrent = playback.activeTrackId == track.id
                NeuralCard(borderColor = if (isCurrent) NeuralMint.copy(alpha = 0.6f) else NeuralCyan.copy(alpha = 0.25f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NeuralCyan)
                            Text(
                                "${track.modelId} • ${track.durationTag} • ${track.sizeBytes / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeuralMint
                            )
                        }
                        IconButton(onClick = { viewModel.togglePlayMusicTrack(track) }) {
                            Icon(
                                if (isCurrent && playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play or Pause ${track.title}"
                            )
                        }
                        IconButton(
                            onClick = {
                                trackPendingExport = track
                                val ext = if (track.mimeType.contains("wav")) "wav" else "mp3"
                                exportLauncher.launch("${track.id}.$ext")
                            }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Save Audio ${track.title}")
                        }
                        IconButton(onClick = { viewModel.deleteMusicTrack(track.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${track.title}")
                        }
                    }
                    Text(track.prompt, style = MaterialTheme.typography.bodySmall)
                    if (track.lyricsOrNotes.isNotBlank()) {
                        Text(
                            text = "Structure / Lyrics:\n${track.lyricsOrNotes.take(400)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatSeconds(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%d:%02d".format(mins, secs)
}
