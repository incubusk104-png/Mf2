package com.rork.mindsetframestracker.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.AlarmEventOutcome
import com.rork.mindsetframestracker.data.HabitAlarmHistory
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.MindsetRepository

/**
 * Turns the lock-screen habit dialog's three answers — **Done, Snooze, Skip** —
 * into state.
 *
 * ## Why the answers are handled here and not in the dialog
 *
 * The dialog is hosted by [HabitAlarmDialogActivity], which may be created on a
 * locked phone at 07:00 and destroyed the instant the user's thumb lands on a
 * button. Anything it wrote itself would be a write racing its own teardown, on
 * the one path where losing an answer means losing the user's check-in. Routing
 * each answer through a broadcast receiver gives every answer the same
 * guarantees the rest of the alarm system already relies on (see
 * [AlarmStopReceiver] for the same reasoning): a broadcast is always
 * deliverable, and the receiver outlives the screen.
 *
 * ## The three answers mean three different things
 *
 *  * **Done** writes the result — an occurrence record plus today's check-in —
 *    and is idempotent per occurrence, so double-delivering it (a re-sent
 *    broadcast, a repeated tap) cannot turn one walk into two.
 *  * **Snooze** defers, and delegates to the existing [HabitSnoozeReceiver] so
 *    there is exactly one snooze implementation in the app rather than a second
 *    one that could drift from the notification's own Snooze button.
 *  * **Skip** records the *absence* of a result: today's history says this
 *    occurrence was dismissed, and the next scheduled occurrence is re-armed.
 *    It deliberately writes no log and no check-in — if the only outcomes were
 *    "done" and "later", users could not tell the app they did not do it, and
 *    their history would fill with completions that never happened.
 *
 * ## Re-arming
 *
 * Done and Skip both re-arm the habit's **next** occurrence. Consuming an
 * occurrence removes the AlarmManager entry that produced it, so a chain that
 * only ever re-armed inside the ringing path would stop after the first
 * completed day. Done re-arms in case the platform cancelled the pending
 * occurrence along with the ring; Skip *must* re-arm, because nothing else will.
 *
 * Every path is guarded: this runs while an alarm is ringing, and a throw
 * escaping `onReceive` kills the process at exactly the wrong moment. A failed
 * side effect loses a log line; an uncaught throw loses the alarm, the answer
 * and the app.
 */
class AlarmRingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching { handle(context, intent) }
            .onFailure { Log.w(TAG, "Ring action handling failed", it) }
    }

    private fun handle(context: Context, intent: Intent) {
        val habitId = intent.getStringExtra(HabitReminderReceiver.EXTRA_HABIT_ID) ?: return
        val habitName = intent.getStringExtra(HabitReminderReceiver.EXTRA_HABIT_NAME) ?: habitId
        val alarmMinutes = intent
            .getIntExtra(
                HabitReminderReceiver.EXTRA_ALARM_MINUTES,
                HabitReminderReceiver.NO_ALARM_MINUTES,
            )
            .takeIf { it != HabitReminderReceiver.NO_ALARM_MINUTES }

        // The answer supersedes the notification: the reminder in the shade is
        // about the occurrence the user just answered, and leaving it behind
        // makes the answer look like it only half worked.
        cancelNotification(context, habitId)

        when (intent.action) {
            ACTION_HABIT_DONE -> recordDone(context, habitId, alarmMinutes)
            ACTION_HABIT_SNOOZE -> snooze(context, habitId, habitName, alarmMinutes)
            ACTION_HABIT_SKIP -> skip(context, habitId, alarmMinutes)
            else -> Log.d(TAG, "Ignoring unknown ring action ${intent.action}")
        }
    }

    /**
     * Marks the occurrence done.
     *
     * [HabitTrackingMode.CHECK] is used rather than the habit's own mode on
     * purpose. The dialog's Done is a **single tap** — the lock screen has no
     * room for a counter, a timer or a note field — so the honest record is "this
     * occurrence was acknowledged", not a fabricated count or duration. A count
     * habit's amount is still recordable from its own dialog in the app; nothing
     * here invents one on the user's behalf, which is the difference between a
     * check-in and an untrue measurement.
     *
     * Idempotence comes from [HabitAlarmRecords.recordOccurrence], which upserts
     * per `(habit, day, alarm time)`: the same answer delivered twice produces
     * one record.
     */
    private fun recordDone(context: Context, habitId: String, alarmMinutes: Int?) {
        HabitAlarmRecords.recordOccurrence(
            context = context,
            habitId = habitId,
            mode = HabitTrackingMode.CHECK,
            alarmMinutes = alarmMinutes,
            markDone = true,
        )
        // The history's own outcome, so the day's slots read as answered rather
        // than as a ring that nobody dealt with.
        HabitAlarmHistory.markOutcome(
            context = context,
            habitId = habitId,
            scheduledMinutes = alarmMinutes,
            outcome = AlarmEventOutcome.ACKNOWLEDGED,
        )
        rearmNextOccurrence(context, habitId, alarmMinutes)
    }

    /**
     * Defers the occurrence through the app's single snooze implementation.
     *
     * Delegating rather than arming a timer here is the point: the notification's
     * own "Snooze 5 min" and this dialog's Snooze must not be able to behave
     * differently, and [HabitSnoozeReceiver] already carries the identity of the
     * exact `(habit, time)` being snoozed, the permission fallback ladder, and
     * the re-arm of the day's remaining occurrences.
     */
    private fun snooze(context: Context, habitId: String, habitName: String, alarmMinutes: Int?) {
        val dispatch = Intent(context, HabitSnoozeReceiver::class.java).apply {
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_NAME, habitName)
            alarmMinutes?.let { putExtra(HabitReminderReceiver.EXTRA_ALARM_MINUTES, it) }
        }
        runCatching { context.sendBroadcast(dispatch) }
            .onFailure { Log.w(TAG, "Could not deliver the snooze for $habitId", it) }
    }

    /**
     * Ends the occurrence without a result.
     *
     * The dismissal is recorded so the day's history shows what actually
     * happened, and no log or check-in is written — skipping a habit is not
     * doing it. The next occurrence is re-armed because nothing else will: the
     * consumed AlarmManager entry is gone.
     */
    private fun skip(context: Context, habitId: String, alarmMinutes: Int?) {
        HabitAlarmHistory.markOutcome(
            context = context,
            habitId = habitId,
            scheduledMinutes = alarmMinutes,
            outcome = AlarmEventOutcome.DISMISSED,
        )
        rearmNextOccurrence(context, habitId, alarmMinutes)
    }

    /**
     * Arms the habit's next scheduled occurrence after [firedAlarmMinutes].
     *
     * Guarded and idempotent: [HabitAlarmScheduler.scheduleNext] addresses each
     * `(habit, time)` pair with its own request code under
     * `FLAG_UPDATE_CURRENT`, so a re-arm is a replacement rather than a second
     * alarm — which is what makes it safe to call on a path that may also be
     * re-armed by the reminder receiver.
     */
    private fun rearmNextOccurrence(context: Context, habitId: String, firedAlarmMinutes: Int?) {
        val minutes = firedAlarmMinutes ?: return
        runCatching {
            val habit = MindsetRepository(context).load().habits.firstOrNull { it.id == habitId }
                ?: return
            HabitAlarmScheduler.scheduleNext(context, habitId, habit.name, minutes)
        }.onFailure { Log.w(TAG, "Could not re-arm the next occurrence for $habitId", it) }
    }

    private fun cancelNotification(context: Context, habitId: String) {
        runCatching {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(HabitCheckInNotifier.notificationId(habitId))
        }.onFailure { Log.w(TAG, "Could not clear the reminder notification for $habitId", it) }
    }

    companion object {
        private const val TAG = "AlarmRingActionReceiver"

        /**
         * Done: record the occurrence and complete today's check-in.
         * Package-scoped, with the receiver itself `exported="false"` — no other
         * app can answer the user's alarm for them.
         */
        const val ACTION_HABIT_DONE = "com.rork.mindsetframestracker.action.HABIT_DONE"

        /** Snooze: defer this occurrence and ring again shortly. */
        const val ACTION_HABIT_SNOOZE = "com.rork.mindsetframestracker.action.HABIT_SNOOZE"

        /** Skip: end the occurrence with no record. */
        const val ACTION_HABIT_SKIP = "com.rork.mindsetframestracker.action.HABIT_SKIP"
    }
}
