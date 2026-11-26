package com.example.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.core.security.EmergencyStopController
import com.example.core.security.SecurityAuditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

enum class LyriaModelMode(
    val modelId: String,
    val shortTitle: String,
    val durationTag: String,
    val description: String
) {
    CLIP_30S(
        modelId = "lyria-3-clip-preview",
        shortTitle = "Short Clip (≤ 30s)",
        durationTag = "Up to 30s",
        description = "Fast loops, reels & background clips via lyria-3-clip-preview"
    ),
    FULL_PRO(
        modelId = "lyria-3-pro-preview",
        shortTitle = "Full Track (Pro)",
        durationTag = "Full-length",
        description = "Multi-section full songs with verses & chorus via lyria-3-pro-preview"
    )
}

data class LyriaGeneratedTrack(
    val id: String,
    val title: String,
    val prompt: String,
    val modelId: String,
    val mimeType: String,
    val filePath: String,
    val lyricsOrNotes: String,
    val durationTag: String,
    val createdAtMs: Long,
    val sizeBytes: Long
)

data class LyriaPlaybackState(
    val activeTrackId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
)

data class ParsedLyriaAudio(
    val mimeType: String,
    val audioBytes: ByteArray,
    val lyricsOrNotes: String
)

class LyriaMusicEngine(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val emergencyStopController: EmergencyStopController? = null,
    private val geminiBaseUrl: String = "https://generativelanguage.googleapis.com"
) {
    companion object {
        private const val TAG = "LyriaMusicEngine"
        private const val API_REVISION_HEADER = "2026-05-20"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BASE64_INV = IntArray(256) { -1 }.apply {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { idx, ch ->
                this[ch.code] = idx
            }
            // URL-safe base64 support too
            this['-'.code] = 62
            this['_'.code] = 63
        }

        fun decodeBase64Clean(input: String): ByteArray {
            val out = ByteArrayOutputStream((input.length * 3) / 4 + 4)
            var buffer = 0
            var bits = 0
            for (ch in input) {
                if (ch.isWhitespace() || ch == '=') continue
                val code = ch.code
                val v = if (code in 0..255) BASE64_INV[code] else -1
                if (v < 0) continue
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            return out.toByteArray()
        }

        fun buildMusicPrompt(
            rawPrompt: String,
            moodOrGenre: String = "",
            bpm: Int? = null,
            instrumentalOnly: Boolean = false
        ): String {
            val cleanPrompt = rawPrompt.trim()
            val directives = buildList {
                if (moodOrGenre.isNotBlank()) add("Style/Genre: ${moodOrGenre.trim()}")
                if (bpm != null && bpm in 40..220) add("Tempo: $bpm BPM")
                if (instrumentalOnly) add("Instrumental only, no vocals")
            }
            return if (directives.isEmpty()) {
                cleanPrompt
            } else {
                "$cleanPrompt (${directives.joinToString(", ")})"
            }
        }

        fun buildInteractionsRequestJson(
            modelMode: LyriaModelMode,
            engineeredPrompt: String
        ): JSONObject = JSONObject().apply {
            put("model", modelMode.modelId)
            put("input", engineeredPrompt.trim())
            put("store", false)
        }

        fun extensionForMimeType(mimeType: String): String = when {
            mimeType.contains("wav", ignoreCase = true) -> "wav"
            mimeType.contains("ogg", ignoreCase = true) -> "ogg"
            mimeType.contains("aac", ignoreCase = true) -> "aac"
            else -> "mp3"
        }

        fun parseInteractionsAudioResponse(rawJson: String): Result<ParsedLyriaAudio> = runCatching {
            val root = JSONObject(rawJson)
            var foundMime = "audio/mp3"
            var foundBase64 = ""
            val notes = mutableListOf<String>()

            fun inspectContentItem(item: JSONObject?) {
                if (item == null) return
                val type = item.optString("type").lowercase()
                if (type == "audio") {
                    val b64 = item.optString("data").ifBlank {
                        item.optJSONObject("audio")?.optString("data").orEmpty()
                    }
                    if (b64.isNotBlank() && foundBase64.isBlank()) {
                        foundBase64 = b64
                        foundMime = item.optString("mime_type")
                            .ifBlank { item.optString("mimeType") }
                            .ifBlank { "audio/mp3" }
                    }
                } else if (type == "text") {
                    item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }
                }

                // Fallback inlineData shape (if returned by gateway/model variant)
                val inline = item.optJSONObject("inlineData") ?: item.optJSONObject("inline_data")
                if (inline != null && foundBase64.isBlank()) {
                    val mime = inline.optString("mimeType").ifBlank { inline.optString("mime_type") }
                    val b64 = inline.optString("data")
                    if (mime.startsWith("audio/", ignoreCase = true) && b64.isNotBlank()) {
                        foundBase64 = b64
                        foundMime = mime
                    }
                }
                if (item.has("text") && type.isEmpty()) {
                    item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }
                }
            }

            // 1. Top-level outputs[]
            val outputs = root.optJSONArray("outputs")
            if (outputs != null) {
                for (i in 0 until outputs.length()) {
                    inspectContentItem(outputs.optJSONObject(i))
                }
            }

            // 2. Top-level output_audio / output_text convenience shape
            root.optJSONObject("output_audio")?.let { oa ->
                val b64 = oa.optString("data")
                if (b64.isNotBlank() && foundBase64.isBlank()) {
                    foundBase64 = b64
                    foundMime = oa.optString("mime_type").ifBlank { oa.optString("mimeType") }.ifBlank { "audio/mp3" }
                }
            }
            root.optString("output_text").trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }

            // 3. steps[].content[] (Interactions API multi-step format)
            val steps = root.optJSONArray("steps")
            if (steps != null) {
                for (s in 0 until steps.length()) {
                    val contentArr = steps.optJSONObject(s)?.optJSONArray("content") ?: continue
                    for (c in 0 until contentArr.length()) {
                        inspectContentItem(contentArr.optJSONObject(c))
                    }
                }
            }

            // 4. candidates[0].content.parts[] fallback
            val parts = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            if (parts != null) {
                for (p in 0 until parts.length()) {
                    inspectContentItem(parts.optJSONObject(p))
                }
            }

            if (foundBase64.isBlank()) {
                throw IllegalStateException("Lyria response contained no audio payload. ${notes.joinToString(" ").take(160)}")
            }

            val bytes = decodeBase64Clean(foundBase64)
            if (bytes.isEmpty()) {
                throw IllegalStateException("Decoded Lyria audio payload was empty.")
            }

            ParsedLyriaAudio(
                mimeType = foundMime,
                audioBytes = bytes,
                lyricsOrNotes = notes.distinct().joinToString("\n")
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val musicDir = File(context.filesDir, "teena_music").apply { mkdirs() }
    private val catalogFile = File(musicDir, "catalog.json")
    private val catalogLock = Any()

    private val _tracks = MutableStateFlow<List<LyriaGeneratedTrack>>(emptyList())
    val tracks: StateFlow<List<LyriaGeneratedTrack>> = _tracks.asStateFlow()

    private val _playback = MutableStateFlow(LyriaPlaybackState())
    val playback: StateFlow<LyriaPlaybackState> = _playback.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressTickerJob: Job? = null

    init {
        _tracks.value = loadCatalog()
    }

    suspend fun generateMusicTrack(
        apiKeysInOrder: List<Pair<String, String>>,
        mode: LyriaModelMode,
        rawPrompt: String,
        moodOrGenre: String = "",
        bpm: Int? = null,
        instrumentalOnly: Boolean = false
    ): Result<LyriaGeneratedTrack> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertExternalNetworkAllowed("Lyria 3 music generation")
            require(rawPrompt.trim().isNotEmpty()) { "Enter a music prompt (genre, instruments, mood, or lyrics idea)." }
            if (apiKeysInOrder.isEmpty()) {
                throw IllegalStateException("BLOCKED: Gemini API key missing. Add one in API Manager to generate music with ${mode.modelId}.")
            }

            val engineeredPrompt = buildMusicPrompt(rawPrompt, moodOrGenre, bpm, instrumentalOnly)
            val requestBodyJson = buildInteractionsRequestJson(mode, engineeredPrompt).toString()
            val url = "${geminiBaseUrl.trimEnd('/')}/v1beta/interactions"

            var lastError: Throwable? = null
            var parsedAudio: ParsedLyriaAudio? = null

            for ((slotLabel, apiKey) in apiKeysInOrder) {
                val cleanKey = apiKey.trim()
                if (cleanKey.isEmpty()) continue
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Api-Revision", API_REVISION_HEADER)
                    .addHeader("x-goog-api-key", cleanKey)
                    .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                try {
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val errMsg = SecurityAuditor.redactSecrets("Lyria (${mode.modelId}, $slotLabel) HTTP ${resp.code}: ${bodyStr.take(220)}")
                            lastError = IllegalStateException(errMsg)
                            if (resp.code == 429 || resp.code == 401 || resp.code == 403) {
                                return@use // Try next API key slot if present
                            }
                            throw lastError!!
                        }
                        parsedAudio = parseInteractionsAudioResponse(bodyStr).getOrThrow()
                    }
                    if (parsedAudio != null) break
                } catch (t: Throwable) {
                    lastError = t
                    if (!t.message.orEmpty().contains("429") && !t.message.orEmpty().contains("quota", ignoreCase = true)) {
                        throw t
                    }
                }
            }

            val finalAudio = parsedAudio ?: throw (lastError ?: IllegalStateException("Lyria music generation failed."))

            val trackId = "lyria_${UUID.randomUUID().toString().take(8)}"
            val ext = extensionForMimeType(finalAudio.mimeType)
            val outFile = File(musicDir, "$trackId.$ext")
            outFile.writeBytes(finalAudio.audioBytes)

            val trackTitle = rawPrompt.trim().replace('\n', ' ').take(42).let {
                if (rawPrompt.trim().length > 42) "$it..." else it
            }

            val created = LyriaGeneratedTrack(
                id = trackId,
                title = trackTitle,
                prompt = engineeredPrompt,
                modelId = mode.modelId,
                mimeType = finalAudio.mimeType,
                filePath = outFile.absolutePath,
                lyricsOrNotes = finalAudio.lyricsOrNotes,
                durationTag = mode.durationTag,
                createdAtMs = System.currentTimeMillis(),
                sizeBytes = outFile.length()
            )

            val updated = listOf(created) + _tracks.value.filterNot { it.id == created.id }
            saveCatalog(updated)
            _tracks.value = updated
            created
        }
    }

    fun playOrToggleTrack(track: LyriaGeneratedTrack) {
        val current = _playback.value
        if (current.activeTrackId == track.id && mediaPlayer != null) {
            runCatching {
                val player = mediaPlayer!!
                if (player.isPlaying) {
                    player.pause()
                    _playback.value = _playback.value.copy(isPlaying = false)
                } else {
                    player.start()
                    _playback.value = _playback.value.copy(isPlaying = true)
                    startTicker()
                }
            }.onFailure { err ->
                _playback.value = LyriaPlaybackState(error = err.localizedMessage)
            }
            return
        }

        stopPlayback()
        val file = File(track.filePath)
        if (!file.exists()) {
            _playback.value = LyriaPlaybackState(error = "Audio file not found locally.")
            return
        }
        runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _playback.value = _playback.value.copy(isPlaying = false, positionMs = _playback.value.durationMs)
                    progressTickerJob?.cancel()
                }
                setOnErrorListener { _, what, extra ->
                    _playback.value = LyriaPlaybackState(error = "Playback error ($what/$extra)")
                    stopPlayback()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _playback.value = LyriaPlaybackState(
                activeTrackId = track.id,
                isPlaying = true,
                positionMs = 0L,
                durationMs = player.duration.toLong().coerceAtLeast(1L),
                error = null
            )
            startTicker()
        }.onFailure { err ->
            Log.e(TAG, "Unable to start MediaPlayer", err)
            _playback.value = LyriaPlaybackState(error = err.localizedMessage ?: "Audio playback failed")
        }
    }

    fun seekTo(positionMs: Long) {
        runCatching {
            mediaPlayer?.let { player ->
                val clamped = positionMs.coerceIn(0L, player.duration.toLong().coerceAtLeast(0L)).toInt()
                player.seekTo(clamped)
                _playback.value = _playback.value.copy(positionMs = clamped.toLong())
            }
        }
    }

    fun stopPlayback() {
        progressTickerJob?.cancel()
        progressTickerJob = null
        runCatching {
            mediaPlayer?.let { p ->
                if (p.isPlaying) p.stop()
                p.release()
            }
        }
        mediaPlayer = null
        _playback.value = LyriaPlaybackState()
    }

    fun deleteTrack(trackId: String) {
        if (_playback.value.activeTrackId == trackId) {
            stopPlayback()
        }
        val target = _tracks.value.firstOrNull { it.id == trackId }
        if (target != null) {
            runCatching { File(target.filePath).delete() }
        }
        val next = _tracks.value.filterNot { it.id == trackId }
        saveCatalog(next)
        _tracks.value = next
    }

    suspend fun exportTrackToUri(track: LyriaGeneratedTrack, destUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val src = File(track.filePath)
            require(src.exists()) { "Generated audio file is missing." }
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                src.inputStream().use { inp -> inp.copyTo(out) }
            } ?: throw IllegalStateException("Unable to open destination file.")
            "Exported '${track.title}' cleanly."
        }
    }

    fun release() {
        stopPlayback()
    }

    private fun startTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch {
            while (isActive) {
                val player = mediaPlayer
                if (player == null || !runCatching { player.isPlaying }.getOrDefault(false)) {
                    break
                }
                _playback.value = _playback.value.copy(
                    positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L),
                    durationMs = runCatching { player.duration.toLong() }.getOrDefault(1L).coerceAtLeast(1L)
                )
                delay(250L)
            }
        }
    }

    private fun loadCatalog(): List<LyriaGeneratedTrack> = synchronized(catalogLock) {
        if (!catalogFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(catalogFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = obj.optString("filePath")
                if (path.isBlank() || !File(path).exists()) return@mapNotNull null
                LyriaGeneratedTrack(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    prompt = obj.optString("prompt"),
                    modelId = obj.optString("modelId", LyriaModelMode.CLIP_30S.modelId),
                    mimeType = obj.optString("mimeType", "audio/mp3"),
                    filePath = path,
                    lyricsOrNotes = obj.optString("lyricsOrNotes"),
                    durationTag = obj.optString("durationTag", "Up to 30s"),
                    createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                    sizeBytes = File(path).length()
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun saveCatalog(list: List<LyriaGeneratedTrack>) = synchronized(catalogLock) {
        runCatching {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("prompt", t.prompt)
                    put("modelId", t.modelId)
                    put("mimeType", t.mimeType)
                    put("filePath", t.filePath)
                    put("lyricsOrNotes", t.lyricsOrNotes)
                    put("durationTag", t.durationTag)
                    put("createdAtMs", t.createdAtMs)
                    put("sizeBytes", t.sizeBytes)
                })
            }
            catalogFile.writeText(arr.toString(2))
        }
    }
}
