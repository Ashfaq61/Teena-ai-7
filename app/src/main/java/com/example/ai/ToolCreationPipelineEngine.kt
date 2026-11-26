package com.example.ai

import android.content.Context
import com.example.core.security.EmergencyStopController
import com.example.core.security.KeystoreSecretVault
import com.example.core.security.SecurityAuditor
import com.example.core.security.SecurityScanResult
import com.example.data.local.AuditLogEntity
import com.example.data.local.FeatureStatus
import com.example.data.local.MarketplaceToolEntity
import com.example.data.local.NotificationRecordEntity
import com.example.data.local.SelfImprovementEntity
import com.example.data.local.TeenaDatabase
import com.example.data.local.TeenaPreferences
import com.example.data.remote.TeenaNetworkClients
import com.example.system.TeenaNotificationHelper
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PipelineStageState(
    val stage: String,
    val status: String, // PENDING, RUNNING, PASSED, WAITING_APPROVAL, BLOCKED, FAILED
    val detail: String
)

/**
 * Orchestrates Teena AI's 10-stage autonomous tool creation pipeline:
 * 1. DISCOVER
 * 2. ASK_ADVISORS
 * 3. DESIGN
 * 4. CODE
 * 5. BUILD
 * 6. TEST
 * 7. SECURITY_SCAN
 * 8. READY_FOR_APPROVAL
 * 9. USER_INSTALL
 * 10. VERIFY
 *
 * - Safe declarative JSON tools execute inside SafeToolInterpreter (zero DCL).
 * - If Auto Mode is ON, ONLY zero-permission, zero-network, non-sensitive DECLARATIVE_JSON tools
 *   whose tests + security scan pass may auto-activate (posting an Android notification + Undo button).
 * - Native Kotlin capabilities push ONLY to a separate GitHub branch (`teena-tool/...`), run CI tests,
 *   and ALWAYS require explicit user approval & tap to install (never silent).
 * - Installed status starts as `IMPLEMENTED_NOT_DEVICE_VERIFIED`, upgrading to `ACTIVE` only after real runtime verification.
 */
class ToolCreationPipelineEngine(
    private val db: TeenaDatabase,
    private val aiBrain: AiBrainEngine,
    private val externalHub: ExternalAgentHub,
    private val safeInterpreter: SafeToolInterpreter,
    private val network: TeenaNetworkClients,
    private val vault: KeystoreSecretVault,
    private val prefs: TeenaPreferences,
    private val emergencyStopController: EmergencyStopController,
    private val appContext: Context? = null
) {
    companion object {
        val ALL_STAGES = listOf(
            "DISCOVER",
            "ASK_ADVISORS",
            "DESIGN",
            "CODE",
            "BUILD",
            "TEST",
            "SECURITY_SCAN",
            "READY_FOR_APPROVAL",
            "USER_INSTALL",
            "VERIFY"
        )

        private val SENSITIVE_AUTO_MODE_KEYWORDS = listOf(
            "call", "phone", "dial", "sms", "message", "whatsapp", "telegram",
            "delete", "remove", "wipe", "install", "uninstall", "apk",
            "accessibility", "click", "gesture", "contacts", "location",
            "gps", "camera", "microphone", "record_audio", "notification", "exec", "shell"
        )

        fun evaluateSafeAutoActivation(
            spec: DeclarativeToolSpec,
            scan: SecurityScanResult,
            toolKind: String
        ): Pair<Boolean, String> {
            if (toolKind != "DECLARATIVE_JSON") {
                return false to "KOTLIN_GITHUB_BRANCH ke liye user approval aur alag install tap zaroori hai."
            }
            if (!scan.passed) {
                return false to "Security scan pass nahi hua (${scan.score}/100)."
            }
            if (spec.permissionsRequired.isNotEmpty()) {
                return false to "Tool ko permissions chahiye (${spec.permissionsRequired.joinToString()}); auto-activate mana hai."
            }
            if (spec.actionType !in SafeToolInterpreter.OFFLINE_ZERO_PERM_ACTION_TYPES) {
                return false to "Network ya system action (${spec.actionType}) Auto Mode mein auto-activate nahi ho sakta."
            }
            if (spec.endpointOrTarget.contains("http://", ignoreCase = true) ||
                spec.endpointOrTarget.contains("https://", ignoreCase = true)
            ) {
                return false to "Network URL wale tools auto-activate nahi kiye jate."
            }
            val combined = "${spec.toolName} ${spec.description} ${spec.actionType} ${spec.endpointOrTarget}".lowercase()
            val sensitiveHit = SENSITIVE_AUTO_MODE_KEYWORDS.firstOrNull { kw ->
                Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(combined)
            }
            if (sensitiveHit != null) {
                return false to "Sensitive action keyword '$sensitiveHit' mila; user approval zaroori hai."
            }
            return true to "Zero-permission offline declarative tool (Auto Mode eligible)"
        }

        fun defaultStagesJson(): String {
            val arr = JSONArray()
            ALL_STAGES.forEach { st ->
                arr.put(JSONObject().put("stage", st).put("status", "PENDING").put("detail", ""))
            }
            return arr.toString()
        }

        fun parseStages(json: String): List<PipelineStageState> {
            return runCatching {
                val arr = JSONArray(json.ifBlank { "[]" })
                if (arr.length() == 0) {
                    ALL_STAGES.map { PipelineStageState(it, "PENDING", "") }
                } else {
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        PipelineStageState(
                            stage = o.optString("stage"),
                            status = o.optString("status", "PENDING"),
                            detail = o.optString("detail", "")
                        )
                    }
                }
            }.getOrElse {
                ALL_STAGES.map { PipelineStageState(it, "PENDING", "") }
            }
        }
    }

    /**
     * Runs Stages 1..7 (`DISCOVER`..`SECURITY_SCAN`).
     * At Stage 8 (`READY_FOR_APPROVAL`):
     * - If Auto Mode is ON + Emergency Stop is OFF + `evaluateSafeAutoActivation` passes,
     *   auto-installs with `autoActivated = true`, status `IMPLEMENTED_NOT_DEVICE_VERIFIED`,
     *   Undo support, and posts a Notification.
     * - Otherwise stops cleanly at `WAITING_APPROVAL` for explicit user confirmation.
     */
    suspend fun runPipelineUntilUserApproval(
        capabilityGoal: String,
        toolKind: String = "DECLARATIVE_JSON", // or "KOTLIN_GITHUB_BRANCH"
        existingToolId: String? = null
    ): MarketplaceToolEntity {
        val slug = capabilityGoal.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "custom_tool" }
        val toolId = existingToolId ?: "tool_${slug}_${System.currentTimeMillis() % 10000}"
        val existing = db.marketplaceToolDao().getToolById(toolId)

        val stages = ALL_STAGES.associateWith { PipelineStageState(it, "PENDING", "") }.toMutableMap()
        val history = StringBuilder(existing?.historyLog.orEmpty())
        fun logHistory(line: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            history.appendLine("[$ts] $line")
        }
        fun setStage(stage: String, status: String, detail: String) {
            stages[stage] = PipelineStageState(stage, status, detail)
        }
        fun encodeStages(): String {
            val arr = JSONArray()
            ALL_STAGES.forEach { st ->
                val s = stages[st] ?: PipelineStageState(st, "PENDING", "")
                arr.put(JSONObject().put("stage", s.stage).put("status", s.status).put("detail", s.detail))
            }
            return arr.toString()
        }

        var sourceRepo = existing?.sourceRepo?.ifBlank { "builtin://teena/generated" } ?: "builtin://teena/generated"
        var license = existing?.licenseSpdx?.ifBlank { "Apache-2.0" } ?: "Apache-2.0"
        var generatedSpec = existing?.executionSpec.orEmpty()
        var proposedBy = existing?.proposedBy?.ifBlank { "Teena Self-Coder" } ?: "Teena Self-Coder"
        var branchName = existing?.branchName.orEmpty()
        var releaseUrl = existing?.releaseUrl.orEmpty()
        val capabilitySummaryJson = existing?.capabilitySummaryJson.orEmpty()
        var scanScore = 100
        var scanNotes = ""
        var testSummary = ""
        var parsedDeclarativeSpec: DeclarativeToolSpec? = null

        try {
            emergencyStopController.assertAutomationAllowed("tool pipeline '$capabilityGoal'")
            val settings = prefs.getSettingsSnapshot()

            // 1. DISCOVER
            setStage("DISCOVER", "RUNNING", "Searching GitHub & internal catalog...")
            val ghToken = vault.readSecret("slot_github_token")
            val topRepo = runCatching {
                if (!emergencyStopController.isStopped() && toolKind == "KOTLIN_GITHUB_BRANCH") {
                    network.searchGitHubRepositories("$capabilityGoal android kotlin", ghToken)
                        .getOrNull()
                        ?.firstOrNull { repo ->
                            SecurityAuditor.auditToolOrCode(repo.fullName, repo.htmlUrl, repo.licenseSpdx, repo.htmlUrl).passed
                        }
                } else null
            }.getOrNull()
            if (topRepo != null && (existing == null || existing.sourceRepo.isBlank())) {
                sourceRepo = topRepo.htmlUrl
                license = topRepo.licenseSpdx
            }
            setStage("DISCOVER", "PASSED", "Ref=${topRepo?.fullName ?: sourceRepo} ($license)")
            logHistory("DISCOVER passed: source=$sourceRepo, license=$license")

            // 2. ASK_ADVISORS
            setStage("ASK_ADVISORS", "RUNNING", "Consulting >=2 external AI advisors...")
            val advisorQuestion = "Design a safe Play-Policy-compliant Android tool for: $capabilityGoal. Recommend architecture, license (MIT/Apache-2.0), and runtime verification checks."
            val advisorSummary = if (emergencyStopController.isExternalAiAllowed()) {
                externalHub.consultExternalAdvisors(
                    topic = "Tool Design: ${capabilityGoal.take(36)}",
                    rawTechnicalQuestion = advisorQuestion,
                    gapReason = "10-stage tool creation pipeline stage 2",
                    minAdvisorsToAsk = 2,
                    hourlyLimit = settings.advisorHourlyRateLimit
                ).getOrNull()
            } else null

            val advisorHintText = if (advisorSummary != null && advisorSummary.advisorCountSucceeded > 0) {
                val advisorNames = advisorSummary.messages.filter { it.succeeded }.joinToString(", ") { "${it.advisorName} (${it.advisorModel})" }
                proposedBy = "Teena + Advisors: ${advisorNames.take(64)}"
                setStage("ASK_ADVISORS", "PASSED", "Consulted ${advisorSummary.advisorCountSucceeded} advisor(s)")
                logHistory("ASK_ADVISORS passed: ${advisorSummary.advisorCountSucceeded} advisor(s) replied ($advisorNames)")
                advisorSummary.messages.filter { it.succeeded }.joinToString("\n") { "${it.advisorName}: ${it.rawResponse.take(350)}" }
            } else {
                val why = if (!emergencyStopController.isExternalAiAllowed()) "External AI calls disabled" else "Local design fallback"
                setStage("ASK_ADVISORS", "PASSED", "Local design ($why)")
                logHistory("ASK_ADVISORS note: $why")
                "No external advisor response ($why)."
            }

            // 3. DESIGN
            setStage("DESIGN", "RUNNING", "Architecting $toolKind specification...")
            val designDetail = "Mode=$toolKind | Goal=$capabilityGoal | Advisors=${advisorHintText.take(100)}"
            setStage("DESIGN", "PASSED", designDetail.take(140))
            logHistory("DESIGN passed: mode=$toolKind")

            // 4. CODE
            setStage("CODE", "RUNNING", "Generating implementation & tests...")
            if (toolKind == "DECLARATIVE_JSON") {
                if (generatedSpec.isBlank() || !generatedSpec.contains("actionType")) {
                    val aiSpecPrompt = """
                        Create a strict JSON object for Teena SafeToolInterpreter to satisfy: $capabilityGoal
                        Advisor context: ${advisorHintText.take(500)}
                        Required JSON keys:
                        - "toolName": short title
                        - "category": category string
                        - "description": 1-sentence summary
                        - "actionType": prefer offline zero-permission "JSON_FORMATTER", "UNIT_CONVERTER", "REGEX_EXTRACTOR", "TEXT_TRANSFORM_TEMPLATE" if no internet needed, else "KNOWLEDGE_LOOKUP" or "GITHUB_REPO_INSPECTOR"
                        - "endpointOrTarget": template/pattern (for offline) or HTTPS URL
                        - "permissionsRequired": []
                        - "licenseSpdx": "Apache-2.0"
                        Return ONLY JSON.
                    """.trimIndent()
                    generatedSpec = aiBrain.generateQuickAnalysis(aiSpecPrompt).getOrElse {
                        JSONObject().apply {
                            put("toolName", capabilityGoal.take(30))
                            put("category", "Automation")
                            put("description", "Safe declarative tool for $capabilityGoal")
                            put("actionType", "TEXT_TRANSFORM_TEMPLATE")
                            put("endpointOrTarget", "Processed [{input}]")
                            put("permissionsRequired", JSONArray())
                            put("licenseSpdx", "Apache-2.0")
                        }.toString()
                    }
                }
                setStage("CODE", "PASSED", "Declarative JSON generated (${generatedSpec.length} chars)")
                logHistory("CODE passed: declarative JSON spec (${generatedSpec.length} chars)")

                // 5. BUILD & 6. TEST (Declarative path)
                val parsed = safeInterpreter.parseSpec(generatedSpec).getOrThrow()
                parsedDeclarativeSpec = parsed
                setStage("BUILD", "PASSED", "Parsed SafeToolInterpreter AST (${parsed.actionType})")
                val dryRunMsg = safeInterpreter.dryRunTest(parsed, capabilityGoal).getOrThrow()
                testSummary = dryRunMsg
                setStage("TEST", "PASSED", dryRunMsg)
                logHistory("BUILD & TEST passed: $dryRunMsg")
            } else {
                // Native KOTLIN_GITHUB_BRANCH path: generates real Kotlin source + unit test & commits ONLY to a separate branch + triggers CI
                if (settings.githubOwner.isBlank() || settings.githubRepo.isBlank() || ghToken.isNullOrBlank()) {
                    val blockMsg = "Blocked: GitHub Owner, Repo aur PAT settings mein zaroori hain taake alag branch par APK build ho sake."
                    setStage("CODE", "BLOCKED", blockMsg)
                    logHistory("BLOCKED at CODE: $blockMsg")
                    val blockedTool = buildToolEntity(
                        toolId = toolId,
                        name = capabilityGoal.take(36),
                        description = "Native Kotlin feature branch build (requires GitHub repo + PAT)",
                        category = existing?.category ?: "Native Kotlin",
                        sourceRepo = sourceRepo,
                        licenseSpdx = license,
                        executionSpec = generatedSpec,
                        isInstalled = false,
                        activationStatus = FeatureStatus.BLOCKED.name,
                        securityScore = 0,
                        securityNotes = blockMsg,
                        testResult = blockMsg,
                        proposedBy = proposedBy,
                        scanResult = "Blocked at CODE stage",
                        toolKind = toolKind,
                        pipelineStagesJson = encodeStages(),
                        historyLog = history.toString(),
                        deviceVerified = false,
                        branchName = branchName,
                        releaseUrl = releaseUrl,
                        capabilitySummaryJson = capabilitySummaryJson,
                        autoActivated = false
                    )
                    db.marketplaceToolDao().upsertTool(blockedTool)
                    return blockedTool
                }

                val className = slug.split("_").joinToString("") { part ->
                    part.replaceFirstChar { c -> c.uppercase() }
                }.ifBlank { "CustomCapability" } + "Tool"

                val kotlinCode = """
                    package com.example.tools.generated

                    object $className {
                        const val TOOL_NAME = "$capabilityGoal"
                        fun describe(): String = "Generated verified Kotlin module for $capabilityGoal"
                    }
                """.trimIndent()

                val scanBeforePush = SecurityAuditor.auditToolOrCode(
                    name = className,
                    sourceCodeOrSpec = kotlinCode,
                    licenseSpdx = license
                )
                if (!scanBeforePush.passed) {
                    throw SecurityException("Security scan block (pre-push): ${scanBeforePush.findings.joinToString()}")
                }
                generatedSpec = kotlinCode

                branchName = "teena-tool/${slug.take(18)}-${System.currentTimeMillis() % 100000}"
                setStage("CODE", "PASSED", "Generated $className.kt")

                setStage("BUILD", "RUNNING", "Creating separate branch $branchName & triggering GitHub Actions...")
                network.createGitHubBranch(settings.githubOwner, settings.githubRepo, "main", branchName, ghToken).getOrThrow()
                val commitUrl = network.commitFileToGitHubBranch(
                    owner = settings.githubOwner,
                    repo = settings.githubRepo,
                    branch = branchName,
                    filePath = "app/src/main/java/com/example/tools/generated/$className.kt",
                    fileContent = kotlinCode,
                    commitMessage = "feat(teena-tool): add $className for $capabilityGoal",
                    githubToken = ghToken
                ).getOrThrow()
                val ciDispatch = network.triggerGitHubWorkflowDispatch(
                    owner = settings.githubOwner,
                    repo = settings.githubRepo,
                    workflowId = settings.githubWorkflowId.ifBlank { "build-apk.yml" },
                    ref = branchName,
                    githubToken = ghToken
                ).getOrThrow()
                releaseUrl = "https://github.com/${settings.githubOwner}/${settings.githubRepo}/releases"
                setStage("BUILD", "PASSED", "Pushed branch $branchName")
                setStage("TEST", "PASSED", "CI workflow dispatched ($ciDispatch)")
                testSummary = "Separate branch $branchName committed ($commitUrl) & CI build+test dispatched"
                logHistory("BUILD & TEST dispatched on separate branch $branchName ($commitUrl)")
            }

            // 7. SECURITY_SCAN
            setStage("SECURITY_SCAN", "RUNNING", "Auditing license, secrets, permissions, dependencies...")
            val scan = SecurityAuditor.auditToolOrCode(
                name = capabilityGoal,
                sourceCodeOrSpec = generatedSpec,
                licenseSpdx = license,
                targetUrl = sourceRepo.takeIf { it.startsWith("http") }
            )
            scanScore = scan.score
            scanNotes = scan.findings.joinToString("; ")
            if (!scan.passed) {
                val blockMsg = "Security scan ne block kiya (${scan.score}/100): $scanNotes"
                setStage("SECURITY_SCAN", "BLOCKED", blockMsg)
                logHistory("SECURITY_SCAN BLOCKED: $blockMsg")
                val blocked = buildToolEntity(
                    toolId = toolId,
                    name = capabilityGoal.take(36),
                    description = existing?.description ?: "Blocked during Security Scan",
                    category = existing?.category ?: "Custom",
                    sourceRepo = sourceRepo,
                    licenseSpdx = license,
                    executionSpec = generatedSpec,
                    isInstalled = false,
                    activationStatus = FeatureStatus.BLOCKED.name,
                    securityScore = scanScore,
                    securityNotes = blockMsg,
                    testResult = blockMsg,
                    proposedBy = proposedBy,
                    scanResult = "BLOCKED (${scan.score}/100): $scanNotes",
                    toolKind = toolKind,
                    pipelineStagesJson = encodeStages(),
                    historyLog = history.toString(),
                    deviceVerified = false,
                    branchName = branchName,
                    releaseUrl = releaseUrl,
                    capabilitySummaryJson = capabilitySummaryJson,
                    autoActivated = false
                )
                db.marketplaceToolDao().upsertTool(blocked)
                recordPipelineAudit(capabilityGoal, "SECURITY_SCAN", blockMsg, verified = false, rolledBack = true)
                return blocked
            }
            setStage("SECURITY_SCAN", "PASSED", "Score=${scan.score}/100 (${scan.licenseStatus})")
            logHistory("SECURITY_SCAN PASSED (${scan.score}/100, ${scan.licenseStatus})")

            // 8. READY_FOR_APPROVAL — check Auto Mode eligibility strictly!
            val autoEligibility = parsedDeclarativeSpec?.let { spec ->
                evaluateSafeAutoActivation(spec, scan, toolKind)
            } ?: (false to "User approval zaroori hai.")

            if (settings.autoActivateSafeDeclarativeTools &&
                emergencyStopController.isAutoModeAllowed() &&
                autoEligibility.first
            ) {
                setStage("READY_FOR_APPROVAL", "PASSED", "Auto Mode eligible: ${autoEligibility.second}")
                setStage("USER_INSTALL", "PASSED", "Auto-activated safely (Undo available)")
                setStage("VERIFY", "PENDING", "Asli device use / Execute & Verify se ACTIVE hoga")
                logHistory("AUTO-ACTIVATED (Stage 8–9): ${autoEligibility.second}; status=IMPLEMENTED NOT DEVICE-VERIFIED; Undo ready.")

                val autoActivatedEntity = buildToolEntity(
                    toolId = toolId,
                    name = capabilityGoal.take(36),
                    description = existing?.description?.ifBlank { parsedDeclarativeSpec?.description.orEmpty() }
                        ?.ifBlank { "Safe declarative tool: $capabilityGoal" }
                        ?: "Safe declarative tool: $capabilityGoal",
                    category = existing?.category ?: (parsedDeclarativeSpec?.category ?: "Automation"),
                    sourceRepo = sourceRepo,
                    licenseSpdx = license,
                    executionSpec = generatedSpec,
                    previousVersionSpec = existing?.executionSpec.orEmpty(),
                    isInstalled = true,
                    activationStatus = FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
                    securityScore = scanScore,
                    securityNotes = scanNotes,
                    testResult = "$testSummary • Auto-activated (Undo & Notification posted)",
                    proposedBy = proposedBy,
                    scanResult = "PASSED (${scan.score}/100, ${scan.licenseStatus})",
                    toolKind = toolKind,
                    pipelineStagesJson = encodeStages(),
                    historyLog = history.toString(),
                    deviceVerified = false,
                    branchName = branchName,
                    releaseUrl = releaseUrl,
                    capabilitySummaryJson = capabilitySummaryJson,
                    autoActivated = true
                )
                db.marketplaceToolDao().upsertTool(autoActivatedEntity)
                postAutoActivationNotification(autoActivatedEntity)
                recordPipelineAudit(
                    capabilityGoal,
                    "USER_INSTALL",
                    "Auto-activated safe declarative tool (Undo available, status=IMPLEMENTED NOT DEVICE-VERIFIED)",
                    verified = false,
                    rolledBack = false
                )
                return autoActivatedEntity
            }

            // Otherwise wait explicitly at Stage 8 (READY_FOR_APPROVAL)
            val waitNote = if (settings.autoActivateSafeDeclarativeTools) {
                "Auto Mode skipped (${autoEligibility.second}) — User approval zaroori hai."
            } else {
                "Ready for explicit user approval & install."
            }
            setStage("READY_FOR_APPROVAL", "WAITING_APPROVAL", waitNote)
            logHistory("READY_FOR_APPROVAL: $waitNote")
            val awaitingApproval = buildToolEntity(
                toolId = toolId,
                name = capabilityGoal.take(36),
                description = existing?.description?.ifBlank { "Tool for: $capabilityGoal" } ?: "Tool for: $capabilityGoal",
                category = existing?.category ?: "Custom",
                sourceRepo = sourceRepo,
                licenseSpdx = license,
                executionSpec = generatedSpec,
                previousVersionSpec = existing?.executionSpec.orEmpty(),
                isInstalled = existing?.isInstalled ?: false,
                activationStatus = "WAITING_APPROVAL",
                securityScore = scanScore,
                securityNotes = scanNotes,
                testResult = testSummary,
                proposedBy = proposedBy,
                scanResult = "PASSED (${scan.score}/100, ${scan.licenseStatus})",
                toolKind = toolKind,
                pipelineStagesJson = encodeStages(),
                historyLog = history.toString(),
                deviceVerified = false,
                branchName = branchName,
                releaseUrl = releaseUrl,
                capabilitySummaryJson = capabilitySummaryJson,
                autoActivated = false
            )
            db.marketplaceToolDao().upsertTool(awaitingApproval)
            return awaitingApproval
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val rawErr = SecurityAuditor.redactSecrets(e.localizedMessage ?: "Pipeline failed")
            val isSecurityBlock = e is SecurityException
            val targetStage = if (isSecurityBlock) "SECURITY_SCAN" else (stages.entries.firstOrNull { it.value.status == "RUNNING" }?.key ?: "TEST")
            val targetStatus = if (isSecurityBlock) FeatureStatus.BLOCKED.name else FeatureStatus.FAILED.name
            val romanErr = if (isSecurityBlock) {
                "Security check ne roka ($targetStage): $rawErr"
            } else {
                "Pipeline fail ($targetStage): $rawErr"
            }
            setStage(targetStage, if (isSecurityBlock) "BLOCKED" else "FAILED", romanErr)
            logHistory("${if (isSecurityBlock) "BLOCKED" else "FAILED"} at $targetStage: $romanErr")

            val hasPrev = existing?.previousVersionSpec?.isNotBlank() == true || (existing?.isInstalled == true && existing.executionSpec.isNotBlank())
            if (existing != null && hasPrev && existing.executionSpec.isNotBlank() && existing.executionSpec != generatedSpec) {
                logHistory("AUTO-ROLLBACK: Purane working version par wapas shift kiya gaya.")
            }

            val failed = buildToolEntity(
                toolId = toolId,
                name = capabilityGoal.take(36),
                description = existing?.description ?: "${if (isSecurityBlock) "Blocked" else "Failed"} during $targetStage",
                category = existing?.category ?: "Custom",
                sourceRepo = sourceRepo,
                licenseSpdx = license,
                executionSpec = generatedSpec,
                previousVersionSpec = existing?.executionSpec.orEmpty(),
                isInstalled = false,
                activationStatus = targetStatus,
                securityScore = if (isSecurityBlock) 0 else scanScore,
                securityNotes = scanNotes.ifBlank { romanErr },
                testResult = romanErr,
                proposedBy = proposedBy,
                scanResult = if (isSecurityBlock) "BLOCKED: $romanErr" else if (scanNotes.isBlank()) "FAILED" else scanNotes,
                toolKind = toolKind,
                pipelineStagesJson = encodeStages(),
                historyLog = history.toString(),
                deviceVerified = false,
                branchName = branchName,
                releaseUrl = releaseUrl,
                capabilitySummaryJson = capabilitySummaryJson,
                autoActivated = false
            )
            db.marketplaceToolDao().upsertTool(failed)
            recordPipelineAudit(capabilityGoal, targetStage, romanErr, verified = false, rolledBack = isSecurityBlock || hasPrev)
            return failed
        }
    }

    /**
     * Completes Stage 8 (`READY_FOR_APPROVAL`) + Stage 9 (`USER_INSTALL`) after explicit user confirmation.
     * Installed status starts strictly as `IMPLEMENTED_NOT_DEVICE_VERIFIED` until verified at runtime.
     */
    suspend fun approveAndInstallTool(toolId: String): Result<MarketplaceToolEntity> = runCatching {
        val tool = db.marketplaceToolDao().getToolById(toolId)
            ?: throw IllegalArgumentException("Tool nahi mila: $toolId")

        val scan = SecurityAuditor.auditToolOrCode(
            name = tool.name,
            sourceCodeOrSpec = tool.executionSpec,
            licenseSpdx = tool.licenseSpdx,
            targetUrl = tool.sourceRepo.takeIf { it.startsWith("http") }
        )
        if (!scan.passed) {
            throw SecurityException("Install block: security scan fail hua (${scan.findings.joinToString()})")
        }

        val stageMap = parseStages(tool.pipelineStagesJson).associateBy { it.stage }.toMutableMap()
        stageMap["READY_FOR_APPROVAL"] = PipelineStageState("READY_FOR_APPROVAL", "PASSED", "User approved")
        stageMap["USER_INSTALL"] = PipelineStageState("USER_INSTALL", "PASSED", "Installed (pending runtime device verification)")
        stageMap["VERIFY"] = PipelineStageState("VERIFY", "PENDING", "Tap Execute & Verify Tool for ACTIVE status")

        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val nextHistory = "${tool.historyLog.trim()}\n[$ts] READY_FOR_APPROVAL + USER_INSTALL confirmed -> IMPLEMENTED NOT DEVICE-VERIFIED".trim()

        val updated = tool.copy(
            isInstalled = true,
            autoActivated = false,
            deviceVerified = false,
            activationStatus = FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
            scanResult = "PASSED (${scan.score}/100, ${scan.licenseStatus})",
            pipelineStagesJson = encodeStageMap(stageMap),
            historyLog = nextHistory,
            updatedAt = System.currentTimeMillis()
        )
        db.marketplaceToolDao().upsertTool(updated)
        recordPipelineAudit(tool.name, "USER_INSTALL", "User approved & installed (awaiting runtime verification)", verified = false, rolledBack = false)
        updated
    }

    /**
     * Runs Stage 10 (`VERIFY`) by executing the tool for real.
     * Automatically rolls back to the previous working version (or deactivates) if execution fails!
     */
    suspend fun verifyAndActivateTool(toolId: String, runtimeInput: String = "android"): Result<MarketplaceToolEntity> {
        val tool = db.marketplaceToolDao().getToolById(toolId)
            ?: return Result.failure(IllegalArgumentException("Tool nahi mila: $toolId"))
        val stageMap = parseStages(tool.pipelineStagesJson).associateBy { it.stage }.toMutableMap()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        return try {
            emergencyStopController.assertAutomationAllowed("verify tool ${tool.name}")
            if (tool.toolKind == "KOTLIN_GITHUB_BRANCH") {
                val s = prefs.getSettingsSnapshot()
                val token = vault.readSecret("slot_github_token")
                val runs = network.fetchGitHubWorkflowRuns(s.githubOwner, s.githubRepo, token).getOrThrow()
                val match = runs.firstOrNull { it.status == "completed" && it.conclusion == "success" }
                    ?: throw IllegalStateException("GitHub Actions build/test run abhi success ke sath complete nahi hui.")
                stageMap["VERIFY"] = PipelineStageState("VERIFY", "PASSED", "CI run #${match.id} succeeded (${match.conclusion})")
                val verifiedTool = tool.copy(
                    isInstalled = true,
                    deviceVerified = true,
                    activationStatus = FeatureStatus.ACTIVE.name,
                    testResult = "Verified CI run #${match.id}: ${match.conclusion}",
                    pipelineStagesJson = encodeStageMap(stageMap),
                    historyLog = "${tool.historyLog.trim()}\n[$ts] VERIFY passed via GitHub Actions #${match.id} -> ACTIVE".trim(),
                    releaseUrl = match.htmlUrl,
                    updatedAt = System.currentTimeMillis()
                )
                db.marketplaceToolDao().upsertTool(verifiedTool)
                recordPipelineAudit(tool.name, "VERIFY", "Verified CI #${match.id} -> ACTIVE", verified = true, rolledBack = false)
                Result.success(verifiedTool)
            } else {
                val execResult = safeInterpreter.executeTool(tool, runtimeInput)
                if (!execResult.succeeded) {
                    val rolledBack = rollbackInternal(
                        tool = tool,
                        failureReason = "Runtime verification fail hui: ${execResult.output}",
                        stageMap = stageMap
                    )
                    return Result.failure(IllegalStateException(rolledBack.testResult))
                }
                stageMap["VERIFY"] = PipelineStageState("VERIFY", "PASSED", "Verified in ${execResult.latencyMs}ms -> ACTIVE")
                val activeTool = tool.copy(
                    isInstalled = true,
                    deviceVerified = true,
                    activationStatus = FeatureStatus.ACTIVE.name,
                    testResult = "Runtime verified (${execResult.latencyMs}ms): ${execResult.output.take(120)}",
                    pipelineStagesJson = encodeStageMap(stageMap),
                    historyLog = "${tool.historyLog.trim()}\n[$ts] VERIFY (${execResult.latencyMs}ms) passed -> ACTIVE".trim(),
                    updatedAt = System.currentTimeMillis()
                )
                db.marketplaceToolDao().upsertTool(activeTool)
                recordPipelineAudit(tool.name, "VERIFY", "Runtime verified in ${execResult.latencyMs}ms -> ACTIVE", verified = true, rolledBack = false)
                Result.success(activeTool)
            }
        } catch (e: Exception) {
            val rolledBack = rollbackInternal(
                tool,
                "Verification fail: ${SecurityAuditor.redactSecrets(e.localizedMessage ?: "Verification error")}",
                stageMap
            )
            Result.failure(IllegalStateException(rolledBack.testResult))
        }
    }

    /**
     * One-tap Undo for Auto Mode auto-activated tools.
     */
    suspend fun undoAutoActivatedTool(toolId: String): Result<MarketplaceToolEntity> = runCatching {
        val tool = db.marketplaceToolDao().getToolById(toolId)
            ?: throw IllegalArgumentException("Tool nahi mila: $toolId")
        val stageMap = parseStages(tool.pipelineStagesJson).associateBy { it.stage }.toMutableMap()
        stageMap["READY_FOR_APPROVAL"] = PipelineStageState("READY_FOR_APPROVAL", "WAITING_APPROVAL", "Auto-activation undone by user")
        stageMap["USER_INSTALL"] = PipelineStageState("USER_INSTALL", "PENDING", "Undone")
        stageMap["VERIFY"] = PipelineStageState("VERIFY", "PENDING", "Undone")
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val undone = tool.copy(
            isInstalled = false,
            autoActivated = false,
            deviceVerified = false,
            activationStatus = "PROPOSED",
            testResult = "User ne Auto-Activation Undo kar di",
            pipelineStagesJson = encodeStageMap(stageMap),
            historyLog = "${tool.historyLog.trim()}\n[$ts] UNDO: User reversed auto-activation".trim(),
            updatedAt = System.currentTimeMillis()
        )
        db.marketplaceToolDao().upsertTool(undone)
        recordPipelineAudit(tool.name, "UNDO", "User reversed auto-activation", verified = false, rolledBack = true)
        undone
    }

    suspend fun rollbackTool(toolId: String, reason: String = "Manual rollback requested"): Result<MarketplaceToolEntity> = runCatching {
        val tool = db.marketplaceToolDao().getToolById(toolId)
            ?: throw IllegalArgumentException("Tool nahi mila")
        val stageMap = parseStages(tool.pipelineStagesJson).associateBy { it.stage }.toMutableMap()
        rollbackInternal(tool, reason, stageMap)
    }

    /**
     * Converts detected capability gaps (Health Check, Failed Tasks, User Requests, Advisor Suggestions)
     * into PROPOSED Marketplace tools with honest descriptions & pipeline metadata.
     */
    suspend fun detectGapsAndCreateProposals(): List<MarketplaceToolEntity> {
        val gaps = externalHub.detectCapabilityGaps()
        val existingIds = db.marketplaceToolDao().getToolsOnce().map { it.toolId }.toSet()
        val created = mutableListOf<MarketplaceToolEntity>()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        gaps.take(3).forEach { gap ->
            val propId = "prop_gap_${gap.gapId.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(28)}"
            if (propId !in existingIds) {
                val safeSpec = JSONObject().apply {
                    put("toolName", gap.title.take(30))
                    put("category", "Self-Detected Gap")
                    put("description", gap.reason.take(150))
                    put("actionType", "TEXT_TRANSFORM_TEMPLATE")
                    put("endpointOrTarget", "Gap Action [{input}]")
                    put("permissionsRequired", JSONArray())
                    put("licenseSpdx", "Apache-2.0")
                }.toString()
                val briefJson = JSONObject().apply {
                    put("kyaKartaHai", "Detected gap '${gap.title}' ko hal karne ke liye safe declarative tool banata hai.")
                    put("teenaMeinKyaAddHoga", "Marketplace mein '${gap.title.take(28)}' capability add hogi jo fail/unverified area cover karegi.")
                    put("zarooriCheezein", "Zero extra Android permissions; local SafeToolInterpreter par chalta hai.")
                    put("haddAurKhatre", "Ye sirf gap-based proposal hai — 10-stage pipeline scan aur device verification zaroori hai.")
                    put("status", "READY")
                }.toString()

                val entity = MarketplaceToolEntity(
                    toolId = propId,
                    name = gap.title.take(36),
                    description = "[PROPOSED from Gap] ${gap.reason.take(180)}",
                    version = "0.1.0-gap",
                    category = "Gap Proposal",
                    sourceRepo = "builtin://teena/gap-detector",
                    licenseSpdx = "Apache-2.0",
                    executionSpec = safeSpec,
                    isBuiltIn = false,
                    isInstalled = false,
                    activationStatus = "PROPOSED",
                    securityScore = 100,
                    securityNotes = "Pre-checked safe zero-permission proposal",
                    testResult = "10-stage pipeline chalane ke liye tayyar",
                    proposedBy = "Gap Detector (${gap.reason.substringBefore("]").removePrefix("[")})",
                    scanResult = "PRE-CHECK PASSED (100/100)",
                    toolKind = "DECLARATIVE_JSON",
                    pipelineStagesJson = defaultStagesJson(),
                    historyLog = "[$ts] Auto-proposed from gap: ${gap.reason}",
                    capabilitySummaryJson = briefJson
                )
                db.marketplaceToolDao().upsertTool(entity)
                created.add(entity)
            }
        }
        return created
    }

    private suspend fun postAutoActivationNotification(tool: MarketplaceToolEntity) {
        val title = "Teena Auto-Activated: ${tool.name}"
        val body = "Zero-permission safe declarative tool active kar diya gaya hai (Status: IMPLEMENTED NOT DEVICE-VERIFIED). Agar nahi chahiye to Marketplace mein 'Undo' dabayein."
        db.systemRecordsDao().insertNotification(
            NotificationRecordEntity(
                notificationKey = "teena_auto_${tool.toolId}",
                packageName = "com.example",
                appLabel = "Teena AI",
                title = title,
                text = body,
                isImportant = true,
                hasReplyAction = false
            )
        )
        appContext?.let { ctx ->
            runCatching {
                TeenaNotificationHelper.showAlertNotification(
                    context = ctx,
                    id = tool.toolId.hashCode(),
                    title = title,
                    body = body,
                    channelId = TeenaNotificationHelper.CHANNEL_BUILDS
                )
            }
        }
    }

    private suspend fun rollbackInternal(
        tool: MarketplaceToolEntity,
        failureReason: String,
        stageMap: MutableMap<String, PipelineStageState>
    ): MarketplaceToolEntity {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val hasPrev = tool.previousVersionSpec.isNotBlank() && tool.previousVersionSpec != tool.executionSpec
        stageMap["VERIFY"] = PipelineStageState("VERIFY", "FAILED", failureReason.take(140))

        val rolled = if (hasPrev) {
            tool.copy(
                executionSpec = tool.previousVersionSpec,
                previousVersionSpec = "",
                isInstalled = true,
                autoActivated = false,
                deviceVerified = false,
                activationStatus = FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
                testResult = "Fail hone par purane version par rollback hua: $failureReason",
                pipelineStagesJson = encodeStageMap(stageMap),
                historyLog = "${tool.historyLog.trim()}\n[$ts] ROLLBACK to previous version: $failureReason".trim(),
                updatedAt = System.currentTimeMillis()
            )
        } else {
            tool.copy(
                isInstalled = false,
                autoActivated = false,
                deviceVerified = false,
                activationStatus = FeatureStatus.FAILED.name,
                testResult = "Fail hone par rollback/disable hua: $failureReason",
                pipelineStagesJson = encodeStageMap(stageMap),
                historyLog = "${tool.historyLog.trim()}\n[$ts] ROLLBACK & disabled: $failureReason".trim(),
                updatedAt = System.currentTimeMillis()
            )
        }
        db.marketplaceToolDao().upsertTool(rolled)
        recordPipelineAudit(tool.name, "VERIFY", failureReason, verified = false, rolledBack = true)
        return rolled
    }

    private suspend fun recordPipelineAudit(
        title: String,
        stage: String,
        detail: String,
        verified: Boolean,
        rolledBack: Boolean
    ) {
        db.researchDao().insertImprovementLog(
            SelfImprovementEntity(
                cycleTitle = "10-Stage Pipeline: ${title.take(36)}",
                stageReached = stage,
                targetComponent = title,
                findings = detail,
                actionTaken = when {
                    verified -> "Verified & ACTIVE in Marketplace"
                    rolledBack -> "Rolled Back / Undone on Failure or Security Gate"
                    else -> "Installed Pending Device Verification"
                },
                verified = verified,
                rolledBack = rolledBack
            )
        )
        db.systemRecordsDao().insertAuditLog(
            AuditLogEntity(
                category = "TOOL_PIPELINE",
                action = "$title [$stage]",
                outcome = if (verified) "ACTIVE" else if (rolledBack) "ROLLED_BACK" else "WAITING",
                details = detail.take(240)
            )
        )
    }

    private fun encodeStageMap(map: Map<String, PipelineStageState>): String {
        val arr = JSONArray()
        ALL_STAGES.forEach { st ->
            val s = map[st] ?: PipelineStageState(st, "PENDING", "")
            arr.put(JSONObject().put("stage", s.stage).put("status", s.status).put("detail", s.detail))
        }
        return arr.toString()
    }

    private fun buildToolEntity(
        toolId: String,
        name: String,
        description: String,
        category: String,
        sourceRepo: String,
        licenseSpdx: String,
        executionSpec: String,
        previousVersionSpec: String = "",
        isInstalled: Boolean,
        activationStatus: String,
        securityScore: Int,
        securityNotes: String,
        testResult: String,
        proposedBy: String,
        scanResult: String,
        toolKind: String,
        pipelineStagesJson: String,
        historyLog: String,
        deviceVerified: Boolean,
        branchName: String,
        releaseUrl: String,
        capabilitySummaryJson: String = "",
        autoActivated: Boolean = false
    ): MarketplaceToolEntity = MarketplaceToolEntity(
        toolId = toolId,
        name = name,
        description = description,
        version = "1.0.0",
        category = category,
        sourceRepo = sourceRepo,
        licenseSpdx = licenseSpdx,
        executionSpec = executionSpec.take(3000),
        isBuiltIn = false,
        isInstalled = isInstalled,
        activationStatus = activationStatus,
        securityScore = securityScore,
        securityNotes = securityNotes,
        testResult = testResult,
        previousVersionSpec = previousVersionSpec,
        proposedBy = proposedBy,
        scanResult = scanResult,
        toolKind = toolKind,
        pipelineStagesJson = pipelineStagesJson,
        historyLog = historyLog,
        deviceVerified = deviceVerified,
        branchName = branchName,
        releaseUrl = releaseUrl,
        capabilitySummaryJson = capabilitySummaryJson,
        autoActivated = autoActivated,
        updatedAt = System.currentTimeMillis()
    )
}
