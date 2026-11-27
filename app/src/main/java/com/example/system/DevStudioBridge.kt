package com.example.system

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class DevStudioBridgeStatus(
    val isRunning: Boolean = false,
    val port: Int = 0,
    val mode: String = "NONE",
    val lastResponse: String = "Bridge idle",
    val requestCount: Int = 0
)

/**
 * Localhost JSON-line IDE bridge connecting Teena's Dev Studio UI/Editor with the
 * sandboxed workspace (`filesDir/dev_studio/workspace`). If `libagy_ide_bridge.so`
 * is absent in `nativeLibraryDir`, runs a pure-Kotlin loopback `ServerSocket`.
 */
class DevStudioBridge(
    private val workspaceDirProvider: () -> File,
    private val rootfsInstalledProvider: () -> Boolean,
    private val nativeBridgePathProvider: () -> String? = { null },
    private val onLog: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var sessionToken: String = ""

    @Volatile
    private var currentStatus = DevStudioBridgeStatus()

    fun status(): DevStudioBridgeStatus = currentStatus

    fun currentSessionToken(): String = sessionToken

    fun start(): Result<DevStudioBridgeStatus> {
        if (running.get() && serverSocket?.isClosed == false) {
            return Result.success(currentStatus)
        }
        return runCatching {
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            socket.soTimeout = 0
            serverSocket = socket
            sessionToken = UUID.randomUUID().toString().replace("-", "").take(24)
            running.set(true)

            val nativePath = nativeBridgePathProvider()
            val mode = if (!nativePath.isNullOrBlank()) "KOTLIN_SOCKET_WITH_NATIVE_HINT" else "KOTLIN_SOCKET"
            currentStatus = DevStudioBridgeStatus(
                isRunning = true,
                port = socket.localPort,
                mode = mode,
                lastResponse = "Listening on 127.0.0.1:${socket.localPort}",
                requestCount = 0
            )
            onLog("IDE Bridge started ($mode) on 127.0.0.1:${socket.localPort}")

            acceptJob?.cancel()
            acceptJob = scope.launch {
                while (isActive && running.get()) {
                    try {
                        val client = socket.accept()
                        launch { handleClient(client) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        if (running.get()) {
                            onLog("Bridge accept error: ${e.localizedMessage}")
                        }
                        break
                    }
                }
            }
            currentStatus
        }.onFailure { err ->
            stop()
            onLog("Bridge failed to start: ${err.localizedMessage}")
        }
    }

    fun stop() {
        running.set(false)
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        sessionToken = ""
        currentStatus = currentStatus.copy(
            isRunning = false,
            port = 0,
            mode = "NONE",
            lastResponse = "Bridge stopped"
        )
    }

    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        if (!currentStatus.isRunning || currentStatus.port <= 0) {
            return@withContext Result.failure(IllegalStateException("Bridge is not running"))
        }
        sendCommand(
            port = currentStatus.port,
            token = sessionToken,
            action = "PING"
        )
    }

    suspend fun requestSandboxInfo(): Result<String> = withContext(Dispatchers.IO) {
        if (!currentStatus.isRunning || currentStatus.port <= 0) {
            return@withContext Result.failure(IllegalStateException("Bridge is not running"))
        }
        sendCommand(
            port = currentStatus.port,
            token = sessionToken,
            action = "SANDBOX_INFO"
        )
    }

    suspend fun readFileViaBridge(relativePath: String): Result<String> = withContext(Dispatchers.IO) {
        if (!currentStatus.isRunning || currentStatus.port <= 0) {
            return@withContext Result.failure(IllegalStateException("Bridge is not running"))
        }
        sendCommand(
            port = currentStatus.port,
            token = sessionToken,
            action = "READ_FILE",
            path = relativePath
        )
    }

    suspend fun writeFileViaBridge(relativePath: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        if (!currentStatus.isRunning || currentStatus.port <= 0) {
            return@withContext Result.failure(IllegalStateException("Bridge is not running"))
        }
        sendCommand(
            port = currentStatus.port,
            token = sessionToken,
            action = "WRITE_FILE",
            path = relativePath,
            content = content
        )
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 4000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
            val rawLine = reader.readLine().orEmpty()
            val response = processJsonCommand(rawLine)
            writer.write(response)
            writer.newLine()
            writer.flush()
        }
    }

    internal fun processJsonCommand(rawLine: String): String {
        val req = runCatching { JSONObject(rawLine) }.getOrElse {
            return JSONObject().put("ok", false).put("error", "Invalid JSON payload").toString()
        }
        val token = req.optString("token", "")
        if (sessionToken.isNotBlank() && token != sessionToken) {
            return JSONObject().put("ok", false).put("error", "Unauthorized bridge token").toString()
        }

        val workspaceDir = workspaceDirProvider()
        val action = req.optString("action", "").uppercase()
        val reply = when (action) {
            "PING" -> JSONObject()
                .put("ok", true)
                .put("mode", currentStatus.mode)
                .put("version", "2.0-phase11")
                .put("port", currentStatus.port)

            "SANDBOX_INFO" -> {
                val files = DevStudioEngine.listSandboxFiles(workspaceDir)
                JSONObject()
                    .put("ok", true)
                    .put("workspacePath", workspaceDir.absolutePath)
                    .put("rootfsInstalled", rootfsInstalledProvider())
                    .put("fileCount", files.size)
            }

            "LIST_FILES" -> {
                val files = DevStudioEngine.listSandboxFiles(workspaceDir)
                val arr = JSONArray()
                files.forEach { node ->
                    arr.put(
                        JSONObject()
                            .put("path", node.relativePath)
                            .put("isDir", node.isDirectory)
                            .put("size", node.sizeBytes)
                    )
                }
                JSONObject().put("ok", true).put("files", arr)
            }

            "STAT" -> {
                val rel = req.optString("path", "")
                val resolved = DevStudioEngine.resolveSandboxPath(workspaceDir, rel)
                resolved.fold(
                    onSuccess = { target ->
                        JSONObject()
                            .put("ok", true)
                            .put("path", rel)
                            .put("exists", target.exists())
                            .put("isDirectory", target.isDirectory)
                            .put("sizeBytes", if (target.isFile) target.length() else 0L)
                    },
                    onFailure = { err ->
                        JSONObject()
                            .put("ok", false)
                            .put("error", err.localizedMessage ?: "Sandbox path blocked")
                    }
                )
            }

            "READ_FILE" -> {
                val rel = req.optString("path", "")
                DevStudioEngine.resolveSandboxPath(workspaceDir, rel).fold(
                    onSuccess = { target ->
                        if (!target.exists() || !target.isFile) {
                            JSONObject().put("ok", false).put("error", "File not found: $rel")
                        } else if (target.length() > MAX_BRIDGE_FILE_BYTES) {
                            JSONObject().put("ok", false).put("error", "File exceeds 256 KB bridge limit")
                        } else {
                            JSONObject()
                                .put("ok", true)
                                .put("path", rel)
                                .put("content", target.readText(Charsets.UTF_8))
                        }
                    },
                    onFailure = { err ->
                        JSONObject().put("ok", false).put("error", err.localizedMessage ?: "Sandbox path blocked")
                    }
                )
            }

            "WRITE_FILE" -> {
                val rel = req.optString("path", "")
                val content = req.optString("content", "")
                if (content.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_FILE_BYTES) {
                    JSONObject().put("ok", false).put("error", "Payload exceeds 256 KB bridge limit")
                } else {
                    DevStudioEngine.resolveSandboxPath(workspaceDir, rel).fold(
                        onSuccess = { target ->
                            target.parentFile?.mkdirs()
                            target.writeText(content, Charsets.UTF_8)
                            onLog("Bridge WRITE_FILE: $rel (${target.length()} bytes)")
                            JSONObject()
                                .put("ok", true)
                                .put("path", rel)
                                .put("sizeBytes", target.length())
                        },
                        onFailure = { err ->
                            JSONObject().put("ok", false).put("error", err.localizedMessage ?: "Sandbox path blocked")
                        }
                    )
                }
            }

            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unsupported bridge action: $action")
        }

        val serialized = reply.toString()
        currentStatus = currentStatus.copy(
            lastResponse = serialized,
            requestCount = currentStatus.requestCount + 1
        )
        return serialized
    }

    companion object {
        private const val MAX_BRIDGE_FILE_BYTES = 256 * 1024

        fun sendCommand(
            port: Int,
            token: String,
            action: String,
            path: String? = null,
            content: String? = null
        ): Result<String> = runCatching {
            Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 4000
                val payload = JSONObject()
                    .put("token", token)
                    .put("action", action)
                if (path != null) payload.put("path", path)
                if (content != null) payload.put("content", content)

                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                writer.write(payload.toString())
                writer.newLine()
                writer.flush()

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                reader.readLine() ?: throw IllegalStateException("Empty bridge response")
            }
        }
    }
}
