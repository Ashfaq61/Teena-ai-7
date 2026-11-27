package com.example.system

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * Foreground service keeping Teena Dev Studio downloads, rootfs extraction,
 * local IDE bridge, and background child tasks alive with an ongoing notification
 * and an immediate Kill Tasks action.
 */
class TeenaDevStudioService : Service() {

    companion object {
        const val DEV_STUDIO_NOTIF_ID = 4351
        const val ACTION_START_DEV_STUDIO = "com.example.teena.ACTION_START_DEV_STUDIO"
        const val ACTION_KILL_DEV_STUDIO = "com.example.teena.ACTION_KILL_DEV_STUDIO"
        const val EXTRA_STATUS_TEXT = "extra_dev_studio_status"

        @Volatile
        var isActive: Boolean = false
            private set

        @Volatile
        var onKillAllRequested: (() -> Unit)? = null

        fun buildOngoingNotification(
            context: Context,
            statusText: String? = null
        ): Notification {
            TeenaNotificationHelper.ensureChannels(context)
            val openPending = PendingIntent.getActivity(
                context,
                DEV_STUDIO_NOTIF_ID,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val killIntent = Intent(context, TeenaDevStudioService::class.java).apply {
                action = ACTION_KILL_DEV_STUDIO
            }
            val killPending = PendingIntent.getService(
                context,
                DEV_STUDIO_NOTIF_ID + 1,
                killIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val body = statusText?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.dev_studio_notif_body)

            return NotificationCompat.Builder(context, TeenaNotificationHelper.CHANNEL_FOREGROUND)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.dev_studio_notif_title))
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(0, "Kill Tasks", killPending)
                .build()
        }

        fun start(context: Context, statusText: String? = null) {
            runCatching {
                val intent = Intent(context, TeenaDevStudioService::class.java).apply {
                    action = ACTION_START_DEV_STUDIO
                    if (!statusText.isNullOrBlank()) {
                        putExtra(EXTRA_STATUS_TEXT, statusText)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun updateNotification(context: Context, statusText: String) {
            if (!isActive) return
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.notify(DEV_STUDIO_NOTIF_ID, buildOngoingNotification(context, statusText))
            }
        }

        fun stop(context: Context) {
            isActive = false
            runCatching {
                context.stopService(Intent(context, TeenaDevStudioService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_KILL_DEV_STUDIO) {
            isActive = false
            runCatching { onKillAllRequested?.invoke() }
            stopSelf()
            return START_NOT_STICKY
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
        val notif = buildOngoingNotification(this, statusText)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    DEV_STUDIO_NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(DEV_STUDIO_NOTIF_ID, notif)
            }
            isActive = true
        }.onFailure {
            isActive = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isActive = false
        super.onDestroy()
    }
}
