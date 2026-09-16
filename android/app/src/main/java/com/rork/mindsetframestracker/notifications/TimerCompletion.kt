package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.util.Log
import com.rork.mindsetframestracker.data.ActiveTimer
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.HabitLogEntry
import com.rork.mindsetframestracker.data.pendingOccurrenceMinutes
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.TimerCompletionEvent
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.data.TimerRepository
import com.rork.mindsetframestracker.data.TimerStatus
import com.rork.mindsetframestracker.data.trackingModeOrDefault

/**
 * The **single** funnel through which a timer's completion happens.
 *
 * Every entry point that can notice a finished timer \u2014 the AlarmManager
 * broadcast, the foreground service's tick loop, the in-app UI tick, and a cold
 * app start that discovers an expired timer \u2014 calls exactly this function.
 *
 * That single-entry design is what makes the \"pop up exactly once\" requirement
 * hold under real conditions. The sequence is:
 *
 * 1. Re-read the timer from disk (never trust the caller's copy \u2014 another
 *    process may have already stopped it).
 * 2. Bail out if it is not genuinely due.
 * 3. **[TimerRepository.recordCompletion]** \u2014 the atomic gate. The first caller
 *    to get here for a given `eventId` wins; every other caller is told the
 *    event was already recorded and returns without alerting.
 * 4. Only the winner posts the alarm-grade notification and marks the habit
 *    done. The *popup itself* is deliberately **not** shown here: it is owned
 *    by the UI, which shows it against the persisted pending event. An event
 *    that fires with the app closed therefore waits in the pending slot and
 *    appears the first time the user opens the app \u2014 once, and only once,
 *    because showing it consumes it.
 */
object TimerCompletion {

    private const val TAG = "TimerCompletion"

    /**
     * Records and announces a finished timer if (and only if) it is due and
     * nobody else has claimed it yet. Returns the event that was fired, or null
     * when this call was a no-op.
     *
     * @param stopTimer when true, the timer is left in the repository as
     *   finished-but-handled so the UI shows it as complete rather than
     *   silently deleting the user's result.
     */
    fun fire(context: Context, timer: ActiveTimer, stopTimer: Boolean = true): TimerCompletionEvent? {
        val repo = TimerRepository(context)

        // Re-read from disk: the UI, the service and the alarm can all race.
        val current = repo.loadActive()
        val source = current ?: timer

        if (source.id != timer.id) {
            // A different run has since started \u2014 this stale event is obsolete.
            Log.d(TAG, "Ignoring completion for stale timer ${timer.id} (active is ${source.id})")
            return null
        }
        if (!source.isExpiredAt(System.currentTimeMillis())) return null

        val event = TimerCompletionEvent(
            eventId = source.eventId,
            runId = source.id,
            kind = source.kind,
            label = source.label,
            habitId = source.habitId,
            targetSeconds = source.targetSeconds,
            elapsedSeconds = source.elapsedAt(System.currentTimeMillis()),
            firedAtEpochMs = System.currentTimeMillis(),
        )

        return when (val outcome = repo.recordCompletion(event)) {
            is TimerRepository.RecordOutcome.AlreadyRecorded -> {
                Log.d(TAG, "Completion ${event.eventId} already recorded \u2014 not alerting again")
                null
            }
            is TimerRepository.RecordOutcome.Rejected -> {
                // No started run behind this event \u2014 nothing the user did, so
                // nothing to announce and nothing to pop.
                Log.d(TAG, "Completion ${event.eventId} rejected \u2014 run ${event.runId} was never started")
                null
            }
            is TimerRepository.RecordOutcome.Recorded -> {
                announce(context, outcome.event)
                if (stopTimer) {
                    repo.saveActive(
                        source.copy(
                            completionFired = true,
                            status = TimerStatus.PAUSED,
                            elapsedSeconds = source.elapsedAt(System.currentTimeMillis()),
                            startedAtEpochMs = 0L,
                            lastUpdatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    TimerService.stop(context)
                }
                outcome.event
            }
        }
    }

    private fun announce(context: Context, event: TimerCompletionEvent) {
        TimerNotifier.postCompletion(context, event)

        // Arm the single follow-up nudge for this event. It is armed now, while
        // the event is still pending, but only ever *posts* if the user has not
        // opened the popup by then, and only once for the event's lifetime.
        TimerReminderReceiver.schedule(context, event.eventId)

        // Mark the habit done the moment the timer actually completes \u2014 this is
        // the same convention the habit reminders use (the action is the record,
        // not the mere setting of a time). Best-effort: a persistence hiccup must
        // not suppress the alert the user is about to see and hear.
        val habitId = event.habitId
        if (!habitId.isNullOrBlank()) {
            runCatching { MindsetRepository(context).markHabitDoneToday(habitId) }
                .onFailure { Log.w(TAG, "Failed to record habit $habitId from timer completion", it) }

            // ── Record WHAT was actually done against the habit ────────────
            // The check-in above is the boolean streaks read; this is the
            // payload. Without it a finished walk contributed nothing but a
            // tick, and the habit's own tracking tool (stopwatch or timer) had
            // no lasting result — the user cannot see how long they walked, and
            // a JOURNAL/COUNT habit finished through an alarm recorded nothing
            // at all. Written from here because this funnel is the single place
            // a completion is known to be genuine and once-only; the habit's
            // mode is read back so the entry is shaped by the habit, not by
            // whatever the timer happened to be.
            //
            // ## Attributed to the occurrence, not just the day
            //
            // A habit can ring several times a day, and a stopwatch started from
            // the 07:00 sheet may not be stopped until much later. Recording it
            // day-scoped would erase which ring it belonged to, so the record is
            // keyed to the habit's outstanding alarm time via
            // [Habit.pendingOccurrenceMinutes] and the write goes through
            // [HabitAlarmRecords.recordOccurrence] — the same per-occurrence
            // funnel the ring uses, so both paths honour the same uniqueness
            // rule and neither can produce a duplicate for one occurrence.
            //
            // The measured duration is carried through in full. It used to be
            // discarded here, which is why a finished walk showed a tick but no
            // minutes.
            runCatching {
                val repo = MindsetRepository(context)
                val habit = repo.load().habits.firstOrNull { it.id == habitId }
                val mode = when {
                    habit == null -> if (event.kind == TimerKind.TIMER) {
                        HabitTrackingMode.TIMER
                    } else {
                        HabitTrackingMode.STOPWATCH
                    }
                    else -> habit.trackingModeOrDefault
                }
                val nowMinutes = runCatching { Dates.nowMinutes() }.getOrNull()
                HabitAlarmRecords.recordOccurrence(
                    context = context,
                    habitId = habitId,
                    mode = mode,
                    alarmMinutes = if (habit != null && nowMinutes != null) {
                        habit.pendingOccurrenceMinutes(nowMinutes)
                    } else {
                        null
                    },
                    durationSeconds = event.elapsedSeconds.takeIf { it > 0 },
                    unit = habit?.trackingUnit,
                )
            }.onFailure { Log.w(TAG, "Failed to record the tracked duration for $habitId", it) }

            // ── Auto-sync the activity from the connected app ──
            // The timer/stopwatch the user just finished IS the activity, but
            // the numbers that count for the habit (steps, distance) live in
            // Strava / Health Connect / Polar. Leaving a one-shot request here
            // — rather than syncing inline — keeps this completion path free of
            // network and OAuth work: a headless alarm-time completion has no
            // UI to consent from and no guarantee the tokens are fresh. The
            // Habits screen picks the request up on next resume and pulls the
            // data for that habit.
            // ── Capture what the user ACTUALLY did ───────────────────────────
            // The timer/stopwatch run that just ended IS the activity, so this is
            // the one moment a real measurement can be attributed to this habit.
            // Reads Health Connect over the session window and records the metrics
            // that are genuinely present — duration, steps, distance, calories,
            // heart rate — so the habit stops being a bare label.
            //
            // Fire-and-forget on the monitor's own IO scope, including the habit
            // lookup: this runs inside a headless alarm-time process, so it must
            // neither block nor throw into the completion path.
            runCatching {
                com.rork.mindsetframestracker.integrations.ActivityMonitor
                    .captureForHabit(context, habitId)
            }.onFailure {
                Log.w(TAG, "Failed to dispatch activity capture for $habitId", it)
            }

            runCatching { HabitTimerRequests.requestAutoSync(context, habitId) }
                .onFailure { Log.w(TAG, "Failed to queue activity sync for habit $habitId", it) }
        }
    }

    /**
     * Cold-start repair: called from the Application's `onCreate`.
     *
     * Covers the one case no receiver can: the app was force-stopped (so
     * neither the alarm nor the service got to run) and the user reopens it
     * *after* the timer expired. The event is recorded here so the same
     * one-time popup appears \u2014 after which it is consumed like any other.
     */
    fun reconcileOnColdStart(context: Context) {
        runCatching {
            val repo = TimerRepository(context)
            val active = repo.loadActive() ?: return
            if (active.status == TimerStatus.RUNNING && !active.isExpiredAt(System.currentTimeMillis())) {
                // Genuinely still counting: re-arm the alarm (it may have been
                // wiped by a force-stop) and bring the service back up.
                TimerAlarmScheduler.schedule(context, active)
                TimerService.start(context)
                return
            }
            if (active.isExpiredAt(System.currentTimeMillis()) && !active.completionFired) {
                fire(context, active)
            }
        }.onFailure { Log.w(TAG, "Cold-start timer reconcile failed", it) }
    }

    /**
     * Launches a brand-new run. Cancels any previous one first so two timers can
     * never race each other's events.
     */
    fun start(
        context: Context,
        kind: TimerKind,
        targetSeconds: Int,
        label: String,
        habitId: String? = null,
    ): ActiveTimer {
        val repo = TimerRepository(context)
        repo.loadActive()?.let { TimerAlarmScheduler.cancel(context, it) }

        val now = System.currentTimeMillis()
        val timer = ActiveTimer(
            kind = kind,
            label = label,
            habitId = habitId,
            targetSeconds = targetSeconds.coerceAtLeast(0),
            elapsedSeconds = 0,
            startedAtEpochMs = now,
            lastUpdatedAtEpochMs = now,
            status = TimerStatus.RUNNING,
        )
        repo.saveActive(timer)
        // Record the session BEFORE anything can complete. This is what lets the
        // popup prove later that the run it is reporting was really started by
        // the user (see TimerRepository.wasRunStarted) \u2014 a completion can
        // never outrun its own start.
        repo.recordRunStarted(timer.id)
        TimerAlarmScheduler.schedule(context, timer)
        TimerService.start(context)
        return timer
    }
}
