package com.example.data.remote

import com.example.core.security.SecurityAuditor
import com.example.data.local.GeminiModelCacheEntity
import com.example.data.local.GeminiModelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
 * Dynamic Gemini v1beta ModelRegistry backed by Room cache & per-key blacklisting.
 * - Filters strictly by `supportedGenerationMethods` (`generateContent` / `bidiGenerateContent`).
 * - Ranks non-blacklisted models strictly by:
 *   working > stable (not preview/exp/alpha/beta) > newer version > flash > lower latency.
 */
class ModelRegistry(
    private val httpClient: OkHttpClient,
    private val geminiModelDao: GeminiModelDao? = null,
    private val geminiBaseUrl: String = "https://generativelanguage.googleapis.com"
) {
    companion object {
        private val VERSION_REGEX = Regex("""gemini-(\d+)(?:\.(\d+))?(?:\.(\d+))?""", RegexOption.IGNORE_CASE)
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        fun keyFingerprint(apiKey: String): String {
            val clean = apiKey.trim()
            if (clean.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun isQuotaError(t: Throwable): Boolean {
            if (t is GeminiHttpException && t.httpCode == 429) return true
            val msg = t.message.orEmpty()
            return msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                msg.contains("HTTP 429", ignoreCase = true) ||
                msg.contains("429:", ignoreCase = true) ||
                msg.contains("exceeded your current quota", ignoreCase = true) ||
                msg.contains("quota", ignoreCase = true) ||
                msg.contains("rate limit", ignoreCase = true) ||
                msg.contains("too many requests", ignoreCase = true)
        }

        fun isModelUnavailableError(t: Throwable): Boolean {
            if (isQuotaError(t)) return false
            if (t is GeminiHttpException) {
                if (t.httpCode == 404 || t.apiStatus.equals("NOT_FOUND", ignoreCase = true)) {
                    return true
                }
            }
            val msg = t.message.orEmpty()
            return msg.contains("no longer available", ignoreCase = true) ||
                msg.contains("not found", ignoreCase = true) ||
                msg.contains("NOT_FOUND", ignoreCase = true) ||
                msg.contains("HTTP 404", ignoreCase = true) ||
                msg.contains("404:", ignoreCase = true) ||
                msg.contains("is not supported for generateContent", ignoreCase = true) ||
                msg.contains("is not supported for bidiGenerateContent", ignoreCase = true) ||
                msg.contains("has been deprecated", ignoreCase = true) ||
                msg.contains("retired", ignoreCase = true)
        }

        fun isInvalidApiKeyError(t: Throwable): Boolean {
            val msg = t.message.orEmpty()
            if (t is GeminiHttpException && t.httpCode == 401) return true
            if (t is GeminiHttpException && t.httpCode == 403 && !msg.contains("model", ignoreCase = true)) {
                return true
            }
            return msg.contains("API_KEY_INVALID", ignoreCase = true) ||
                msg.contains("API key not valid", ignoreCase = true) ||
                msg.contains("API key expired", ignoreCase = true)
        }

        fun isStableModelId(shortId: String): Boolean {
            val lower = shortId.lowercase()
            return !lower.contains("preview") &&
                !lower.contains("-exp") &&
                !lower.contains("experimental") &&
                !lower.contains("alpha") &&
                !lower.contains("beta")
        }

        fun isSpecializedNonChatModel(shortId: String): Boolean {
            val lower = shortId.lowercase()
            return lower.contains("-tts") ||
                lower.contains("-image") ||
                lower.contains("robotics") ||
                lower.contains("computer-use") ||
                lower.contains("embedding") ||
                lower.contains("aqa")
        }

        fun parseSemanticVersion(shortId: String): Triple<Int, Int, Int> {
            val match = VERSION_REGEX.find(shortId) ?: return Triple(0, 0, 0)
            val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return Triple(major, minor, patch)
        }

        private fun modelTierScore(shortId: String, forLive: Boolean): Int {
            val lower = shortId.lowercase()
            if (forLive) {
                var s = 0
                if (lower.contains("native-audio")) s += 4
                if (lower.contains("live")) s += 2
                if (lower.contains("flash")) s += 1
                return s
            }
            return when {
                lower.contains("flash") && !lower.contains("lite") -> 3
                lower.contains("flash-lite") || lower.contains("lite") -> 2
                lower.contains("pro") -> 1
                else -> 0
            }
        }

        /**
         * Sorts candidates strictly by:
         * 1. Verified WORKING in cache > UNPROBED (excludes BLACKLISTED)
         * 2. General-purpose model before specialized TTS/image-only variants (chat)
         * 3. Stable release (not preview/exp/alpha/beta) > pre-release
         * 4. Higher semantic version (e.g. 3.8 > 2.5 > 2.0 > 1.5)
         * 5. Higher tier (flash > flash-lite > pro) within same stability & version
         * 6. Lower measured latency in ms
         * 7. Higher input token limit & lexicographical shortId
         */
        fun rankModels(
            models: List<GeminiModelInfo>,
            cachedById: Map<String, GeminiModelCacheEntity> = emptyMap(),
            forLive: Boolean = false
        ): List<GeminiModelInfo> {
            return models
                .filter { cachedById[it.shortId]?.probeStatus != "BLACKLISTED" }
                .sortedWith { a, b ->
                    val cacheA = cachedById[a.shortId]
                    val cacheB = cachedById[b.shortId]

                    val workA = if (cacheA?.probeStatus == "WORKING") 1 else 0
                    val workB = if (cacheB?.probeStatus == "WORKING") 1 else 0
                    if (workA != workB) return@sortedWith workB.compareTo(workA)

                    if (!forLive) {
                        val genA = if (!isSpecializedNonChatModel(a.shortId)) 1 else 0
                        val genB = if (!isSpecializedNonChatModel(b.shortId)) 1 else 0
                        if (genA != genB) return@sortedWith genB.compareTo(genA)
                    }

                    val stableA = if (isStableModelId(a.shortId)) 1 else 0
                    val stableB = if (isStableModelId(b.shortId)) 1 else 0
                    if (stableA != stableB) return@sortedWith stableB.compareTo(stableA)

                    val verA = parseSemanticVersion(a.shortId)
                    val verB = parseSemanticVersion(b.shortId)
                    if (verA.first != verB.first) return@sortedWith verB.first.compareTo(verA.first)
                    if (verA.second != verB.second) return@sortedWith verB.second.compareTo(verA.second)
                    if (verA.third != verB.third) return@sortedWith verB.third.compareTo(verA.third)

                    val tierA = modelTierScore(a.shortId, forLive)
                    val tierB = modelTierScore(b.shortId, forLive)
                    if (tierA != tierB) return@sortedWith tierB.compareTo(tierA)

                    val latA = cacheA?.lastLatencyMs ?: Long.MAX_VALUE
                    val latB = cacheB?.lastLatencyMs ?: Long.MAX_VALUE
                    if (latA != latB) return@sortedWith latA.compareTo(latB)

                    if (a.inputTokenLimit != b.inputTokenLimit) {
                        return@sortedWith b.inputTokenLimit.compareTo(a.inputTokenLimit)
                    }
                    a.shortId.compareTo(b.shortId)
                }
        }
    }

    suspend fun fetchModelsFromNetwork(apiKey: String): Result<List<GeminiModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isEmpty()) {
                throw IllegalStateException("Gemini API key missing")
            }
            val request = Request.Builder()
                .url("${geminiBaseUrl.trimEnd('/')}/v1beta/models?pageSize=200")
                .addHeader("x-goog-api-key", cleanKey)
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val parsed = runCatching {
                        val j = JSONObject(body)
                        val eObj = j.optJSONObject("error")
                        val status = eObj?.optString("status").orEmpty()
                        val msg = eObj?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: j.optString("message").takeIf { it.isNotBlank() }
                            ?: "ModelRegistry HTTP ${resp.code}"
                        status to msg
                    }.getOrElse { "" to "ModelRegistry HTTP ${resp.code}" }
                    throw GeminiHttpException(
                        httpCode = resp.code,
                        apiStatus = parsed.first,
                        message = SecurityAuditor.redactSecrets(parsed.second)
                    )
                }
                parseModelsJson(body)
            }
        }
    }

    suspend fun refreshAndCacheModels(apiKey: String): Result<List<GeminiModelInfo>> {
        val cleanKey = apiKey.trim()
        val fp = keyFingerprint(cleanKey)
        return fetchModelsFromNetwork(cleanKey).onSuccess { fetched ->
            if (geminiModelDao != null && fp.isNotEmpty() && fetched.isNotEmpty()) {
                val existing = geminiModelDao.getModelsForKeyOnce(fp).associateBy { it.modelId }
                val now = System.currentTimeMillis()
                val merged = fetched.map { info ->
                    val prev = existing[info.shortId]
                    GeminiModelCacheEntity(
                        cacheKey = "$fp:${info.shortId}",
                        keyFingerprint = fp,
                        modelId = info.shortId,
                        fullName = info.name,
                        displayName = info.displayName,
                        supportsGenerateContent = info.supportsGenerateContent,
                        supportsBidiGenerateContent = info.supportsBidiGenerateContent,
                        inputTokenLimit = info.inputTokenLimit,
                        outputTokenLimit = info.outputTokenLimit,
                        probeStatus = prev?.probeStatus ?: "UNPROBED",
                        probeError = prev?.probeError ?: "",
                        lastLatencyMs = prev?.lastLatencyMs ?: Long.MAX_VALUE,
                        updatedAt = now
                    )
                }
                geminiModelDao.upsertModels(merged)
                geminiModelDao.pruneRemovedModels(fp, fetched.map { it.shortId })
            }
        }
    }

    suspend fun getOrRefreshModels(
        apiKey: String,
        forceRefresh: Boolean = false
    ): Result<List<GeminiModelInfo>> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) return Result.failure(IllegalStateException("Gemini API key missing"))
        val fp = keyFingerprint(cleanKey)
        if (!forceRefresh && geminiModelDao != null) {
            val cached = geminiModelDao.getModelsForKeyOnce(fp)
            val newest = cached.maxOfOrNull { it.updatedAt } ?: 0L
            if (cached.isNotEmpty() && (System.currentTimeMillis() - newest) < CACHE_TTL_MS) {
                return Result.success(cached.map { it.toModelInfo() })
            }
        }
        return refreshAndCacheModels(cleanKey)
    }

    suspend fun getRankedChatCandidates(
        apiKey: String,
        forceRefresh: Boolean = false
    ): Result<List<GeminiModelInfo>> {
        val fp = keyFingerprint(apiKey)
        return getOrRefreshModels(apiKey, forceRefresh).mapCatching { all ->
            val generateCapable = all.filter { it.supportsGenerateContent }
            if (generateCapable.isEmpty()) {
                throw IllegalStateException(
                    "ModelRegistry returned no models supporting generateContent for this API key."
                )
            }
            val cachedMap = if (geminiModelDao != null && fp.isNotEmpty()) {
                geminiModelDao.getModelsForKeyOnce(fp).associateBy { it.modelId }
            } else emptyMap()

            val ranked = rankModels(generateCapable, cachedMap, forLive = false)
            if (ranked.isEmpty()) {
                val reasons = cachedMap.values
                    .filter { it.probeStatus == "BLACKLISTED" && it.supportsGenerateContent }
                    .joinToString("; ") { "${it.modelId}: ${it.probeError}" }
                throw IllegalStateException(
                    "All generateContent models are blacklisted for this key. $reasons"
                )
            }
            ranked
        }
    }

    suspend fun getRankedLiveCandidates(
        apiKey: String,
        forceRefresh: Boolean = false
    ): Result<List<GeminiModelInfo>> {
        val fp = keyFingerprint(apiKey)
        return getOrRefreshModels(apiKey, forceRefresh).mapCatching { all ->
            val bidiCapable = all.filter { it.supportsBidiGenerateContent }
            if (bidiCapable.isEmpty()) {
                throw IllegalStateException(
                    "ModelRegistry returned no models supporting bidiGenerateContent for this API key."
                )
            }
            val cachedMap = if (geminiModelDao != null && fp.isNotEmpty()) {
                geminiModelDao.getModelsForKeyOnce(fp).associateBy { it.modelId }
            } else emptyMap()

            val ranked = rankModels(bidiCapable, cachedMap, forLive = true)
            if (ranked.isEmpty()) {
                val reasons = cachedMap.values
                    .filter { it.probeStatus == "BLACKLISTED" && it.supportsBidiGenerateContent }
                    .joinToString("; ") { "${it.modelId}: ${it.probeError}" }
                throw IllegalStateException(
                    "All bidiGenerateContent models are blacklisted for this key. $reasons"
                )
            }
            ranked
        }
    }

    suspend fun markModelWorking(apiKey: String, modelId: String, latencyMs: Long) {
        val fp = keyFingerprint(apiKey)
        val dao = geminiModelDao ?: return
        if (fp.isEmpty()) return
        val cleanId = modelId.removePrefix("models/").trim()
        val cacheKey = "$fp:$cleanId"
        val existing = dao.getModelByCacheKey(cacheKey)
        dao.upsertModel(
            (existing ?: GeminiModelCacheEntity(
                cacheKey = cacheKey,
                keyFingerprint = fp,
                modelId = cleanId,
                fullName = "models/$cleanId",
                displayName = cleanId,
                supportsGenerateContent = true,
                supportsBidiGenerateContent = false
            )).copy(
                probeStatus = "WORKING",
                probeError = "",
                lastLatencyMs = latencyMs.coerceAtLeast(1L),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markModelBlacklisted(apiKey: String, modelId: String, reason: String) {
        val fp = keyFingerprint(apiKey)
        val dao = geminiModelDao ?: return
        if (fp.isEmpty()) return
        val cleanId = modelId.removePrefix("models/").trim()
        val cacheKey = "$fp:$cleanId"
        val existing = dao.getModelByCacheKey(cacheKey)
        dao.upsertModel(
            (existing ?: GeminiModelCacheEntity(
                cacheKey = cacheKey,
                keyFingerprint = fp,
                modelId = cleanId,
                fullName = "models/$cleanId",
                displayName = cleanId,
                supportsGenerateContent = true,
                supportsBidiGenerateContent = false
            )).copy(
                probeStatus = "BLACKLISTED",
                probeError = SecurityAuditor.redactSecrets(reason).take(220),
                lastLatencyMs = Long.MAX_VALUE,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearBlacklist(apiKey: String) {
        val fp = keyFingerprint(apiKey)
        if (fp.isNotEmpty()) {
            geminiModelDao?.clearBlacklistForKey(fp)
        }
    }

    suspend fun getCachedModelsForKey(apiKey: String): List<GeminiModelCacheEntity> {
        val fp = keyFingerprint(apiKey)
        if (fp.isEmpty()) return emptyList()
        return geminiModelDao?.getModelsForKeyOnce(fp).orEmpty()
    }

    suspend fun fetchAllDiscoveredGeminiModels(apiKey: String): Result<List<DiscoveredModel>> {
        val fp = keyFingerprint(apiKey)
        return refreshAndCacheModels(apiKey).map { all ->
            val cachedMap = if (geminiModelDao != null && fp.isNotEmpty()) {
                geminiModelDao.getModelsForKeyOnce(fp).associateBy { it.modelId }
            } else emptyMap()

            val sorted = rankModels(
                models = all.filter { it.supportsGenerateContent || it.supportsBidiGenerateContent },
                cachedById = cachedMap,
                forLive = false
            )
            sorted.map { info ->
                val cache = cachedMap[info.shortId]
                val caps = buildList {
                    if (info.supportsGenerateContent) add("Chat")
                    if (info.supportsBidiGenerateContent) add("Live Audio")
                    if (cache?.probeStatus == "WORKING") add("Verified ${cache.lastLatencyMs}ms")
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

    private fun GeminiModelCacheEntity.toModelInfo(): GeminiModelInfo {
        val methods = buildList {
            if (supportsGenerateContent) add("generateContent")
            if (supportsBidiGenerateContent) add("bidiGenerateContent")
        }
        return GeminiModelInfo(
            name = fullName,
            shortId = modelId,
            displayName = displayName,
            supportedGenerationMethods = methods,
            inputTokenLimit = inputTokenLimit,
            outputTokenLimit = outputTokenLimit
        )
    }
}
