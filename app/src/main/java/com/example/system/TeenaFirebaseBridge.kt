package com.example.system

import android.content.Context
import com.example.core.security.SecurityAuditor
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class FirebaseRoomAuthState(
    val googleServicesPresent: Boolean = false,
    val signedIn: Boolean = false,
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val fcmTokenPreview: String = "",
    val hasFcmToken: Boolean = false,
    val lastPushTitle: String = "",
    val lastPushBody: String = "",
    val statusLabel: String = "DISABLED", // DISABLED | READY | ACTIVE | ERROR
    val statusMessage: String = "Optional Firebase Auth & FCM disabled (add app/google-services.json to enable)."
)

/**
 * Optional Firebase Auth + FCM bridge conditional strictly on `google-services.json`.
 * Never uses anonymous authentication and never crashes when `google-services.json` is absent.
 */
class TeenaFirebaseBridge(private val context: Context) {

    private val _state = MutableStateFlow(evaluateInitialState())
    val state: StateFlow<FirebaseRoomAuthState> = _state.asStateFlow()

    private fun evaluateInitialState(): FirebaseRoomAuthState {
        val configured = isGoogleServicesConfigured(context)
        if (!configured) {
            return FirebaseRoomAuthState(
                googleServicesPresent = false,
                signedIn = false,
                statusLabel = "DISABLED",
                statusMessage = "Optional Firebase Auth & FCM disabled. Supply app/google-services.json and rebuild to enable."
            )
        }
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        return if (user != null) {
            FirebaseRoomAuthState(
                googleServicesPresent = true,
                signedIn = true,
                uid = user.uid,
                email = user.email.orEmpty(),
                displayName = user.displayName.orEmpty().ifBlank { user.email.orEmpty().substringBefore("@") },
                statusLabel = "ACTIVE",
                statusMessage = "Signed in as ${user.email ?: user.uid}"
            )
        } else {
            FirebaseRoomAuthState(
                googleServicesPresent = true,
                signedIn = false,
                statusLabel = "READY",
                statusMessage = "Firebase configured. Sign in with email/password for authenticated room tokens & FCM alerts."
            )
        }
    }

    fun isConfigured(): Boolean = isGoogleServicesConfigured(context)

    fun refreshStatus() {
        val prev = _state.value
        val next = evaluateInitialState()
        _state.value = next.copy(
            fcmTokenPreview = if (next.googleServicesPresent) prev.fcmTokenPreview else "",
            hasFcmToken = if (next.googleServicesPresent) prev.hasFcmToken else false,
            lastPushTitle = prev.lastPushTitle,
            lastPushBody = prev.lastPushBody
        )
    }

    suspend fun signInWithEmailPassword(email: String, password: String): Result<FirebaseRoomAuthState> {
        if (!isConfigured()) {
            val disabled = evaluateInitialState()
            _state.value = disabled
            return Result.failure(IllegalStateException(disabled.statusMessage))
        }
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || password.length < 6) {
            return Result.failure(IllegalArgumentException("Enter a valid email and a password of at least 6 characters."))
        }
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseAuth.getInstance()
                    .signInWithEmailAndPassword(cleanEmail, password)
                    .addOnSuccessListener {
                        refreshStatus()
                        if (cont.isActive) cont.resume(Result.success(_state.value))
                    }
                    .addOnFailureListener { err ->
                        val safe = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Firebase sign-in failed")
                        _state.value = _state.value.copy(statusLabel = "ERROR", statusMessage = safe)
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException(safe)))
                    }
            }.onFailure { err ->
                val safe = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Firebase Auth unavailable")
                _state.value = _state.value.copy(statusLabel = "ERROR", statusMessage = safe)
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException(safe)))
            }
        }
    }

    suspend fun createAccountWithEmailPassword(email: String, password: String): Result<FirebaseRoomAuthState> {
        if (!isConfigured()) {
            val disabled = evaluateInitialState()
            _state.value = disabled
            return Result.failure(IllegalStateException(disabled.statusMessage))
        }
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || password.length < 6) {
            return Result.failure(IllegalArgumentException("Enter a valid email and a password of at least 6 characters."))
        }
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseAuth.getInstance()
                    .createUserWithEmailAndPassword(cleanEmail, password)
                    .addOnSuccessListener {
                        refreshStatus()
                        if (cont.isActive) cont.resume(Result.success(_state.value))
                    }
                    .addOnFailureListener { err ->
                        val safe = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Account creation failed")
                        _state.value = _state.value.copy(statusLabel = "ERROR", statusMessage = safe)
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException(safe)))
                    }
            }.onFailure { err ->
                val safe = SecurityAuditor.redactSecrets(err.localizedMessage ?: "Firebase Auth unavailable")
                _state.value = _state.value.copy(statusLabel = "ERROR", statusMessage = safe)
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException(safe)))
            }
        }
    }

    fun signOut() {
        if (isConfigured()) {
            runCatching { FirebaseAuth.getInstance().signOut() }
        }
        refreshStatus()
    }

    suspend fun fetchIdTokenOrNull(): String? {
        if (!isConfigured()) return null
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                user.getIdToken(false)
                    .addOnSuccessListener { res ->
                        if (cont.isActive) cont.resume(res.token)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun refreshFcmToken(): Result<String> {
        if (!isConfigured()) {
            val disabled = evaluateInitialState()
            _state.value = disabled
            return Result.failure(IllegalStateException(disabled.statusMessage))
        }
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        val masked = maskToken(token)
                        _state.value = _state.value.copy(
                            hasFcmToken = true,
                            fcmTokenPreview = masked,
                            statusMessage = "FCM device push token active ($masked)"
                        )
                        if (cont.isActive) cont.resume(Result.success(masked))
                    }
                    .addOnFailureListener { err ->
                        val safe = SecurityAuditor.redactSecrets(err.localizedMessage ?: "FCM token fetch failed")
                        _state.value = _state.value.copy(statusMessage = safe)
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException(safe)))
                    }
            }.onFailure { err ->
                val safe = SecurityAuditor.redactSecrets(err.localizedMessage ?: "FCM unavailable")
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException(safe)))
            }
        }
    }

    fun onFcmTokenUpdated(token: String) {
        if (!isConfigured()) return
        val masked = maskToken(token)
        _state.value = _state.value.copy(
            hasFcmToken = masked.isNotBlank(),
            fcmTokenPreview = masked
        )
    }

    fun onIncomingRoomInvitePush(title: String, body: String) {
        _state.value = _state.value.copy(
            lastPushTitle = SecurityAuditor.redactSecrets(title.take(100)),
            lastPushBody = SecurityAuditor.redactSecrets(body.take(240))
        )
    }

    companion object {
        fun maskToken(token: String?): String {
            val clean = token.orEmpty().trim()
            if (clean.isEmpty()) return ""
            return if (clean.length <= 12) "***(${clean.length})" else "${clean.take(6)}...${clean.takeLast(4)}"
        }

        fun hasGoogleServicesResource(context: Context): Boolean {
            return runCatching {
                val resId = context.resources.getIdentifier("google_app_id", "string", context.packageName)
                resId != 0 && context.getString(resId).isNotBlank()
            }.getOrDefault(false)
        }

        fun isGoogleServicesConfigured(context: Context): Boolean {
            if (!hasGoogleServicesResource(context)) return false
            return runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
        }
    }
}
