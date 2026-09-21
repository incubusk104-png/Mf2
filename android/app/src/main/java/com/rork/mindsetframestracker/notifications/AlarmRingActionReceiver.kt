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
 * ## The diagnostic id is refused before anything is written
 *
 * [HabitCheckInNotifier.DIAGNOSTIC_HABIT_ID] is the "Send a test reminder now"
 * button's id. It is not a habit and not a UUID, and a write under it poisons
 * check-in syncing for good — see the guard at the top of [handle] and
 * [DIAGNOSTIC_HABIT_ID]'s own note for the full failure. The guard sits
 * **before** the notification is cancelled on purpose: the test notification
 * must still be cleared when the user answers, or tapping Done would leave the
 * test reminder sitting in the shade forever.
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

        // ── RE-ARM FIRST: before anything else, on every path ────────────────
        // Consuming this occurrence removed the AlarmManager entry that produced
        // it, so the next one exists only if this call succeeds — and everything
        // below it (the diagnostic refusal, the missed-alarm sweep, clearing the
        // notification, the answer's own record) can fail. Ordered this way, a
        // failure further down costs a log line; ordered the other way it would
        // cost the user their next alarm, silently, which is the worst outcome
        // the app can produce.
        //
        // The answer branches therefore no longer re-arm, and that is safe
        // because this call is idempotent: `HabitAlarmScheduler.scheduleNext`
        // addresses the `(habit, time)` pair under FLAG_UPDATE_CURRENT, so
        // re-arming once, here, replaces rather than duplicates. For the
        // diagnostic test id there is no stored habit and no alarm time on the
        // intent, so this is a no-op — exactly right, since a test ring has
        // nothing to re-arm.
        rearmNextOccurrence(context, habitId, alarmMinutes)

        // ── The diagnostic id is not a habit — refuse it whole ───────────────
        // BUG FIX (security review, blocking). The "Send a test reminder now"
        // button in AlarmPermissionPromptDialog rings under
        // [HabitCheckInNotifier.DIAGNOSTIC_HABIT_ID] ("diagnostic_test"). If that
        // id could reach recordDone(), it would write a non-uuid key into the
        // local checkIns map; SupabaseSync.pushSnapshot() then hands it to
        // Postgres's `checkins.habit_id` — which is type uuid — and every sync
        // from then on fails on that one row, for good (check-ins, settings, mood
        // and backup alike, because the push returns on the first failed upsert).
        //
        // Placed deliberately BEFORE cancelNotification(): the test reminder is a
        // real notification the user just answered, so it still has to be cleared
        // from the shade. Refusing the write must not strand the notification.
        if (habitId == HabitCheckInNotifier.DIAGNOSTIC_HABIT_ID) {
            Log.d(TAG, "Ignoring ring action ${intent.action} for the diagnostic test id")
            cancelNotification(context, habitId)
            return
        }

        // ── Log the occurrences this habit's alarm already let pass ──────────
        // Runs after the re-arm and before anything the user's answer does, and is
        // internally guarded: a history write can never cost the alarm or the
        // answer.
        logMissedOccurrences(context, habitId, alarmMinutes)

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
     *
     * Never reached for [HabitCheckInNotifier.DIAGNOSTIC_HABIT_ID] — [handle]
     * returns before dispatch for it, and the dialog is not attached to that id
     * in the first place (see [HabitCheckInNotifier.showResult]).
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
        // No re-arm here: [handle] already re-armed this habit's next occurrence
        // before the answer was even dispatched, so a failure at any point below
        // could not leave the alarm un-armed. Re-arming again here would only
        // duplicate work it has already done.
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
        // No re-arm here either — see [recordDone]: [handle] re-arms first, so the
        // next occurrence is already armed by the time this runs.
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

    /**
     * Logs the occurrences of [habitId] that its alarm already let pass with
     * nothing recorded — the "log missed alarms" half of Change B.
     *
     * Delegates to [HabitAlarmHistory.logMissedOccurrences], which owns the rule
     * (a slot is missed when its time has gone and it has no event at all) and
     * does its own guarding, so nothing here can affect the re-arm or the answer.
     */
    private fun logMissedOccurrences(context: Context, habitId: String, firedAlarmMinutes: Int?) {
        HabitAlarmHistory.logMissedOccurrences(
            context = context,
            habitId = habitId,
            beforeMinutes = firedAlarmMinutes,
        )
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
