package com.example.ai

import com.example.core.security.SecurityAuditor
import com.example.data.local.ErrorDoctorFixEntity
import com.example.system.DevStudioEngine
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class ParsedGradleErrorLine(
    val rawLine: String,
    val filePath: String,
    val relativeWorkspacePath: String,
    val lineNumber: Int,
    val columnNumber: Int,
    val message: String,
    val hinglishExplanation: String,
    val errorSignature: String
)

data class ErrorDoctorPatchProposal(
    val strategy: String, // FIX_HISTORY, KNOWN_PATTERN, GEMINI_PATCH
    val targetRelativePath: String,
    val lineNumber: Int,
    val hinglishSummary: String,
    val diffPreview: String,
    val originalSnippet: String,
    val replacementSnippet: String,
    val fullUpdatedFileContent: String? = null,
    val isApplicable: Boolean = true
)

data class ErrorDoctorReport(
    val attemptNumber: Int,
    val maxAttempts: Int = ErrorDoctorEngine.MAX_ATTEMPTS,
    val errorLines: List<ParsedGradleErrorLine>,
    val whatWentWrongBlock: String,
    val summaryHinglish: String,
    val patchProposal: ErrorDoctorPatchProposal? = null,
    val rawLogExcerpt: String = "",
    val exhaustedAutoLoop: Boolean = false
)

/**
 * Phase 11E: Error Doctor for Dev Studio APK builds.
 * 1. Parses `e: file://` lines and `* What went wrong:` from Gradle logs.
 * 2. Explains causes in simple Hinglish.
 * 3. Checks local Fix History & Known-Pattern fixes first (missing import, unresolved ref,
 *    missing dep/repo, wrong JDK, duplicate class, manifest merger, resource not found, KSP/Kapt).
 * 4. Calls shared GeminiRepository with ONLY the relevant file snippet + error lines to produce a patch diff.
 */
class ErrorDoctorEngine(
    private val geminiRepository: GeminiRepository? = null
) {
    fun analyzeGradleFailure(
        rawLog: String,
        workspaceDir: File,
        fixHistory: List<ErrorDoctorFixEntity>,
        attemptNumber: Int
    ): ErrorDoctorReport {
        val parsedLines = parseGradleErrorLines(rawLog, workspaceDir)
        val whatWentWrong = extractWhatWentWrongBlock(rawLog)

        if (attemptNumber >= MAX_ATTEMPTS) {
            val summary = buildString {
                appendLine("Max $MAX_ATTEMPTS auto-fix attempts poore ho gaye. Loop rok diya gaya hai.")
                if (parsedLines.isNotEmpty()) {
                    appendLine("Main error (${parsedLines.first().relativeWorkspacePath}:${parsedLines.first().lineNumber}): ${parsedLines.first().hinglishExplanation}")
                } else if (whatWentWrong.isNotBlank()) {
                    appendLine("Gradle cause: ${explainWhatWentWrongHinglish(whatWentWrong)}")
                }
            }.trim()
            return ErrorDoctorReport(
                attemptNumber = attemptNumber,
                errorLines = parsedLines,
                whatWentWrongBlock = whatWentWrong,
                summaryHinglish = summary,
                patchProposal = null,
                rawLogExcerpt = rawLog.takeLast(3000),
                exhaustedAutoLoop = true
            )
        }

        // 1. Check local Fix History first for faster solve
        val primaryError = parsedLines.firstOrNull()
        val historyMatch = if (primaryError != null) {
            fixHistory.firstOrNull { it.errorSignature == primaryError.errorSignature && it.patchReplacement.isNotBlank() }
        } else null

        var proposal: ErrorDoctorPatchProposal? = null
        if (historyMatch != null && primaryError != null) {
            val targetFile = resolveWorkspaceFile(workspaceDir, primaryError.relativeWorkspacePath)
            if (targetFile != null && targetFile.exists()) {
                val content = targetFile.readText(Charsets.UTF_8)
                val patched = applyHistoryFixToContent(content, historyMatch.patchReplacement)
                if (patched != null && patched != content) {
                    proposal = ErrorDoctorPatchProposal(
                        strategy = "FIX_HISTORY",
                        targetRelativePath = primaryError.relativeWorkspacePath,
                        lineNumber = primaryError.lineNumber,
                        hinglishSummary = "Fix History se pehle ka tested fix mil gaya: ${historyMatch.patchSummary}",
                        diffPreview = buildUnifiedDiffPreview(primaryError.relativeWorkspacePath, content, patched),
                        originalSnippet = "",
                        replacementSnippet = historyMatch.patchReplacement,
                        fullUpdatedFileContent = patched
                    )
                }
            }
        }

        // 2. Try deterministic Known-Pattern fix next
        if (proposal == null) {
            proposal = matchKnownPatternFix(parsedLines, whatWentWrong, workspaceDir)
        }

        val summaryHinglish = buildString {
            when {
                parsedLines.isNotEmpty() -> {
                    appendLine("${parsedLines.size} Kotlin/Gradle error(s) mile:")
                    parsedLines.take(3).forEach { e ->
                        appendLine("• ${e.relativeWorkspacePath}:${e.lineNumber} — ${e.hinglishExplanation}")
                    }
                }
                whatWentWrong.isNotBlank() -> {
                    appendLine(explainWhatWentWrongHinglish(whatWentWrong))
                }
                else -> {
                    appendLine("Build fail hui, lekin exact 'e: file://' line nahi mili. Log snippet check karein ya Gemini Fix try karein.")
                }
            }
        }.trim()

        return ErrorDoctorReport(
            attemptNumber = attemptNumber,
            errorLines = parsedLines,
            whatWentWrongBlock = whatWentWrong,
            summaryHinglish = summaryHinglish,
            patchProposal = proposal,
            rawLogExcerpt = rawLog.takeLast(3000),
            exhaustedAutoLoop = false
        )
    }

    suspend fun askGeminiForPatch(
        report: ErrorDoctorReport,
        workspaceDir: File,
        preferredModel: String
    ): Result<ErrorDoctorPatchProposal> {
        val primary = report.errorLines.firstOrNull()
        val relPath = primary?.relativeWorkspacePath
            ?.takeIf { it.isNotBlank() }
            ?: inferTargetFileFromWhatWentWrong(report.whatWentWrongBlock, workspaceDir)
            ?: "app/src/main/java/com/example/app/MainActivity.kt"

        val targetFile = resolveWorkspaceFile(workspaceDir, relPath)
        val fileText = if (targetFile != null && targetFile.exists()) {
            targetFile.readText(Charsets.UTF_8)
        } else ""

        // Send ONLY the relevant slice (±40 lines) + exact error lines
        val snippetWithLineNumbers = extractFocusedFileWindow(fileText, primary?.lineNumber ?: 1, radius = 40)
        val errorContext = buildString {
            report.errorLines.take(6).forEach { appendLine(it.rawLine) }
            if (report.whatWentWrongBlock.isNotBlank()) {
                appendLine()
                appendLine(report.whatWentWrongBlock.take(900))
            }
        }.trim().ifBlank { report.rawLogExcerpt.takeLast(900) }

        val systemInstruction = """
            You are Teena Error Doctor. Fix Android/Gradle/Kotlin compile errors accurately with minimal diff.
            Return ONLY valid JSON with keys:
            "hinglish_explanation": short Hinglish reason (1 line),
            "target_file": relative workspace file path,
            "find_exact": exact existing line(s) from the file to replace (must match verbatim),
            "replace_with": corrected replacement line(s).
            Keep code minimal, valid Kotlin/Gradle, no markdown outside JSON.
        """.trimIndent()

        val userPrompt = """
            Target file: $relPath
            Gradle errors:
            $errorContext

            Relevant file slice:
            ```
            $snippetWithLineNumbers
            ```
        """.trimIndent()

        val repo = geminiRepository
            ?: return Result.failure(IllegalStateException("Gemini is not available — GeminiRepository unavailable."))

        return repo.askDirectText(
            userPrompt = userPrompt,
            systemInstruction = systemInstruction,
            preferredModel = preferredModel
        ).mapCatching { out ->
            val cleaned = out.text.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleaned)
            val chosenPath = obj.optString("target_file").ifBlank { relPath }
            val actualFile = resolveWorkspaceFile(workspaceDir, chosenPath) ?: targetFile
            val currentContent = if (actualFile != null && actualFile.exists()) {
                actualFile.readText(Charsets.UTF_8)
            } else fileText

            val findExact = obj.optString("find_exact")
            val replaceWith = obj.optString("replace_with")
            val hinglish = obj.optString("hinglish_explanation")
                .ifBlank { "Gemini ne fix suggest kiya (${out.modelUsed})" }

            val updatedContent = when {
                findExact.isNotBlank() && currentContent.contains(findExact) ->
                    currentContent.replaceFirst(findExact, replaceWith)
                findExact.isBlank() && replaceWith.isNotBlank() && currentContent.isNotBlank() ->
                    replaceWith
                else -> throw IllegalStateException(
                    "Gemini patch file ke exact lines se match nahi hua.\nSuggested:\n$replaceWith"
                )
            }

            ErrorDoctorPatchProposal(
                strategy = "GEMINI_PATCH",
                targetRelativePath = chosenPath,
                lineNumber = primary?.lineNumber ?: 1,
                hinglishSummary = hinglish,
                diffPreview = buildUnifiedDiffPreview(chosenPath, currentContent, updatedContent),
                originalSnippet = findExact,
                replacementSnippet = replaceWith,
                fullUpdatedFileContent = updatedContent,
                isApplicable = updatedContent != currentContent
            )
        }.recoverCatching { err ->
            throw IllegalStateException(SecurityAuditor.redactSecrets(err.message ?: "Gemini patch failed"))
        }
    }

    fun applyPatchProposal(
        workspaceDir: File,
        proposal: ErrorDoctorPatchProposal
    ): Result<File> = runCatching {
        val target = DevStudioEngine.resolveSandboxPath(workspaceDir, proposal.targetRelativePath).getOrThrow()
        val updated = proposal.fullUpdatedFileContent
            ?: throw IllegalStateException("Patch content unavailable.")
        target.parentFile?.mkdirs()
        target.writeText(updated, Charsets.UTF_8)
        target
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        private val eFileRegex = Regex(
            """^e:\s*(?:file://)?([^\s:]+(?:/[^\s:]+)+):(\d+):(\d+)?\s*(.*)$"""
        )

        private val knownImportMap = mapOf(
            "LaunchedEffect" to "import androidx.compose.runtime.LaunchedEffect",
            "remember" to "import androidx.compose.runtime.remember",
            "mutableStateOf" to "import androidx.compose.runtime.mutableStateOf",
            "getValue" to "import androidx.compose.runtime.getValue",
            "setValue" to "import androidx.compose.runtime.setValue",
            "Composable" to "import androidx.compose.runtime.Composable",
            "Modifier" to "import androidx.compose.ui.Modifier",
            "Alignment" to "import androidx.compose.ui.Alignment",
            "Arrangement" to "import androidx.compose.foundation.layout.Arrangement",
            "Column" to "import androidx.compose.foundation.layout.Column",
            "Row" to "import androidx.compose.foundation.layout.Row",
            "Spacer" to "import androidx.compose.foundation.layout.Spacer",
            "fillMaxSize" to "import androidx.compose.foundation.layout.fillMaxSize",
            "fillMaxWidth" to "import androidx.compose.foundation.layout.fillMaxWidth",
            "padding" to "import androidx.compose.foundation.layout.padding",
            "dp" to "import androidx.compose.ui.unit.dp",
            "sp" to "import androidx.compose.ui.unit.sp",
            "Color" to "import androidx.compose.ui.graphics.Color",
            "Icons" to "import androidx.compose.material.icons.Icons",
            "Text" to "import androidx.compose.material3.Text",
            "Button" to "import androidx.compose.material3.Button",
            "MaterialTheme" to "import androidx.compose.material3.MaterialTheme",
            "Scaffold" to "import androidx.compose.material3.Scaffold",
            "Intent" to "import android.content.Intent",
            "Context" to "import android.content.Context",
            "Bundle" to "import android.os.Bundle",
            "File" to "import java.io.File",
            "Dispatchers" to "import kotlinx.coroutines.Dispatchers",
            "launch" to "import kotlinx.coroutines.launch"
        )

        fun parseGradleErrorLines(rawLog: String, workspaceDir: File? = null): List<ParsedGradleErrorLine> {
            val out = mutableListOf<ParsedGradleErrorLine>()
            val seen = HashSet<String>()
            rawLog.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("e: file://") || trimmed.startsWith("e: /")) {
                    val m = eFileRegex.find(trimmed)
                    if (m != null) {
                        val rawPath = m.groupValues[1]
                        val lineNum = m.groupValues[2].toIntOrNull() ?: 1
                        val colNum = m.groupValues[3].toIntOrNull() ?: 1
                        val msg = m.groupValues[4].trim()
                        val rel = normalizeToRelativeWorkspacePath(rawPath, workspaceDir)
                        val sig = buildErrorSignature(rel, msg)
                        if (seen.add("$rel:$lineNum:$msg")) {
                            out.add(
                                ParsedGradleErrorLine(
                                    rawLine = trimmed,
                                    filePath = rawPath,
                                    relativeWorkspacePath = rel,
                                    lineNumber = lineNum,
                                    columnNumber = colNum,
                                    message = msg,
                                    hinglishExplanation = explainErrorLineHinglish(msg),
                                    errorSignature = sig
                                )
                            )
                        }
                    } else if (seen.add(trimmed)) {
                        val msg = trimmed.removePrefix("e:").trim()
                        out.add(
                            ParsedGradleErrorLine(
                                rawLine = trimmed,
                                filePath = "",
                                relativeWorkspacePath = "",
                                lineNumber = 1,
                                columnNumber = 1,
                                message = msg,
                                hinglishExplanation = explainErrorLineHinglish(msg),
                                errorSignature = buildErrorSignature("", msg)
                            )
                        )
                    }
                }
            }
            return out
        }

        fun extractWhatWentWrongBlock(rawLog: String): String {
            val lines = rawLog.lines()
            val idx = lines.indexOfFirst { it.contains("* What went wrong:", ignoreCase = true) }
            if (idx == -1) return ""
            val collected = mutableListOf<String>()
            for (i in idx until minOf(lines.size, idx + 14)) {
                val l = lines[i]
                if (i > idx && l.trim().startsWith("* Try:")) break
                if (l.isNotBlank()) collected.add(l.trimEnd())
            }
            return collected.joinToString("\n")
        }

        fun explainErrorLineHinglish(message: String): String {
            val lower = message.lowercase(Locale.US)
            return when {
                lower.contains("unresolved reference") -> {
                    val sym = Regex("""'([^']+)'|reference:\s*([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE)
                        .find(message)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }.orEmpty()
                    if (sym.isNotBlank()) {
                        "'$sym' mil nahi raha — import missing hai ya naam/dependency galat hai."
                    } else {
                        "Symbol/function mil nahi raha — import ya dependency missing ho sakti hai."
                    }
                }
                lower.contains("type mismatch") -> "Data type match nahi ho raha (expected aur actual type alag hain)."
                lower.contains("expecting") || lower.contains("syntax") -> "Syntax error hai (bracket, semicolon ya comma check karein)."
                lower.contains("overload resolution ambiguity") -> "Same naam ke 2 functions match ho rahe hain — clear parameter types dein."
                lower.contains("cannot infer a type") -> "Kotlin type guess nahi kar paa raha — explicit type likhein."
                lower.contains("modifier") && lower.contains("not allowed") -> "Galat keyword/modifier use hua hai."
                lower.contains("override") -> "Parent class method signature `override` se match nahi ho raha."
                else -> message.take(140)
            }
        }

        fun explainWhatWentWrongHinglish(block: String): String {
            val lower = block.lowercase(Locale.US)
            return when {
                lower.contains("could not resolve") || lower.contains("failed to resolve") ->
                    "Dependency download/resolve nahi hui — library version ya google()/mavenCentral() repository check karein."
                lower.contains("unsupported class file major version") || lower.contains("jvm") && lower.contains("17") ->
                    "JDK version mismatch hai — workflow aur Gradle mein JDK 17 set hona chahiye."
                lower.contains("duplicate class") ->
                    "Duplicate class error — ek hi library ke 2 versions project mein aa gaye hain."
                lower.contains("manifest merger failed") ->
                    "AndroidManifest merge fail — android:exported ya tools:replace attribute missing hai."
                lower.contains("resource") && lower.contains("not found") ->
                    "XML/Resource file ya string/drawable ID nahi mil rahi."
                lower.contains("ksp") || lower.contains("kapt") ->
                    "KSP/Kapt annotation processor error — Kotlin aur KSP version sync check karein."
                else -> "Gradle build fail hui: ${block.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()}"
            }
        }

        fun matchKnownPatternFix(
            errors: List<ParsedGradleErrorLine>,
            whatWentWrong: String,
            workspaceDir: File
        ): ErrorDoctorPatchProposal? {
            // 1. Check missing imports for known Compose / Kotlin / Android symbols
            for (err in errors) {
                val m = Regex("""Unresolved reference[:\s']+(\w+)""", RegexOption.IGNORE_CASE).find(err.message)
                val symbol = m?.groupValues?.getOrNull(1).orEmpty()
                val importLine = knownImportMap[symbol]
                if (importLine != null && err.relativeWorkspacePath.isNotBlank()) {
                    val file = resolveWorkspaceFile(workspaceDir, err.relativeWorkspacePath)
                    if (file != null && file.exists()) {
                        val content = file.readText(Charsets.UTF_8)
                        if (!content.contains(importLine)) {
                            val patched = insertImportStatement(content, importLine)
                            return ErrorDoctorPatchProposal(
                                strategy = "KNOWN_PATTERN",
                                targetRelativePath = err.relativeWorkspacePath,
                                lineNumber = err.lineNumber,
                                hinglishSummary = "Known fix: '$symbol' ke liye missing import `$importLine` add kar diya.",
                                diffPreview = buildUnifiedDiffPreview(err.relativeWorkspacePath, content, patched),
                                originalSnippet = "",
                                replacementSnippet = importLine,
                                fullUpdatedFileContent = patched
                            )
                        }
                    }
                }
            }

            val lowerWhat = whatWentWrong.lowercase(Locale.US)

            // 2. Missing google() or mavenCentral() repository in settings.gradle(.kts)
            if (lowerWhat.contains("could not resolve") || lowerWhat.contains("failed to resolve") || lowerWhat.contains("could not find")) {
                val settingsFile = listOf("settings.gradle.kts", "settings.gradle")
                    .map { File(workspaceDir, it) }
                    .firstOrNull { it.exists() }
                if (settingsFile != null) {
                    val content = settingsFile.readText(Charsets.UTF_8)
                    if (!content.contains("google()") || !content.contains("mavenCentral()")) {
                        val rel = settingsFile.relativeTo(workspaceDir).invariantSeparatorsPath
                        val patched = ensureRepositoriesInSettings(content)
                        if (patched != content) {
                            return ErrorDoctorPatchProposal(
                                strategy = "KNOWN_PATTERN",
                                targetRelativePath = rel,
                                lineNumber = 1,
                                hinglishSummary = "Known fix: `google()` aur `mavenCentral()` repositories $rel mein add kar diye.",
                                diffPreview = buildUnifiedDiffPreview(rel, content, patched),
                                originalSnippet = content.take(200),
                                replacementSnippet = "google()\nmavenCentral()",
                                fullUpdatedFileContent = patched
                            )
                        }
                    }
                }
            }

            // 3. Manifest merger missing android:exported on launcher activity
            if (lowerWhat.contains("manifest merger failed") || lowerWhat.contains("android:exported")) {
                val manifest = File(workspaceDir, "app/src/main/AndroidManifest.xml")
                if (manifest.exists()) {
                    val content = manifest.readText(Charsets.UTF_8)
                    if (content.contains("<activity") && !content.contains("android:exported")) {
                        val patched = content.replaceFirst("<activity", "<activity\n            android:exported=\"true\"")
                        val rel = "app/src/main/AndroidManifest.xml"
                        return ErrorDoctorPatchProposal(
                            strategy = "KNOWN_PATTERN",
                            targetRelativePath = rel,
                            lineNumber = 1,
                            hinglishSummary = "Known fix: Android 12+ ke liye `<activity android:exported=\"true\">` add kar diya.",
                            diffPreview = buildUnifiedDiffPreview(rel, content, patched),
                            originalSnippet = "<activity",
                            replacementSnippet = "<activity\n            android:exported=\"true\"",
                            fullUpdatedFileContent = patched
                        )
                    }
                }
            }

            // 4. Wrong JDK / Java 11 vs 17 mismatch in app/build.gradle(.kts)
            if (lowerWhat.contains("unsupported class file major version") || lowerWhat.contains("java 17")) {
                val buildFile = listOf("app/build.gradle.kts", "app/build.gradle")
                    .map { File(workspaceDir, it) }
                    .firstOrNull { it.exists() }
                if (buildFile != null) {
                    val content = buildFile.readText(Charsets.UTF_8)
                    val patched = content
                        .replace("JavaVersion.VERSION_1_8", "JavaVersion.VERSION_17")
                        .replace("JavaVersion.VERSION_11", "JavaVersion.VERSION_17")
                        .replace("jvmTarget = \"1.8\"", "jvmTarget = \"17\"")
                        .replace("jvmTarget = \"11\"", "jvmTarget = \"17\"")
                    if (patched != content) {
                        val rel = buildFile.relativeTo(workspaceDir).invariantSeparatorsPath
                        return ErrorDoctorPatchProposal(
                            strategy = "KNOWN_PATTERN",
                            targetRelativePath = rel,
                            lineNumber = 1,
                            hinglishSummary = "Known fix: CompileOptions & jvmTarget ko Java 17 par align kar diya.",
                            diffPreview = buildUnifiedDiffPreview(rel, content, patched),
                            originalSnippet = "VERSION_11",
                            replacementSnippet = "VERSION_17",
                            fullUpdatedFileContent = patched
                        )
                    }
                }
            }

            // 5. Missing string resource (resource string/xxx not found)
            val missingStringMatch = Regex("""resource string/([a-zA-Z0-9_]+)\s+not found""").find(whatWentWrong)
            if (missingStringMatch != null) {
                val key = missingStringMatch.groupValues[1]
                val stringsXml = File(workspaceDir, "app/src/main/res/values/strings.xml")
                if (stringsXml.exists()) {
                    val content = stringsXml.readText(Charsets.UTF_8)
                    if (!content.contains("name=\"$key\"") && content.contains("</resources>")) {
                        val patched = content.replace(
                            "</resources>",
                            "    <string name=\"$key\">${key.replace('_', ' ')}</string>\n</resources>"
                        )
                        val rel = "app/src/main/res/values/strings.xml"
                        return ErrorDoctorPatchProposal(
                            strategy = "KNOWN_PATTERN",
                            targetRelativePath = rel,
                            lineNumber = 1,
                            hinglishSummary = "Known fix: Missing string `$key` strings.xml mein add kar diya.",
                            diffPreview = buildUnifiedDiffPreview(rel, content, patched),
                            originalSnippet = "</resources>",
                            replacementSnippet = "<string name=\"$key\">...</string>",
                            fullUpdatedFileContent = patched
                        )
                    }
                }
            }

            return null
        }

        fun normalizeToRelativeWorkspacePath(rawPath: String, workspaceDir: File?): String {
            val clean = rawPath.replace('\\', '/')
            if (workspaceDir != null) {
                val wsNorm = workspaceDir.absolutePath.replace('\\', '/').trimEnd('/') + "/"
                if (clean.startsWith(wsNorm)) return clean.removePrefix(wsNorm)
            }
            val markers = listOf("/workspace/", "/app/src/", "/src/main/", "/app/build.gradle", "/settings.gradle", "/build.gradle")
            for (m in markers) {
                val idx = clean.indexOf(m)
                if (idx >= 0) {
                    return if (m == "/workspace/") clean.substring(idx + m.length)
                    else clean.substring(idx + 1)
                }
            }
            return clean.substringAfterLast("/").ifBlank { clean }
        }

        fun resolveWorkspaceFile(workspaceDir: File, relativeOrName: String): File? {
            if (relativeOrName.isBlank()) return null
            val direct = DevStudioEngine.resolveSandboxPath(workspaceDir, relativeOrName).getOrNull()
            if (direct != null && direct.exists()) return direct
            val name = relativeOrName.substringAfterLast('/')
            return workspaceDir.walkTopDown().firstOrNull { it.isFile && it.name == name }
        }

        private fun inferTargetFileFromWhatWentWrong(what: String, workspaceDir: File): String? {
            val lower = what.lowercase(Locale.US)
            return when {
                lower.contains("manifest") -> "app/src/main/AndroidManifest.xml"
                lower.contains("settings.gradle") -> "settings.gradle.kts"
                lower.contains("build.gradle") || lower.contains("dependency") || lower.contains("ksp") -> "app/build.gradle.kts"
                else -> workspaceDir.walkTopDown()
                    .firstOrNull { it.isFile && it.extension == "kt" }
                    ?.relativeTo(workspaceDir)?.invariantSeparatorsPath
            }
        }

        private fun insertImportStatement(content: String, importLine: String): String {
            val lines = content.lines().toMutableList()
            val lastImportIdx = lines.indexOfLast { it.trim().startsWith("import ") }
            val pkgIdx = lines.indexOfFirst { it.trim().startsWith("package ") }
            val insertAt = when {
                lastImportIdx >= 0 -> lastImportIdx + 1
                pkgIdx >= 0 -> pkgIdx + 1
                else -> 0
            }
            if (pkgIdx >= 0 && lastImportIdx < 0) {
                lines.add(insertAt, "")
                lines.add(insertAt + 1, importLine)
            } else {
                lines.add(insertAt, importLine)
            }
            return lines.joinToString("\n")
        }

        private fun ensureRepositoriesInSettings(content: String): String {
            return if (content.contains("repositories {")) {
                content.replaceFirst("repositories {", "repositories {\n        google()\n        mavenCentral()")
            } else {
                content + "\ndependencyResolutionManagement {\n    repositories {\n        google()\n        mavenCentral()\n    }\n}\n"
            }
        }

        private fun applyHistoryFixToContent(content: String, replacement: String): String? {
            val trimmed = replacement.trim()
            if (trimmed.startsWith("import ") && !content.contains(trimmed)) {
                return insertImportStatement(content, trimmed)
            }
            return null
        }

        fun extractFocusedFileWindow(content: String, centerLine: Int, radius: Int = 35): String {
            if (content.isBlank()) return "(empty file)"
            val lines = content.lines()
            val start = (centerLine - radius - 1).coerceAtLeast(0)
            val end = (centerLine + radius).coerceAtMost(lines.size)
            return (start until end).joinToString("\n") { i ->
                "${i + 1}: ${lines[i]}"
            }
        }

        fun buildUnifiedDiffPreview(filePath: String, before: String, after: String): String {
            val oldLines = before.lines()
            val newLines = after.lines()
            val sb = StringBuilder("--- a/$filePath\n+++ b/$filePath\n")
            val max = maxOf(oldLines.size, newLines.size)
            var shown = 0
            for (i in 0 until max) {
                val o = oldLines.getOrNull(i)
                val n = newLines.getOrNull(i)
                if (o != n) {
                    if (o != null) sb.appendLine("-$o")
                    if (n != null) sb.appendLine("+$n")
                    shown++
                    if (shown >= 24) break
                }
            }
            return sb.toString().trim()
        }

        private fun buildErrorSignature(relPath: String, message: String): String {
            val normMsg = message.lowercase(Locale.US).replace(Regex("\\d+"), "#").take(80)
            return "${relPath.substringAfterLast('/')}:$normMsg"
        }
    }
}
