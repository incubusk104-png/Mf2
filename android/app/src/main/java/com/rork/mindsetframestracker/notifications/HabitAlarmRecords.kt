package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.util.Log
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.HabitAlarmBehavior
import com.rork.mindsetframestracker.data.HabitLogEntry
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.alarmBehavior
import com.rork.mindsetframestracker.data.alarmMinutes
import com.rork.mindsetframestracker.data.hasAnsweredOccurrence
import com.rork.mindsetframestracker.data.occurrenceKeyFor
import com.rork.mindsetframestracker.data.trackingModeOrDefault
import java.util.UUID

/**
 * The headless half of habit recording — the one place a record is written when
 * there is **no UI running**.
 *
 * ## Why this exists
 *
 * Every record used to be written either by a Compose screen (`AppViewModel`)
 * or, at ring time, by a bare `markHabitDoneToday()` that set the check-in
 * boolean and nothing else. That left three holes this closes:
 *
 *  1. **A CHECK habit's alarm produced no log entry at all.** The day was marked
 *     done, so streaks and the heatmap were right, but "what did I actually do"
 *     had nothing behind it — the log existed only for habits the user opened a
 *     sheet for. `HabitTrackingSheet`'s CHECK button wrote an entry; dismissing
 *     the alarm did not. Same act, two different records depending on how the
 *     user got there.
 *  2. **A ring that fired while the app was closed could not be attributed to
 *     its occurrence.** With several alarms a day, a record that says only "this
 *     habit was done on Tuesday" cannot answer the question the user actually
 *     asked — which of the day's alarms did they answer.
 *  3. **Nothing could tell a double-fire from a second occurrence.** Without an
 *     occurrence key, a re-delivered intent and a genuine 18:00 ring look
 *     identical, so the safe-looking fix (write unconditionally) double-counts
 *     and the other safe-looking fix (write only once per day) silently drops
 *     every occurrence after the first.
 *
 * ## What decides whether a ring writes a record
 *
 * The habit's own [HabitAlarmBehavior], because that is exactly the question
 * "does the ring already know the answer?":
 *
 * | behavior        | ring-time record                                        |
 * |-----------------|---------------------------------------------------------|
 * | [ONE_TAP]       | **writes it** — the dismissal *is* the completion        |
 * | [MINIMAL_INPUT] | does not — the sheet owns the number/note, so writing here would double-count |
 * | [TOOL]          | does not — the timer/stopwatch owns the measurement      |
 *
 * That last point is the important one to keep in mind when editing this: a
 * COUNT habit is `MINIMAL_INPUT`, so writing "1 glass" here would be **added to**
 * the three the user then logs in the sheet, and a walk would get a record with
 * no duration alongside the timer's real one. So the ring writes for the one
 * behavior where there is nothing to ask — and for the rest it deliberately
 * writes nothing, because a record the user did not provide is a fabricated one.
 *
 * A JOURNAL habit is the same reasoning taken to its conclusion: no tap or ring
 * can invent a sentence, so a dismissal records nothing and the day stays
 * unmarked until the user writes. "No completion without a record" is honoured
 * by not marking the completion — not by inventing the record.
 */
object HabitAlarmRecords {

    private const val TAG = "HabitAlarmRecords"

    /**
     * Writes the record for a habit's alarm ringing at [alarmMinutes], when (and
     * only when) the ring itself is the record.
     *
     * Returns the entry written, or null when nothing should be written — the
     * behavior does not record at ring time, the occurrence was already answered,
     * or persistence failed. Never throws: this runs inside the alarm's own
     * receiver, where an escaping exception kills the process mid-ring.
     */
    fun recordRingOccurrence(context: Context, habitId: String, alarmMinutes: Int?): HabitLogEntry? =
        runCatching {
            val repo = MindsetRepository(context)
            val habit = repo.load().habits.firstOrNull { it.id == habitId } ?: return null

            // Only ONE_TAP: everything else has an input the user still has to
            // give, and recording on their behalf would either double-count
            // (COUNT) or fabricate (JOURNAL).
            if (habit.alarmBehavior != HabitAlarmBehavior.ONE_TAP) return null

            recordOccurrence(
                context = context,
                habitId = habitId,
                mode = habit.trackingModeOrDefault,
                alarmMinutes = alarmMinutes ?: habit.alarmMinutes.firstOrNull(),
                unit = habit.trackingUnit,
            )
        }.onFailure { Log.w(TAG, "Failed to record the ring-time occurrence for $habitId", it) }
            .getOrNull()

    /**
     * Records one occurrence of [habitId]: a [HabitLogEntry] carrying the real
     * day and the alarm time that produced it, plus the matching check-in.
     *
     * Idempotent per occurrence. The guard is *per occurrence*, not per day — an
     * 18:00 walk must still record after a 07:00 one — which is the whole point
     * of keying on `(dayKey, alarmMinutes)` rather than on the day alone.
     */
    fun recordOccurrence(
        context: Context,
        habitId: String,
        mode: HabitTrackingMode,
        alarmMinutes: Int?,
        title: String? = null,
        note: String? = null,
        durationSeconds: Int? = null,
        count: Int? = null,
        unit: String? = null,
        markDone: Boolean = true,
    ): HabitLogEntry? = runCatching {
        if (habitId.isBlank()) return null
        // Safety net for the "one poisoned key kills every sync" failure mode.
        //
        // `checkins.habit_id` is a uuid column and SupabaseSync.pushSnapshot()
        // abandons the whole push on the first failed upsert — so a single
        // non-uuid key (historically "diagnostic_test") silently blocks check-in,
        // settings, mood AND backup syncing, for good. A real habit id is a UUID,
        // so anything that will not parse as one is refused here, per row, rather
        // than being allowed to reach pushSnapshot().
        //
        // Callers must still guard the diagnostic id themselves (see
        // [HabitCheckInNotifier.DIAGNOSTIC_HABIT_ID] and
        // [AlarmRingActionReceiver]); this only contains the blast radius when one
        // does not.
        if (uuidOrNull(habitId) == null) {
            Log.w(TAG, "Refusing to record an occurrence for a non-UUID habit id")
            return null
        }
        val repo = MindsetRepository(context)
        val dayKey = Dates.todayKey()

        // Already answered this exact occurrence — a re-delivered intent, a
        // snooze that re-fired, or the user answering twice. Never a second
        // record: that is how a single walk becomes "2 walks today".
        if (alarmMinutes != null && repo.load().hasAnsweredOccurrence(habitId, dayKey, alarmMinutes)) {
            Log.d(TAG, "Occurrence $dayKey@$alarmMinutes for $habitId already recorded")
            return null
        }

        val entry = HabitLogEntry(
            habitId = habitId,
            dayKey = dayKey,
            mode = mode,
            occurrenceKey = alarmMinutes?.let { occurrenceKeyFor(dayKey, it) },
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            durationSeconds = durationSeconds?.takeIf { it > 0 },
            count = count?.takeIf { it > 0 },
            unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            recordedAtEpochMs = System.currentTimeMillis(),
        )
        val saved = repo.saveHabitLog(entry) ?: return null
        if (markDone) {
            runCatching { repo.markHabitDoneToday(habitId) }
                .onFailure { Log.w(TAG, "Saved the record for $habitId but could not mark it done", it) }
        }
        saved
    }.onFailure { Log.w(TAG, "Failed to record an occurrence for $habitId", it) }
        .getOrNull()

    /**
     * The alarm times that are **outstanding** for [habitId] today — fired, but
     * with no record answering them yet.
     *
     * Lets the UI show the honest thing ("07:00 logged, 12:00 not yet") rather
     * than implying a habit is fully handled because it has been done once
     * today. Pure read; safe to call from composition.
     */
    fun outstandingOccurrences(context: Context, habitId: String): List<Int> = runCatching {
        val data = MindsetRepository(context).load()
        val habit = data.habits.firstOrNull { it.id == habitId } ?: return emptyList()
        val today = Dates.todayKey()
        habit.alarmMinutes.filterNot { data.hasAnsweredOccurrence(habitId, today, it) }
    }.getOrDefault(emptyList())

    /**
     * [value] as a [UUID], or null when it is not one.
     *
     * Parsing with the same grammar Postgres uses for a `uuid` column is
     * deliberately the only test applied: it accepts exactly what the `checkins`
     * table will accept, and nothing else.
     */
    private fun uuidOrNull(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()
}
