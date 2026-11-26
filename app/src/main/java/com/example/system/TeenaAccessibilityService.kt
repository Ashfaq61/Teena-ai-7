package com.example.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class UiNodeSummary(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: Rect,
    val focused: Boolean = false,
    val isPassword: Boolean = false
)

data class ScreenSnapshot(
    val packageName: String,
    val windowClass: String,
    val visibleText: List<String>,
    val nodes: List<UiNodeSummary>,
    val capturedAt: Long = System.currentTimeMillis(),
    val displayWidth: Int = 1080,
    val displayHeight: Int = 2400,
    val isSecureWindow: Boolean = false,
    val secureReason: String = ""
)

data class AutomationResult(
    val success: Boolean,
    val verified: Boolean,
    val action: String,
    val details: String
)

class SecureScreenException(message: String) : SecurityException(message)

object ScreenSafetyDetector {
    private val sensitivePackageMarkers = listOf(
        "keyguard", "lockscreen", "settings.password", "biometric", "fingerprint",
        "authenticator", "totp", "bitwarden", "1password", "lastpass", "dashlane", "keepass",
        "banking", "netbanking", "upi", "paytm", "phonepe", "paisa", "easypaisa", "jazzcash",
        "sadapay", "nayapay", "meezan", "hbl", "ubl", "alfalah", "faysal", "mcb", "paypal",
        "stripe", "venmo", "cashapp", "wallet"
    )

    private val sensitiveTextMarkers = listOf(
        "enter otp", "one-time password", "one time password", "verification code",
        "enter pin", "upi pin", "mpin", "atm pin", "enter passcode", "confirm passcode",
        "enter password", "confirm password", "cvv", "card number", "expiry date",
        "netbanking", "biometric authentication", "verify your identity"
    )

    fun detectSecureScreen(
        snapshot: ScreenSnapshot,
        windowFlagsSecure: Boolean = false
    ): Pair<Boolean, String> {
        if (windowFlagsSecure) {
            return true to "FLAG_SECURE window flag detected"
        }
        if (snapshot.nodes.any { it.isPassword }) {
            return true to "Password input field detected on screen"
        }
        val pkgLower = snapshot.packageName.lowercase()
        val matchedPkg = sensitivePackageMarkers.firstOrNull { pkgLower.contains(it) }
        if (matchedPkg != null) {
            return true to "Sensitive security/banking/wallet app detected ($matchedPkg)"
        }
        val combinedText = buildString {
            append(snapshot.windowClass.lowercase())
            append(' ')
            snapshot.visibleText.forEach { append(it.lowercase()).append(' ') }
            snapshot.nodes.forEach {
                append(it.viewId.lowercase()).append(' ')
                append(it.contentDescription.lowercase()).append(' ')
            }
        }
        val matchedText = sensitiveTextMarkers.firstOrNull { combinedText.contains(it) }
        if (matchedText != null) {
            return true to "Sensitive credential/OTP/banking prompt detected ('$matchedText')"
        }
        return false to ""
    }

    fun computeScreenSignature(snapshot: ScreenSnapshot): String {
        val topTexts = snapshot.visibleText.take(20).joinToString("|") { it.trim().lowercase() }
        val nodeShape = snapshot.nodes.take(25).joinToString(",") { "${it.viewId}:${it.clickable}:${it.editable}" }
        return "${snapshot.packageName}|${snapshot.windowClass}|${topTexts.hashCode()}|${nodeShape.hashCode()}"
    }
}

class TeenaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TeenaAccessibilityService? = null
            private set

        @Volatile
        var emergencyHaltActive: Boolean = false

        @Volatile
        var runtimeVerifiedOnDevice: Boolean = false
            internal set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _latestSnapshot = MutableStateFlow<ScreenSnapshot?>(null)
        val latestSnapshot: StateFlow<ScreenSnapshot?> = _latestSnapshot.asStateFlow()

        fun isAccessibilityEnabledInSettings(context: Context): Boolean {
            if (instance != null) return true
            val expected = "${context.packageName}/${TeenaAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabledServices.split(':').any {
                it.equals(expected, ignoreCase = true) ||
                    it.endsWith("/${TeenaAccessibilityService::class.java.simpleName}", ignoreCase = true)
            }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        captureCurrentScreen()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || emergencyHaltActive) return
        captureCurrentScreen(event.packageName?.toString(), event.className?.toString())
    }

    override fun onInterrupt() {
        // Service interrupted by system
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _isConnected.value = false
        return super.onUnbind(intent)
    }

    fun captureCurrentScreen(pkgOverride: String? = null, clsOverride: String? = null): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return _latestSnapshot.value
        val nodes = mutableListOf<UiNodeSummary>()
        val texts = mutableListOf<String>()
        traverseNode(root, nodes, texts, depth = 0)
        val dm = resources.displayMetrics
        val baseSnap = ScreenSnapshot(
            packageName = pkgOverride ?: root.packageName?.toString().orEmpty(),
            windowClass = clsOverride ?: root.className?.toString().orEmpty(),
            visibleText = texts.distinct().take(70),
            nodes = nodes.take(140),
            displayWidth = dm.widthPixels.coerceAtLeast(1),
            displayHeight = dm.heightPixels.coerceAtLeast(1)
        )
        val (isSecure, secureReason) = ScreenSafetyDetector.detectSecureScreen(baseSnap)
        val finalSnap = baseSnap.copy(isSecureWindow = isSecure, secureReason = secureReason)
        _latestSnapshot.value = finalSnap
        if (finalSnap.nodes.isNotEmpty() || finalSnap.visibleText.isNotEmpty()) {
            runtimeVerifiedOnDevice = true
        }
        return finalSnap
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        outNodes: MutableList<UiNodeSummary>,
        outTexts: MutableList<String>,
        depth: Int
    ) {
        if (depth > 24 || outNodes.size >= 140) return
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty()) outTexts.add(txt)
        if (desc.isNotEmpty() && desc != txt) outTexts.add(desc)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (txt.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable ||
            node.isScrollable || node.isFocused || node.isPassword || !node.viewIdResourceName.isNullOrBlank()
        ) {
            outNodes.add(
                UiNodeSummary(
                    text = txt,
                    contentDescription = desc,
                    viewId = node.viewIdResourceName.orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    bounds = rect,
                    focused = node.isFocused,
                    isPassword = node.isPassword
                )
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, outNodes, outTexts, depth + 1)
            }
        }
    }

    /**
     * Captures a live RAM-only Bitmap screenshot via Android 11+ AccessibilityService.takeScreenshot.
     * NEVER writes to disk or Room; refuses immediately on password/OTP/banking/FLAG_SECURE screens.
     */
    @SuppressLint("NewApi")
    suspend fun captureScreenshotBitmap(): Result<Bitmap> {
        if (emergencyHaltActive) {
            return Result.failure(SecurityException("Emergency Stop active: screenshot cancelled."))
        }
        val currentSnap = captureCurrentScreen()
        if (currentSnap?.isSecureWindow == true) {
            return Result.failure(
                SecureScreenException("Secure screen blocked (${currentSnap.secureReason}): na screenshot liya gaya na action.")
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(
                IllegalStateException("Android 11+ (API 30) zaroori hai AccessibilityService.takeScreenshot ke liye (current API=${Build.VERSION.SDK_INT}).")
            )
        }
        val bitmapResult = withTimeoutOrNull(7_000L) {
            suspendCancellableCoroutine<Result<Bitmap>> { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val hwBuffer = screenshot.hardwareBuffer
                                try {
                                    val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                    val softwareCopy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                    if (softwareCopy != null) {
                                        runtimeVerifiedOnDevice = true
                                        cont.safeResume(Result.success(softwareCopy))
                                    } else {
                                        cont.safeResume(Result.failure(IllegalStateException("HardwareBuffer bitmap conversion failed.")))
                                    }
                                } catch (e: Exception) {
                                    cont.safeResume(Result.failure(e))
                                } finally {
                                    runCatching { hwBuffer.close() }
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                                    cont.safeResume(
                                        Result.failure(
                                            SecureScreenException("FLAG_SECURE window detected (error=$errorCode): secure screen par screenshot aur action band hain.")
                                        )
                                    )
                                } else {
                                    cont.safeResume(
                                        Result.failure(
                                            IllegalStateException("Accessibility takeScreenshot failed (errorCode=$errorCode).")
                                        )
                                    )
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    cont.safeResume(Result.failure(e))
                }
            }
        }
        return bitmapResult ?: Result.failure(IllegalStateException("Screenshot capture timed out after 7s."))
    }

    /**
     * Human-like stability wait after every UI action:
     * waits minimum animation time (`minSettleMs`) and polls until consecutive UI signatures match
     * or `maxWaitMs` completes.
     */
    suspend fun waitForScreenStability(
        minSettleMs: Long = 520L,
        maxWaitMs: Long = 2400L,
        pollIntervalMs: Long = 260L
    ): ScreenSnapshot? {
        delay(minSettleMs)
        var prevSnap = captureCurrentScreen()
        var elapsed = minSettleMs
        var stableHits = 0
        while (elapsed < maxWaitMs && !emergencyHaltActive) {
            delay(pollIntervalMs)
            elapsed += pollIntervalMs
            val current = captureCurrentScreen()
            if (prevSnap != null && current != null &&
                ScreenSafetyDetector.computeScreenSignature(prevSnap) == ScreenSafetyDetector.computeScreenSignature(current)
            ) {
                stableHits++
                if (stableHits >= 1) return current
            } else {
                stableHits = 0
            }
            prevSnap = current
        }
        return prevSnap
    }

    /**
     * Priority tap: viewId -> text/contentDescription -> bounds center -> vision coordinates.
     */
    suspend fun tapWithPriority(
        viewId: String = "",
        textOrDesc: String = "",
        boundsRect: Rect? = null,
        x: Float? = null,
        y: Float? = null
    ): AutomationResult {
        if (emergencyHaltActive) {
            return AutomationResult(false, false, "TAP", "Halted by Emergency Stop.")
        }
        val before = captureCurrentScreen()
        if (before?.isSecureWindow == true) {
            return AutomationResult(false, false, "TAP", "Secure screen blocked: ${before.secureReason}")
        }
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "TAP", "No active window root available."
        )

        var strategyUsed = ""
        var targetNode: AccessibilityNodeInfo? = null

        // 1. viewId priority
        if (viewId.isNotBlank()) {
            val byId = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull().orEmpty()
            targetNode = byId.firstOrNull() ?: findNodeMatching(root) { n ->
                n.viewIdResourceName?.equals(viewId, ignoreCase = true) == true ||
                    n.viewIdResourceName?.endsWith("/$viewId", ignoreCase = true) == true
            }
            if (targetNode != null) strategyUsed = "viewId($viewId)"
        }

        // 2. text or contentDescription priority
        if (targetNode == null && textOrDesc.isNotBlank()) {
            targetNode = findNodeMatching(root) { n ->
                n.text?.toString()?.equals(textOrDesc, ignoreCase = true) == true ||
                    n.contentDescription?.toString()?.equals(textOrDesc, ignoreCase = true) == true
            } ?: findNodeMatching(root) { n ->
                n.text?.toString()?.contains(textOrDesc, ignoreCase = true) == true ||
                    n.contentDescription?.toString()?.contains(textOrDesc, ignoreCase = true) == true
            }
            if (targetNode != null) strategyUsed = "text($textOrDesc)"
        }

        var dispatched = false
        if (targetNode != null) {
            var clickableNode: AccessibilityNodeInfo? = targetNode
            while (clickableNode != null && !clickableNode.isClickable) {
                clickableNode = clickableNode.parent
            }
            if (clickableNode != null) {
                dispatched = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!dispatched) {
                val r = Rect()
                targetNode.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    strategyUsed += "->bounds(${r.centerX()},${r.centerY()})"
                    dispatched = dispatchTapGestureAwait(r.centerX().toFloat(), r.centerY().toFloat())
                }
            }
        }

        // 3. Bounds center fallback
        if (!dispatched && boundsRect != null && !boundsRect.isEmpty) {
            strategyUsed = "bounds(${boundsRect.centerX()},${boundsRect.centerY()})"
            dispatched = dispatchTapGestureAwait(boundsRect.centerX().toFloat(), boundsRect.centerY().toFloat())
        }

        // 4. Vision coordinate fallback
        if (!dispatched && x != null && y != null && x >= 0f && y >= 0f) {
            strategyUsed = "vision(${x.toInt()},${y.toInt()})"
            dispatched = dispatchTapGestureAwait(x, y)
        }

        if (!dispatched && strategyUsed.isBlank()) {
            return AutomationResult(
                false,
                false,
                "TAP(${viewId.ifBlank { textOrDesc }})",
                "Target not found via viewId, text, bounds, or vision coordinates."
            )
        }

        val after = waitForScreenStability()
        val uiChanged = hasMeaningfulScreenChange(before, after)
        if (dispatched) runtimeVerifiedOnDevice = true
        return AutomationResult(
            success = dispatched,
            verified = dispatched && uiChanged,
            action = "TAP[$strategyUsed]",
            details = when {
                !dispatched -> "Tap rejected via $strategyUsed."
                uiChanged -> "Tapped via $strategyUsed and verified screen changed (${after?.packageName})."
                else -> "Tapped via $strategyUsed; screen tree unchanged after stability wait."
            }
        )
    }

    suspend fun tapByTextOrDesc(query: String): AutomationResult =
        tapWithPriority(textOrDesc = query)

    suspend fun longPressWithPriority(
        viewId: String = "",
        textOrDesc: String = "",
        x: Float? = null,
        y: Float? = null
    ): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "LONG_PRESS", "Halted by Emergency Stop.")
        val before = captureCurrentScreen()
        if (before?.isSecureWindow == true) {
            return AutomationResult(false, false, "LONG_PRESS", "Secure screen blocked: ${before.secureReason}")
        }
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "LONG_PRESS", "No active window root available."
        )
        val target = (if (viewId.isNotBlank()) findNodeMatching(root) { it.viewIdResourceName?.contains(viewId, true) == true } else null)
            ?: (if (textOrDesc.isNotBlank()) findNodeMatching(root) { n ->
                n.text?.toString()?.contains(textOrDesc, true) == true ||
                    n.contentDescription?.toString()?.contains(textOrDesc, true) == true
            } else null)

        val ok = when {
            target != null && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) -> true
            target != null -> {
                val r = Rect()
                target.getBoundsInScreen(r)
                if (!r.isEmpty) dispatchLongPressGestureAwait(r.centerX().toFloat(), r.centerY().toFloat()) else false
            }
            x != null && y != null && x >= 0f && y >= 0f -> dispatchLongPressGestureAwait(x, y)
            else -> false
        }
        val after = waitForScreenStability()
        val changed = hasMeaningfulScreenChange(before, after)
        if (ok) runtimeVerifiedOnDevice = true
        return AutomationResult(
            ok,
            ok && changed,
            "LONG_PRESS(${viewId.ifBlank { textOrDesc }.ifBlank { "${x?.toInt()},${y?.toInt()}" }})",
            if (ok) "Long-press executed (changed=$changed)." else "Long-press target unavailable or rejected."
        )
    }

    suspend fun longPressByText(query: String): AutomationResult =
        longPressWithPriority(textOrDesc = query)

    suspend fun typeTextSmart(
        text: String,
        viewId: String = "",
        targetHintOrText: String = "",
        x: Float? = null,
        y: Float? = null,
        submit: Boolean = false
    ): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "TYPE_TEXT", "Halted by Emergency Stop.")
        val before = captureCurrentScreen()
        if (before?.isSecureWindow == true) {
            return AutomationResult(false, false, "TYPE_TEXT", "Secure screen blocked: ${before.secureReason}")
        }
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "TYPE_TEXT", "No active window root available."
        )

        var target = if (viewId.isNotBlank()) {
            findNodeMatching(root) { it.viewIdResourceName?.contains(viewId, true) == true && it.isEditable }
                ?: findNodeMatching(root) { it.viewIdResourceName?.contains(viewId, true) == true }
        } else null

        if (target == null && targetHintOrText.isNotBlank()) {
            target = findNodeMatching(root) { n ->
                n.isEditable && (
                    n.text?.toString()?.contains(targetHintOrText, true) == true ||
                        n.contentDescription?.toString()?.contains(targetHintOrText, true) == true ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.hintText?.toString()?.contains(targetHintOrText, true) == true)
                    )
            }
        }
        if (target == null) {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findNodeMatching(root) { it.isEditable }
        }

        // If still no editable node found and vision coordinates were given, tap those coordinates first to open/focus field
        if (target == null && x != null && y != null && x >= 0f && y >= 0f) {
            dispatchTapGestureAwait(x, y)
            waitForScreenStability(minSettleMs = 420L, maxWaitMs = 1500L)
            val newRoot = rootInActiveWindow
            if (newRoot != null) {
                target = newRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findNodeMatching(newRoot) { it.isEditable }
            }
        }

        if (target == null) {
            return AutomationResult(false, false, "TYPE_TEXT", "No editable text field found on screen.")
        }
        if (target.isPassword) {
            return AutomationResult(false, false, "TYPE_TEXT", "Secure password input blocked.")
        }

        if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(280L)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        var submitNote = ""
        if (setOk && submit) {
            delay(320L)
            var submitted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submitted = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!submitted) {
                val refreshedRoot = rootInActiveWindow
                val submitBtn = refreshedRoot?.let { r ->
                    findNodeMatching(r) { n ->
                        val t = n.text?.toString().orEmpty()
                        val d = n.contentDescription?.toString().orEmpty()
                        val vid = n.viewIdResourceName.orEmpty()
                        n.isClickable && (
                            t.equals("Search", true) || t.equals("Send", true) || t.equals("Go", true) ||
                                d.contains("search", true) || d.contains("send", true) ||
                                vid.contains("search_button", true) || vid.contains("send", true)
                            )
                    }
                }
                if (submitBtn != null) {
                    submitted = submitBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            submitNote = if (submitted) " + submitted" else " (submit trigger not matched)"
        }

        val after = waitForScreenStability()
        val verified = after?.visibleText?.any { it.contains(text.take(10), ignoreCase = true) } == true ||
            hasMeaningfulScreenChange(before, after)
        if (setOk) runtimeVerifiedOnDevice = true
        return AutomationResult(
            success = setOk,
            verified = setOk && verified,
            action = "TYPE_TEXT(${text.take(24)})",
            details = if (setOk && verified) "Text typed$submitNote and verified." else if (setOk) "Set-text returned true$submitNote." else "Text field rejected input."
        )
    }

    suspend fun typeIntoFocusedOrEditable(text: String): AutomationResult =
        typeTextSmart(text = text, submit = false)

    suspend fun inAppSearch(query: String): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "IN_APP_SEARCH", "Halted by Emergency Stop.")
        val before = captureCurrentScreen()
        if (before?.isSecureWindow == true) {
            return AutomationResult(false, false, "IN_APP_SEARCH", "Secure screen blocked: ${before.secureReason}")
        }
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "IN_APP_SEARCH", "No active window root available."
        )

        val existingInput = findNodeMatching(root) { n ->
            n.isEditable && (
                n.viewIdResourceName?.contains("search", true) == true ||
                    n.contentDescription?.toString()?.contains("search", true) == true ||
                    n.text?.toString()?.contains("search", true) == true
                )
        }
        if (existingInput == null) {
            val searchTrigger = findNodeMatching(root) { n ->
                val vid = n.viewIdResourceName.orEmpty()
                val desc = n.contentDescription?.toString().orEmpty()
                val txt = n.text?.toString().orEmpty()
                (n.isClickable || n.parent?.isClickable == true) && (
                    vid.contains("search", true) || desc.contains("search", true) ||
                        txt.equals("Search", true) || txt.contains("Search ", true)
                    )
            }
            if (searchTrigger != null) {
                var clickable: AccessibilityNodeInfo? = searchTrigger
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                if (!clicked) {
                    val r = Rect()
                    searchTrigger.getBoundsInScreen(r)
                    if (!r.isEmpty) dispatchTapGestureAwait(r.centerX().toFloat(), r.centerY().toFloat())
                }
                waitForScreenStability(minSettleMs = 550L, maxWaitMs = 2000L)
            }
        }
        return typeTextSmart(text = query, submit = true)
    }

    suspend fun scrollScreen(forward: Boolean): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "SCROLL", "Halted by Emergency Stop.")
        val before = captureCurrentScreen()
        if (before?.isSecureWindow == true) {
            return AutomationResult(false, false, "SCROLL", "Secure screen blocked: ${before.secureReason}")
        }
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "SCROLL", "No active window root available."
        )
        val scrollable = findNodeMatching(root) { it.isScrollable }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val nodeScrolled = scrollable?.performAction(action) ?: false
        if (nodeScrolled) {
            val after = waitForScreenStability()
            val changed = hasMeaningfulScreenChange(before, after)
            runtimeVerifiedOnDevice = true
            return AutomationResult(
                success = true,
                verified = changed,
                action = if (forward) "SCROLL_FORWARD" else "SCROLL_BACKWARD",
                details = if (changed) "ScrollView scrolled and verified new content appeared." else "ScrollView action dispatched."
            )
        } else {
            return swipeDirection(if (forward) "UP" else "DOWN")
        }
    }

    suspend fun swipeDirection(direction: String): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "SWIPE_$direction", "Halted by Emergency Stop.")
        val before = captureCurrentScreen()
        if (before?.isSecureWindow == true) {
            return AutomationResult(false, false, "SWIPE_$direction", "Secure screen blocked: ${before.secureReason}")
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (sx, sy, ex, ey) = when (direction.uppercase()) {
            "UP" -> listOf(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.25f)
            "DOWN" -> listOf(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.76f)
            "LEFT" -> listOf(w * 0.84f, h * 0.5f, w * 0.16f, h * 0.5f)
            "RIGHT" -> listOf(w * 0.16f, h * 0.5f, w * 0.84f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f)
        }
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        // Natural human swipe speed (~420ms)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
            .build()
        val dispatched = dispatchGestureWithAwait(gesture)
        val after = waitForScreenStability()
        val changed = hasMeaningfulScreenChange(before, after)
        if (dispatched) runtimeVerifiedOnDevice = true
        return AutomationResult(
            success = dispatched,
            verified = dispatched && changed,
            action = "SWIPE_${direction.uppercase()}",
            details = if (changed) "Natural swipe ${direction.uppercase()} verified with UI change." else "Swipe ${direction.uppercase()} dispatched."
        )
    }

    suspend fun openAppByNameOrPackage(appQuery: String): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "OPEN_APP", "Halted by Emergency Stop.")
        val clean = appQuery.trim()
        val lower = clean.lowercase()
        val candidatePackages = buildList {
            if (clean.contains(".")) add(clean)
            when {
                lower.contains("youtube") -> add("com.google.android.youtube")
                lower.contains("whatsapp") -> {
                    add("com.whatsapp")
                    add("com.whatsapp.w4b")
                }
                lower.contains("instagram") -> add("com.instagram.android")
                lower.contains("facebook") -> add("com.facebook.katana")
                lower.contains("tiktok") -> {
                    add("com.zhiliaoapp.musically")
                    add("com.ss.android.ugc.trill")
                }
                lower.contains("telegram") -> add("org.telegram.messenger")
                lower.contains("maps") -> add("com.google.android.apps.maps")
                lower.contains("chrome") -> add("com.android.chrome")
                lower.contains("settings") -> add("com.android.settings")
                lower.contains("gmail") -> add("com.google.android.gm")
                lower.contains("play store") -> add("com.android.vending")
            }
        }

        val pm = packageManager
        val launchIntent = candidatePackages.firstNotNullOfOrNull { pkg ->
            pm.getLaunchIntentForPackage(pkg)
        } ?: run {
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val matches = pm.queryIntentActivities(mainIntent, 0)
            val resolved = matches.firstOrNull { ri ->
                val label = ri.loadLabel(pm).toString()
                label.contains(clean, ignoreCase = true) ||
                    ri.activityInfo.packageName.contains(lower)
            }
            resolved?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        }

        if (launchIntent == null) {
            return AutomationResult(false, false, "OPEN_APP($clean)", "App '$clean' installed/launchable nahi mila.")
        }
        val before = captureCurrentScreen()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        val after = waitForScreenStability(minSettleMs = 850L, maxWaitMs = 3000L)
        val targetPkg = launchIntent.`package`.orEmpty()
        val afterPkg = after?.packageName.orEmpty()
        val verified = after != null && (
            (targetPkg.isNotBlank() && afterPkg.equals(targetPkg, ignoreCase = true)) ||
                before?.packageName != afterPkg
            )
        runtimeVerifiedOnDevice = true
        return AutomationResult(
            success = true,
            verified = verified,
            action = "OPEN_APP($clean)",
            details = if (verified) "Opened $afterPkg and verified active window." else "Launch intent sent for '$clean' (current=$afterPkg)."
        )
    }

    suspend fun goBackVerified(): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "GLOBAL_BACK", "Halted by Emergency Stop.")
        val before = captureCurrentScreen()
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        val after = waitForScreenStability()
        val changed = hasMeaningfulScreenChange(before, after)
        if (ok) runtimeVerifiedOnDevice = true
        return AutomationResult(ok, ok && changed, "GLOBAL_BACK", if (changed) "Navigated back and verified change." else "Back action executed.")
    }

    suspend fun goHomeVerified(): AutomationResult {
        if (emergencyHaltActive) return AutomationResult(false, false, "GLOBAL_HOME", "Halted by Emergency Stop.")
        val before = captureCurrentScreen()
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        val after = waitForScreenStability(minSettleMs = 650L, maxWaitMs = 2400L)
        val changed = hasMeaningfulScreenChange(before, after)
        if (ok) runtimeVerifiedOnDevice = true
        return AutomationResult(ok, ok && changed, "GLOBAL_HOME", "Home screen requested (${after?.packageName ?: "launcher"}).")
    }

    private fun hasMeaningfulScreenChange(before: ScreenSnapshot?, after: ScreenSnapshot?): Boolean {
        if (before == null || after == null) return false
        return before.packageName != after.packageName ||
            before.windowClass != after.windowClass ||
            before.visibleText != after.visibleText
    }

    private suspend fun dispatchTapGestureAwait(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGestureWithAwait(gesture)
    }

    private suspend fun dispatchLongPressGestureAwait(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 720))
            .build()
        return dispatchGestureWithAwait(gesture)
    }

    private suspend fun dispatchGestureWithAwait(gesture: GestureDescription): Boolean {
        val res = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val accepted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            cont.safeResume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            cont.safeResume(false)
                        }
                    },
                    null
                )
                if (!accepted) {
                    cont.safeResume(false)
                }
            }
        }
        return res ?: false
    }

    private fun findNodeMatching(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findNodeMatching(child, predicate)
            if (match != null) return match
        }
        return null
    }

    private fun <T> CancellableContinuation<T>.safeResume(value: T) {
        if (isActive) resume(value)
    }
}
