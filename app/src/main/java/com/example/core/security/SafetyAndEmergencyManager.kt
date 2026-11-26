package com.example.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global safety & Emergency Stop coordinator across automation, agents, voice, and external calls.
 * When Emergency Stop is active:
 * - All OkHttp external calls are aborted cleanly.
 * - Gemini Live WebSocket + audio capture/playback are terminated immediately.
 * - All Accessibility automation gestures refuse to execute.
 * - Background WorkManager research / self-improvement tasks halt immediately.
 * - External AI Collaboration / Advisor calls are refused.
 */
class EmergencyStopController {

    private val haltedFlag = AtomicBoolean(false)
    private val _isEmergencyStopped = MutableStateFlow(false)
    val isEmergencyStopped: StateFlow<Boolean> = _isEmergencyStopped.asStateFlow()

    private val externalAiAllowedFlag = AtomicBoolean(true)
    private val _externalAiCallsEnabled = MutableStateFlow(true)
    val externalAiCallsEnabled: StateFlow<Boolean> = _externalAiCallsEnabled.asStateFlow()

    private val autoModeEnabledFlag = AtomicBoolean(false)
    private val _autoModeEnabled = MutableStateFlow(false)
    val autoModeEnabled: StateFlow<Boolean> = _autoModeEnabled.asStateFlow()

    private val stopListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addStopListener(listener: () -> Unit) {
        stopListeners.add(listener)
    }

    fun setExternalAiCallsEnabled(enabled: Boolean) {
        externalAiAllowedFlag.set(enabled)
        _externalAiCallsEnabled.value = enabled
    }

    fun setAutoModeEnabled(enabled: Boolean) {
        autoModeEnabledFlag.set(enabled)
        _autoModeEnabled.value = enabled
    }

    fun isStopped(): Boolean = haltedFlag.get()

    fun isExternalAiAllowed(): Boolean = !haltedFlag.get() && externalAiAllowedFlag.get()

    fun isAutoModeAllowed(): Boolean = !haltedFlag.get() && autoModeEnabledFlag.get()

    fun triggerEmergencyStop() {
        haltedFlag.set(true)
        _isEmergencyStopped.value = true
        stopListeners.forEach { listener ->
            runCatching { listener() }
        }
    }

    fun resumeOperations() {
        haltedFlag.set(false)
        _isEmergencyStopped.value = false
    }

    fun assertAutomationAllowed(actionName: String = "automation") {
        if (haltedFlag.get()) {
            throw SecurityException("Emergency Stop active: blocked $actionName.")
        }
    }

    fun assertExternalNetworkAllowed(operation: String = "network call") {
        if (haltedFlag.get()) {
            throw SecurityException("Emergency Stop active: aborted $operation.")
        }
    }

    fun assertExternalAiAdvisorAllowed(operation: String = "external AI advisor call") {
        if (haltedFlag.get()) {
            throw SecurityException("Emergency Stop active: aborted $operation.")
        }
        if (!externalAiAllowedFlag.get()) {
            throw SecurityException("External AI calls are disabled in user settings.")
        }
    }
}
