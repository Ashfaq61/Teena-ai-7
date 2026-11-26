package com.example.system

import com.example.core.TeenaServiceContainer
import com.example.core.security.SecurityAuditor
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Optional Firebase Cloud Messaging listener for Teena Room invites & backend notifications.
 * Only operates when `app/google-services.json` is configured; safely no-ops otherwise.
 */
class TeenaFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        runCatching {
            if (!TeenaFirebaseBridge.isGoogleServicesConfigured(applicationContext)) return
            TeenaServiceContainer.getShared(applicationContext).firebaseBridge.onFcmTokenUpdated(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        runCatching {
            if (!TeenaFirebaseBridge.isGoogleServicesConfigured(applicationContext)) return
            val title = SecurityAuditor.redactSecrets(
                message.notification?.title
                    ?: message.data["title"]
                    ?: "Teena Room Invite"
            )
            val body = SecurityAuditor.redactSecrets(
                message.notification?.body
                    ?: message.data["body"]
                    ?: message.data["roomName"]?.let { "Join voice/video room: $it" }
                    ?: "New Teena room update received."
            )
            TeenaServiceContainer.getShared(applicationContext)
                .firebaseBridge
                .onIncomingRoomInvitePush(title, body)
            TeenaNotificationHelper.showAlertNotification(
                context = applicationContext,
                id = 4401,
                title = title,
                body = body
            )
        }
    }
}
