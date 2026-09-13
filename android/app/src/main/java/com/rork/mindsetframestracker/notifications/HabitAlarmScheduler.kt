package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.REPEAT_DAILY
import com.rork.mindsetframestracker.data.REPEAT_ONCE
import java.util.Calendar

/**
 * Schedules, cancels, and reschedules per-habit reminders via
 * **[AlarmManager.setAlarmClock]** — the same mechanism [NotificationScheduler]
 * already uses for the daily check-in, streak alert, and evening reflection.
 *
 * ## Why this is AlarmManager again, not WorkManager
 *
 * A previous revision moved per-habit reminders to WorkManager's
 * `setInitialDelay()` to sidestep the exact-alarm permission entirely. That
 * traded a *rare* failure (a user denying the exact-alarm permission) for a
 * *routine* one: `setInitialDelay()` only sets a **minimum** delay — Android's
 * Doze / App Standby batching is free to defer the job well past that once the
 * delay spans idle, screen-off time, which "walk at 8:45 PM" always does. That
 * is exactly the reported symptom: 8:45 PM comes and goes with no sound and no
 * vibration, because the job hadn't run yet.
 *
 * `AlarmManager.setAlarmClock()` is the one Android primitive that's
 * explicitly exempt from Doze/App-Standby deferral — it's the same API the
 * system Clock app uses (hence the status-bar alarm-clock icon). On API 31+ we
 * check [AlarmManager.canScheduleExactAlarms] first and fall back to a
 * 15-minute [AlarmManager.setWindow] the same way [NotificationScheduler]
 * already does, so the app never crashes and a reminder still fires close to
 * on time even without the permission.
 *
 * Each habit with a non-null [Habit.reminderMinutes] gets its own
 * [PendingIntent] (keyed by `habit.id.hashCode()`) targeting
 * [HabitReminderReceiver], which re-arms the next occurrence itself after
 * firing — mirroring how [CheckInReceiver]/[StreakAlertReceiver] re-arm the
 * global alarms.
 */
object HabitAlarmScheduler {

    private const val TAG = "HabitAlarmScheduler"
    private const val WINDOW_MILLIS = 15L * 60L * 1000L
    const val ACTION_HABIT_REMINDER = "com.rork.mindsetframestracker.HABIT_REMINDER"

    fun schedule(context: Context, habit: Habit) {
        val minutes = habit.reminderMinutes ?: return
        enqueue(context, habit.id, habit.name, minutes, habit.repeatDaysMask)
    }

    fun cancel(context: Context, habit: Habit) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(habit.id),
            reminderIntent(context, habit.id, habit.name),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            pendingIntent.cancel()
        }
        Log.d(TAG, "Cancelled reminder for '${habit.name}'")
    }

    fun rescheduleAll(context: Context, habits: List<Habit>) {
        var count = 0
        habits.forEach { habit ->
            if (habit.reminderMinutes != null) {
                schedule(context, habit)
                count++
            }
        }
        Log.i(TAG, "Rescheduled $count habit reminder(s)")
    }

    /**
     * Called by [HabitReminderReceiver] right after it fires, to re-arm the
     * next occurrence. A repeat mask of [REPEAT_ONCE] means it was a
     * one-shot reminder — it is NOT re-armed (mirrors the system Clock's
     * "Repeat: Once" behaviour).
     */
    fun scheduleNext(context: Context, habitId: String, habitName: String) {
        val repo = MindsetRepository(context)
        val habit = repo.load().habits.find { it.id == habitId } ?: return
        val minutes = habit.reminderMinutes ?: return
        if (habit.repeatDaysMask == REPEAT_ONCE) {
            Log.d(TAG, "'${habit.name}' repeats Once — not re-arming")
            return
        }
        enqueue(context, habitId, habitName, minutes, habit.repeatDaysMask)
    }

    private fun enqueue(
        context: Context,
        habitId: String,
        habitName: String,
        minutes: Int,
        repeatDaysMask: Int = REPEAT_DAILY,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = nextTriggerMillis(minutes, repeatDaysMask)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(habitId),
            reminderIntent(context, habitId, habitName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        runCatching {
            if (canUseExact) {
                // AlarmClockInfo's 2nd param is a *show* intent — what the OS
                // launches if the user taps the alarm-clock icon in the status
                // bar. It must point at an Activity; the broadcast
                // `pendingIntent` below (which targets HabitReminderReceiver)
                // is the wrong PendingIntent type for this slot.
                val showIntent = PendingIntent.getActivity(
                    context,
                    requestCode(habitId),
                    Intent(context, com.rork.mindsetframestracker.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                    pendingIntent,
                )
            } else {
                // No exact-alarm permission: fire within a 15-minute window
                // around the target time rather than not at all.
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    WINDOW_MILLIS,
                    pendingIntent,
                )
            }
        }
            .onSuccess {
                Log.d(TAG, "Reminder for '$habitName' scheduled (exact=$canUseExact) at $triggerAtMillis")
            }
            .onFailure { error ->
                Log.w(TAG, "Failed to schedule reminder for '$habitName'", error)
            }
    }

    private fun reminderIntent(context: Context, habitId: String, habitName: String): Intent =
        Intent(context, HabitReminderReceiver::class.java).apply {
            action = ACTION_HABIT_REMINDER
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_NAME, habitName)
        }

    /** Distinct per habit; matches the id used for its notification/content intents. */
    private fun requestCode(habitId: String) = habitId.hashCode()

    /**
     * Next trigger time honouring the repeat day mask (bit 0 = Monday …
     * bit 6 = Sunday). [REPEAT_ONCE] (mask 0) behaves like "next occurrence
     * of this time" — today if still ahead, otherwise tomorrow — and the
     * alarm simply isn't re-armed after it fires.
     */
    private fun nextTriggerMillis(minutesFromMidnight: Int, repeatDaysMask: Int = REPEAT_DAILY): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesFromMidnight / 60)
            set(Calendar.MINUTE, minutesFromMidnight % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (repeatDaysMask == REPEAT_ONCE || repeatDaysMask == REPEAT_DAILY) {
            return cal.timeInMillis
        }
        // Walk forward (max 7 days) to the next enabled day-of-week.
        repeat(7) {
            // Calendar: SUNDAY=1..SATURDAY=7 → our mask bit: Monday=0..Sunday=6
            val bit = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                else -> 6 // SUNDAY
            }
            if (repeatDaysMask and (1 shl bit) != 0) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis // unreachable for any non-zero mask
    }
}
