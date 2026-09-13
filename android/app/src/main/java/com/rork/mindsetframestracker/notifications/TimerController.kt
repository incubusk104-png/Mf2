package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.util.Log
import com.rork.mindsetframestracker.data.ActiveTimer
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.data.TimerRepository
import com.rork.mindsetframestracker.data.TimerStatus

/**
 * Every user-driven mutation of the live timer, in one place.
 *
 * The screen, the notification's "Stop" action and the app's cold-start path
 * all go through here, which is what keeps the three pieces of state that must
 * never disagree — the persisted [ActiveTimer], the AlarmManager alarm and the
 * foreground service — in lockstep after every tap:
 *
 * | tap            | persisted state        | alarm                  | service |
 * |----------------|------------------------|------------------------|---------|
 * | start / resume | running, new segment   | armed at the deadline   | started |
 * | pause          | paused, banked elapsed | cancelled               | stopped |
 * | +5 min         | target extended        | re-armed at new deadline| started |
 * | stop           | cleared                | cancelled               | stopped |
 *
 * Each function is idempotent and safe to call with the app in any state (it
 * re-reads from disk rather than trusting a caller's copy), so a stale Compose
 * lambda or a re-delivered intent can't desynchronise anything.
 */
object TimerController {

    private const val TAG = "TimerController"

    /** Starts a brand-new run, replacing any previous one. */
    fun start(
        context: Context,
        kind: TimerKind,
        targetSeconds: Int,
        label: String,
        habitId: String? = null,
    ): ActiveTimer = TimerCompletion.start(context, kind, targetSeconds, label, habitId)

    /** Pauses the running timer, banking the elapsed segment. */
    fun pause(context: Context) {
        mutate(context, "pause") { timer, now ->
            if (timer.status != TimerStatus.RUNNING) timer else timer.pausedAt(now)
        }
    }

    /**
     * Resumes a paused timer. Re-arms the alarm from the *new* deadline and
     * restarts the service — without this, a timer resumed after a long pause
     * would show the right number in the UI but never ring.
     */
    fun resume(context: Context) {
        mutate(context, "resume") { timer, now ->
            if (timer.status == TimerStatus.RUNNING) timer else timer.resumedAt(now)
        }
    }

    /**
     * Adds [seconds] to a count-down target and immediately resumes.
     *
     * Note this deliberately produces a **new** event id: the event id is
     * derived from `(run id, kind, target)`, so extending a finished timer
     * legitimately earns its own single completion popup later — and the
     * already-shown popup for the previous target can never repeat.
     */
    fun extendAndResume(context: Context, seconds: Int = com.rork.mindsetframestracker.data.TIMER_EXTEND_SECONDS) {
        mutate(context, "extend") { timer, now ->
            if (!timer.hasTarget) timer else timer.extendedBy(seconds, now).resumedAt(now)
        }
    }

    /** Records a lap/split on the stopwatch. No-op for a count-down timer. */
    fun lap(context: Context) {
        mutate(context, "lap", rescheduleAlarm = false) { timer, now ->
            if (timer.kind != TimerKind.STOPWATCH) timer else timer.withSplitAt(now)
        }
    }

    /**
     * Stops and clears the run. Cancels the alarm and the pending one-shot
     * reminder so no stale notification can arrive after the user gave up, but
     * leaves any already-pending *completion* event alone — if the timer did
     * finish, the user still deserves to see the result once.
     */
    fun stop(context: Context) {
        val repo = TimerRepository(context)
        val timer = repo.loadActive()
        TimerAlarmScheduler.cancel(context, timer)
        repo.clearActive()
        TimerService.stop(context)
        Log.d(TAG, "Stopped timer ${timer?.id ?: "(none)"}")
    }

    /**
     * Acknowledges a completion event: marks it handled (so its popup can never
     * appear again, in this process or any future one), clears it from the
     * pending slot, and drops the one-shot follow-up reminder that was armed
     * for it.
     */
    fun acknowledgeEvent(context: Context, eventId: String): Boolean {
        val firstTime = TimerRepository(context).consumeEvent(eventId)
        TimerReminderReceiver.cancel(context)
        TimerNotifier.clearCompletion(context, eventId)
        return firstTime
    }

    /**
     * Shared read-modify-write. Re-reads the timer from disk first (never trusts
     * a caller's snapshot), applies [transform], persists, then realigns the
     * alarm and service with the new state.
     */
    private fun mutate(
        context: Context,
        operation: String,
        rescheduleAlarm: Boolean = true,
        transform: (ActiveTimer, Long) -> ActiveTimer,
    ) {
        runCatching {
            val repo = TimerRepository(context)
            val current = repo.loadActive() ?: return
            val now = System.currentTimeMillis()
            val updated = transform(current, now)
            if (updated == current) return
            repo.saveActive(updated)

            if (rescheduleAlarm) {
                when {
                    updated.status == TimerStatus.PAUSED -> {
                        TimerAlarmScheduler.cancel(context, updated)
                        TimerService.stop(context)
                    }
                    else -> {
                        TimerAlarmScheduler.schedule(context, updated)
                        TimerService.start(context)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Timer $operation failed", it) }
    }
}
