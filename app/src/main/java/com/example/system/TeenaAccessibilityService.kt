package com.example.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiNodeSummary(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: Rect
)

data class ScreenSnapshot(
    val packageName: String,
    val windowClass: String,
    val visibleText: List<String>,
    val nodes: List<UiNodeSummary>,
    val capturedAt: Long = System.currentTimeMillis()
)

data class AutomationResult(
    val success: Boolean,
    val verified: Boolean,
    val action: String,
    val details: String
)

class TeenaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TeenaAccessibilityService? = null
            private set

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
        if (event == null) return
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
        val snap = ScreenSnapshot(
            packageName = pkgOverride ?: root.packageName?.toString().orEmpty(),
            windowClass = clsOverride ?: root.className?.toString().orEmpty(),
            visibleText = texts.distinct().take(60),
            nodes = nodes.take(120)
        )
        _latestSnapshot.value = snap
        return snap
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        outNodes: MutableList<UiNodeSummary>,
        outTexts: MutableList<String>,
        depth: Int
    ) {
        if (depth > 22 || outNodes.size >= 120) return
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty()) outTexts.add(txt)
        if (desc.isNotEmpty() && desc != txt) outTexts.add(desc)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (txt.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
            outNodes.add(
                UiNodeSummary(
                    text = txt,
                    contentDescription = desc,
                    viewId = node.viewIdResourceName.orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    bounds = rect
                )
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, outNodes, outTexts, depth + 1)
            }
        }
    }

    suspend fun tapByTextOrDesc(query: String): AutomationResult {
        val before = captureCurrentScreen()
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "TAP($query)", "No active window root available."
        )
        val target = findNodeMatching(root) { n ->
            n.text?.toString()?.contains(query, ignoreCase = true) == true ||
                n.contentDescription?.toString()?.contains(query, ignoreCase = true) == true
        } ?: return AutomationResult(
            false, false, "TAP($query)", "No visible UI element matched '$query'."
        )

        var clickableNode: AccessibilityNodeInfo? = target
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        val clicked = if (clickableNode != null) {
            clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val r = Rect()
            target.getBoundsInScreen(r)
            dispatchTapGesture(r.centerX().toFloat(), r.centerY().toFloat())
        }
        delay(450)
        val after = captureCurrentScreen()
        val uiChanged = before?.visibleText != after?.visibleText || before?.packageName != after?.packageName
        return AutomationResult(
            success = clicked,
            verified = clicked && uiChanged,
            action = "TAP($query)",
            details = when {
                !clicked -> "Click action rejected by target view."
                uiChanged -> "Tapped '$query' and verified screen hierarchy changed (${after?.packageName})."
                else -> "Tapped '$query'; action dispatched (screen text unchanged)."
            }
        )
    }

    suspend fun longPressByText(query: String): AutomationResult {
        val before = captureCurrentScreen()
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "LONG_PRESS($query)", "No active window root available."
        )
        val target = findNodeMatching(root) { n ->
            n.text?.toString()?.contains(query, ignoreCase = true) == true ||
                n.contentDescription?.toString()?.contains(query, ignoreCase = true) == true
        } ?: return AutomationResult(false, false, "LONG_PRESS($query)", "Element '$query' not found.")

        val ok = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        delay(450)
        val after = captureCurrentScreen()
        val changed = before?.visibleText != after?.visibleText
        return AutomationResult(ok, ok && changed, "LONG_PRESS($query)", if (ok) "Long-press executed." else "Long-press rejected.")
    }

    suspend fun typeIntoFocusedOrEditable(text: String): AutomationResult {
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "TYPE_TEXT", "No active window root available."
        )
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findNodeMatching(root) { it.isEditable }
            ?: return AutomationResult(false, false, "TYPE_TEXT", "No editable text field found on screen.")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(350)
        val after = captureCurrentScreen()
        val verified = after?.visibleText?.any { it.contains(text.take(12), ignoreCase = true) } == true
        return AutomationResult(
            success = setOk,
            verified = setOk && verified,
            action = "TYPE_TEXT",
            details = if (verified) "Text entered and verified in UI tree." else if (setOk) "Set-text returned true." else "Text field rejected input."
        )
    }

    suspend fun scrollScreen(forward: Boolean): AutomationResult {
        val before = captureCurrentScreen()
        val root = rootInActiveWindow ?: return AutomationResult(
            false, false, "SCROLL", "No active window root available."
        )
        val scrollable = findNodeMatching(root) { it.isScrollable }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = scrollable?.performAction(action) ?: swipeDirection(if (forward) "UP" else "DOWN").success
        delay(450)
        val after = captureCurrentScreen()
        val changed = before?.visibleText != after?.visibleText
        return AutomationResult(
            success = ok,
            verified = ok && changed,
            action = if (forward) "SCROLL_FORWARD" else "SCROLL_BACKWARD",
            details = if (changed) "Scrolled and verified new content appeared." else "Scroll dispatched."
        )
    }

    suspend fun swipeDirection(direction: String): AutomationResult {
        val before = captureCurrentScreen()
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (sx, sy, ex, ey) = when (direction.uppercase()) {
            "UP" -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f)
            "DOWN" -> listOf(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.72f)
            "LEFT" -> listOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "RIGHT" -> listOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.7f, w * 0.5f, h * 0.3f)
        }
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        delay(450)
        val after = captureCurrentScreen()
        val changed = before?.visibleText != after?.visibleText
        return AutomationResult(
            success = dispatched,
            verified = dispatched && changed,
            action = "SWIPE_$direction",
            details = if (changed) "Swipe $direction verified with UI change." else "Swipe $direction dispatched."
        )
    }

    suspend fun goBackVerified(): AutomationResult {
        val before = captureCurrentScreen()
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        delay(450)
        val after = captureCurrentScreen()
        val changed = before?.packageName != after?.packageName || before?.visibleText != after?.visibleText
        return AutomationResult(ok, ok && changed, "GLOBAL_BACK", if (changed) "Navigated back and verified change." else "Back action executed.")
    }

    suspend fun goHomeVerified(): AutomationResult {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        delay(450)
        val after = captureCurrentScreen()
        return AutomationResult(ok, ok, "GLOBAL_HOME", "Home screen requested (${after?.packageName ?: "launcher"}). ")
    }

    private fun dispatchTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
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
}
