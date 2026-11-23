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
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
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
        private const val SLOT_GEMINI_BUILTIN = "slot_gemini_ai_logic"
    }

    private val defaultGeminiModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    suspend fun ensureDefaultProviderSlots() {
        val existing = db.providerSlotDao().getSlotsOnce()
        if (existing.isEmpty()) {
            db.providerSlotDao().upsertSlot(
                ProviderSlotEntity(
                    slotId = SLOT_GEMINI_BUILTIN,
                    providerType = ProviderType.GEMINI_AI_LOGIC.name,
                    label = "Gemini Built-In (Firebase AI Logic)",
                    selectedModel = "gemini-3.8-flash",
                    isEnabled = true,
                    isPrimary = true,
                    lastTestStatus = "READY",
                    lastTestMessage = "App Check configured; ready for live test."
                )
            )
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
                    ProviderType.GEMINI_AI_LOGIC -> {
                        val model = Firebase.ai.generativeModel(
                            modelName = slot.selectedModel.ifBlank { "gemini-3.8-flash" },
                            systemInstruction = content { text(systemPrompt) }
                        )
                        val sb = StringBuilder()
                        withTimeout(60_000L) {
                            if (attachedImage != null) {
                                val resp = model.generateContent(
                                    content {
                                        image(attachedImage)
                                        text(userPrompt)
                                    }
                                )
                                val text = resp.text.orEmpty()
                                sb.append(text)
                                emit(AiStreamChunk.Delta(text, slot.label, slot.selectedModel))
                            } else {
                                val promptWithHistory = buildHistoryWrappedPrompt(recentHistory, userPrompt)
                                model.generateContentStream(promptWithHistory).collect { chunk ->
                                    val part = chunk.text.orEmpty()
                                    if (part.isNotEmpty()) {
                                        sb.append(part)
                                        emit(AiStreamChunk.Delta(sb.toString(), slot.label, slot.selectedModel))
                                    }
                                }
                            }
                        }
                        val finalText = sb.toString().trim()
                        if (finalText.isBlank()) throw IllegalStateException("Gemini returned empty response.")
                        val latency = System.currentTimeMillis() - start
                        markSlotResult(slot, "ACTIVE", "Responded in ${latency}ms", latency)
                        emit(
                            AiStreamChunk.Completed(
                                AiInferenceOutput(
                                    text = finalText,
                                    providerUsed = slot.label,
                                    modelUsed = slot.selectedModel,
                                    detectedLanguage = lang,
                                    latencyMs = latency,
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

                    ProviderType.GEMINI_DIRECT -> {
                        val apiKey = vault.readSecret(slot.slotId)
                        if (apiKey.isNullOrBlank()) {
                            failureNotes.add("${slot.label}: missing API key")
                            continue
                        }
                        val res = network.chatGeminiDirectRest(
                            apiKey = apiKey,
                            model = slot.selectedModel.ifBlank { "gemini-2.5-flash" },
                            systemInstruction = systemPrompt,
                            userPrompt = buildHistoryWrappedPrompt(recentHistory, userPrompt)
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
                        ProviderType.GEMINI_AI_LOGIC -> {
                            val response = withTimeout(45_000L) {
                                defaultGeminiModel.generateContent(prompt)
                            }
                            val text = response.text?.trim().orEmpty()
                            if (text.isNotBlank()) return@runCatching text
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
                        ProviderType.GEMINI_DIRECT -> {
                            val key = vault.readSecret(slot.slotId) ?: continue
                            val res = network.chatGeminiDirectRest(key, slot.selectedModel, "", prompt)
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
                ProviderType.GEMINI_AI_LOGIC -> {
                    val model = Firebase.ai.generativeModel(slot.selectedModel.ifBlank { "gemini-3.8-flash" })
                    val resp = withTimeout(30_000L) {
                        model.generateContent("Reply with strictly: OK")
                    }
                    val txt = resp.text?.trim().orEmpty()
                    if (txt.isBlank()) throw IllegalStateException("Empty response")
                    val lat = System.currentTimeMillis() - start
                    slot.copy(
                        lastTestStatus = "ACTIVE",
                        lastTestMessage = "Verified via Firebase AI Logic (${lat}ms): $txt",
                        lastLatencyMs = lat,
                        lastTestedAt = System.currentTimeMillis()
                    )
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

                ProviderType.GEMINI_DIRECT -> {
                    val key = vault.readSecret(slot.slotId)
                        ?: throw IllegalStateException("API key is empty in Keystore vault.")
                    val res = network.chatGeminiDirectRest(
                        apiKey = key,
                        model = slot.selectedModel,
                        systemInstruction = "",
                        userPrompt = "Reply with OK"
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
            ProviderType.GEMINI_AI_LOGIC -> Result.success(
                listOf(
                    DiscoveredModel("gemini-3.8-flash", "Gemini 3.8 Flash (Default)", "GEMINI", 1048576, true, "App Check"),
                    DiscoveredModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite (Fast)", "GEMINI", 1048576, true, "App Check"),
                    DiscoveredModel("gemini-4-argon", "Gemini 4 Argon (Deep Reasoning)", "GEMINI", 1048576, false, "Blaze"),
                    DiscoveredModel("gemini-nano-banana-2.1", "Gemini Nano Banana 2.1 (Image)", "GEMINI", 32768, false, "Blaze")
                )
            )
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

    private fun buildHistoryWrappedPrompt(history: List<Pair<String, String>>, userPrompt: String): String {
        if (history.isEmpty()) return userPrompt
        val sb = StringBuilder("Recent conversation context:\n")
        history.takeLast(6).forEach { (role, msg) ->
            sb.appendLine("${role.uppercase()}: ${msg.take(400)}")
        }
        sb.appendLine("\nCURRENT USER MESSAGE:\n$userPrompt")
        return sb.toString()
    }
}
