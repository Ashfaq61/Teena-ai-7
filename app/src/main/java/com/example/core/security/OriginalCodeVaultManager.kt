package com.example.core.security

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

data class VaultArchiveSaveResult(
    val vaultId: String,
    val repoFullName: String,
    val repoUrl: String,
    val commitSha: String,
    val licenseSpdx: String,
    val attributionText: String,
    val downloadedAtIso: String,
    val downloadedAtMillis: Long,
    val zipFilePath: String,
    val metadataFilePath: String,
    val licenseFilePath: String,
    val zipSha256: String,
    val zipSizeBytes: Long
)

data class VaultOpenVerificationResult(
    val verified: Boolean,
    val status: String, // VERIFIED, FAILED
    val computedSha256: String,
    val expectedSha256: String,
    val message: String,
    val entryPaths: List<String> = emptyList()
)

/**
 * ORIGINAL CODE VAULT (Bina Badle):
 * - Saves exact, unmodified commit-SHA ZIP snapshot + metadata.json + LICENSE.txt in app private storage.
 * - Rejects HIGH RISK (KHATARNAAK), unreviewed (NOT DONE), or missing/disallowed license repos.
 * - Re-computes & verifies SHA-256 on EVERY open; marks FAILED and blocks access on any tampering.
 * - Strict READ-ONLY reference viewer: NEVER executes, loads, or compiles any code inside the vault.
 */
class OriginalCodeVaultManager(private val context: Context) {

    private val vaultRootDir: File by lazy {
        File(context.filesDir, "original_code_vault").apply { mkdirs() }
    }

    suspend fun saveImmutableSnapshot(
        report: RepoRiskReport,
        rawZipBytes: ByteArray
    ): Result<VaultArchiveSaveResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!report.codeReviewed) {
                throw SecurityException("NOT DONE: Asli code review ke baghair repo Vault mein save nahi ho sakta.")
            }
            if (report.riskLevel == RepoRiskLevel.KHATARNAAK) {
                throw SecurityException("KHATARNAAK repo Vault mein save karna block hai: ${report.headlineBanner}")
            }
            if (!report.licenseAllowsUse) {
                throw SecurityException("License maujood nahi (${report.licenseSpdx}): bina valid license Vault save band hai.")
            }
            if (report.commitSha.isBlank()) {
                throw IllegalArgumentException("Commit SHA missing hai; snapshot sirf exact commit SHA ka save hota hai.")
            }
            if (rawZipBytes.isEmpty()) {
                throw IllegalArgumentException("Downloaded ZIP archive khaali hai.")
            }

            val nowMs = System.currentTimeMillis()
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val downloadedIso = isoFormat.format(Date(nowMs))

            val safeSlug = report.repoFullName.lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .trim('_')
                .take(40)
                .ifBlank { "repo" }
            val shortSha = report.commitSha.take(12)
            val vaultId = "vault_${safeSlug}_${shortSha}"

            val entryDir = File(vaultRootDir, vaultId).apply { mkdirs() }
            val zipFile = File(entryDir, "${safeSlug}_${shortSha}.zip")
            val metadataFile = File(entryDir, "metadata.json")
            val licenseFile = File(entryDir, "LICENSE.txt")

            // Remove read-only flag only if replacing same commit snapshot cleanly
            if (zipFile.exists()) zipFile.setWritable(true)
            if (metadataFile.exists()) metadataFile.setWritable(true)
            if (licenseFile.exists()) licenseFile.setWritable(true)

            // Write EXACT unmodified bytes from commit archive
            zipFile.writeBytes(rawZipBytes)
            val sha256 = sha256Hex(rawZipBytes)

            val attribution = buildAttributionText(
                repoFullName = report.repoFullName,
                repoUrl = report.repoUrl,
                commitSha = report.commitSha,
                licenseSpdx = report.licenseSpdx,
                licenseText = report.licenseText
            )
            licenseFile.writeText(attribution, Charsets.UTF_8)

            val metaJson = JSONObject().apply {
                put("vaultId", vaultId)
                put("repoFullName", report.repoFullName)
                put("repoUrl", report.repoUrl)
                put("commitSha", report.commitSha)
                put("licenseSpdx", report.licenseSpdx)
                put("gplWarning", report.gplWarningRomanUrdu)
                put("downloadedAtIso", downloadedIso)
                put("downloadedAtMillis", nowMs)
                put("zipFileName", zipFile.name)
                put("zipSizeBytes", rawZipBytes.size.toLong())
                put("zipSha256", sha256)
                put("riskLevel", report.riskLevel.name)
                put("riskScore", report.riskScore)
                put("readOnlyPolicy", "STRICT_READ_ONLY_NEVER_EXECUTE_OR_COMPILE")
            }
            metadataFile.writeText(metaJson.toString(2), Charsets.UTF_8)

            // Best-effort read-only markers to prevent accidental mutation
            runCatching {
                zipFile.setReadOnly()
                metadataFile.setReadOnly()
                licenseFile.setReadOnly()
            }

            VaultArchiveSaveResult(
                vaultId = vaultId,
                repoFullName = report.repoFullName,
                repoUrl = report.repoUrl,
                commitSha = report.commitSha,
                licenseSpdx = report.licenseSpdx,
                attributionText = attribution,
                downloadedAtIso = downloadedIso,
                downloadedAtMillis = nowMs,
                zipFilePath = zipFile.absolutePath,
                metadataFilePath = metadataFile.absolutePath,
                licenseFilePath = licenseFile.absolutePath,
                zipSha256 = sha256,
                zipSizeBytes = rawZipBytes.size.toLong()
            )
        }
    }

    /**
     * Verifies the ZIP SHA-256 against both stored record and metadata.json BEFORE reading entries.
     * Any mismatch marks verificationStatus = FAILED and blocks access!
     */
    suspend fun openAndVerifyArchive(
        zipFilePath: String,
        metadataFilePath: String,
        expectedSha256FromDb: String
    ): VaultOpenVerificationResult = withContext(Dispatchers.IO) {
        val zipFile = File(zipFilePath)
        val metaFile = File(metadataFilePath)
        if (!zipFile.exists() || !zipFile.isFile) {
            return@withContext VaultOpenVerificationResult(
                verified = false,
                status = "FAILED",
                computedSha256 = "",
                expectedSha256 = expectedSha256FromDb,
                message = "FAILED: Vault ZIP file maujood nahi hai — access block kar diya gaya."
            )
        }
        val metaSha = runCatching {
            if (metaFile.exists()) JSONObject(metaFile.readText(Charsets.UTF_8)).optString("zipSha256") else ""
        }.getOrDefault("")

        val bytes = runCatching { zipFile.readBytes() }.getOrElse {
            return@withContext VaultOpenVerificationResult(
                verified = false,
                status = "FAILED",
                computedSha256 = "",
                expectedSha256 = expectedSha256FromDb,
                message = "FAILED: Vault ZIP file read error (${it.localizedMessage}) — access blocked."
            )
        }
        val computedSha = sha256Hex(bytes)
        val dbMatch = computedSha.equals(expectedSha256FromDb, ignoreCase = true)
        val metaMatch = metaSha.isBlank() || computedSha.equals(metaSha, ignoreCase = true)

        if (!dbMatch || !metaMatch) {
            return@withContext VaultOpenVerificationResult(
                verified = false,
                status = "FAILED",
                computedSha256 = computedSha,
                expectedSha256 = expectedSha256FromDb,
                message = "FAILED: ZIP SHA-256 match nahi hua! Expected=${expectedSha256FromDb.take(16)}..., Actual=${computedSha.take(16)}... File mein tabdeeli hui hai — yeh copy block kar di gayi hai."
            )
        }

        // Safely list ZIP entries in memory without extracting executable files to disk
        val entryList = mutableListOf<String>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && entryList.size < 200) {
                    val name = entry.name
                    if (!entry.isDirectory && isSafeZipEntryPath(name)) {
                        entryList.add(name)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.onFailure { err ->
            return@withContext VaultOpenVerificationResult(
                verified = false,
                status = "FAILED",
                computedSha256 = computedSha,
                expectedSha256 = expectedSha256FromDb,
                message = "FAILED: Corrupted ZIP archive (${err.localizedMessage}) — access blocked."
            )
        }

        VaultOpenVerificationResult(
            verified = true,
            status = "VERIFIED",
            computedSha256 = computedSha,
            expectedSha256 = expectedSha256FromDb,
            message = "VERIFIED: ZIP SHA-256 100% match (${computedSha.take(16)}...). Read-only mode active.",
            entryPaths = entryList.sorted()
        )
    }

    /**
     * Reads a single text file inside the ZIP archive ONLY after verifying ZIP SHA-256 first.
     * Never writes the entry to disk and never executes anything.
     */
    suspend fun readEntryTextReadOnly(
        zipFilePath: String,
        metadataFilePath: String,
        expectedSha256FromDb: String,
        targetEntryPath: String,
        maxChars: Int = 12_000
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val verify = openAndVerifyArchive(zipFilePath, metadataFilePath, expectedSha256FromDb)
            if (!verify.verified) {
                throw SecurityException(verify.message)
            }
            if (!isSafeZipEntryPath(targetEntryPath)) {
                throw SecurityException("Blocked unsafe ZIP path: $targetEntryPath")
            }

            val bytes = File(zipFilePath).readBytes()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == targetEntryPath) {
                        val contentBytes = zis.readBytes()
                        if (contentBytes.size > 512_000) {
                            return@runCatching String(contentBytes.copyOf(maxChars), Charsets.UTF_8) +
                                "\n\n[... Read-only preview truncated (${contentBytes.size} bytes) ...]"
                        }
                        if (looksBinary(contentBytes)) {
                            return@runCatching "[Binary file (${contentBytes.size} bytes) — execution aur binary preview band hai.]"
                        }
                        return@runCatching String(contentBytes, Charsets.UTF_8).take(maxChars)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            throw IllegalArgumentException("Entry '$targetEntryPath' ZIP mein nahi mili.")
        }
    }

    private fun isSafeZipEntryPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains("..\\") || path.contains("../") || path == "..") {
            return false
        }
        return true
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sampleLen = minOf(bytes.size, 512)
        for (i in 0 until sampleLen) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    private fun buildAttributionText(
        repoFullName: String,
        repoUrl: String,
        commitSha: String,
        licenseSpdx: String,
        licenseText: String
    ): String = buildString {
        appendLine("ORIGINAL CODE VAULT ATTRIBUTION & LICENSE")
        appendLine("Repository: $repoFullName ($repoUrl)")
        appendLine("Commit SHA: $commitSha")
        appendLine("SPDX License: $licenseSpdx")
        val copyleftNote = RepoRiskAnalyzer.buildCopyleftWarning(licenseSpdx)
        if (copyleftNote.isNotBlank()) {
            appendLine("Notice: $copyleftNote")
        }
        appendLine("Policy: Read-only archival copy. Original code must remain unmodified.")
        appendLine("------------------------------------------------------------")
        if (licenseText.isNotBlank()) {
            append(licenseText)
        } else {
            append("License SPDX declared by repository: $licenseSpdx (see $repoUrl)")
        }
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
