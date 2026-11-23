package com.example.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.URLEncoder

enum class SocialPlatform(
    val displayName: String,
    val primaryPackage: String,
    val fallbackPackage: String? = null
) {
    WHATSAPP("WhatsApp", "com.whatsapp", "com.whatsapp.w4b"),
    FACEBOOK("Facebook", "com.facebook.katana"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    TIKTOK("TikTok", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
    YOUTUBE("YouTube", "com.google.android.youtube"),
    TELEGRAM("Telegram", "org.telegram.messenger")
}

data class SocialAppStatus(
    val platform: SocialPlatform,
    val installed: Boolean,
    val activePackage: String?
)

class SocialAutomationEngine(private val context: Context) {

    fun checkInstalledApps(): List<SocialAppStatus> {
        return SocialPlatform.entries.map { platform ->
            val pkg = resolveInstalledPackage(platform)
            SocialAppStatus(platform, pkg != null, pkg)
        }
    }

    fun resolveInstalledPackage(platform: SocialPlatform): String? {
        val candidates = listOfNotNull(platform.primaryPackage, platform.fallbackPackage)
        return candidates.firstOrNull { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                true
            }.recoverCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
    }

    fun openPlatform(platform: SocialPlatform): Result<String> {
        val pkg = resolveInstalledPackage(platform)
        if (pkg != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return Result.success("Opened ${platform.displayName} ($pkg).")
            }
        }
        val webUrl = when (platform) {
            SocialPlatform.WHATSAPP -> "https://web.whatsapp.com"
            SocialPlatform.FACEBOOK -> "https://www.facebook.com"
            SocialPlatform.INSTAGRAM -> "https://www.instagram.com"
            SocialPlatform.TIKTOK -> "https://www.tiktok.com"
            SocialPlatform.YOUTUBE -> "https://www.youtube.com"
            SocialPlatform.TELEGRAM -> "https://web.telegram.org"
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "${platform.displayName} app not installed; opened web portal."
        }
    }

    fun searchPlatformContent(platform: SocialPlatform, query: String): Result<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val uri = when (platform) {
            SocialPlatform.YOUTUBE -> Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            SocialPlatform.TIKTOK -> Uri.parse("https://www.tiktok.com/search?q=$encoded")
            SocialPlatform.INSTAGRAM -> Uri.parse("https://www.instagram.com/explore/tags/${query.replace(" ", "")}/")
            SocialPlatform.FACEBOOK -> Uri.parse("https://www.facebook.com/search/top/?q=$encoded")
            SocialPlatform.TELEGRAM -> Uri.parse("https://t.me/s/${query.replace(" ", "")}")
            SocialPlatform.WHATSAPP -> Uri.parse("https://wa.me/?text=$encoded")
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            resolveInstalledPackage(platform)?.let { pkg ->
                if (platform == SocialPlatform.YOUTUBE || platform == SocialPlatform.TIKTOK) {
                    intent.setPackage(pkg)
                }
            }
            context.startActivity(intent)
            "Opened ${platform.displayName} search/workflow for '$query'."
        }
    }

    fun openWhatsAppDraft(phoneOrEmpty: String, message: String): Result<String> {
        val encoded = URLEncoder.encode(message, "UTF-8")
        val cleanPhone = phoneOrEmpty.filter { it.isDigit() }
        val url = if (cleanPhone.isNotBlank()) {
            "https://wa.me/$cleanPhone?text=$encoded"
        } else {
            "https://wa.me/?text=$encoded"
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Prepared authorized WhatsApp draft."
        }
    }

    fun shareCaptionOrPost(platform: SocialPlatform, text: String, mediaUri: Uri? = null): Result<String> {
        val pkg = resolveInstalledPackage(platform)
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (mediaUri != null) {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, mediaUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            putExtra(Intent.EXTRA_TEXT, text)
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Share via ${platform.displayName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Opened authorized composer for ${platform.displayName}."
        }
    }
}
