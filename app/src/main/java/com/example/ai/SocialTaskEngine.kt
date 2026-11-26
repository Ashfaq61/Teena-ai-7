package com.example.ai

import android.content.Context
import android.net.Uri
import com.example.core.security.EmergencyStopController
import com.example.core.security.SecurityAuditor
import com.example.data.local.AuditLogEntity
import com.example.data.local.TeenaDatabase
import com.example.system.PhoneCallEngine
import com.example.system.SocialAutomationEngine
import com.example.system.SocialPlatform
import com.example.system.TeenaAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class SocialTaskStatus {
    IDLE,
    RUNNING,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_USER,
    COMPLETED,
    SUBMITTED_UNVERIFIED,
    FAILED
}

enum class SocialTaskControl {
    CONFIRM,
    REJECT,
    RESUME
}

enum class SocialTaskKind(val displayLabel: String) {
    CREATE_POST("Create Post"),
    CREATE_REEL("Create Reel"),
    CREATE_STORY("Create Story"),
    WHATSAPP_MESSAGE("WhatsApp Message")
}

data class ResolvedTarget(
    val displayName: String,
    val phoneNumber: String,
    val resolutionNote: String
)

data class StageOutcome(
    val composerReady: Boolean,
    val needsManualUserStep: Boolean = false,
    val note: String = ""
)

data class SubmitOutcome(
    val dispatched: Boolean,
    val verifiedDelivered: Boolean,
    val details: String
)

data class SocialTaskState(
    val taskId: String = "",
    val status: SocialTaskStatus = SocialTaskStatus.IDLE,
    val platform: SocialPlatform = SocialPlatform.INSTAGRAM,
    val taskKind: SocialTaskKind = SocialTaskKind.CREATE_POST,
    val targetRecipientOrAccount: String = "",
    val captionOrMessage: String = "",
    val mediaUriString: String? = null,
    val resolvedContactInfo: String = "",
    val confirmationPrompt: String = "",
    val statusMessage: String = "No active social or WhatsApp task.",
    val errorMessage: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

interface SocialTaskDriver {
    fun isAccessibilityReady(): Boolean
    fun isSecureScreenActive(): Boolean
    suspend fun resolveWhatsAppTarget(query: String): ResolvedTarget
    suspend fun stageTaskComposer(
        platform: SocialPlatform,
        kind: SocialTaskKind,
        target: String,
        text: String,
        mediaUri: Uri?
    ): Result<StageOutcome>

    suspend fun executeConfirmedSubmit(
        platform: SocialPlatform,
        kind: SocialTaskKind,
        target: String,
        text: String,
        mediaUri: Uri?
    ): Result<SubmitOutcome>
}

class LiveSocialTaskDriver(
    private val context: Context,
    private val socialEngine: SocialAutomationEngine,
    private val phoneEngine: PhoneCallEngine
) : SocialTaskDriver {

    private val relationAliases = mapOf(
        "ammi" to listOf("ammi", "ami", "mom", "mama", "mother", "maa"),
        "papa" to listOf("papa", "abba", "abbu", "dad", "father", "baba"),
        "bhai" to listOf("bhai", "brother", "bro", "bhaiya"),
        "behen" to listOf("behen", "baji", "sister", "sis", "api", "aapi"),
        "dost" to listOf("dost", "friend", "yaar")
    )

    override fun isAccessibilityReady(): Boolean = TeenaAccessibilityService.instance != null

    override fun isSecureScreenActive(): Boolean =
        TeenaAccessibilityService.latestSnapshot.value?.isSecureWindow == true

    override suspend fun resolveWhatsAppTarget(query: String): ResolvedTarget {
        val clean = query.trim()
        if (clean.isEmpty()) {
            return ResolvedTarget("", "", "No recipient specified — user will select chat in WhatsApp.")
        }
        val digitsOnly = clean.filter { it.isDigit() || it == '+' }
        if (digitsOnly.count { it.isDigit() } >= 7) {
            return ResolvedTarget(clean, digitsOnly, "Direct phone number provided")
        }

        val lower = clean.lowercase()
        val expandedQueries = buildList {
            add(clean)
            relationAliases.forEach { (key, synonyms) ->
                if (lower == key || synonyms.any { lower.contains(it) }) {
                    addAll(synonyms)
                }
            }
        }.distinct()

        for (q in expandedQueries) {
            val contacts = phoneEngine.searchContacts(q).getOrNull().orEmpty()
            val best = contacts.firstOrNull()
            if (best != null) {
                return ResolvedTarget(
                    displayName = best.name,
                    phoneNumber = best.phoneNumber,
                    resolutionNote = "Matched contact '${best.name}' (${best.phoneNumber})"
                )
            }
        }
        return ResolvedTarget(
            displayName = clean,
            phoneNumber = "",
            resolutionNote = "Contact '$clean' not matched in Contacts (or READ_CONTACTS not granted); chooser draft will open."
        )
    }

    override suspend fun stageTaskComposer(
        platform: SocialPlatform,
        kind: SocialTaskKind,
        target: String,
        text: String,
        mediaUri: Uri?
    ): Result<StageOutcome> = runCatching {
        val installedPkg = socialEngine.resolveInstalledPackage(platform)
        if ((kind == SocialTaskKind.CREATE_REEL || kind == SocialTaskKind.CREATE_STORY) && mediaUri == null) {
            StageOutcome(
                composerReady = false,
                needsManualUserStep = true,
                note = "${kind.displayLabel} ke liye ${platform.displayName} mein media/video select karein, phir RESUME dabayein."
            )
        } else {
            val pkgLabel = installedPkg ?: "web/share composer"
            StageOutcome(
                composerReady = true,
                needsManualUserStep = false,
                note = "Staged ${kind.displayLabel} for ${platform.displayName} ($pkgLabel). Awaiting explicit CONFIRM."
            )
        }
    }

    override suspend fun executeConfirmedSubmit(
        platform: SocialPlatform,
        kind: SocialTaskKind,
        target: String,
        text: String,
        mediaUri: Uri?
    ): Result<SubmitOutcome> = runCatching {
        val a11y = TeenaAccessibilityService.instance
        if (kind == SocialTaskKind.WHATSAPP_MESSAGE) {
            val snap = a11y?.captureCurrentScreen()
            val onWhatsAppScreen = snap?.packageName?.contains("whatsapp", ignoreCase = true) == true
            if (a11y != null && onWhatsAppScreen && target.isBlank()) {
                val typed = a11y.typeTextSmart(text = text, targetHintOrText = "Message", submit = true)
                return@runCatching SubmitOutcome(
                    dispatched = typed.success,
                    verifiedDelivered = typed.verified,
                    details = typed.details
                )
            }
            val openRes = socialEngine.openWhatsAppDraft(target, text)
            return@runCatching SubmitOutcome(
                dispatched = openRes.isSuccess,
                verifiedDelivered = false,
                details = openRes.getOrElse { it.localizedMessage ?: "WhatsApp composer open failed" } +
                    " (Send button user ya Accessibility verification par depend karta hai -> SUBMITTED_UNVERIFIED)"
            )
        }

        val shareRes = socialEngine.shareCaptionOrPost(platform, text, mediaUri)
        if (shareRes.isFailure) {
            throw shareRes.exceptionOrNull() ?: IllegalStateException("Could not open ${platform.displayName} composer.")
        }
        val a11ySnap = a11y?.waitForScreenStability(minSettleMs = 650L, maxWaitMs = 1800L)
        val switchedToTarget = a11ySnap?.packageName == socialEngine.resolveInstalledPackage(platform)
        SubmitOutcome(
            dispatched = true,
            verifiedDelivered = false,
            details = "${shareRes.getOrNull().orEmpty()} " +
                if (switchedToTarget) "Composer opened in ${platform.displayName}; final tap user confirmation ke baad hoga."
                else "Share composer opened cleanly (delivery unverified)."
        )
    }
}

class SocialTaskEngine(
    private val db: TeenaDatabase? = null,
    private val emergencyStopController: EmergencyStopController? = null,
    private val driver: SocialTaskDriver
) {
    private val mutex = Mutex()
    private val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SocialTaskState())
    val state: StateFlow<SocialTaskState> = _state.asStateFlow()

    suspend fun startSocialTask(
        platform: SocialPlatform,
        taskKind: SocialTaskKind,
        caption: String,
        mediaUriString: String? = null,
        targetAccount: String = "",
        requireManualUserPrep: Boolean = false
    ): SocialTaskState = mutex.withLock {
        val taskId = "social_${UUID.randomUUID().toString().take(8)}"
        if (emergencyStopController?.isEmergencyStopped?.value == true) {
            return@withLock updateAndAudit(
                SocialTaskState(
                    taskId = taskId,
                    status = SocialTaskStatus.FAILED,
                    platform = platform,
                    taskKind = taskKind,
                    captionOrMessage = caption,
                    errorMessage = "Emergency Stop active — social automation halted.",
                    statusMessage = "Blocked by Emergency Stop."
                ),
                auditAction = "Social task blocked (${taskKind.name})",
                auditOutcome = "BLOCKED"
            )
        }

        if (driver.isSecureScreenActive()) {
            return@withLock updateAndAudit(
                SocialTaskState(
                    taskId = taskId,
                    status = SocialTaskStatus.FAILED,
                    platform = platform,
                    taskKind = taskKind,
                    captionOrMessage = caption,
                    errorMessage = "Secure/password screen active — social task refused.",
                    statusMessage = "Blocked on secure screen."
                ),
                auditAction = "Social task secure screen block",
                auditOutcome = "BLOCKED"
            )
        }

        val cleanCaption = caption.trim()
        if (cleanCaption.isBlank() && mediaUriString.isNullOrBlank()) {
            return@withLock updateAndAudit(
                SocialTaskState(
                    taskId = taskId,
                    status = SocialTaskStatus.FAILED,
                    platform = platform,
                    taskKind = taskKind,
                    errorMessage = "Caption ya media missing hai; empty ${taskKind.displayLabel} stage nahi ho sakta.",
                    statusMessage = "Validation failed."
                ),
                auditAction = "Social task validation failed",
                auditOutcome = "FAILED"
            )
        }

        _state.value = SocialTaskState(
            taskId = taskId,
            status = SocialTaskStatus.RUNNING,
            platform = platform,
            taskKind = taskKind,
            targetRecipientOrAccount = targetAccount.trim(),
            captionOrMessage = cleanCaption,
            mediaUriString = mediaUriString,
            statusMessage = "Staging ${taskKind.displayLabel} on ${platform.displayName}..."
        )

        val parsedUri = mediaUriString?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val stageRes = driver.stageTaskComposer(platform, taskKind, targetAccount, cleanCaption, parsedUri)
        if (stageRes.isFailure) {
            val err = SecurityAuditor.redactSecrets(stageRes.exceptionOrNull()?.localizedMessage ?: "Staging error")
            return@withLock updateAndAudit(
                _state.value.copy(
                    status = SocialTaskStatus.FAILED,
                    errorMessage = err,
                    statusMessage = "Failed to stage ${taskKind.displayLabel}: $err",
                    updatedAt = System.currentTimeMillis()
                ),
                auditAction = "Social task stage failed",
                auditOutcome = "FAILED"
            )
        }

        val outcome = stageRes.getOrThrow()
        if (requireManualUserPrep || outcome.needsManualUserStep) {
            return@withLock updateAndAudit(
                _state.value.copy(
                    status = SocialTaskStatus.WAITING_FOR_USER,
                    confirmationPrompt = "Media/screen tayyar karne ke baad RESUME dabayein.",
                    statusMessage = outcome.note.ifBlank {
                        "Waiting for user to prepare ${taskKind.displayLabel} in ${platform.displayName}; tap RESUME when ready."
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                auditAction = "Social task waiting for user (${platform.name}/${taskKind.name})",
                auditOutcome = "WAITING_FOR_USER"
            )
        }

        val confirmSummary = "${taskKind.displayLabel} on ${platform.displayName}" +
            (if (targetAccount.isNotBlank()) " (@${targetAccount.trim()})" else "") +
            ": \"${cleanCaption.take(140)}\"" +
            if (!mediaUriString.isNullOrBlank()) " + attached media" else ""

        updateAndAudit(
            _state.value.copy(
                status = SocialTaskStatus.WAITING_FOR_CONFIRMATION,
                confirmationPrompt = confirmSummary,
                statusMessage = outcome.note.ifBlank { "Ready for explicit CONFIRM before sending/posting." },
                updatedAt = System.currentTimeMillis()
            ),
            auditAction = "Social task staged (${platform.name}/${taskKind.name})",
            auditOutcome = "WAITING_FOR_CONFIRMATION"
        )
    }

    suspend fun startWhatsAppTask(
        contactOrPhone: String,
        message: String
    ): SocialTaskState = mutex.withLock {
        val taskId = "wa_${UUID.randomUUID().toString().take(8)}"
        if (emergencyStopController?.isEmergencyStopped?.value == true) {
            return@withLock updateAndAudit(
                SocialTaskState(
                    taskId = taskId,
                    status = SocialTaskStatus.FAILED,
                    platform = SocialPlatform.WHATSAPP,
                    taskKind = SocialTaskKind.WHATSAPP_MESSAGE,
                    targetRecipientOrAccount = contactOrPhone,
                    captionOrMessage = message,
                    errorMessage = "Emergency Stop active — WhatsApp task halted.",
                    statusMessage = "Blocked by Emergency Stop."
                ),
                auditAction = "WhatsApp task blocked",
                auditOutcome = "BLOCKED"
            )
        }

        val cleanMessage = message.trim()
        if (cleanMessage.isBlank()) {
            return@withLock updateAndAudit(
                SocialTaskState(
                    taskId = taskId,
                    status = SocialTaskStatus.FAILED,
                    platform = SocialPlatform.WHATSAPP,
                    taskKind = SocialTaskKind.WHATSAPP_MESSAGE,
                    targetRecipientOrAccount = contactOrPhone,
                    errorMessage = "WhatsApp message khali nahi ho sakta.",
                    statusMessage = "Validation failed: empty message."
                ),
                auditAction = "WhatsApp task validation failed",
                auditOutcome = "FAILED"
            )
        }

        _state.value = SocialTaskState(
            taskId = taskId,
            status = SocialTaskStatus.RUNNING,
            platform = SocialPlatform.WHATSAPP,
            taskKind = SocialTaskKind.WHATSAPP_MESSAGE,
            targetRecipientOrAccount = contactOrPhone.trim(),
            captionOrMessage = cleanMessage,
            statusMessage = "Resolving WhatsApp contact & staging draft..."
        )

        val resolved = driver.resolveWhatsAppTarget(contactOrPhone)
        val stageRes = driver.stageTaskComposer(
            platform = SocialPlatform.WHATSAPP,
            kind = SocialTaskKind.WHATSAPP_MESSAGE,
            target = resolved.phoneNumber.ifBlank { resolved.displayName },
            text = cleanMessage,
            mediaUri = null
        )
        if (stageRes.isFailure) {
            val err = SecurityAuditor.redactSecrets(stageRes.exceptionOrNull()?.localizedMessage ?: "WhatsApp staging error")
            return@withLock updateAndAudit(
                _state.value.copy(
                    status = SocialTaskStatus.FAILED,
                    errorMessage = err,
                    statusMessage = "WhatsApp task failed: $err",
                    updatedAt = System.currentTimeMillis()
                ),
                auditAction = "WhatsApp stage failed",
                auditOutcome = "FAILED"
            )
        }

        val recipientLabel = listOf(resolved.displayName, resolved.phoneNumber)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" • ")
            .ifBlank { "Selected WhatsApp chat" }

        updateAndAudit(
            _state.value.copy(
                status = SocialTaskStatus.WAITING_FOR_CONFIRMATION,
                targetRecipientOrAccount = resolved.phoneNumber.ifBlank { resolved.displayName },
                resolvedContactInfo = resolved.resolutionNote,
                confirmationPrompt = "WhatsApp message to $recipientLabel: \"${cleanMessage.take(140)}\"",
                statusMessage = "Draft staged. Never auto-sends — tap CONFIRM or say 'confirm' to proceed.",
                updatedAt = System.currentTimeMillis()
            ),
            auditAction = "WhatsApp task staged",
            auditOutcome = "WAITING_FOR_CONFIRMATION"
        )
    }

    suspend fun controlTask(control: SocialTaskControl): SocialTaskState = mutex.withLock {
        val current = _state.value
        when (control) {
            SocialTaskControl.CONFIRM -> {
                if (current.status != SocialTaskStatus.WAITING_FOR_CONFIRMATION) {
                    val msg = "CONFIRM ignored: current status is ${current.status.name} (expected WAITING_FOR_CONFIRMATION)."
                    return@withLock current.copy(statusMessage = msg, updatedAt = System.currentTimeMillis()).also {
                        _state.value = it
                    }
                }
                if (emergencyStopController?.isEmergencyStopped?.value == true) {
                    return@withLock updateAndAudit(
                        current.copy(
                            status = SocialTaskStatus.FAILED,
                            errorMessage = "Emergency Stop active — confirmation aborted.",
                            statusMessage = "Aborted by Emergency Stop.",
                            updatedAt = System.currentTimeMillis()
                        ),
                        auditAction = "Confirmed task aborted by Emergency Stop",
                        auditOutcome = "BLOCKED"
                    )
                }
                _state.value = current.copy(
                    status = SocialTaskStatus.RUNNING,
                    statusMessage = "Executing confirmed ${current.taskKind.displayLabel} on ${current.platform.displayName}...",
                    updatedAt = System.currentTimeMillis()
                )
                val parsedUri = current.mediaUriString?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
                val submitRes = driver.executeConfirmedSubmit(
                    platform = current.platform,
                    kind = current.taskKind,
                    target = current.targetRecipientOrAccount,
                    text = current.captionOrMessage,
                    mediaUri = parsedUri
                )
                return@withLock submitRes.fold(
                    onSuccess = { out ->
                        val nextStatus = when {
                            !out.dispatched -> SocialTaskStatus.FAILED
                            out.verifiedDelivered -> SocialTaskStatus.COMPLETED
                            else -> SocialTaskStatus.SUBMITTED_UNVERIFIED
                        }
                        updateAndAudit(
                            _state.value.copy(
                                status = nextStatus,
                                statusMessage = out.details,
                                errorMessage = if (nextStatus == SocialTaskStatus.FAILED) out.details else "",
                                updatedAt = System.currentTimeMillis()
                            ),
                            auditAction = "Confirmed ${current.platform.name}/${current.taskKind.name}",
                            auditOutcome = nextStatus.name
                        )
                    },
                    onFailure = { err ->
                        val safeErr = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Dispatch failed")
                        updateAndAudit(
                            _state.value.copy(
                                status = SocialTaskStatus.FAILED,
                                errorMessage = safeErr,
                                statusMessage = "Failed during confirmed submit: $safeErr",
                                updatedAt = System.currentTimeMillis()
                            ),
                            auditAction = "Confirmed ${current.platform.name}/${current.taskKind.name} failed",
                            auditOutcome = "FAILED"
                        )
                    }
                )
            }

            SocialTaskControl.REJECT -> {
                if (current.status == SocialTaskStatus.IDLE) {
                    return@withLock current
                }
                return@withLock updateAndAudit(
                    current.copy(
                        status = SocialTaskStatus.FAILED,
                        errorMessage = "Rejected by user — nothing was sent or posted.",
                        statusMessage = "Task rejected by user. Safe: no outbound send/post occurred.",
                        updatedAt = System.currentTimeMillis()
                    ),
                    auditAction = "User rejected ${current.platform.name}/${current.taskKind.name}",
                    auditOutcome = "REJECTED"
                )
            }

            SocialTaskControl.RESUME -> {
                if (current.status != SocialTaskStatus.WAITING_FOR_USER) {
                    val msg = "RESUME ignored: current status is ${current.status.name} (expected WAITING_FOR_USER)."
                    return@withLock current.copy(statusMessage = msg, updatedAt = System.currentTimeMillis()).also {
                        _state.value = it
                    }
                }
                _state.value = current.copy(
                    status = SocialTaskStatus.RUNNING,
                    statusMessage = "Resuming ${current.taskKind.displayLabel} check...",
                    updatedAt = System.currentTimeMillis()
                )
                if (driver.isSecureScreenActive()) {
                    return@withLock updateAndAudit(
                        _state.value.copy(
                            status = SocialTaskStatus.FAILED,
                            errorMessage = "Secure screen active on resume.",
                            statusMessage = "Blocked on secure screen.",
                            updatedAt = System.currentTimeMillis()
                        ),
                        auditAction = "Resume blocked by secure screen",
                        auditOutcome = "BLOCKED"
                    )
                }
                val confirmSummary = "${current.taskKind.displayLabel} on ${current.platform.displayName}: \"${current.captionOrMessage.take(140)}\""
                return@withLock updateAndAudit(
                    _state.value.copy(
                        status = SocialTaskStatus.WAITING_FOR_CONFIRMATION,
                        confirmationPrompt = confirmSummary,
                        statusMessage = "User step resumed cleanly — tap CONFIRM to execute or REJECT to cancel.",
                        updatedAt = System.currentTimeMillis()
                    ),
                    auditAction = "Resumed ${current.platform.name}/${current.taskKind.name} -> waiting confirmation",
                    auditOutcome = "WAITING_FOR_CONFIRMATION"
                )
            }
        }
    }

    suspend fun handleWhatsAppTaskControl(
        action: String,
        contactOrPhone: String = "",
        message: String = ""
    ): Pair<String, SocialTaskState> {
        return when (action.trim().uppercase()) {
            "START" -> {
                val st = startWhatsAppTask(contactOrPhone, message)
                formatToolStatus(st) to st
            }
            "CONFIRM" -> {
                val st = controlTask(SocialTaskControl.CONFIRM)
                formatToolStatus(st) to st
            }
            "REJECT", "CANCEL" -> {
                val st = controlTask(SocialTaskControl.REJECT)
                formatToolStatus(st) to st
            }
            "RESUME" -> {
                val st = controlTask(SocialTaskControl.RESUME)
                formatToolStatus(st) to st
            }
            "STATUS" -> {
                val st = _state.value
                formatToolStatus(st) to st
            }
            else -> {
                val st = _state.value
                "Unsupported whatsapp_task_control action '$action'. Use START, CONFIRM, REJECT, RESUME, or STATUS." to st
            }
        }
    }

    suspend fun handleSocialTaskControl(
        action: String,
        platformName: String = "",
        taskTypeName: String = "",
        caption: String = ""
    ): Pair<String, SocialTaskState> {
        return when (action.trim().uppercase()) {
            "START" -> {
                val plat = SocialPlatform.entries.firstOrNull {
                    it.name.equals(platformName.trim(), ignoreCase = true) ||
                        it.displayName.equals(platformName.trim(), ignoreCase = true)
                } ?: SocialPlatform.INSTAGRAM
                val kind = SocialTaskKind.entries.firstOrNull {
                    it.name.equals(taskTypeName.trim(), ignoreCase = true) ||
                        it.displayLabel.equals(taskTypeName.trim(), ignoreCase = true)
                } ?: SocialTaskKind.CREATE_POST
                val st = startSocialTask(platform = plat, taskKind = kind, caption = caption)
                formatToolStatus(st) to st
            }
            "CONFIRM" -> {
                val st = controlTask(SocialTaskControl.CONFIRM)
                formatToolStatus(st) to st
            }
            "REJECT", "CANCEL" -> {
                val st = controlTask(SocialTaskControl.REJECT)
                formatToolStatus(st) to st
            }
            "RESUME" -> {
                val st = controlTask(SocialTaskControl.RESUME)
                formatToolStatus(st) to st
            }
            "STATUS" -> {
                val st = _state.value
                formatToolStatus(st) to st
            }
            else -> {
                val st = _state.value
                "Unsupported social_task_control action '$action'." to st
            }
        }
    }

    suspend fun resetToIdle(): SocialTaskState = mutex.withLock {
        val fresh = SocialTaskState()
        _state.value = fresh
        fresh
    }

    private fun updateAndAudit(
        next: SocialTaskState,
        auditAction: String,
        auditOutcome: String
    ): SocialTaskState {
        _state.value = next
        val dao = db?.systemRecordsDao()
        if (dao != null) {
            auditScope.launch {
                runCatching {
                    dao.insertAuditLog(
                        AuditLogEntity(
                            category = "SOCIAL_AUTOMATION",
                            action = auditAction.take(120),
                            outcome = auditOutcome,
                            details = next.statusMessage.take(200)
                        )
                    )
                }
            }
        }
        return next
    }

    private fun formatToolStatus(st: SocialTaskState): String =
        "[${st.status.name}] ${st.platform.displayName} ${st.taskKind.displayLabel}: ${st.statusMessage}" +
            if (st.confirmationPrompt.isNotBlank() && st.status == SocialTaskStatus.WAITING_FOR_CONFIRMATION) {
                " | Confirm exact action: ${st.confirmationPrompt}"
            } else ""
}
