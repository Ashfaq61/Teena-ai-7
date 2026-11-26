package com.example.system

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.TeenaServiceContainer
import com.example.data.local.AuditLogEntity
import com.example.data.local.TeenaDatabase
import com.example.data.local.TeenaPreferences
import com.example.data.local.TriggerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TeenaTriggerLogic {
    const val TYPE_ONE_TIME = "ONE_TIME"
    const val TYPE_DAILY = "DAILY"
    const val TYPE_INTERVAL = "INTERVAL"
    const val TYPE_BOOT = "BOOT"

    const val ACTION_NOTIFY = "NOTIFY"
    const val ACTION_SPEAK_ALERT = "SPEAK_ALERT"
    const val ACTION_VOICE_PROMPT = "VOICE_PROMPT"
    const val ACTION_AI_CHECK = "AI_CHECK"

    fun validateTriggerInput(
        title: String,
        triggerType: String,
        intervalMinutes: Int
    ): Result<Unit> {
        if (title.isBlank()) {
            return Result.failure(IllegalArgumentException("Trigger title cannot be empty."))
        }
        if (triggerType == TYPE_INTERVAL && intervalMinutes < 1) {
            return Result.failure(IllegalArgumentException("Interval trigger must be at least 1 minute."))
        }
        return Result.success(Unit)
    }

    fun computeInitialTriggerMillis(
        triggerType: String,
        nowMillis: Long,
        delayOrIntervalMinutes: Int
    ): Long {
        val safeMins = delayOrIntervalMinutes.coerceAtLeast(1)
        return when (triggerType) {
            TYPE_BOOT -> nowMillis
            TYPE_DAILY -> nowMillis + safeMins * 60_000L
            TYPE_INTERVAL -> nowMillis + safeMins * 60_000L
            else -> nowMillis + safeMins * 60_000L
        }
    }

    fun computeNextTriggerAfterFire(
        trigger: TriggerEntity,
        nowMillis: Long
    ): Long? {
        return when (trigger.triggerType) {
            TYPE_DAILY -> {
                var next = trigger.triggerAtMillis + 24L * 60L * 60_000L
                while (next <= nowMillis) {
                    next += 24L * 60L * 60_000L
                }
                next
            }
            TYPE_INTERVAL -> {
                val intervalMs = trigger.intervalMinutes.coerceAtLeast(1) * 60_000L
                var next = trigger.triggerAtMillis + intervalMs
                while (next <= nowMillis) {
                    next += intervalMs
                }
                next
            }
            else -> null
        }
    }
}

object TeenaTriggerScheduler {
    private const val ACTION_TRIGGER_FIRE = "com.example.teena.ACTION_TRIGGER_FIRE"
    const val EXTRA_TRIGGER_ID = "extra_trigger_id"
    const val EXTRA_TRIGGER_TITLE = "extra_trigger_title"
    const val EXTRA_TRIGGER_PAYLOAD = "extra_trigger_payload"
    const val EXTRA_TRIGGER_ACTION = "extra_trigger_action"

    fun scheduleTrigger(context: Context, trigger: TriggerEntity): Boolean {
        if (!trigger.enabled || trigger.triggerType == TeenaTriggerLogic.TYPE_BOOT) {
            return false
        }
        return runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            val pending = buildPendingIntent(context, trigger)
            val fireAt = trigger.triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 2_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, fireAt, pending)
            }
            true
        }.getOrDefault(false)
    }

    fun cancelTrigger(context: Context, triggerId: Long) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, TeenaTriggerAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FIRE
            }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCodeFor(triggerId),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                am.cancel(pending)
                pending.cancel()
            }
        }
    }

    fun rescheduleAll(
        context: Context,
        triggers: List<TriggerEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        var scheduledCount = 0
        triggers.filter { it.enabled }.forEach { trig ->
            if (trig.triggerType == TeenaTriggerLogic.TYPE_BOOT) return@forEach
            val effectiveAt = if (trig.triggerAtMillis > nowMillis) {
                trig.triggerAtMillis
            } else {
                TeenaTriggerLogic.computeNextTriggerAfterFire(trig, nowMillis) ?: (nowMillis + 5_000L)
            }
            if (scheduleTrigger(context, trig.copy(triggerAtMillis = effectiveAt))) {
                scheduledCount++
            }
        }
        return scheduledCount
    }

    private fun requestCodeFor(triggerId: Long): Int = (70_000 + (triggerId % 20_000)).toInt()

    private fun buildPendingIntent(context: Context, trigger: TriggerEntity): PendingIntent {
        val intent = Intent(context, TeenaTriggerAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_FIRE
            putExtra(EXTRA_TRIGGER_ID, trigger.id)
            putExtra(EXTRA_TRIGGER_TITLE, trigger.title)
            putExtra(EXTRA_TRIGGER_PAYLOAD, trigger.actionPayload)
            putExtra(EXTRA_TRIGGER_ACTION, trigger.actionType)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(trigger.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class TeenaTriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pendingResult = goAsync()
        val triggerId = intent.getLongExtra(TeenaTriggerScheduler.EXTRA_TRIGGER_ID, -1L)
        val fallbackTitle = intent.getStringExtra(TeenaTriggerScheduler.EXTRA_TRIGGER_TITLE).orEmpty()
            .ifBlank { "Teena Scheduled Trigger" }
        val fallbackPayload = intent.getStringExtra(TeenaTriggerScheduler.EXTRA_TRIGGER_PAYLOAD).orEmpty()
        val fallbackAction = intent.getStringExtra(TeenaTriggerScheduler.EXTRA_TRIGGER_ACTION)
            ?: TeenaTriggerLogic.ACTION_NOTIFY

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appCtx = context.applicationContext
                val container = runCatching { TeenaServiceContainer.getShared(appCtx) }.getOrNull()
                if (container?.emergencyStopController?.isEmergencyStopped?.value == true) {
                    TeenaNotificationHelper.showAlertNotification(
                        appCtx,
                        (triggerId.toInt().takeIf { it > 0 } ?: 7200),
                        "Trigger Halted (Emergency Stop)",
                        fallbackTitle
                    )
                    return@launch
                }

                val db = TeenaDatabase.getInstance(appCtx)
                val entity = if (triggerId > 0) db.triggerDao().getTriggerById(triggerId) else null
                if (entity != null && !entity.enabled) {
                    return@launch
                }

                val title = entity?.title ?: fallbackTitle
                val payload = (entity?.actionPayload ?: fallbackPayload).ifBlank { "Trigger executed cleanly." }
                val actionType = entity?.actionType ?: fallbackAction
                val notifId = 7100 + ((triggerId.coerceAtLeast(1L) % 900L).toInt())

                TeenaNotificationHelper.showAlertNotification(
                    appCtx,
                    notifId,
                    "Teena Trigger: $title",
                    "[$actionType] $payload"
                )

                val now = System.currentTimeMillis()
                if (entity != null) {
                    val nextMillis = TeenaTriggerLogic.computeNextTriggerAfterFire(entity, now)
                    if (nextMillis != null) {
                        val updated = entity.copy(
                            triggerAtMillis = nextMillis,
                            lastFiredAtMillis = now,
                            lastResult = "Fired & rescheduled"
                        )
                        db.triggerDao().upsertTrigger(updated)
                        TeenaTriggerScheduler.scheduleTrigger(appCtx, updated)
                    } else {
                        db.triggerDao().upsertTrigger(
                            entity.copy(
                                enabled = false,
                                lastFiredAtMillis = now,
                                lastResult = "Fired (one-time complete)"
                            )
                        )
                    }
                }

                db.systemRecordsDao().insertAuditLog(
                    AuditLogEntity(
                        category = "TRIGGER",
                        action = "Fired trigger '$title'",
                        outcome = "EXECUTED",
                        details = "Action=$actionType | ${payload.take(120)}"
                    )
                )
            } catch (_: Throwable) {
                // Keep BroadcastReceiver completely crash-proof
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }
}

class TeenaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appCtx = context.applicationContext
                val prefs = runCatching { TeenaPreferences(appCtx).getSettingsSnapshot() }.getOrNull()
                val db = runCatching { TeenaDatabase.getInstance(appCtx) }.getOrNull()

                if (db != null) {
                    val enabledTriggers = runCatching { db.triggerDao().getEnabledTriggersOnce() }.getOrDefault(emptyList())
                    val rescheduledCount = TeenaTriggerScheduler.rescheduleAll(appCtx, enabledTriggers)

                    val bootTriggers = enabledTriggers.filter { it.triggerType == TeenaTriggerLogic.TYPE_BOOT }
                    bootTriggers.forEachIndexed { index, bt ->
                        TeenaNotificationHelper.showAlertNotification(
                            appCtx,
                            7900 + index,
                            "Teena Boot Trigger: ${bt.title}",
                            bt.actionPayload.ifBlank { "Device booted — Teena boot trigger active." }
                        )
                        db.triggerDao().upsertTrigger(
                            bt.copy(
                                lastFiredAtMillis = System.currentTimeMillis(),
                                lastResult = "Executed on boot ($action)"
                            )
                        )
                    }

                    db.systemRecordsDao().insertAuditLog(
                        AuditLogEntity(
                            category = "BOOT_TRIGGER",
                            action = "Boot event ($action)",
                            outcome = "RESCHEDULED",
                            details = "Rescheduled $rescheduledCount alarm trigger(s); ran ${bootTriggers.size} boot trigger(s)."
                        )
                    )
                }

                if (prefs?.triggerMonitorEnabled == true) {
                    TeenaTriggerMonitorService.start(appCtx)
                }
                if (prefs?.backgroundServiceEnabled == true) {
                    TeenaForegroundService.start(appCtx)
                }
            } catch (_: Throwable) {
                // Safe guard against Direct Boot locked storage or OS restrictions
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }
}

class TeenaTriggerMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val MONITOR_NOTIF_ID = 4301
        private const val CHANNEL_MONITOR = "teena_trigger_monitor_channel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _statusSummary = MutableStateFlow("Monitor stopped")
        val statusSummary: StateFlow<String> = _statusSummary.asStateFlow()

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, TeenaTriggerMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            _isRunning.value = false
            _statusSummary.value = "Monitor stopped"
            runCatching {
                context.stopService(Intent(context, TeenaTriggerMonitorService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureMonitorChannel()
        val openPending = PendingIntent.getActivity(
            this,
            4302,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Teena Trigger Monitor")
            .setContentText("Watching scheduled alarms & boot triggers.")
            .setContentIntent(openPending)
            .setOngoing(true)
            .build()

        val fgStarted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    MONITOR_NOTIF_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(MONITOR_NOTIF_ID, notif)
            }
            true
        }.getOrDefault(false)

        if (!fgStarted) {
            _isRunning.value = false
            _statusSummary.value = "Unable to start foreground monitor on this OS state"
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        _statusSummary.value = "Monitoring active triggers"

        serviceScope.launch {
            runCatching {
                TeenaDatabase.getInstance(applicationContext).triggerDao().observeTriggers().collect { list ->
                    val enabled = list.filter { it.enabled }
                    val rescheduled = TeenaTriggerScheduler.rescheduleAll(applicationContext, enabled)
                    _statusSummary.value = "Monitoring ${enabled.size} enabled trigger(s) ($rescheduled alarm synced)"
                }
            }
        }
        return START_STICKY
    }

    private fun ensureMonitorChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_MONITOR,
                        getString(R.string.trigger_monitor_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        _statusSummary.value = "Monitor stopped"
        serviceScope.cancel()
        super.onDestroy()
    }
}
