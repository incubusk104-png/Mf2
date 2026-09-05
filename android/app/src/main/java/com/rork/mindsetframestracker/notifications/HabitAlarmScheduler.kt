package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.REPEAT_DAILY
import com.rork.mindsetframestracker.data.REPEAT_ONCE
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules, cancels, and reschedules per-habit reminders via **WorkManager**
 * instead of [android.app.AlarmManager].
 *
 * ## Why WorkManager instead of AlarmManager
 *
 * The previous revision used `setExactAndAllowWhileIdle()` (falling back to
 * `setWindow()`/`set()`), which needs the `SCHEDULE_EXACT_ALARM` /
 * `USE_EXACT_ALARM` special permission on Android 12+. In practice that
 * permission is exactly the kind of thing that quietly breaks reminders:
 * OEM battery managers (MIUI, One UI, EMUI, ColorOS, …) revoke or ignore it,
 * Play Store policy limits `USE_EXACT_ALARM` to apps whose core purpose is
 * being an alarm clock or calendar, and a user can turn it off at any time
 * in Settings > Apps > Alarms & reminders — with zero feedback inside the
 * app when that happens. The result: "the habit alarm just doesn't fire."
 *
 * WorkManager is Android's own built-in, batteries-included scheduler
 * (already used elsewhere in this app for [com.rork.mindsetframestracker.data.CloudBackupWorker]).
 * It needs no special permission, is guaranteed to run (even after the app
 * is killed or the device reboots — no custom [BootReceiver] wiring
 * required for it specifically), and Android itself decides the most
 * battery-friendly way to honor the requested delay. The one trade-off is
 * that a fire time can drift by a few minutes under aggressive Doze — a
 * reasonable price for "actually goes off" over "exactly on the second but
 * sometimes silently doesn't."
 *
 * Each habit with a non-null [Habit.reminderMinutes] gets its own uniquely
 * named one-time work request that reschedules itself (via
 * [HabitReminderWorker] calling back into [scheduleNext]) after it fires,
 * mirroring how the old alarm chain re-armed itself daily.
 */
object HabitAlarmScheduler {

    private const val TAG = "HabitAlarmScheduler"
    private const val WORK_NAME_PREFIX = "habit_reminder_"

    fun schedule(context: Context, habit: Habit) {
        val minutes = habit.reminderMinutes ?: return
        enqueue(context, habit.id, habit.name, minutes, habit.repeatDaysMask)
    }

    fun cancel(context: Context, habit: Habit) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(habit.id))
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
     * Called by [HabitReminderWorker] right after it fires, to re-arm the
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
        val delayMillis =
            (nextTriggerMillis(minutes, repeatDaysMask) - System.currentTimeMillis())
                .coerceAtLeast(0L)

        val inputData = Data.Builder()
            .putString(HabitReminderWorker.KEY_HABIT_ID, habitId)
            .putString(HabitReminderWorker.KEY_HABIT_NAME, habitName)
            .putBoolean(HabitReminderWorker.KEY_IS_SNOOZE_REFIRE, false)
            .build()

        val request = OneTimeWorkRequestBuilder<HabitReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(habitId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.d(TAG, "Reminder for '$habitName' queued in ${delayMillis / 60_000} min")
    }

    private fun workName(habitId: String) = "$WORK_NAME_PREFIX$habitId"

    /**
     * Next trigger time honouring the repeat day mask (bit 0 = Monday …
     * bit 6 = Sunday). [REPEAT_ONCE] (mask 0) behaves like "next occurrence
     * of this time" — today if still ahead, otherwise tomorrow — and the
     * work simply isn't re-enqueued after it fires.
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
