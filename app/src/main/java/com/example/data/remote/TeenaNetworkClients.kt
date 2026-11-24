package com.example.data.remote

import android.graphics.Bitmap
import android.util.Base64
import com.example.core.security.EmergencyStopController
import com.example.core.security.SecurityAuditor
import com.example.data.local.GeminiModelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
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

data class McpProbeResult(
    val serverName: String,
    val tools: List<String>,
    val latencyMs: Long
)

data class A2aAgentCard(
    val name: String,
    val description: String,
    val rpcUrl: String,
    val skills: List<String>,
    val latencyMs: Long
)

class GeminiHttpException(
    val httpCode: Int,
    val apiStatus: String,
    override val message: String
) : IllegalStateException(message)

class TeenaNetworkClients(
    geminiModelDao: GeminiModelDao? = null,
    private val geminiBaseUrl: String = "https://generativelanguage.googleapis.com",
    private val emergencyStopController: EmergencyStopController? = null
) {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (emergencyStopController?.isStopped() == true) {
                throw IOException("Emergency Stop active: external network calls halted.")
            }
            chain.proceed(chain.request())
        }
        .build()

    val modelRegistry: ModelRegistry = ModelRegistry(httpClient, geminiModelDao, geminiBaseUrl)

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
        userPrompt: String,
        recentHistory: List<Pair<String, String>> = emptyList(),
        attachedImage: Bitmap? = null,
        maxOutputTokens: Int? = null
    ): Result<OpenAiChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isEmpty()) throw IllegalStateException("Gemini API key missing")
            val cleanModel = model.removePrefix("models/").trim()
            if (cleanModel.isEmpty()) throw IllegalStateException("No Gemini chat model selected.")

            val start = System.currentTimeMillis()
            val url = "${geminiBaseUrl.trimEnd('/')}/v1beta/models/$cleanModel:generateContent"
            val payload = buildGeminiRestPayload(systemInstruction, recentHistory, userPrompt, attachedImage, maxOutputTokens)

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw buildGeminiHttpException(resp.code, bodyStr)
                }
                val text = extractGeminiCandidateText(JSONObject(bodyStr))
                if (text.isBlank()) throw IllegalStateException("Gemini REST returned empty content")
                OpenAiChatResult(
                    text = text.trim(),
                    modelUsed = cleanModel,
                    latencyMs = System.currentTimeMillis() - start
                )
            }
        }
    }

    suspend fun streamGeminiDirectRest(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String,
        recentHistory: List<Pair<String, String>> = emptyList(),
        attachedImage: Bitmap? = null,
        onDelta: suspend (String) -> Unit
    ): Result<OpenAiChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isEmpty()) throw IllegalStateException("Gemini API key missing")
            val cleanModel = model.removePrefix("models/").trim()
            if (cleanModel.isEmpty()) throw IllegalStateException("No Gemini chat model selected.")

            val start = System.currentTimeMillis()
            val url = "${geminiBaseUrl.trimEnd('/')}/v1beta/models/$cleanModel:streamGenerateContent?alt=sse"
            val payload = buildGeminiRestPayload(systemInstruction, recentHistory, userPrompt, attachedImage, null)

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    throw buildGeminiHttpException(resp.code, errBody)
                }
                val source = resp.body?.source() ?: throw IllegalStateException("Empty Gemini stream body")
                val accumulated = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data:")) {
                        val jsonStr = line.removePrefix("data:").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue
                        val chunkText = runCatching {
                            extractGeminiCandidateText(JSONObject(jsonStr))
                        }.getOrDefault("")
                        if (chunkText.isNotEmpty()) {
                            accumulated.append(chunkText)
                            onDelta(accumulated.toString())
                        }
                    }
                }
                val finalText = accumulated.toString().trim()
                if (finalText.isBlank()) {
                    throw IllegalStateException("Gemini REST stream returned empty content")
                }
                OpenAiChatResult(
                    text = finalText,
                    modelUsed = cleanModel,
                    latencyMs = System.currentTimeMillis() - start
                )
            }
        }
    }

    private fun buildGeminiHttpException(code: Int, body: String): GeminiHttpException {
        val parsed = runCatching {
            val root = JSONObject(body)
            val errObj = root.optJSONObject("error")
            val status = errObj?.optString("status").orEmpty()
            val msg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: extractJsonError(body)
                ?: "Gemini HTTP $code"
            status to msg
        }.getOrElse { "" to ("Gemini HTTP $code") }
        return GeminiHttpException(
            httpCode = code,
            apiStatus = parsed.first,
            message = SecurityAuditor.redactSecrets(parsed.second)
        )
    }

    private fun buildGeminiRestPayload(
        systemInstruction: String,
        recentHistory: List<Pair<String, String>>,
        userPrompt: String,
        attachedImage: Bitmap?,
        maxOutputTokens: Int?
    ): JSONObject {
        val contentsArray = JSONArray()
        recentHistory.takeLast(8).forEach { (role, msg) ->
            if (msg.isNotBlank()) {
                val mappedRole = if (role.equals("assistant", true) || role.equals("model", true)) "model" else "user"
                contentsArray.put(
                    JSONObject().apply {
                        put("role", mappedRole)
                        put("parts", JSONArray().put(JSONObject().put("text", msg.take(2500))))
                    }
                )
            }
        }
        val currentParts = JSONArray().apply {
            if (attachedImage != null) {
                val scaled = scaleBitmapIfNeeded(attachedImage, 1024)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", b64)
                        }
                    )
                )
            }
            put(JSONObject().put("text", userPrompt))
        }
        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", currentParts)
            }
        )
        return JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
            }
            if (maxOutputTokens != null && maxOutputTokens > 0) {
                put("generationConfig", JSONObject().put("maxOutputTokens", maxOutputTokens))
            }
            put("contents", contentsArray)
        }
    }

    private fun extractGeminiCandidateText(json: JSONObject): String {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isNotEmpty()) sb.append(t)
        }
        return sb.toString()
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
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

    suspend fun createGitHubBranch(
        owner: String,
        repo: String,
        baseBranch: String = "main",
        newBranch: String,
        githubToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseBranch.ifBlank { "main" }
            val refReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/git/ref/heads/$cleanBase")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $githubToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            val baseSha = httpClient.newCall(refReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(extractJsonError(body) ?: "GitHub ref HTTP ${resp.code}")
                }
                JSONObject(body).optJSONObject("object")?.optString("sha").orEmpty()
            }
            if (baseSha.isBlank()) throw IllegalStateException("Could not resolve base SHA for $cleanBase.")

            val createPayload = JSONObject().apply {
                put("ref", "refs/heads/$newBranch")
                put("sha", baseSha)
            }
            val createReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/git/refs")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $githubToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .post(createPayload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(createReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful && resp.code != 422) {
                    throw IllegalStateException(extractJsonError(body) ?: "Create branch HTTP ${resp.code}")
                }
            }
            newBranch
        }
    }

    suspend fun commitFileToGitHubBranch(
        owner: String,
        repo: String,
        branch: String,
        filePath: String,
        fileContent: String,
        commitMessage: String,
        githubToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPath = filePath.trimStart('/')
            val encodedB64 = Base64.encodeToString(fileContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            // Check existing file SHA if updating on branch
            val getReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath?ref=$branch")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $githubToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .get()
                .build()
            val existingSha = runCatching {
                httpClient.newCall(getReq).execute().use { resp ->
                    if (resp.isSuccessful) JSONObject(resp.body?.string().orEmpty()).optString("sha") else ""
                }
            }.getOrDefault("")

            val payload = JSONObject().apply {
                put("message", commitMessage)
                put("content", encodedB64)
                put("branch", branch)
                if (existingSha.isNotBlank()) put("sha", existingSha)
            }
            val putReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $githubToken")
                .addHeader("User-Agent", "TeenaAI-Android")
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(putReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(extractJsonError(body) ?: "Commit file HTTP ${resp.code}")
                }
                JSONObject(body).optJSONObject("commit")?.optString("html_url")
                    .orEmpty()
                    .ifBlank { "https://github.com/$owner/$repo/tree/$branch" }
            }
        }
    }

    suspend fun probeMcpServer(
        endpointUrl: String,
        authToken: String? = null
    ): Result<McpProbeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = validateHttpsOrLoopbackUrl(endpointUrl)
            val start = System.currentTimeMillis()
            val initPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put(
                    "params",
                    JSONObject().apply {
                        put("protocolVersion", "2024-11-05")
                        put("capabilities", JSONObject())
                        put(
                            "clientInfo",
                            JSONObject().put("name", "TeenaAI").put("version", "1.0.0")
                        )
                    }
                )
            }
            val initJson = postJsonRpc(cleanUrl, authToken, initPayload)
            val serverName = initJson.optJSONObject("result")
                ?.optJSONObject("serverInfo")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?: cleanUrl

            val listPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/list")
                put("params", JSONObject())
            }
            val listJson = runCatching { postJsonRpc(cleanUrl, authToken, listPayload) }.getOrNull()
            val toolsArr = listJson?.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            val toolNames = (0 until toolsArr.length()).mapNotNull { i ->
                toolsArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
            McpProbeResult(
                serverName = serverName,
                tools = toolNames,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            )
        }
    }

    suspend fun callMcpAdvisor(
        endpointUrl: String,
        authToken: String? = null,
        toolName: String,
        question: String
    ): Result<OpenAiChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = validateHttpsOrLoopbackUrl(endpointUrl)
            val start = System.currentTimeMillis()
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", System.currentTimeMillis())
                put("method", "tools/call")
                put(
                    "params",
                    JSONObject().apply {
                        put("name", toolName.ifBlank { "advise" })
                        put("arguments", JSONObject().put("question", question).put("prompt", question))
                    }
                )
            }
            val respJson = postJsonRpc(cleanUrl, authToken, payload)
            val resultObj = respJson.optJSONObject("result")
            val contentArr = resultObj?.optJSONArray("content")
            val text = buildString {
                if (contentArr != null) {
                    for (i in 0 until contentArr.length()) {
                        val t = contentArr.optJSONObject(i)?.optString("text").orEmpty()
                        if (t.isNotBlank()) appendLine(t)
                    }
                }
                if (isEmpty() && resultObj != null) {
                    append(resultObj.optString("text").ifBlank { resultObj.toString() })
                }
            }.trim()
            if (text.isBlank()) throw IllegalStateException("MCP server returned empty response")
            OpenAiChatResult(
                text = text,
                modelUsed = "mcp:${toolName.ifBlank { "tools/call" }}",
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            )
        }
    }

    suspend fun fetchA2aAgentCard(
        endpointUrl: String,
        authToken: String? = null
    ): Result<A2aAgentCard> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = validateHttpsOrLoopbackUrl(endpointUrl).trimEnd('/')
            val cardUrl = if (cleanBase.endsWith("agent.json")) cleanBase else "$cleanBase/.well-known/agent.json"
            val start = System.currentTimeMillis()
            val req = Request.Builder()
                .url(cardUrl)
                .addHeader("Accept", "application/json")
                .apply {
                    if (!authToken.isNullOrBlank()) addHeader("Authorization", "Bearer $authToken")
                }
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(extractJsonError(body) ?: "A2A card HTTP ${resp.code}")
                }
                val json = JSONObject(body)
                val skillsArr = json.optJSONArray("skills") ?: JSONArray()
                val skills = (0 until skillsArr.length()).mapNotNull { i ->
                    skillsArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                }
                A2aAgentCard(
                    name = json.optString("name", cleanBase),
                    description = json.optString("description", "A2A Agent"),
                    rpcUrl = json.optString("url", cleanBase).ifBlank { cleanBase },
                    skills = skills,
                    latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                )
            }
        }
    }

    suspend fun sendA2aMessage(
        rpcUrl: String,
        authToken: String? = null,
        messageText: String
    ): Result<OpenAiChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = validateHttpsOrLoopbackUrl(rpcUrl)
            val start = System.currentTimeMillis()
            val msgId = "teena-${System.currentTimeMillis()}"
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", msgId)
                put("method", "message/send")
                put(
                    "params",
                    JSONObject().put(
                        "message",
                        JSONObject().apply {
                            put("role", "user")
                            put("messageId", msgId)
                            put("parts", JSONArray().put(JSONObject().put("kind", "text").put("text", messageText)))
                        }
                    )
                )
            }
            val respJson = postJsonRpc(cleanUrl, authToken, payload)
            val resObj = respJson.optJSONObject("result")
            val text = extractA2aResultText(resObj)
            if (text.isBlank()) throw IllegalStateException("A2A agent returned empty response")
            OpenAiChatResult(
                text = text,
                modelUsed = "a2a-jsonrpc",
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            )
        }
    }

    private fun extractA2aResultText(resObj: JSONObject?): String {
        if (resObj == null) return ""
        val parts = resObj.optJSONArray("parts")
            ?: resObj.optJSONObject("status")?.optJSONObject("message")?.optJSONArray("parts")
            ?: resObj.optJSONArray("artifacts")?.optJSONObject(0)?.optJSONArray("parts")
        if (parts != null) {
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (t.isNotBlank()) sb.appendLine(t)
            }
            if (sb.isNotBlank()) return sb.toString().trim()
        }
        return resObj.optString("text").ifBlank { resObj.toString() }
    }

    private fun postJsonRpc(url: String, authToken: String?, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .apply {
                if (!authToken.isNullOrBlank()) addHeader("Authorization", "Bearer $authToken")
            }
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractJsonError(body) ?: "JSON-RPC HTTP ${resp.code}")
            }
            val json = JSONObject(body)
            val rpcErr = json.optJSONObject("error")
            if (rpcErr != null) {
                throw IllegalStateException(SecurityAuditor.redactSecrets(rpcErr.optString("message", "JSON-RPC error")))
            }
            return json
        }
    }

    private fun validateHttpsOrLoopbackUrl(raw: String): String {
        val clean = raw.trim()
        val isLocalDev = clean.startsWith("http://127.0.0.1") || clean.startsWith("http://localhost")
        if (!clean.startsWith("https://") && !isLocalDev) {
            throw SecurityException("Only HTTPS URLs are permitted for external MCP/A2A endpoints.")
        }
        return clean
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
