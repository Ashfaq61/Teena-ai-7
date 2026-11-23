package com.example.system

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.data.local.NotificationRecordEntity
import com.example.data.local.TeenaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class TeenaNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val replyActions = ConcurrentHashMap<String, Notification.Action>()

        fun isEnabledInSettings(context: Context): Boolean {
            if (_isConnected.value) return true
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val cn = ComponentName(context, TeenaNotificationListenerService::class.java).flattenToString()
            return flat.split(':').any { it.equals(cn, ignoreCase = true) || it.contains(context.packageName) }
        }

        fun openNotificationListenerSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun sendAuthorizedReply(context: Context, notificationKey: String, replyText: String): Result<String> {
            val action = replyActions[notificationKey]
                ?: return Result.failure(IllegalStateException("No active reply action cached for this notification."))
            val remoteInputs = action.remoteInputs
                ?: return Result.failure(IllegalStateException("Notification does not expose RemoteInput."))
            return runCatching {
                val intent = Intent()
                val bundle = Bundle()
                remoteInputs.forEach { ri ->
                    bundle.putCharSequence(ri.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                action.actionIntent.send(context, 0, intent)
                "Reply sent via Android RemoteInput."
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        runCatching { activeNotifications?.forEach { recordSbn(it) } }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) recordSbn(sbn)
    }

    private fun recordSbn(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val replyAction = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (replyAction != null) {
            replyActions[sbn.key] = replyAction
        }

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(sbn.packageName)

        val isImportant = sbn.notification.priority >= Notification.PRIORITY_HIGH ||
            text.contains("urgent", ignoreCase = true) ||
            text.contains("otp", ignoreCase = true) ||
            text.contains("call", ignoreCase = true) ||
            sbn.packageName.contains("whatsapp") ||
            sbn.packageName.contains("telegram")

        serviceScope.launch {
            TeenaDatabase.getInstance(applicationContext)
                .systemRecordsDao()
                .insertNotification(
                    NotificationRecordEntity(
                        notificationKey = sbn.key,
                        packageName = sbn.packageName,
                        appLabel = appLabel,
                        title = title,
                        text = text,
                        isImportant = isImportant,
                        hasReplyAction = replyAction != null,
                        timestamp = sbn.postTime
                    )
                )
        }
    }
}
