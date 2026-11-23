package com.example.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object TeenaNotificationHelper {
    const val CHANNEL_FOREGROUND = "teena_fg_channel"
    const val CHANNEL_ALERTS = "teena_alerts_channel"
    const val CHANNEL_BUILDS = "teena_builds_channel"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FOREGROUND,
                    context.getString(R.string.foreground_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.alerts_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BUILDS,
                    context.getString(R.string.builds_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    fun showAlertNotification(
        context: Context,
        id: Int,
        title: String,
        body: String,
        channelId: String = CHANNEL_ALERTS
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)
        val openIntent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notif)
    }
}

class TeenaForegroundService : Service() {
    companion object {
        private const val FG_NOTIF_ID = 4101

        fun start(context: Context) {
            val intent = Intent(context, TeenaForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TeenaForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TeenaNotificationHelper.ensureChannels(this)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, TeenaNotificationHelper.CHANNEL_FOREGROUND)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Teena AI Neural Engine Active")
            .setContentText("Monitoring scheduled tasks, agent health, and voice readiness.")
            .setContentIntent(openPending)
            .setOngoing(true)
            .build()
        startForeground(FG_NOTIF_ID, notif)
        return START_STICKY
    }
}

class TeenaReminderReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_TITLE = "extra_reminder_title"
        const val EXTRA_NOTES = "extra_reminder_notes"
        const val EXTRA_ID = "extra_reminder_id"

        fun scheduleReminder(context: Context, id: Int, title: String, notes: String, triggerAtMillis: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, TeenaReminderReceiver::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_NOTES, notes)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, (System.currentTimeMillis() % 10000).toInt())
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Teena Reminder"
        val notes = intent.getStringExtra(EXTRA_NOTES).orEmpty().ifBlank { "Scheduled reminder triggered." }
        TeenaNotificationHelper.showAlertNotification(context, id, title, notes)
    }
}

data class PermissionSnapshot(
    val recordAudio: Boolean,
    val camera: Boolean,
    val fineLocation: Boolean,
    val readContacts: Boolean,
    val callPhone: Boolean,
    val readCallLog: Boolean,
    val answerCalls: Boolean,
    val postNotifications: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
    val writeSettingsAllowed: Boolean,
    val isDefaultDialer: Boolean
)

class PermissionStatusManager(private val context: Context) {

    fun captureStatus(): PermissionSnapshot {
        fun granted(perm: String): Boolean =
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

        val notifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else true

        val answerPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            granted(Manifest.permission.ANSWER_PHONE_CALLS)
        } else true

        return PermissionSnapshot(
            recordAudio = granted(Manifest.permission.RECORD_AUDIO),
            camera = granted(Manifest.permission.CAMERA),
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            readContacts = granted(Manifest.permission.READ_CONTACTS),
            callPhone = granted(Manifest.permission.CALL_PHONE),
            readCallLog = granted(Manifest.permission.READ_CALL_LOG),
            answerCalls = answerPerm,
            postNotifications = notifPerm,
            accessibilityEnabled = TeenaAccessibilityService.isAccessibilityEnabledInSettings(context),
            notificationListenerEnabled = TeenaNotificationListenerService.isEnabledInSettings(context),
            writeSettingsAllowed = android.provider.Settings.System.canWrite(context),
            isDefaultDialer = PhoneCallEngine(context).isDefaultDialer()
        )
    }
}
