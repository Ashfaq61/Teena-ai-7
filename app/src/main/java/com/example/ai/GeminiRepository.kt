package com.example.ai

import com.example.core.security.EmergencyStopController
import com.example.core.security.KeystoreSecretVault
import com.example.core.security.SecurityAuditor
import com.example.data.local.ProviderSlotEntity
import com.example.data.local.ProviderType
import com.example.data.local.TeenaDatabase
import com.example.data.remote.GeminiHttpException
import com.example.data.remote.ModelRegistry
import com.example.data.remote.OpenAiChatResult
import com.example.data.remote.TeenaNetworkClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GeminiRepositoryException(
    val userMessageHinglish: String,
    val rawErrorLine: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : IllegalStateException(
    if (rawErrorLine.isNotBlank()) "$userMessageHinglish\nRaw: $rawErrorLine" else userMessageHinglish,
    cause
)

/**
 * Single shared Gemini repository and API key source used across Chat, Dev Studio, and Settings.
 * - Reads API keys strictly from KeystoreSecretVault + Room ProviderSlotDao (`GEMINI_DIRECT`).
 * - Executes requests through the shared [TeenaNetworkClients] HTTP client.
 * - Never falls back silently to Wikipedia or unrelated offline retrieval.
 */
class GeminiRepository(
    private val db: TeenaDatabase,
    private val vault: KeystoreSecretVault,
    private val network: TeenaNetworkClients,
    private val offlineEngine: OfflineCapabilitiesEngine? = null,
    private val emergencyStopController: EmergencyStopController? = null
) {
    companion object {
        const val SLOT_GEMINI_PRIMARY = "slot_gemini_primary"
        const val DEFAULT_GEMINI_TEXT_MODEL = "gemini-2.5-flash"

        val RECOMMENDED_TEXT_MODELS = listOf(
            DEFAULT_GEMINI_TEXT_MODEL,
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.0-flash"
        )

        fun normalizeModelId(raw: String?): String {
            val clean = raw.orEmpty().trim().removePrefix("models/").trim()
            return clean.ifEmpty { DEFAULT_GEMINI_TEXT_MODEL }
        }

        fun formatHinglishError(throwable: Throwable, modelUsed: String = ""): GeminiRepositoryException {
            if (throwable is GeminiRepositoryException) return throwable

            val chain = generateSequence(throwable) { it.cause }.take(6).toList()
            val geminiHttp = chain.filterIsInstance<GeminiHttpException>().firstOrNull()
            val rawCandidate = chain.firstNotNullOfOrNull { t ->
                t.message?.takeIf { it.isNotBlank() }
            } ?: throwable.javaClass.simpleName
            val shortRaw = SecurityAuditor.redactSecrets(rawCandidate)
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(160)

            val lowerMsg = shortRaw.lowercase()
            val cleanModel = modelUsed.removePrefix("models/").trim()

            // 1. Emergency Stop / explicit offline
            if (chain.any { it is SecurityException } ||
                lowerMsg.contains("emergency stop") ||
                lowerMsg.contains("external ai calls are disabled")
            ) {
                return GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — Emergency Stop ya External AI block active hai. Resume karein.",
                    rawErrorLine = shortRaw,
                    cause = throwable
                )
            }

            // 2. Missing API key
            if (lowerMsg.contains("api key missing") ||
                lowerMsg.contains("key is empty") ||
                lowerMsg.contains("no gemini api key") ||
                lowerMsg.contains("key set nahi")
            ) {
                return GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — Gemini API key set nahi hai. API Manager me key save karein.",
                    rawErrorLine = shortRaw,
                    cause = throwable
                )
            }

            // 3. No internet / DNS / Connect / Timeout
            val isNetworkFailure = chain.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketTimeoutException ||
                    it is InterruptedIOException ||
                    it is TimeoutCancellationException ||
                    (it is IOException && geminiHttp == null)
            } || lowerMsg.contains("unable to resolve host") ||
                lowerMsg.contains("no address associated") ||
                lowerMsg.contains("network is unreachable") ||
                lowerMsg.contains("failed to connect") ||
                lowerMsg.contains("timed out") ||
                lowerMsg.contains("timeout") ||
                lowerMsg.contains("offline")

            if (isNetworkFailure) {
                return GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — Internet connection nahi hai ya network timeout ho gaya.",
                    rawErrorLine = shortRaw,
                    cause = throwable
                )
            }

            // 4. HTTP status based mapping (401/403, 404, 429)
            val inferredCode = geminiHttp?.httpCode ?: when {
                ModelRegistry.isInvalidApiKeyError(throwable) -> 403
                ModelRegistry.isModelUnavailableError(throwable) -> 404
                ModelRegistry.isQuotaError(throwable) -> 429
                Regex("""\b401\b""").containsMatchIn(shortRaw) -> 401
                Regex("""\b403\b""").containsMatchIn(shortRaw) -> 403
                Regex("""\b404\b""").containsMatchIn(shortRaw) -> 404
                Regex("""\b429\b""").containsMatchIn(shortRaw) -> 429
                else -> null
            }

            return when (inferredCode) {
                401, 403 -> GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — HTTP $inferredCode: Gemini API key galat ya unauthorized hai.",
                    rawErrorLine = shortRaw,
                    httpCode = inferredCode,
                    cause = throwable
                )
                404 -> GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — HTTP 404: Galat Gemini model name${if (cleanModel.isNotBlank()) " '$cleanModel'" else ""} ya model unavailable hai. Settings me valid model set karein.",
                    rawErrorLine = shortRaw,
                    httpCode = 404,
                    cause = throwable
                )
                429 -> GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — HTTP 429: Gemini quota/rate limit khatam ho gaya hai, thodi der baad try karein.",
                    rawErrorLine = shortRaw,
                    httpCode = 429,
                    cause = throwable
                )
                else -> GeminiRepositoryException(
                    userMessageHinglish = "Gemini is not available — Gemini call fail ho gayi${if (inferredCode != null) " (HTTP $inferredCode)" else ""}.",
                    rawErrorLine = shortRaw,
                    httpCode = inferredCode,
                    cause = throwable
                )
            }
        }
    }

    /**
     * Resolves the active API key strictly from the PRIMARY Gemini slot in API Manager
     * (falling back to any enabled Gemini slot with a stored key if a non-Gemini provider is primary,
     * or directly reading [SLOT_GEMINI_PRIMARY] if Room slots haven't been seeded yet).
     */
    suspend fun resolvePrimarySlotAndKey(): Pair<ProviderSlotEntity?, String?> {
        val slots = db.providerSlotDao().getSlotsOnce()
        val geminiSlots = slots.filter { it.providerType == ProviderType.GEMINI_DIRECT.name && it.isEnabled }
        if (geminiSlots.isNotEmpty()) {
            val explicitPrimary = geminiSlots.firstOrNull { it.isPrimary }
            if (explicitPrimary != null) {
                val key = vault.readSecret(explicitPrimary.slotId)?.trim()?.takeIf { it.isNotEmpty() }
                return explicitPrimary to key
            }
            val firstWithKey = geminiSlots.firstOrNull { !vault.readSecret(it.slotId).isNullOrBlank() }
            val targetSlot = firstWithKey ?: geminiSlots.first()
            val key = vault.readSecret(targetSlot.slotId)?.trim()?.takeIf { it.isNotEmpty() }
            return targetSlot to key
        }

        // Only if no GEMINI_DIRECT slot exists in DB at all (e.g. startup race), still inspect shared primary vault key
        if (slots.none { it.providerType == ProviderType.GEMINI_DIRECT.name }) {
            val fallbackKey = vault.readSecret(SLOT_GEMINI_PRIMARY)?.trim()?.takeIf { it.isNotEmpty() }
            if (fallbackKey != null) {
                return ProviderSlotEntity(
                    slotId = SLOT_GEMINI_PRIMARY,
                    providerType = ProviderType.GEMINI_DIRECT.name,
                    label = "Gemini Cloud API (Primary)",
                    selectedModel = DEFAULT_GEMINI_TEXT_MODEL,
                    isEnabled = true,
                    isPrimary = true
                ) to fallbackKey
            }
        }
        return null to null
    }

    /**
     * Returns all enabled Gemini key slots ordered with primary first, for key resolution & quota/invalid-key rotation.
     */
    suspend fun resolveAllKeysInOrder(): List<Pair<ProviderSlotEntity, String>> {
        val slots = db.providerSlotDao().getSlotsOnce()
        val enabledGemini = slots
            .filter { it.providerType == ProviderType.GEMINI_DIRECT.name && it.isEnabled }
            .sortedByDescending { it.isPrimary }
        val resolved = enabledGemini.mapNotNull { slot ->
            val key = vault.readSecret(slot.slotId)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            slot to key
        }
        if (resolved.isNotEmpty()) return resolved

        if (slots.none { it.providerType == ProviderType.GEMINI_DIRECT.name }) {
            val fallbackKey = vault.readSecret(SLOT_GEMINI_PRIMARY)?.trim()?.takeIf { it.isNotEmpty() }
            if (fallbackKey != null) {
                return listOf(
                    ProviderSlotEntity(
                        slotId = SLOT_GEMINI_PRIMARY,
                        providerType = ProviderType.GEMINI_DIRECT.name,
                        label = "Gemini Cloud API (Primary)",
                        selectedModel = DEFAULT_GEMINI_TEXT_MODEL,
                        isEnabled = true,
                        isPrimary = true
                    ) to fallbackKey
                )
            }
        }
        return emptyList()
    }

    suspend fun hasApiKey(): Boolean {
        return resolveAllKeysInOrder().isNotEmpty() || !resolvePrimarySlotAndKey().second.isNullOrBlank()
    }

    /**
     * Direct shared Gemini text invocation using the exact same [TeenaNetworkClients] client
     * and Keystore key source as Chat. Never silently falls back to Wikipedia or offline retrieval.
     */
    suspend fun askDirectText(
        userPrompt: String,
        systemInstruction: String = "",
        preferredModel: String? = null,
        recentHistory: List<Pair<String, String>> = emptyList(),
        maxOutputTokens: Int? = null,
        timeoutMs: Long = 60_000L,
        onDelta: (suspend (String) -> Unit)? = null
    ): Result<OpenAiChatResult> {
        val keys = resolveAllKeysInOrder()
        if (keys.isEmpty()) {
            return Result.failure(formatHinglishError(IllegalStateException("Gemini API key missing")))
        }

        val primarySlotModel = keys.firstOrNull()?.first?.selectedModel
        val modelToUse = normalizeModelId(preferredModel?.takeIf { it.isNotBlank() } ?: primarySlotModel)

        try {
            emergencyStopController?.assertExternalNetworkAllowed("Gemini request ($modelToUse)")
        } catch (sec: SecurityException) {
            return Result.failure(formatHinglishError(sec, modelToUse))
        }

        var lastError: Throwable? = null
        for ((slot, apiKey) in keys) {
            try {
                val res = withTimeout(timeoutMs) {
                    if (onDelta != null) {
                        val streamRes = network.streamGeminiDirectRest(
                            apiKey = apiKey,
                            model = modelToUse,
                            systemInstruction = systemInstruction,
                            userPrompt = userPrompt,
                            recentHistory = recentHistory,
                            onDelta = onDelta
                        )
                        streamRes.getOrElse { streamErr ->
                            if (ModelRegistry.isInvalidApiKeyError(streamErr) ||
                                ModelRegistry.isModelUnavailableError(streamErr) ||
                                ModelRegistry.isQuotaError(streamErr)
                            ) {
                                throw streamErr
                            }
                            val unary = network.chatGeminiDirectRest(
                                apiKey = apiKey,
                                model = modelToUse,
                                systemInstruction = systemInstruction,
                                userPrompt = userPrompt,
                                recentHistory = recentHistory,
                                maxOutputTokens = maxOutputTokens
                            ).getOrThrow()
                            onDelta(unary.text)
                            unary
                        }
                    } else {
                        network.chatGeminiDirectRest(
                            apiKey = apiKey,
                            model = modelToUse,
                            systemInstruction = systemInstruction,
                            userPrompt = userPrompt,
                            recentHistory = recentHistory,
                            maxOutputTokens = maxOutputTokens
                        ).getOrThrow()
                    }
                }
                db.providerSlotDao().upsertSlot(
                    slot.copy(
                        selectedModel = res.modelUsed,
                        lastTestStatus = "ACTIVE",
                        lastTestMessage = "Verified ${res.modelUsed} in ${res.latencyMs}ms",
                        lastLatencyMs = res.latencyMs,
                        lastTestedAt = System.currentTimeMillis()
                    )
                )
                return Result.success(res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val safeMsg = SecurityAuditor.redactSecrets(e.localizedMessage ?: "Gemini failed").take(220)
                val status = if (ModelRegistry.isQuotaError(e)) "PARTIAL" else "FAILED"
                db.providerSlotDao().upsertSlot(
                    slot.copy(
                        lastTestStatus = status,
                        lastTestMessage = safeMsg,
                        lastTestedAt = System.currentTimeMillis()
                    )
                )
                // Only rotate to next Gemini key if multiple Gemini keys exist and this was quota/bad key
                if (ModelRegistry.isQuotaError(e) || ModelRegistry.isInvalidApiKeyError(e)) {
                    continue
                }
                return Result.failure(formatHinglishError(e, modelToUse))
            }
        }

        return Result.failure(
            formatHinglishError(
                lastError ?: IllegalStateException("Gemini API request failed"),
                modelToUse
            )
        )
    }

    suspend fun testHello(preferredModel: String? = null): Result<OpenAiChatResult> {
        return askDirectText(
            userPrompt = "hi",
            systemInstruction = "",
            preferredModel = preferredModel,
            maxOutputTokens = 64,
            timeoutMs = 30_000L
        )
    }
}
