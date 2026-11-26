package com.example.ai

import com.example.core.security.EmergencyStopController
import com.example.core.security.KeystoreSecretVault
import com.example.core.security.SecurityAuditor
import com.example.core.security.UntrustedContentSanitizer
import com.example.data.local.AuditLogEntity
import com.example.data.local.ExternalAdvisorEntity
import com.example.data.local.ExternalCollabMessageEntity
import com.example.data.local.FeatureStatus
import com.example.data.local.ProviderType
import com.example.data.local.TeenaDatabase
import com.example.data.remote.ModelRegistry
import com.example.data.remote.TeenaNetworkClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

data class CapabilityGapReport(
    val gapId: String,
    val title: String,
    val reason: String,
    val suggestedTechnicalQuestion: String
)

data class AdvisorConsultationSummary(
    val sessionId: String,
    val topic: String,
    val advisorCountQueried: Int,
    val advisorCountSucceeded: Int,
    val comparisonNotes: String,
    val messages: List<ExternalCollabMessageEntity>
)

data class AdvisorQuotaStatus(
    val usedLastHour: Int,
    val limitPerHour: Int
) {
    val remaining: Int get() = (limitPerHour - usedLastHour).coerceAtLeast(0)
}

/**
 * Coordinates real API communication with external AI advisors:
 * - Distinct models/providers discovered via ModelRegistry & API Manager (Gemini, Groq, OpenRouter, Custom OpenAI)
 * - User-configured Model Context Protocol (MCP) JSON-RPC 2.0 servers
 * - User-configured Agent-to-Agent (A2A) JSON-RPC agents
 *
 * Security & privacy guarantee:
 * - Never sends API keys, personal memories, contacts, call logs, or screen contents to advisors.
 * - Advisor outputs are wrapped in <untrusted_data> and treated strictly as suggestions, never commands.
 * - Gated on user "External AI Calls" switch + EmergencyStopController + configurable hourly rate limit.
 */
class ExternalAgentHub(
    private val db: TeenaDatabase,
    private val vault: KeystoreSecretVault,
    private val network: TeenaNetworkClients,
    private val emergencyStopController: EmergencyStopController
) {

    suspend fun getAdvisorQuotaStatus(limitPerHour: Int = 12): AdvisorQuotaStatus {
        val safeLimit = limitPerHour.coerceIn(1, 200)
        val since = System.currentTimeMillis() - 3_600_000L
        val used = db.externalCollabDao().countMessagesSince(since)
        return AdvisorQuotaStatus(usedLastHour = used, limitPerHour = safeLimit)
    }

    suspend fun syncProviderModelAdvisors() {
        val slots = db.providerSlotDao().getSlotsOnce()
        val existing = db.externalAdvisorDao().getAdvisorsOnce().associateBy { it.advisorId }
        val synced = mutableListOf<ExternalAdvisorEntity>()

        for (slot in slots) {
            val type = runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull() ?: continue
            if (type == ProviderType.OFFLINE_LOCAL) continue
            val hasKey = !vault.readSecret(slot.slotId).isNullOrBlank()

            if (type == ProviderType.GEMINI_DIRECT) {
                val primaryId = "adv_slot_${slot.slotId}"
                val prev = existing[primaryId]
                val status = when {
                    !hasKey -> FeatureStatus.BLOCKED.name
                    slot.lastTestStatus == "ACTIVE" -> FeatureStatus.ACTIVE.name
                    slot.lastTestStatus == "FAILED" -> FeatureStatus.FAILED.name
                    else -> FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name
                }
                synced.add(
                    ExternalAdvisorEntity(
                        advisorId = primaryId,
                        name = "${slot.label} Advisor",
                        sourceType = "PROVIDER_MODEL",
                        providerType = type.name,
                        slotId = slot.slotId,
                        modelId = slot.selectedModel,
                        status = status,
                        lastResponseTimeMs = prev?.lastResponseTimeMs ?: slot.lastLatencyMs,
                        lastStatusMessage = if (!hasKey) "API key missing" else prev?.lastStatusMessage ?: slot.lastTestMessage,
                        capabilities = "Architecture, Kotlin, Android, Security review",
                        isEnabled = prev?.isEnabled ?: slot.isEnabled,
                        lastCheckedAt = prev?.lastCheckedAt ?: slot.lastTestedAt
                    )
                )

                // Expose additional non-blacklisted Gemini models discovered for this key as peer model advisors
                val apiKey = vault.readSecret(slot.slotId)?.trim()
                if (!apiKey.isNullOrEmpty()) {
                    val ranked = network.modelRegistry.getCachedModelsForKey(apiKey)
                        .filter { it.supportsGenerateContent && it.probeStatus != "BLACKLISTED" }
                        .filter { it.modelId != slot.selectedModel && !it.modelId.contains("tts", true) }
                        .take(3)
                    ranked.forEach { m ->
                        val peerId = "adv_gem_${slot.slotId}_${m.modelId.replace(Regex("[^a-zA-Z0-9_]"), "_")}"
                        val prevPeer = existing[peerId]
                        synced.add(
                            ExternalAdvisorEntity(
                                advisorId = peerId,
                                name = "Gemini (${m.displayName.ifBlank { m.modelId }})",
                                sourceType = "PROVIDER_MODEL",
                                providerType = ProviderType.GEMINI_DIRECT.name,
                                slotId = slot.slotId,
                                modelId = m.modelId,
                                status = if (m.probeStatus == "WORKING") FeatureStatus.ACTIVE.name else FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
                                lastResponseTimeMs = if (m.lastLatencyMs < Long.MAX_VALUE) m.lastLatencyMs else (prevPeer?.lastResponseTimeMs ?: 0L),
                                lastStatusMessage = if (m.probeStatus == "WORKING") "Probed working (${m.lastLatencyMs}ms)" else "Discovered via ModelRegistry",
                                capabilities = "Peer Gemini model advisor (${m.modelId})",
                                isEnabled = prevPeer?.isEnabled ?: true,
                                lastCheckedAt = prevPeer?.lastCheckedAt ?: m.updatedAt
                            )
                        )
                    }
                }
            } else {
                val advId = "adv_slot_${slot.slotId}"
                val prev = existing[advId]
                val status = when {
                    !hasKey && type != ProviderType.CUSTOM_OPENAI -> FeatureStatus.BLOCKED.name
                    slot.lastTestStatus == "ACTIVE" -> FeatureStatus.ACTIVE.name
                    slot.lastTestStatus == "FAILED" -> FeatureStatus.FAILED.name
                    else -> FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name
                }
                synced.add(
                    ExternalAdvisorEntity(
                        advisorId = advId,
                        name = "${slot.label} Advisor",
                        sourceType = "PROVIDER_MODEL",
                        providerType = type.name,
                        slotId = slot.slotId,
                        modelId = slot.selectedModel,
                        endpointUrl = slot.baseUrl,
                        status = status,
                        lastResponseTimeMs = prev?.lastResponseTimeMs ?: slot.lastLatencyMs,
                        lastStatusMessage = if (!hasKey && type != ProviderType.CUSTOM_OPENAI) "API key missing" else prev?.lastStatusMessage ?: slot.lastTestMessage,
                        capabilities = "Cross-provider reasoning (${type.displayName})",
                        isEnabled = prev?.isEnabled ?: slot.isEnabled,
                        lastCheckedAt = prev?.lastCheckedAt ?: slot.lastTestedAt
                    )
                )
            }
        }

        // Remove stale auto-synced provider advisors no longer present; preserve MCP/A2A advisors
        existing.values.filter {
            it.sourceType == "PROVIDER_MODEL" && synced.none { s -> s.advisorId == it.advisorId }
        }.forEach { stale ->
            db.externalAdvisorDao().deleteAdvisor(stale.advisorId)
        }

        if (synced.isNotEmpty()) {
            db.externalAdvisorDao().upsertAdvisors(synced)
        }
    }

    suspend fun addCustomExternalAdvisor(
        name: String,
        sourceType: String, // "MCP_SERVER" or "A2A_AGENT"
        endpointUrl: String,
        bearerToken: String = ""
    ): Result<ExternalAdvisorEntity> {
        return runCatching {
            val cleanUrl = endpointUrl.trim()
            if (cleanUrl.isBlank()) throw IllegalArgumentException("Endpoint URL is required.")
            val id = "adv_${sourceType.lowercase()}_${System.currentTimeMillis() % 100000}"
            if (bearerToken.isNotBlank()) {
                vault.saveSecret("advisor_token_$id", bearerToken.trim())
            }
            val initial = ExternalAdvisorEntity(
                advisorId = id,
                name = name.ifBlank { cleanUrl },
                sourceType = sourceType,
                providerType = sourceType,
                endpointUrl = cleanUrl,
                status = FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
                lastStatusMessage = "Saved; tap Probe to verify $sourceType handshake",
                capabilities = if (sourceType == "MCP_SERVER") "MCP JSON-RPC 2.0 tools" else "A2A /.well-known/agent.json",
                isEnabled = true,
                lastCheckedAt = System.currentTimeMillis()
            )
            db.externalAdvisorDao().upsertAdvisor(initial)
            probeAdvisor(id).getOrDefault(initial)
        }
    }

    suspend fun deleteAdvisor(advisorId: String) {
        vault.deleteSecret("advisor_token_$advisorId")
        db.externalAdvisorDao().deleteAdvisor(advisorId)
    }

    suspend fun toggleAdvisorEnabled(advisorId: String, enabled: Boolean) {
        val current = db.externalAdvisorDao().getAdvisorById(advisorId) ?: return
        db.externalAdvisorDao().upsertAdvisor(current.copy(isEnabled = enabled))
    }

    suspend fun probeAdvisor(advisorId: String): Result<ExternalAdvisorEntity> {
        val advisor = db.externalAdvisorDao().getAdvisorById(advisorId)
            ?: return Result.failure(IllegalArgumentException("Advisor not found"))

        return runCatching {
            emergencyStopController.assertExternalAiAdvisorAllowed("probe advisor ${advisor.name}")
            val token = vault.readSecret("advisor_token_${advisor.advisorId}")
            val updated = when (advisor.sourceType) {
                "MCP_SERVER" -> {
                    val probe = network.probeMcpServer(advisor.endpointUrl, token).getOrThrow()
                    advisor.copy(
                        name = advisor.name.ifBlank { probe.serverName },
                        status = FeatureStatus.ACTIVE.name,
                        lastResponseTimeMs = probe.latencyMs,
                        lastStatusMessage = "MCP ready (${probe.tools.size} tool(s): ${probe.tools.take(4).joinToString()})",
                        capabilities = probe.tools.joinToString(", ").ifBlank { "tools/call" },
                        lastCheckedAt = System.currentTimeMillis()
                    )
                }
                "A2A_AGENT" -> {
                    val card = network.fetchA2aAgentCard(advisor.endpointUrl, token).getOrThrow()
                    advisor.copy(
                        name = card.name.ifBlank { advisor.name },
                        status = FeatureStatus.ACTIVE.name,
                        lastResponseTimeMs = card.latencyMs,
                        lastStatusMessage = "A2A card verified (${card.skills.size} skill(s))",
                        capabilities = card.skills.joinToString(", ").ifBlank { card.description },
                        lastCheckedAt = System.currentTimeMillis()
                    )
                }
                else -> {
                    val reply = querySingleAdvisorRaw(advisor, "Reply with the single word OK.")
                    advisor.copy(
                        modelId = reply.first.ifBlank { advisor.modelId },
                        status = FeatureStatus.ACTIVE.name,
                        lastResponseTimeMs = reply.third,
                        lastStatusMessage = "Live response in ${reply.third}ms",
                        lastCheckedAt = System.currentTimeMillis()
                    )
                }
            }
            db.externalAdvisorDao().upsertAdvisor(updated)
            updated
        }.onFailure { err ->
            val safeErr = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Probe failed")
            db.externalAdvisorDao().upsertAdvisor(
                advisor.copy(
                    status = FeatureStatus.FAILED.name,
                    lastStatusMessage = safeErr,
                    lastCheckedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Detects capability gaps from ALL 4 real sources:
     * 1. System Health Check (feature_verifications)
     * 2. Failed Tasks (agent_states, agent_collaborations, marketplace_tools)
     * 3. User Feature Requests & Unresolved Chat Prompts (messages, strictly PII-sanitized)
     * 4. External Advisor Suggestions (external_collab_messages)
     */
    suspend fun detectCapabilityGaps(): List<CapabilityGapReport> {
        val gaps = mutableListOf<CapabilityGapReport>()
        val verifications = db.featureVerificationDao().getAllOnce()
        val agents = db.agentDao().getAgentsOnce()
        val failedCollabs = db.agentDao().getCollaborationsOnce().filter { it.outcomeStatus == "FAILED" }
        val allTools = db.marketplaceToolDao().getToolsOnce()
        val blockedOrFailedTools = allTools
            .filter { it.activationStatus == FeatureStatus.FAILED.name || it.activationStatus == FeatureStatus.BLOCKED.name }
        val recentMessages = db.conversationDao().getAllMessagesOnce()
        val recentAdvisorReplies = db.externalCollabDao().getMessagesOnce().filter { it.succeeded }

        // Source 1: Health Check
        verifications.filter {
            !it.runtimeTestPassed || it.status == FeatureStatus.PARTIAL.name || it.status == FeatureStatus.BLOCKED.name
        }.take(3).forEach { fv ->
            gaps.add(
                CapabilityGapReport(
                    gapId = "gap_feat_${fv.featureId}",
                    title = "Health Check Gap: ${fv.featureId}",
                    reason = "[Health Check] ${fv.status}: ${fv.lastTestSummary}",
                    suggestedTechnicalQuestion = "For an Android Kotlin + Jetpack Compose AI assistant, what is the cleanest Play-Policy-compliant architecture, library, and runtime test approach to make '${fv.featureId}' reliably active (${fv.lastTestSummary})?"
                )
            )
        }

        // Source 2: Failed Tasks
        agents.filter { it.tasksFailed > 0 }.take(2).forEach { agent ->
            gaps.add(
                CapabilityGapReport(
                    gapId = "gap_agent_${agent.agentId}",
                    title = "Failed Agent Tasks: ${agent.displayName}",
                    reason = "[Failed Task] ${agent.tasksFailed} failed task(s); health=${agent.healthStatus} (${agent.healthReason})",
                    suggestedTechnicalQuestion = "An Android agent handling '${agent.specialty}' has failed tasks (${agent.healthReason}). Recommend the best library, fallback design, and validation checks to harden it."
                )
            )
        }

        failedCollabs.take(2).forEach { collab ->
            gaps.add(
                CapabilityGapReport(
                    gapId = "gap_collab_${collab.id}",
                    title = "Failed Task Handoff (${collab.fromAgentId})",
                    reason = "[Failed Task] ${collab.protocolPayload.take(180)}",
                    suggestedTechnicalQuestion = "How can an Android assistant safely resolve this failed sub-task without unsafe dynamic code loading: ${collab.protocolPayload.take(200)}?"
                )
            )
        }

        blockedOrFailedTools.take(2).forEach { tool ->
            gaps.add(
                CapabilityGapReport(
                    gapId = "gap_tool_${tool.toolId}",
                    title = "Failed/Blocked Tool: ${tool.name}",
                    reason = "[Failed Tool] ${tool.activationStatus}: ${tool.testResult}",
                    suggestedTechnicalQuestion = "How should tool '${tool.name}' (${tool.category}, ${tool.licenseSpdx}) be redesigned to pass security scanning and deterministic runtime verification? Current failure: ${tool.testResult}"
                )
            )
        }

        // Source 3: User Requests (strictly sanitized — no keys, emails, phone numbers, or personal names sent externally)
        val requestKeywords = listOf(
            "feature", "tool", "bana", "banao", "add ", "support", "formatter", "converter",
            "extractor", "scanner", "integrate", "chahiye", "nayi", "naya", "karo"
        )
        recentMessages
            .filter { it.role == "user" }
            .filter { m ->
                val lower = m.content.lowercase()
                m.content.length in 10..260 && requestKeywords.any { lower.contains(it) }
            }
            .take(2)
            .forEach { msg ->
                val cleanSnippet = SecurityAuditor.sanitizeTechnicalQuestionForAdvisor(msg.content).take(120)
                if (cleanSnippet.isNotBlank()) {
                    gaps.add(
                        CapabilityGapReport(
                            gapId = "gap_user_req_${msg.id}",
                            title = "User Request: ${cleanSnippet.take(42)}",
                            reason = "[User Request] User asked for capability: $cleanSnippet",
                            suggestedTechnicalQuestion = "A mobile assistant user asked for a capability like: '$cleanSnippet'. Suggest a safe declarative JSON spec or MIT/Apache-2.0 Kotlin library design complying with Android Play Policy."
                        )
                    )
                }
            }

        // Source 4: Advisors' Suggestions from ExternalCollabMessages not yet converted to a tool
        recentAdvisorReplies
            .filter { reply ->
                allTools.none { t -> t.proposedBy.contains(reply.advisorName, ignoreCase = true) && t.name.contains(reply.topic.take(12), ignoreCase = true) }
            }
            .take(2)
            .forEach { reply ->
                val cleanExcerpt = UntrustedContentSanitizer.wrapAsUntrustedData("advisor:${reply.advisorName}", reply.rawResponse, 200)
                    .lineSequence()
                    .filterNot { it.startsWith("<untrusted_data") || it.startsWith("</untrusted_data") }
                    .joinToString(" ")
                    .trim()
                    .take(150)
                gaps.add(
                    CapabilityGapReport(
                        gapId = "gap_advisor_${reply.id}",
                        title = "Advisor Suggestion: ${reply.topic.take(40)}",
                        reason = "[Advisor ${reply.advisorName} / ${reply.advisorModel}] $cleanExcerpt",
                        suggestedTechnicalQuestion = "Build or refine a declarative Marketplace tool addressing advisor suggestion on '${reply.topic}': $cleanExcerpt"
                    )
                )
            }

        if (gaps.isEmpty()) {
            gaps.add(
                CapabilityGapReport(
                    gapId = "gap_research_evolution",
                    title = "Self-Improvement Capability Expansion",
                    reason = "All checked subsystems healthy; evaluate new Android/AI capabilities for marketplace proposals.",
                    suggestedTechnicalQuestion = "What high-value, Play-Policy-compliant declarative tools or open-source Android Kotlin libraries (MIT/Apache-2.0) should an autonomous assistant add next?"
                )
            )
        }
        return gaps
    }

    /**
     * Queries at least 2 distinct external advisors with a privacy-sanitized technical question,
     * compares their recommendations, enforces hourly rate limits, and stores every message & comparison in Room.
     */
    suspend fun consultExternalAdvisors(
        topic: String,
        rawTechnicalQuestion: String,
        gapReason: String = "Manual or capability-gap consultation",
        minAdvisorsToAsk: Int = 2,
        hourlyLimit: Int = 12
    ): Result<AdvisorConsultationSummary> {
        return runCatching {
            emergencyStopController.assertExternalAiAdvisorAllowed("external advisor consultation")

            val quota = getAdvisorQuotaStatus(hourlyLimit)
            if (quota.usedLastHour >= quota.limitPerHour) {
                db.systemRecordsDao().insertAuditLog(
                    AuditLogEntity(
                        category = "RATE_LIMIT",
                        action = "Advisor consultation throttled ($topic)",
                        outcome = "BLOCKED",
                        details = "Used ${quota.usedLastHour}/${quota.limitPerHour} advisor calls in last hour."
                    )
                )
                throw IllegalStateException(
                    "Advisor hourly rate limit poora ho gaya (${quota.usedLastHour}/${quota.limitPerHour} calls/hr). Settings mein limit badhain ya thori dair intezar karein."
                )
            }

            syncProviderModelAdvisors()

            // Strictly sanitize technical question — never leaks keys, memories, contacts, or screen text
            val safeQuestion = SecurityAuditor.sanitizeTechnicalQuestionForAdvisor(rawTechnicalQuestion)
            val systemGuide = """
                You are an external AI technical advisor collaborating with Teena AI (an Android Kotlin + Compose assistant).
                Provide concise, actionable technical advice (recommended open-source library/API + SPDX license, declarative or Kotlin design, and security/Play-Policy caveats).
                Keep your recommendation under 220 words.
            """.trimIndent()

            val allEnabled = db.externalAdvisorDao().getAdvisorsOnce()
                .filter { it.isEnabled && it.status != FeatureStatus.BLOCKED.name }

            val maxAllowedNow = (quota.limitPerHour - quota.usedLastHour).coerceAtLeast(1)
            val askCount = minAdvisorsToAsk.coerceAtLeast(2).coerceAtMost(maxAllowedNow.coerceAtLeast(2))
            if (quota.usedLastHour + 2 > quota.limitPerHour && allEnabled.size >= 2) {
                throw IllegalStateException(
                    "Hourly rate limit bacha hua (${quota.remaining} calls) kam az kam 2 advisors ke liye kaafi nahi (${quota.usedLastHour}/${quota.limitPerHour})."
                )
            }

            val candidates = selectDiverseAdvisors(allEnabled, askCount)
            if (candidates.isEmpty()) {
                throw IllegalStateException("Koi configured external advisor tayyar nahi (API Manager mein key ya MCP/A2A URL add karein).")
            }

            val sessionId = "collab_${UUID.randomUUID().toString().take(8)}"
            val queriedResults = coroutineScope {
                candidates.map { advisor ->
                    async {
                        val started = System.currentTimeMillis()
                        try {
                            val (modelUsed, replyText, latencyMs) = querySingleAdvisorRaw(
                                advisor = advisor,
                                technicalQuestion = safeQuestion,
                                systemPrompt = systemGuide
                            )
                            val updatedAdvisor = advisor.copy(
                                modelId = modelUsed.ifBlank { advisor.modelId },
                                status = FeatureStatus.ACTIVE.name,
                                lastResponseTimeMs = latencyMs,
                                lastStatusMessage = "Responded in ${latencyMs}ms",
                                lastCheckedAt = System.currentTimeMillis()
                            )
                            db.externalAdvisorDao().upsertAdvisor(updatedAdvisor)

                            val isolatedText = UntrustedContentSanitizer.wrapAsUntrustedData(
                                source = "advisor:${advisor.name}",
                                rawContent = replyText,
                                maxChars = 2500
                            )
                            ExternalCollabMessageEntity(
                                sessionId = sessionId,
                                topic = topic,
                                detectedGapReason = gapReason,
                                questionSent = safeQuestion,
                                advisorId = advisor.advisorId,
                                advisorName = advisor.name,
                                advisorModel = modelUsed.ifBlank { advisor.modelId.ifBlank { advisor.sourceType } },
                                protocolUsed = protocolFor(advisor),
                                rawResponse = isolatedText,
                                responseTimeMs = latencyMs,
                                succeeded = true
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val lat = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                            val safeErr = SecurityAuditor.redactSecrets(e.localizedMessage ?: "Advisor request failed")
                            db.externalAdvisorDao().upsertAdvisor(
                                advisor.copy(
                                    status = FeatureStatus.FAILED.name,
                                    lastResponseTimeMs = lat,
                                    lastStatusMessage = safeErr,
                                    lastCheckedAt = System.currentTimeMillis()
                                )
                            )
                            ExternalCollabMessageEntity(
                                sessionId = sessionId,
                                topic = topic,
                                detectedGapReason = gapReason,
                                questionSent = safeQuestion,
                                advisorId = advisor.advisorId,
                                advisorName = advisor.name,
                                advisorModel = advisor.modelId.ifBlank { advisor.sourceType },
                                protocolUsed = protocolFor(advisor),
                                rawResponse = "",
                                responseTimeMs = lat,
                                succeeded = false,
                                errorMessage = safeErr
                            )
                        }
                    }
                }.awaitAll()
            }

            val comparison = buildAdvisorComparisonReport(queriedResults)
            val savedMessages = queriedResults.map { msg ->
                val withComparison = msg.copy(comparisonNotes = comparison)
                val insertedId = db.externalCollabDao().insertMessage(withComparison)
                withComparison.copy(id = insertedId)
            }

            val succeededCount = savedMessages.count { it.succeeded }
            db.systemRecordsDao().insertAuditLog(
                AuditLogEntity(
                    category = "EXTERNAL_COLLAB",
                    action = "Consulted ${savedMessages.size} advisor(s) on '$topic'",
                    outcome = if (succeededCount >= 2) "SUCCESS" else if (succeededCount == 1) "PARTIAL" else "FAILED",
                    details = comparison.take(240)
                )
            )

            AdvisorConsultationSummary(
                sessionId = sessionId,
                topic = topic,
                advisorCountQueried = savedMessages.size,
                advisorCountSucceeded = succeededCount,
                comparisonNotes = comparison,
                messages = savedMessages
            )
        }
    }

    private fun selectDiverseAdvisors(
        all: List<ExternalAdvisorEntity>,
        targetCount: Int
    ): List<ExternalAdvisorEntity> {
        if (all.size <= targetCount) return all
        val picked = mutableListOf<ExternalAdvisorEntity>()
        val usedProviders = mutableSetOf<String>()
        // Pass 1: one per distinct providerType / sourceType for genuine cross-system diversity
        for (adv in all) {
            val key = "${adv.sourceType}:${adv.providerType}"
            if (key !in usedProviders) {
                picked.add(adv)
                usedProviders.add(key)
            }
            if (picked.size >= targetCount) return picked
        }
        // Pass 2: add additional distinct models
        for (adv in all) {
            if (picked.none { it.advisorId == adv.advisorId }) {
                picked.add(adv)
            }
            if (picked.size >= targetCount) break
        }
        return picked
    }

    private suspend fun querySingleAdvisorRaw(
        advisor: ExternalAdvisorEntity,
        technicalQuestion: String,
        systemPrompt: String = ""
    ): Triple<String, String, Long> {
        val start = System.currentTimeMillis()
        when (advisor.sourceType) {
            "MCP_SERVER" -> {
                val token = vault.readSecret("advisor_token_${advisor.advisorId}")
                val toolToCall = advisor.capabilities.split(",").firstOrNull()?.trim().orEmpty().ifBlank { "advise" }
                val res = network.callMcpAdvisor(
                    endpointUrl = advisor.endpointUrl,
                    authToken = token,
                    toolName = toolToCall,
                    question = technicalQuestion
                ).getOrThrow()
                return Triple(res.modelUsed, res.text, res.latencyMs)
            }
            "A2A_AGENT" -> {
                val token = vault.readSecret("advisor_token_${advisor.advisorId}")
                val card = runCatching { network.fetchA2aAgentCard(advisor.endpointUrl, token).getOrNull() }.getOrNull()
                val rpcTarget = card?.rpcUrl ?: advisor.endpointUrl
                val res = network.sendA2aMessage(
                    rpcUrl = rpcTarget,
                    authToken = token,
                    messageText = if (systemPrompt.isNotBlank()) "$systemPrompt\n\nQuestion: $technicalQuestion" else technicalQuestion
                ).getOrThrow()
                return Triple(res.modelUsed, res.text, res.latencyMs)
            }
            else -> {
                val type = ProviderType.valueOf(advisor.providerType)
                val key = vault.readSecret(advisor.slotId)?.trim().orEmpty()
                if (type == ProviderType.GEMINI_DIRECT) {
                    if (key.isEmpty()) throw IllegalStateException("Gemini API key missing for ${advisor.name}")
                    val modelId = advisor.modelId.ifBlank {
                        val ranked = network.modelRegistry.getRankedChatCandidates(key, forceRefresh = false).getOrThrow()
                        ranked.firstOrNull()?.shortId ?: throw IllegalStateException("No ranked Gemini model available")
                    }
                    val res = network.chatGeminiDirectRest(
                        apiKey = key,
                        model = modelId,
                        systemInstruction = systemPrompt,
                        userPrompt = technicalQuestion
                    ).getOrThrow()
                    return Triple(res.modelUsed, res.text, res.latencyMs)
                } else {
                    if (key.isEmpty() && type != ProviderType.CUSTOM_OPENAI) {
                        throw IllegalStateException("Missing API key for ${advisor.name}")
                    }
                    val base = when (type) {
                        ProviderType.GROQ -> "https://api.groq.com/openai/v1"
                        ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
                        else -> advisor.endpointUrl.ifBlank { throw IllegalStateException("Missing endpoint URL") }
                    }
                    val msgs = buildList {
                        if (systemPrompt.isNotBlank()) add("system" to systemPrompt)
                        add("user" to technicalQuestion)
                    }
                    val headers = if (type == ProviderType.OPENROUTER) {
                        mapOf("HTTP-Referer" to "https://teena-ai.local", "X-Title" to "Teena AI Collaboration")
                    } else emptyMap()
                    val res = network.chatOpenAiCompatible(
                        baseUrl = base,
                        apiKey = key,
                        model = advisor.modelId,
                        messages = msgs,
                        extraHeaders = headers
                    ).getOrThrow()
                    return Triple(res.modelUsed, res.text, res.latencyMs)
                }
            }
        }
    }

    private fun protocolFor(advisor: ExternalAdvisorEntity): String = when (advisor.sourceType) {
        "MCP_SERVER" -> "MCP"
        "A2A_AGENT" -> "A2A"
        else -> if (advisor.providerType == ProviderType.GEMINI_DIRECT.name) "GEMINI_REST" else "OPENAI_COMPAT"
    }

    private fun buildAdvisorComparisonReport(results: List<ExternalCollabMessageEntity>): String {
        val succeeded = results.filter { it.succeeded }
        val failed = results.filter { !it.succeeded }
        return buildString {
            appendLine("Advisors queried: ${results.size} | Succeeded: ${succeeded.size} | Failed: ${failed.size}")
            if (succeeded.size < 2) {
                appendLine("Note: Fewer than 2 distinct external advisors responded successfully. Add another provider key, Gemini model, MCP server, or A2A agent for multi-advisor cross-verification.")
            }
            if (succeeded.isNotEmpty()) {
                val fastest = succeeded.minByOrNull { it.responseTimeMs }
                appendLine("Fastest advisor: ${fastest?.advisorName} (${fastest?.advisorModel}) in ${fastest?.responseTimeMs}ms.")
                val flagged = succeeded.filter { UntrustedContentSanitizer.containsSuspiciousDirective(it.rawResponse) }
                if (flagged.isNotEmpty()) {
                    appendLine("SECURITY NOTICE: Suspicious directive text neutralized inside <untrusted_data> from: ${flagged.joinToString { it.advisorName }}.")
                }
                appendLine("Treated strictly as advisory suggestions (never auto-executed).")
            }
            if (failed.isNotEmpty()) {
                appendLine("Failures: ${failed.joinToString(" | ") { "${it.advisorName}: ${it.errorMessage}" }}")
            }
        }.trim()
    }
}
