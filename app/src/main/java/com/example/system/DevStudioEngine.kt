package com.example.system

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.example.core.security.EmergencyStopController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

enum class DevStudioPhase {
    UNINSTALLED,
    CHECKING_PREREQUISITES,
    DOWNLOADING,
    VERIFYING_SHA256,
    EXTRACTING,
    READY,
    REPAIRING,
    ERROR
}

data class AbiCompatibility(
    val supportedAbis: List<String> = emptyList(),
    val primaryAbi: String = "unknown",
    val isSupported: Boolean = false,
    val reason: String = ""
)

data class NativeBinariesStatus(
    val nativeLibDir: String = "",
    val prootBinaryPath: String? = null,
    val hasProot: Boolean = false,
    val bridgeBinaryPath: String? = null,
    val hasNativeBridge: Boolean = false,
    val fallbackModeReason: String = ""
)

data class StorageCheckResult(
    val availableBytes: Long = 0L,
    val requiredBytes: Long = DevStudioEngine.DEFAULT_REQUIRED_FREE_BYTES,
    val hasEnoughSpace: Boolean = false,
    val formattedAvailable: String = "0 MB",
    val formattedRequired: String = "150 MB"
)

data class RootfsSpec(
    val id: String,
    val name: String,
    val abi: String,
    val version: String,
    val downloadUrl: String,
    val sha256FileUrl: String = "$downloadUrl.sha256",
    val expectedSha256: String = "",
    val requiredFreeSpaceBytes: Long = DevStudioEngine.DEFAULT_REQUIRED_FREE_BYTES
)

data class DevStudioProgress(
    val phase: DevStudioPhase = DevStudioPhase.UNINSTALLED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Int = 0,
    val message: String = "Dev Studio rootfs not installed yet.",
    val lastError: String? = null
)

data class DevStudioProcessInfo(
    val pid: String,
    val command: String,
    val startedAtMillis: Long,
    val isRunning: Boolean
)

data class SandboxFileNode(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long
)

data class DevStudioTerminalEntry(
    val id: Long,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestampMillis: Long
)

data class DevStudioEngineState(
    val phase: DevStudioPhase = DevStudioPhase.UNINSTALLED,
    val statusLabel: String = "UNINSTALLED",
    val progress: DevStudioProgress = DevStudioProgress(),
    val abi: AbiCompatibility = AbiCompatibility(),
    val nativeBinaries: NativeBinariesStatus = NativeBinariesStatus(),
    val storage: StorageCheckResult = StorageCheckResult(),
    val engineVersion: String = DevStudioEngine.ENGINE_VERSION,
    val rootfsInstalled: Boolean = false,
    val rootfsVersion: String = "",
    val rootfsSizeBytes: Long = 0L,
    val rootfsSha256: String = "",
    val bridgeStatus: DevStudioBridgeStatus = DevStudioBridgeStatus(),
    val foregroundServiceActive: Boolean = false,
    val activeProcesses: List<DevStudioProcessInfo> = emptyList(),
    val workspacePath: String = "",
    val workspaceFiles: List<SandboxFileNode> = emptyList(),
    val selectedFilePath: String = "",
    val selectedFileContent: String = "",
    val isCommandRunning: Boolean = false,
    val terminalHistory: List<DevStudioTerminalEntry> = emptyList(),
    val logs: List<String> = emptyList()
)

private data class ManagedDevProcess(
    val id: String,
    val command: String,
    val startedAtMillis: Long,
    val process: Process
)

/**
 * Phase 11A: Teena Dev Studio Core Engine.
 * - Inspects device ABI (`arm64-v8a` priority) and `nativeLibraryDir` for `libproot.so` / `libagy_ide_bridge.so`.
 * - Enforces pre-install free storage headroom via `StatFs`.
 * - Downloads compressed Linux rootfs on-demand via OkHttp with HTTP `Range` resume,
 *   SHA-256 verification, and safe pure-Kotlin `.tar.gz` extraction inside app sandbox.
 * - Provides Kotlin loopback socket IDE bridge fallback + child Process & WakeLock lifecycle management.
 */
class DevStudioEngine(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val emergencyStopController: EmergencyStopController? = null
) {
    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, ROOT_DIR_NAME)
    private val downloadsDir = File(rootDir, "downloads")
    private val rootfsDir = File(rootDir, "rootfs")
    private val workspaceDir = File(rootDir, "workspace")
    private val metaFile = File(rootDir, "rootfs_meta.json")

    private val activeProcesses = ConcurrentHashMap<String, ManagedDevProcess>()
    private val terminalBuffer = ArrayDeque<DevStudioTerminalEntry>()
    private val terminalLock = Any()
    private val logBuffer = ArrayDeque<String>()
    private val logLock = Any()

    @Volatile
    private var selectedFilePath: String = ""

    @Volatile
    private var selectedFileContent: String = ""

    @Volatile
    private var isCommandRunning: Boolean = false

    @Volatile
    private var activeInstallJob: Job? = null

    private val _state = MutableStateFlow(DevStudioEngineState())
    val state: StateFlow<DevStudioEngineState> = _state.asStateFlow()

    val bridge: DevStudioBridge = DevStudioBridge(
        workspaceDirProvider = {
            ensureSandboxDirs()
            workspaceDir
        },
        rootfsInstalledProvider = { _state.value.rootfsInstalled },
        nativeBridgePathProvider = { inspectNativeBinaries().bridgeBinaryPath },
        onLog = { line ->
            appendLog(line)
            refreshStatusSnapshot()
        }
    )

    init {
        ensureSandboxDirs()
        TeenaDevStudioService.onKillAllRequested = {
            killAllProcesses()
        }
        refreshStatusSnapshot()
    }

    fun ensureSandboxDirs() {
        rootDir.mkdirs()
        downloadsDir.mkdirs()
        rootfsDir.mkdirs()
        workspaceDir.mkdirs()
    }

    fun workspaceDirectory(): File {
        ensureSandboxDirs()
        return workspaceDir
    }

    fun refreshStatusSnapshot() {
        ensureSandboxDirs()
        val abi = evaluateAbiCompatibility(Build.SUPPORTED_ABIS?.toList().orEmpty())
        val natives = inspectNativeBinaries()
        val storage = checkAvailableStorage()
        val meta = readInstalledMeta()
        val isInstalled = meta != null && rootfsDir.exists() && (rootfsDir.listFiles()?.isNotEmpty() == true)
        val workspaceFiles = listSandboxFiles(workspaceDir)

        val currentPhase = _state.value.phase
        val effectivePhase = when {
            currentPhase in setOf(
                DevStudioPhase.CHECKING_PREREQUISITES,
                DevStudioPhase.DOWNLOADING,
                DevStudioPhase.VERIFYING_SHA256,
                DevStudioPhase.EXTRACTING,
                DevStudioPhase.REPAIRING
            ) -> currentPhase
            isInstalled -> DevStudioPhase.READY
            currentPhase == DevStudioPhase.ERROR -> DevStudioPhase.ERROR
            else -> DevStudioPhase.UNINSTALLED
        }

        val statusLabel = when (effectivePhase) {
            DevStudioPhase.READY -> "READY"
            DevStudioPhase.UNINSTALLED -> "UNINSTALLED"
            DevStudioPhase.CHECKING_PREREQUISITES -> "CHECKING"
            DevStudioPhase.DOWNLOADING -> "DOWNLOADING"
            DevStudioPhase.VERIFYING_SHA256 -> "VERIFYING"
            DevStudioPhase.EXTRACTING -> "EXTRACTING"
            DevStudioPhase.REPAIRING -> "REPAIRING"
            DevStudioPhase.ERROR -> "ERROR"
        }

        val defaultProgressMsg = when (effectivePhase) {
            DevStudioPhase.READY -> "Dev Studio sandbox ready (${meta?.version ?: "installed"})."
            DevStudioPhase.UNINSTALLED -> "Tap Install Dev Studio to fetch & verify sandbox rootfs."
            else -> _state.value.progress.message
        }

        val procs = activeProcesses.values.map {
            val running = isProcessAlive(it.process)
            DevStudioProcessInfo(
                pid = it.id,
                command = it.command,
                startedAtMillis = it.startedAtMillis,
                isRunning = running
            )
        }

        _state.value = _state.value.copy(
            phase = effectivePhase,
            statusLabel = statusLabel,
            progress = _state.value.progress.copy(
                phase = effectivePhase,
                percent = if (effectivePhase == DevStudioPhase.READY) 100 else _state.value.progress.percent,
                message = defaultProgressMsg
            ),
            abi = abi,
            nativeBinaries = natives,
            storage = storage,
            rootfsInstalled = isInstalled,
            rootfsVersion = meta?.version.orEmpty(),
            rootfsSizeBytes = meta?.sizeBytes ?: 0L,
            rootfsSha256 = meta?.sha256.orEmpty(),
            bridgeStatus = bridge.status(),
            foregroundServiceActive = TeenaDevStudioService.isActive,
            activeProcesses = procs,
            workspacePath = workspaceDir.absolutePath,
            workspaceFiles = workspaceFiles,
            selectedFilePath = selectedFilePath,
            selectedFileContent = selectedFileContent,
            isCommandRunning = isCommandRunning,
            terminalHistory = snapshotTerminalHistory(),
            logs = snapshotLogs()
        )
    }

    suspend fun installRootfs(
        customDownloadUrl: String = "",
        customExpectedSha256: String = ""
    ): Result<DevStudioEngineState> = withContext(Dispatchers.IO) {
        if (emergencyStopController?.isStopped() == true) {
            return@withContext Result.failure(IllegalStateException("Blocked: Emergency Stop is active."))
        }
        activeInstallJob = coroutineContext[Job]

        val abi = evaluateAbiCompatibility(Build.SUPPORTED_ABIS?.toList().orEmpty())
        val spec = resolveRootfsSpec(abi.primaryAbi, customDownloadUrl, customExpectedSha256)
        val storage = checkAvailableStorage(spec.requiredFreeSpaceBytes)

        updateProgress(
            DevStudioPhase.CHECKING_PREREQUISITES,
            percent = 5,
            message = "Checking ABI (${abi.primaryAbi}), free storage (${storage.formattedAvailable}), and sandbox..."
        )
        appendLog("Install started: ABI=${abi.primaryAbi}, URL=${spec.downloadUrl}")

        if (!abi.isSupported) {
            val msg = abi.reason.ifBlank { "Unsupported CPU ABI: ${abi.primaryAbi}. arm64-v8a recommended." }
            return@withContext failInstall(msg)
        }
        if (!storage.hasEnoughSpace) {
            val msg = "Low storage: need ${storage.formattedRequired} free, found ${storage.formattedAvailable}."
            return@withContext failInstall(msg)
        }

        TeenaDevStudioService.start(appContext, "Downloading Linux rootfs...")
        val wakeLock = acquireTimedWakeLock(WAKE_LOCK_TIMEOUT_MS)

        try {
            ensureSandboxDirs()
            val partFile = File(downloadsDir, "${spec.id}.tar.gz.part")
            val archiveFile = File(downloadsDir, "${spec.id}.tar.gz")

            // 1. Resolve SHA-256 checksum (user-provided or fetched .sha256 file)
            val expectedSha = if (spec.expectedSha256.isNotBlank()) {
                normalizeSha256Hex(spec.expectedSha256)
            } else {
                fetchExpectedSha256(spec.sha256FileUrl).getOrElse { "" }
            }
            if (spec.expectedSha256.isNotBlank() && normalizeSha256Hex(spec.expectedSha256).length != 64) {
                return@withContext failInstall("Invalid SHA-256 format provided (expected 64 hex chars).")
            }

            // 2. Download with HTTP Range resume support
            downloadWithResume(spec.downloadUrl, partFile).getOrElse { err ->
                return@withContext failInstall("Download failed: ${err.localizedMessage ?: "Network error"}")
            }

            // 3. Verify SHA-256 before extraction
            updateProgress(
                DevStudioPhase.VERIFYING_SHA256,
                percent = 72,
                message = "Verifying archive SHA-256 checksum..."
            )
            TeenaDevStudioService.updateNotification(appContext, "Verifying rootfs SHA-256...")
            val actualSha = computeFileSha256(partFile).getOrElse { err ->
                return@withContext failInstall("Checksum error: ${err.localizedMessage}")
            }
            if (expectedSha.isNotBlank() && !matchesSha256(actualSha, expectedSha)) {
                partFile.delete()
                return@withContext failInstall(
                    "SHA-256 verification failed (expected ${expectedSha.take(12)}..., got ${actualSha.take(12)}...). Partial download deleted; tap Auto-Repair."
                )
            }
            if (archiveFile.exists()) archiveFile.delete()
            if (!partFile.renameTo(archiveFile)) {
                partFile.copyTo(archiveFile, overwrite = true)
                partFile.delete()
            }
            appendLog("SHA-256 verified: ${actualSha.take(16)}...")

            // 4. Safe extraction inside sandbox rootfsDir
            updateProgress(
                DevStudioPhase.EXTRACTING,
                percent = 80,
                message = "Extracting rootfs into app sandbox..."
            )
            TeenaDevStudioService.updateNotification(appContext, "Extracting rootfs into sandbox...")

            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val extractedCount = extractTarGzSafely(archiveFile, rootfsDir) { pct ->
                val mapped = 80 + (pct * 18 / 100)
                updateProgress(
                    DevStudioPhase.EXTRACTING,
                    percent = mapped.coerceIn(80, 98),
                    message = "Extracting rootfs ($pct%)..."
                )
            }.getOrElse { err ->
                return@withContext failInstall("Extraction failed: ${err.localizedMessage}")
            }

            val totalExtractedBytes = directorySizeBytes(rootfsDir)
            writeInstalledMeta(
                InstalledRootfsMeta(
                    id = spec.id,
                    version = spec.version,
                    abi = abi.primaryAbi,
                    sha256 = actualSha,
                    sizeBytes = totalExtractedBytes,
                    installedAtMillis = System.currentTimeMillis()
                )
            )
            seedStarterWorkspaceIfEmpty()
            archiveFile.delete()

            val binaries = inspectNativeBinaries()
            if (!binaries.hasProot && !bridge.status().isRunning) {
                bridge.start()
            }

            updateProgress(
                DevStudioPhase.READY,
                percent = 100,
                message = "Dev Studio ready ($extractedCount files, ${formatBytes(totalExtractedBytes)}).",
                lastError = null
            )
            appendLog("Dev Studio rootfs ready ($extractedCount entries, ${formatBytes(totalExtractedBytes)}).")
            refreshStatusSnapshot()
            Result.success(_state.value)
        } catch (ce: CancellationException) {
            appendLog("Install cancelled by user.")
            updateProgress(DevStudioPhase.UNINSTALLED, percent = 0, message = "Install cancelled.")
            refreshStatusSnapshot()
            Result.failure(ce)
        } catch (e: Exception) {
            failInstall("Engine error: ${e.localizedMessage ?: "Unknown failure"}")
        } finally {
            releaseWakeLockSafely(wakeLock)
            TeenaDevStudioService.stop(appContext)
            activeInstallJob = null
        }
    }

    suspend fun autoRepair(): Result<DevStudioEngineState> = withContext(Dispatchers.IO) {
        updateProgress(DevStudioPhase.REPAIRING, percent = 15, message = "Auto-Repair: cleaning partial downloads & resetting bridge...")
        appendLog("Running Auto-Repair...")
        killAllProcesses()
        bridge.stop()
        runCatching { downloadsDir.deleteRecursively() }
        runCatching {
            if (metaFile.exists() && (rootfsDir.listFiles()?.isEmpty() != false)) {
                metaFile.delete()
            }
        }
        ensureSandboxDirs()
        seedStarterWorkspaceIfEmpty()
        bridge.start()
        updateProgress(
            phase = if (readInstalledMeta() != null) DevStudioPhase.READY else DevStudioPhase.UNINSTALLED,
            percent = if (readInstalledMeta() != null) 100 else 0,
            message = "Auto-Repair finished. Sandbox & IDE bridge healthy.",
            lastError = null
        )
        appendLog("Auto-Repair completed cleanly.")
        refreshStatusSnapshot()
        Result.success(_state.value)
    }

    suspend fun uninstallRootfs(): Result<DevStudioEngineState> = withContext(Dispatchers.IO) {
        killAllProcesses()
        bridge.stop()
        runCatching { rootfsDir.deleteRecursively() }
        runCatching { downloadsDir.deleteRecursively() }
        runCatching { metaFile.delete() }
        ensureSandboxDirs()
        updateProgress(
            phase = DevStudioPhase.UNINSTALLED,
            downloadedBytes = 0L,
            totalBytes = 0L,
            percent = 0,
            message = "Rootfs removed. Workspace files preserved.",
            lastError = null
        )
        appendLog("Rootfs uninstalled (workspace preserved).")
        refreshStatusSnapshot()
        Result.success(_state.value)
    }

    suspend fun startBridge(): Result<DevStudioBridgeStatus> = withContext(Dispatchers.IO) {
        val res = bridge.start()
        if (res.isSuccess) {
            TeenaDevStudioService.start(appContext, "IDE bridge listening on 127.0.0.1:${res.getOrThrow().port}")
        }
        refreshStatusSnapshot()
        res
    }

    fun stopBridge() {
        bridge.stop()
        if (activeProcesses.isEmpty() && activeInstallJob == null) {
            TeenaDevStudioService.stop(appContext)
        }
        appendLog("IDE Bridge stopped.")
        refreshStatusSnapshot()
    }

    suspend fun pingBridge(): Result<String> {
        val res = bridge.ping()
        res.onSuccess { appendLog("Bridge PING ok: $it") }
            .onFailure { appendLog("Bridge PING failed: ${it.localizedMessage}") }
        refreshStatusSnapshot()
        return res
    }

    fun createSandboxFile(relativePath: String, content: String): Result<SandboxFileNode> {
        return saveSandboxFile(relativePath, content)
    }

    fun readSandboxFile(relativePath: String): Result<String> {
        ensureSandboxDirs()
        val resolved = resolveSandboxPath(workspaceDir, relativePath).getOrElse {
            appendLog("Blocked unsafe read '$relativePath': ${it.localizedMessage}")
            refreshStatusSnapshot()
            return Result.failure(it)
        }
        return runCatching {
            if (!resolved.exists() || !resolved.isFile) {
                throw IOException("File not found in sandbox: $relativePath")
            }
            if (resolved.length() > MAX_EDITOR_FILE_BYTES) {
                throw IOException("File exceeds 256 KB editor preview limit")
            }
            val text = resolved.readText(Charsets.UTF_8)
            val normRel = resolved.relativeTo(workspaceDir).invariantSeparatorsPath
            selectedFilePath = normRel
            selectedFileContent = text
            appendLog("Opened sandbox file: $normRel (${resolved.length()} bytes)")
            refreshStatusSnapshot()
            text
        }
    }

    fun saveSandboxFile(relativePath: String, content: String): Result<SandboxFileNode> {
        ensureSandboxDirs()
        val resolved = resolveSandboxPath(workspaceDir, relativePath).getOrElse {
            appendLog("Blocked unsafe path '$relativePath': ${it.localizedMessage}")
            refreshStatusSnapshot()
            return Result.failure(it)
        }
        return runCatching {
            resolved.parentFile?.mkdirs()
            resolved.writeText(content, Charsets.UTF_8)
            val normRel = resolved.relativeTo(workspaceDir).invariantSeparatorsPath
            selectedFilePath = normRel
            selectedFileContent = content
            val node = SandboxFileNode(
                relativePath = normRel,
                name = resolved.name,
                isDirectory = false,
                sizeBytes = resolved.length(),
                modifiedAtMillis = resolved.lastModified()
            )
            appendLog("Saved sandbox file: ${node.relativePath} (${node.sizeBytes} bytes)")
            refreshStatusSnapshot()
            node
        }
    }

    fun deleteSandboxFile(relativePath: String): Result<Boolean> {
        ensureSandboxDirs()
        val resolved = resolveSandboxPath(workspaceDir, relativePath).getOrElse {
            appendLog("Blocked unsafe delete '$relativePath': ${it.localizedMessage}")
            refreshStatusSnapshot()
            return Result.failure(it)
        }
        return runCatching {
            if (!resolved.exists()) {
                throw IOException("File not found: $relativePath")
            }
            val normRel = resolved.relativeTo(workspaceDir).invariantSeparatorsPath
            val deleted = if (resolved.isDirectory) resolved.deleteRecursively() else resolved.delete()
            if (selectedFilePath == normRel) {
                selectedFilePath = ""
                selectedFileContent = ""
            }
            appendLog("Deleted sandbox entry: $normRel (ok=$deleted)")
            refreshStatusSnapshot()
            deleted
        }
    }

    fun clearTerminalHistory() {
        synchronized(terminalLock) {
            terminalBuffer.clear()
        }
        refreshStatusSnapshot()
    }

    suspend fun runSandboxCommand(rawCommand: String): Result<DevStudioTerminalEntry> = withContext(Dispatchers.IO) {
        val clean = rawCommand.trim()
        if (clean.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a command to execute."))
        }
        ensureSandboxDirs()
        isCommandRunning = true
        refreshStatusSnapshot()

        try {
            val tokens = tokenizeCommandArgs(clean)
            val cmd = tokens.firstOrNull()?.lowercase(Locale.US).orEmpty()
            val arg = tokens.getOrNull(1).orEmpty()

            val (exitCode, output) = when (cmd) {
                "help" -> 0 to (
                    "Teena Dev Studio Phase 11 Sandbox Shell\n" +
                        "Built-ins: help, pwd, ls [dir], cat <file>, head <file>, touch <file>, rm <file>, echo <text>, env, bridge ping, clear\n" +
                        "Subprocess: runs inside sandbox (${if (inspectNativeBinaries().hasProot && _state.value.rootfsInstalled) "proot + rootfs" else "sandbox JVM ProcessBuilder"})"
                    )

                "pwd" -> 0 to workspaceDir.absolutePath

                "ls" -> {
                    val targetDir = if (arg.isBlank()) {
                        workspaceDir
                    } else {
                        resolveSandboxPath(workspaceDir, arg).getOrElse { err ->
                            val entry = recordTerminalEntry(clean, "Error: ${err.localizedMessage}", 1)
                            return@withContext Result.success(entry)
                        }
                    }
                    val files = listSandboxFiles(targetDir)
                    if (files.isEmpty()) {
                        0 to "(empty sandbox directory)"
                    } else {
                        0 to files.joinToString("\n") { node ->
                            val kind = if (node.isDirectory) "<DIR>" else formatBytes(node.sizeBytes)
                            "${node.relativePath}\t$kind"
                        }
                    }
                }

                "cat", "head" -> {
                    if (arg.isBlank()) {
                        1 to "Usage: $cmd <relative-path>"
                    } else {
                        readSandboxFile(arg).fold(
                            onSuccess = { txt ->
                                val out = if (cmd == "head") txt.lineSequence().take(15).joinToString("\n") else txt
                                0 to out
                            },
                            onFailure = { err -> 1 to (err.localizedMessage ?: "Failed to read file") }
                        )
                    }
                }

                "touch" -> {
                    if (arg.isBlank()) {
                        1 to "Usage: touch <relative-path>"
                    } else {
                        resolveSandboxPath(workspaceDir, arg).fold(
                            onSuccess = { file ->
                                file.parentFile?.mkdirs()
                                if (!file.exists()) file.writeText("", Charsets.UTF_8)
                                refreshStatusSnapshot()
                                0 to "Created/touched ${file.relativeTo(workspaceDir).invariantSeparatorsPath}"
                            },
                            onFailure = { err -> 1 to (err.localizedMessage ?: "Blocked path") }
                        )
                    }
                }

                "rm" -> {
                    if (arg.isBlank()) {
                        1 to "Usage: rm <relative-path>"
                    } else {
                        deleteSandboxFile(arg).fold(
                            onSuccess = { ok -> if (ok) 0 to "Removed $arg" else 1 to "Could not remove $arg" },
                            onFailure = { err -> 1 to (err.localizedMessage ?: "Delete failed") }
                        )
                    }
                }

                "echo" -> 0 to clean.removePrefix(tokens.first()).trim()

                "env" -> {
                    val snap = _state.value
                    0 to buildString {
                        appendLine("ENGINE=$ENGINE_VERSION")
                        appendLine("ABI=${snap.abi.primaryAbi}")
                        appendLine("ROOTFS_INSTALLED=${snap.rootfsInstalled} (${snap.rootfsVersion.ifBlank { "none" }})")
                        appendLine("PROOT_NATIVE=${snap.nativeBinaries.hasProot}")
                        appendLine("BRIDGE=${if (snap.bridgeStatus.isRunning) "127.0.0.1:${snap.bridgeStatus.port}" else "STOPPED"}")
                        append("WORKSPACE=${workspaceDir.absolutePath}")
                    }
                }

                "bridge" -> {
                    if (arg.equals("ping", ignoreCase = true)) {
                        bridge.ping().fold(
                            onSuccess = { resp -> 0 to resp },
                            onFailure = { err -> 1 to (err.localizedMessage ?: "Bridge stopped") }
                        )
                    } else {
                        val st = bridge.status()
                        0 to "Bridge: running=${st.isRunning}, port=${st.port}, mode=${st.mode}, requests=${st.requestCount}"
                    }
                }

                "clear" -> {
                    synchronized(terminalLock) { terminalBuffer.clear() }
                    0 to "Terminal cleared."
                }

                else -> {
                    val natives = inspectNativeBinaries()
                    val proot = natives.prootBinaryPath
                    val procCmd = if (proot != null && _state.value.rootfsInstalled && rootfsDir.exists()) {
                        listOf(
                            proot,
                            "-0",
                            "-r", rootfsDir.absolutePath,
                            "-b", "${workspaceDir.absolutePath}:/workspace",
                            "-w", "/workspace",
                            "/bin/sh", "-c", clean
                        )
                    } else {
                        tokens
                    }
                    executeSandboxProcess(procCmd, timeoutSeconds = 20L).fold(
                        onSuccess = { (code, out) -> code to out.ifBlank { "(exit $code, no output)" } },
                        onFailure = { err -> 1 to (err.localizedMessage ?: "Command failed in sandbox") }
                    )
                }
            }

            val entry = recordTerminalEntry(clean, output, exitCode)
            appendLog("Terminal [$exitCode]: ${clean.take(48)}")
            refreshStatusSnapshot()
            Result.success(entry)
        } finally {
            isCommandRunning = false
            refreshStatusSnapshot()
        }
    }

    fun killAllProcesses(): Int {
        var killed = 0
        activeInstallJob?.cancel()
        activeInstallJob = null
        isCommandRunning = false

        val entries = activeProcesses.entries.toList()
        entries.forEach { (id, managed) ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    managed.process.destroyForcibly()
                } else {
                    managed.process.destroy()
                }
                killed++
            }
            activeProcesses.remove(id)
        }
        TeenaDevStudioService.stop(appContext)
        appendLog("Kill Tasks executed (terminated $killed active child process(es)).")
        refreshStatusSnapshot()
        return killed
    }

    /**
     * Spawns a child process constrained to [workspaceDir] under [TeenaDevStudioService] and
     * a timed WakeLock that is always released in `finally`.
     */
    suspend fun executeSandboxProcess(
        command: List<String>,
        timeoutSeconds: Long = 30L
    ): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        if (emergencyStopController?.isStopped() == true) {
            return@withContext Result.failure(IllegalStateException("Blocked: Emergency Stop is active."))
        }
        if (command.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Command cannot be empty."))
        }
        ensureSandboxDirs()
        val procId = "proc_${System.currentTimeMillis() % 100000}"
        val cmdLabel = command.joinToString(" ")
        TeenaDevStudioService.start(appContext, "Running: ${cmdLabel.take(40)}")
        val wakeLock = acquireTimedWakeLock(timeoutSeconds * 1000L + 5000L)
        var spawned: Process? = null
        try {
            val pb = ProcessBuilder(command)
                .directory(workspaceDir)
                .redirectErrorStream(true)
            pb.environment()["HOME"] = workspaceDir.absolutePath
            pb.environment()["TMPDIR"] = downloadsDir.absolutePath
            val process = pb.start()
            spawned = process
            activeProcesses[procId] = ManagedDevProcess(
                id = procId,
                command = cmdLabel,
                startedAtMillis = System.currentTimeMillis(),
                process = process
            )
            refreshStatusSnapshot()

            val output = process.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(1024)
                var total = 0
                while (isActive) {
                    val read = reader.read(buf)
                    if (read == -1) break
                    if (total < MAX_PROCESS_OUTPUT_CHARS) {
                        val take = minOf(read, MAX_PROCESS_OUTPUT_CHARS - total)
                        sb.appendRange(buf, 0, take)
                        total += take
                    }
                }
                sb.toString()
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.destroyForcibly() else process.destroy()
                return@withContext Result.failure(IllegalStateException("Process timed out after ${timeoutSeconds}s"))
            }
            Result.success(process.exitValue() to output)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeProcesses.remove(procId)
            runCatching {
                if (spawned != null && isProcessAlive(spawned)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) spawned.destroyForcibly() else spawned.destroy()
                }
            }
            releaseWakeLockSafely(wakeLock)
            if (!bridge.status().isRunning && activeProcesses.isEmpty()) {
                TeenaDevStudioService.stop(appContext)
            }
            refreshStatusSnapshot()
        }
    }

    fun inspectNativeBinaries(): NativeBinariesStatus {
        val libDir = appContext.applicationInfo?.nativeLibraryDir.orEmpty()
        val prootFile = if (libDir.isNotBlank()) File(libDir, "libproot.so") else null
        val bridgeFile = if (libDir.isNotBlank()) File(libDir, "libagy_ide_bridge.so") else null

        val hasProot = prootFile?.exists() == true && prootFile.canExecute()
        val hasBridge = bridgeFile?.exists() == true && bridgeFile.canExecute()
        val fallbackReason = when {
            hasProot && hasBridge -> "Native proot and IDE bridge binaries verified in nativeLibraryDir."
            !hasProot && !hasBridge -> "libproot.so / libagy_ide_bridge.so not bundled in APK; Kotlin loopback bridge & safe JVM sandbox active."
            !hasProot -> "libproot.so absent in nativeLibraryDir; using safe sandbox file/process fallback."
            else -> "libagy_ide_bridge.so absent; using Kotlin 127.0.0.1 Socket Bridge."
        }
        return NativeBinariesStatus(
            nativeLibDir = libDir,
            prootBinaryPath = if (hasProot) prootFile.absolutePath else null,
            hasProot = hasProot,
            bridgeBinaryPath = if (hasBridge) bridgeFile.absolutePath else null,
            hasNativeBridge = hasBridge,
            fallbackModeReason = fallbackReason
        )
    }

    fun checkAvailableStorage(requiredBytes: Long = DEFAULT_REQUIRED_FREE_BYTES): StorageCheckResult {
        val avail = runCatching {
            val stat = StatFs(appContext.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrDefault(0L)
        return evaluateStorageSpace(avail, requiredBytes)
    }

    private fun downloadWithResume(url: String, destPartFile: File): Result<File> = runCatching {
        val existingBytes = if (destPartFile.exists()) destPartFile.length() else 0L
        val reqBuilder = Request.Builder().url(url).get()
        buildRangeHeader(existingBytes)?.let { reqBuilder.header("Range", it) }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val code = resp.code
            if (code == 416 && existingBytes > 0L) {
                // Range already reached/exceeded server file length; restart clean if zero or treat complete
                return@use
            }
            if (!resp.isSuccessful && code != 206) {
                throw IOException("HTTP $code while fetching rootfs")
            }
            val body = resp.body ?: throw IOException("Empty HTTP response body")
            val isAppend = (code == 206 && existingBytes > 0L)
            val contentLen = body.contentLength().coerceAtLeast(0L)
            val totalExpected = if (isAppend && contentLen > 0L) existingBytes + contentLen else contentLen

            var downloaded = if (isAppend) existingBytes else 0L
            body.byteStream().use { input ->
                FileOutputStream(destPartFile, isAppend).use { out ->
                    val buf = ByteArray(16 * 1024)
                    var lastReportAt = 0L
                    while (true) {
                        if (activeInstallJob?.isActive == false) {
                            throw CancellationException("Download cancelled")
                        }
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= 200L) {
                            lastReportAt = now
                            val pct = if (totalExpected > 0L) {
                                ((downloaded * 65L) / totalExpected).toInt().coerceIn(5, 70)
                            } else 35
                            updateProgress(
                                DevStudioPhase.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = totalExpected,
                                percent = pct,
                                message = "Downloading rootfs (${formatBytes(downloaded)}${if (totalExpected > 0) " / ${formatBytes(totalExpected)}" else ""})..."
                            )
                            TeenaDevStudioService.updateNotification(
                                appContext,
                                "Downloading rootfs: $pct% (${formatBytes(downloaded)})"
                            )
                        }
                    }
                }
            }
        }
        destPartFile
    }

    private fun fetchExpectedSha256(shaUrl: String): Result<String> = runCatching {
        val req = Request.Builder().url(shaUrl).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("SHA-256 index HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            parseSha256ChecksumText(text)
                ?: throw IOException("No valid SHA-256 hex digest in $shaUrl")
        }
    }

    private fun failInstall(reason: String): Result<DevStudioEngineState> {
        appendLog("ERROR: $reason")
        updateProgress(
            phase = DevStudioPhase.ERROR,
            percent = 0,
            message = reason,
            lastError = reason
        )
        refreshStatusSnapshot()
        return Result.failure(IllegalStateException(reason))
    }

    private fun updateProgress(
        phase: DevStudioPhase,
        downloadedBytes: Long = _state.value.progress.downloadedBytes,
        totalBytes: Long = _state.value.progress.totalBytes,
        percent: Int = _state.value.progress.percent,
        message: String,
        lastError: String? = null
    ) {
        _state.value = _state.value.copy(
            phase = phase,
            statusLabel = phase.name,
            progress = DevStudioProgress(
                phase = phase,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                percent = percent,
                message = message,
                lastError = lastError
            ),
            logs = snapshotLogs()
        )
    }

    private fun seedStarterWorkspaceIfEmpty() {
        ensureSandboxDirs()
        if (workspaceDir.listFiles()?.isEmpty() == true) {
            File(workspaceDir, "hello_teena.py").writeText(
                "# Teena Dev Studio Sandbox\nprint(\"Assalam o Alaikum from Teena Dev Studio!\")\n",
                Charsets.UTF_8
            )
            File(workspaceDir, "README.md").writeText(
                "# Teena Dev Studio Workspace\nAll files inside this directory are sandboxed inside app storage.\n",
                Charsets.UTF_8
            )
        }
    }

    private fun acquireTimedWakeLock(timeoutMs: Long): PowerManager.WakeLock? {
        return runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeenaAI:DevStudioEngine").apply {
                setReferenceCounted(false)
                acquire(timeoutMs.coerceIn(5_000L, 15 * 60_000L))
            }
        }.getOrNull()
    }

    private fun releaseWakeLockSafely(wakeLock: PowerManager.WakeLock?) {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    private fun isProcessAlive(process: Process): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.isAlive
        } else {
            runCatching {
                process.exitValue()
                false
            }.getOrDefault(true)
        }
    }

    private data class InstalledRootfsMeta(
        val id: String,
        val version: String,
        val abi: String,
        val sha256: String,
        val sizeBytes: Long,
        val installedAtMillis: Long
    )

    private fun readInstalledMeta(): InstalledRootfsMeta? {
        if (!metaFile.exists()) return null
        return runCatching {
            val obj = JSONObject(metaFile.readText(Charsets.UTF_8))
            InstalledRootfsMeta(
                id = obj.optString("id", "alpine"),
                version = obj.optString("version", "unknown"),
                abi = obj.optString("abi", "arm64-v8a"),
                sha256 = obj.optString("sha256", ""),
                sizeBytes = obj.optLong("sizeBytes", 0L),
                installedAtMillis = obj.optLong("installedAtMillis", 0L)
            )
        }.getOrNull()
    }

    private fun writeInstalledMeta(meta: InstalledRootfsMeta) {
        val obj = JSONObject()
            .put("id", meta.id)
            .put("version", meta.version)
            .put("abi", meta.abi)
            .put("sha256", meta.sha256)
            .put("sizeBytes", meta.sizeBytes)
            .put("installedAtMillis", meta.installedAtMillis)
        metaFile.writeText(obj.toString(2), Charsets.UTF_8)
    }

    private fun recordTerminalEntry(command: String, output: String, exitCode: Int): DevStudioTerminalEntry {
        val entry = DevStudioTerminalEntry(
            id = System.nanoTime(),
            command = command,
            output = output.take(4000),
            exitCode = exitCode,
            timestampMillis = System.currentTimeMillis()
        )
        synchronized(terminalLock) {
            terminalBuffer.addLast(entry)
            while (terminalBuffer.size > MAX_TERMINAL_ENTRIES) {
                terminalBuffer.removeFirst()
            }
        }
        return entry
    }

    private fun snapshotTerminalHistory(): List<DevStudioTerminalEntry> =
        synchronized(terminalLock) { terminalBuffer.toList() }

    private fun appendLog(entry: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logLock) {
            logBuffer.addLast("[$ts] $entry")
            while (logBuffer.size > MAX_LOG_LINES) {
                logBuffer.removeFirst()
            }
        }
    }

    private fun snapshotLogs(): List<String> = synchronized(logLock) { logBuffer.toList() }

    companion object {
        const val ENGINE_VERSION = "Teena-DevStudio-Engine 2.0 (Phase 11)"
        const val ROOT_DIR_NAME = "dev_studio"
        const val DEFAULT_REQUIRED_FREE_BYTES = 150L * 1024L * 1024L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val MAX_LOG_LINES = 100
        private const val MAX_TERMINAL_ENTRIES = 30
        private const val MAX_EDITOR_FILE_BYTES = 256 * 1024L
        private const val MAX_PROCESS_OUTPUT_CHARS = 64 * 1024

        fun tokenizeCommandArgs(rawCommand: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            for (ch in rawCommand.trim()) {
                when {
                    quote != null -> {
                        if (ch == quote) {
                            quote = null
                        } else {
                            current.append(ch)
                        }
                    }
                    ch == '"' || ch == '\'' -> quote = ch
                    ch.isWhitespace() -> {
                        if (current.isNotEmpty()) {
                            tokens.add(current.toString())
                            current.clear()
                        }
                    }
                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) tokens.add(current.toString())
            return tokens
        }

        fun extractCodeBlockFromAiReply(rawReply: String): String? {
            val fenced = Regex("""```[a-zA-Z0-9_+-]*\s*\n([\s\S]*?)```""")
                .findAll(rawReply)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (fenced.isNotEmpty()) return fenced.maxByOrNull { it.length }
            return rawReply.trim().takeIf { it.isNotEmpty() }
        }

        fun resolveRootfsSpec(
            primaryAbi: String,
            customUrl: String = "",
            customSha256: String = ""
        ): RootfsSpec {
            val trimmedUrl = customUrl.trim()
            if (trimmedUrl.isNotEmpty()) {
                return RootfsSpec(
                    id = "custom_${primaryAbi.replace("-", "_")}",
                    name = "Custom Linux Rootfs ($primaryAbi)",
                    abi = primaryAbi,
                    version = "custom",
                    downloadUrl = trimmedUrl,
                    sha256FileUrl = "$trimmedUrl.sha256",
                    expectedSha256 = customSha256.trim()
                )
            }
            val arch = when (primaryAbi) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a" -> "armv7"
                "x86_64" -> "x86_64"
                else -> "aarch64"
            }
            val version = "3.21.3"
            val url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/$arch/alpine-minirootfs-$version-$arch.tar.gz"
            return RootfsSpec(
                id = "alpine_${version}_$arch",
                name = "Alpine Linux Minirootfs ($arch)",
                abi = primaryAbi,
                version = version,
                downloadUrl = url,
                sha256FileUrl = "$url.sha256",
                expectedSha256 = customSha256.trim()
            )
        }

        fun evaluateAbiCompatibility(supportedAbis: List<String>): AbiCompatibility {
            val clean = supportedAbis.map { it.trim() }.filter { it.isNotEmpty() }
            if (clean.isEmpty()) {
                return AbiCompatibility(
                    supportedAbis = emptyList(),
                    primaryAbi = "unknown",
                    isSupported = false,
                    reason = "No device ABI reported by OS."
                )
            }
            val primary = when {
                "arm64-v8a" in clean -> "arm64-v8a"
                "x86_64" in clean -> "x86_64"
                "armeabi-v7a" in clean -> "armeabi-v7a"
                else -> clean.first()
            }
            val supported = primary in setOf("arm64-v8a", "armeabi-v7a", "x86_64")
            val reason = when {
                primary == "arm64-v8a" -> "64-bit arm64-v8a detected (optimal Dev Studio target)."
                primary == "x86_64" -> "x86_64 ABI detected (emulator / x86_64 compatible)."
                primary == "armeabi-v7a" -> "32-bit armeabi-v7a detected (limited package support compared to arm64-v8a)."
                else -> "Unsupported ABI '$primary'. Teena Dev Studio supports arm64-v8a, armeabi-v7a, and x86_64."
            }
            return AbiCompatibility(
                supportedAbis = clean,
                primaryAbi = primary,
                isSupported = supported,
                reason = reason
            )
        }

        fun evaluateStorageSpace(
            availableBytes: Long,
            requiredBytes: Long = DEFAULT_REQUIRED_FREE_BYTES
        ): StorageCheckResult {
            val safeAvail = availableBytes.coerceAtLeast(0L)
            val safeReq = requiredBytes.coerceAtLeast(1L)
            return StorageCheckResult(
                availableBytes = safeAvail,
                requiredBytes = safeReq,
                hasEnoughSpace = safeAvail >= safeReq,
                formattedAvailable = formatBytes(safeAvail),
                formattedRequired = formatBytes(safeReq)
            )
        }

        fun buildRangeHeader(existingBytes: Long): String? {
            return if (existingBytes > 0L) "bytes=$existingBytes-" else null
        }

        fun normalizeSha256Hex(raw: String): String {
            return raw.trim().lowercase(Locale.US).filter { it in '0'..'9' || it in 'a'..'f' }
        }

        fun parseSha256ChecksumText(text: String): String? {
            val regex = Regex("""\b([0-9a-fA-F]{64})\b""")
            val match = regex.find(text) ?: return null
            return match.groupValues[1].lowercase(Locale.US)
        }

        fun matchesSha256(actualHex: String, expectedHex: String): Boolean {
            val a = normalizeSha256Hex(actualHex)
            val b = normalizeSha256Hex(expectedHex)
            return a.length == 64 && b.length == 64 && a == b
        }

        fun computeFileSha256(file: File): Result<String> = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Resolves [requestedRelativePath] strictly inside [sandboxRoot], rejecting any
         * `..`, null byte, or canonical-path escape outside the sandbox directory.
         */
        fun resolveSandboxPath(sandboxRoot: File, requestedRelativePath: String): Result<File> = runCatching {
            val trimmed = requestedRelativePath.trim().replace('\\', '/')
            if (trimmed.isEmpty()) throw IllegalArgumentException("Path cannot be empty")
            if (trimmed.contains('\u0000')) throw SecurityException("Invalid null byte in path")
            if (trimmed.startsWith("/")) throw SecurityException("Absolute paths outside sandbox are blocked")

            val canonicalRoot = sandboxRoot.canonicalFile
            val candidate = File(canonicalRoot, trimmed).canonicalFile
            val rootPathWithSep = canonicalRoot.path + File.separator
            if (candidate.path != canonicalRoot.path && !candidate.path.startsWith(rootPathWithSep)) {
                throw SecurityException("Path traversal blocked outside Dev Studio workspace: $requestedRelativePath")
            }
            candidate
        }

        fun listSandboxFiles(sandboxRoot: File, maxEntries: Int = 200): List<SandboxFileNode> {
            if (!sandboxRoot.exists() || !sandboxRoot.isDirectory) return emptyList()
            val rootCanonical = sandboxRoot.canonicalFile
            val nodes = mutableListOf<SandboxFileNode>()
            sandboxRoot.walkTopDown()
                .maxDepth(4)
                .filter { it.canonicalFile != rootCanonical }
                .take(maxEntries)
                .forEach { f ->
                    val rel = runCatching {
                        f.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath
                    }.getOrNull() ?: return@forEach
                    nodes.add(
                        SandboxFileNode(
                            relativePath = rel,
                            name = f.name,
                            isDirectory = f.isDirectory,
                            sizeBytes = if (f.isFile) f.length() else 0L,
                            modifiedAtMillis = f.lastModified()
                        )
                    )
                }
            return nodes.sortedWith(compareBy<SandboxFileNode> { !it.isDirectory }.thenBy { it.relativePath })
        }

        /**
         * Pure-Kotlin POSIX/ustar `.tar.gz` extractor enforcing Tar-Slip protection:
         * rejects entries attempting `..` or escaping [destDir].
         */
        fun extractTarGzSafely(
            tarGzFile: File,
            destDir: File,
            onPercent: (Int) -> Unit = {}
        ): Result<Int> = runCatching {
            val canonicalDest = destDir.canonicalFile
            canonicalDest.mkdirs()
            val destPrefix = canonicalDest.path + File.separator
            val totalFileLen = tarGzFile.length().coerceAtLeast(1L)
            var entryCount = 0

            FileInputStream(tarGzFile).use { rawFis ->
                val counting = CountingInputStream(BufferedInputStream(rawFis, 32 * 1024))
                GZIPInputStream(counting, 32 * 1024).use { gz ->
                    val header = ByteArray(512)
                    while (true) {
                        val readHeader = readFully(gz, header, 512)
                        if (readHeader == 0) break
                        if (readHeader < 512) {
                            throw IOException("Corrupt tar archive header (truncated)")
                        }
                        if (header.all { it == 0.toByte() }) {
                            break
                        }

                        val rawName = parseTarString(header, 0, 100)
                        val prefix = parseTarString(header, 345, 155)
                        val entryName = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                        val normalizedSegments = entryName.replace('\\', '/').split('/')
                        if (normalizedSegments.any { it == ".." }) {
                            throw SecurityException("Blocked Tar-Slip entry outside sandbox: $entryName")
                        }
                        val cleanName = entryName.trimStart('.', '/').trim()
                        val mode = parseTarOctal(header, 100, 8)
                        val size = parseTarOctal(header, 124, 12)
                        val typeFlag = header[156].toInt().toChar()

                        if (cleanName.isNotEmpty()) {
                            val outFile = File(canonicalDest, cleanName).canonicalFile
                            if (outFile.path != canonicalDest.path && !outFile.path.startsWith(destPrefix)) {
                                throw SecurityException("Blocked Tar-Slip entry outside sandbox: $entryName")
                            }

                            when (typeFlag) {
                                '5' -> {
                                    outFile.mkdirs()
                                    entryCount++
                                }
                                '0', '\u0000' -> {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        copyExactBytes(gz, fos, size)
                                    }
                                    if ((mode and 0b001_000_000L) != 0L) {
                                        runCatching { outFile.setExecutable(true, false) }
                                    }
                                    entryCount++
                                }
                                else -> {
                                    // Skip symlinks/hardlinks/meta headers safely without creating outside-sandbox links
                                    skipExactBytes(gz, size)
                                }
                            }
                        } else {
                            skipExactBytes(gz, size)
                        }

                        val remainder = (size % 512L).toInt()
                        if (remainder != 0) {
                            skipExactBytes(gz, (512 - remainder).toLong())
                        }
                        val pct = ((counting.bytesRead * 100L) / totalFileLen).toInt().coerceIn(0, 100)
                        onPercent(pct)
                    }
                }
            }
            entryCount
        }

        private class CountingInputStream(private val delegate: BufferedInputStream) : java.io.InputStream() {
            var bytesRead: Long = 0L
                private set

            override fun read(): Int {
                val b = delegate.read()
                if (b >= 0) bytesRead++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = delegate.read(b, off, len)
                if (n > 0) bytesRead += n
                return n
            }

            override fun close() = delegate.close()
        }

        private fun parseTarString(buf: ByteArray, offset: Int, length: Int): String {
            var end = offset
            val limit = (offset + length).coerceAtMost(buf.size)
            while (end < limit && buf[end] != 0.toByte()) {
                end++
            }
            return String(buf, offset, end - offset, Charsets.UTF_8).trim()
        }

        private fun parseTarOctal(buf: ByteArray, offset: Int, length: Int): Long {
            val raw = parseTarString(buf, offset, length).trim()
            if (raw.isEmpty()) return 0L
            return raw.toLongOrNull(8) ?: 0L
        }

        private fun readFully(input: java.io.InputStream, buf: ByteArray, len: Int): Int {
            var total = 0
            while (total < len) {
                val n = input.read(buf, total, len - total)
                if (n == -1) break
                total += n
            }
            return total
        }

        private fun copyExactBytes(input: java.io.InputStream, out: java.io.OutputStream, count: Long) {
            val buf = ByteArray(16 * 1024)
            var remaining = count
            while (remaining > 0L) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n == -1) throw IOException("Unexpected EOF while extracting tar entry")
                out.write(buf, 0, n)
                remaining -= n
            }
        }

        private fun skipExactBytes(input: java.io.InputStream, count: Long) {
            val buf = ByteArray(8 * 1024)
            var remaining = count
            while (remaining > 0L) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n == -1) break
                remaining -= n
            }
        }

        private fun directorySizeBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L)
            return when {
                b >= 1024L * 1024L * 1024L -> "%.2f GB".format(Locale.US, b / (1024.0 * 1024.0 * 1024.0))
                b >= 1024L * 1024L -> "%.1f MB".format(Locale.US, b / (1024.0 * 1024.0))
                b >= 1024L -> "${b / 1024L} KB"
                else -> "$b B"
            }
        }
    }
}
