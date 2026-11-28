package com.example.data.remote

import android.util.Base64
import com.example.core.security.EmergencyStopController
import com.example.core.security.SecurityAuditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class GitHubTokenTestResult(
    val username: String,
    val scopes: String,
    val rateLimitRemaining: String,
    val message: String
)

data class GitHubRepoEnsureResult(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val createdNow: Boolean,
    val isPrivate: Boolean,
    val htmlUrl: String
)

data class GitHubPushSummary(
    val totalWorkspaceFiles: Int,
    val pushedFilesCount: Int,
    val skippedUnchangedCount: Int,
    val lastCommitUrl: String
)

data class GitHubWorkflowStepStatus(
    val number: Int,
    val name: String,
    val status: String,
    val conclusion: String
)

data class GitHubLiveRunSnapshot(
    val runId: Long,
    val name: String,
    val status: String,       // queued, in_progress, completed
    val conclusion: String,   // success, failure, cancelled, etc.
    val htmlUrl: String,
    val createdAt: String,
    val steps: List<GitHubWorkflowStepStatus> = emptyList()
)

data class GitHubArtifactInfo(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val archiveDownloadUrl: String,
    val expired: Boolean
)

class GitHubBuildApiException(
    val httpCode: Int,
    val category: String,
    override val message: String
) : Exception(message)

/**
 * Phase 11D: GitHub REST API client for free Cloud APK builds via GitHub Actions.
 * Never logs or exposes tokens. Handles no internet, bad token, missing permissions,
 * repo name taken, rate limits, and missing workflow errors cleanly.
 */
class GitHubActionsBuildClient(
    private val httpClient: OkHttpClient,
    private val emergencyStopController: EmergencyStopController? = null
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun testGitHubToken(token: String): Result<GitHubTokenTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("GitHub token test")
            val cleanToken = token.trim()
            if (cleanToken.isEmpty()) {
                throw GitHubBuildApiException(0, "NO_TOKEN", "GitHub token khali hai. Settings ya Dev Studio mein PAT save karein.")
            }
            val req = Request.Builder()
                .url("https://api.github.com/user")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $cleanToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            executeOrClassify("Token test", req) { resp, body ->
                val obj = JSONObject(body)
                val login = obj.optString("login").ifBlank { "authenticated-user" }
                val scopes = resp.header("X-OAuth-Scopes")?.ifBlank { "fine-grained PAT" } ?: "fine-grained PAT"
                val remaining = resp.header("X-RateLimit-Remaining") ?: "?"
                GitHubTokenTestResult(
                    username = login,
                    scopes = scopes,
                    rateLimitRemaining = remaining,
                    message = "Token valid! Signed in as @$login (Scopes: $scopes, API quota left: $remaining)."
                )
            }
        }
    }

    suspend fun ensureRepositoryExists(
        owner: String,
        repo: String,
        isPrivate: Boolean,
        token: String
    ): Result<GitHubRepoEnsureResult> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("GitHub repo check")
            val cleanOwner = owner.trim()
            val cleanRepo = repo.trim()
            val cleanToken = token.trim()
            if (cleanOwner.isEmpty() || cleanRepo.isEmpty()) {
                throw GitHubBuildApiException(0, "MISSING_CONFIG", "GitHub username aur repo name dono bharna zaroori hai.")
            }
            if (cleanToken.isEmpty()) {
                throw GitHubBuildApiException(0, "NO_TOKEN", "GitHub token set nahi hai.")
            }

            // 1. Check if repo already exists
            val getReq = Request.Builder()
                .url("https://api.github.com/repos/$cleanOwner/$cleanRepo")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $cleanToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            try {
                return@runCatching executeOrClassify("Check repo", getReq) { _, body ->
                    val obj = JSONObject(body)
                    GitHubRepoEnsureResult(
                        owner = cleanOwner,
                        repo = obj.optString("name", cleanRepo),
                        defaultBranch = obj.optString("default_branch", "main").ifBlank { "main" },
                        createdNow = false,
                        isPrivate = obj.optBoolean("private", isPrivate),
                        htmlUrl = obj.optString("html_url", "https://github.com/$cleanOwner/$cleanRepo")
                    )
                }
            } catch (e: GitHubBuildApiException) {
                if (e.httpCode != 404) throw e
            }

            // 2. Repo not found -> check authenticated user vs org & create repo
            val authUser = testGitHubToken(cleanToken).getOrNull()?.username.orEmpty()
            val createUrl = if (authUser.equals(cleanOwner, ignoreCase = true) || authUser.isBlank()) {
                "https://api.github.com/user/repos"
            } else {
                "https://api.github.com/orgs/$cleanOwner/repos"
            }
            val payload = JSONObject().apply {
                put("name", cleanRepo)
                put("private", isPrivate)
                put("auto_init", true)
                put("description", "Android project built from Teena Dev Studio")
            }
            val createReq = Request.Builder()
                .url(createUrl)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $cleanToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            executeOrClassify("Create repo", createReq) { _, body ->
                val obj = JSONObject(body)
                GitHubRepoEnsureResult(
                    owner = cleanOwner,
                    repo = obj.optString("name", cleanRepo),
                    defaultBranch = obj.optString("default_branch", "main").ifBlank { "main" },
                    createdNow = true,
                    isPrivate = obj.optBoolean("private", isPrivate),
                    htmlUrl = obj.optString("html_url", "https://github.com/$cleanOwner/$cleanRepo")
                )
            }
        }
    }

    suspend fun fetchRepoTreeFileShas(
        owner: String,
        repo: String,
        branch: String,
        token: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyMap()
                val body = resp.body?.string().orEmpty()
                val tree = JSONObject(body).optJSONArray("tree") ?: return@runCatching emptyMap()
                buildMap {
                    for (i in 0 until tree.length()) {
                        val item = tree.optJSONObject(i) ?: continue
                        if (item.optString("type") == "blob") {
                            val p = item.optString("path")
                            val sha = item.optString("sha")
                            if (p.isNotBlank() && sha.isNotBlank()) put(p, sha)
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * Pushes ONLY new or modified files via GitHub Contents API (`PUT /repos/.../contents/...`)
     * by comparing Git blob SHA-1 digests against the remote tree.
     */
    suspend fun pushChangedFiles(
        owner: String,
        repo: String,
        branch: String,
        files: Map<String, ByteArray>,
        token: String,
        commitMessagePrefix: String = "Teena Dev Studio sync",
        onFileProgress: (index: Int, total: Int, path: String) -> Unit = { _, _, _ -> }
    ): Result<GitHubPushSummary> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("GitHub file push")
            val remoteShas = fetchRepoTreeFileShas(owner, repo, branch, token)
            val changedEntries = files.entries.filter { (relPath, bytes) ->
                val cleanPath = relPath.trimStart('/')
                val remoteSha = remoteShas[cleanPath]
                remoteSha == null || remoteSha != computeGitBlobSha1(bytes)
            }
            var lastUrl = "https://github.com/$owner/$repo/tree/$branch"
            changedEntries.forEachIndexed { idx, (relPath, bytes) ->
                val cleanPath = relPath.trimStart('/')
                onFileProgress(idx + 1, changedEntries.size, cleanPath)
                val existingSha = remoteShas[cleanPath] ?: fetchSingleFileSha(owner, repo, branch, cleanPath, token)
                val encodedB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val payload = JSONObject().apply {
                    put("message", "$commitMessagePrefix: $cleanPath")
                    put("content", encodedB64)
                    put("branch", branch)
                    if (!existingSha.isNullOrBlank()) put("sha", existingSha)
                }
                val putReq = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath")
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("User-Agent", "TeenaAI-Android")
                    .put(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                lastUrl = executeOrClassify("Push $cleanPath", putReq) { _, body ->
                    JSONObject(body).optJSONObject("commit")?.optString("html_url")
                        .orEmpty()
                        .ifBlank { "https://github.com/$owner/$repo/blob/$branch/$cleanPath" }
                }
            }
            GitHubPushSummary(
                totalWorkspaceFiles = files.size,
                pushedFilesCount = changedEntries.size,
                skippedUnchangedCount = files.size - changedEntries.size,
                lastCommitUrl = lastUrl
            )
        }
    }

    suspend fun triggerWorkflowDispatch(
        owner: String,
        repo: String,
        workflowFileName: String,
        branch: String,
        token: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("GitHub Actions dispatch")
            val bodyJson = JSONObject().put("ref", branch).toString()
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFileName/dispatches")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()
            executeOrClassify("Trigger workflow '$workflowFileName'", req) { _, _ ->
                "Workflow '$workflowFileName' dispatched on $owner/$repo ($branch)."
            }
        }
    }

    suspend fun findLatestDispatchedRun(
        owner: String,
        repo: String,
        workflowFileName: String,
        token: String
    ): Result<GitHubLiveRunSnapshot?> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("GitHub workflow run lookup")
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFileName/runs?per_page=5")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            executeOrClassify("Fetch workflow runs", req) { _, body ->
                val arr = JSONObject(body).optJSONArray("workflow_runs") ?: JSONArray()
                val r = (0 until arr.length()).asSequence()
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull()
                    ?: return@executeOrClassify null
                GitHubLiveRunSnapshot(
                    runId = r.optLong("id"),
                    name = r.optString("name", workflowFileName),
                    status = r.optString("status", "queued"),
                    conclusion = r.optString("conclusion", ""),
                    htmlUrl = r.optString("html_url"),
                    createdAt = r.optString("created_at")
                )
            }
        }
    }

    suspend fun fetchRunWithSteps(
        owner: String,
        repo: String,
        runId: Long,
        token: String
    ): Result<GitHubLiveRunSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("GitHub poll run")
            val runReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            val baseRun = executeOrClassify("Poll run #$runId", runReq) { _, body ->
                val r = JSONObject(body)
                GitHubLiveRunSnapshot(
                    runId = r.optLong("id", runId),
                    name = r.optString("name", "Build APK"),
                    status = r.optString("status", "unknown"),
                    conclusion = r.optString("conclusion", ""),
                    htmlUrl = r.optString("html_url"),
                    createdAt = r.optString("created_at")
                )
            }

            val jobsReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            val steps = runCatching {
                executeOrClassify("Fetch run steps", jobsReq) { _, body ->
                    val jobs = JSONObject(body).optJSONArray("jobs") ?: JSONArray()
                    val firstJob = jobs.optJSONObject(0) ?: return@executeOrClassify emptyList()
                    val stepsArr = firstJob.optJSONArray("steps") ?: JSONArray()
                    (0 until stepsArr.length()).mapNotNull { i ->
                        val s = stepsArr.optJSONObject(i) ?: return@mapNotNull null
                        GitHubWorkflowStepStatus(
                            number = s.optInt("number", i + 1),
                            name = s.optString("name", "Step ${i + 1}"),
                            status = s.optString("status", "queued"),
                            conclusion = s.optString("conclusion", "")
                        )
                    }
                }
            }.getOrDefault(emptyList())

            baseRun.copy(steps = steps)
        }
    }

    suspend fun cancelWorkflowRun(
        owner: String,
        repo: String,
        runId: Long,
        token: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/cancel")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            executeOrClassify("Cancel run #$runId", req) { _, _ ->
                "Build run #$runId cancellation requested on GitHub Actions."
            }
        }
    }

    suspend fun listRunArtifacts(
        owner: String,
        repo: String,
        runId: Long,
        token: String
    ): Result<List<GitHubArtifactInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("GitHub list artifacts")
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            executeOrClassify("List artifacts", req) { _, body ->
                val arr = JSONObject(body).optJSONArray("artifacts") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val a = arr.optJSONObject(i) ?: return@mapNotNull null
                    GitHubArtifactInfo(
                        id = a.optLong("id"),
                        name = a.optString("name", "app-apk"),
                        sizeInBytes = a.optLong("size_in_bytes", 0L),
                        archiveDownloadUrl = a.optString("archive_download_url"),
                        expired = a.optBoolean("expired", false)
                    )
                }
            }
        }
    }

    suspend fun downloadArtifactZip(
        archiveDownloadUrl: String,
        destZipFile: File,
        token: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("Download APK artifact")
            destZipFile.parentFile?.mkdirs()
            val req = Request.Builder()
                .url(archiveDownloadUrl)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    throw classifyHttpFailure("Download artifact", resp.code, errBody, resp.header("X-RateLimit-Remaining"))
                }
                val stream = resp.body?.byteStream() ?: throw IOException("Empty artifact ZIP stream")
                FileOutputStream(destZipFile).use { out ->
                    stream.copyTo(out)
                }
            }
            destZipFile
        }.recoverCatching { err ->
            throw wrapNetworkThrowable("Download artifact", err)
        }
    }

    suspend fun downloadAndExtractRunLogs(
        owner: String,
        repo: String,
        runId: Long,
        token: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            emergencyStopController?.assertAutomationAllowed("Download build logs")
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/logs")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            val zipBytes = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    throw classifyHttpFailure("Download logs", resp.code, errBody, resp.header("X-RateLimit-Remaining"))
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
            extractTextFromLogZipBytes(zipBytes)
        }.recoverCatching { err ->
            throw wrapNetworkThrowable("Download build logs", err)
        }
    }

    private fun fetchSingleFileSha(
        owner: String,
        repo: String,
        branch: String,
        cleanPath: String,
        token: String
    ): String? {
        val getReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath?ref=$branch")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("User-Agent", "TeenaAI-Android")
            .get()
            .build()
        return runCatching {
            httpClient.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) JSONObject(resp.body?.string().orEmpty()).optString("sha").takeIf { it.isNotBlank() }
                else null
            }
        }.getOrNull()
    }

    private fun <T> executeOrClassify(
        operation: String,
        request: Request,
        parser: (okhttp3.Response, String) -> T
    ): T {
        return try {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw classifyHttpFailure(operation, resp.code, body, resp.header("X-RateLimit-Remaining"))
                }
                parser(resp, body)
            }
        } catch (e: GitHubBuildApiException) {
            throw e
        } catch (t: Throwable) {
            throw wrapNetworkThrowable(operation, t)
        }
    }

    companion object {
        fun computeGitBlobSha1(bytes: ByteArray): String {
            val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("SHA-1")
            md.update(header)
            md.update(bytes)
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun extractTextFromLogZipBytes(zipBytes: ByteArray, maxChars: Int = 120_000): String {
            if (zipBytes.isEmpty()) return ""
            val sb = StringBuilder()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) {
                        sb.appendLine("=== ${entry.name} ===")
                        val buf = ByteArray(8192)
                        val out = ByteArrayOutputStream()
                        var read: Int
                        while (zis.read(buf).also { read = it } != -1 && out.size() < 64_000) {
                            out.write(buf, 0, read)
                        }
                        sb.appendLine(out.toString(Charsets.UTF_8.name()))
                        if (sb.length >= maxChars) break
                    }
                    entry = zis.nextEntry
                }
            }
            return sb.toString().take(maxChars)
        }

        fun wrapNetworkThrowable(operation: String, err: Throwable): GitHubBuildApiException {
            if (err is GitHubBuildApiException) return err
            val raw = SecurityAuditor.redactSecrets(err.localizedMessage ?: err::class.java.simpleName)
            return when (err) {
                is UnknownHostException, is ConnectException, is SocketTimeoutException -> {
                    GitHubBuildApiException(
                        httpCode = 0,
                        category = "NO_INTERNET",
                        message = "Internet connect nahi hai ya GitHub reach nahi ho raha ($operation).\nRaw: $raw"
                    )
                }
                else -> {
                    val lower = raw.lowercase()
                    if (lower.contains("unable to resolve host") || lower.contains("network") || lower.contains("failed to connect")) {
                        GitHubBuildApiException(
                            httpCode = 0,
                            category = "NO_INTERNET",
                            message = "Internet unavailable hai ($operation).\nRaw: $raw"
                        )
                    } else {
                        GitHubBuildApiException(
                            httpCode = 0,
                            category = "NETWORK_ERROR",
                            message = "$operation fail ho gayi.\nRaw: $raw"
                        )
                    }
                }
            }
        }

        fun classifyHttpFailure(
            operation: String,
            httpCode: Int,
            rawBody: String,
            rateLimitRemaining: String? = null
        ): GitHubBuildApiException {
            val apiMsg = runCatching {
                val obj = JSONObject(rawBody)
                val base = obj.optString("message")
                val errs = obj.optJSONArray("errors")
                val firstErr = errs?.optJSONObject(0)?.optString("message").orEmpty()
                listOf(base, firstErr).filter { it.isNotBlank() }.joinToString(" — ")
            }.getOrDefault("").ifBlank { rawBody.take(180) }
            val safeRaw = SecurityAuditor.redactSecrets(apiMsg.ifBlank { "HTTP $httpCode" })
            val lower = safeRaw.lowercase()

            return when {
                httpCode == 401 -> GitHubBuildApiException(
                    httpCode = 401,
                    category = "BAD_TOKEN",
                    message = "Bad GitHub token (HTTP 401): Token galat ya expire ho gaya hai.\nRaw: $safeRaw"
                )
                httpCode == 429 || (httpCode == 403 && (rateLimitRemaining == "0" || lower.contains("rate limit"))) -> {
                    GitHubBuildApiException(
                        httpCode = httpCode,
                        category = "RATE_LIMIT",
                        message = "GitHub rate limit hit ho gayi (HTTP $httpCode). Thodi der baad retry karein.\nRaw: $safeRaw"
                    )
                }
                httpCode == 403 -> GitHubBuildApiException(
                    httpCode = 403,
                    category = "MISSING_PERMISSION",
                    message = "Permission missing (HTTP 403): Fine-grained token mein Contents, Actions aur Workflows (Read & Write) enable karein.\nRaw: $safeRaw"
                )
                httpCode == 404 && operation.contains("workflow", ignoreCase = true) -> GitHubBuildApiException(
                    httpCode = 404,
                    category = "WORKFLOW_NOT_FOUND",
                    message = "Workflow not found (HTTP 404): Repo mein '.github/workflows/build.yml' abhi register nahi hui.\nRaw: $safeRaw"
                )
                httpCode == 404 -> GitHubBuildApiException(
                    httpCode = 404,
                    category = "NOT_FOUND",
                    message = "GitHub resource nahi mila (HTTP 404): $operation.\nRaw: $safeRaw"
                )
                httpCode == 422 && (lower.contains("name already exists") || lower.contains("taken")) -> {
                    GitHubBuildApiException(
                        httpCode = 422,
                        category = "REPO_NAME_TAKEN",
                        message = "Repo name already taken (HTTP 422): Ye repository naam aapke account mein pehle se hai.\nRaw: $safeRaw"
                    )
                }
                else -> GitHubBuildApiException(
                    httpCode = httpCode,
                    category = "HTTP_$httpCode",
                    message = "$operation fail (HTTP $httpCode).\nRaw: $safeRaw"
                )
            }
        }
    }
}
