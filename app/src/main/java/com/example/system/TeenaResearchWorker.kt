package com.example.system

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.ai.ToolCreationPipelineEngine
import com.example.core.TeenaServiceContainer
import com.example.core.security.SecurityAuditor
import com.example.data.local.AuditLogEntity
import com.example.data.local.MarketplaceToolEntity
import com.example.data.local.ResearchEntryEntity
import com.example.data.local.SelfImprovementEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ResearchCycleOutcome(
    val modelsRefreshed: Int,
    val proposalsCreated: Int,
    val summary: String
)

/**
 * WorkManager background worker for scheduled self-improvement research:
 * - Researches new Gemini models via ModelRegistry, GitHub Android tools, Hugging Face models,
 *   and latest Android/Gemini updates via web search.
 * - Asks ExternalAgentHub advisors to evaluate discovered candidates (when External AI Calls allowed).
 * - Creates Marketplace proposals with status `PROPOSED` (requiring user approval to run the 11-stage pipeline).
 * - Respects Emergency Stop immediately.
 */
class TeenaResearchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val container = TeenaServiceContainer.getShared(applicationContext)
        return try {
            val outcome = executeResearchCycle(container)
            if (outcome.isSuccess) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        suspend fun executeResearchCycle(container: TeenaServiceContainer): kotlin.Result<ResearchCycleOutcome> {
            return runCatching {
                container.emergencyStopController.assertExternalNetworkAllowed("self-improvement research cycle")
                val db = container.database
                val network = container.networkClients
                val prefs = container.preferences

                var modelsCount = 0
                val findings = mutableListOf<String>()

                // 1. Refresh Gemini ModelRegistry if key exists
                val geminiKey = container.aiBrain.resolvePrimaryGeminiSlotAndKey().second
                if (!geminiKey.isNullOrBlank()) {
                    val refreshed = network.modelRegistry.refreshAndCacheModels(geminiKey).getOrNull()
                    if (refreshed != null) {
                        modelsCount = refreshed.size
                        findings.add("Refreshed $modelsCount Gemini models in ModelRegistry.")
                    }
                }

                // 2. Research GitHub Android AI tools
                val ghToken = container.vault.readSecret("slot_github_token")
                val ghRepos = network.searchGitHubRepositories("android kotlin gemini ai tool", ghToken).getOrNull().orEmpty()
                ghRepos.take(2).forEach { r ->
                    db.researchDao().insertResearch(
                        ResearchEntryEntity(
                            sourceType = "GITHUB",
                            title = r.fullName,
                            summary = r.description,
                            sourceUrl = r.htmlUrl,
                            licenseInfo = r.licenseSpdx,
                            compatibilityNotes = "${r.stars}★ • ${r.language}",
                            securityScore = r.securityScore
                        )
                    )
                }

                // 3. Research Hugging Face edge/vision/speech models
                val ram = container.offlineEngine.inspectHardware().totalRamGb
                val hfModels = network.searchHuggingFaceModels("android mobile ocr vision", "", ram).getOrNull().orEmpty()
                hfModels.take(2).forEach { m ->
                    db.researchDao().insertResearch(
                        ResearchEntryEntity(
                            sourceType = "HUGGINGFACE",
                            title = m.modelId,
                            summary = "${m.pipelineTag} (${m.downloads} downloads)",
                            sourceUrl = m.url,
                            licenseInfo = m.license,
                            compatibilityNotes = m.compatibilityNotes
                        )
                    )
                }

                // 4. Web research on latest Android / Gemini API updates
                val webUpdates = network.searchWeb("Android Jetpack Gemini Live API updates").getOrNull().orEmpty()
                webUpdates.take(2).forEach { w ->
                    db.researchDao().insertResearch(
                        ResearchEntryEntity(
                            sourceType = "WEB",
                            title = w.title,
                            summary = w.snippet,
                            sourceUrl = w.url,
                            licenseInfo = w.source
                        )
                    )
                }

                // 5. Consult external advisors for evaluation (only if enabled by user)
                var advisorNote = "Local evaluation only (External AI Calls disabled or <2 advisors ready)."
                var proposedBy = "Teena Scheduled Research"
                if (container.emergencyStopController.isExternalAiAllowed()) {
                    val evalQuestion = buildString {
                        appendLine("Evaluate these newly discovered candidates for Teena AI (Android Kotlin assistant):")
                        ghRepos.take(2).forEach { appendLine("- GitHub: ${it.fullName} (${it.licenseSpdx}, ${it.stars}★): ${it.description.take(90)}") }
                        hfModels.take(2).forEach { appendLine("- HuggingFace: ${it.modelId} (${it.license}): ${it.compatibilityNotes}") }
                        appendLine("Which candidate is safest and most practical as a declarative Marketplace tool proposal? Reply concisely.")
                    }
                    val collab = container.externalAgentHub.consultExternalAdvisors(
                        topic = "Scheduled Research Evaluation",
                        rawTechnicalQuestion = evalQuestion,
                        gapReason = "WorkManager periodic model/tool discovery",
                        minAdvisorsToAsk = 2
                    ).getOrNull()
                    if (collab != null && collab.advisorCountSucceeded > 0) {
                        val names = collab.messages.filter { it.succeeded }.joinToString(", ") { it.advisorName }
                        proposedBy = "Teena + Advisors ($names)"
                        advisorNote = collab.comparisonNotes
                    }
                }

                // 6. Save top clean proposal into Marketplace as `PROPOSED` (never auto-installed)
                var proposalsAdded = 0
                val existingTools = db.marketplaceToolDao().getToolsOnce().map { it.toolId }.toSet()
                val topGh = ghRepos.firstOrNull {
                    SecurityAuditor.auditToolOrCode(it.fullName, it.htmlUrl, it.licenseSpdx, it.htmlUrl).passed
                }
                if (topGh != null) {
                    val propId = "prop_${topGh.fullName.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(26)}"
                    if (propId !in existingTools) {
                        val scan = SecurityAuditor.auditToolOrCode(topGh.fullName, topGh.htmlUrl, topGh.licenseSpdx, topGh.htmlUrl)
                        val specJson = JSONObject().apply {
                            put("toolName", topGh.fullName.substringAfter('/').take(28))
                            put("category", "Research Proposal")
                            put("description", topGh.description.take(160))
                            put("actionType", "GITHUB_REPO_INSPECTOR")
                            put("endpointOrTarget", topGh.htmlUrl)
                            put("licenseSpdx", topGh.licenseSpdx)
                        }.toString()
                        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                        db.marketplaceToolDao().upsertTool(
                            MarketplaceToolEntity(
                                toolId = propId,
                                name = topGh.fullName.substringAfter('/').take(34),
                                description = "[PROPOSED] ${topGh.description.take(180)}",
                                version = "0.1.0-proposal",
                                category = "Research Proposal",
                                sourceRepo = topGh.htmlUrl,
                                licenseSpdx = topGh.licenseSpdx,
                                executionSpec = specJson,
                                isBuiltIn = false,
                                isInstalled = false,
                                activationStatus = "PROPOSED",
                                securityScore = scan.score,
                                securityNotes = scan.findings.joinToString("; "),
                                testResult = "Proposed by research cycle; approve to run 11-stage pipeline",
                                proposedBy = proposedBy,
                                scanResult = "PRE-SCAN PASSED (${scan.score}/100, ${scan.licenseStatus})",
                                toolKind = "DECLARATIVE_JSON",
                                pipelineStagesJson = ToolCreationPipelineEngine.defaultStagesJson(),
                                historyLog = "[$ts] Proposed by $proposedBy\n$advisorNote"
                            )
                        )
                        proposalsAdded++
                    }
                }

                prefs.updateSettings { it.copy(lastSelfImproveRunAt = System.currentTimeMillis()) }

                val summary = "Models=$modelsCount, GitHub=${ghRepos.size}, HF=${hfModels.size}, Web=${webUpdates.size}, NewProposals=$proposalsAdded"
                db.researchDao().insertImprovementLog(
                    SelfImprovementEntity(
                        cycleTitle = "Scheduled Self-Improvement Research",
                        stageReached = "DISCOVER_AND_PROPOSE",
                        targetComponent = "Marketplace & ModelRegistry",
                        findings = "$summary\n$advisorNote",
                        actionTaken = "Added $proposalsAdded PROPOSED tool(s) for user review",
                        verified = false,
                        rolledBack = false
                    )
                )
                db.systemRecordsDao().insertAuditLog(
                    AuditLogEntity(
                        category = "SELF_IMPROVE",
                        action = "Scheduled Research Cycle",
                        outcome = "COMPLETED",
                        details = summary
                    )
                )

                ResearchCycleOutcome(modelsCount, proposalsAdded, summary)
            }
        }
    }
}

object TeenaResearchScheduler {
    private const val PERIODIC_WORK_NAME = "teena_self_improvement_research_periodic"
    private const val ONE_TIME_WORK_NAME = "teena_self_improvement_research_now"

    private fun workManagerOrNull(context: Context): WorkManager? {
        if (!WorkManager.isInitialized()) {
            runCatching {
                WorkManager.initialize(
                    context.applicationContext,
                    androidx.work.Configuration.Builder().build()
                )
            }
        }
        return runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull()
    }

    fun scheduleOrCancel(context: Context, enabled: Boolean, intervalHours: Int) {
        val wm = workManagerOrNull(context) ?: return
        if (!enabled) {
            runCatching { wm.cancelUniqueWork(PERIODIC_WORK_NAME) }
            return
        }
        val hours = intervalHours.coerceAtLeast(1).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<TeenaResearchWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runOneTimeNow(context: Context) {
        val wm = workManagerOrNull(context) ?: return
        val request = OneTimeWorkRequestBuilder<TeenaResearchWorker>().build()
        wm.enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelAllResearchWork(context: Context) {
        val wm = workManagerOrNull(context) ?: return
        runCatching {
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            wm.cancelUniqueWork(ONE_TIME_WORK_NAME)
        }
    }
}
