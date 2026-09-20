package com.rork.mindsetframestracker.data

import android.content.Context
import android.util.Log

/**
 * Whether a ringing alarm has **already been delivered to the user**, and the
 * one place that answers it.
 *
 * ## The bug this exists to close
 *
 * "When an alarm has already rung once, it must reset properly — right now it
 * does not reset after ringing."
 *
 * The ring dialog's "show this once" discipline lived entirely in
 * [com.rork.mindsetframestracker.notifications.HabitTimerRequests]: the request
 * is written at ring time and **consumed** the instant the host reads it. That
 * is correct for the common case, but it is not durable against the one case an
 * alarm app has to survive — the process dying mid-ring. If the ring is written
 * and the process is killed before the reader runs, the request stays on disk.
 * On the next launch the host reads it and raises a dialog for **an alarm that
 * rang in the past**, which is the "it did not reset" the user is reporting.
 *
 * So the acknowledgement is persisted separately, under the occurrence it
 * belongs to. The host consults it before raising a note, and a delivered ring
 * can never come back.
 *
 * ## Why the key is the (day, time) occurrence, and not the habit or the day
 *
 * Both simpler keys are wrong in a way users notice:
 *
 *  * **Per habit** — a habit set for 07:00 / 12:00 / 18:00 would show its dialog
 *    once and never again, so the afternoon and evening alarms would ring with
 *    no way to record anything. It also breaks "the dialog must appear when the
 *    alarm rings", which is the other half of the same request.
 *  * **Per day** — same failure, one dialog per habit per day.
 *
 * Keying on `(day, time)` — the same identity [alarmEventKey] and
 * [occurrenceKeyFor] already use for per-occurrence history — gives exactly the
 * wanted behaviour with no extra state: each of the day's alarms is delivered
 * once, a re-delivered ring for the same time is not a second delivery, and
 * tomorrow's 07:00 is a different key, so the gate reopens fully on schedule.
 *
 * ## Why it must be persisted at all
 *
 * The dialog can be raised while the app is backgrounded, while the process is
 * cold-starting from the alarm, or while the user is mid-way through something
 * else. Compose state survives none of those, and the request record itself is
 * deliberately consumed on first read — so there is nowhere else to remember
 * "this one already happened". This is the same durable-once discipline
 * [com.rork.mindsetframestracker.data.TimerRepository] uses for the timer
 * completion popup.
 *
 * ## Failure behaviour
 *
 * Every read and write is guarded. This runs on the **ring path**, where a throw
 * would take down the process at the exact moment the user's alarm should be
 * ringing — strictly worse than losing the record. A failed read yields "not
 * yet delivered", which at worst re-shows a dialog once; throwing costs the
 * ring.
 */
object AlarmRingState {

    private const val TAG = "AlarmRingState"
    private const val PREFS = "mindset_alarm_ring_state"
    private const val KEY_ACCEPTED = "accepted_occurrence"

    /**
     * Stands in for "this request carried no alarm time".
     *
     * `-1` rather than a real minute-of-day, so it can never collide with a
     * genuine 23:59 occurrence.
     */
    private const val NO_TIME = -1

    /** Separator matching [alarmEventKey]'s, so the keys read the same way. */
    private const val SEPARATOR = "@"

    /**
     * The identity of one delivered occurrence.
     *
     * [alarmMinutes] is null only for a request written by a build that predates
     * per-time alarms; those are keyed under [NO_TIME] so they are still
     * delivered exactly once instead of never being acknowledged at all.
     */
    fun occurrenceKey(dayKey: String, alarmMinutes: Int?): String =
        dayKey + SEPARATOR + (alarmMinutes ?: NO_TIME)

    /**
     * Whether [stored] is the acknowledgement for this exact occurrence.
     *
     * The decision is a pure function of its arguments so the whole point of the
     * gate — one delivery per occurrence, every occurrence still delivered — is
     * assertable in a plain JVM test, without a device or a SharedPreferences.
     */
    fun isAcceptedKey(stored: String?, dayKey: String, alarmMinutes: Int?): Boolean =
        stored != null && stored == occurrenceKey(dayKey, alarmMinutes)

    /** True when this occurrence has already been shown to the user today. */
    fun isAccepted(context: Context, alarmMinutes: Int?): Boolean =
        isAcceptedKey(read(context), Dates.todayKey(), alarmMinutes)

    /**
     * Records that the dialog for this occurrence has been delivered.
     *
     * Written when the dialog is **raised**, not when it is answered: whether the
     * user records something, taps "Not now", minimizes, or dismisses it some
     * other way, they have been shown it — and re-showing the same occurrence's
     * note is precisely the "it did not reset / it keeps coming back" symptom.
     */
    fun markAccepted(context: Context, alarmMinutes: Int?) =
        write(context, occurrenceKey(Dates.todayKey(), alarmMinutes))

    /**
     * Reopens the gate because a new ring is starting.
     *
     * Called by the ring itself at the moment the alarm reaches the user. It
     * **resets** the record rather than accumulating one, and that is what makes
     * the state self-healing: a habit whose alarm was armed, rang while the app
     * was being force-stopped, and is now ringing again is not left silently
     * suppressed by the previous attempt.
     */
    fun beginRing(context: Context) = clear(context)

    /** Forgets the acknowledgement — the alarm was stopped, or data was reset. */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_ACCEPTED).apply() }
            .onFailure { Log.w(TAG, "Could not clear the ring state", it) }
    }

    private fun read(context: Context): String? =
        runCatching { prefs(context).getString(KEY_ACCEPTED, null) }
            // A stored value of an unexpected type throws a ClassCastException
            // here, on the ring path. Degrading to "not yet delivered" costs one
            // extra dialog; throwing costs the ring.
            .onFailure { Log.w(TAG, "Could not read the ring state", it) }
            .getOrNull()

    private fun write(context: Context, value: String) {
        runCatching { prefs(context).edit().putString(KEY_ACCEPTED, value).apply() }
            .onFailure { Log.w(TAG, "Could not record the ring state", it) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
