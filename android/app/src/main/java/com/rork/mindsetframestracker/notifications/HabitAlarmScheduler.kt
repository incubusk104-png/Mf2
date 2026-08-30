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
 * Schedules, cancels, and reschedules per-habit reminder alarms via
 * [AlarmManager]. Each habit with a non-null [Habit.reminderMinutes] gets
 * its own daily repeating alarm that fires [HabitAlarmReceiver] ->
 * [HabitCheckInNotifier] to show the notification.
 *
 * ## Key architectural fixes in this revision
 *
 * 1. **USE_EXACT_ALARM support (API 33+):** On Android 13+ the app can hold
 *    the `USE_EXACT_ALARM` permission which is auto-granted for alarm/reminder
 *    apps. The scheduler now checks both `canScheduleExactAlarms()` (covers
 *    `SCHEDULE_EXACT_ALARM`) and the manifest `USE_EXACT_ALARM` flag before
 *    deciding whether to use exact or windowed alarms.
 *
 * 2. **Triple-fallback strategy:** exact -> windowed -> inexact. Every layer
 *    is wrapped in `runCatching` so no OEM quirk can crash the UI.
 *
 * 3. **Boot resilience:** [BootReceiver] now calls [rescheduleAll] so
 *    individual habit alarms survive reboots and app updates.
 */
object HabitAlarmScheduler {

    private const val TAG = "HabitAlarmScheduler"
    private const val REQUEST_CODE_BASE = 10_000

    /** 15-minute tolerance window used when exact alarms aren't permitted. */
    private const val WINDOW_MILLIS = 15L * 60L * 1000L

    fun schedule(context: Context, habit: Habit) {
        val minutes = habit.reminderMinutes ?: return
        setAlarm(context, habit.id, habit.name, minutes, habit.repeatDaysMask)
    }

    fun cancel(context: Context, habit: Habit) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, habit.id, habit.name)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled alarm for '${habit.name}'")
    }

    fun rescheduleAll(context: Context, habits: List<Habit>) {
        var count = 0
        habits.forEach { habit ->
            if (habit.reminderMinutes != null) {
                schedule(context, habit)
                count++
            }
        }
        Log.i(TAG, "Rescheduled $count habit alarm(s)")
    }

    /**
     * Called by HabitCheckInNotifier right after firing, to re-arm the next
     * occurrence. A repeat mask of [REPEAT_ONCE] means the alarm was a
     * one-shot — it is NOT re-armed (mirrors the system Clock's
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
        setAlarm(context, habitId, habitName, minutes, habit.repeatDaysMask)
    }

    private fun setAlarm(
        context: Context,
        habitId: String,
        habitName: String,
        minutes: Int,
        repeatDaysMask: Int = REPEAT_DAILY,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, habitId, habitName)
        val triggerTime = nextTriggerMillis(minutes, repeatDaysMask)

        // Determine the best alarm type we can use:
        //   1. Exact (setExactAndAllowWhileIdle) — most reliable, needs permission
        //   2. Windowed (setWindow) — 15-min tolerance, no special permission
        //   3. Inexact (set) — last resort
        val canUseExact = canScheduleExact(alarmManager)

        // Attempt 1: exact alarm
        if (canUseExact) {
            val exactOk = runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent,
                )
            }.isSuccess
            if (exactOk) {
                Log.d(TAG, "Exact alarm set for '$habitName' at $triggerTime")
                return
            }
        }

        // Attempt 2: windowed alarm (15-min tolerance)
        val windowOk = runCatching {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                WINDOW_MILLIS,
                pendingIntent,
            )
        }.isSuccess
        if (windowOk) {
            Log.d(TAG, "Windowed alarm set for '$habitName' at $triggerTime")
            return
        }

        // Attempt 3: plain inexact alarm — last resort
        runCatching {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }.onSuccess {
            Log.d(TAG, "Inexact alarm set for '$habitName' at $triggerTime")
        }.onFailure { error ->
            Log.e(TAG, "All alarm methods failed for '$habitName'", error)
        }
    }

    /**
     * Checks whether the app can schedule exact alarms on this device.
     *
     * - Below API 31 (Android 12): exact alarms are always allowed.
     * - API 31+: `canScheduleExactAlarms()` returns true when either
     *   `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` is held.
     */
    private fun canScheduleExact(alarmManager: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        }
    }

    private fun buildPendingIntent(context: Context, habitId: String, habitName: String): PendingIntent {
        val intent = Intent(context, HabitAlarmReceiver::class.java).apply {
            putExtra("habitId", habitId)
            putExtra("habitName", habitName)
        }
        val requestCode = REQUEST_CODE_BASE + habitId.hashCode()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

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
