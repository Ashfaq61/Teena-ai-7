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

    private const val SCREEN_WATCH_NOTIF_ID = 4199

    fun showScreenWatchingIndicator(context: Context, goal: String, stepNumber: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            ensureChannels(context)
            val openIntent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                SCREEN_WATCH_NOTIF_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val bodyText = if (stepNumber > 0) {
                "Step $stepNumber • ${goal.take(80)}"
            } else {
                goal.take(100).ifBlank { context.getString(R.string.screen_watching_indicator_body) }
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.screen_watching_indicator_title))
                .setContentText(bodyText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(SCREEN_WATCH_NOTIF_ID, notif)
        }
    }

    fun clearScreenWatchingIndicator(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(SCREEN_WATCH_NOTIF_ID)
        }
    }
}

class TeenaForegroundService : Service() {
    companion object {
        private const val FG_NOTIF_ID = 4101
        const val ACTION_START_MONITOR = "com.example.teena.ACTION_START_MONITOR"
        const val ACTION_START_VOICE = "com.example.teena.ACTION_START_VOICE"
        const val ACTION_STOP_VOICE = "com.example.teena.ACTION_STOP_VOICE"

        @Volatile
        var onVoiceStopRequested: (() -> Unit)? = null

        @Volatile
        var isVoiceForegroundActive: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TeenaForegroundService::class.java).apply {
                action = ACTION_START_MONITOR
            }
            safeStartForeground(context, intent)
        }

        fun startVoiceMode(context: Context) {
            val intent = Intent(context, TeenaForegroundService::class.java).apply {
                action = ACTION_START_VOICE
            }
            safeStartForeground(context, intent)
        }

        fun stopVoiceMode(context: Context, keepMonitorIfEnabled: Boolean) {
            isVoiceForegroundActive = false
            if (keepMonitorIfEnabled) {
                start(context)
            } else {
                runCatching { context.stopService(Intent(context, TeenaForegroundService::class.java)) }
            }
        }

        fun stop(context: Context) {
            isVoiceForegroundActive = false
            runCatching { context.stopService(Intent(context, TeenaForegroundService::class.java)) }
        }

        private fun safeStartForeground(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VOICE) {
            isVoiceForegroundActive = false
            onVoiceStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }

        val voiceMode = intent?.action == ACTION_START_VOICE
        isVoiceForegroundActive = voiceMode
        TeenaNotificationHelper.ensureChannels(this)

        val openPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, TeenaNotificationHelper.CHANNEL_FOREGROUND)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPending)
            .setOngoing(true)

        if (voiceMode) {
            val stopVoiceIntent = Intent(this, TeenaForegroundService::class.java).apply {
                action = ACTION_STOP_VOICE
            }
            val stopPending = PendingIntent.getService(
                this,
                4102,
                stopVoiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder
                .setContentTitle("Teena Live Voice Active")
                .setContentText("Microphone listening • Tap Stop Voice to disconnect cleanly.")
                .addAction(0, "Stop Voice", stopPending)
        } else {
            builder
                .setContentTitle("Teena AI Neural Engine Active")
                .setContentText("Monitoring scheduled tasks, agent health, and voice readiness.")
        }

        val notif = builder.build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val fgType = if (voiceMode) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(FG_NOTIF_ID, notif, fgType)
            } else {
                startForeground(FG_NOTIF_ID, notif)
            }
        }.onFailure {
            stopSelf()
        }
        return if (voiceMode) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        isVoiceForegroundActive = false
        super.onDestroy()
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
    val isDefaultDialer: Boolean,
    val teenaKeyboardEnabled: Boolean = false,
    val teenaKeyboardSelected: Boolean = false,
    val deviceAdminActive: Boolean = false
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
            writeSettingsAllowed = runCatching { android.provider.Settings.System.canWrite(context) }.getOrDefault(false),
            isDefaultDialer = PhoneCallEngine(context).isDefaultDialer(),
            teenaKeyboardEnabled = TeenaKeyboardService.isKeyboardEnabled(context),
            teenaKeyboardSelected = TeenaKeyboardService.isKeyboardSelected(context),
            deviceAdminActive = TeenaDeviceAdminReceiver.isAdminActive(context)
        )
    }
}

