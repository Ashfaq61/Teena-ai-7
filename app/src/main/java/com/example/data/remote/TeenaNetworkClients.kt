package com.example.data.remote

import android.util.Base64
import com.example.core.security.SecurityAuditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DiscoveredModel(
    val id: String,
    val name: String,
    val provider: String,
    val contextLength: Int = 0,
    val isFree: Boolean = false,
    val pricingSummary: String = ""
)

data class OpenAiChatResult(
    val text: String,
    val modelUsed: String,
    val latencyMs: Long
)

data class GitHubRepoDetail(
    val fullName: String,
    val description: String,
    val stars: Int,
    val language: String,
    val licenseSpdx: String,
    val htmlUrl: String,
    val defaultBranch: String,
    val updatedAt: String,
    val dependenciesPreview: String = "",
    val securityScore: Int = 100,
    val securityNotes: String = ""
)

data class HuggingFaceModelDetail(
    val modelId: String,
    val pipelineTag: String,
    val downloads: Long,
    val likes: Int,
    val license: String,
    val tags: List<String>,
    val compatibilityNotes: String,
    val url: String
)

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

data class GitHubWorkflowRunInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val htmlUrl: String,
    val createdAt: String
)

class TeenaNetworkClients {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun chatOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        extraHeaders: Map<String, String> = emptyMap()
    ): Result<OpenAiChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val payload = JSONObject().apply {
                put("model", model)
                val arr = JSONArray()
                messages.forEach { (role, content) ->
                    arr.put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                put("messages", arr)
                put("temperature", 0.6)
            }
            val start = System.currentTimeMillis()
            val builder = Request.Builder()
                .url("$cleanBase/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
            extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }

            httpClient.newCall(builder.build()).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = extractJsonError(bodyStr) ?: "HTTP ${resp.code}"
                    throw IllegalStateException(SecurityAuditor.redactSecrets(errMsg))
                }
                val json = JSONObject(bodyStr)
                val choices = json.optJSONArray("choices")
                val text = choices?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (text.isBlank()) throw IllegalStateException("Empty response from $cleanBase")
                val latency = System.currentTimeMillis() - start
                OpenAiChatResult(
                    text = text.trim(),
                    modelUsed = json.optString("model", model),
                    latencyMs = latency
                )
            }
        }
    }

    suspend fun chatGeminiDirectRest(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String
    ): Result<OpenAiChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val start = System.currentTimeMillis()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val payload = JSONObject().apply {
                if (systemInstruction.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                    })
                }
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                    }
                ))
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = extractJsonError(bodyStr) ?: "HTTP ${resp.code}"
                    throw IllegalStateException(SecurityAuditor.redactSecrets(errMsg))
                }
                val json = JSONObject(bodyStr)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                if (text.isBlank()) throw IllegalStateException("Gemini REST returned empty content")
                OpenAiChatResult(
                    text = text.trim(),
                    modelUsed = model,
                    latencyMs = System.currentTimeMillis() - start
                )
            }
        }
    }

    suspend fun fetchGroqModels(apiKey: String): Result<List<DiscoveredModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(extractJsonError(body) ?: "Groq HTTP ${resp.code}")
                    }
                    val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                    (0 until data.length()).mapNotNull { i ->
                        val item = data.optJSONObject(i) ?: return@mapNotNull null
                        val id = item.optString("id")
                        if (id.isBlank()) return@mapNotNull null
                        DiscoveredModel(
                            id = id,
                            name = id,
                            provider = "GROQ",
                            contextLength = item.optInt("context_window", 8192),
                            isFree = true,
                            pricingSummary = "Groq Tier"
                        )
                    }.sortedBy { it.id }
                }
            }
        }

    suspend fun fetchOpenRouterModels(
        apiKey: String? = null,
        freeOnly: Boolean = false
    ): Result<List<DiscoveredModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .get()
            if (!apiKey.isNullOrBlank()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(extractJsonError(body) ?: "OpenRouter HTTP ${resp.code}")
                }
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                val models = (0 until data.length()).mapNotNull { i ->
                    val item = data.optJSONObject(i) ?: return@mapNotNull null
                    val id = item.optString("id")
                    if (id.isBlank()) return@mapNotNull null
                    val pricing = item.optJSONObject("pricing")
                    val promptPrice = pricing?.optString("prompt", "1") ?: "1"
                    val completionPrice = pricing?.optString("completion", "1") ?: "1"
                    val isFree = id.endsWith(":free") ||
                        (promptPrice == "0" && completionPrice == "0")
                    if (freeOnly && !isFree) return@mapNotNull null
                    DiscoveredModel(
                        id = id,
                        name = item.optString("name", id),
                        provider = "OPENROUTER",
                        contextLength = item.optInt("context_length", 0),
                        isFree = isFree,
                        pricingSummary = if (isFree) "FREE ($0)" else "$$promptPrice/tok"
                    )
                }
                models.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenByDescending { it.contextLength })
            }
        }
    }

    suspend fun searchGitHubRepositories(
        query: String,
        githubToken: String? = null
    ): Result<List<GitHubRepoDetail>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://api.github.com/search/repositories?q=$q&sort=stars&order=desc&per_page=10")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "TeenaAI-Android")
                .apply {
                    if (!githubToken.isNullOrBlank()) {
                        addHeader("Authorization", "Bearer $githubToken")
                    }
                }
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(extractJsonError(body) ?: "GitHub HTTP ${resp.code}")
                }
                val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { i ->
                    val repo = items.optJSONObject(i) ?: return@mapNotNull null
                    val fullName = repo.optString("full_name")
                    val licenseSpdx = repo.optJSONObject("license")?.optString("spdx_id", "NONE") ?: "NONE"
                    val desc = repo.optString("description", "No description")
                    val scan = SecurityAuditor.auditToolOrCode(
                        name = fullName,
                        sourceCodeOrSpec = desc,
                        licenseSpdx = licenseSpdx,
                        targetUrl = repo.optString("html_url")
                    )
                    GitHubRepoDetail(
                        fullName = fullName,
                        description = desc,
                        stars = repo.optInt("stargazers_count", 0),
                        language = repo.optString("language", "Unknown"),
                        licenseSpdx = licenseSpdx,
                        htmlUrl = repo.optString("html_url"),
                        defaultBranch = repo.optString("default_branch", "main"),
                        updatedAt = repo.optString("updated_at", ""),
                        securityScore = scan.score,
                        securityNotes = scan.findings.joinToString("; ")
                    )
                }
            }
        }
    }

    suspend fun inspectGitHubRepoDependencies(
        fullName: String,
        githubToken: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val candidatePaths = listOf(
                "app/build.gradle.kts",
                "build.gradle.kts",
                "gradle/libs.versions.toml",
                "package.json",
                "requirements.txt",
                "README.md"
            )
            val findings = StringBuilder()
            for (path in candidatePaths) {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$fullName/contents/$path")
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("User-Agent", "TeenaAI-Android")
                    .apply {
                        if (!githubToken.isNullOrBlank()) {
                            addHeader("Authorization", "Bearer $githubToken")
                        }
                    }
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val encoded = json.optString("content").replace("\n", "")
                        if (encoded.isNotBlank()) {
                            val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                            findings.appendLine("=== $path ===")
                            findings.appendLine(decoded.take(1600))
                            val scan = SecurityAuditor.auditToolOrCode(fullName, decoded)
                            findings.appendLine("Security Audit Score: ${scan.score}/100 (${scan.findings.joinToString()})")
                            return@use
                        }
                    }
                }
                if (findings.length > 2200) break
            }
            if (findings.isEmpty()) "No standard manifest (Gradle/TOML/package.json/README) found in root."
            else findings.toString()
        }
    }

    suspend fun searchHuggingFaceModels(
        query: String,
        taskFilter: String = "",
        deviceRamGb: Float = 4f
    ): Result<List<HuggingFaceModelDetail>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val filterPart = if (taskFilter.isNotBlank()) "&filter=${URLEncoder.encode(taskFilter, "UTF-8")}" else ""
            val url = "https://huggingface.co/api/models?search=$q$filterPart&sort=downloads&direction=-1&limit=12"
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HuggingFace HTTP ${resp.code}")
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = item.optString("id")
                    if (id.isBlank()) return@mapNotNull null
                    val tagsArr = item.optJSONArray("tags") ?: JSONArray()
                    val tags = (0 until tagsArr.length()).map { tagsArr.optString(it) }
                    val license = tags.firstOrNull { it.startsWith("license:") }
                        ?.removePrefix("license:") ?: "Check model card"
                    val pipeline = item.optString("pipeline_tag", "general")
                    val isMobileFriendly = tags.any {
                        it.contains("tflite", true) || it.contains("onnx", true) ||
                            it.contains("gguf", true) || it.contains("mediapipe", true) ||
                            id.contains("mobile", true) || id.contains("tiny", true) ||
                            id.contains("mini", true) || id.contains("nano", true)
                    }
                    val compat = buildString {
                        append(if (isMobileFriendly) "Edge/Mobile-compatible format detected. " else "Cloud/Server inference recommended. ")
                        append("Device RAM: ${"%.1f".format(deviceRamGb)} GB.")
                    }
                    HuggingFaceModelDetail(
                        modelId = id,
                        pipelineTag = pipeline,
                        downloads = item.optLong("downloads", 0L),
                        likes = item.optInt("likes", 0),
                        license = license,
                        tags = tags.take(8),
                        compatibilityNotes = compat,
                        url = "https://huggingface.co/$id"
                    )
                }
            }
        }
    }

    suspend fun searchWeb(query: String): Result<List<WebSearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val results = mutableListOf<WebSearchResult>()
            val q = URLEncoder.encode(query, "UTF-8")

            // 1. DuckDuckGo Instant Answer API
            runCatching {
                val req = Request.Builder()
                    .url("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1")
                    .addHeader("User-Agent", "TeenaAI-Android/1.0")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val absText = json.optString("AbstractText")
                        val absUrl = json.optString("AbstractURL")
                        if (absText.isNotBlank()) {
                            results.add(
                                WebSearchResult(
                                    title = json.optString("Heading", query),
                                    snippet = absText,
                                    url = absUrl.ifBlank { "https://duckduckgo.com/?q=$q" },
                                    source = json.optString("AbstractSource", "DuckDuckGo")
                                )
                            )
                        }
                        val related = json.optJSONArray("RelatedTopics") ?: JSONArray()
                        for (i in 0 until minOf(related.length(), 6)) {
                            val obj = related.optJSONObject(i) ?: continue
                            val text = obj.optString("Text")
                            val firstUrl = obj.optString("FirstURL")
                            if (text.isNotBlank() && firstUrl.isNotBlank()) {
                                results.add(
                                    WebSearchResult(
                                        title = text.substringBefore(" - ").take(80),
                                        snippet = text,
                                        url = firstUrl,
                                        source = "DuckDuckGo"
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 2. Wikipedia OpenSearch / Query API for grounded knowledge
            runCatching {
                val req = Request.Builder()
                    .url("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&utf8=&format=json&srlimit=5")
                    .addHeader("User-Agent", "TeenaAI-Android/1.0")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONObject(resp.body?.string().orEmpty())
                            .optJSONObject("query")
                            ?.optJSONArray("search") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val title = obj.optString("title")
                            val cleanSnippet = obj.optString("snippet")
                                .replace(Regex("<[^>]*>"), "")
                                .replace("&quot;", "\"")
                                .replace("&amp;", "&")
                            val pageSlug = URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                            results.add(
                                WebSearchResult(
                                    title = title,
                                    snippet = cleanSnippet,
                                    url = "https://en.wikipedia.org/wiki/$pageSlug",
                                    source = "Wikipedia"
                                )
                            )
                        }
                    }
                }
            }

            results
        }
    }

    suspend fun fetchReadableWebPage(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            val req = Request.Builder()
                .url(safeUrl)
                .addHeader("User-Agent", "TeenaAI-Android/1.0")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching $safeUrl")
                val raw = resp.body?.string().orEmpty()
                raw.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(8000)
            }
        }
    }

    suspend fun fetchGitHubWorkflowRuns(
        owner: String,
        repo: String,
        githubToken: String?
    ): Result<List<GitHubWorkflowRunInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=10")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "TeenaAI-Android")
                .apply {
                    if (!githubToken.isNullOrBlank()) {
                        addHeader("Authorization", "Bearer $githubToken")
                    }
                }
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException(extractJsonError(body) ?: "GitHub Actions HTTP ${resp.code}")
                val runs = JSONObject(body).optJSONArray("workflow_runs") ?: JSONArray()
                (0 until runs.length()).mapNotNull { i ->
                    val r = runs.optJSONObject(i) ?: return@mapNotNull null
                    GitHubWorkflowRunInfo(
                        id = r.optLong("id"),
                        name = r.optString("name", "Workflow"),
                        status = r.optString("status", "unknown"),
                        conclusion = r.optString("conclusion", "pending"),
                        htmlUrl = r.optString("html_url"),
                        createdAt = r.optString("created_at")
                    )
                }
            }
        }
    }

    suspend fun triggerGitHubWorkflowDispatch(
        owner: String,
        repo: String,
        workflowId: String,
        ref: String = "main",
        githubToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bodyJson = JSONObject().put("ref", ref).toString()
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowId/dispatches")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $githubToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    throw IllegalStateException(extractJsonError(err) ?: "Dispatch HTTP ${resp.code}")
                }
                "Workflow '$workflowId' dispatched on $owner/$repo ($ref)."
            }
        }
    }

    private fun extractJsonError(body: String): String? {
        return runCatching {
            val json = JSONObject(body)
            when {
                json.has("error") -> {
                    val err = json.opt("error")
                    if (err is JSONObject) err.optString("message", body) else err?.toString() ?: body
                }
                json.has("message") -> json.optString("message")
                else -> null
            }
        }.getOrNull()
    }
}
