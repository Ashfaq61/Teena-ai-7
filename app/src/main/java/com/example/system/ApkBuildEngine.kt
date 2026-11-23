package com.example.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class InstalledApkInspection(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val signingCertSha256: String,
    val isDebuggable: Boolean,
    val sourcePath: String
)

class ApkBuildEngine(private val context: Context) {

    suspend fun inspectCurrentApk(): InstalledApkInspection = withContext(Dispatchers.IO) {
        val pkgName = context.packageName
        val appInfo = context.applicationInfo
        val sourceFile = appInfo.sourceDir?.let { File(it) }
        val sizeBytes = if (sourceFile?.exists() == true) sourceFile.length() else 0L
        val apkSha256 = if (sourceFile?.exists() == true) computeFileSha256(sourceFile) else "N/A (Test JVM)"
        val certSha256 = readSigningCertSha256(pkgName)

        InstalledApkInspection(
            packageName = pkgName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            apkSizeBytes = sizeBytes,
            apkSha256 = apkSha256,
            signingCertSha256 = certSha256,
            isDebuggable = BuildConfig.DEBUG,
            sourcePath = appInfo.sourceDir.orEmpty()
        )
    }

    private fun computeFileSha256(file: File): String {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString(":") { "%02X".format(it) }
        }.getOrDefault("Unavailable")
    }

    private fun readSigningCertSha256(pkgName: String): String {
        return runCatching {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES).signatures
            }
            val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return "Unavailable"
            val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
            digest.joinToString(":") { "%02X".format(it) }
        }.getOrDefault("Unavailable")
    }
}
