package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.notifications.CheckInReceiver.Companion.EXTRA_REMINDER_MINUTES
import java.util.Date

/**
 * Schedules the app's recurring alarms:
 *
 * 1. The daily check-in reminder (always shows a notification).
 * 2. The streak-protection alert, which fires later in the day and only
 *    notifies when today's habits are still incomplete.
 * 3. The Sunday-evening weekly recap ("You checked in 5/7 days this week").
 *
 * Uses the strongest **app-owned** alarm this device currently allows — see
 * [AlarmScheduler] for the fallback ladder. An exact alarm needs the
 * exact-alarm permission ([AlarmManager.canScheduleExactAlarms]); without it we
 * degrade to `setExactAndAllowWhileIdle`, then to a 15-minute inexact window,
 * rather than scheduling nothing at all. No alarm here registers with the
 * system Clock app.
 *
 * Each receiver reschedules itself for the next day after each fire, so the
 * alarms stay aligned to the clock even across daylight-saving transitions.
 */
class NotificationScheduler(private val context: Context) {

    /**
     * The system [AlarmManager], resolved on demand.
     *
     * Both call sites below — `setWindow` for the weekly recap and `cancel` for
     * the teardown paths — referenced a bare `alarmManager` that this class
     * never declared. Together with the missing `android.app.AlarmManager`
     * import that is what made `:app:compileReleaseKotlin` fail with
     * `Unresolved reference 'alarmManager'` / `'AlarmManager'`, and the
     * `Cannot infer type for type parameter 'R'` / `'T'` errors that fell out
     * of the same unresolved receiver in the `runCatching { … }` chains.
     *
     * Resolved through `Context.ALARM_SERVICE` here rather than routed via
     * [AlarmScheduler]: the weekly recap deliberately stays on an inexact
     * windowed alarm (see [scheduleWeeklyRecap]), so it must not be promoted to
     * the exact ladder that [AlarmScheduler.schedule] would pick.
     */
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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
        val triggerAtMillis = nextWeeklyTriggerTime(java.time.DayOfWeek.SUNDAY, WEEKLY_RECAP_MINUTES)
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
     * [requestCode] identifies which reminder this is; the arming itself goes
     * through [AlarmScheduler], which arms an app-owned exact alarm and never
     * registers with the system Clock app.
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
        val clamped = minutes.coerceIn(0, 24 * 60 - 1)
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDateTime.now(zone)
        val time = java.time.LocalTime.of(clamped / 60, clamped % 60)
        var candidate = now.toLocalDate().atTime(time)
        // Strictly ahead: a time equal to `now` to the minute is already due, and
        // arming it would fire immediately.
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Next occurrence of [dayOfWeek] at [minutes] past midnight — the
     * weekly-recap equivalent of [nextTriggerTime].
     *
     * ## Why both of these are `java.time` and not `Calendar`
     *
     * The `Calendar` versions moved *the current instant* forward rather than
     * asking for the next local wall-clock time. On an ordinary day those agree,
     * but they diverge across a daylight-saving transition and after a timezone
     * change: the alarm drifts by the offset delta and can land an hour early or
     * late — or, in the spring-forward gap, at a wall-clock time that does not
     * exist on that date. Resolving a local time through the zone is exactly what
     * `java.time` is for: a non-existent local time is shifted forward by the gap
     * and an ambiguous one resolves to the earlier offset, which is what the OS
     * clock does. The app's reminders and the system's own alarms therefore agree.
     */
    private fun nextWeeklyTriggerTime(dayOfWeek: java.time.DayOfWeek, minutes: Int): Long {
        val clamped = minutes.coerceIn(0, 24 * 60 - 1)
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDateTime.now(zone)
        val time = java.time.LocalTime.of(clamped / 60, clamped % 60)
        // Walk forward to the first matching day that is still ahead of `now`.
        // A full week guarantees a hit; the loop returns as soon as it finds one.
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (day.dayOfWeek != dayOfWeek) continue
            val candidate = day.atTime(time)
            if (!candidate.isAfter(now)) continue
            return candidate.atZone(zone).toInstant().toEpochMilli()
        }
        // Unreachable: 8 consecutive days contain two of every weekday.
        return now.plusDays(7).toLocalDate().atTime(time)
            .atZone(zone).toInstant().toEpochMilli()
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