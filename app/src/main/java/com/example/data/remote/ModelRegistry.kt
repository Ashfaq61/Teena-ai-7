package com.example.data.remote

import com.example.core.security.SecurityAuditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class GeminiModelInfo(
    val name: String,
    val shortId: String,
    val displayName: String,
    val supportedGenerationMethods: List<String>,
    val inputTokenLimit: Int,
    val outputTokenLimit: Int
) {
    val supportsBidiGenerateContent: Boolean
        get() = supportedGenerationMethods.any { it.equals("bidiGenerateContent", ignoreCase = true) }

    val supportsGenerateContent: Boolean
        get() = supportedGenerationMethods.any { it.equals("generateContent", ignoreCase = true) }
}

/**
 * Queries the Gemini v1beta ModelRegistry endpoint using the user's API key to
 * auto-detect models supporting `bidiGenerateContent` (Live Audio) and `generateContent` (REST Chat).
 */
class ModelRegistry(private val httpClient: OkHttpClient) {

    suspend fun fetchModels(apiKey: String): Result<List<GeminiModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isEmpty()) {
                throw IllegalStateException("Gemini API key missing")
            }
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .addHeader("x-goog-api-key", cleanKey)
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = runCatching {
                        val j = JSONObject(body)
                        j.optJSONObject("error")?.optString("message")
                            ?: j.optString("message")
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "ModelRegistry HTTP ${resp.code}"
                    throw IllegalStateException(SecurityAuditor.redactSecrets(err))
                }
                parseModelsJson(body)
            }
        }
    }

    suspend fun autoDetectLiveModels(apiKey: String): Result<List<GeminiModelInfo>> {
        return fetchModels(apiKey).mapCatching { all ->
            val liveModels = all.filter { it.supportsBidiGenerateContent }
                .sortedWith(
                    compareByDescending<GeminiModelInfo> { scoreLiveModel(it.shortId) }
                        .thenByDescending { it.inputTokenLimit }
                        .thenBy { it.shortId }
                )
            if (liveModels.isEmpty()) {
                throw IllegalStateException(
                    "ModelRegistry returned no Gemini models supporting bidiGenerateContent for this API key."
                )
            }
            liveModels
        }
    }

    suspend fun autoDetectChatModels(apiKey: String): Result<List<GeminiModelInfo>> {
        return fetchModels(apiKey).mapCatching { all ->
            val chatModels = all.filter { it.supportsGenerateContent }
                .sortedWith(
                    compareByDescending<GeminiModelInfo> { scoreChatModel(it.shortId) }
                        .thenByDescending { it.inputTokenLimit }
                        .thenBy { it.shortId }
                )
            if (chatModels.isEmpty()) {
                throw IllegalStateException(
                    "ModelRegistry returned no Gemini models supporting generateContent for this API key."
                )
            }
            chatModels
        }
    }

    suspend fun fetchAllDiscoveredGeminiModels(apiKey: String): Result<List<DiscoveredModel>> {
        return fetchModels(apiKey).map { all ->
            all.filter { it.supportsGenerateContent || it.supportsBidiGenerateContent }
                .map { info ->
                    val caps = buildList {
                        if (info.supportsGenerateContent) add("Chat")
                        if (info.supportsBidiGenerateContent) add("Live Audio")
                    }.joinToString(" + ")
                    DiscoveredModel(
                        id = info.shortId,
                        name = info.displayName.ifBlank { info.shortId },
                        provider = "GEMINI",
                        contextLength = info.inputTokenLimit,
                        isFree = false,
                        pricingSummary = caps
                    )
                }
                .sortedWith(
                    compareByDescending<DiscoveredModel> { it.pricingSummary.contains("Live Audio") }
                        .thenByDescending { scoreChatModel(it.id) }
                )
        }
    }

    fun parseModelsJson(jsonBody: String): List<GeminiModelInfo> {
        val root = JSONObject(jsonBody)
        val arr = root.optJSONArray("models") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val fullName = obj.optString("name").trim()
            if (fullName.isEmpty()) return@mapNotNull null
            val shortId = fullName.removePrefix("models/")
            val methodsArr = obj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            val methods = (0 until methodsArr.length()).mapNotNull { j ->
                methodsArr.optString(j).takeIf { it.isNotBlank() }
            }
            GeminiModelInfo(
                name = if (fullName.startsWith("models/")) fullName else "models/$fullName",
                shortId = shortId,
                displayName = obj.optString("displayName", shortId),
                supportedGenerationMethods = methods,
                inputTokenLimit = obj.optInt("inputTokenLimit", 0),
                outputTokenLimit = obj.optInt("outputTokenLimit", 0)
            )
        }
    }

    private fun scoreLiveModel(id: String): Int {
        val lower = id.lowercase()
        var score = 0
        if (lower.contains("native-audio")) score += 50
        if (lower.contains("live")) score += 35
        if (lower.contains("2.5")) score += 30
        if (lower.contains("2.0")) score += 20
        if (lower.contains("flash")) score += 15
        if (lower.contains("exp") && !lower.contains("native-audio")) score += 5
        return score
    }

    private fun scoreChatModel(id: String): Int {
        val lower = id.lowercase()
        var score = 0
        if (lower.contains("flash")) score += 40
        if (lower.contains("2.5")) score += 30
        if (lower.contains("2.0")) score += 20
        if (lower.contains("pro")) score += 15
        if (lower.contains("lite")) score -= 5
        if (lower.contains("tts") || lower.contains("image") || lower.contains("audio")) score -= 25
        return score
    }
}
