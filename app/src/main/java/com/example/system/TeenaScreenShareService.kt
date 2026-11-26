package com.example.system

import android.app.Notification
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
 * Dedicated MediaProjection foreground service required on Android 10+ / 14+
 * while sharing the device screen inside a LiveKit WebRTC room.
 * Starts ONLY after the user approves the MediaProjection system consent prompt.
 */
class TeenaScreenShareService : Service() {

    companion object {
        const val SCREEN_SHARE_NOTIF_ID = 4301
        const val ACTION_START_SCREEN_SHARE = "com.example.teena.ACTION_START_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "com.example.teena.ACTION_STOP_SCREEN_SHARE"

        @Volatile
        var isActive: Boolean = false
            private set

        @Volatile
        var onStopRequested: (() -> Unit)? = null

        fun buildOngoingNotification(context: Context): Notification {
            TeenaNotificationHelper.ensureChannels(context)
            val openPending = PendingIntent.getActivity(
                context,
                SCREEN_SHARE_NOTIF_ID,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(context, TeenaScreenShareService::class.java).apply {
                action = ACTION_STOP_SCREEN_SHARE
            }
            val stopPending = PendingIntent.getService(
                context,
                SCREEN_SHARE_NOTIF_ID + 1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, TeenaNotificationHelper.CHANNEL_FOREGROUND)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.screen_share_notif_title))
                .setContentText(context.getString(R.string.screen_share_notif_body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(0, "Stop Share", stopPending)
                .build()
        }

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, TeenaScreenShareService::class.java).apply {
                    action = ACTION_START_SCREEN_SHARE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            isActive = false
            runCatching {
                context.stopService(Intent(context, TeenaScreenShareService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SCREEN_SHARE) {
            isActive = false
            runCatching { onStopRequested?.invoke() }
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = buildOngoingNotification(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SCREEN_SHARE_NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(SCREEN_SHARE_NOTIF_ID, notif)
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
