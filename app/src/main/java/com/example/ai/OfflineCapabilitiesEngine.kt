package com.example.ai

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.example.data.local.MemoryFactEntity
import com.example.data.local.ResearchEntryEntity

data class LocalHardwareProfile(
    val totalRamGb: Float,
    val availableRamGb: Float,
    val cpuCores: Int,
    val hasAiCore: Boolean,
    val recommendation: String
)

data class OfflineIntentResult(
    val handledLocally: Boolean,
    val detectedLanguage: String,
    val actionKey: String? = null,
    val actionArg: String? = null,
    val replyText: String
)

class OfflineCapabilitiesEngine(private val context: Context) {

    private val romanUrduTokens = setOf(
        "kya", "kaise", "kaisi", "karo", "kholo", "batao", "mera", "meri", "tum",
        "aaj", "kal", "bhai", "yaar", "samjho", "chahiye", "jaldi", "milao",
        "roshni", "torch", "awaz", "zyada", "kam", "band", "shuru", "bhaijaan",
        "acha", "theek", "shukriya", "madad", "likho", "padho", "bhejo", "dekho"
    )

    fun detectLanguage(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "English"
        val hasArabicScript = trimmed.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
        if (hasArabicScript) return "Urdu"

        val words = trimmed.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        val romanMatches = words.count { it in romanUrduTokens }
        return if (romanMatches >= 1 && (romanMatches * 100 / words.size.coerceAtLeast(1) >= 15 || romanMatches >= 2)) {
            "Roman Urdu"
        } else {
            "English"
        }
    }

    fun inspectHardware(): LocalHardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val totalGb = mi.totalMem / (1024f * 1024f * 1024f)
        val availGb = mi.availMem / (1024f * 1024f * 1024f)
        val cores = Runtime.getRuntime().availableProcessors()
        val hasAiCore = runCatching {
            context.packageManager.getPackageInfo("com.google.android.aicore", PackageManager.PackageInfoFlags.of(0))
            true
        }.recoverCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            true
        }.getOrDefault(false)

        val rec = when {
            hasAiCore -> "Google AICore detected; device supports on-device Gemini Nano acceleration."
            totalGb >= 7.5f -> "High-RAM device (${"%.1f".format(totalGb)} GB). Can connect to local GGUF/Ollama endpoint; built-in TF-IDF + command engine active offline."
            else -> "Standard RAM (${"%.1f".format(totalGb)} GB). Built-in offline command & TF-IDF knowledge retrieval engine active; cloud AI recommended for generative tasks."
        }
        return LocalHardwareProfile(totalGb, availGb, cores, hasAiCore, rec)
    }

    fun evaluateLocalCommandOrKnowledge(
        prompt: String,
        memories: List<MemoryFactEntity>,
        research: List<ResearchEntryEntity>
    ): OfflineIntentResult {
        val lang = detectLanguage(prompt)
        val lower = prompt.lowercase().trim()

        // Direct device action intents in English / Roman Urdu / Urdu
        when {
            lower.contains("flashlight on") || lower.contains("torch on") ||
                lower.contains("torch chalao") || lower.contains("roshni on") ||
                lower.contains("ٹارچ آن") -> {
                return OfflineIntentResult(true, lang, "TORCH_ON", null, "Turning on the device flashlight.")
            }
            lower.contains("flashlight off") || lower.contains("torch off") ||
                lower.contains("torch band") || lower.contains("roshni band") ||
                lower.contains("ٹارچ بند") -> {
                return OfflineIntentResult(true, lang, "TORCH_OFF", null, "Turning off the device flashlight.")
            }
            lower.contains("open whatsapp") || lower.contains("whatsapp kholo") || lower.contains("واٹس ایپ کھولو") -> {
                return OfflineIntentResult(true, lang, "OPEN_APP", "WHATSAPP", "Opening WhatsApp.")
            }
            lower.contains("open youtube") || lower.contains("youtube kholo") || lower.contains("یوٹیوب کھولو") -> {
                return OfflineIntentResult(true, lang, "OPEN_APP", "YOUTUBE", "Opening YouTube.")
            }
            lower.contains("open instagram") || lower.contains("instagram kholo") -> {
                return OfflineIntentResult(true, lang, "OPEN_APP", "INSTAGRAM", "Opening Instagram.")
            }
            lower.contains("open facebook") || lower.contains("facebook kholo") -> {
                return OfflineIntentResult(true, lang, "OPEN_APP", "FACEBOOK", "Opening Facebook.")
            }
            lower.contains("open tiktok") || lower.contains("tiktok kholo") -> {
                return OfflineIntentResult(true, lang, "OPEN_APP", "TIKTOK", "Opening TikTok.")
            }
            lower.contains("open telegram") || lower.contains("telegram kholo") -> {
                return OfflineIntentResult(true, lang, "OPEN_APP", "TELEGRAM", "Opening Telegram.")
            }
            lower.contains("volume up") || lower.contains("awaz barhao") || lower.contains("awaz zyada") -> {
                return OfflineIntentResult(true, lang, "VOLUME_UP", null, "Increasing media volume.")
            }
            lower.contains("volume down") || lower.contains("awaz kam") -> {
                return OfflineIntentResult(true, lang, "VOLUME_DOWN", null, "Decreasing media volume.")
            }
            lower.contains("read screen") || lower.contains("screen padho") || lower.contains("screen dekho") -> {
                return OfflineIntentResult(true, lang, "READ_SCREEN", null, "Inspecting current screen via AccessibilityService.")
            }
            lower.startsWith("navigate to ") || lower.startsWith("directions to ") -> {
                val dest = prompt.substringAfter(" to ").trim()
                return OfflineIntentResult(true, lang, "NAVIGATE", dest, "Launching Google Maps navigation to $dest.")
            }
        }

        // Local Knowledge Base retrieval (BM25/Token-overlap scoring)
        val queryTerms = lower.split(Regex("[^a-z0-9\u0600-\u06FF]+")).filter { it.length >= 2 }.toSet()
        val matchedMemories = memories.map { mem ->
            val hay = "${mem.category} ${mem.factKey} ${mem.factValue}".lowercase()
            val score = queryTerms.count { hay.contains(it) }
            mem to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(3)

        val matchedResearch = research.map { res ->
            val hay = "${res.title} ${res.summary}".lowercase()
            val score = queryTerms.count { hay.contains(it) }
            res to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(2)

        if (matchedMemories.isNotEmpty() || matchedResearch.isNotEmpty()) {
            val sb = StringBuilder()
            sb.appendLine("[Offline Knowledge Retrieval | $lang]")
            matchedMemories.forEach { (m, _) ->
                sb.appendLine("• Memory (${m.category}): ${m.factKey} — ${m.factValue}")
            }
            matchedResearch.forEach { (r, _) ->
                sb.appendLine("• Research (${r.sourceType}): ${r.title} — ${r.summary.take(180)} (${r.sourceUrl})")
            }
            return OfflineIntentResult(true, lang, null, null, sb.toString().trim())
        }

        val hw = inspectHardware()
        return OfflineIntentResult(
            handledLocally = false,
            detectedLanguage = lang,
            replyText = "Offline mode active ($lang). No local memory matched your query, and this device (${"%.1f".format(hw.totalRamGb)} GB RAM, AICore=${hw.hasAiCore}) requires a connected AI provider (Gemini AI Logic, Groq, OpenRouter, or a local OpenAI-compatible server) for open-ended reasoning."
        )
    }
}
