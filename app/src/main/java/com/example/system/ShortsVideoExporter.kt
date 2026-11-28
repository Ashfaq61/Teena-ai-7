package com.example.system

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.FileProvider
import com.example.ai.ActiveShortsFrame
import com.example.ai.ShortsBackgroundTheme
import com.example.ai.ShortsExportRecord
import com.example.ai.ShortsExportResolution
import com.example.ai.ShortsProject
import com.example.ai.ShortsScene
import com.example.ai.ShortsSceneTransition
import com.example.ai.ShortsScriptEngine
import com.example.ai.ShortsVibePreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class ShortsExportProgress(
    val phase: String,
    val progressFraction: Float,
    val currentFrame: Int,
    val totalFrames: Int
)

class ShortsProjectStore(private val context: Context) {
    private val studioDir = File(context.filesDir, "shorts_studio").apply { mkdirs() }
    private val exportsDir = File(studioDir, "exports").apply { mkdirs() }
    private val audioTempDir = File(studioDir, "audio_tmp").apply { mkdirs() }
    private val projectsFile = File(studioDir, "projects.json")
    private val exportsFile = File(studioDir, "exports.json")

    fun exportsDirectory(): File = exportsDir
    fun audioTempDirectory(): File = audioTempDir

    fun loadProjects(): List<ShortsProject> {
        if (!projectsFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(projectsFile.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(projectFromJson(obj))
                }
            }.sortedByDescending { it.updatedAtMs }
        }.getOrDefault(emptyList())
    }

    fun saveProjects(projects: List<ShortsProject>) {
        val arr = JSONArray()
        projects.take(30).forEach { arr.put(projectToJson(it)) }
        projectsFile.writeText(arr.toString(2))
    }

    fun loadExports(): List<ShortsExportRecord> {
        if (!exportsFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(exportsFile.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val path = obj.optString("filePath")
                    if (path.isBlank() || !File(path).exists()) continue
                    add(
                        ShortsExportRecord(
                            id = obj.optString("id"),
                            projectId = obj.optString("projectId"),
                            title = obj.optString("title"),
                            filePath = path,
                            resolutionLabel = obj.optString("resolutionLabel", "720×1280 HD"),
                            durationSec = obj.optDouble("durationSec", 15.0).toFloat(),
                            sizeBytes = obj.optLong("sizeBytes", File(path).length()),
                            hasAudio = obj.optBoolean("hasAudio", false),
                            audioNote = obj.optString("audioNote", ""),
                            mediaStoreUri = obj.optString("mediaStoreUri").takeIf { it.isNotBlank() },
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }.sortedByDescending { it.createdAtMs }
        }.getOrDefault(emptyList())
    }

    fun saveExports(records: List<ShortsExportRecord>) {
        val arr = JSONArray()
        records.take(40).forEach { rec ->
            arr.put(
                JSONObject().apply {
                    put("id", rec.id)
                    put("projectId", rec.projectId)
                    put("title", rec.title)
                    put("filePath", rec.filePath)
                    put("resolutionLabel", rec.resolutionLabel)
                    put("durationSec", rec.durationSec.toDouble())
                    put("sizeBytes", rec.sizeBytes)
                    put("hasAudio", rec.hasAudio)
                    put("audioNote", rec.audioNote)
                    put("mediaStoreUri", rec.mediaStoreUri.orEmpty())
                    put("createdAtMs", rec.createdAtMs)
                }
            )
        }
        exportsFile.writeText(arr.toString(2))
    }

    private fun projectToJson(p: ShortsProject): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("title", p.title)
        put("hook", p.hook)
        put("vibe", p.vibe.name)
        put("targetDurationSec", p.targetDurationSec)
        put("hashtags", p.hashtags)
        put("bgmTrackTitle", p.bgmTrackTitle)
        put("bgmFilePath", p.bgmFilePath.orEmpty())
        put("bgmUriString", p.bgmUriString.orEmpty())
        put("includeVoiceoverTts", p.includeVoiceoverTts)
        put("showWatermark", p.showWatermark)
        put("createdAtMs", p.createdAtMs)
        put("updatedAtMs", p.updatedAtMs)
        put(
            "scenes",
            JSONArray().apply {
                p.scenes.forEach { s ->
                    put(
                        JSONObject().apply {
                            put("id", s.id)
                            put("headline", s.headline)
                            put("captionText", s.captionText)
                            put("voiceoverLine", s.voiceoverLine)
                            put("durationSec", s.durationSec.toDouble())
                            put("theme", s.theme.name)
                            put("transition", s.transition.name)
                            put("photoUriString", s.photoUriString.orEmpty())
                        }
                    )
                }
            }
        )
    }

    private fun projectFromJson(obj: JSONObject): ShortsProject {
        val scenesArr = obj.optJSONArray("scenes") ?: JSONArray()
        val scenes = buildList {
            for (i in 0 until scenesArr.length()) {
                val sObj = scenesArr.optJSONObject(i) ?: continue
                add(
                    ShortsScene(
                        id = sObj.optString("id", UUID.randomUUID().toString().take(8)),
                        headline = sObj.optString("headline", "Scene ${i + 1}"),
                        captionText = sObj.optString("captionText", ""),
                        voiceoverLine = sObj.optString("voiceoverLine", ""),
                        durationSec = sObj.optDouble("durationSec", 4.0).toFloat().coerceIn(1.5f, 15f),
                        theme = runCatching {
                            ShortsBackgroundTheme.valueOf(sObj.optString("theme"))
                        }.getOrDefault(ShortsBackgroundTheme.CYBER_CYAN),
                        transition = runCatching {
                            ShortsSceneTransition.valueOf(sObj.optString("transition"))
                        }.getOrDefault(ShortsSceneTransition.ZOOM_IN),
                        photoUriString = sObj.optString("photoUriString").takeIf { it.isNotBlank() }
                    )
                )
            }
        }.ifEmpty { ShortsScriptEngine.starterTemplates().first().scenes }

        return ShortsProject(
            id = obj.optString("id", UUID.randomUUID().toString().take(10)),
            title = obj.optString("title", "Teena Short"),
            hook = obj.optString("hook", ""),
            vibe = runCatching {
                ShortsVibePreset.valueOf(obj.optString("vibe"))
            }.getOrDefault(ShortsVibePreset.HINGLISH_TECH),
            targetDurationSec = obj.optInt("targetDurationSec", 20),
            hashtags = obj.optString("hashtags", "#shorts #teenaai"),
            scenes = scenes,
            bgmTrackTitle = obj.optString("bgmTrackTitle", ""),
            bgmFilePath = obj.optString("bgmFilePath").takeIf { it.isNotBlank() },
            bgmUriString = obj.optString("bgmUriString").takeIf { it.isNotBlank() },
            includeVoiceoverTts = obj.optBoolean("includeVoiceoverTts", true),
            showWatermark = obj.optBoolean("showWatermark", true),
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }
}

class ShortsVideoExporter(
    private val context: Context,
    private val store: ShortsProjectStore = ShortsProjectStore(context)
) {
    companion object {
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val FPS = 24

        /**
         * Converts ARGB_8888 pixel array into YUV420 Planar (I420) or Semi-Planar (NV12) bytes.
         * Ensures dimensions are rounded cleanly to even numbers by caller.
         */
        fun argbToYuv420(
            argb: IntArray,
            width: Int,
            height: Int,
            semiPlanar: Boolean
        ): ByteArray {
            val frameSize = width * height
            val yuv = ByteArray(frameSize + (frameSize / 2))
            var yIndex = 0
            var uIndex = frameSize
            var vIndex = frameSize + (frameSize / 4)
            var uvInterleavedIndex = frameSize

            for (j in 0 until height) {
                val rowOffset = j * width
                for (i in 0 until width) {
                    val px = argb[rowOffset + i]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF

                    val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                    yuv[yIndex++] = y.toByte()

                    if ((j and 1) == 0 && (i and 1) == 0) {
                        val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                        val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                        if (semiPlanar) {
                            if (uvInterleavedIndex + 1 < yuv.size) {
                                yuv[uvInterleavedIndex++] = u.toByte()
                                yuv[uvInterleavedIndex++] = v.toByte()
                            }
                        } else {
                            if (uIndex < frameSize + frameSize / 4) yuv[uIndex++] = u.toByte()
                            if (vIndex < yuv.size) yuv[vIndex++] = v.toByte()
                        }
                    }
                }
            }
            return yuv
        }

        fun pickSupportedYuvFormat(): Pair<String?, Int> {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(VIDEO_MIME, ignoreCase = true) }) continue
                val caps = runCatching { info.getCapabilitiesForType(VIDEO_MIME) }.getOrNull() ?: continue
                val formats = caps.colorFormats
                if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                    return info.name to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                }
                if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                    return info.name to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                }
                if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                    return info.name to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                }
            }
            return null to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    suspend fun exportProjectToMp4(
        project: ShortsProject,
        resolution: ShortsExportResolution,
        onProgress: (ShortsExportProgress) -> Unit
    ): Result<ShortsExportRecord> = withContext(Dispatchers.Default) {
        runCatching {
            val totalMs = project.totalDurationMs().coerceAtLeast(1500L)
            val totalFrames = ((totalMs / 1000.0) * FPS).toInt().coerceIn(24, FPS * 75)
            val width = resolution.width - (resolution.width % 2)
            val height = resolution.height - (resolution.height % 2)

            onProgress(ShortsExportProgress("Preparing scenes & audio...", 0.03f, 0, totalFrames))

            // 1. Optional TTS narration synthesis
            val safeTitle = project.title.ifBlank { "Teena_Short" }
                .replace(Regex("""[^a-zA-Z0-9_-]"""), "_")
                .take(28)
            val outMp4 = File(
                store.exportsDirectory(),
                "${safeTitle}_${System.currentTimeMillis()}.mp4"
            )

            var audioSourceFile: File? = null
            var tempTtsWav: File? = null
            var audioNote = ""

            if (project.includeVoiceoverTts) {
                val narration = buildString {
                    if (project.hook.isNotBlank()) {
                        append(project.hook.trim())
                        append(". ")
                    }
                    project.scenes.forEach { s ->
                        val line = s.voiceoverLine.ifBlank { s.captionText }.trim()
                        if (line.isNotBlank()) {
                            append(line)
                            append(". ")
                        }
                    }
                }.trim()
                if (narration.isNotBlank()) {
                    onProgress(ShortsExportProgress("Synthesizing voiceover...", 0.08f, 0, totalFrames))
                    val wavCandidate = File(store.audioTempDirectory(), "tts_${UUID.randomUUID().toString().take(8)}.wav")
                    val synthesized = synthesizeVoiceoverWav(narration, wavCandidate)
                    if (synthesized != null && synthesized.length() > 44L) {
                        tempTtsWav = synthesized
                        audioSourceFile = synthesized
                        audioNote = "TTS voiceover included"
                    }
                }
            }

            // Fallback to selected BGM if no TTS file was generated
            var tempBgmCopy: File? = null
            if (audioSourceFile == null) {
                val bgmPath = project.bgmFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                if (bgmPath != null) {
                    audioSourceFile = bgmPath
                    audioNote = "Music: ${project.bgmTrackTitle.ifBlank { bgmPath.name }}"
                } else if (!project.bgmUriString.isNullOrBlank()) {
                    val uri = Uri.parse(project.bgmUriString)
                    val copied = File(store.audioTempDirectory(), "bgm_${UUID.randomUUID().toString().take(8)}.tmp")
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            copied.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    if (copied.exists() && copied.length() > 0L) {
                        tempBgmCopy = copied
                        audioSourceFile = copied
                        audioNote = "Music: ${project.bgmTrackTitle.ifBlank { "Custom Audio" }}"
                    }
                }
            }

            // Optional AAC audio pre-encode for reliable MP4 muxing
            var encodedAacTempFile: File? = null
            if (audioSourceFile != null) {
                onProgress(ShortsExportProgress("Encoding AAC audio track...", 0.14f, 0, totalFrames))
                val aacFile = File(store.audioTempDirectory(), "aac_${UUID.randomUUID().toString().take(8)}.m4a")
                val audioReady = prepareAacAudioContainer(
                    sourceAudioFile = audioSourceFile,
                    outM4aFile = aacFile,
                    maxDurationMs = totalMs
                )
                if (audioReady && aacFile.exists() && aacFile.length() > 0L) {
                    encodedAacTempFile = aacFile
                } else if (audioNote.isNotBlank()) {
                    audioNote = "$audioNote (video-only fallback: device audio codec skipped)"
                }
            }

            // 2. Decode scene photos into bounded bitmaps
            var audioIncluded = false
            val sceneBitmaps = mutableMapOf<String, Bitmap>()
            try {
                project.scenes.forEach { scene ->
                    val uriStr = scene.photoUriString
                    if (!uriStr.isNullOrBlank() && !sceneBitmaps.containsKey(uriStr)) {
                        decodeBoundedPhoto(Uri.parse(uriStr), width, height)?.let { bmp ->
                            sceneBitmaps[uriStr] = bmp
                        }
                    }
                }

                val (codecName, colorFormat) = pickSupportedYuvFormat()
                val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, resolution.bitrateBps)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                val encoder = if (codecName != null) {
                    MediaCodec.createByCodecName(codecName)
                } else {
                    MediaCodec.createEncoderByType(VIDEO_MIME)
                }
                val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                val muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                var audioExtractor: MediaExtractor? = null
                var muxerAudioTrackIdx = -1
                var muxerVideoTrackIdx = -1
                var muxerStarted = false

                val renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val renderCanvas = Canvas(renderBitmap)
                val pixels = IntArray(width * height)

                try {
                    if (encodedAacTempFile != null) {
                        val ext = MediaExtractor()
                        ext.setDataSource(encodedAacTempFile.absolutePath)
                        for (t in 0 until ext.trackCount) {
                            val fmt = ext.getTrackFormat(t)
                            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                                ext.selectTrack(t)
                                muxerAudioTrackIdx = muxer.addTrack(fmt)
                                audioExtractor = ext
                                break
                            }
                        }
                        if (audioExtractor == null) {
                            ext.release()
                        }
                    }

                    encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder.start()

                    val bufferInfo = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var frameIndex = 0
                    val frameDurationUs = 1_000_000L / FPS

                    while (!outputDone) {
                        currentCoroutineContext().ensureActive()

                        if (!inputDone) {
                            val inIdx = encoder.dequeueInputBuffer(10_000L)
                            if (inIdx >= 0) {
                                val inBuf = encoder.getInputBuffer(inIdx)
                                if (frameIndex >= totalFrames) {
                                    encoder.queueInputBuffer(
                                        inIdx,
                                        0,
                                        0,
                                        frameIndex * frameDurationUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else if (inBuf != null) {
                                    val playheadMs = (frameIndex * 1000L) / FPS
                                    val activeFrame = ShortsScriptEngine.resolveActiveSceneAt(
                                        project.scenes,
                                        playheadMs
                                    )
                                    if (activeFrame != null) {
                                        renderFrameOntoCanvas(
                                            canvas = renderCanvas,
                                            width = width,
                                            height = height,
                                            project = project,
                                            frame = activeFrame,
                                            photoBitmap = activeFrame.scene.photoUriString?.let { sceneBitmaps[it] }
                                        )
                                    }
                                    renderBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                                    val yuvBytes = argbToYuv420(pixels, width, height, semiPlanar)
                                    inBuf.clear()
                                    val bytesToWrite = yuvBytes.size.coerceAtMost(inBuf.remaining())
                                    inBuf.put(yuvBytes, 0, bytesToWrite)
                                    encoder.queueInputBuffer(
                                        inIdx,
                                        0,
                                        bytesToWrite,
                                        frameIndex * frameDurationUs,
                                        0
                                    )
                                    frameIndex++
                                    if (frameIndex % 4 == 0 || frameIndex == totalFrames) {
                                        val frac = 0.16f + 0.76f * (frameIndex.toFloat() / totalFrames.toFloat())
                                        onProgress(
                                            ShortsExportProgress(
                                                phase = "Rendering 9:16 video ($frameIndex/$totalFrames frames)...",
                                                progressFraction = frac.coerceIn(0f, 0.95f),
                                                currentFrame = frameIndex,
                                                totalFrames = totalFrames
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        var drain = true
                        while (drain) {
                            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                            when {
                                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> drain = false
                                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    if (!muxerStarted) {
                                        muxerVideoTrackIdx = muxer.addTrack(encoder.outputFormat)
                                        muxer.start()
                                        muxerStarted = true
                                    }
                                }
                                outIdx >= 0 -> {
                                    val outBuf = encoder.getOutputBuffer(outIdx)
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        bufferInfo.size = 0
                                    }
                                    if (bufferInfo.size > 0 && muxerStarted && outBuf != null && muxerVideoTrackIdx >= 0) {
                                        outBuf.position(bufferInfo.offset)
                                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                        muxer.writeSampleData(muxerVideoTrackIdx, outBuf, bufferInfo)
                                    }
                                    encoder.releaseOutputBuffer(outIdx, false)
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        outputDone = true
                                        drain = false
                                    }
                                }
                            }
                        }
                    }

                    // Write audio samples if both muxer started & audio track present
                    if (muxerStarted && audioExtractor != null && muxerAudioTrackIdx >= 0) {
                        onProgress(ShortsExportProgress("Muxing audio into MP4...", 0.95f, totalFrames, totalFrames))
                        val audioBuf = ByteBuffer.allocate(64 * 1024)
                        val audioInfo = MediaCodec.BufferInfo()
                        val maxPtsUs = totalMs * 1000L
                        while (true) {
                            val sampleSize = audioExtractor.readSampleData(audioBuf, 0)
                            if (sampleSize < 0) break
                            val pts = audioExtractor.sampleTime
                            if (pts > maxPtsUs) break
                            audioInfo.offset = 0
                            audioInfo.size = sampleSize
                            audioInfo.presentationTimeUs = pts.coerceAtLeast(0L)
                            @Suppress("DEPRECATION")
                            audioInfo.flags = audioExtractor.sampleFlags
                            muxer.writeSampleData(muxerAudioTrackIdx, audioBuf, audioInfo)
                            audioIncluded = true
                            audioExtractor.advance()
                        }
                    }
                } finally {
                    runCatching { audioExtractor?.release() }
                    runCatching { encoder.stop() }
                    runCatching { encoder.release() }
                    if (muxerStarted) {
                        runCatching { muxer.stop() }
                    }
                    runCatching { muxer.release() }
                    if (!renderBitmap.isRecycled) renderBitmap.recycle()
                }
            } finally {
                sceneBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
                tempTtsWav?.delete()
                tempBgmCopy?.delete()
                encodedAacTempFile?.delete()
            }

            onProgress(ShortsExportProgress("Export complete!", 1f, totalFrames, totalFrames))
            val record = ShortsExportRecord(
                id = UUID.randomUUID().toString().take(10),
                projectId = project.id,
                title = project.title.ifBlank { "Teena Short" },
                filePath = outMp4.absolutePath,
                resolutionLabel = resolution.label,
                durationSec = totalMs / 1000f,
                sizeBytes = outMp4.length(),
                hasAudio = audioIncluded,
                audioNote = audioNote
            )
            record
        }
    }

    private fun decodeBoundedPhoto(uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > targetW * 2 && bounds.outHeight / sampleSize > targetH * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }.getOrNull()
    }

    private fun renderFrameOntoCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        project: ShortsProject,
        frame: ActiveShortsFrame,
        photoBitmap: Bitmap?
    ) {
        val scene = frame.scene
        val progress = frame.sceneProgress
        val theme = scene.theme

        // 1. Background: either Ken Burns photo or rich animated gradient
        if (photoBitmap != null && !photoBitmap.isRecycled) {
            val scaleBase = maxOf(
                width.toFloat() / photoBitmap.width.toFloat(),
                height.toFloat() / photoBitmap.height.toFloat()
            )
            val zoom = when (scene.transition) {
                ShortsSceneTransition.ZOOM_IN -> 1.0f + 0.14f * progress
                ShortsSceneTransition.PAN_LEFT -> 1.12f
                ShortsSceneTransition.PULSE -> 1.04f + 0.04f * sin(progress * PI).toFloat()
                ShortsSceneTransition.FADE -> 1.03f
            }
            val panOffsetX = if (scene.transition == ShortsSceneTransition.PAN_LEFT) {
                (0.5f - progress) * width * 0.08f
            } else {
                0f
            }
            val drawW = photoBitmap.width * scaleBase * zoom
            val drawH = photoBitmap.height * scaleBase * zoom
            val left = (width - drawW) / 2f + panOffsetX
            val top = (height - drawH) / 2f
            val dst = RectF(left, top, left + drawW, top + drawH)
            val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (scene.transition == ShortsSceneTransition.FADE && progress < 0.15f) {
                imgPaint.alpha = ((progress / 0.15f).coerceIn(0f, 1f) * 255).toInt()
                canvas.drawColor(Color.BLACK)
            }
            canvas.drawBitmap(photoBitmap, null, dst, imgPaint)

            // Dark contrast scrim for crisp caption readability
            val scrimPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0x99000000.toInt(), 0x33000000, 0xCC000000.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        } else {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width * 0.35f, height.toFloat(),
                    theme.topColorArgb,
                    theme.bottomColorArgb,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // Animated glow aura circles
            val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.accentColorArgb
                alpha = 48
            }
            val cx1 = width * (0.25f + 0.45f * sin(progress * PI).toFloat())
            val cy1 = height * (0.28f + 0.08f * cos(progress * 2 * PI).toFloat())
            canvas.drawCircle(cx1, cy1, width * 0.42f, orbPaint)

            orbPaint.alpha = 28
            canvas.drawCircle(width * 0.78f, height * (0.68f - 0.06f * progress), width * 0.36f, orbPaint)
        }

        // 2. Top multi-segment Shorts story progress bar
        val barTop = height * 0.032f
        val barHeight = (height * 0.006f).coerceAtLeast(5f)
        val marginX = width * 0.05f
        val segmentCount = project.scenes.size.coerceAtLeast(1)
        val gap = width * 0.014f
        val totalBarW = width - marginX * 2f - gap * (segmentCount - 1)
        val segW = totalBarW / segmentCount
        val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accentColorArgb }

        for (i in 0 until segmentCount) {
            val sx = marginX + i * (segW + gap)
            val rect = RectF(sx, barTop, sx + segW, barTop + barHeight)
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, trackBgPaint)
            val fillFrac = when {
                i < frame.sceneIndex -> 1f
                i == frame.sceneIndex -> progress
                else -> 0f
            }
            if (fillFrac > 0f) {
                val fillRect = RectF(sx, barTop, sx + segW * fillFrac, barTop + barHeight)
                canvas.drawRoundRect(fillRect, barHeight / 2f, barHeight / 2f, trackFillPaint)
            }
        }

        // 3. Top Hook pill (strong opening attention badge)
        val hookText = project.hook.ifBlank { project.title }.trim().take(46)
        if (hookText.isNotBlank()) {
            val hookPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = width * 0.041f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val hookTop = barTop + barHeight + height * 0.025f
            val hookPadH = width * 0.05f
            val hookBoxW = (hookPaint.measureText(hookText) + hookPadH * 2f).coerceAtMost(width * 0.9f)
            val hookBoxRect = RectF(
                (width - hookBoxW) / 2f,
                hookTop,
                (width + hookBoxW) / 2f,
                hookTop + height * 0.055f
            )
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCC0C1422.toInt()
            }
            val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (width * 0.004f).coerceAtLeast(2f)
                color = theme.accentColorArgb
            }
            canvas.drawRoundRect(hookBoxRect, 28f, 28f, pillPaint)
            canvas.drawRoundRect(hookBoxRect, 28f, 28f, pillStroke)
            val fm = hookPaint.fontMetrics
            val textY = hookBoxRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(hookText, width / 2f, textY, hookPaint)
        }

        // 4. Center Headline Badge
        val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.accentColorArgb
            textSize = width * 0.046f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
        }
        val headlineY = height * 0.56f
        canvas.drawText(scene.headline.uppercase(), width / 2f, headlineY, headlinePaint)

        // 5. Kinetic Caption Card (bottom safe zone)
        val lines = ShortsScriptEngine.wrapCaptionLines(scene.captionText, maxCharsPerLine = 22)
        if (lines.isNotEmpty()) {
            val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = width * 0.058f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
            }
            val lineHeight = captionPaint.textSize * 1.32f
            val boxPadV = height * 0.022f
            val boxH = lines.size * lineHeight + boxPadV * 2f
            val boxTop = height * 0.62f
            val boxRect = RectF(width * 0.06f, boxTop, width * 0.94f, boxTop + boxH)
            val boxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xBA09101C.toInt()
            }
            val boxBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                color = theme.accentColorArgb
                alpha = 180
            }
            canvas.drawRoundRect(boxRect, 24f, 24f, boxBg)
            canvas.drawRoundRect(boxRect, 24f, 24f, boxBorder)

            lines.forEachIndexed { idx, line ->
                val y = boxTop + boxPadV + captionPaint.textSize + idx * lineHeight
                canvas.drawText(line, width / 2f, y, captionPaint)
            }
        }

        // 6. Hashtags + Watermark footer
        if (project.hashtags.isNotBlank()) {
            val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFD0E4F5.toInt()
                textSize = width * 0.033f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(project.hashtags.take(52), width / 2f, height * 0.89f, tagPaint)
        }
        if (project.showWatermark) {
            val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAAFFFFFF.toInt()
                textSize = width * 0.029f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("TEENA SHORTS STUDIO", width / 2f, height * 0.94f, wmPaint)
        }
    }

    private suspend fun synthesizeVoiceoverWav(text: String, outFile: File): File? {
        val deferred = CompletableDeferred<Boolean>()
        var tts: TextToSpeech? = null
        val utteranceId = "shorts_vo_${UUID.randomUUID().toString().take(8)}"
        withContext(Dispatchers.Main) {
            runCatching {
                tts = TextToSpeech(context.applicationContext) { status ->
                    val engine = tts
                    if (status == TextToSpeech.SUCCESS && engine != null) {
                        runCatching {
                            val hi = Locale("hi", "IN")
                            val langRes = engine.setLanguage(hi)
                            if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                                engine.setLanguage(Locale.ENGLISH)
                            }
                            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onDone(id: String?) {
                                    if (id == utteranceId && !deferred.isCompleted) deferred.complete(true)
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onError(id: String?) {
                                    if (!deferred.isCompleted) deferred.complete(false)
                                }

                                override fun onError(id: String?, errorCode: Int) {
                                    if (!deferred.isCompleted) deferred.complete(false)
                                }
                            })
                            val code = engine.synthesizeToFile(text.take(1200), Bundle(), outFile, utteranceId)
                            if (code != TextToSpeech.SUCCESS && !deferred.isCompleted) {
                                deferred.complete(false)
                            }
                        }.onFailure {
                            if (!deferred.isCompleted) deferred.complete(false)
                        }
                    } else if (!deferred.isCompleted) {
                        deferred.complete(false)
                    }
                }
            }.onFailure {
                if (!deferred.isCompleted) deferred.complete(false)
            }
        }
        val ok = withTimeoutOrNull(14_000L) { deferred.await() } == true
        withContext(Dispatchers.Main) {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
        return if (ok && outFile.exists() && outFile.length() > 44L) outFile else null
    }

    /**
     * Converts either a WAV PCM file (from TTS or Lyria) or decodable audio file into
     * an M4A (AAC) container capped at [maxDurationMs] so MediaMuxer can copy the AAC track cleanly.
     */
    private fun prepareAacAudioContainer(
        sourceAudioFile: File,
        outM4aFile: File,
        maxDurationMs: Long
    ): Boolean = runCatching {
        val header = ByteArray(12)
        sourceAudioFile.inputStream().use { it.read(header) }
        val isWav = String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE"
        if (isWav) {
            val wav = parseWavPcm(sourceAudioFile) ?: return false
            encodePcmToAacM4a(
                pcmBytes = wav.pcmBytes,
                sampleRate = wav.sampleRate,
                channelCount = wav.channels,
                outM4aFile = outM4aFile,
                maxDurationMs = maxDurationMs
            )
            return outM4aFile.exists() && outM4aFile.length() > 0L
        }

        // Check if source already has an AAC track that MediaMuxer can use directly
        val ext = MediaExtractor()
        try {
            ext.setDataSource(sourceAudioFile.absolutePath)
            var audioIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioIdx = i
                    audioFormat = f
                    break
                }
            }
            if (audioIdx < 0 || audioFormat == null) return false
            val mime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime == AUDIO_MIME) {
                ext.selectTrack(audioIdx)
                val muxer = MediaMuxer(outM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = muxer.addTrack(audioFormat)
                muxer.start()
                val buf = ByteBuffer.allocate(64 * 1024)
                val info = MediaCodec.BufferInfo()
                val maxUs = maxDurationMs * 1000L
                while (true) {
                    val sz = ext.readSampleData(buf, 0)
                    if (sz < 0) break
                    val pts = ext.sampleTime
                    if (pts > maxUs) break
                    info.set(0, sz, pts.coerceAtLeast(0L), ext.sampleFlags)
                    muxer.writeSampleData(track, buf, info)
                    ext.advance()
                }
                muxer.stop()
                muxer.release()
                return outM4aFile.exists() && outM4aFile.length() > 0L
            }
        } finally {
            ext.release()
        }
        false
    }.getOrDefault(false)

    private data class WavPcmData(
        val sampleRate: Int,
        val channels: Int,
        val pcmBytes: ByteArray
    )

    private fun parseWavPcm(file: File): WavPcmData? {
        val raw = file.readBytes()
        if (raw.size < 44) return null
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 22050
        var channels = 1
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= raw.size) {
            val id = String(raw, pos, 4)
            val size = bb.getInt(pos + 4)
            val payloadStart = pos + 8
            if (id == "fmt " && size >= 16 && payloadStart + 16 <= raw.size) {
                channels = bb.getShort(payloadStart + 2).toInt().coerceIn(1, 2)
                sampleRate = bb.getInt(payloadStart + 4).coerceIn(8000, 48000)
                bitsPerSample = bb.getShort(payloadStart + 14).toInt()
            } else if (id == "data" && payloadStart < raw.size) {
                dataOffset = payloadStart
                dataLen = size.coerceAtMost(raw.size - payloadStart).coerceAtLeast(0)
                break
            }
            pos = payloadStart + size + (size and 1)
        }
        if (dataOffset < 0 || dataLen <= 0 || bitsPerSample != 16) return null
        return WavPcmData(sampleRate, channels, raw.copyOfRange(dataOffset, dataOffset + dataLen))
    }

    private fun encodePcmToAacM4a(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channelCount: Int,
        outM4aFile: File,
        maxDurationMs: Long
    ) {
        val bytesPerSec = sampleRate * channelCount * 2
        val maxBytes = ((bytesPerSec * maxDurationMs) / 1000L).toInt().coerceAtMost(pcmBytes.size)
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        val muxer = MediaMuxer(outM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIdx = -1
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var totalSamplesWritten = 0L
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)
                        if (offset >= maxBytes) {
                            val pts = (totalSamplesWritten * 1_000_000L) / sampleRate
                            encoder.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else if (buf != null) {
                            val chunk = (maxBytes - offset).coerceAtMost(buf.remaining()).coerceAtMost(4096)
                            buf.clear()
                            buf.put(pcmBytes, offset, chunk)
                            val pts = (totalSamplesWritten * 1_000_000L) / sampleRate
                            encoder.queueInputBuffer(inIdx, 0, chunk, pts, 0)
                            offset += chunk
                            totalSamplesWritten += chunk / (channelCount * 2)
                        }
                    }
                }
                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIdx = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (info.size > 0 && muxerStarted && outBuf != null && trackIdx >= 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIdx, outBuf, info)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    suspend fun saveVideoToMoviesMediaStore(mp4File: File, title: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(mp4File.exists() && mp4File.length() > 0L) { "Exported MP4 file missing." }
                val cleanName = title.ifBlank { "TeenaShort" }
                    .replace(Regex("""[^a-zA-Z0-9_-]"""), "_")
                    .take(32) + "_${System.currentTimeMillis()}.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, cleanName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TeenaShorts")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Unable to create MediaStore video entry.")
                resolver.openOutputStream(uri)?.use { out ->
                    mp4File.inputStream().use { input -> input.copyTo(out) }
                } ?: error("Unable to write to Movies gallery.")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }
                uri
            }
        }

    fun buildShareVideoIntent(mp4File: File, title: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            mp4File
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
