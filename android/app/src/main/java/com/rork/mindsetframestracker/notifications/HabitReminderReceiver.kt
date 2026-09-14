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
 * Posts the reminder via [HabitCheckInNotifier.showResult]. Unless this run
 * was a snooze re-fire, that call also re-arms tomorrow's occurrence through
 * [HabitAlarmScheduler.scheduleNext] — mirroring how [CheckInReceiver] and
 * [StreakAlertReceiver] re-arm the global daily alarms.
 */
class HabitReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Never let a throw escape: a manifest receiver that dies takes its
        // process with it, and this one runs at the exact moment the user's
        // alarm is supposed to ring.
        runCatching { postReminder(context, intent) }
            .onFailure { Log.w(TAG, "Habit reminder handling failed", it) }
    }

    private fun postReminder(context: Context, intent: Intent) {
        val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: "Habit"
        val isSnoozeRefire = intent.getBooleanExtra(EXTRA_IS_SNOOZE_REFIRE, false)

        // showResult() already wraps its own body in runCatching, so this
        // can't throw — but log every non-success case with the real reason
        // (previously a failure here was invisible; nothing recorded WHY a
        // scheduled alarm didn't produce a notification).
        when (val result = HabitCheckInNotifier.showResult(
            context,
            habitId,
            habitName,
            reschedule = !isSnoozeRefire,
        )) {
            is HabitCheckInNotifier.NotifyResult.Posted -> {
                if (result.doNotDisturbActive) {
                    Log.w(TAG, "Habit reminder for '$habitName' posted, but Do Not Disturb / a Focus mode is active — it may not visibly appear")
                }
                if (result.fullScreenIntentUnavailable) {
                    Log.w(TAG, "Habit reminder for '$habitName' posted without a full-screen intent — USE_FULL_SCREEN_INTENT isn't granted, so this will only show as a normal notification, not a ringing alarm screen")
                }
            }
            is HabitCheckInNotifier.NotifyResult.PermissionMissing ->
                Log.w(TAG, "Habit reminder for '$habitName' not shown: notification permission missing")
            is HabitCheckInNotifier.NotifyResult.Blocked ->
                Log.w(TAG, "Habit reminder for '$habitName' not shown: notifications are switched off for this app/channel at the system level")
            is HabitCheckInNotifier.NotifyResult.Failed ->
                Log.w(TAG, "Habit reminder for '$habitName' failed: ${result.error}")
        }
    }

    companion object {
        private const val TAG = "HabitReminderReceiver"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_IS_SNOOZE_REFIRE = "isSnoozeRefire"
    }
}
