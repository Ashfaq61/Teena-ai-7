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
import com.example.ai.CapabilitySummaryAnalyzer
import com.example.ai.ToolCreationPipelineEngine
import com.example.core.TeenaServiceContainer
import com.example.core.security.RepoRiskAnalyzer
import com.example.core.security.RepoRiskLevel
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
 * - Refreshes ModelRegistry, searches GitHub, HuggingFace, and web updates.
 * - Detects Teena capability gaps from all 4 sources and consults >=2 External AI Advisors
 *   (respecting user toggle, Emergency Stop, and hourly rate limit; never leaking keys/PII).
 * - Creates Marketplace `PROPOSED` tools with real 4-part capability briefs & pipeline stages.
 * - If Auto Mode is enabled, safely auto-activates eligible zero-permission declarative tools only.
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
                val settings = prefs.getSettingsSnapshot()

                var modelsCount = 0

                // 1. Refresh Gemini ModelRegistry if key exists
                val geminiKey = container.aiBrain.resolvePrimaryGeminiSlotAndKey().second
                if (!geminiKey.isNullOrBlank()) {
                    val refreshed = network.modelRegistry.refreshAndCacheModels(geminiKey).getOrNull()
                    if (refreshed != null) {
                        modelsCount = refreshed.size
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

                // 4. Web research on latest Android / Gemini / AI updates
                val webUpdates = network.searchWeb("Android Jetpack Gemini API AI tools updates").getOrNull().orEmpty()
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

                // 5. Consult >=2 external advisors on latest Android/AI tools, GitHub/HF projects & Teena's gaps
                val gaps = container.externalAgentHub.detectCapabilityGaps()
                var advisorNote = "Local evaluation only (External AI Calls disabled, rate-limited, or <2 advisors ready)."
                var proposedBy = "Teena Scheduled Research"
                if (container.emergencyStopController.isExternalAiAllowed()) {
                    val evalQuestion = buildString {
                        appendLine("Teena AI scheduled technical check-in (Android Kotlin + Jetpack Compose assistant).")
                        appendLine("1) Latest Android / Jetpack / AI features or Play-Policy-safe libraries (MIT/Apache-2.0)?")
                        appendLine("2) Useful new GitHub/HuggingFace projects?")
                        ghRepos.take(2).forEach { appendLine("   - GitHub: ${it.fullName} (${it.licenseSpdx}, ${it.stars}★): ${it.description.take(80)}") }
                        hfModels.take(2).forEach { appendLine("   - HuggingFace: ${it.modelId} (${it.license}): ${it.compatibilityNotes}") }
                        appendLine("3) Teena current detected gaps:")
                        gaps.take(2).forEach { appendLine("   - ${it.title}: ${it.reason.take(100)}") }
                        appendLine("Recommend safest declarative tools or improvements.")
                    }
                    val collab = container.externalAgentHub.consultExternalAdvisors(
                        topic = "Scheduled Android/AI & Gap Research",
                        rawTechnicalQuestion = evalQuestion,
                        gapReason = "WorkManager periodic model/tool & gap discovery",
                        minAdvisorsToAsk = 2,
                        hourlyLimit = settings.advisorHourlyRateLimit
                    ).getOrNull()
                    if (collab != null && collab.advisorCountSucceeded > 0) {
                        val names = collab.messages.filter { it.succeeded }.joinToString(", ") { it.advisorName }
                        proposedBy = "Teena + Advisors ($names)"
                        advisorNote = collab.comparisonNotes
                    }
                }

                // 6. Inspect & summarize top GitHub candidate + convert gaps into PROPOSED Marketplace entries
                var proposalsAdded = 0
                val existingTools = db.marketplaceToolDao().getToolsOnce().map { it.toolId }.toSet()
                val topGh = ghRepos.firstOrNull {
                    SecurityAuditor.auditToolOrCode(it.fullName, it.htmlUrl, it.licenseSpdx, it.htmlUrl).passed
                }
                if (topGh != null) {
                    val propId = "prop_${topGh.fullName.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(26)}"
                    if (propId !in existingTools) {
                        val bundle = network.fetchGitHubRepoAuditBundle(topGh.fullName, ghToken).getOrNull()
                        var briefJson = ""
                        var scanScore = topGh.securityScore
                        var scanHeadline = "PRE-SCAN PASSED (${topGh.securityScore}/100)"
                        var isSafeToPropose = true

                        if (bundle != null) {
                            val risk = RepoRiskAnalyzer.analyzeRealCode(
                                repoFullName = bundle.fullName,
                                repoUrl = bundle.htmlUrl,
                                commitSha = bundle.commitSha,
                                licenseSpdx = bundle.licenseSpdx,
                                licenseText = bundle.licenseText,
                                stars = bundle.stars,
                                ownerLogin = bundle.ownerLogin,
                                ownerAccountAgeDays = bundle.ownerAccountAgeDays,
                                files = bundle.files
                            )
                            isSafeToPropose = risk.codeReviewed && risk.riskLevel != RepoRiskLevel.KHATARNAAK && risk.proposeAllowed
                            scanScore = risk.riskScore
                            scanHeadline = "${risk.riskLevel.label} (${risk.riskScore}/100)"
                            val summary = CapabilitySummaryAnalyzer.analyzeGitHubRepo(container.aiBrain, bundle, risk)
                            db.capabilitySummaryDao().upsert(summary)
                            briefJson = CapabilitySummaryAnalyzer.toJsonString(summary)
                        }

                        if (isSafeToPropose) {
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
                                    securityScore = scanScore,
                                    securityNotes = scanHeadline,
                                    testResult = "Proposed by research cycle; tap Run 10-Stage Pipeline",
                                    proposedBy = proposedBy,
                                    scanResult = scanHeadline,
                                    toolKind = "KOTLIN_GITHUB_BRANCH",
                                    pipelineStagesJson = ToolCreationPipelineEngine.defaultStagesJson(),
                                    historyLog = "[$ts] Proposed by $proposedBy\n$advisorNote",
                                    capabilitySummaryJson = briefJson
                                )
                            )
                            proposalsAdded++
                        }
                    }
                }

                // Also turn 4-source gaps (health, failures, user requests, advisor suggestions) into PROPOSED items
                val gapProposals = container.toolPipelineEngine.detectGapsAndCreateProposals()
                proposalsAdded += gapProposals.size

                // 7. If Auto Mode is ON (and not emergency stopped), auto-run eligible zero-permission declarative gap proposals
                if (settings.autoActivateSafeDeclarativeTools && container.emergencyStopController.isAutoModeAllowed()) {
                    gapProposals.take(1).forEach { prop ->
                        container.toolPipelineEngine.runPipelineUntilUserApproval(
                            capabilityGoal = prop.name,
                            toolKind = prop.toolKind,
                            existingToolId = prop.toolId
                        )
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
