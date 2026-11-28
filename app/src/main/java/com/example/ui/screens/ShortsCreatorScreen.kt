package com.example.ui.screens

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.ai.ActiveShortsFrame
import com.example.ai.ShortsBackgroundTheme
import com.example.ai.ShortsExportRecord
import com.example.ai.ShortsExportResolution
import com.example.ai.ShortsProject
import com.example.ai.ShortsScene
import com.example.ai.ShortsSceneTransition
import com.example.ai.ShortsScriptEngine
import com.example.ai.ShortsVibePreset
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.ShortsCreatorUiState
import com.example.ui.viewmodel.TeenaViewModel
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ShortsCreatorScreen(
    viewModel: TeenaViewModel,
    onOpenApiManager: () -> Unit = {},
    onOpenMusicStudio: () -> Unit = {}
) {
    val state by viewModel.shortsState.collectAsStateWithLifecycle()
    val lyriaTracks by viewModel.musicTracks.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()

    var topicPrompt by remember { mutableStateOf("") }
    var selectedVibe by remember { mutableStateOf(ShortsVibePreset.HINGLISH_TECH) }
    var selectedDurationSec by remember { mutableIntStateOf(20) }
    var pendingPhotoSceneIndex by remember { mutableIntStateOf(0) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.attachPhotoToShortsScene(pendingPhotoSceneIndex, uri)
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachShortsExternalAudio(uri)
        }
    }

    val project = state.project
    val activeFrame = remember(project.scenes, state.playheadMs) {
        ShortsScriptEngine.resolveActiveSceneAt(project.scenes, state.playheadMs)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("shorts_creator_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live 9:16 Stage Preview Card
        item {
            ShortsPreviewStageCard(
                project = project,
                state = state,
                activeFrame = activeFrame,
                onTogglePlay = { viewModel.toggleShortsPreviewPlayback() },
                onRestart = { viewModel.restartShortsPreview() },
                onPrevScene = {
                    val prev = (state.selectedSceneIndex - 1).coerceAtLeast(0)
                    viewModel.selectShortsScene(prev)
                },
                onNextScene = {
                    val next = (state.selectedSceneIndex + 1).coerceAtMost((project.scenes.size - 1).coerceAtLeast(0))
                    viewModel.selectShortsScene(next)
                },
                onSeek = { viewModel.seekShortsPreviewTo(it) },
                onSpeakPreview = { viewModel.speakShortsNarration() }
            )
        }

        // Optional real exported MP4 video player card
        val previewPath = state.activeVideoPreviewPath
        if (!previewPath.isNullOrBlank() && File(previewPath).exists()) {
            item {
                NeuralCard(borderColor = NeuralMint.copy(alpha = 0.65f)) {
                    SectionHeader(
                        title = "Exported 9:16 MP4 Player",
                        subtitle = File(previewPath).name,
                        trailing = {
                            IconButton(
                                onClick = { viewModel.closeShortsVideoPreview() },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close MP4 preview")
                            }
                        }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .widthIn(max = 260.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val controller = MediaController(ctx)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setVideoPath(previewPath)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                }
                            },
                            update = { vv ->
                                vv.setVideoPath(previewPath)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // 2. AI Script & Storyboard Generator
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Phase 13 • AI Shorts Script Studio",
                    subtitle = "Gemini storyboard + Hinglish/English captions + 9:16 scene breakdown",
                    trailing = {
                        StatusPill(
                            when {
                                state.isGeneratingScript -> "RUNNING"
                                state.isExporting -> "EXPORTING"
                                else -> "READY"
                            }
                        )
                    }
                )
                OutlinedTextField(
                    value = topicPrompt,
                    onValueChange = { topicPrompt = it },
                    label = { Text("Shorts Topic / Idea (e.g. '3 AI tools for students')") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shorts_topic_input")
                )

                Text("Style / Vibe", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ShortsVibePreset.entries) { vibe ->
                        FilterChip(
                            selected = selectedVibe == vibe,
                            onClick = { selectedVibe = vibe },
                            label = { Text(vibe.label, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                Text("Target Length", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 20, 30, 45).forEach { sec ->
                        FilterChip(
                            selected = selectedDurationSec == sec,
                            onClick = { selectedDurationSec = sec },
                            label = { Text("${sec}s", maxLines = 1, softWrap = false) }
                        )
                    }
                }

                if (state.isGeneratingScript) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeuralCyan)
                }

                if (!state.scriptError.isNullOrBlank()) {
                    Surface(
                        color = StatusCrimson.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StatusCrimson.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shorts_script_error_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.scriptError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusCrimson,
                                modifier = Modifier.testTag("shorts_script_error_text")
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.retryShortsScriptGeneration() },
                                    enabled = state.lastScriptTopic.isNotBlank() && !state.isGeneratingScript,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("shorts_script_retry_btn")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Retry")
                                }
                                if (state.scriptError.orEmpty().contains("API key", ignoreCase = true) ||
                                    state.scriptError.orEmpty().contains("key set", ignoreCase = true)
                                ) {
                                    OutlinedButton(
                                        onClick = onOpenApiManager,
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("API Manager")
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.generateShortsScript(
                                topic = topicPrompt,
                                vibe = selectedVibe,
                                targetSec = selectedDurationSec
                            )
                        },
                        enabled = topicPrompt.isNotBlank() && !state.isGeneratingScript && !emergencyStopped,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("shorts_generate_script_btn")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.isGeneratingScript) "Writing..." else "Generate Script")
                    }
                }

                Text("Starter Templates (Offline Editable)", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ShortsScriptEngine.starterTemplates()) { tpl ->
                        OutlinedButton(
                            onClick = { viewModel.loadShortsTemplate(tpl) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("shorts_template_${tpl.id}")
                        ) {
                            Text(tpl.title, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // 3. Project Title, Hook & Hashtags
        item {
            NeuralCard {
                SectionHeader(
                    title = "Title, Hook & Tags",
                    subtitle = "${project.scenes.size} scenes • ${formatMsSeconds(project.totalDurationMs())}"
                )
                OutlinedTextField(
                    value = project.title,
                    onValueChange = { viewModel.updateShortsMeta(it, project.hook, project.hashtags) },
                    label = { Text("Short Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shorts_title_input")
                )
                OutlinedTextField(
                    value = project.hook,
                    onValueChange = { viewModel.updateShortsMeta(project.title, it, project.hashtags) },
                    label = { Text("Opening Hook Banner") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shorts_hook_input")
                )
                OutlinedTextField(
                    value = project.hashtags,
                    onValueChange = { viewModel.updateShortsMeta(project.title, project.hook, it) },
                    label = { Text("Hashtags") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 4. Storyboard Timeline & Active Scene Editor
        item {
            val idx = state.selectedSceneIndex.coerceIn(0, (project.scenes.size - 1).coerceAtLeast(0))
            val scene = project.scenes.getOrNull(idx)
            NeuralCard(borderColor = NeuralViolet.copy(alpha = 0.5f)) {
                SectionHeader(
                    title = "Scene Timeline (${project.scenes.size})",
                    subtitle = "Tap scene pill to edit photo, caption, duration & transition",
                    trailing = {
                        FilledTonalButton(
                            onClick = { viewModel.addShortsScene() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("shorts_add_scene_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Scene", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Scene")
                        }
                    }
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(project.scenes) { i, sc ->
                        FilterChip(
                            selected = i == idx,
                            onClick = { viewModel.selectShortsScene(i) },
                            label = {
                                Text(
                                    "S${i + 1} (${sc.durationSec.roundToInt()}s)",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            modifier = Modifier.testTag("shorts_scene_chip_$i")
                        )
                    }
                }

                if (scene != null) {
                    ShortsSceneEditorBlock(
                        sceneIndex = idx,
                        sceneCount = project.scenes.size,
                        scene = scene,
                        onUpdate = { updated -> viewModel.updateShortsScene(idx, updated) },
                        onMoveUp = { viewModel.moveShortsScene(idx, -1) },
                        onMoveDown = { viewModel.moveShortsScene(idx, +1) },
                        onDelete = { viewModel.deleteShortsScene(idx) },
                        onPickPhoto = {
                            pendingPhotoSceneIndex = idx
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onClearPhoto = { viewModel.clearShortsScenePhoto(idx) }
                    )
                }
            }
        }

        // 5. Audio, Voiceover TTS & Lyria Music Card
        item {
            NeuralCard {
                SectionHeader(
                    title = "Voiceover & Music",
                    subtitle = "Attach TTS narration, Lyria 3 music track, or custom audio"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("TTS Voiceover Narration", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Synthesize spoken Hinglish/English voiceover into exported MP4",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = project.includeVoiceoverTts,
                        onCheckedChange = { viewModel.setShortsVoiceoverEnabled(it) },
                        modifier = Modifier.testTag("shorts_tts_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show Teena Watermark", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Subtle bottom badge on rendered 9:16 frames",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = project.showWatermark,
                        onCheckedChange = { viewModel.setShortsWatermarkEnabled(it) }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("shorts_pick_audio_btn")
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Pick Audio File", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onOpenMusicStudio,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                    ) {
                        Text("Lyria Music Studio", maxLines = 1, softWrap = false)
                    }
                }

                if (project.bgmTrackTitle.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Attached BGM: ${project.bgmTrackTitle}",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeuralMint,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearShortsBgmAudio() },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear audio")
                        }
                    }
                }

                if (lyriaTracks.isNotEmpty()) {
                    Text("Use Generated Lyria Track", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(lyriaTracks, key = { it.id }) { track ->
                            FilterChip(
                                selected = project.bgmFilePath == track.filePath,
                                onClick = { viewModel.selectShortsLyriaTrack(track) },
                                label = { Text(track.title.take(24), maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                }
            }
        }

        // 6. Export 9:16 MP4 Card
        item {
            NeuralCard(borderColor = NeuralMint.copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Render & Export 9:16 MP4",
                    subtitle = "Hardware/Software H.264 MediaCodec + MediaMuxer vertical video"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortsExportResolution.entries.forEach { res ->
                        FilterChip(
                            selected = state.selectedResolution == res,
                            onClick = { viewModel.selectShortsResolution(res) },
                            label = { Text(res.label, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("shorts_res_${res.name.lowercase()}")
                        )
                    }
                }

                state.exportProgress?.let { prog ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { prog.progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = NeuralMint
                        )
                        Text(
                            text = "${prog.phase} (${(prog.progressFraction * 100).roundToInt()}%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeuralCyan
                        )
                    }
                }

                if (!state.exportError.isNullOrBlank()) {
                    Text(
                        text = state.exportError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusCrimson
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.exportShortsMp4() },
                        enabled = !state.isExporting && project.scenes.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("shorts_export_mp4_btn")
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.isExporting) "Rendering MP4..." else "Export 9:16 MP4")
                    }
                    if (state.isExporting) {
                        OutlinedButton(
                            onClick = { viewModel.cancelShortsWork() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCrimson),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("shorts_export_cancel_btn")
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        // 7. Exported Shorts Library
        item {
            SectionHeader(
                title = "Exported Shorts (${state.exports.size})",
                subtitle = "Play, save to Movies/TeenaShorts gallery, or share"
            )
        }

        if (state.exports.isEmpty()) {
            item {
                NeuralCard {
                    Text(
                        text = "Abhi koi MP4 export nahi hua hai. Upar 'Export 9:16 MP4' tap karein!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.exports, key = { it.id }) { rec ->
                ShortsExportRecordCard(
                    record = rec,
                    onPlay = { viewModel.openShortsExportPreview(rec) },
                    onSaveGallery = { viewModel.saveShortsExportToMovies(rec) },
                    onShare = { viewModel.shareShortsExport(rec) },
                    onDelete = { viewModel.deleteShortsExport(rec) }
                )
            }
        }
    }
}

@Composable
private fun ShortsPreviewStageCard(
    project: ShortsProject,
    state: ShortsCreatorUiState,
    activeFrame: ActiveShortsFrame?,
    onTogglePlay: () -> Unit,
    onRestart: () -> Unit,
    onPrevScene: () -> Unit,
    onNextScene: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeakPreview: () -> Unit
) {
    val totalMs = project.totalDurationMs().coerceAtLeast(1L)
    val scene = activeFrame?.scene ?: project.scenes.firstOrNull()
    val progress = activeFrame?.sceneProgress ?: 0f
    val sceneIndex = activeFrame?.sceneIndex ?: 0

    NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.5f)) {
        SectionHeader(
            title = "9:16 Live Stage",
            subtitle = "${formatMsSeconds(state.playheadMs)} / ${formatMsSeconds(totalMs)} • Scene ${sceneIndex + 1}/${project.scenes.size.coerceAtLeast(1)}",
            trailing = {
                IconButton(
                    onClick = onSpeakPreview,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("shorts_preview_speak_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speak voiceover")
                }
            }
        )

        if (scene != null) {
            val topColor = Color(scene.theme.topColorArgb)
            val bottomColor = Color(scene.theme.bottomColorArgb)
            val accentColor = Color(scene.theme.accentColorArgb)

            val scale = when (scene.transition) {
                ShortsSceneTransition.ZOOM_IN -> 1f + 0.12f * progress
                ShortsSceneTransition.PAN_LEFT -> 1.1f
                ShortsSceneTransition.PULSE -> 1.03f + 0.04f * sin(progress * PI).toFloat()
                ShortsSceneTransition.FADE -> 1.02f
            }
            val transX = if (scene.transition == ShortsSceneTransition.PAN_LEFT) {
                (0.5f - progress) * 28f
            } else {
                0f
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 250.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
                    .clickable { onTogglePlay() }
                    .testTag("shorts_preview_stage")
            ) {
                if (!scene.photoUriString.isNullOrBlank()) {
                    AsyncImage(
                        model = Uri.parse(scene.photoUriString),
                        contentDescription = scene.headline,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = transX
                            }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.55f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.78f)
                                    )
                                )
                            )
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top progress bars + Hook pill
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            project.scenes.forEachIndexed { i, _ ->
                                val segFill = when {
                                    i < sceneIndex -> 1f
                                    i == sceneIndex -> progress
                                    else -> 0f
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White.copy(alpha = 0.25f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(segFill)
                                            .height(4.dp)
                                            .background(accentColor)
                                    )
                                }
                            }
                        }

                        if (project.hook.isNotBlank()) {
                            Surface(
                                color = Color(0xCC0A111D),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, accentColor),
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(
                                    text = project.hook,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // Bottom kinetic caption box + tags
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = scene.headline.uppercase(),
                            color = accentColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Surface(
                            color = Color(0xBA09101C),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = scene.captionText,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                        if (project.hashtags.isNotBlank()) {
                            Text(
                                text = project.hashtags,
                                color = Color.White.copy(alpha = 0.78f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Slider(
            value = state.playheadMs.toFloat().coerceIn(0f, totalMs.toFloat()),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..totalMs.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onRestart, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Default.Replay, contentDescription = "Restart preview")
            }
            IconButton(onClick = onPrevScene, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous scene")
            }
            FilledTonalButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("shorts_preview_play_btn")
            ) {
                Icon(
                    if (state.isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPreviewPlaying) "Pause preview" else "Play preview"
                )
                Spacer(Modifier.size(4.dp))
                Text(if (state.isPreviewPlaying) "Pause" else "Play")
            }
            IconButton(onClick = onNextScene, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next scene")
            }
        }
    }
}

@Composable
private fun ShortsSceneEditorBlock(
    sceneIndex: Int,
    sceneCount: Int,
    scene: ShortsScene,
    onUpdate: ((ShortsScene) -> ShortsScene) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Editing Scene ${sceneIndex + 1} of $sceneCount",
                style = MaterialTheme.typography.titleSmall,
                color = NeuralCyan
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = sceneIndex > 0,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move scene earlier")
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = sceneIndex < sceneCount - 1,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move scene later")
                }
                IconButton(
                    onClick = onDelete,
                    enabled = sceneCount > 1,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete scene", tint = StatusCrimson)
                }
            }
        }

        OutlinedTextField(
            value = scene.headline,
            onValueChange = { v -> onUpdate { it.copy(headline = v.take(48)) } },
            label = { Text("Scene Headline") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = scene.captionText,
            onValueChange = { v -> onUpdate { it.copy(captionText = v.take(120)) } },
            label = { Text("On-Screen Caption") },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("shorts_scene_caption_input")
        )
        OutlinedTextField(
            value = scene.voiceoverLine,
            onValueChange = { v -> onUpdate { it.copy(voiceoverLine = v.take(220)) } },
            label = { Text("Voiceover Narration Line") },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Duration: ${scene.durationSec.roundToInt()}s",
            style = MaterialTheme.typography.labelMedium
        )
        Slider(
            value = scene.durationSec.coerceIn(2f, 12f),
            onValueChange = { dur -> onUpdate { it.copy(durationSec = dur) } },
            valueRange = 2f..12f,
            steps = 9
        )

        Text("Color Backdrop", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ShortsBackgroundTheme.entries) { theme ->
                FilterChip(
                    selected = scene.theme == theme,
                    onClick = { onUpdate { it.copy(theme = theme) } },
                    label = { Text(theme.label, maxLines = 1, softWrap = false) }
                )
            }
        }

        Text("Motion Transition", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ShortsSceneTransition.entries) { tr ->
                FilterChip(
                    selected = scene.transition == tr,
                    onClick = { onUpdate { it.copy(transition = tr) } },
                    label = { Text(tr.label, maxLines = 1, softWrap = false) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onPickPhoto,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("shorts_pick_photo_btn")
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(if (scene.photoUriString.isNullOrBlank()) "Pick Photo (Photo Picker)" else "Change Photo")
            }
            if (!scene.photoUriString.isNullOrBlank()) {
                OutlinedButton(
                    onClick = onClearPhoto,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Remove Photo")
                }
            }
        }
    }
}

@Composable
private fun ShortsExportRecordCard(
    record: ShortsExportRecord,
    onPlay: () -> Unit,
    onSaveGallery: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    NeuralCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VideoFile, contentDescription = null, tint = NeuralMint)
                Column {
                    Text(record.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${record.resolutionLabel} • ${"%.1f".format(record.durationSec)}s • ${record.sizeBytes / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (record.audioNote.isNotBlank()) {
                        Text(
                            text = record.audioNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (record.hasAudio) NeuralMint else StatusAmber
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Default.Delete, contentDescription = "Delete export", tint = StatusCrimson)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onPlay,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Play", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onSaveGallery,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(if (record.mediaStoreUri != null) "Saved" else "Save", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Share", maxLines = 1, softWrap = false)
            }
        }
    }
}

private fun formatMsSeconds(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%02d:%02d".format(mins, secs)
}
