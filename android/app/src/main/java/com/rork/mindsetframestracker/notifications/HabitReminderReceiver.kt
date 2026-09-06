package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by the [android.app.AlarmManager] alarm-clock alarm that
 * [HabitAlarmScheduler] scheduled for the user's chosen habit-reminder time
 * (or by [HabitSnoozeReceiver]'s 5-minute snooze re-fire).
 *
 * Posts the reminder via [HabitCheckInNotifier.show]. Unless this run was a
 * snooze re-fire, that call also re-arms tomorrow's occurrence through
 * [HabitAlarmScheduler.scheduleNext] — mirroring how [CheckInReceiver] and
 * [StreakAlertReceiver] re-arm the global daily alarms.
 */
class HabitReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: "Habit"
        val isSnoozeRefire = intent.getBooleanExtra(EXTRA_IS_SNOOZE_REFIRE, false)

        runCatching {
            HabitCheckInNotifier.show(
                context,
                habitId,
                habitName,
                reschedule = !isSnoozeRefire,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to show habit reminder for '$habitName'", error)
        }
    }

    companion object {
        private const val TAG = "HabitReminderReceiver"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_IS_SNOOZE_REFIRE = "isSnoozeRefire"
    }
}
