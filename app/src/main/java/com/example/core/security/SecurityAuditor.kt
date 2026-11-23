package com.example.core.security

data class SecurityScanResult(
    val passed: Boolean,
    val score: Int,
    val licenseStatus: String,
    val findings: List<String>
)

/**
 * Performs static security, license, and Play Policy compliance checks on discovered repositories,
 * generated tool specs, and automation workflows.
 */
object SecurityAuditor {

    private val permissiveLicenses = setOf(
        "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Unlicense", "CC0-1.0"
    )

    private val restrictedPatterns = listOf(
        "DexClassLoader" to "Dynamic code loading (.dex/.jar) is prohibited by Google Play policy.",
        "PathClassLoader" to "Dynamic external class loading is prohibited.",
        "System.load(" to "Loading external native .so binaries is prohibited.",
        "Runtime.getRuntime().exec" to "Unvalidated shell command execution detected.",
        "http://" to "Cleartext HTTP endpoint detected; HTTPS is required.",
        "AIzaSy" to "Potential hardcoded Google API key literal detected.",
        "gsk_" to "Potential hardcoded Groq API key literal detected.",
        "sk-or-" to "Potential hardcoded OpenRouter key literal detected.",
        "ghp_" to "Potential hardcoded GitHub personal access token detected."
    )

    fun auditToolOrCode(
        name: String,
        sourceCodeOrSpec: String,
        licenseSpdx: String? = "MIT",
        targetUrl: String? = null
    ): SecurityScanResult {
        val findings = mutableListOf<String>()
        var score = 100

        // License verification
        val normalizedLicense = licenseSpdx?.trim().orEmpty()
        val licenseStatus = when {
            normalizedLicense.isEmpty() || normalizedLicense.equals("NOASSERTION", ignoreCase = true) -> {
                score -= 20
                findings.add("Missing or unverified SPDX license.")
                "UNVERIFIED"
            }
            permissiveLicenses.any { it.equals(normalizedLicense, ignoreCase = true) } -> {
                "VERIFIED ($normalizedLicense)"
            }
            normalizedLicense.contains("GPL", ignoreCase = true) -> {
                score -= 15
                findings.add("Copyleft license ($normalizedLicense) requires source distribution review.")
                "RESTRICTED ($normalizedLicense)"
            }
            else -> {
                score -= 5
                "REVIEW ($normalizedLicense)"
            }
        }

        // URL transport verification
        if (!targetUrl.isNullOrBlank() && targetUrl.startsWith("http://", ignoreCase = true)) {
            score -= 25
            findings.add("Insecure cleartext target URL: $targetUrl")
        }

        // Static dangerous pattern check
        restrictedPatterns.forEach { (pattern, warning) ->
            if (sourceCodeOrSpec.contains(pattern)) {
                score -= 30
                findings.add("[$name] $warning")
            }
        }

        if (findings.isEmpty()) {
            findings.add("Passed static pattern check, HTTPS transport check, and license audit.")
        }

        val finalScore = score.coerceIn(0, 100)
        return SecurityScanResult(
            passed = finalScore >= 70 && findings.none { it.contains("prohibited", ignoreCase = true) },
            score = finalScore,
            licenseStatus = licenseStatus,
            findings = findings
        )
    }

    fun redactSecrets(input: String): String {
        return input
            .replace(Regex("(AIza[0-9A-Za-z\\-_]{20,})"), "[REDACTED_GOOGLE_KEY]")
            .replace(Regex("(gsk_[0-9A-Za-z]{16,})"), "[REDACTED_GROQ_KEY]")
            .replace(Regex("(sk-or-v1-[0-9A-Za-z]{16,})"), "[REDACTED_OPENROUTER_KEY]")
            .replace(Regex("(ghp_[0-9A-Za-z]{16,})"), "[REDACTED_GITHUB_TOKEN]")
            .replace(Regex("(github_pat_[0-9A-Za-z_]{20,})"), "[REDACTED_GITHUB_PAT]")
    }
}
