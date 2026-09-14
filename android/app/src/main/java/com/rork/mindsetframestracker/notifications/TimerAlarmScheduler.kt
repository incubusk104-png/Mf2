package com.rork.mindsetframestracker.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.ActiveTimer
import com.rork.mindsetframestracker.data.TimerRepository

/**
 * Arms and cancels the single AlarmManager alarm that backs a running timer.
 *
 * Unlike the recurring habit reminders, a timer needs **one** wake-up at one
 * absolute instant, so there is no re-arming chain here — the timer's own
 * state is the schedule. When the user pauses, extends or stops the timer the
 * old alarm is cancelled and (for pause/extend) a fresh one is armed from the
 * new deadline, which is why every mutation in the UI funnels through
 * [reschedule].
 */
object TimerAlarmScheduler {

    private const val TAG = "TimerAlarmScheduler"

    const val ACTION_TIMER_DEADLINE = "com.rork.mindsetframestracker.TIMER_DEADLINE"

    /**
     * A single fixed request code: at most one timer runs at a time, so a
     * second arm is always a *replacement*, never an additional alarm. Using a
     * constant also means a stale alarm can never be left behind needing its
     * own id to cancel.
     */
    private const val REQUEST_CODE = 7100

    /** Arms (or re-arms) the alarm for [timer]'s deadline. No-op when not due. */
    fun schedule(context: Context, timer: ActiveTimer) {
        val deadline = timer.alarmDeadlineEpochMs()
        if (deadline == null) {
            // Paused, open-ended stopwatch, or already fired: nothing to arm.
            cancel(context, timer)
            return
        }

        val pendingIntent = AlarmScheduler.broadcastIntent(
            context,
            REQUEST_CODE,
            deadlineIntent(context, timer, deadline),
        ) ?: return

        val precision = AlarmScheduler.schedule(
            context = context,
            triggerAtMillis = deadline,
            pendingIntent = pendingIntent,
            wakeUp = true,
            // The user is actively waiting on this one (a timer they are on, a
            // stopwatch they are watching) — it must survive Doze, so the
            // allowWhileIdle fallbacks stay enabled.
            allowWhileIdle = true,
        )

        Log.i(TAG, "Timer ${timer.kind} armed at $deadline (precision=$precision)")
    }

    /**
     * Re-arms from the timer's *current* persisted state. Call after any
     * mutation (start, pause, resume, extend, stop) so the alarm always matches
     * what the UI is showing.
     */
    fun reschedule(context: Context, timer: ActiveTimer?) {
        val current = timer ?: TimerRepository(context).loadActive()
        if (current == null) {
            cancelByIntent(context)
            return
        }
        schedule(context, current)
    }

    /** Cancels whatever timer alarm is currently armed. */
    fun cancel(context: Context, timer: ActiveTimer?) {
        cancelByIntent(context)
    }

    private fun cancelByIntent(context: Context) {
        // FLAG_NO_CREATE so cancel never fabricates a PendingIntent; extras are
        // irrelevant to equality (filterEquals ignores them) but the action is
        // not, so it must match what schedule() used.
        val existing = AlarmScheduler.broadcastIntent(
            context = context,
            requestCode = REQUEST_CODE,
            intent = Intent(context, TimerAlarmReceiver::class.java).apply {
                action = ACTION_TIMER_DEADLINE
            },
            createIfMissing = false,
        )
        AlarmScheduler.cancel(context, existing)
    }

    private fun deadlineIntent(context: Context, timer: ActiveTimer, deadline: Long): Intent =
        Intent(context, TimerAlarmReceiver::class.java).apply {
            action = ACTION_TIMER_DEADLINE
            putExtra(TimerAlarmReceiver.EXTRA_TIMER_ID, timer.id)
            putExtra(TimerAlarmReceiver.EXTRA_DEADLINE, deadline)
        }
}
