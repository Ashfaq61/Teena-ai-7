package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import com.example.ai.ErrorDoctorEngine
import com.example.ai.ErrorDoctorPatchProposal
import com.example.ai.ErrorDoctorReport
import com.example.ai.GeminiRepository
import com.example.core.TeenaServiceContainer
import com.example.core.security.SecurityAuditor
import com.example.data.local.BuildRecordEntity
import com.example.data.local.ErrorDoctorFixEntity
import com.example.data.remote.GitHubPushSummary
import com.example.data.remote.GitHubWorkflowStepStatus
import com.example.system.BuiltApkInfo
import com.example.system.DevApkBuildManager
import com.example.system.DevBuildMode
import com.example.system.DevBuildType
import com.example.system.DevKeystoreStatus
import com.example.system.DevStudioEngine
import com.example.system.LocalBuildPreflightReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class DevApkBuildPhase {
    IDLE,
    PREPARING_PROJECT,
    CREATING_REPO,
    PUSHING_FILES,
    DISPATCHING,
    QUEUED,
    RUNNING,
    DOWNLOADING_APK,
    VERIFYING_APK,
    LOCAL_PREFLIGHT,
    LOCAL_BUILDING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class DevApkBuildUiState(
    val phase: DevApkBuildPhase = DevApkBuildPhase.IDLE,
    val mode: DevBuildMode = DevBuildMode.CLOUD_GITHUB,
    val buildType: DevBuildType = DevBuildType.DEBUG,
    val statusMessage: String = "Tap 'Build APK' to compile via free GitHub Actions.",
    val errorMessage: String? = null,
    val elapsedSeconds: Long = 0L,
    val activeRunId: Long? = null,
    val workflowHtmlUrl: String = "",
    val steps: List<GitHubWorkflowStepStatus> = emptyList(),
    val pushSummary: GitHubPushSummary? = null,
    val builtApk: BuiltApkInfo? = null,
    val unknownAppsPermissionMissing: Boolean = false,
    val errorDoctorReport: ErrorDoctorReport? = null,
    val isAskingGeminiDoctor: Boolean = false,
    val fixAttemptNumber: Int = 0,
    val localPreflight: LocalBuildPreflightReport? = null,
    val keystoreStatus: DevKeystoreStatus = DevKeystoreStatus(
        isGenerated = false,
        alias = DevApkBuildManager.DEFAULT_KEY_ALIAS,
        certSha256 = "Not generated yet",
        sizeBytes = 0L,
        warning = DevApkBuildManager.KEYSTORE_LOSS_WARNING
    ),
    val githubTokenTestResult: String? = null,
    val githubTokenTestSucceeded: Boolean? = null,
    val isTestingGithubToken: Boolean = false,
    val hasGithubTokenSaved: Boolean = false,
    val maskedGithubToken: String = "Not set"
) {
    val isActive: Boolean
        get() = phase in setOf(
            DevApkBuildPhase.PREPARING_PROJECT,
            DevApkBuildPhase.CREATING_REPO,
            DevApkBuildPhase.PUSHING_FILES,
            DevApkBuildPhase.DISPATCHING,
            DevApkBuildPhase.QUEUED,
            DevApkBuildPhase.RUNNING,
            DevApkBuildPhase.DOWNLOADING_APK,
            DevApkBuildPhase.VERIFYING_APK,
            DevApkBuildPhase.LOCAL_PREFLIGHT,
            DevApkBuildPhase.LOCAL_BUILDING
        )
}

/**
 * Coordinates Phase 11D (Cloud & Local APK Build) and Phase 11E (Error Doctor)
 * without bloating TeenaViewModel.kt.
 */
class DevStudioApkCoordinator(
    private val appContext: Context,
    private val container: TeenaServiceContainer,
    private val scope: CoroutineScope,
    private val onBanner: (String) -> Unit
) {
    private val db = container.database
    private val prefs = container.preferences
    private val vault = container.vault
    private val ghClient = container.gitHubActionsBuildClient
    private val buildManager = container.devApkBuildManager
    private val devEngine = container.devStudioEngine
    private val errorDoctor = container.errorDoctorEngine

    private val _uiState = MutableStateFlow(DevApkBuildUiState())
    val uiState: StateFlow<DevApkBuildUiState> = _uiState.asStateFlow()

    private var activeBuildJob: Job? = null
    private var elapsedTickerJob: Job? = null
    private var activeTokenTestJob: Job? = null
    private var activeDoctorJob: Job? = null

    private var activeOwnerSnapshot: String = ""
    private var activeRepoSnapshot: String = ""

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        val hasToken = vault.hasSecret(GITHUB_TOKEN_SLOT)
        val masked = if (hasToken) vault.maskedPreview(GITHUB_TOKEN_SLOT) else "Not set"
        val ks = buildManager.getCurrentKeystoreStatus()
        val unknownMissing = !buildManager.canRequestPackageInstalls()
        _uiState.value = _uiState.value.copy(
            hasGithubTokenSaved = hasToken,
            maskedGithubToken = masked,
            keystoreStatus = ks,
            unknownAppsPermissionMissing = unknownMissing
        )
        scope.launch {
            val s = prefs.getSettingsSnapshot()
            val mode = if (s.devBuildMode == DevBuildMode.LOCAL_ON_DEVICE.name) {
                DevBuildMode.LOCAL_ON_DEVICE
            } else DevBuildMode.CLOUD_GITHUB
            val type = if (s.devBuildType == DevBuildType.RELEASE.name) {
                DevBuildType.RELEASE
            } else DevBuildType.DEBUG
            val preflight = buildManager.runLocalBuildPreflight(s.devLocalAapt2Path)
            _uiState.value = _uiState.value.copy(
                mode = mode,
                buildType = type,
                localPreflight = preflight
            )
        }
    }

    fun selectBuildMode(mode: DevBuildMode) {
        _uiState.value = _uiState.value.copy(mode = mode, errorMessage = null)
        scope.launch {
            prefs.updateSettings { it.copy(devBuildMode = mode.name) }
            if (mode == DevBuildMode.LOCAL_ON_DEVICE) {
                val s = prefs.getSettingsSnapshot()
                val preflight = buildManager.runLocalBuildPreflight(s.devLocalAapt2Path)
                _uiState.value = _uiState.value.copy(localPreflight = preflight)
            }
        }
    }

    fun selectBuildType(type: DevBuildType) {
        _uiState.value = _uiState.value.copy(buildType = type)
        scope.launch { prefs.updateSettings { it.copy(devBuildType = type.name) } }
    }

    fun setAutoBumpVersion(enabled: Boolean) {
        scope.launch { prefs.updateSettings { it.copy(devAutoBumpVersion = enabled) } }
    }

    fun setPrivateRepo(isPrivate: Boolean) {
        scope.launch { prefs.updateSettings { it.copy(githubPrivateRepo = isPrivate) } }
    }

    fun saveGitHubConfig(owner: String, repo: String, isPrivate: Boolean, tokenInput: String) {
        scope.launch {
            prefs.updateSettings {
                it.copy(
                    githubOwner = owner.trim(),
                    githubRepo = repo.trim(),
                    githubPrivateRepo = isPrivate
                )
            }
            if (tokenInput.isNotBlank()) {
                vault.saveSecret(GITHUB_TOKEN_SLOT, tokenInput.trim())
            }
            refreshStatus()
            onBanner("GitHub config encrypted & saved in Keystore.")
        }
    }

    fun testGitHubToken(overrideDraftToken: String = "") {
        activeTokenTestJob?.cancel()
        activeTokenTestJob = scope.launch {
            _uiState.value = _uiState.value.copy(
                isTestingGithubToken = true,
                githubTokenTestResult = "Testing GitHub token...",
                githubTokenTestSucceeded = null
            )
            val token = overrideDraftToken.trim().ifBlank {
                vault.readSecret(GITHUB_TOKEN_SLOT).orEmpty()
            }
            val res = ghClient.testGitHubToken(token)
            res.onSuccess { info ->
                _uiState.value = _uiState.value.copy(
                    isTestingGithubToken = false,
                    githubTokenTestSucceeded = true,
                    githubTokenTestResult = info.message
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isTestingGithubToken = false,
                    githubTokenTestSucceeded = false,
                    githubTokenTestResult = SecurityAuditor.redactSecrets(
                        err.message ?: "GitHub token test failed."
                    )
                )
            }
        }
    }

    fun generateOrVerifyKeystore() {
        scope.launch {
            buildManager.ensureProjectSigningKeystore()
                .onSuccess { st ->
                    _uiState.value = _uiState.value.copy(keystoreStatus = st)
                    onBanner("Signing Keystore ready (SHA-256 ${st.certSha256.take(18)}...).")
                }
                .onFailure { err ->
                    onBanner("Keystore error: ${err.localizedMessage}")
                }
        }
    }

    fun exportKeystoreToUri(uri: Uri) {
        buildManager.exportKeystoreToUri(uri)
            .onSuccess { onBanner(it) }
            .onFailure { onBanner(it.localizedMessage ?: "Export failed") }
    }

    fun startBuildApk(attemptNumber: Int = 0) {
        if (container.emergencyStopController.isStopped()) {
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.FAILED,
                statusMessage = "Blocked: Emergency Stop active.",
                errorMessage = "Emergency Stop active hai. Top bar mein Resume tap karein."
            )
            return
        }
        activeBuildJob?.cancel()
        elapsedTickerJob?.cancel()

        val startMs = System.currentTimeMillis()
        elapsedTickerJob = scope.launch {
            while (isActive) {
                _uiState.value = _uiState.value.copy(
                    elapsedSeconds = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
                )
                delay(1000L)
            }
        }

        activeBuildJob = scope.launch {
            try {
                val s = prefs.getSettingsSnapshot()
                val mode = if (s.devBuildMode == DevBuildMode.LOCAL_ON_DEVICE.name) {
                    DevBuildMode.LOCAL_ON_DEVICE
                } else DevBuildMode.CLOUD_GITHUB
                val buildType = if (s.devBuildType == DevBuildType.RELEASE.name) {
                    DevBuildType.RELEASE
                } else DevBuildType.DEBUG

                // Version bump on fresh build (not on Error Doctor retry)
                var vCode = s.devVersionCode.coerceAtLeast(1)
                var vName = s.devVersionName.ifBlank { "1.0.0" }
                if (attemptNumber == 0 && s.devAutoBumpVersion) {
                    vCode += 1
                    vName = DevApkBuildManager.bumpVersionNamePatch(vName)
                    prefs.updateSettings { it.copy(devVersionCode = vCode, devVersionName = vName) }
                }

                // Ensure signing keystore on first use
                val ks = buildManager.ensureProjectSigningKeystore().getOrNull()
                if (ks != null) {
                    _uiState.value = _uiState.value.copy(keystoreStatus = ks)
                }

                val wsDir = devEngine.workspaceDirectory()
                _uiState.value = _uiState.value.copy(
                    phase = DevApkBuildPhase.PREPARING_PROJECT,
                    mode = mode,
                    buildType = buildType,
                    statusMessage = "Preparing Android project & workflow (v$vName / $vCode)...",
                    errorMessage = null,
                    builtApk = null,
                    steps = emptyList(),
                    activeRunId = null,
                    fixAttemptNumber = attemptNumber,
                    errorDoctorReport = if (attemptNumber == 0) null else _uiState.value.errorDoctorReport
                )

                val projectTitle = s.githubRepo.ifBlank { "TeenaDevApp" }
                buildManager.ensureAndroidGradleProjectInWorkspace(
                    workspaceDir = wsDir,
                    appName = projectTitle,
                    versionCode = vCode,
                    versionName = vName,
                    buildType = buildType,
                    includeGitHubRelease = (buildType == DevBuildType.RELEASE)
                )
                devEngine.refreshStatusSnapshot()

                if (mode == DevBuildMode.LOCAL_ON_DEVICE) {
                    runLocalOnDeviceBuild(wsDir, vName, vCode, buildType, startMs, s.devLocalAapt2Path)
                } else {
                    runCloudGitHubBuild(
                        wsDir = wsDir,
                        owner = s.githubOwner.trim(),
                        repo = s.githubRepo.trim(),
                        isPrivate = s.githubPrivateRepo,
                        buildType = buildType,
                        versionName = vName,
                        versionCode = vCode,
                        certSha = ks?.certSha256.orEmpty(),
                        startMs = startMs,
                        attemptNumber = attemptNumber
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    phase = DevApkBuildPhase.CANCELLED,
                    statusMessage = "Build cancelled by user."
                )
                throw ce
            } catch (e: Exception) {
                val safeErr = SecurityAuditor.redactSecrets(e.message ?: "Build failed.")
                _uiState.value = _uiState.value.copy(
                    phase = DevApkBuildPhase.FAILED,
                    statusMessage = "Build failed.",
                    errorMessage = safeErr
                )
            } finally {
                elapsedTickerJob?.cancel()
            }
        }
    }

    private suspend fun runCloudGitHubBuild(
        wsDir: File,
        owner: String,
        repo: String,
        isPrivate: Boolean,
        buildType: DevBuildType,
        versionName: String,
        versionCode: Int,
        certSha: String,
        startMs: Long,
        attemptNumber: Int
    ) {
        val token = vault.readSecret(GITHUB_TOKEN_SLOT)?.trim().orEmpty()
        if (owner.isBlank() || repo.isBlank() || token.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.FAILED,
                statusMessage = "GitHub config incomplete.",
                errorMessage = "GitHub username, repo name aur encrypted token teeno zaroori hain. Upar GitHub Settings card mein save karein."
            )
            return
        }
        activeOwnerSnapshot = owner
        activeRepoSnapshot = repo

        // 1. Create repo if missing
        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.CREATING_REPO,
            statusMessage = "Checking/creating GitHub repository $owner/$repo..."
        )
        val repoRes = ghClient.ensureRepositoryExists(owner, repo, isPrivate, token).getOrElse { err ->
            failBuild(err, DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs)
            return
        }

        // 2. Push ONLY changed files via REST API
        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.PUSHING_FILES,
            statusMessage = "Scanning changed workspace files...",
            workflowHtmlUrl = repoRes.htmlUrl
        )
        val filesMap = buildManager.collectPushableWorkspaceFiles(wsDir)
        val pushSummary = ghClient.pushChangedFiles(
            owner = owner,
            repo = repo,
            branch = repoRes.defaultBranch,
            files = filesMap,
            token = token
        ) { idx, total, path ->
            _uiState.value = _uiState.value.copy(
                statusMessage = "Pushing changed file $idx/$total: $path"
            )
        }.getOrElse { err ->
            failBuild(err, DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs)
            return
        }

        // 3. Trigger workflow_dispatch
        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.DISPATCHING,
            pushSummary = pushSummary,
            statusMessage = "Pushed ${pushSummary.pushedFilesCount} changed file(s) (${pushSummary.skippedUnchangedCount} unchanged). Starting GitHub Actions..."
        )
        ghClient.triggerWorkflowDispatch(
            owner = owner,
            repo = repo,
            workflowFileName = DevApkBuildManager.WORKFLOW_FILE_NAME,
            branch = repoRes.defaultBranch,
            token = token
        ).getOrElse { err ->
            failBuild(err, DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs)
            return
        }

        // 4. Find the dispatched run & poll status/steps every 3s
        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.QUEUED,
            statusMessage = "Workflow dispatched — waiting for GitHub Actions runner..."
        )
        var runId: Long? = null
        for (probe in 1..6) {
            delay(2200L)
            val latest = ghClient.findLatestDispatchedRun(owner, repo, DevApkBuildManager.WORKFLOW_FILE_NAME, token).getOrNull()
            if (latest != null) {
                runId = latest.runId
                _uiState.value = _uiState.value.copy(
                    activeRunId = latest.runId,
                    workflowHtmlUrl = latest.htmlUrl
                )
                break
            }
        }
        val activeId = runId ?: run {
            failBuild(
                IllegalStateException("Workflow run queue mein nahi mila. GitHub Actions tab check karein."),
                DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs
            )
            return
        }

        var finalSnapshot = ghClient.fetchRunWithSteps(owner, repo, activeId, token).getOrNull()
        while (finalSnapshot == null || finalSnapshot.status != "completed") {
            delay(3000L)
            val snap = ghClient.fetchRunWithSteps(owner, repo, activeId, token).getOrElse { err ->
                failBuild(err, DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs)
                return
            }
            finalSnapshot = snap
            val nextPhase = if (snap.status == "queued" || snap.status == "waiting") {
                DevApkBuildPhase.QUEUED
            } else DevApkBuildPhase.RUNNING
            val runningStep = snap.steps.firstOrNull { it.status == "in_progress" }?.name
                ?: snap.steps.lastOrNull { it.status == "completed" }?.name
                ?: snap.status
            _uiState.value = _uiState.value.copy(
                phase = nextPhase,
                steps = snap.steps,
                workflowHtmlUrl = snap.htmlUrl,
                statusMessage = "GitHub Actions: ${snap.status.uppercase()} • $runningStep"
            )
        }

        _uiState.value = _uiState.value.copy(steps = finalSnapshot.steps)

        if (finalSnapshot.conclusion == "cancelled") {
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.CANCELLED,
                statusMessage = "Workflow run #$activeId cancelled."
            )
            return
        }

        if (finalSnapshot.conclusion == "success") {
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.DOWNLOADING_APK,
                statusMessage = "Build succeeded! Downloading APK artifact ZIP..."
            )
            val artifacts = ghClient.listRunArtifacts(owner, repo, activeId, token).getOrElse { err ->
                failBuild(err, DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs)
                return
            }
            val apkArtifact = artifacts.firstOrNull { !it.expired && it.name.contains("apk", ignoreCase = true) }
                ?: artifacts.firstOrNull { !it.expired }
            if (apkArtifact == null) {
                failBuild(
                    IllegalStateException("Build pass hui par koi downloadable APK artifact nahi mila."),
                    DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs
                )
                return
            }
            val zipDest = File(buildManager.apkBuildsDirectory(), "artifact_$activeId.zip")
            ghClient.downloadArtifactZip(apkArtifact.archiveDownloadUrl, zipDest, token).getOrElse { err ->
                failBuild(err, DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs)
                return
            }

            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.VERIFYING_APK,
                statusMessage = "Unzipping & verifying APK signature..."
            )
            val builtApk = buildManager.extractApkFromArtifactZip(zipDest, activeId).getOrElse { err ->
                failBuild(err, DevBuildMode.CLOUD_GITHUB, buildType, versionName, versionCode, certSha, startMs)
                return
            }
            runCatching { zipDest.delete() }

            val elapsed = System.currentTimeMillis() - startMs
            db.systemRecordsDao().insertBuildRecord(
                BuildRecordEntity(
                    versionName = builtApk.versionName,
                    versionCode = builtApk.versionCode,
                    buildSource = "DEV_STUDIO_CLOUD",
                    status = "SUCCESS",
                    sha256Checksum = builtApk.sha256,
                    signingCertSha256 = certSha,
                    apkSizeBytes = builtApk.sizeBytes,
                    logs = "Cloud APK built via $owner/$repo (run #$activeId, pushed ${pushSummary.pushedFilesCount} file(s)).",
                    downloadUrl = finalSnapshot.htmlUrl,
                    buildMode = DevBuildMode.CLOUD_GITHUB.name,
                    buildType = buildType.name,
                    apkLocalPath = builtApk.apkFile.absolutePath,
                    apkFileName = builtApk.fileName,
                    elapsedMs = elapsed,
                    workflowRunId = activeId
                )
            )
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.SUCCESS,
                builtApk = builtApk,
                unknownAppsPermissionMissing = !buildManager.canRequestPackageInstalls(),
                statusMessage = "APK ready: ${builtApk.fileName} (${DevStudioEngine.formatBytes(builtApk.sizeBytes)})",
                errorDoctorReport = null
            )
            onBanner("APK ready: ${builtApk.fileName}")
        } else {
            // Failure -> download full logs and open Error Doctor (11E)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Build failed (${finalSnapshot.conclusion}). Downloading logs for Error Doctor..."
            )
            val rawLog = ghClient.downloadAndExtractRunLogs(owner, repo, activeId, token)
                .getOrElse { "Could not download workflow logs: ${it.localizedMessage}" }
            val fixHistory = db.errorDoctorFixDao().getAllFixesOnce()
            val doctorReport = errorDoctor.analyzeGradleFailure(
                rawLog = rawLog,
                workspaceDir = wsDir,
                fixHistory = fixHistory,
                attemptNumber = attemptNumber + 1
            )
            val elapsed = System.currentTimeMillis() - startMs
            db.systemRecordsDao().insertBuildRecord(
                BuildRecordEntity(
                    versionName = versionName,
                    versionCode = versionCode,
                    buildSource = "DEV_STUDIO_CLOUD",
                    status = "FAILED",
                    sha256Checksum = "N/A",
                    signingCertSha256 = certSha,
                    apkSizeBytes = 0L,
                    logs = rawLog.takeLast(6000),
                    downloadUrl = finalSnapshot.htmlUrl,
                    buildMode = DevBuildMode.CLOUD_GITHUB.name,
                    buildType = buildType.name,
                    elapsedMs = elapsed,
                    workflowRunId = activeId,
                    errorSummary = doctorReport.summaryHinglish
                )
            )
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.FAILED,
                fixAttemptNumber = attemptNumber + 1,
                errorDoctorReport = doctorReport,
                statusMessage = "Build failed — Error Doctor ready (Attempt ${attemptNumber + 1}/${ErrorDoctorEngine.MAX_ATTEMPTS}).",
                errorMessage = doctorReport.summaryHinglish
            )
        }
    }

    private suspend fun runLocalOnDeviceBuild(
        wsDir: File,
        versionName: String,
        versionCode: Int,
        buildType: DevBuildType,
        startMs: Long,
        aapt2Path: String
    ) {
        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.LOCAL_PREFLIGHT,
            statusMessage = "Running local on-device pre-flight checks (6 GB storage, RAM, ABI, battery, aapt2)..."
        )
        buildManager.applyLowMemoryGradleProperties(wsDir, aapt2Path)
        val preflight = buildManager.runLocalBuildPreflight(aapt2Path)
        _uiState.value = _uiState.value.copy(localPreflight = preflight)

        if (!preflight.canAttemptLocalBuild) {
            val reason = buildString {
                appendLine("Local on-device build abhi start nahi ho sakta:")
                preflight.warnings.forEach { appendLine("• $it") }
                append("Free Cloud Build (GitHub Actions) try karne ke liye 'Use Cloud Build' tap karein.")
            }
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.FAILED,
                statusMessage = "Local pre-flight failed — Cloud Build recommended.",
                errorMessage = reason
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.LOCAL_BUILDING,
            statusMessage = "Local Gradle build chal rahi hai (${preflight.etaNote})..."
        )
        val gradleBin = File(buildManager.toolchainDirectory(), "gradle/bin/gradle").absolutePath
        val jdkHome = File(buildManager.toolchainDirectory(), "jdk17").absolutePath
        val sdkHome = File(buildManager.toolchainDirectory(), "android-sdk").absolutePath
        val gradleUserHome = buildManager.gradleCacheDirectory().absolutePath

        val cmd = listOf(
            "/system/bin/sh",
            "-c",
            "export JAVA_HOME=\"$jdkHome\" ANDROID_SDK_ROOT=\"$sdkHome\" GRADLE_USER_HOME=\"$gradleUserHome\"; " +
                "\"$gradleBin\" ${buildType.gradleTask} --no-daemon --stacktrace"
        )
        val res = devEngine.executeSandboxProcess(cmd, timeoutSeconds = 900L)
        val elapsed = System.currentTimeMillis() - startMs

        res.onSuccess { (code, output) ->
            if (code == 0) {
                val apkOut = wsDir.walkTopDown().firstOrNull { it.isFile && it.extension == "apk" }
                if (apkOut != null) {
                    val destCopy = File(buildManager.apkBuildsDirectory(), "local_${System.currentTimeMillis()}_${apkOut.name}")
                    apkOut.copyTo(destCopy, overwrite = true)
                    val builtApk = buildManager.verifyAndInspectApkFile(destCopy).getOrNull()
                    if (builtApk != null) {
                        db.systemRecordsDao().insertBuildRecord(
                            BuildRecordEntity(
                                versionName = builtApk.versionName,
                                versionCode = builtApk.versionCode,
                                buildSource = "DEV_STUDIO_LOCAL",
                                status = "SUCCESS",
                                sha256Checksum = builtApk.sha256,
                                signingCertSha256 = _uiState.value.keystoreStatus.certSha256,
                                apkSizeBytes = builtApk.sizeBytes,
                                logs = output.takeLast(4000),
                                buildMode = DevBuildMode.LOCAL_ON_DEVICE.name,
                                buildType = buildType.name,
                                apkLocalPath = builtApk.apkFile.absolutePath,
                                apkFileName = builtApk.fileName,
                                elapsedMs = elapsed
                            )
                        )
                        _uiState.value = _uiState.value.copy(
                            phase = DevApkBuildPhase.SUCCESS,
                            builtApk = builtApk,
                            statusMessage = "Local APK ready: ${builtApk.fileName}"
                        )
                        return
                    }
                }
            }
            val fixHistory = db.errorDoctorFixDao().getAllFixesOnce()
            val doctor = errorDoctor.analyzeGradleFailure(output, wsDir, fixHistory, 1)
            db.systemRecordsDao().insertBuildRecord(
                BuildRecordEntity(
                    versionName = versionName,
                    versionCode = versionCode,
                    buildSource = "DEV_STUDIO_LOCAL",
                    status = "FAILED",
                    sha256Checksum = "N/A",
                    signingCertSha256 = _uiState.value.keystoreStatus.certSha256,
                    apkSizeBytes = 0L,
                    logs = output.takeLast(6000),
                    buildMode = DevBuildMode.LOCAL_ON_DEVICE.name,
                    buildType = buildType.name,
                    elapsedMs = elapsed,
                    errorSummary = doctor.summaryHinglish
                )
            )
            _uiState.value = _uiState.value.copy(
                phase = DevApkBuildPhase.FAILED,
                errorDoctorReport = doctor,
                statusMessage = "Local build failed (exit $code).",
                errorMessage = doctor.summaryHinglish
            )
        }.onFailure { err ->
            failBuild(err, DevBuildMode.LOCAL_ON_DEVICE, buildType, versionName, versionCode, "", startMs)
        }
    }

    private suspend fun failBuild(
        err: Throwable,
        mode: DevBuildMode,
        buildType: DevBuildType,
        versionName: String,
        versionCode: Int,
        certSha: String,
        startMs: Long
    ) {
        val safeMsg = SecurityAuditor.redactSecrets(err.message ?: "APK build fail ho gayi.")
        db.systemRecordsDao().insertBuildRecord(
            BuildRecordEntity(
                versionName = versionName,
                versionCode = versionCode,
                buildSource = if (mode == DevBuildMode.CLOUD_GITHUB) "DEV_STUDIO_CLOUD" else "DEV_STUDIO_LOCAL",
                status = "FAILED",
                sha256Checksum = "N/A",
                signingCertSha256 = certSha,
                apkSizeBytes = 0L,
                logs = safeMsg,
                buildMode = mode.name,
                buildType = buildType.name,
                elapsedMs = System.currentTimeMillis() - startMs,
                errorSummary = safeMsg
            )
        )
        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.FAILED,
            statusMessage = "Build failed.",
            errorMessage = safeMsg
        )
    }

    fun cancelActiveBuild() {
        val runId = _uiState.value.activeRunId
        val owner = activeOwnerSnapshot
        val repo = activeRepoSnapshot
        activeBuildJob?.cancel()
        elapsedTickerJob?.cancel()
        devEngine.killAllProcesses()
        if (runId != null && owner.isNotBlank() && repo.isNotBlank()) {
            val token = vault.readSecret(GITHUB_TOKEN_SLOT).orEmpty()
            if (token.isNotBlank()) {
                scope.launch { ghClient.cancelWorkflowRun(owner, repo, runId, token) }
            }
        }
        _uiState.value = _uiState.value.copy(
            phase = DevApkBuildPhase.CANCELLED,
            statusMessage = "APK build cancelled."
        )
    }

    fun askGeminiForErrorDoctorFix() {
        val report = _uiState.value.errorDoctorReport ?: return
        if (report.exhaustedAutoLoop) {
            onBanner("Max 3 auto-fix loops poore ho chuke hain.")
            return
        }
        activeDoctorJob?.cancel()
        activeDoctorJob = scope.launch {
            _uiState.value = _uiState.value.copy(isAskingGeminiDoctor = true)
            val s = prefs.getSettingsSnapshot()
            val model = GeminiRepository.normalizeModelId(s.geminiTextModel)
            val res = errorDoctor.askGeminiForPatch(report, devEngine.workspaceDirectory(), model)
            res.onSuccess { patch ->
                _uiState.value = _uiState.value.copy(
                    isAskingGeminiDoctor = false,
                    errorDoctorReport = report.copy(patchProposal = patch)
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isAskingGeminiDoctor = false,
                    errorMessage = SecurityAuditor.redactSecrets(err.message ?: "Gemini Error Doctor fail hua.")
                )
            }
        }
    }

    fun applyErrorDoctorPatchAndRebuild() {
        val report = _uiState.value.errorDoctorReport ?: return
        val proposal: ErrorDoctorPatchProposal = report.patchProposal ?: return
        if (report.attemptNumber >= ErrorDoctorEngine.MAX_ATTEMPTS) {
            onBanner("Max ${ErrorDoctorEngine.MAX_ATTEMPTS} fix attempts poore ho chuke hain.")
            return
        }
        scope.launch {
            errorDoctor.applyPatchProposal(devEngine.workspaceDirectory(), proposal)
                .onSuccess {
                    devEngine.refreshStatusSnapshot()
                    val sig = report.errorLines.firstOrNull()?.errorSignature
                        ?: "${proposal.targetRelativePath}:${proposal.lineNumber}"
                    val existing = db.errorDoctorFixDao().findBySignature(sig)
                    db.errorDoctorFixDao().upsertFix(
                        ErrorDoctorFixEntity(
                            id = existing?.id ?: 0L,
                            errorSignature = sig,
                            filePath = proposal.targetRelativePath,
                            lineNumber = proposal.lineNumber,
                            hinglishCause = proposal.hinglishSummary,
                            fixStrategy = proposal.strategy,
                            patchSummary = proposal.hinglishSummary,
                            patchReplacement = proposal.replacementSnippet.take(1000),
                            appliedCount = (existing?.appliedCount ?: 0) + 1,
                            lastSuccess = true
                        )
                    )
                    onBanner("Patch applied to ${proposal.targetRelativePath}. Re-building (Attempt ${report.attemptNumber + 1}/3)...")
                    startBuildApk(attemptNumber = report.attemptNumber)
                }
                .onFailure { err ->
                    onBanner("Patch apply failed: ${err.localizedMessage}")
                }
        }
    }

    fun installBuiltApk(apkFile: File? = _uiState.value.builtApk?.apkFile) {
        val file = apkFile ?: return
        if (!file.exists()) {
            onBanner("APK file device storage se delete ho chuki hai.")
            return
        }
        if (!buildManager.canRequestPackageInstalls()) {
            _uiState.value = _uiState.value.copy(unknownAppsPermissionMissing = true)
            runCatching { appContext.startActivity(buildManager.createUnknownAppsSettingsIntent()) }
            onBanner("Allow 'Install unknown apps' for Teena AI, then tap Install again.")
            return
        }
        _uiState.value = _uiState.value.copy(unknownAppsPermissionMissing = false)
        runCatching {
            appContext.startActivity(buildManager.createInstallApkIntent(file))
        }.onFailure { err ->
            onBanner("Install launch error: ${err.localizedMessage}")
        }
    }

    fun openUnknownAppsSettings() {
        runCatching {
            appContext.startActivity(buildManager.createUnknownAppsSettingsIntent())
        }.onFailure { err ->
            onBanner("Could not open Install Unknown Apps settings: ${err.localizedMessage}")
        }
    }

    fun shareBuiltApk(apkFile: File? = _uiState.value.builtApk?.apkFile) {
        val file = apkFile ?: return
        if (!file.exists()) {
            onBanner("APK file available nahi hai.")
            return
        }
        runCatching {
            appContext.startActivity(buildManager.createShareApkIntent(file))
        }.onFailure { err ->
            onBanner("Share APK error: ${err.localizedMessage}")
        }
    }

    fun reinstallFromBuildRecord(record: BuildRecordEntity) {
        if (record.apkLocalPath.isBlank()) {
            onBanner("Is build record ka local APK path saved nahi hai.")
            return
        }
        installBuiltApk(File(record.apkLocalPath))
    }

    fun deleteBuildRecord(record: BuildRecordEntity) {
        scope.launch {
            if (record.apkLocalPath.isNotBlank()) {
                runCatching { File(record.apkLocalPath).delete() }
            }
            db.systemRecordsDao().deleteBuildRecord(record.id)
            onBanner("Deleted build record v${record.versionName} (${record.versionCode}).")
        }
    }

    companion object {
        const val GITHUB_TOKEN_SLOT = "slot_github_token"
    }
}
