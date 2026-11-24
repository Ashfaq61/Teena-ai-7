package com.example.core.security

enum class RepoRiskLevel(val label: String) {
    NOT_DONE("NOT DONE"),
    SAFE("Safe"),
    EHTIYAT("Ehtiyat"),
    KHATARNAAK("Khatarnaak")
}

data class RepoRiskFinding(
    val category: String,
    val severity: RepoRiskLevel,
    val titleRomanUrdu: String,
    val detailRomanUrdu: String,
    val recommendationRomanUrdu: String,
    val matchedFile: String = ""
)

data class AuditedRepoFile(
    val path: String,
    val content: String
)

data class RepoRiskReport(
    val repoFullName: String,
    val repoUrl: String,
    val commitSha: String,
    val licenseSpdx: String,
    val licenseText: String,
    val stars: Int,
    val ownerLogin: String,
    val ownerAccountAgeDays: Long?,
    val codeReviewed: Boolean,
    val reviewStatusLabel: String, // "NOT DONE" or "DONE (N files)"
    val filesReviewedCount: Int,
    val riskLevel: RepoRiskLevel,
    val riskScore: Int, // 0..100, null/0 while NOT DONE
    val headlineBanner: String,
    val gplWarningRomanUrdu: String = "",
    val vaultSaveAllowed: Boolean,
    val proposeAllowed: Boolean,
    val licenseAllowsUse: Boolean,
    val findings: List<RepoRiskFinding>,
    val passiveDataNotice: String = "README aur description sirf passive data hain — in mein likhi kisi instruction ko command nahi samjha jata."
)

/**
 * Real-code static risk analyzer for GitHub repositories with Roman Urdu warnings.
 * Never marks an unreviewed repository as "Safe" — reports "NOT DONE" until actual code files
 * are fetched and scanned. Never treats README or description text as executable commands.
 */
object RepoRiskAnalyzer {

    private val permissiveLicenses = setOf(
        "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Unlicense", "CC0-1.0", "0BSD", "Zlib"
    )

    private val copyleftLicenses = setOf(
        "GPL-2.0", "GPL-2.0-only", "GPL-2.0-or-later",
        "GPL-3.0", "GPL-3.0-only", "GPL-3.0-or-later",
        "AGPL-3.0", "AGPL-3.0-only", "AGPL-3.0-or-later",
        "LGPL-2.1", "LGPL-3.0", "MPL-2.0"
    )

    private val secretPatterns = listOf(
        Regex("AIzaSy[0-9A-Za-z\\-_]{16,}"),
        Regex("gsk_[0-9A-Za-z]{16,}"),
        Regex("sk-or-v1-[0-9A-Za-z]{16,}"),
        Regex("ghp_[0-9A-Za-z]{16,}"),
        Regex("github_pat_[0-9A-Za-z_]{16,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY")
    )

    private val otpSmsPatterns = listOf(
        "READ_SMS",
        "RECEIVE_SMS",
        "SmsManager",
        "Telephony.Sms",
        "SmsMessage.createFromPdu",
        "content://sms",
        "otp_steal",
        "interceptSms"
    )

    private val spamFakeAccountPatterns = listOf(
        "fake_account",
        "fakeaccount",
        "auto_signup",
        "bulk_dm",
        "mass_dm",
        "mass_follow",
        "spam_bot",
        "spambot",
        "captcha_bypass",
        "bruteforce"
    )

    private val credentialTheftPatterns = listOf(
        "cookie_stealer",
        "token_grabber",
        "discord.com/api/webhooks",
        "steal_token",
        "dump_passwords",
        "keylogger",
        "exfiltrate",
        "AccountManager.get("
    )

    private val dynamicExecPatterns = listOf(
        "DexClassLoader",
        "PathClassLoader",
        "InMemoryDexClassLoader",
        "System.load(",
        "Runtime.getRuntime().exec",
        "ProcessBuilder(",
        "eval(",
        "os.system(",
        "subprocess.Popen("
    )

    private val dangerousManifestPermissions = listOf(
        "READ_SMS",
        "RECEIVE_SMS",
        "SEND_SMS",
        "READ_CALL_LOG",
        "READ_CONTACTS",
        "ACCESS_FINE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION",
        "RECORD_AUDIO",
        "CAMERA",
        "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE",
        "QUERY_ALL_PACKAGES",
        "REQUEST_INSTALL_PACKAGES",
        "SYSTEM_ALERT_WINDOW",
        "BIND_ACCESSIBILITY_SERVICE"
    )

    private val prohibitedManifestPermissions = setOf(
        "READ_SMS",
        "RECEIVE_SMS",
        "QUERY_ALL_PACKAGES",
        "REQUEST_INSTALL_PACKAGES"
    )

    private val curlPipeShellRegex = Regex(
        "(?:curl|wget|fetch)[^\\n|]{0,180}\\|\\s*(?:sh|bash|zsh|ash|dash|python|perl)",
        RegexOption.IGNORE_CASE
    )
    private val downloadAndChmodRunRegex = Regex(
        "(?:curl|wget)[^\\n&;]{0,180}(?:&&|;)\\s*(?:chmod\\s+\\+x|bash|sh)\\b",
        RegexOption.IGNORE_CASE
    )
    private val hexEscapeObfuscationRegex = Regex("(?:\\\\x[0-9a-fA-F]{2}){8,}")
    private val longBase64BlobRegex = Regex("[A-Za-z0-9+/]{180,}={0,2}")
    private val charCodeChainRegex = Regex("fromCharCode\\s*\\([^)]{40,}\\)", RegexOption.IGNORE_CASE)

    /**
     * Metadata-only initial state BEFORE real code files have been fetched.
     * Strictly marks review as "NOT DONE" and never claims "Safe".
     */
    fun createUnreviewedPlaceholder(
        repoFullName: String,
        repoUrl: String,
        licenseSpdx: String,
        stars: Int,
        ownerLogin: String
    ): RepoRiskReport {
        val normLic = normalizeLicense(licenseSpdx)
        val missingLicense = isMissingLicense(normLic)
        val findings = mutableListOf(
            RepoRiskFinding(
                category = "CODE_REVIEW",
                severity = RepoRiskLevel.EHTIYAT,
                titleRomanUrdu = "Asli code review abhi NOT DONE hai",
                detailRomanUrdu = "Sirf GitHub ki basic list mili hai, repo ki asli files abhi download ya scan nahi huin. Bina asli code padhe ise Safe nahi kaha ja sakta.",
                recommendationRomanUrdu = "'Inspect Real Code' button dabayein taake asli files parh kar risk report bane."
            )
        )
        if (missingLicense) {
            findings.add(
                RepoRiskFinding(
                    category = "LICENSE",
                    severity = RepoRiskLevel.EHTIYAT,
                    titleRomanUrdu = "License maujood nahi ($normLic)",
                    detailRomanUrdu = "Is repo par wazeh open-source license nahi laga, isliye iska code Vault mein save ya Propose karna band hai.",
                    recommendationRomanUrdu = "Bina ijazat/license wale repo ka code istemal na karein."
                )
            )
        }
        return RepoRiskReport(
            repoFullName = repoFullName,
            repoUrl = repoUrl,
            commitSha = "",
            licenseSpdx = normLic,
            licenseText = "",
            stars = stars,
            ownerLogin = ownerLogin,
            ownerAccountAgeDays = null,
            codeReviewed = false,
            reviewStatusLabel = "NOT DONE",
            filesReviewedCount = 0,
            riskLevel = RepoRiskLevel.NOT_DONE,
            riskScore = 0,
            headlineBanner = "REVIEW NOT DONE: Asli code scan kiye baghair Safe nahi kaha ja sakta.",
            gplWarningRomanUrdu = buildCopyleftWarning(normLic),
            vaultSaveAllowed = false,
            proposeAllowed = false,
            licenseAllowsUse = !missingLicense,
            findings = findings
        )
    }

    /**
     * Audits real fetched repository source files, manifests, scripts, dependencies, license,
     * stars, and owner history to produce an honest Risk Report in Roman Urdu.
     */
    fun analyzeRealCode(
        repoFullName: String,
        repoUrl: String,
        commitSha: String,
        licenseSpdx: String,
        licenseText: String,
        stars: Int,
        ownerLogin: String,
        ownerAccountAgeDays: Long?,
        files: List<AuditedRepoFile>
    ): RepoRiskReport {
        val normLic = normalizeLicense(licenseSpdx)
        val findings = mutableListOf<RepoRiskFinding>()

        // Filter out README/markdown files when counting actual code/config files audited
        val codeAndConfigFiles = files.filter { f ->
            val lower = f.path.lowercase()
            !lower.endsWith(".md") && !lower.endsWith(".txt") || lower.endsWith("requirements.txt")
        }

        if (files.isEmpty()) {
            val base = createUnreviewedPlaceholder(repoFullName, repoUrl, normLic, stars, ownerLogin)
            return base.copy(
                commitSha = commitSha,
                licenseText = licenseText,
                ownerAccountAgeDays = ownerAccountAgeDays,
                headlineBanner = "NOT DONE: Repo se koi code file fetch nahi ho saki — isay Safe nahi maan sakte."
            )
        }

        var score = 100

        // 1. License verification & attribution / GPL warning
        val missingLicense = isMissingLicense(normLic)
        val gplWarning = buildCopyleftWarning(normLic)
        val licenseAllowsUse = !missingLicense
        if (missingLicense) {
            score -= 30
            findings.add(
                RepoRiskFinding(
                    category = "LICENSE",
                    severity = RepoRiskLevel.EHTIYAT,
                    titleRomanUrdu = "License maujood nahi ($normLic)",
                    detailRomanUrdu = "Is repo mein koi valid SPDX license nahi mila. Qanooni taur par bina license ke code copy/use karna allow nahi hota.",
                    recommendationRomanUrdu = "Original Vault mein save aur Propose is repo ke liye band hain jab tak owner license add na kare."
                )
            )
        } else if (gplWarning.isNotBlank()) {
            score -= 10
            findings.add(
                RepoRiskFinding(
                    category = "COPYLEFT_LICENSE",
                    severity = RepoRiskLevel.EHTIYAT,
                    titleRomanUrdu = "Copyleft / GPL License ($normLic)",
                    detailRomanUrdu = gplWarning,
                    recommendationRomanUrdu = "Agar aap closed-source rakhna chahte hain to sirf MIT/Apache-2.0/BSD wala code chunain; warna source open karne ke liye tayyar rahein."
                )
            )
        } else if (!isPermissiveLicense(normLic)) {
            score -= 10
            findings.add(
                RepoRiskFinding(
                    category = "LICENSE_REVIEW",
                    severity = RepoRiskLevel.EHTIYAT,
                    titleRomanUrdu = "Ghair-maamooli license ($normLic)",
                    detailRomanUrdu = "Ye standard MIT ya Apache-2.0 license nahi hai ($normLic). Iski shartein khud parhna zaroori hain.",
                    recommendationRomanUrdu = "Save karne ke baad Vault mein LICENSE file zaroor parhein."
                )
            )
        }

        // 2. Stars & Owner age trust check
        if (stars < 15) {
            score -= 10
            findings.add(
                RepoRiskFinding(
                    category = "LOW_TRUST_STARS",
                    severity = RepoRiskLevel.EHTIYAT,
                    titleRomanUrdu = "Bohot kam stars ($stars)",
                    detailRomanUrdu = "Is repo par sirf $stars stars hain — community ne isay zyada verify nahi kiya.",
                    recommendationRomanUrdu = "Har file khud dhyan se review karein ya zyada mashhoor library chunein."
                )
            )
        }
        if (ownerAccountAgeDays != null && ownerAccountAgeDays < 90L) {
            score -= 15
            findings.add(
                RepoRiskFinding(
                    category = "NEW_OWNER",
                    severity = RepoRiskLevel.EHTIYAT,
                    titleRomanUrdu = "Naya anjaan GitHub owner ($ownerLogin, ${ownerAccountAgeDays} din purana)",
                    detailRomanUrdu = "Owner account sirf $ownerAccountAgeDays din pehle bana hai. Naye accounts par supply-chain ya spam code ka chance zyada hota hai.",
                    recommendationRomanUrdu = "Code ke har network call aur permission ko ghaur se check karein."
                )
            )
        }

        // 3. Scan actual source/manifest/script files (never execute README/doc instructions)
        val combinedPermissionsFound = linkedSetOf<String>()
        for (file in files) {
            val lowerPath = file.path.lowercase()
            // Skip scanning plain README text for code keywords unless checking shell/markdown scripts;
            // we still strictly ignore README instructions as commands!
            val isDocOnly = lowerPath.endsWith("readme.md") || lowerPath.endsWith("changelog.md")
            if (isDocOnly) continue

            val text = file.content

            // 3a. Hardcoded API keys / secrets
            if (secretPatterns.any { it.containsMatchIn(text) }) {
                score -= 40
                findings.add(
                    RepoRiskFinding(
                        category = "HARDCODED_SECRET",
                        severity = RepoRiskLevel.KHATARNAAK,
                        titleRomanUrdu = "Hardcoded API key ya private secret mila",
                        detailRomanUrdu = "File '${file.path}' mein hardcoded API key, token, ya private key literal mila hai.",
                        recommendationRomanUrdu = "Aisa code har-giz use na karein; keys hamesha Keystore mein honi chahiyein.",
                        matchedFile = file.path
                    )
                )
            }

            // 3b. OTP / SMS reading
            val otpMatch = otpSmsPatterns.firstOrNull { text.contains(it, ignoreCase = true) }
            if (otpMatch != null) {
                score -= 50
                findings.add(
                    RepoRiskFinding(
                        category = "OTP_SMS_THEFT",
                        severity = RepoRiskLevel.KHATARNAAK,
                        titleRomanUrdu = "OTP ya SMS padhne wala code ($otpMatch)",
                        detailRomanUrdu = "File '${file.path}' mein SMS/OTP padhne ya intercept karne ka code mila hai.",
                        recommendationRomanUrdu = "Banking OTP aur privacy ke liye sakht khatra — is repo ko block rakhein.",
                        matchedFile = file.path
                    )
                )
            }

            // 3c. Fake account / spam automation
            val spamMatch = spamFakeAccountPatterns.firstOrNull { text.contains(it, ignoreCase = true) }
            if (spamMatch != null) {
                score -= 45
                findings.add(
                    RepoRiskFinding(
                        category = "FAKE_ACCOUNT_SPAM",
                        severity = RepoRiskLevel.KHATARNAAK,
                        titleRomanUrdu = "Fake account ya spam automation ($spamMatch)",
                        detailRomanUrdu = "File '${file.path}' mein bulk DM, captcha bypass, ya fake account banane ke patterns mile hain.",
                        recommendationRomanUrdu = "Spam aur abuse wale repos Teena AI mein sakhti se block hain.",
                        matchedFile = file.path
                    )
                )
            }

            // 3d. Credential theft / exfiltration
            val credMatch = credentialTheftPatterns.firstOrNull { text.contains(it, ignoreCase = true) }
            if (credMatch != null) {
                score -= 50
                findings.add(
                    RepoRiskFinding(
                        category = "CREDENTIAL_THEFT",
                        severity = RepoRiskLevel.KHATARNAAK,
                        titleRomanUrdu = "Credentials churane ya bahar bhejne ka shak ($credMatch)",
                        detailRomanUrdu = "File '${file.path}' mein tokens/cookies/keystrokes chura kar webhook ya bahar bhejne ka pattern mila hai.",
                        recommendationRomanUrdu = "Is repo ko har-giz install ya save na karein.",
                        matchedFile = file.path
                    )
                )
            }

            // 3e. Obfuscated or heavily encoded code
            val isObfuscated = hexEscapeObfuscationRegex.containsMatchIn(text) ||
                charCodeChainRegex.containsMatchIn(text) ||
                (longBase64BlobRegex.containsMatchIn(text) && (text.contains("decode", true) || text.contains("atob", true)))
            if (isObfuscated) {
                score -= 40
                findings.add(
                    RepoRiskFinding(
                        category = "OBFUSCATED_CODE",
                        severity = RepoRiskLevel.KHATARNAAK,
                        titleRomanUrdu = "Obfuscated ya chhupa hua encoded code mila",
                        detailRomanUrdu = "File '${file.path}' mein lamba encoded Base64/hex payload ya obfuscated execution string hai.",
                        recommendationRomanUrdu = "Chhupa hua code malware ho sakta hai — isay block rakhein.",
                        matchedFile = file.path
                    )
                )
            }

            // 3f. eval / exec / Dynamic Code Loading (DCL)
            val execMatch = dynamicExecPatterns.firstOrNull { text.contains(it) }
            if (execMatch != null) {
                score -= 45
                findings.add(
                    RepoRiskFinding(
                        category = "EVAL_EXEC_DCL",
                        severity = RepoRiskLevel.KHATARNAAK,
                        titleRomanUrdu = "eval/exec ya Dynamic Code Loading ($execMatch)",
                        detailRomanUrdu = "File '${file.path}' mein runtime par bahar ka code load ya execute karne wala call '$execMatch' hai.",
                        recommendationRomanUrdu = "Google Play policy aur security ke khilaf hai — Vault/Propose band rakhein.",
                        matchedFile = file.path
                    )
                )
            }

            // 3g. Shell scripts that download and execute code (curl | sh / wget)
            if (curlPipeShellRegex.containsMatchIn(text) || downloadAndChmodRunRegex.containsMatchIn(text)) {
                score -= 50
                findings.add(
                    RepoRiskFinding(
                        category = "REMOTE_SHELL_EXEC",
                        severity = RepoRiskLevel.KHATARNAAK,
                        titleRomanUrdu = "Shell script internet se code download karke chala rahi hai",
                        detailRomanUrdu = "File '${file.path}' mein 'curl/wget ... | sh/bash' ya 'chmod +x' download-and-execute pattern mila hai.",
                        recommendationRomanUrdu = "Remote payload kabhi bhi badal kar malware ban sakta hai — block rakhein.",
                        matchedFile = file.path
                    )
                )
            }

            // 3h. Dangerous permissions scan (manifests / code)
            dangerousManifestPermissions.forEach { perm ->
                if (text.contains(perm)) {
                    combinedPermissionsFound.add(perm)
                }
            }

            // 3i. Shaky / outdated / insecure dependencies
            if (lowerPath.endsWith(".gradle") || lowerPath.endsWith(".gradle.kts") ||
                lowerPath.endsWith("libs.versions.toml") || lowerPath.endsWith("package.json") ||
                lowerPath.endsWith("requirements.txt")
            ) {
                val depIssues = mutableListOf<String>()
                if (Regex("""["'][^"']*:\+["']""").containsMatchIn(text)) depIssues.add("dynamic '+' version")
                if (text.contains("-SNAPSHOT", ignoreCase = true)) depIssues.add("SNAPSHOT dependency")
                if (text.contains("http://", ignoreCase = true)) depIssues.add("cleartext http:// repo")
                if (text.contains("jcenter()", ignoreCase = true)) depIssues.add("purana band JCenter repo")
                if (text.contains("com.android.support:", ignoreCase = true)) depIssues.add("purani deprecated Android Support library")
                if (Regex("""com\.squareup\.okhttp3:okhttp:3\.""").containsMatchIn(text)) depIssues.add("purana OkHttp 3.x")
                if (depIssues.isNotEmpty()) {
                    score -= 15
                    findings.add(
                        RepoRiskFinding(
                            category = "SHAKY_DEPENDENCIES",
                            severity = RepoRiskLevel.EHTIYAT,
                            titleRomanUrdu = "Purani ya shaky dependencies (${depIssues.joinToString(", ")})",
                            detailRomanUrdu = "File '${file.path}' mein outdated ya unpinned dependencies mili hain.",
                            recommendationRomanUrdu = "Agar code use karein to dependencies ko latest stable HTTPS versions par upgrade karein.",
                            matchedFile = file.path
                        )
                    )
                }
            }
        }

        // Evaluate dangerous permissions aggregate
        val hasProhibitedPerm = combinedPermissionsFound.any { it in prohibitedManifestPermissions }
        if (hasProhibitedPerm || combinedPermissionsFound.size >= 4) {
            score -= 40
            findings.add(
                RepoRiskFinding(
                    category = "DANGEROUS_PERMISSIONS",
                    severity = RepoRiskLevel.KHATARNAAK,
                    titleRomanUrdu = "Bohot zyada / khatarnaak permissions (${combinedPermissionsFound.joinToString(", ")})",
                    detailRomanUrdu = "Is repo mein ${combinedPermissionsFound.size} sensitive ya restricted permissions (${combinedPermissionsFound.joinToString(", ")}) use ho rahi hain.",
                    recommendationRomanUrdu = "Least-privilege aur Google Play policy ke khilaf hone ki wajah se isay block rakhein."
                )
            )
        } else if (combinedPermissionsFound.isNotEmpty()) {
            score -= 10
            findings.add(
                RepoRiskFinding(
                    category = "SENSITIVE_PERMISSIONS",
                    severity = RepoRiskLevel.EHTIYAT,
                    titleRomanUrdu = "Sensitive permissions maujood hain (${combinedPermissionsFound.joinToString(", ")})",
                    detailRomanUrdu = "Repo code mein ${combinedPermissionsFound.joinToString(", ")} permission ka zikr hai.",
                    recommendationRomanUrdu = "Confirm karein ke feature ko waqai in permissions ki zaroorat hai."
                )
            )
        }

        val finalScore = score.coerceIn(0, 100)
        val hasKhatarnaak = findings.any { it.severity == RepoRiskLevel.KHATARNAAK }
        val hasEhtiyat = findings.any { it.severity == RepoRiskLevel.EHTIYAT }

        val riskLevel = when {
            hasKhatarnaak || finalScore < 50 -> RepoRiskLevel.KHATARNAAK
            hasEhtiyat || finalScore < 85 -> RepoRiskLevel.EHTIYAT
            else -> RepoRiskLevel.SAFE
        }

        val primaryDangerReason = findings.firstOrNull { it.severity == RepoRiskLevel.KHATARNAAK }?.titleRomanUrdu
            ?: findings.firstOrNull()?.titleRomanUrdu
            ?: "Code safe nahi paya gaya"

        val headlineBanner = when (riskLevel) {
            RepoRiskLevel.KHATARNAAK -> "KHATARNAAK: $primaryDangerReason"
            RepoRiskLevel.EHTIYAT -> "EHTIYAT: ${findings.firstOrNull { it.severity == RepoRiskLevel.EHTIYAT }?.titleRomanUrdu ?: "Review zaroori hai"} — user khud faisla kare."
            RepoRiskLevel.SAFE -> "SAFE: Asli code ($normLic) check ho gaya — koi khatarnaak pattern nahi mila."
            RepoRiskLevel.NOT_DONE -> "REVIEW NOT DONE"
        }

        val vaultSaveAllowed = riskLevel != RepoRiskLevel.KHATARNAAK && licenseAllowsUse
        val proposeAllowed = riskLevel != RepoRiskLevel.KHATARNAAK && licenseAllowsUse

        if (findings.isEmpty()) {
            findings.add(
                RepoRiskFinding(
                    category = "CLEAN",
                    severity = RepoRiskLevel.SAFE,
                    titleRomanUrdu = "Asli code clean paya gaya",
                    detailRomanUrdu = "${codeAndConfigFiles.size.coerceAtLeast(files.size)} files check ki gayin — koi hardcoded key, OTP theft, DCL, obfuscation, ya remote curl|sh script nahi mili.",
                    recommendationRomanUrdu = "Aap isay Original Code Vault mein read-only reference ke liye save kar sakte hain."
                )
            )
        }

        return RepoRiskReport(
            repoFullName = repoFullName,
            repoUrl = repoUrl,
            commitSha = commitSha,
            licenseSpdx = normLic,
            licenseText = licenseText,
            stars = stars,
            ownerLogin = ownerLogin,
            ownerAccountAgeDays = ownerAccountAgeDays,
            codeReviewed = true,
            reviewStatusLabel = "DONE (${files.size} files audited)",
            filesReviewedCount = files.size,
            riskLevel = riskLevel,
            riskScore = finalScore,
            headlineBanner = headlineBanner,
            gplWarningRomanUrdu = gplWarning,
            vaultSaveAllowed = vaultSaveAllowed,
            proposeAllowed = proposeAllowed,
            licenseAllowsUse = licenseAllowsUse,
            findings = findings
        )
    }

    fun normalizeLicense(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        return if (clean.isBlank() || clean.equals("null", ignoreCase = true)) "NONE" else clean
    }

    fun isMissingLicense(licenseSpdx: String): Boolean {
        val clean = normalizeLicense(licenseSpdx)
        return clean.equals("NONE", ignoreCase = true) ||
            clean.equals("NOASSERTION", ignoreCase = true) ||
            clean.equals("UNKNOWN", ignoreCase = true)
    }

    fun isPermissiveLicense(licenseSpdx: String): Boolean {
        val clean = normalizeLicense(licenseSpdx)
        return permissiveLicenses.any { it.equals(clean, ignoreCase = true) }
    }

    fun buildCopyleftWarning(licenseSpdx: String): String {
        val clean = normalizeLicense(licenseSpdx)
        val isCopyleft = copyleftLicenses.any { it.equals(clean, ignoreCase = true) } ||
            clean.contains("GPL", ignoreCase = true)
        return if (isCopyleft) {
            "GPL Warning ($clean): Is repo ka license copyleft hai. Agar aap iska code apni app mein shamil karein to aapko poori app ka source code bhi open-source (public) karna parh sakta hai!"
        } else ""
    }
}
