package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Fires a single habit reminder and — unless this run was a snooze re-fire —
 * re-arms the next occurrence. This replaces the old AlarmManager-based
 * broadcast receiver: WorkManager is Android's own "built-in" background
 * scheduler, so a habit reminder no longer depends on the user having
 * granted the exact-alarm special permission (`SCHEDULE_EXACT_ALARM` /
 * `USE_EXACT_ALARM`), which many OEMs silently deny or revoke and which
 * Google Play may not even grant to a non-alarm-clock app in the first
 * place. WorkManager also survives reboots and app updates automatically —
 * no custom boot-rescheduling logic required for it to keep firing.
 *
 * The notification itself is unchanged: [HabitCheckInNotifier.show] still
 * posts a high-priority notification with a full-screen intent so it rings
 * like a real alarm, sound, vibration and all.
 */
class HabitReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val habitId = inputData.getString(KEY_HABIT_ID) ?: return Result.failure()
        val habitName = inputData.getString(KEY_HABIT_NAME) ?: "Habit"
        val isSnoozeRefire = inputData.getBoolean(KEY_IS_SNOOZE_REFIRE, false)

        runCatching {
            HabitCheckInNotifier.show(
                applicationContext,
                habitId,
                habitName,
                reschedule = !isSnoozeRefire,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to show habit reminder for '$habitName'", error)
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "HabitReminderWorker"
        const val KEY_HABIT_ID = "habitId"
        const val KEY_HABIT_NAME = "habitName"
        const val KEY_IS_SNOOZE_REFIRE = "isSnoozeRefire"
    }
}
