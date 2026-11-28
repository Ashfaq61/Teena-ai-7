package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.ai.GeminiRepository
import com.example.ai.LyriaGeneratedTrack
import com.example.ai.ShortsBackgroundTheme
import com.example.ai.ShortsExportRecord
import com.example.ai.ShortsExportResolution
import com.example.ai.ShortsProject
import com.example.ai.ShortsScene
import com.example.ai.ShortsSceneTransition
import com.example.ai.ShortsScriptEngine
import com.example.ai.ShortsVibePreset
import com.example.core.TeenaServiceContainer
import com.example.core.security.SecurityAuditor
import com.example.system.ShortsExportProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ShortsCreatorUiState(
    val project: ShortsProject = ShortsScriptEngine.starterTemplates().first(),
    val savedProjects: List<ShortsProject> = emptyList(),
    val exports: List<ShortsExportRecord> = emptyList(),
    val selectedSceneIndex: Int = 0,
    val selectedResolution: ShortsExportResolution = ShortsExportResolution.HD_720P,
    val isGeneratingScript: Boolean = false,
    val scriptError: String? = null,
    val lastScriptTopic: String = "",
    val lastScriptVibe: ShortsVibePreset = ShortsVibePreset.HINGLISH_TECH,
    val lastScriptTargetSec: Int = 20,
    val isExporting: Boolean = false,
    val exportProgress: ShortsExportProgress? = null,
    val exportError: String? = null,
    val isPreviewPlaying: Boolean = false,
    val playheadMs: Long = 0L,
    val activeVideoPreviewPath: String? = null
) {
    val isActive: Boolean
        get() = isGeneratingScript || isExporting
}

class ShortsCoordinator(
    private val appContext: Context,
    private val container: TeenaServiceContainer,
    private val scope: CoroutineScope,
    private val getGeminiModel: () -> String,
    private val isEmergencyStopped: () -> Boolean,
    private val onBanner: (String) -> Unit
) {
    private val store = container.shortsProjectStore
    private val scriptEngine = container.shortsScriptEngine
    private val exporter = container.shortsVideoExporter

    private val _uiState = MutableStateFlow(ShortsCreatorUiState())
    val uiState: StateFlow<ShortsCreatorUiState> = _uiState.asStateFlow()

    private var scriptJob: Job? = null
    private var exportJob: Job? = null
    private var playbackTickerJob: Job? = null

    init {
        scope.launch {
            val loadedProjects = withContext(Dispatchers.IO) { store.loadProjects() }
            val loadedExports = withContext(Dispatchers.IO) { store.loadExports() }
            val active = loadedProjects.firstOrNull() ?: ShortsScriptEngine.starterTemplates().first()
            _uiState.value = _uiState.value.copy(
                project = active,
                savedProjects = loadedProjects,
                exports = loadedExports
            )
        }
    }

    fun generateScriptWithGemini(
        topic: String,
        vibe: ShortsVibePreset,
        targetSec: Int
    ) {
        val clean = topic.trim()
        if (clean.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                scriptError = "Pehle Shorts ka topic ya idea likhein."
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            lastScriptTopic = clean,
            lastScriptVibe = vibe,
            lastScriptTargetSec = targetSec,
            scriptError = null
        )
        if (isEmergencyStopped()) {
            _uiState.value = _uiState.value.copy(
                scriptError = "Gemini is not available — Emergency Stop active hai. Resume karein."
            )
            return
        }

        scriptJob?.cancel()
        scriptJob = scope.launch {
            _uiState.value = _uiState.value.copy(
                isGeneratingScript = true,
                scriptError = null,
                isPreviewPlaying = false
            )
            try {
                val modelId = GeminiRepository.normalizeModelId(getGeminiModel())
                val res = scriptEngine.generateStoryboardWithGemini(
                    topicPrompt = clean,
                    vibe = vibe,
                    targetDurationSec = targetSec,
                    preferredModel = modelId
                )
                res.onSuccess { generated ->
                    val preserved = generated.copy(
                        bgmTrackTitle = _uiState.value.project.bgmTrackTitle,
                        bgmFilePath = _uiState.value.project.bgmFilePath,
                        bgmUriString = _uiState.value.project.bgmUriString,
                        includeVoiceoverTts = _uiState.value.project.includeVoiceoverTts,
                        showWatermark = _uiState.value.project.showWatermark
                    )
                    updateAndPersistProject(preserved, resetPlayhead = true)
                    onBanner("Shorts script ready (${preserved.scenes.size} scenes)!")
                }.onFailure { err ->
                    val msg = SecurityAuditor.redactSecrets(
                        err.message ?: "Gemini is not available — Shorts script fail ho gayi."
                    )
                    _uiState.value = _uiState.value.copy(scriptError = msg)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.value = _uiState.value.copy(isGeneratingScript = false)
            }
        }
    }

    fun retryLastScriptGeneration() {
        val st = _uiState.value
        if (st.lastScriptTopic.isBlank()) return
        generateScriptWithGemini(st.lastScriptTopic, st.lastScriptVibe, st.lastScriptTargetSec)
    }

    fun loadTemplate(template: ShortsProject) {
        val fresh = template.copy(
            id = UUID.randomUUID().toString().take(10),
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
        _uiState.value = _uiState.value.copy(scriptError = null)
        updateAndPersistProject(fresh, resetPlayhead = true)
        onBanner("Starter template loaded: ${fresh.title}")
    }

    fun selectSavedProject(project: ShortsProject) {
        updateAndPersistProject(project, resetPlayhead = true)
        onBanner("Opened project: ${project.title}")
    }

    fun updateTitleAndMeta(title: String, hook: String, hashtags: String) {
        val cur = _uiState.value.project
        updateAndPersistProject(
            cur.copy(
                title = title.take(60),
                hook = hook.take(70),
                hashtags = hashtags.take(100),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    fun setVoiceoverEnabled(enabled: Boolean) {
        val cur = _uiState.value.project
        updateAndPersistProject(cur.copy(includeVoiceoverTts = enabled, updatedAtMs = System.currentTimeMillis()))
    }

    fun setWatermarkEnabled(enabled: Boolean) {
        val cur = _uiState.value.project
        updateAndPersistProject(cur.copy(showWatermark = enabled, updatedAtMs = System.currentTimeMillis()))
    }

    fun selectResolution(resolution: ShortsExportResolution) {
        _uiState.value = _uiState.value.copy(selectedResolution = resolution)
    }

    fun selectLyriaTrack(track: LyriaGeneratedTrack) {
        val cur = _uiState.value.project
        updateAndPersistProject(
            cur.copy(
                bgmTrackTitle = track.title,
                bgmFilePath = track.filePath,
                bgmUriString = null,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        onBanner("Audio attached: ${track.title}")
    }

    fun attachExternalAudioUri(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val cur = _uiState.value.project
        val name = uri.lastPathSegment?.substringAfterLast('/')?.take(28) ?: "Selected Audio"
        updateAndPersistProject(
            cur.copy(
                bgmTrackTitle = name,
                bgmFilePath = null,
                bgmUriString = uri.toString(),
                updatedAtMs = System.currentTimeMillis()
            )
        )
        onBanner("Attached audio: $name")
    }

    fun clearBgmAudio() {
        val cur = _uiState.value.project
        updateAndPersistProject(
            cur.copy(
                bgmTrackTitle = "",
                bgmFilePath = null,
                bgmUriString = null,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    fun selectScene(index: Int) {
        val scenes = _uiState.value.project.scenes
        if (scenes.isEmpty()) return
        val clampedIdx = index.coerceIn(0, scenes.lastIndex)
        var startMs = 0L
        for (i in 0 until clampedIdx) {
            startMs += (scenes[i].durationSec * 1000f).toLong()
        }
        _uiState.value = _uiState.value.copy(
            selectedSceneIndex = clampedIdx,
            playheadMs = startMs
        )
    }

    fun addScene() {
        val cur = _uiState.value.project
        if (cur.scenes.size >= 8) {
            onBanner("Maximum 8 scenes per Short recommended.")
            return
        }
        val nextIdx = cur.scenes.size
        val newScene = ShortsScene(
            headline = "Scene ${nextIdx + 1}",
            captionText = "Key point ${nextIdx + 1} yahan likhein",
            voiceoverLine = "Key point ${nextIdx + 1}.",
            durationSec = 4f,
            theme = ShortsBackgroundTheme.entries[nextIdx % ShortsBackgroundTheme.entries.size],
            transition = ShortsSceneTransition.entries[nextIdx % ShortsSceneTransition.entries.size]
        )
        val updated = cur.copy(
            scenes = cur.scenes + newScene,
            updatedAtMs = System.currentTimeMillis()
        )
        updateAndPersistProject(updated)
        selectScene(updated.scenes.lastIndex)
    }

    fun updateScene(index: Int, transform: (ShortsScene) -> ShortsScene) {
        val cur = _uiState.value.project
        if (index !in cur.scenes.indices) return
        val newScenes = cur.scenes.toMutableList()
        newScenes[index] = transform(newScenes[index])
        updateAndPersistProject(
            cur.copy(scenes = newScenes, updatedAtMs = System.currentTimeMillis())
        )
    }

    fun moveScene(index: Int, delta: Int) {
        val cur = _uiState.value.project
        val target = index + delta
        if (index !in cur.scenes.indices || target !in cur.scenes.indices) return
        val list = cur.scenes.toMutableList()
        val item = list.removeAt(index)
        list.add(target, item)
        updateAndPersistProject(cur.copy(scenes = list, updatedAtMs = System.currentTimeMillis()))
        selectScene(target)
    }

    fun deleteScene(index: Int) {
        val cur = _uiState.value.project
        if (cur.scenes.size <= 1) {
            onBanner("Kam se kam 1 scene zaroori hai.")
            return
        }
        if (index !in cur.scenes.indices) return
        val list = cur.scenes.toMutableList().apply { removeAt(index) }
        val newSel = index.coerceAtMost(list.lastIndex)
        updateAndPersistProject(cur.copy(scenes = list, updatedAtMs = System.currentTimeMillis()))
        selectScene(newSel)
    }

    fun attachPhotoToScene(index: Int, uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        updateScene(index) { it.copy(photoUriString = uri.toString()) }
        onBanner("Photo attached to Scene ${index + 1}.")
    }

    fun clearScenePhoto(index: Int) {
        updateScene(index) { it.copy(photoUriString = null) }
    }

    fun togglePreviewPlayback() {
        val currentlyPlaying = _uiState.value.isPreviewPlaying
        if (currentlyPlaying) {
            pausePreview()
        } else {
            startPreview()
        }
    }

    fun restartPreview() {
        _uiState.value = _uiState.value.copy(playheadMs = 0L, selectedSceneIndex = 0)
        startPreview()
    }

    fun seekPreviewTo(ms: Long) {
        val project = _uiState.value.project
        val total = project.totalDurationMs().coerceAtLeast(1L)
        val clamped = ms.coerceIn(0L, total)
        val frame = ShortsScriptEngine.resolveActiveSceneAt(project.scenes, clamped)
        _uiState.value = _uiState.value.copy(
            playheadMs = clamped,
            selectedSceneIndex = frame?.sceneIndex ?: _uiState.value.selectedSceneIndex
        )
    }

    private fun startPreview() {
        playbackTickerJob?.cancel()
        _uiState.value = _uiState.value.copy(isPreviewPlaying = true)
        playbackTickerJob = scope.launch {
            while (isActive && _uiState.value.isPreviewPlaying) {
                delay(60L)
                val proj = _uiState.value.project
                val total = proj.totalDurationMs().coerceAtLeast(1000L)
                var nextMs = _uiState.value.playheadMs + 60L
                if (nextMs >= total) nextMs = 0L
                val frame = ShortsScriptEngine.resolveActiveSceneAt(proj.scenes, nextMs)
                _uiState.value = _uiState.value.copy(
                    playheadMs = nextMs,
                    selectedSceneIndex = frame?.sceneIndex ?: _uiState.value.selectedSceneIndex
                )
            }
        }
    }

    fun pausePreview() {
        playbackTickerJob?.cancel()
        _uiState.value = _uiState.value.copy(isPreviewPlaying = false)
    }

    fun speakPreviewNarration() {
        val proj = _uiState.value.project
        val text = buildString {
            if (proj.hook.isNotBlank()) append("${proj.hook}. ")
            proj.scenes.forEach { s ->
                append(s.voiceoverLine.ifBlank { s.captionText })
                append(". ")
            }
        }.trim()
        container.speechHelper.speak(text)
            .onSuccess { onBanner("Reading Shorts voiceover aloud...") }
            .onFailure { onBanner(it.localizedMessage ?: "TTS unavailable") }
    }

    fun exportMp4() {
        if (_uiState.value.isExporting) return
        pausePreview()
        val project = _uiState.value.project
        val resolution = _uiState.value.selectedResolution

        exportJob?.cancel()
        exportJob = scope.launch {
            _uiState.value = _uiState.value.copy(
                isExporting = true,
                exportError = null,
                exportProgress = ShortsExportProgress("Initializing encoder...", 0.02f, 0, 1)
            )
            try {
                val res = exporter.exportProjectToMp4(project, resolution) { prog ->
                    _uiState.value = _uiState.value.copy(exportProgress = prog)
                }
                res.onSuccess { record ->
                    val nextExports = listOf(record) + _uiState.value.exports.filterNot { it.id == record.id }
                    withContext(Dispatchers.IO) { store.saveExports(nextExports) }
                    _uiState.value = _uiState.value.copy(
                        exports = nextExports,
                        activeVideoPreviewPath = record.filePath,
                        exportError = null
                    )
                    onBanner("9:16 MP4 exported (${record.sizeBytes / 1024} KB)!")
                }.onFailure { err ->
                    val raw = err.localizedMessage ?: "MP4 export failed on this device."
                    _uiState.value = _uiState.value.copy(exportError = raw)
                    onBanner("Export failed: $raw")
                }
            } catch (e: CancellationException) {
                onBanner("Shorts MP4 export cancelled.")
                throw e
            } finally {
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportProgress = null
                )
            }
        }
    }

    fun cancelActiveWork() {
        scriptJob?.cancel()
        exportJob?.cancel()
        pausePreview()
        _uiState.value = _uiState.value.copy(
            isGeneratingScript = false,
            isExporting = false,
            exportProgress = null
        )
    }

    fun saveExportToMovies(record: ShortsExportRecord) {
        scope.launch {
            val file = File(record.filePath)
            exporter.saveVideoToMoviesMediaStore(file, record.title)
                .onSuccess { uri ->
                    val updatedList = _uiState.value.exports.map {
                        if (it.id == record.id) it.copy(mediaStoreUri = uri.toString()) else it
                    }
                    withContext(Dispatchers.IO) { store.saveExports(updatedList) }
                    _uiState.value = _uiState.value.copy(exports = updatedList)
                    onBanner("Saved to Movies/TeenaShorts!")
                }
                .onFailure { err ->
                    onBanner(err.localizedMessage ?: "Failed to save to Movies.")
                }
        }
    }

    fun shareExport(record: ShortsExportRecord) {
        val file = File(record.filePath)
        if (!file.exists()) {
            onBanner("Exported MP4 file no longer exists.")
            return
        }
        runCatching {
            val intent = exporter.buildShareVideoIntent(file, record.title)
            appContext.startActivity(Intent.createChooser(intent, "Share Teena Short").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { err ->
            onBanner(err.localizedMessage ?: "Unable to open share sheet.")
        }
    }

    fun openExportInPreview(record: ShortsExportRecord) {
        val file = File(record.filePath)
        if (!file.exists()) {
            onBanner("Video file not found.")
            return
        }
        _uiState.value = _uiState.value.copy(activeVideoPreviewPath = record.filePath)
    }

    fun closeVideoPreview() {
        _uiState.value = _uiState.value.copy(activeVideoPreviewPath = null)
    }

    fun deleteExport(record: ShortsExportRecord) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(record.filePath).delete() }
            }
            val nextExports = _uiState.value.exports.filterNot { it.id == record.id }
            withContext(Dispatchers.IO) { store.saveExports(nextExports) }
            _uiState.value = _uiState.value.copy(
                exports = nextExports,
                activeVideoPreviewPath = if (_uiState.value.activeVideoPreviewPath == record.filePath) null else _uiState.value.activeVideoPreviewPath
            )
            onBanner("Deleted exported Short.")
        }
    }

    private fun updateAndPersistProject(project: ShortsProject, resetPlayhead: Boolean = false) {
        val existing = _uiState.value.savedProjects.filterNot { it.id == project.id }
        val nextSaved = (listOf(project) + existing).take(20)
        _uiState.value = _uiState.value.copy(
            project = project,
            savedProjects = nextSaved,
            playheadMs = if (resetPlayhead) 0L else _uiState.value.playheadMs.coerceAtMost(project.totalDurationMs()),
            selectedSceneIndex = if (resetPlayhead) 0 else _uiState.value.selectedSceneIndex.coerceIn(0, (project.scenes.size - 1).coerceAtLeast(0))
        )
        scope.launch(Dispatchers.IO) {
            store.saveProjects(nextSaved)
        }
    }
}
