package com.rork.mindsetframestracker.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.rork.mindsetframestracker.notifications.CheckInReceiver.Companion.EXTRA_REMINDER_MINUTES
import java.util.Calendar
import java.util.Date

/**
 * Schedules the app's recurring alarms:
 *
 * 1. The daily check-in reminder (always shows a notification).
 * 2. The streak-protection alert, which fires later in the day and only
 *    notifies when today's habits are still incomplete.
 * 3. The Sunday-evening weekly recap ("You checked in 5/7 days this week").
 *
 * Uses the strongest alarm this device currently allows — see
 * [AlarmScheduler] for the fallback ladder. An exact alarm
 * ([AlarmManager.setAlarmClock]) needs the exact-alarm permission
 * ([AlarmManager.canScheduleExactAlarms]); without it we degrade to
 * `setExactAndAllowWhileIdle`, then to a 15-minute inexact window, rather than
 * scheduling nothing at all.
 *
 * Each receiver reschedules itself for the next day after each fire, so the
 * alarms stay aligned to the clock even across daylight-saving transitions.
 */
class NotificationScheduler(private val context: Context) {

    /** Schedules (or reschedules) the daily reminder at [minutes] past midnight. */
    fun scheduleDailyReminder(minutes: Int) {
        cancel()
        scheduleAt(
            label = "Daily reminder",
            minutes = minutes,
            requestCode = REQUEST_CODE,
            pendingIntent = checkInIntent(minutes),
        )
    }

    /**
     * Schedules (or reschedules) the streak-protection alert at [minutes]
     * past midnight. Whether a notification actually appears is decided at
     * fire time by [StreakAlertNotifier] — it stays silent once today's
     * habits are all done.
     */
    fun scheduleStreakAlert(minutes: Int) {
        cancelStreakAlert()
        scheduleAt(
            label = "Streak alert",
            minutes = minutes,
            requestCode = STREAK_ALERT_REQUEST_CODE,
            pendingIntent = streakAlertIntent(minutes),
        )
    }

    /**
     * Schedules (or reschedules) the Sunday-evening weekly recap. Always uses
     * an inexact windowed alarm on purpose: a recap needs no minute precision,
     * and this works on every device without the exact-alarm permission or
     * the status-bar alarm indicator.
     */
    fun scheduleWeeklyRecap() {
        cancelWeeklyRecap()
        val triggerAtMillis = nextWeeklyTriggerTime(Calendar.SUNDAY, WEEKLY_RECAP_MINUTES)
        runCatching {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                WINDOW_MILLIS,
                weeklyRecapIntent(),
            )
        }
            .onSuccess { Log.i(TAG, "Weekly recap scheduled for ${Date(triggerAtMillis)}") }
            .onFailure { error -> Log.w(TAG, "Failed to schedule weekly recap", error) }
    }

    /**
     * Schedules the one-shot trial payment reminder at [triggerAtMillis]
     * (typically 24 hours before the trial converts to a paid plan). The
     * notifier re-verifies the trial at fire time, so a stale alarm is safe.
     */
    /**
     * Schedules (or reschedules) the nightly evening reflection prompt — a
     * quiet companion question that lands around 9:15 PM. The receiver
     * reschedules itself daily so the ritual stays perpetual.
     */
    fun scheduleEveningReflection() {
        cancelEveningReflection()
        scheduleAt(
            label = "Evening reflection",
            minutes = EVENING_REFLECTION_MINUTES,
            requestCode = EVENING_REFLECTION_REQUEST_CODE,
            pendingIntent = eveningReflectionIntent(),
        )
    }

    /** Removes the scheduled evening reflection prompt. */
    fun cancelEveningReflection() {
        cancelAlarm(
            CompanionReceiver::class.java,
            ACTION_EVENING_REFLECTION,
            EVENING_REFLECTION_REQUEST_CODE,
        )
    }

    /** Removes the scheduled daily reminder entirely. */
    fun cancel() {
        cancelAlarm(CheckInReceiver::class.java, ACTION_DAILY_CHECK_IN, REQUEST_CODE)
    }

    /** Removes the scheduled streak alert entirely. */
    fun cancelStreakAlert() {
        cancelAlarm(
            StreakAlertReceiver::class.java,
            ACTION_STREAK_ALERT,
            STREAK_ALERT_REQUEST_CODE,
        )
    }

    /** Removes the scheduled weekly recap entirely. */
    fun cancelWeeklyRecap() {
        cancelAlarm(
            WeeklyRecapReceiver::class.java,
            ACTION_WEEKLY_RECAP,
            WEEKLY_RECAP_REQUEST_CODE,
        )
    }

    /**
     * Arms one recurring reminder and logs which mechanism actually took it.
     *
     * [requestCode] is only used to build the alarm-clock show-intent, so the
     * status-bar alarm icon opens *this app* instead of doing nothing (the old
     * code passed the broadcast PendingIntent as the show-intent, which Android
     * cannot launch as an Activity — see [AlarmScheduler]).
     */
    private fun scheduleAt(
        label: String,
        minutes: Int,
        requestCode: Int,
        pendingIntent: PendingIntent,
    ) {
        val triggerAtMillis = nextTriggerTime(minutes)
        val precision = AlarmScheduler.schedule(
            context = context,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            wakeUp = true,
            allowWhileIdle = true,
            showIntent = AlarmScheduler.showIntent(context, requestCode),
        )
        Log.i(TAG, "$label scheduled for ${formatTime(minutes)} (precision=$precision)")
    }

    private fun cancelAlarm(receiver: Class<*>, action: String, requestCode: Int) {
        // Build a PendingIntent matching the scheduled one using NO_CREATE so we
        // don't fabricate a new one if it doesn't exist, then cancel it.
        val receiverIntent = Intent(context, receiver).apply { this.action = action }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            receiverIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            pendingIntent.cancel()
        }
    }

    private fun checkInIntent(minutes: Int): PendingIntent {
        val receiverIntent = Intent(context, CheckInReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_MINUTES, minutes)
            action = ACTION_DAILY_CHECK_IN
        }
        // Match flags used in cancelAlarm() so they refer to the same PendingIntent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, receiverIntent, flags)
    }

    private fun streakAlertIntent(minutes: Int): PendingIntent {
        val receiverIntent = Intent(context, StreakAlertReceiver::class.java).apply {
            putExtra(StreakAlertReceiver.EXTRA_ALERT_MINUTES, minutes)
            action = ACTION_STREAK_ALERT
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            STREAK_ALERT_REQUEST_CODE,
            receiverIntent,
            flags,
        )
    }

    private fun eveningReflectionIntent(): PendingIntent {
        val receiverIntent = Intent(context, CompanionReceiver::class.java).apply {
            action = ACTION_EVENING_REFLECTION
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            EVENING_REFLECTION_REQUEST_CODE,
            receiverIntent,
            flags,
        )
    }

    private fun weeklyRecapIntent(): PendingIntent {
        val receiverIntent = Intent(context, WeeklyRecapReceiver::class.java).apply {
            action = ACTION_WEEKLY_RECAP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            WEEKLY_RECAP_REQUEST_CODE,
            receiverIntent,
            flags,
        )
    }

    private fun nextTriggerTime(minutes: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If the target time has already passed today, roll to tomorrow.
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    /** Next occurrence of [dayOfWeek] (a [Calendar] constant) at [minutes] past midnight. */
    private fun nextWeeklyTriggerTime(dayOfWeek: Int, minutes: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If that day/time already passed this week, roll to next week.
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.WEEK_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    private fun formatTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val amPm = if (h < 12) "AM" else "PM"
        val hour12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return String.format("%d:%02d %s", hour12, m, amPm)
    }

    companion object {
        const val TAG = "NotificationScheduler"
        const val ACTION_DAILY_CHECK_IN = "com.rork.mindsetframestracker.DAILY_CHECK_IN"
        const val ACTION_STREAK_ALERT = "com.rork.mindsetframestracker.STREAK_ALERT"
        const val ACTION_WEEKLY_RECAP = "com.rork.mindsetframestracker.WEEKLY_RECAP"
        const val ACTION_EVENING_REFLECTION =
            "com.rork.mindsetframestracker.EVENING_REFLECTION"
        const val REQUEST_CODE = 1001
        const val STREAK_ALERT_REQUEST_CODE = 1002
        const val WEEKLY_RECAP_REQUEST_CODE = 1003
        const val EVENING_REFLECTION_REQUEST_CODE = 1006
        /** Evening reflection lands around 9:15 PM — after the streak alert. */
        const val EVENING_REFLECTION_MINUTES = 21 * 60 + 15
        /** Weekly recap fires Sundays around 6:00 PM (inexact 15-min window). */
        const val WEEKLY_RECAP_MINUTES = 18 * 60
        private const val WINDOW_MILLIS = 15L * 60L * 1000L
    }
}
