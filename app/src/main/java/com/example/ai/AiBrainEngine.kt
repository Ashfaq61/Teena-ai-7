package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.core.security.KeystoreSecretVault
import com.example.core.security.SecurityAuditor
import com.example.data.local.AuditLogEntity
import com.example.data.local.MemoryFactEntity
import com.example.data.local.ProviderSlotEntity
import com.example.data.local.ProviderType
import com.example.data.local.TeenaDatabase
import com.example.data.remote.DiscoveredModel
import com.example.data.remote.TeenaNetworkClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

data class AiInferenceOutput(
    val text: String,
    val providerUsed: String,
    val modelUsed: String,
    val detectedLanguage: String,
    val latencyMs: Long,
    val failoverNotes: String = ""
)

sealed interface AiStreamChunk {
    data class Delta(val accumulatedText: String, val provider: String, val model: String) : AiStreamChunk
    data class Completed(val output: AiInferenceOutput) : AiStreamChunk
    data class Error(val message: String) : AiStreamChunk
}

class AiBrainEngine(
    private val db: TeenaDatabase,
    private val vault: KeystoreSecretVault,
    private val network: TeenaNetworkClients,
    private val offlineEngine: OfflineCapabilitiesEngine
) {
    companion object {
        private const val TAG = "AiBrainEngine"
        const val SLOT_GEMINI_PRIMARY = "slot_gemini_primary"
        private const val LEGACY_FIREBASE_SLOT = "slot_gemini_ai_logic"
    }

    suspend fun ensureDefaultProviderSlots() {
        val existing = db.providerSlotDao().getSlotsOnce()

        // Remove any legacy Firebase AI Logic provider slots completely
        existing.filter {
            it.slotId == LEGACY_FIREBASE_SLOT || it.providerType == "GEMINI_AI_LOGIC"
        }.forEach { legacy ->
            db.providerSlotDao().deleteSlot(legacy.slotId)
        }

        val remaining = db.providerSlotDao().getSlotsOnce()
        val hasGeminiSlot = remaining.any { it.providerType == ProviderType.GEMINI_DIRECT.name }
        if (!hasGeminiSlot) {
            val hasStoredKey = vault.hasSecret(SLOT_GEMINI_PRIMARY)
            val hasAnotherPrimary = remaining.any { it.isPrimary }
            db.providerSlotDao().upsertSlot(
                ProviderSlotEntity(
                    slotId = SLOT_GEMINI_PRIMARY,
                    providerType = ProviderType.GEMINI_DIRECT.name,
                    label = "Gemini Cloud API (Primary)",
                    selectedModel = "",
                    isEnabled = true,
                    isPrimary = !hasAnotherPrimary,
                    lastTestStatus = if (hasStoredKey) "READY" else "BLOCKED",
                    lastTestMessage = if (hasStoredKey) {
                        "API key stored in Keystore vault; tap Test to verify."
                    } else {
                        "Gemini API key missing"
                    }
                )
            )
        }

        if (remaining.none { it.providerType == ProviderType.OFFLINE_LOCAL.name }) {
            db.providerSlotDao().upsertSlot(
                ProviderSlotEntity(
                    slotId = "slot_offline_local",
                    providerType = ProviderType.OFFLINE_LOCAL.name,
                    label = "Local Offline Command & Knowledge Engine",
                    selectedModel = "teena-local-tfidf-v1",
                    isEnabled = true,
                    isPrimary = false,
                    lastTestStatus = "ACTIVE",
                    lastTestMessage = "On-device deterministic command + memory retrieval active."
                )
            )
        }
    }

    /**
     * Resolves the active API key strictly from the PRIMARY Gemini slot in API Manager
     * (falling back to any enabled Gemini slot with a stored key only if a non-Gemini provider is primary).
     */
    suspend fun resolvePrimaryGeminiSlotAndKey(): Pair<ProviderSlotEntity?, String?> {
        val slots = db.providerSlotDao().getSlotsOnce()
        val geminiSlots = slots.filter { it.providerType == ProviderType.GEMINI_DIRECT.name && it.isEnabled }
        if (geminiSlots.isEmpty()) return null to null

        val explicitPrimaryGemini = geminiSlots.firstOrNull { it.isPrimary }
        if (explicitPrimaryGemini != null) {
            val key = vault.readSecret(explicitPrimaryGemini.slotId)?.trim()?.takeIf { it.isNotEmpty() }
            return explicitPrimaryGemini to key
        }

        val firstWithKey = geminiSlots.firstOrNull { !vault.readSecret(it.slotId).isNullOrBlank() }
        val targetSlot = firstWithKey ?: geminiSlots.first()
        val key = vault.readSecret(targetSlot.slotId)?.trim()?.takeIf { it.isNotEmpty() }
        return targetSlot to key
    }

    suspend fun hasPrimaryGeminiApiKey(): Boolean {
        return !resolvePrimaryGeminiSlotAndKey().second.isNullOrBlank()
    }

    fun generateStreamingChat(
        userPrompt: String,
        recentHistory: List<Pair<String, String>>,
        memories: List<MemoryFactEntity>,
        attachedImage: Bitmap? = null,
        autoFailover: Boolean = true
    ): Flow<AiStreamChunk> = flow {
        val lang = offlineEngine.detectLanguage(userPrompt)
        val systemPrompt = buildTeenaSystemInstruction(lang, memories)
        val slots = db.providerSlotDao().getSlotsOnce().filter { it.isEnabled }
        val orderedSlots = slots.sortedByDescending { it.isPrimary }

        if (orderedSlots.isEmpty()) {
            emit(AiStreamChunk.Error("No AI provider slots are enabled."))
            return@flow
        }

        val failureNotes = mutableListOf<String>()

        for (slot in orderedSlots) {
            val type = runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull() ?: continue
            val start = System.currentTimeMillis()
            try {
                when (type) {
                    ProviderType.GEMINI_DIRECT -> {
                        val apiKey = vault.readSecret(slot.slotId)?.trim()
                        if (apiKey.isNullOrEmpty()) {
                            markSlotResult(slot, "BLOCKED", "Gemini API key missing", 0L)
                            failureNotes.add("${slot.label}: Gemini API key missing")
                            continue
                        }

                        val resolvedModel = slot.selectedModel.trim().ifBlank {
                            val detected = network.modelRegistry.autoDetectChatModels(apiKey).getOrThrow()
                            val chosen = detected.first().shortId
                            db.providerSlotDao().upsertSlot(slot.copy(selectedModel = chosen))
                            chosen
                        }

                        val result = withTimeout(60_000L) {
                            if (attachedImage != null) {
                                val resp = network.chatGeminiDirectRest(
                                    apiKey = apiKey,
                                    model = resolvedModel,
                                    systemInstruction = systemPrompt,
                                    userPrompt = userPrompt,
                                    recentHistory = recentHistory,
                                    attachedImage = attachedImage
                                ).getOrThrow()
                                emit(AiStreamChunk.Delta(resp.text, slot.label, resp.modelUsed))
                                resp
                            } else {
                                network.streamGeminiDirectRest(
                                    apiKey = apiKey,
                                    model = resolvedModel,
                                    systemInstruction = systemPrompt,
                                    userPrompt = userPrompt,
                                    recentHistory = recentHistory
                                ) { accumulated ->
                                    emit(AiStreamChunk.Delta(accumulated, slot.label, resolvedModel))
                                }.getOrElse {
                                    // Fallback to unary REST generateContent if SSE fails on a specific model
                                    val unary = network.chatGeminiDirectRest(
                                        apiKey = apiKey,
                                        model = resolvedModel,
                                        systemInstruction = systemPrompt,
                                        userPrompt = userPrompt,
                                        recentHistory = recentHistory
                                    ).getOrThrow()
                                    emit(AiStreamChunk.Delta(unary.text, slot.label, unary.modelUsed))
                                    unary
                                }
                            }
                        }

                        markSlotResult(slot.copy(selectedModel = result.modelUsed), "ACTIVE", "Responded in ${result.latencyMs}ms", result.latencyMs)
                        emit(
                            AiStreamChunk.Completed(
                                AiInferenceOutput(
                                    text = result.text,
                                    providerUsed = slot.label,
                                    modelUsed = result.modelUsed,
                                    detectedLanguage = lang,
                                    latencyMs = result.latencyMs,
                                    failoverNotes = failureNotes.joinToString(" | ")
                                )
                            )
                        )
                        return@flow
                    }

                    ProviderType.GROQ, ProviderType.OPENROUTER, ProviderType.CUSTOM_OPENAI -> {
                        val apiKey = vault.readSecret(slot.slotId)
                        if (apiKey.isNullOrBlank() && type != ProviderType.CUSTOM_OPENAI) {
                            failureNotes.add("${slot.label}: missing API key")
                            continue
                        }
                        val baseUrl = when (type) {
                            ProviderType.GROQ -> "https://api.groq.com/openai/v1"
                            ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
                            else -> slot.baseUrl.ifBlank { throw IllegalStateException("Missing base URL for custom provider.") }
                        }
                        val headers = if (type == ProviderType.OPENROUTER) {
                            mapOf("HTTP-Referer" to "https://teena-ai.local", "X-Title" to "Teena AI Android")
                        } else emptyMap()

                        val msgList = buildList {
                            add("system" to systemPrompt)
                            recentHistory.takeLast(8).forEach { add(it) }
                            add("user" to userPrompt)
                        }
                        val res = network.chatOpenAiCompatible(
                            baseUrl = baseUrl,
                            apiKey = apiKey.orEmpty(),
                            model = slot.selectedModel,
                            messages = msgList,
                            extraHeaders = headers
                        ).getOrThrow()

                        emit(AiStreamChunk.Delta(res.text, slot.label, res.modelUsed))
                        markSlotResult(slot, "ACTIVE", "Responded in ${res.latencyMs}ms", res.latencyMs)
                        emit(
                            AiStreamChunk.Completed(
                                AiInferenceOutput(
                                    text = res.text,
                                    providerUsed = slot.label,
                                    modelUsed = res.modelUsed,
                                    detectedLanguage = lang,
                                    latencyMs = res.latencyMs,
                                    failoverNotes = failureNotes.joinToString(" | ")
                                )
                            )
                        )
                        return@flow
                    }

                    ProviderType.OFFLINE_LOCAL -> {
                        val res = offlineEngine.evaluateLocalCommandOrKnowledge(
                            prompt = userPrompt,
                            memories = memories,
                            research = db.researchDao().getResearchOnce()
                        )
                        emit(AiStreamChunk.Delta(res.replyText, slot.label, slot.selectedModel))
                        emit(
                            AiStreamChunk.Completed(
                                AiInferenceOutput(
                                    text = res.replyText,
                                    providerUsed = slot.label,
                                    modelUsed = slot.selectedModel,
                                    detectedLanguage = lang,
                                    latencyMs = System.currentTimeMillis() - start,
                                    failoverNotes = failureNotes.joinToString(" | ")
                                )
                            )
                        )
                        return@flow
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                val msg = "${slot.label} timed out after 60s"
                Log.e(TAG, msg, e)
                failureNotes.add(msg)
                markSlotResult(slot, "FAILED", msg, System.currentTimeMillis() - start)
                if (!autoFailover) {
                    emit(AiStreamChunk.Error(msg))
                    return@flow
                }
            } catch (e: Exception) {
                val safeMsg = SecurityAuditor.redactSecrets(e.localizedMessage ?: "Connection failed")
                Log.e(TAG, "Provider ${slot.label} failed: $safeMsg", e)
                failureNotes.add("${slot.label}: $safeMsg")
                markSlotResult(slot, "FAILED", safeMsg, System.currentTimeMillis() - start)
                if (!autoFailover) {
                    emit(AiStreamChunk.Error("${slot.label} failed: $safeMsg"))
                    return@flow
                }
            }
        }

        emit(
            AiStreamChunk.Error(
                "All enabled AI providers failed: ${failureNotes.joinToString(" ; ")}"
            )
        )
    }

    suspend fun generateQuickAnalysis(prompt: String): Result<String> {
        return runCatching {
            val slots = db.providerSlotDao().getSlotsOnce().filter { it.isEnabled }.sortedByDescending { it.isPrimary }
            for (slot in slots) {
                val type = runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull() ?: continue
                try {
                    when (type) {
                        ProviderType.GEMINI_DIRECT -> {
                            val key = vault.readSecret(slot.slotId)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                            val modelId = slot.selectedModel.trim().ifBlank {
                                network.modelRegistry.autoDetectChatModels(key).getOrNull()?.firstOrNull()?.shortId ?: continue
                            }
                            val res = withTimeout(45_000L) {
                                network.chatGeminiDirectRest(
                                    apiKey = key,
                                    model = modelId,
                                    systemInstruction = "",
                                    userPrompt = prompt
                                )
                            }
                            if (res.isSuccess) return@runCatching res.getOrThrow().text
                        }
                        ProviderType.GROQ, ProviderType.OPENROUTER, ProviderType.CUSTOM_OPENAI -> {
                            val key = vault.readSecret(slot.slotId)
                            if (key.isNullOrBlank() && type != ProviderType.CUSTOM_OPENAI) continue
                            val base = when (type) {
                                ProviderType.GROQ -> "https://api.groq.com/openai/v1"
                                ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
                                else -> slot.baseUrl
                            }
                            val res = network.chatOpenAiCompatible(
                                baseUrl = base,
                                apiKey = key.orEmpty(),
                                model = slot.selectedModel,
                                messages = listOf("user" to prompt)
                            )
                            if (res.isSuccess) return@runCatching res.getOrThrow().text
                        }
                        ProviderType.OFFLINE_LOCAL -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Failover to next provider
                }
            }
            throw IllegalStateException("No online AI provider succeeded for analysis.")
        }
    }

    suspend fun testProviderSlot(slot: ProviderSlotEntity): ProviderSlotEntity {
        val type = ProviderType.valueOf(slot.providerType)
        val start = System.currentTimeMillis()
        val updated = try {
            when (type) {
                ProviderType.GEMINI_DIRECT -> {
                    val key = vault.readSecret(slot.slotId)?.trim()
                    if (key.isNullOrEmpty()) {
                        slot.copy(
                            lastTestStatus = "BLOCKED",
                            lastTestMessage = "Gemini API key missing",
                            lastLatencyMs = 0L,
                            lastTestedAt = System.currentTimeMillis()
                        )
                    } else {
                        val chatModels = network.modelRegistry.autoDetectChatModels(key).getOrThrow()
                        val liveModels = network.modelRegistry.autoDetectLiveModels(key).getOrNull().orEmpty()
                        val targetModel = slot.selectedModel.trim().ifBlank { chatModels.first().shortId }
                        val res = network.chatGeminiDirectRest(
                            apiKey = key,
                            model = targetModel,
                            systemInstruction = "",
                            userPrompt = "Reply with OK"
                        ).getOrThrow()
                        slot.copy(
                            selectedModel = targetModel,
                            lastTestStatus = "ACTIVE",
                            lastTestMessage = "REST verified (${res.modelUsed}, ${res.latencyMs}ms); ${liveModels.size} Live bidi model(s) detected",
                            lastLatencyMs = res.latencyMs,
                            lastTestedAt = System.currentTimeMillis()
                        )
                    }
                }

                ProviderType.GROQ -> {
                    val key = vault.readSecret(slot.slotId)
                        ?: throw IllegalStateException("API key is empty in Keystore vault.")
                    val models = network.fetchGroqModels(key).getOrThrow()
                    val lat = System.currentTimeMillis() - start
                    slot.copy(
                        lastTestStatus = "ACTIVE",
                        lastTestMessage = "Connected (${models.size} models available, ${lat}ms)",
                        lastLatencyMs = lat,
                        lastTestedAt = System.currentTimeMillis()
                    )
                }

                ProviderType.OPENROUTER -> {
                    val key = vault.readSecret(slot.slotId)
                        ?: throw IllegalStateException("API key is empty in Keystore vault.")
                    val res = network.chatOpenAiCompatible(
                        baseUrl = "https://openrouter.ai/api/v1",
                        apiKey = key,
                        model = slot.selectedModel,
                        messages = listOf("user" to "Say OK"),
                        extraHeaders = mapOf("HTTP-Referer" to "https://teena-ai.local", "X-Title" to "Teena AI")
                    ).getOrThrow()
                    slot.copy(
                        lastTestStatus = "ACTIVE",
                        lastTestMessage = "Verified ${res.modelUsed} in ${res.latencyMs}ms",
                        lastLatencyMs = res.latencyMs,
                        lastTestedAt = System.currentTimeMillis()
                    )
                }

                ProviderType.CUSTOM_OPENAI -> {
                    val key = vault.readSecret(slot.slotId).orEmpty()
                    val res = network.chatOpenAiCompatible(
                        baseUrl = slot.baseUrl,
                        apiKey = key,
                        model = slot.selectedModel,
                        messages = listOf("user" to "Say OK")
                    ).getOrThrow()
                    slot.copy(
                        lastTestStatus = "ACTIVE",
                        lastTestMessage = "Verified custom endpoint in ${res.latencyMs}ms",
                        lastLatencyMs = res.latencyMs,
                        lastTestedAt = System.currentTimeMillis()
                    )
                }

                ProviderType.OFFLINE_LOCAL -> {
                    val hw = offlineEngine.inspectHardware()
                    slot.copy(
                        lastTestStatus = "ACTIVE",
                        lastTestMessage = "Active (${"%.1f".format(hw.totalRamGb)} GB RAM, AICore=${hw.hasAiCore})",
                        lastLatencyMs = 2L,
                        lastTestedAt = System.currentTimeMillis()
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = SecurityAuditor.redactSecrets(e.localizedMessage ?: "Test failed")
            slot.copy(
                lastTestStatus = "FAILED",
                lastTestMessage = msg,
                lastLatencyMs = System.currentTimeMillis() - start,
                lastTestedAt = System.currentTimeMillis()
            )
        }

        db.providerSlotDao().upsertSlot(updated)
        db.systemRecordsDao().insertAuditLog(
            AuditLogEntity(
                category = "API_TEST",
                action = "Test ${updated.label}",
                outcome = updated.lastTestStatus,
                details = updated.lastTestMessage
            )
        )
        return updated
    }

    suspend fun discoverModelsForProvider(type: ProviderType, slotId: String): Result<List<DiscoveredModel>> {
        return when (type) {
            ProviderType.GEMINI_DIRECT -> {
                val key = vault.readSecret(slotId)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: resolvePrimaryGeminiSlotAndKey().second
                if (key.isNullOrBlank()) {
                    Result.failure(IllegalStateException("Gemini API key missing"))
                } else {
                    network.modelRegistry.fetchAllDiscoveredGeminiModels(key)
                }
            }
            ProviderType.GROQ -> {
                val key = vault.readSecret(slotId)
                if (key.isNullOrBlank()) {
                    Result.failure(IllegalStateException("Add a Groq API key in this slot first to query live Groq models."))
                } else {
                    network.fetchGroqModels(key)
                }
            }
            ProviderType.OPENROUTER -> {
                val key = vault.readSecret(slotId)
                network.fetchOpenRouterModels(apiKey = key, freeOnly = true)
            }
            else -> Result.success(emptyList())
        }
    }

    private suspend fun markSlotResult(slot: ProviderSlotEntity, status: String, message: String, latencyMs: Long) {
        db.providerSlotDao().upsertSlot(
            slot.copy(
                lastTestStatus = status,
                lastTestMessage = message.take(220),
                lastLatencyMs = latencyMs,
                lastTestedAt = System.currentTimeMillis()
            )
        )
    }

    private fun buildTeenaSystemInstruction(lang: String, memories: List<MemoryFactEntity>): String {
        val memSnippet = if (memories.isEmpty()) "None saved yet." else {
            memories.take(10).joinToString("\n") { "- [${it.category}] ${it.factKey}: ${it.factValue}" }
        }
        return """
            You are TEENA AI, a real, capable, honest Android autonomous multi-agent AI assistant.
            Respond naturally, clearly, and helpfully in the user's language (detected: $lang — seamlessly support English, Urdu script, and Roman Urdu).
            Never pretend an Android action, API call, or build succeeded if it was not executed or verified.
            User long-term memory facts:
            $memSnippet
        """.trimIndent()
    }
}
