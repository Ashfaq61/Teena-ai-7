package com.example.ai

import com.example.core.security.SecurityAuditor
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ShortsAspectPreset(val label: String, val widthRatio: Int, val heightRatio: Int) {
    VERTICAL_9_16("9:16 Vertical", 9, 16)
}

enum class ShortsExportResolution(
    val width: Int,
    val height: Int,
    val label: String,
    val bitrateBps: Int
) {
    HD_720P(720, 1280, "720×1280 HD", 3_200_000),
    SD_540P(540, 960, "540×960 Fast", 1_800_000)
}

enum class ShortsSceneTransition(val label: String) {
    ZOOM_IN("Zoom In"),
    PAN_LEFT("Pan Left"),
    FADE("Smooth Fade"),
    PULSE("Kinetic Pulse")
}

enum class ShortsBackgroundTheme(
    val label: String,
    val topColorArgb: Int,
    val bottomColorArgb: Int,
    val accentColorArgb: Int
) {
    CYBER_CYAN("Cyber Cyan", 0xFF071929.toInt(), 0xFF0D0824.toInt(), 0xFF38E0FF.toInt()),
    SUNSET_AMBER("Sunset Gold", 0xFF2B1105.toInt(), 0xFF14071A.toInt(), 0xFFFFB020.toInt()),
    ROYAL_VIOLET("Royal Violet", 0xFF1C0C36.toInt(), 0xFF090A18.toInt(), 0xFFA78BFA.toInt()),
    EMERALD_NEON("Emerald Neon", 0xFF05241D.toInt(), 0xFF07101A.toInt(), 0xFF34D399.toInt()),
    CRIMSON_FIRE("Crimson Pulse", 0xFF2D0712.toInt(), 0xFF0A0912.toInt(), 0xFFFF4D6D.toInt())
}

enum class ShortsVibePreset(val label: String, val promptHint: String) {
    HINGLISH_TECH("Hinglish Tech", "Fast-paced Hinglish tech tip with crisp hook and actionable steps"),
    MOTIVATIONAL("Motivational", "High-energy motivational short with punchy one-liners"),
    STORYTELLING("Storytelling", "Engaging mini story with suspense hook and twist ending"),
    PRODUCT_PROMO("Product Promo", "Modern product spotlight reel with problem-solution flow"),
    COMEDY_SKIT("Relatable Skit", "Short witty Hinglish relatable observation with punchline")
}

data class ShortsScene(
    val id: String = UUID.randomUUID().toString().take(8),
    val headline: String,
    val captionText: String,
    val voiceoverLine: String,
    val durationSec: Float = 4f,
    val theme: ShortsBackgroundTheme = ShortsBackgroundTheme.CYBER_CYAN,
    val transition: ShortsSceneTransition = ShortsSceneTransition.ZOOM_IN,
    val photoUriString: String? = null
)

data class ShortsProject(
    val id: String = UUID.randomUUID().toString().take(10),
    val title: String,
    val hook: String,
    val vibe: ShortsVibePreset = ShortsVibePreset.HINGLISH_TECH,
    val targetDurationSec: Int = 20,
    val hashtags: String = "#shorts #teenaai #reels",
    val scenes: List<ShortsScene>,
    val bgmTrackTitle: String = "",
    val bgmFilePath: String? = null,
    val bgmUriString: String? = null,
    val includeVoiceoverTts: Boolean = true,
    val showWatermark: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun totalDurationMs(): Long = ShortsScriptEngine.totalDurationMs(scenes)
}

data class ShortsExportRecord(
    val id: String,
    val projectId: String,
    val title: String,
    val filePath: String,
    val resolutionLabel: String,
    val durationSec: Float,
    val sizeBytes: Long,
    val hasAudio: Boolean,
    val audioNote: String = "",
    val mediaStoreUri: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class ActiveShortsFrame(
    val sceneIndex: Int,
    val scene: ShortsScene,
    val sceneProgress: Float,
    val totalProgress: Float,
    val totalDurationMs: Long,
    val clampedPlayheadMs: Long
)

class ShortsScriptEngine(
    private val geminiRepository: GeminiRepository? = null
) {
    companion object {
        private val JSON_FENCE_REGEX = Regex(
            """```(?:json)?\s*\n([\s\S]*?)```""",
            setOf(RegexOption.IGNORE_CASE)
        )

        fun totalDurationMs(scenes: List<ShortsScene>): Long {
            if (scenes.isEmpty()) return 0L
            return scenes.sumOf { (it.durationSec.coerceIn(1.5f, 15f) * 1000f).toLong() }
        }

        fun resolveActiveSceneAt(scenes: List<ShortsScene>, playheadMs: Long): ActiveShortsFrame? {
            if (scenes.isEmpty()) return null
            val totalMs = totalDurationMs(scenes).coerceAtLeast(1L)
            val clamped = playheadMs.coerceIn(0L, totalMs)
            var cursor = 0L
            scenes.forEachIndexed { idx, scene ->
                val sceneDurMs = (scene.durationSec.coerceIn(1.5f, 15f) * 1000f).toLong().coerceAtLeast(1L)
                val endMs = cursor + sceneDurMs
                if (clamped < endMs || idx == scenes.lastIndex) {
                    val localMs = (clamped - cursor).coerceIn(0L, sceneDurMs)
                    return ActiveShortsFrame(
                        sceneIndex = idx,
                        scene = scene,
                        sceneProgress = (localMs.toFloat() / sceneDurMs.toFloat()).coerceIn(0f, 1f),
                        totalProgress = (clamped.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f),
                        totalDurationMs = totalMs,
                        clampedPlayheadMs = clamped
                    )
                }
                cursor = endMs
            }
            val last = scenes.last()
            return ActiveShortsFrame(
                sceneIndex = scenes.lastIndex,
                scene = last,
                sceneProgress = 1f,
                totalProgress = 1f,
                totalDurationMs = totalMs,
                clampedPlayheadMs = totalMs
            )
        }

        fun wrapCaptionLines(text: String, maxCharsPerLine: Int = 24): List<String> {
            val clean = text.trim().replace(Regex("""\s+"""), " ")
            if (clean.isEmpty()) return emptyList()
            val limit = maxCharsPerLine.coerceAtLeast(8)
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (word in clean.split(" ")) {
                if (word.length > limit) {
                    if (current.isNotEmpty()) {
                        lines.add(current.toString())
                        current = StringBuilder()
                    }
                    word.chunked(limit).forEach { chunk -> lines.add(chunk) }
                    continue
                }
                val candidateLen = if (current.isEmpty()) word.length else current.length + 1 + word.length
                if (candidateLen <= limit) {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                } else {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            return lines.take(4)
        }

        fun parseStoryboardFromResponse(
            raw: String,
            fallbackTopic: String,
            vibe: ShortsVibePreset,
            targetSec: Int
        ): ShortsProject {
            val fenced = JSON_FENCE_REGEX.find(raw)?.groupValues?.getOrNull(1)?.trim()
            val candidate = fenced ?: extractFirstJsonObject(raw)
            if (!candidate.isNullOrBlank()) {
                val parsed = runCatching { JSONObject(candidate) }.getOrNull()
                if (parsed != null) {
                    val scenesArr = parsed.optJSONArray("scenes") ?: JSONArray()
                    val parsedScenes = mutableListOf<ShortsScene>()
                    for (i in 0 until scenesArr.length().coerceAtMost(8)) {
                        val obj = scenesArr.optJSONObject(i) ?: continue
                        val caption = obj.optString("captionText")
                            .ifBlank { obj.optString("caption") }
                            .ifBlank { obj.optString("text") }
                            .trim()
                        val headline = obj.optString("headline")
                            .ifBlank { obj.optString("title") }
                            .ifBlank { "Scene ${i + 1}" }
                            .trim()
                        val voiceover = obj.optString("voiceoverLine")
                            .ifBlank { obj.optString("voiceover") }
                            .ifBlank { caption }
                            .trim()
                        if (caption.isBlank() && voiceover.isBlank()) continue
                        val dur = obj.optDouble("durationSec", 4.0).toFloat().coerceIn(2f, 12f)
                        val theme = parseTheme(obj.optString("theme"), i)
                        val transition = parseTransition(obj.optString("transition"), i)
                        parsedScenes.add(
                            ShortsScene(
                                headline = headline.take(48),
                                captionText = caption.ifBlank { headline }.take(120),
                                voiceoverLine = voiceover.ifBlank { caption }.take(220),
                                durationSec = dur,
                                theme = theme,
                                transition = transition
                            )
                        )
                    }
                    if (parsedScenes.isNotEmpty()) {
                        val title = parsed.optString("title").ifBlank { fallbackTopic.take(48) }.trim()
                        val hook = parsed.optString("hook")
                            .ifBlank { parsedScenes.first().headline }
                            .trim()
                            .take(64)
                        val tags = parsed.optString("hashtags")
                            .ifBlank { "#shorts #reels #teenaai" }
                            .trim()
                        return ShortsProject(
                            title = title,
                            hook = hook,
                            vibe = vibe,
                            targetDurationSec = targetSec,
                            hashtags = tags,
                            scenes = parsedScenes
                        )
                    }
                }
            }

            // Fallback structured split if model returned plain lines instead of valid JSON
            val cleanLines = raw.lineSequence()
                .map { it.trim().removePrefix("-").removePrefix("*").trim() }
                .filter { it.isNotBlank() && !it.startsWith("```") }
                .take(5)
                .toList()
            if (cleanLines.isNotEmpty()) {
                val perSceneDur = (targetSec.toFloat() / cleanLines.size.coerceAtLeast(1)).coerceIn(2.5f, 8f)
                val scenes = cleanLines.mapIndexed { idx, line ->
                    ShortsScene(
                        headline = "Scene ${idx + 1}",
                        captionText = line.take(100),
                        voiceoverLine = line.take(200),
                        durationSec = perSceneDur,
                        theme = ShortsBackgroundTheme.entries[idx % ShortsBackgroundTheme.entries.size],
                        transition = ShortsSceneTransition.entries[idx % ShortsSceneTransition.entries.size]
                    )
                }
                return ShortsProject(
                    title = fallbackTopic.ifBlank { "Teena AI Short" }.take(48),
                    hook = scenes.first().captionText.take(56),
                    vibe = vibe,
                    targetDurationSec = targetSec,
                    hashtags = "#shorts #teenaai",
                    scenes = scenes
                )
            }

            return starterTemplates().first().copy(
                id = UUID.randomUUID().toString().take(10),
                title = fallbackTopic.ifBlank { "Teena AI Short" }.take(48),
                vibe = vibe,
                targetDurationSec = targetSec
            )
        }

        private fun extractFirstJsonObject(raw: String): String? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) {
                return raw.substring(start, end + 1)
            }
            return null
        }

        private fun parseTheme(name: String, index: Int): ShortsBackgroundTheme {
            val norm = name.trim().uppercase().replace(" ", "_")
            return ShortsBackgroundTheme.entries.firstOrNull { it.name == norm }
                ?: ShortsBackgroundTheme.entries[index % ShortsBackgroundTheme.entries.size]
        }

        private fun parseTransition(name: String, index: Int): ShortsSceneTransition {
            val norm = name.trim().uppercase().replace(" ", "_")
            return ShortsSceneTransition.entries.firstOrNull { it.name == norm }
                ?: ShortsSceneTransition.entries[index % ShortsSceneTransition.entries.size]
        }

        fun starterTemplates(): List<ShortsProject> = listOf(
            ShortsProject(
                id = "starter_ai_tip",
                title = "3 AI Tricks Jo Time Bachaye",
                hook = "WAIT! Ye 3 AI tricks miss mat karo",
                vibe = ShortsVibePreset.HINGLISH_TECH,
                targetDurationSec = 16,
                hashtags = "#shorts #aitips #teenaai #tech",
                scenes = listOf(
                    ShortsScene(
                        headline = "Hook: Stop Scrolling",
                        captionText = "Kya aap roz 2 ghante bachana chahte ho?",
                        voiceoverLine = "Kya aap roz do ghante bachana chahte ho? Ye teen AI tricks dekho.",
                        durationSec = 4f,
                        theme = ShortsBackgroundTheme.CYBER_CYAN,
                        transition = ShortsSceneTransition.ZOOM_IN
                    ),
                    ShortsScene(
                        headline = "Trick #1: Smart Summaries",
                        captionText = "1. Long PDFs ko 10 second me summarize karo",
                        voiceoverLine = "Pehla trick: lambe PDFs aur notes ko dus second mein summarize karo.",
                        durationSec = 4f,
                        theme = ShortsBackgroundTheme.ROYAL_VIOLET,
                        transition = ShortsSceneTransition.PAN_LEFT
                    ),
                    ShortsScene(
                        headline = "Trick #2: Voice Coding",
                        captionText = "2. Bol kar app prototypes & scripts banao",
                        voiceoverLine = "Doosra trick: bol kar scripts aur prototypes turant generate karo.",
                        durationSec = 4f,
                        theme = ShortsBackgroundTheme.EMERALD_NEON,
                        transition = ShortsSceneTransition.PULSE
                    ),
                    ShortsScene(
                        headline = "Follow for More",
                        captionText = "Save karo & Part 2 ke liye Follow dabao!",
                        voiceoverLine = "Video save karo aur Part two ke liye follow dabao!",
                        durationSec = 4f,
                        theme = ShortsBackgroundTheme.SUNSET_AMBER,
                        transition = ShortsSceneTransition.FADE
                    )
                )
            ),
            ShortsProject(
                id = "starter_motivation",
                title = "Silent Progress Rule",
                hook = "6 Mahine Gayab Ho Jao",
                vibe = ShortsVibePreset.MOTIVATIONAL,
                targetDurationSec = 15,
                hashtags = "#motivation #mindset #shorts",
                scenes = listOf(
                    ShortsScene(
                        headline = "01. Cut The Noise",
                        captionText = "Logon ko plan mat batao, direct result dikhao.",
                        voiceoverLine = "Logon ko apna plan mat batao, seedha result dikhao.",
                        durationSec = 5f,
                        theme = ShortsBackgroundTheme.CRIMSON_FIRE,
                        transition = ShortsSceneTransition.ZOOM_IN
                    ),
                    ShortsScene(
                        headline = "02. Daily 1% Rule",
                        captionText = "Har roz sirf 1% better bano — consistency jeetati hai.",
                        voiceoverLine = "Har roz sirf ek percent better bano. Consistency sabse badi taqat hai.",
                        durationSec = 5f,
                        theme = ShortsBackgroundTheme.SUNSET_AMBER,
                        transition = ShortsSceneTransition.FADE
                    ),
                    ShortsScene(
                        headline = "03. Your Turn",
                        captionText = "Aaj se shuru karo. Future self thank you bolega.",
                        voiceoverLine = "Aaj se shuru karo, aapka future self aapko thank you bolega.",
                        durationSec = 5f,
                        theme = ShortsBackgroundTheme.CYBER_CYAN,
                        transition = ShortsSceneTransition.PULSE
                    )
                )
            )
        )
    }

    suspend fun generateStoryboardWithGemini(
        topicPrompt: String,
        vibe: ShortsVibePreset,
        targetDurationSec: Int,
        preferredModel: String?
    ): Result<ShortsProject> {
        val repo = geminiRepository
            ?: return Result.failure(
                GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — GeminiRepository configured nahi hai.",
                    rawErrorLine = "Missing GeminiRepository"
                )
            )
        val cleanTopic = topicPrompt.trim()
        if (cleanTopic.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pehle Shorts ka topic ya idea likhein."))
        }

        val systemInstruction = """
            You are an expert vertical 9:16 YouTube Shorts / Instagram Reels scriptwriter.
            Respond ONLY with valid JSON (no extra commentary) matching this schema:
            {
              "title": "short catchy title (max 45 chars)",
              "hook": "attention-grabbing opening hook (max 55 chars)",
              "hashtags": "#shorts #reels ...",
              "scenes": [
                {
                  "headline": "2-4 word scene title",
                  "captionText": "punchy on-screen subtitle (max 90 chars)",
                  "voiceoverLine": "natural spoken narration line for TTS",
                  "durationSec": 4.0,
                  "theme": "CYBER_CYAN|SUNSET_AMBER|ROYAL_VIOLET|EMERALD_NEON|CRIMSON_FIRE",
                  "transition": "ZOOM_IN|PAN_LEFT|FADE|PULSE"
                }
              ]
            }
            Generate 3 to 5 crisp scenes whose durations sum close to ${targetDurationSec.coerceIn(12, 60)} seconds.
        """.trimIndent()

        val userPrompt = """
            Topic: $cleanTopic
            Style / Vibe: ${vibe.label} (${vibe.promptHint})
            Target total duration: ${targetDurationSec}s
        """.trimIndent()

        return repo.askDirectText(
            userPrompt = userPrompt,
            systemInstruction = systemInstruction,
            preferredModel = preferredModel,
            maxOutputTokens = 1200,
            timeoutMs = 55_000L
        ).map { result ->
            parseStoryboardFromResponse(
                raw = result.text,
                fallbackTopic = cleanTopic,
                vibe = vibe,
                targetSec = targetDurationSec
            )
        }.recoverCatching { err ->
            throw GeminiRepository.formatHinglishError(
                err,
                SecurityAuditor.redactSecrets(preferredModel.orEmpty())
            )
        }
    }
}
