package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * On-device model for the two live timers this app now supports:
 *
 *  - [TimerKind.WALK_TIMER] \u2014 a **count-down** timer with a target duration
 *    (\"walk for 20 minutes\"). When it reaches zero it fires exactly one
 *    completion event.
 *  - [TimerKind.STOPWATCH] \u2014 a **count-up** stopwatch that simply measures
 *    until the user stops it. It reaches an *optional* target instead of a
 *    fixed one; if a target is set it fires exactly one completion event when
 *    that target is crossed, but **keeps running** (a stopwatch that stops
 *    itself is not a stopwatch). If no target is set it never fires.
 *
 * ## Why elapsed time is stored as (accumulatedSeconds + segmentStartEpochMs)
 *
 * A timer has to stay correct while the app process is dead \u2014 an alarm fires
 * in a fresh process, the user reboots the phone, or an OEM battery manager
 * kills the app mid-walk. Storing a ticking counter would freeze the moment
 * nothing is running, so instead:
 *
 *  - [elapsedSeconds] = seconds accumulated by all *finished* running segments
 *  - [startedAtEpochMs] = wall-clock instant the *current* running segment began
 *    (0 while [TimerStatus.PAUSED])
 *
 * Every value the UI or the alarm needs is therefore derived from the wall
 * clock at read time ([elapsedAt]), which cannot drift or freeze, and pausing
 * / resuming is a two-field upsert instead of a running coroutine.
 */
@Serializable
enum class TimerKind { WALK_TIMER, STOPWATCH }

@Serializable
enum class TimerStatus { RUNNING, PAUSED }

/** Shortest walk-timer target we accept (1 minute). */
const val MIN_TIMER_TARGET_SECONDS = 60

/** Longest target we accept (8 hours) \u2014 keeps a stuck timer from arming forever. */
const val MAX_TIMER_TARGET_SECONDS = 8 * 60 * 60

/** How much \"Add 5 min\" (and the ringing screen's +5) extends a walk timer by. */
const val TIMER_EXTEND_SECONDS = 5 * 60

/** Default walk-timer target when a habit has no per-habit override (20 minutes). */
const val DEFAULT_WALK_TARGET_SECONDS = 20 * 60

/** Quick-pick targets offered on the walk-timer screen, in minutes. */
val TIMER_PRESET_MINUTES: List<Int> = listOf(10, 15, 20, 30, 45, 60)

/** A lap/split taken on the stopwatch, stored as total elapsed seconds. */
@Serializable
data class TimerSplit(val label: Int, val elapsedSeconds: Int)

/**
 * A timer run that is either running right now or paused and waiting to be
 * resumed. Persisted in [TimerRepository] so it survives process death,
 * navigation and app restarts.
 */
@Serializable
data class ActiveTimer(
    val id: String = UUID.randomUUID().toString(),
    val kind: TimerKind = TimerKind.WALK_TIMER,
    val label: String = "",
    /** Optional habit this timer completes when it finishes. */
    val habitId: String? = null,
    /** Count-down target (0 = open-ended, i.e. plain stopwatch with no goal). */
    val targetSeconds: Int = 0,
    /** Seconds accumulated by *finished* running segments (see class doc). */
    val elapsedSeconds: Int = 0,
    /** Epoch millis the current running segment began; 0 while paused. */
    val startedAtEpochMs: Long = 0L,
    /** Epoch millis of the last state write \u2014 used for diagnostics/staleness only. */
    val lastUpdatedAtEpochMs: Long = 0L,
    val status: TimerStatus = TimerStatus.RUNNING,
    val splits: List<TimerSplit> = emptyList(),
    /** True once this run has already produced its single completion event. */
    val completionFired: Boolean = false,
) {
    /** A count-down timer (walk timer, or a stopwatch with a goal). */
    val hasTarget: Boolean get() = targetSeconds > 0

    /** True on a count-up stopwatch with no goal \u2014 it can never \"finish\" on its own. */
    val isOpenEnded: Boolean get() = targetSeconds <= 0

    /**
     * Identity of the ONE completion event this run may ever produce. Baked
     * from the run id, kind and target, so it is stable across restarts and
     * across every entry point that might try to fire it (the AlarmManager
     * receiver, the foreground service, an in-app tick).
     */
    val eventId: String get() = "$id:${kind.name}:$targetSeconds"

    /** Total elapsed seconds at wall-clock [nowMs] \u2014 never freezes while running. */
    fun elapsedAt(nowMs: Long): Int {
        val live = if (status == TimerStatus.RUNNING && startedAtEpochMs > 0L) {
            ((nowMs - startedAtEpochMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        return (elapsedSeconds + live).toInt()
    }

    /** Seconds left on a count-down timer (0 when expired or open-ended). */
    fun remainingSecondsAt(nowMs: Long): Int {
        if (!hasTarget) return 0
        return (targetSeconds - elapsedAt(nowMs)).coerceAtLeast(0)
    }

    /** 0f..1f progress on a count-down timer; null for an open-ended stopwatch. */
    fun progressAt(nowMs: Long): Float? {
        if (!hasTarget) return null
        return (elapsedAt(nowMs).toFloat() / targetSeconds.toFloat()).coerceIn(0f, 1f)
    }

    /** True once a count-down timer has reached (or passed) its target. */
    fun isExpiredAt(nowMs: Long): Boolean = hasTarget && elapsedAt(nowMs) >= targetSeconds

    /**
     * Absolute wall-clock instant the AlarmManager alarm should fire, or null
     * when there is nothing to arm (paused, open-ended, or already fired).
     */
    fun alarmDeadlineEpochMs(): Long? {
        if (status != TimerStatus.RUNNING || !hasTarget || completionFired) return null
        val secondsLeft = (targetSeconds - elapsedSeconds).coerceAtLeast(0)
        return startedAtEpochMs + secondsLeft * 1000L
    }

    /** Immutable transition: pause now, banking the live segment. */
    fun pausedAt(nowMs: Long): ActiveTimer {
        if (status == TimerStatus.PAUSED) return this
        return copy(
            elapsedSeconds = elapsedAt(nowMs),
            startedAtEpochMs = 0L,
            status = TimerStatus.PAUSED,
            lastUpdatedAtEpochMs = nowMs,
        )
    }

    /** Immutable transition: resume now, starting a fresh running segment. */
    fun resumedAt(nowMs: Long): ActiveTimer {
        if (status == TimerStatus.RUNNING) return this
        return copy(
            startedAtEpochMs = nowMs,
            status = TimerStatus.RUNNING,
            lastUpdatedAtEpochMs = nowMs,
        )
    }

    /** Immutable transition: add [seconds] to the target (\"Add 5 min\"). */
    fun extendedBy(seconds: Int, nowMs: Long): ActiveTimer {
        val newTarget = (targetSeconds + seconds).coerceAtMost(MAX_TIMER_TARGET_SECONDS)
        return copy(
            targetSeconds = newTarget,
            startedAtEpochMs = if (status == TimerStatus.RUNNING) nowMs else 0L,
            elapsedSeconds = if (status == TimerStatus.RUNNING) elapsedAt(nowMs) else elapsedSeconds,
            lastUpdatedAtEpochMs = nowMs,
            completionFired = false,
        )
    }

    /** Immutable transition: record a lap/split at [nowMs]. */
    fun withSplitAt(nowMs: Long): ActiveTimer = copy(
        splits = splits + TimerSplit(label = splits.size + 1, elapsedSeconds = elapsedAt(nowMs)),
    )
}

/**
 * The single completion event a finished timer run produces. Persisted so an
 * event that fired while the app was closed still surfaces **exactly once**
 * the next time the user opens the app \u2014 never twice, and never zero times.
 */
@Serializable
data class TimerCompletionEvent(
    val eventId: String,
    val kind: TimerKind,
    val label: String,
    val habitId: String? = null,
    val targetSeconds: Int = 0,
    val elapsedSeconds: Int = 0,
    val firedAtEpochMs: Long = 0L,
)

/** mm:ss, or h:mm:ss once the timer passes an hour. */
fun formatTimerDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}
