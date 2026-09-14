package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rork.mindsetframestracker.MainActivity
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.ActiveTimer
import com.rork.mindsetframestracker.data.TimerCompletionEvent
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.data.TimerRepository
import com.rork.mindsetframestracker.data.formatTimerDuration

/**
 * Notification surface for a finished timer.
 *
 * Two *distinct* notifications, because they answer two different questions:
 *
 * 1. **The completion alert** ([postCompletion]) \u2014 \"your timer
 *    finished\". Uses the ALARM audio stream and an alarm-grade channel so it
 *    cuts through silent mode and Do Not Disturb the same way the habit
 *    reminders do, plus a full-screen intent that opens [AlarmRingingActivity]
 *    so the user gets a ringing screen rather than a notification they can
 *    sleep through.
 * 2. **The one-shot re-reminder** ([maybePostReminder]) \u2014 if the user swipes
 *    the completion alert away *without acting on it*, this fires once (and
 *    only once, guarded by [TimerRepository.markReminderSent]) a few minutes
 *    later. No repeated polling, no notification spam.
 *
 * The notification id is derived from the event id, so re-posting the same
 * event updates the same notification instead of stacking duplicates.
 */
object TimerNotifier {

    private const val TAG = "TimerNotifier"

    /** Alarm-grade channel \u2014 heard through silent mode / DND like a real alarm. */
    const val CHANNEL_ID = "timer_alarm"

    /** Quiet channel for the one-shot \"still waiting on you\" re-reminder. */
    private const val CHANNEL_ID_REMINDER = "timer_alarm_reminder"

    private const val NOTIFICATION_ID_BASE = 4000

    /**
     * Intent extras describing the timer event a notification refers to.
     *
     * These live here, privately, and every reference in this file goes
     * through them — including the full-screen `ringingIntent` below. (That
     * block previously read `AlarmRingingActivity.EXTRA_TIMER_*`, but
     * [AlarmRingingActivity] declares no such constants: it is the *habit*-
     * reminder ringing screen and only ever reads `habitId` / `habitName`. The
     * references were unresolved, so the whole class failed to compile.)
     * Keeping the names on our own `applyEventExtras` / `eventFromExtras` pair
     * means the producer and the consumer of these extras can never drift.
     */
    private const val EXTRA_EVENT_ID = "extra_event_id"
    private const val EXTRA_TIMER_RUN_ID = "extra_timer_run_id"
    private const val EXTRA_TIMER_KIND = "extra_timer_kind"
    private const val EXTRA_TIMER_LABEL = "extra_timer_label"
    private const val EXTRA_TIMER_HABIT_ID = "extra_timer_habit_id"
    private const val EXTRA_TIMER_TARGET = "extra_timer_target"
    private const val EXTRA_TIMER_ELAPSED = "extra_timer_elapsed"

    /** Stable notification id for an event \u2014 re-posting updates, never stacks. */
    fun notificationId(eventId: String): Int = NOTIFICATION_ID_BASE + (eventId.hashCode() and 0x3FFF)

    /** True when we hold POST_NOTIFICATIONS (or the OS predates the permission). */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Posts the completion alert for [event]. The caller is responsible for
     * having already won the one-time gate ([TimerRepository.recordCompletion]),
     * so this only deals with presentation.
     */
    fun postCompletion(context: Context, event: TimerCompletionEvent) {
        if (!canPost(context)) {
            Log.w(TAG, "Completion alert suppressed \u2014 notifications unavailable for ${event.eventId}")
            return
        }
        runCatching {
            ensureChannels(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val ringingIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                putExtra(EXTRA_EVENT_ID, event.eventId)
                putExtra(EXTRA_TIMER_RUN_ID, event.runId)
                putExtra(EXTRA_TIMER_KIND, event.kind.name)
                putExtra(EXTRA_TIMER_LABEL, event.label)
                putExtra(EXTRA_TIMER_HABIT_ID, event.habitId)
                putExtra(EXTRA_TIMER_TARGET, event.targetSeconds)
                putExtra(EXTRA_TIMER_ELAPSED, event.elapsedSeconds)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val ringingPendingIntent = PendingIntent.getActivity(
                context,
                notificationId(event.eventId),
                ringingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_EVENT_ID, event.eventId)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                notificationId(event.eventId) + 1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val canUseFullScreenIntent =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false)

            val title = when (event.kind) {
                TimerKind.TIMER -> "Timer complete"
                TimerKind.STOPWATCH -> "Target reached"
            }
            val body = buildString {
                append(
                    if (event.kind == TimerKind.TIMER) {
                        "${formatTimerDuration(event.targetSeconds)} timer finished"
                    } else {
                        "Stopwatch passed ${formatTimerDuration(event.targetSeconds)}"
                    },
                )
                if (event.label.isNotBlank()) append(" \u2014 ${event.label}")
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                // Flat alpha-only vector — see AlarmRingService for why
                // `splash_icon` (a layer-list wrapping an adaptive icon) must
                // not be used in the small-icon slot.
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(longArrayOf(0, 400, 200, 400))
            // The manual "Stop alarm" action. Same guarded-broadcast rationale as
            // HabitCheckInNotifier: deliverable from the shade, and unable to
            // throw into the notification path that runs at ring time.
            AlarmStopReceiver.stopPendingIntent(context, notificationId(event.eventId) + 4)
                ?.let { stopIntent -> builder.addAction(0, "Stop alarm", stopIntent) }
            if (canUseFullScreenIntent) {
                builder.setFullScreenIntent(ringingPendingIntent, true)
            }
            manager.notify(notificationId(event.eventId), builder.build())
            // Start the audible ring HERE, on the notification path — not from
            // AlarmRingingActivity. The screen is only launched full-screen when
            // USE_FULL_SCREEN_INTENT is granted (API 34+ gates it behind its own
            // special-access setting), so tying the sound to that launch meant a
            // missing grant produced exactly the reported symptom: the
            // notification appears, and nothing ever rings.
            AlarmRingService.start(context, habitId = event.habitId, eventId = event.eventId)
            Log.i(TAG, "Posted completion alert for ${event.eventId}")
        }.onFailure { Log.e(TAG, "Failed to post completion alert for ${event.eventId}", it) }
    }

    /**
     * Posts the *one-shot* re-reminder for a still-unhandled event. Returns
     * true when it actually posted. Uses [TimerRepository.markReminderSent] so
     * the receiver can be re-delivered (reboot, retry) without ever producing
     * a second reminder for the same event.
     */
    fun maybePostReminder(context: Context, event: TimerCompletionEvent): Boolean {
        val repo = TimerRepository(context)
        if (repo.isEventHandled(event.eventId)) return false
        if (!repo.markReminderSent(event.eventId)) {
            Log.d(TAG, "Re-reminder already sent for ${event.eventId} \u2014 skipping")
            return false
        }
        if (!canPost(context)) return false

        return runCatching {
            ensureChannels(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_EVENT_ID, event.eventId)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                notificationId(event.eventId) + 2,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_REMINDER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Still there?")
                .setContentText("Your ${event.label.ifBlank { "timer" }} result is waiting")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            manager.notify(notificationId(event.eventId) + 3, notification)
            true
        }.onFailure { Log.w(TAG, "Failed to post timer re-reminder", it) }.getOrDefault(false)
    }

    /** Replaces any live \"timer running\" summary notification. Best-effort. */
    fun clearCompletion(context: Context, eventId: String) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId(eventId))
            manager.cancel(notificationId(eventId) + 3)
        }
    }

    /** Reads back a timer from the extras [AlarmRingingActivity] was launched with. */
    fun eventFromExtras(intent: Intent): TimerCompletionEvent? {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return null
        val kind = runCatching { TimerKind.valueOf(intent.getStringExtra(EXTRA_TIMER_KIND) ?: "") }
            .getOrDefault(TimerKind.TIMER)
        return TimerCompletionEvent(
            eventId = eventId,
            runId = intent.getStringExtra(EXTRA_TIMER_RUN_ID).orEmpty(),
            kind = kind,
            label = intent.getStringExtra(EXTRA_TIMER_LABEL).orEmpty(),
            habitId = intent.getStringExtra(EXTRA_TIMER_HABIT_ID),
            targetSeconds = intent.getIntExtra(EXTRA_TIMER_TARGET, 0),
            elapsedSeconds = intent.getIntExtra(EXTRA_TIMER_ELAPSED, 0),
        )
    }

    /**
     * Reads the timer event a full-screen launch was started with.
     *
     * Reading it back *is* how [AlarmRingingActivity] decides what is ringing.
     * It used to call `TimerNotifier.eventFromIntent(intent)` while only
     * [eventFromExtras] existed, which failed to resolve — so the ringing screen
     * could not compile at all. They are one implementation, not two.
     */
    fun eventFromIntent(intent: Intent): TimerCompletionEvent? = eventFromExtras(intent)

    /**
     * The headline for a ringing [event], shared by the notification and
     * [AlarmRingingActivity]
     * so the alert and the screen that opens from it can never disagree about
     * what finished.
     */
    fun ringTitle(event: TimerCompletionEvent): String = when (event.kind) {
        TimerKind.TIMER -> "Timer complete"
        TimerKind.STOPWATCH -> "Target reached"
    }

    /** Supporting line for a ringing [event], naming the duration reached. */
    fun ringSubtitle(event: TimerCompletionEvent): String = buildString {
        append(
            if (event.kind == TimerKind.TIMER) {
                "${formatTimerDuration(event.targetSeconds)} timer finished"
            } else {
                "Stopwatch passed ${formatTimerDuration(event.targetSeconds)}"
            },
        )
        if (event.label.isNotBlank()) append(" — ${event.label}")
    }

    /** Puts a [TimerCompletionEvent] into extras (used by the ringing screen's tests/shares). */
    fun applyEventExtras(intent: Intent, event: TimerCompletionEvent) {
        intent.putExtra(EXTRA_EVENT_ID, event.eventId)
        intent.putExtra(EXTRA_TIMER_RUN_ID, event.runId)
        intent.putExtra(EXTRA_TIMER_KIND, event.kind.name)
        intent.putExtra(EXTRA_TIMER_LABEL, event.label)
        intent.putExtra(EXTRA_TIMER_HABIT_ID, event.habitId)
        intent.putExtra(EXTRA_TIMER_TARGET, event.targetSeconds)
        intent.putExtra(EXTRA_TIMER_ELAPSED, event.elapsedSeconds)
    }

    /** True when the app may currently ring full-screen (API 34+: its own grant). */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    /** True when Do Not Disturb / a Focus mode is filtering notifications right now. */
    fun isDoNotDisturbActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android ignores importance/sound edits on an existing channel, so a
        // channel whose settings drifted (e.g. an older build created it LOW)
        // has to be deleted and recreated for the change to take effect.
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null &&
            (existing.importance < NotificationManager.IMPORTANCE_HIGH ||
                existing.audioAttributes?.usage != AudioAttributes.USAGE_ALARM)
        ) {
            manager.deleteNotificationChannel(CHANNEL_ID)
        }
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Timer alarms",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Rings when a timer or stopwatch target is reached"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    setSound(
                        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                },
            )
        }

        if (manager.getNotificationChannel(CHANNEL_ID_REMINDER) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_REMINDER,
                    "Timer follow-ups",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "A single quiet follow-up when a timer result has not been looked at yet"
                },
            )
        }
    }
}
