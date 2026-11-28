package com.example.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.core.security.KeystoreSecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

enum class DevBuildMode(val label: String) {
    CLOUD_GITHUB("Cloud Build (GitHub Actions • Free)"),
    LOCAL_ON_DEVICE("Local Build (On-Device • Advanced)")
}

enum class DevBuildType(val gradleTask: String, val label: String) {
    DEBUG("assembleDebug", "Debug"),
    RELEASE("assembleRelease", "Release")
}

data class BuiltApkInfo(
    val apkFile: File,
    val fileName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val sizeBytes: Long,
    val sha256: String
)

data class LocalBuildPreflightReport(
    val freeSpaceBytes: Long,
    val recommendedMinBytes: Long = DevApkBuildManager.LOCAL_MIN_FREE_BYTES,
    val hasEnoughStorage: Boolean,
    val totalRamGb: Float,
    val availableRamGb: Float,
    val hasEnoughRam: Boolean,
    val primaryAbi: String,
    val isArm64: Boolean,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val jdk17Ready: Boolean,
    val gradleReady: Boolean,
    val androidSdkReady: Boolean,
    val aapt2Arm64Ready: Boolean,
    val aapt2Path: String,
    val offlineCacheBytes: Long,
    val warnings: List<String>,
    val canAttemptLocalBuild: Boolean,
    val etaNote: String
)

data class DevKeystoreStatus(
    val isGenerated: Boolean,
    val alias: String,
    val certSha256: String,
    val sizeBytes: Long,
    val warning: String
)

/**
 * Phase 11D: Manages Dev Studio APK builds (Cloud GitHub Actions & Local on-device pre-flight),
 * `.github/workflows/build.yml` generation, keystore signing & backup, artifact ZIP extraction,
 * APK verification, FileProvider Install / Share intents, and unknown-sources shortcut.
 */
class DevApkBuildManager(
    private val context: Context,
    private val vault: KeystoreSecretVault
) {
    private val appContext = context.applicationContext
    private val buildsDir = File(appContext.filesDir, "dev_studio/apk_builds")
    private val toolchainDir = File(appContext.filesDir, "dev_studio/toolchain")
    private val gradleCacheDir = File(appContext.filesDir, "dev_studio/gradle_cache")

    init {
        buildsDir.mkdirs()
        toolchainDir.mkdirs()
        gradleCacheDir.mkdirs()
    }

    fun apkBuildsDirectory(): File {
        buildsDir.mkdirs()
        return buildsDir
    }

    fun toolchainDirectory(): File {
        toolchainDir.mkdirs()
        return toolchainDir
    }

    fun gradleCacheDirectory(): File {
        gradleCacheDir.mkdirs()
        return gradleCacheDir
    }

    fun ensureAndroidGradleProjectInWorkspace(
        workspaceDir: File,
        appName: String = "Teena Dev App",
        applicationId: String = "com.teena.devstudio.app",
        versionCode: Int = 1,
        versionName: String = "1.0.0",
        buildType: DevBuildType = DevBuildType.DEBUG,
        includeGitHubRelease: Boolean = false
    ) {
        workspaceDir.mkdirs()
        val hasAppGradle = File(workspaceDir, "app/build.gradle.kts").exists() ||
            File(workspaceDir, "app/build.gradle").exists()

        if (!hasAppGradle) {
            File(workspaceDir, "settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "${appName.replace("\"", "")}"
                include(":app")
                """.trimIndent() + "\n",
                Charsets.UTF_8
            )

            File(workspaceDir, "build.gradle.kts").writeText(
                """
                plugins {
                    id("com.android.application") version "8.7.3" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
                }
                """.trimIndent() + "\n",
                Charsets.UTF_8
            )

            File(workspaceDir, "app/build.gradle.kts").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                        id("org.jetbrains.kotlin.plugin.compose")
                    }

                    android {
                        namespace = "$applicationId"
                        compileSdk = 35

                        defaultConfig {
                            applicationId = "$applicationId"
                            minSdk = 24
                            targetSdk = 35
                            versionCode = $versionCode
                            versionName = "$versionName"
                        }

                        buildTypes {
                            release {
                                isMinifyEnabled = false
                            }
                        }

                        compileOptions {
                            sourceCompatibility = JavaVersion.VERSION_17
                            targetCompatibility = JavaVersion.VERSION_17
                        }
                        kotlinOptions {
                            jvmTarget = "17"
                        }
                        buildFeatures {
                            compose = true
                        }
                    }

                    dependencies {
                        implementation(platform("androidx.compose:compose-bom:2024.09.00"))
                        implementation("androidx.core:core-ktx:1.15.0")
                        implementation("androidx.activity:activity-compose:1.9.3")
                        implementation("androidx.compose.ui:ui")
                        implementation("androidx.compose.material3:material3")
                    }
                    """.trimIndent() + "\n",
                    Charsets.UTF_8
                )
            }

            File(workspaceDir, "app/src/main/AndroidManifest.xml").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application
                            android:label="@string/app_name"
                            android:supportsRtl="true">
                            <activity
                                android:name=".MainActivity"
                                android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                    """.trimIndent() + "\n",
                    Charsets.UTF_8
                )
            }

            File(workspaceDir, "app/src/main/res/values/strings.xml").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="app_name">$appName</string>
                    </resources>
                    """.trimIndent() + "\n",
                    Charsets.UTF_8
                )
            }

            val pkgDir = applicationId.replace('.', '/')
            File(workspaceDir, "app/src/main/java/$pkgDir/MainActivity.kt").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    package $applicationId

                    import android.os.Bundle
                    import androidx.activity.ComponentActivity
                    import androidx.activity.compose.setContent
                    import androidx.compose.foundation.layout.Arrangement
                    import androidx.compose.foundation.layout.Column
                    import androidx.compose.foundation.layout.fillMaxSize
                    import androidx.compose.foundation.layout.padding
                    import androidx.compose.material3.MaterialTheme
                    import androidx.compose.material3.Surface
                    import androidx.compose.material3.Text
                    import androidx.compose.ui.Alignment
                    import androidx.compose.ui.Modifier
                    import androidx.compose.ui.unit.dp

                    class MainActivity : ComponentActivity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContent {
                                MaterialTheme {
                                    Surface(modifier = Modifier.fillMaxSize()) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(24.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("$appName", style = MaterialTheme.typography.headlineMedium)
                                            Text("Built with Teena Dev Studio (v$versionName)")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    """.trimIndent() + "\n",
                    Charsets.UTF_8
                )
            }
        } else {
            // Update versionCode & versionName inside existing app/build.gradle(.kts) if present
            bumpVersionInExistingGradle(workspaceDir, versionCode, versionName)
        }

        // Always keep .github/workflows/build.yml synced for Cloud Build
        val workflowFile = File(workspaceDir, ".github/workflows/build.yml")
        workflowFile.parentFile?.mkdirs()
        workflowFile.writeText(
            generateGitHubBuildWorkflowYml(buildType = buildType, includeRelease = includeGitHubRelease),
            Charsets.UTF_8
        )
    }

    fun applyLowMemoryGradleProperties(
        workspaceDir: File,
        aapt2OverridePath: String = ""
    ): File {
        workspaceDir.mkdirs()
        val propsFile = File(workspaceDir, "gradle.properties")
        val existing = if (propsFile.exists()) propsFile.readLines() else emptyList()
        val desired = linkedMapOf(
            "org.gradle.daemon" to "false",
            "org.gradle.jvmargs" to "-Xmx1g -Dfile.encoding=UTF-8",
            "kotlin.daemon.jvmargs" to "-Xmx768m",
            "org.gradle.workers.max" to "2",
            "android.useAndroidX" to "true"
        )
        val resolvedAapt2 = aapt2OverridePath.trim().ifBlank { detectArm64Aapt2Path().orEmpty() }
        if (resolvedAapt2.isNotBlank()) {
            desired["android.aapt2FromMavenOverride"] = resolvedAapt2
        }

        val keptLines = existing.filter { line ->
            val key = line.substringBefore('=').trim()
            key !in desired.keys
        }.toMutableList()
        desired.forEach { (k, v) -> keptLines.add("$k=$v") }
        propsFile.writeText(keptLines.joinToString("\n").trim() + "\n", Charsets.UTF_8)
        return propsFile
    }

    fun collectPushableWorkspaceFiles(workspaceDir: File): Map<String, ByteArray> {
        if (!workspaceDir.exists()) return emptyMap()
        val rootCanonical = workspaceDir.canonicalFile
        val result = linkedMapOf<String, ByteArray>()
        workspaceDir.walkTopDown()
            .onEnter { dir ->
                val name = dir.name
                name != ".git" && name != ".gradle" && name != "build" && name != ".idea"
            }
            .filter { it.isFile && it.length() <= MAX_PUSH_FILE_BYTES }
            .forEach { f ->
                val rel = runCatching { f.canonicalFile.relativeTo(rootCanonical).invariantSeparatorsPath }.getOrNull()
                if (!rel.isNullOrBlank()) {
                    result[rel] = f.readBytes()
                }
            }
        return result
    }

    suspend fun runLocalBuildPreflight(customAapt2Path: String = ""): LocalBuildPreflightReport =
        withContext(Dispatchers.IO) {
            val freeBytes = runCatching {
                val stat = StatFs(appContext.filesDir.absolutePath)
                stat.availableBlocksLong * stat.blockSizeLong
            }.getOrDefault(0L)
            val hasStorage = freeBytes >= LOCAL_MIN_FREE_BYTES

            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val totalRamGb = mi.totalMem / (1024f * 1024f * 1024f)
            val availRamGb = mi.availMem / (1024f * 1024f * 1024f)
            val hasRam = totalRamGb >= 3.5f

            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            val primaryAbi = abis.firstOrNull() ?: "unknown"
            val isArm64 = "arm64-v8a" in abis

            val batteryStatus = runCatching {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = plugged != 0

            val jdkReady = File(toolchainDir, "jdk17/bin/java").let { it.exists() && it.canExecute() }
            val gradleReady = File(toolchainDir, "gradle/bin/gradle").let { it.exists() && it.canExecute() }
            val sdkReady = File(toolchainDir, "android-sdk/platforms").exists() &&
                File(toolchainDir, "android-sdk/build-tools").exists()
            val aapt2Path = customAapt2Path.trim().ifBlank { detectArm64Aapt2Path().orEmpty() }
            val aapt2Ready = aapt2Path.isNotBlank() && File(aapt2Path).exists()

            val cacheSize = runCatching {
                gradleCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }.getOrDefault(0L)

            val warnings = mutableListOf<String>()
            if (!hasStorage) {
                warnings.add("Free space ${DevStudioEngine.formatBytes(freeBytes)} hai — local build ke liye kam se kam 6 GB chahiye. Cloud Build use karein.")
            }
            if (!hasRam) {
                warnings.add("Device RAM ${"%.1f".format(totalRamGb)} GB low hai; Gradle -Xmx1g crash ho sakta hai.")
            }
            if (!isArm64) {
                warnings.add("ABI '$primaryAbi' hai; arm64-v8a optimal hai.")
            }
            if (pct < 20 && !charging) {
                warnings.add("Battery $pct% low hai — local build battery zyada use karega.")
            }
            if (!jdkReady || !gradleReady || !sdkReady || !aapt2Ready) {
                warnings.add("Local toolchain (JDK 17 / Gradle / Android SDK / arm64 aapt2) engine mein setup nahi hai — Cloud Build instant chalega.")
            }

            LocalBuildPreflightReport(
                freeSpaceBytes = freeBytes,
                hasEnoughStorage = hasStorage,
                totalRamGb = totalRamGb,
                availableRamGb = availRamGb,
                hasEnoughRam = hasRam,
                primaryAbi = primaryAbi,
                isArm64 = isArm64,
                batteryPercent = pct,
                isCharging = charging,
                jdk17Ready = jdkReady,
                gradleReady = gradleReady,
                androidSdkReady = sdkReady,
                aapt2Arm64Ready = aapt2Ready,
                aapt2Path = aapt2Path,
                offlineCacheBytes = cacheSize,
                warnings = warnings,
                canAttemptLocalBuild = jdkReady && gradleReady && sdkReady && aapt2Ready && hasStorage,
                etaNote = if (cacheSize > 50L * 1024L * 1024L) {
                    "Offline cache ready (${DevStudioEngine.formatBytes(cacheSize)}) — incremental build ~1–3 min."
                } else {
                    "First local build slow hota hai (ETA 6–15 min) — Gradle deps workspace cache mein save honge."
                }
            )
        }

    fun detectArm64Aapt2Path(): String? {
        val candidates = listOf(
            File(toolchainDir, "aapt2-arm64"),
            File(toolchainDir, "android-sdk/build-tools/35.0.0/aapt2"),
            File(appContext.applicationInfo?.nativeLibraryDir.orEmpty(), "libaapt2.so")
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }?.absolutePath
    }

    suspend fun ensureProjectSigningKeystore(alias: String = DEFAULT_KEY_ALIAS): Result<DevKeystoreStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val existingB64 = vault.readSecret(VAULT_KEYSTORE_B64)
                val existingPass = vault.readSecret(VAULT_KEYSTORE_PASS)
                if (!existingB64.isNullOrBlank() && !existingPass.isNullOrBlank()) {
                    val bytes = Base64.decode(existingB64, Base64.NO_WRAP)
                    val ks = KeyStore.getInstance("PKCS12")
                    ks.load(ByteArrayInputStream(bytes), existingPass.toCharArray())
                    val cert = ks.getCertificate(alias) as? X509Certificate
                    val sha = cert?.encoded?.let { sha256Colon(it) } ?: "VERIFIED"
                    return@runCatching DevKeystoreStatus(
                        isGenerated = true,
                        alias = alias,
                        certSha256 = sha,
                        sizeBytes = bytes.size.toLong(),
                        warning = KEYSTORE_LOSS_WARNING
                    )
                }

                // Generate new 2048-bit RSA KeyPair + self-signed X.509 certificate in PKCS12 KeyStore
                val pass = generateRandomPassword()
                val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                val keyPair = kpg.generateKeyPair()
                val cert = buildSelfSignedX509Cert(keyPair.public.encoded, keyPair.private)
                val ks = KeyStore.getInstance("PKCS12").apply {
                    load(null, pass.toCharArray())
                    setKeyEntry(alias, keyPair.private, pass.toCharArray(), arrayOf(cert))
                }
                val baos = ByteArrayOutputStream()
                ks.store(baos, pass.toCharArray())
                val ksBytes = baos.toByteArray()
                vault.saveSecret(VAULT_KEYSTORE_B64, Base64.encodeToString(ksBytes, Base64.NO_WRAP))
                vault.saveSecret(VAULT_KEYSTORE_PASS, pass)

                DevKeystoreStatus(
                    isGenerated = true,
                    alias = alias,
                    certSha256 = sha256Colon(cert.encoded),
                    sizeBytes = ksBytes.size.toLong(),
                    warning = KEYSTORE_LOSS_WARNING
                )
            }
        }

    fun getCurrentKeystoreStatus(alias: String = DEFAULT_KEY_ALIAS): DevKeystoreStatus {
        val existingB64 = vault.readSecret(VAULT_KEYSTORE_B64)
        val existingPass = vault.readSecret(VAULT_KEYSTORE_PASS)
        if (existingB64.isNullOrBlank() || existingPass.isNullOrBlank()) {
            return DevKeystoreStatus(
                isGenerated = false,
                alias = alias,
                certSha256 = "Not generated yet",
                sizeBytes = 0L,
                warning = KEYSTORE_LOSS_WARNING
            )
        }
        return runCatching {
            val bytes = Base64.decode(existingB64, Base64.NO_WRAP)
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(ByteArrayInputStream(bytes), existingPass.toCharArray())
            val cert = ks.getCertificate(alias) as? X509Certificate
            DevKeystoreStatus(
                isGenerated = true,
                alias = alias,
                certSha256 = cert?.encoded?.let { sha256Colon(it) } ?: "Available",
                sizeBytes = bytes.size.toLong(),
                warning = KEYSTORE_LOSS_WARNING
            )
        }.getOrElse {
            DevKeystoreStatus(false, alias, "Corrupt or locked", 0L, KEYSTORE_LOSS_WARNING)
        }
    }

    fun exportKeystoreToUri(uri: Uri): Result<String> = runCatching {
        val b64 = vault.readSecret(VAULT_KEYSTORE_B64)
            ?: throw IllegalStateException("Keystore abhi generate nahi hua. Pehle Generate/Build karein.")
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        appContext.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
        } ?: throw IOException("Selected destination file open nahi ho saki.")
        "Keystore backup exported (${bytes.size} bytes). Password safely note karein."
    }

    suspend fun extractApkFromArtifactZip(
        artifactZipFile: File,
        runId: Long
    ): Result<BuiltApkInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val outDir = File(buildsDir, "run_$runId").apply { mkdirs() }
            val canonicalOut = outDir.canonicalFile
            val outPrefix = canonicalOut.path + File.separator
            var extractedApk: File? = null

            ZipInputStream(FileInputStream(artifactZipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val cleanName = entry.name.replace('\\', '/').trimStart('/')
                    if (!entry.isDirectory && cleanName.endsWith(".apk", ignoreCase = true)) {
                        val safeName = cleanName.substringAfterLast('/')
                        val dest = File(canonicalOut, safeName).canonicalFile
                        if (dest.path != canonicalOut.path && !dest.path.startsWith(outPrefix)) {
                            throw SecurityException("Blocked Zip-Slip APK entry: ${entry.name}")
                        }
                        FileOutputStream(dest).use { fos -> zis.copyTo(fos) }
                        if (extractedApk == null || safeName.contains("release", ignoreCase = true)) {
                            extractedApk = dest
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            val apk = extractedApk ?: throw IOException("Artifact ZIP ke andar koi .apk file nahi mili.")
            verifyAndInspectApkFile(apk).getOrThrow()
        }
    }

    fun verifyAndInspectApkFile(apkFile: File): Result<BuiltApkInfo> = runCatching {
        if (!apkFile.exists() || apkFile.length() < 128L) {
            throw IOException("APK file missing ya empty hai (${apkFile.name}).")
        }
        // Verify PK ZIP magic header
        FileInputStream(apkFile).use { fis ->
            val hdr = ByteArray(4)
            val n = fis.read(hdr)
            if (n < 4 || hdr[0] != 0x50.toByte() || hdr[1] != 0x4B.toByte()) {
                throw IOException("Valid APK/ZIP signature missing hai (${apkFile.name}).")
            }
        }
        val pm = appContext.packageManager
        val flags = PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val pkgInfo = runCatching { pm.getPackageArchiveInfo(apkFile.absolutePath, flags) }.getOrNull()
        val pkgName = pkgInfo?.packageName ?: "com.teena.devstudio.app"
        val verName = pkgInfo?.versionName ?: "1.0"
        val verCode = if (pkgInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pkgInfo.versionCode
        } else 1

        BuiltApkInfo(
            apkFile = apkFile,
            fileName = apkFile.name,
            packageName = pkgName,
            versionName = verName,
            versionCode = verCode,
            sizeBytes = apkFile.length(),
            sha256 = sha256HexFile(apkFile)
        )
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else true
    }

    fun createUnknownAppsSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createInstallApkIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createShareApkIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Teena Dev Studio APK: ${apkFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(share, "Share APK").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        const val WORKFLOW_FILE_NAME = "build.yml"
        const val WORKFLOW_RELATIVE_PATH = ".github/workflows/build.yml"
        const val DEFAULT_KEY_ALIAS = "teena_dev_key"
        const val LOCAL_MIN_FREE_BYTES = 6L * 1024L * 1024L * 1024L
        private const val MAX_PUSH_FILE_BYTES = 4L * 1024L * 1024L
        private const val VAULT_KEYSTORE_B64 = "slot_dev_signing_keystore_b64"
        private const val VAULT_KEYSTORE_PASS = "slot_dev_signing_keystore_pass"
        const val KEYSTORE_LOSS_WARNING =
            "Warning: Yeh signing key kho gayi to app ke future updates install nahi honge! Backup zaroor export karein."

        fun generateGitHubBuildWorkflowYml(
            buildType: DevBuildType = DevBuildType.DEBUG,
            includeRelease: Boolean = false
        ): String {
            val task = buildType.gradleTask
            return buildString {
                appendLine("name: Teena Dev Studio APK Build")
                appendLine()
                appendLine("on:")
                appendLine("  workflow_dispatch:")
                appendLine("  push:")
                appendLine("    branches: [ main, master ]")
                appendLine()
                appendLine("permissions:")
                appendLine("  contents: write")
                appendLine()
                appendLine("jobs:")
                appendLine("  build-apk:")
                appendLine("    runs-on: ubuntu-latest")
                appendLine("    steps:")
                appendLine("      - name: Checkout repository")
                appendLine("        uses: actions/checkout@v4")
                appendLine()
                appendLine("      - name: Set up JDK 17")
                appendLine("        uses: actions/setup-java@v4")
                appendLine("        with:")
                appendLine("          distribution: temurin")
                appendLine("          java-version: '17'")
                appendLine()
                appendLine("      - name: Set up Gradle with cache")
                appendLine("        uses: gradle/actions/setup-gradle@v4")
                appendLine("        with:")
                appendLine("          gradle-version: '8.11.1'")
                appendLine()
                appendLine("      - name: Build Android APK")
                appendLine("        run: |")
                appendLine("          if [ -f ./gradlew ]; then")
                appendLine("            chmod +x ./gradlew")
                appendLine("            ./gradlew $task --stacktrace")
                appendLine("          else")
                appendLine("            gradle $task --stacktrace")
                appendLine("          fi")
                appendLine()
                appendLine("      - name: Upload APK Artifact")
                appendLine("        uses: actions/upload-artifact@v4")
                appendLine("        with:")
                appendLine("          name: teena-dev-apk")
                appendLine("          path: app/build/outputs/apk/**/*.apk")
                appendLine("          if-no-files-found: error")
                if (includeRelease) {
                    appendLine()
                    appendLine("      - name: Publish GitHub Release (Optional)")
                    appendLine("        if: success()")
                    appendLine("        uses: softprops/action-gh-release@v2")
                    appendLine("        with:")
                    appendLine("          tag_name: dev-build-\${{ github.run_number }}")
                    appendLine("          files: app/build/outputs/apk/**/*.apk")
                }
            }
        }

        fun bumpVersionNamePatch(current: String): String {
            val parts = current.trim().split(".").map { it.toIntOrNull() }
            return if (parts.size == 3 && parts.all { it != null }) {
                "${parts[0]}.${parts[1]}.${(parts[2] ?: 0) + 1}"
            } else {
                "1.0.1"
            }
        }

        fun bumpVersionInExistingGradle(workspaceDir: File, versionCode: Int, versionName: String) {
            val kts = File(workspaceDir, "app/build.gradle.kts")
            val groovy = File(workspaceDir, "app/build.gradle")
            val target = when {
                kts.exists() -> kts
                groovy.exists() -> groovy
                else -> return
            }
            var content = target.readText(Charsets.UTF_8)
            content = content.replace(
                Regex("""versionCode\s*=?\s*\d+"""),
                if (target.extension == "kts") "versionCode = $versionCode" else "versionCode $versionCode"
            )
            content = content.replace(
                Regex("""versionName\s*=?\s*"[^"]+""""),
                if (target.extension == "kts") "versionName = \"$versionName\"" else "versionName \"$versionName\""
            )
            target.writeText(content, Charsets.UTF_8)
        }

        private fun sha256HexFile(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(16 * 1024)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun sha256Colon(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(":") { "%02X".format(it) }
        }

        private fun generateRandomPassword(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..20).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }

        /**
         * Pure-Java DER-encoded self-signed X.509 v3 certificate generator (no sun.security dependency),
         * compatible with both Android ART and Robolectric JVM.
         */
        private fun buildSelfSignedX509Cert(
            subjectPublicKeyInfoDer: ByteArray,
            privateKey: PrivateKey
        ): X509Certificate {
            val now = Date()
            val notAfter = Date(now.time + 3650L * 24 * 60 * 60 * 1000L) // 10 years
            val serial = BigInteger(64, SecureRandom()).abs()

            // CN=Teena Dev Studio
            val cnBytes = "Teena Dev Studio".toByteArray(Charsets.UTF_8)
            val attrTypeAndValue = derSeq(
                derOid(byteArrayOf(0x55, 0x04, 0x03)), // 2.5.4.3 (CN)
                derUtf8(cnBytes)
            )
            val rdn = derSet(attrTypeAndValue)
            val dn = derSeq(rdn)

            // sha256WithRSAEncryption OID: 1.2.840.113549.1.1.11
            val sigAlgId = derSeq(
                derOid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
                byteArrayOf(0x05, 0x00) // NULL
            )

            val validity = derSeq(derUtcTime(now), derUtcTime(notAfter))
            val versionTag = derExplicitTag(0, derInteger(BigInteger.valueOf(2))) // v3

            val tbsCert = derSeq(
                versionTag,
                derInteger(serial),
                sigAlgId,
                dn,
                validity,
                dn,
                subjectPublicKeyInfoDer
            )

            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(privateKey)
                update(tbsCert)
            }.sign()

            val fullCertDer = derSeq(tbsCert, sigAlgId, derBitString(sig))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(fullCertDer)) as X509Certificate
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray {
            val len = content.size
            val lenBytes = when {
                len < 128 -> byteArrayOf(len.toByte())
                len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
                else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
            }
            return byteArrayOf(tag.toByte()) + lenBytes + content
        }

        private fun derSeq(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))
        private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))
        private fun derOid(encoded: ByteArray): ByteArray = derTag(0x06, encoded)
        private fun derUtf8(bytes: ByteArray): ByteArray = derTag(0x0C, bytes)
        private fun derInteger(v: BigInteger): ByteArray = derTag(0x02, v.toByteArray())
        private fun derExplicitTag(num: Int, inner: ByteArray): ByteArray = derTag(0xA0 or num, inner)
        private fun derBitString(bytes: ByteArray): ByteArray = derTag(0x03, byteArrayOf(0x00) + bytes)

        private fun derUtcTime(date: Date): ByteArray {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
        }

        private fun concat(vararg arrays: ByteArray): ByteArray {
            val total = arrays.sumOf { it.size }
            val out = ByteArray(total)
            var pos = 0
            for (a in arrays) {
                System.arraycopy(a, 0, out, pos, a.size)
                pos += a.size
            }
            return out
        }
    }
}
