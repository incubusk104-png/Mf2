package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.HabitAlarmBehavior
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.alarmBehavior
import com.rork.mindsetframestracker.data.isSportActivity
import com.rork.mindsetframestracker.data.trackingModeOrDefault

/**
 * Fired by the app's own [android.app.AlarmManager] alarm that
 * [HabitAlarmScheduler] scheduled for one of the user's chosen habit-reminder
 * times (or by [HabitSnoozeReceiver]'s 5-minute snooze re-fire).
 *
 * Posts the reminder via [HabitCheckInNotifier.showResult]. Unless this run was
 * a snooze re-fire, that call also re-arms **this time's** next occurrence
 * through [HabitAlarmScheduler.scheduleNext] — mirroring how [CheckInReceiver]
 * and [StreakAlertReceiver] re-arm the global daily alarms.
 *
 * ## This receiver knows WHICH occurrence rang
 *
 * A habit can ring several times a day, so the alarm's time travels with the
 * intent ([EXTRA_ALARM_MINUTES]) and is handed to both the re-arm and the
 * record. That is what lets the app answer "what did I do at each time today"
 * instead of collapsing a morning and an evening walk into one entry — and it
 * is why the timer request carries it too, so the sheet that opens knows which
 * occurrence it is recording against.
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
        // Which of the habit's alarm times this is. Absent only for a snooze
        // re-fire of a request written by an older build, in which case the
        // record falls back to "the habit's first alarm time".
        val alarmMinutes = intent.getIntExtra(EXTRA_ALARM_MINUTES, NO_ALARM_MINUTES)

        // showResult() already wraps its own body in runCatching, so this
        // can't throw — but log every non-success case with the real reason
        // (previously a failure here was invisible; nothing recorded WHY a
        // scheduled alarm didn't produce a notification).
        when (val result = HabitCheckInNotifier.showResult(
            context,
            habitId,
            habitName,
            alarmMinutes = alarmMinutes.takeIf { it != NO_ALARM_MINUTES },
            reschedule = !isSnoozeRefire,
        )) {
            is HabitCheckInNotifier.NotifyResult.Posted -> {
                // The alarm actually reached the user, so this is the moment the
                // habit's own tool becomes due — but ONLY for a habit whose
                // completion needs a tool.
                //
                // Whether a dialog is right at all is the habit's own
                // [Habit.alarmBehavior]: a CHECK habit ("Take a vitamin") is
                // answered by the dismissal itself, so raising a timer sheet for
                // it asked the user to configure a measurement it does not have
                // and made a one-tap habit cost three taps. A COUNT or JOURNAL
                // habit does need input, but not a *timer* — its sheet renders
                // the stepper or the note field, chosen from the mode recorded
                // here. Only TIMER/STOPWATCH habits actually want the tool.
                //
                // Written HERE, on the notification path, rather than from
                // AlarmRingingActivity: that screen only launches when
                // USE_FULL_SCREEN_INTENT is granted (Android 14+ revokes it by
                // default), so a ring that never reached it used to leave no
                // request behind and the popup never appeared.
                val habit = runCatching {
                    MindsetRepository(context).load().habits.firstOrNull { it.id == habitId }
                }.getOrNull()
                if (habit != null && habit.alarmBehavior != HabitAlarmBehavior.ONE_TAP) {
                    runCatching {
                        HabitTimerRequests.request(
                            context = context,
                            habitId = habitId,
                            habitName = habitName,
                            iconId = habit.iconId,
                            mode = habit.trackingModeOrDefault,
                            isSport = habit.isSportActivity,
                            alarmMinutes = alarmMinutes.takeIf { it != NO_ALARM_MINUTES },
                        )
                    }.onFailure { Log.w(TAG, "Could not record the timer/stopwatch request", it) }
                }

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

        /** Which of the habit's alarm times fired, in minutes from midnight. */
        const val EXTRA_ALARM_MINUTES = "alarmMinutes"

        /** Sentinel for "this intent predates multi-time alarms". */
        const val NO_ALARM_MINUTES = -1
    }
}
