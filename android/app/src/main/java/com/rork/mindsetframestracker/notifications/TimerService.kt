package com.rork.mindsetframestracker.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rork.mindsetframestracker.MainActivity
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.data.TimerRepository
import com.rork.mindsetframestracker.data.TimerStatus
import com.rork.mindsetframestracker.data.formatTimerDuration

/**
 * Keeps a *running* timer honest while the user is elsewhere: shows a live
 * \"timer running\" notification with the elapsed time, and rings the completion
 * alert at the exact moment the target is reached.
 *
 * ## Why a service at all, when the alarm already fires?
 *
 * The AlarmManager alarm is what guarantees the **precise** wake-up (it is the
 * only Doze-exempt primitive). The service is what makes the timer *visible*
 * and *self-healing* meanwhile:
 *
 *  - the ongoing notification gives the user a way back into the timer from
 *    outside the app, and shows remaining time at a glance;
 *  - if the alarm is dropped anyway (an OEM task-killer force-stopping the app
 *    is the classic case \u2014 it cancels alarms *without* sending
 *    `BOOT_COMPLETED`), the service's own in-process check still fires the
 *    completion. Either path may win; [TimerRepository.recordCompletion]
 *    makes that race harmless, because only the first winner alerts.
 *
 * ## Lifecycle
 *
 * Started with `startForegroundService` whenever a timer resumes, stopped with
 * [stop] when it pauses or is cleared. Failure to start (Android 12+ background
 * start limits, or an OEM that blocks foreground services) is **not** fatal:
 * every call site runs it through `runCatching`, and the alarm remains the
 * primary guarantee, so the feature degrades to \"alarm-only\" rather than
 * breaking.
 */
class TimerService : Service() {

    private val repo by lazy { TimerRepository(applicationContext) }
    private var tickThread: Thread? = null
    @Volatile private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val timer = repo.loadActive()
        if (timer == null || timer.status != TimerStatus.RUNNING) {
            // Nothing to keep alive (paused or cleared while we were starting).
            stopSelfSafely()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID_ONGOING, buildOngoingNotification())
        if (tickThread == null) {
            running = true
            tickThread = Thread { tickLoop() }.apply {
                name = "MindsetTimerTick"
                isDaemon = true
                start()
            }
        }
        // Deliberately NOT sticky: a restart with no state is pointless, and
        // the AlarmManager alarm covers the "process was killed" case.
        return START_NOT_STICKY
    }

    /**
     * Once-a-second loop that refreshes the ongoing notification and fires the
     * completion as soon as the deadline passes. Reading elapsed time from the
     * wall clock (rather than counting ticks) means a delayed loop iteration
     * can never make the timer *slow* \u2014 only slightly late to notice.
     */
    private fun tickLoop() {
        while (running) {
            val timer = repo.loadActive()
            if (timer == null || timer.status != TimerStatus.RUNNING) {
                stopSelfSafely()
                return
            }

            val now = System.currentTimeMillis()
            if (timer.isExpiredAt(now)) {
                TimerCompletion.fire(applicationContext, timer)
                stopSelfSafely()
                return
            }

            runCatching {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID_ONGOING, buildOngoingNotification())
            }

            // Poll twice a second near the deadline so the alert is not late by
            // up to a second; once a second otherwise (notification only needs
            // second-level granularity).
            val msLeft = (timer.remainingSecondsAt(now) * 1000L) - (now - now / 1000L * 1000L)
            val sleepMs = if (timer.hasTarget && msLeft in 1..2_000) 150L else 1_000L
            runCatching { Thread.sleep(sleepMs) }
        }
    }

    private fun buildOngoingNotification(): Notification {
        val timer = repo.loadActive()
        val now = System.currentTimeMillis()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_TIMER)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_ONGOING,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title: String
        val text: String
        if (timer == null) {
            title = "Timer"
            text = ""
        } else if (timer.hasTarget) {
            title = if (timer.kind == TimerKind.WALK_TIMER) "Walk timer" else "Stopwatch goal"
            val left = timer.remainingSecondsAt(now)
            text = if (left > 0) {
                "${formatTimerDuration(left)} left"
            } else {
                "Finished"
            }
        } else {
            title = "Stopwatch"
            text = formatTimerDuration(timer.elapsedAt(now))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_ONGOING)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setProgress(
                /* max = */ if (timer != null && timer.hasTarget) 100 else 0,
                /* progress = */ if (timer != null && timer.hasTarget) {
                    ((timer.progressAt(now) ?: 0f) * 100f).toInt()
                } else 0,
                /* indeterminate = */ timer == null || !timer.hasTarget,
            )
            .addAction(
                0,
                "Stop",
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID_ONGOING + 1,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_TIMER)
                        putExtra(MainActivity.EXTRA_STOP_TIMER, true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ONGOING,
                    "Running timer",
                    NotificationManager.IMPORTANCE_LOW, // silent, no heads-up
                ).apply {
                    description = "Shows the time remaining on a walk timer or stopwatch"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun stopSelfSafely() {
        running = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }.onFailure { Log.w(TAG, "stopForeground failed", it) }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        running = false
        tickThread = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TimerService"
        const val ACTION_START = "com.rork.mindsetframestracker.action.TIMER_START"
        const val ACTION_STOP = "com.rork.mindsetframestracker.action.TIMER_STOP"
        private const val CHANNEL_ID_ONGOING = "timer_running"
        private const val NOTIFICATION_ID_ONGOING = 4200

        /**
         * Best-effort start. Safe to call from any process, from a receiver, or
         * from Compose; never throws into the caller.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, TimerService::class.java).apply { action = ACTION_START }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "Could not start timer service", it) }
        }

        /** Best-effort stop; also removes the ongoing notification. */
        fun stop(context: Context) {
            runCatching {
                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID_ONGOING)
                context.startService(
                    Intent(context, TimerService::class.java).apply { action = ACTION_STOP },
                )
            }.onFailure { Log.w(TAG, "Could not stop timer service", it) }
        }
    }
}
