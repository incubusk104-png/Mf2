package com.rork.mindsetframestracker.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which of a habit's alarm times an event with **no** time of its own belongs to.
 *
 * ## The gap this closes
 *
 * The ring path records what the alarm did ([HabitAlarmHistory.record]) with the
 * scheduled time carried on the reminder intent. But an *answer* that arrives
 * through a path which never saw that intent — an older arming, a notification
 * action rebuilt by the OS, a stop that raced the ring — reaches
 * `HabitAlarmHistory.markOutcome(habitId, scheduledMinutes = null, …)` with no
 * time at all, and the guard there (`scheduledMinutes == null` → return null)
 * drops it silently.
 *
 * That is the difference between the history the user asked for and a history
 * that quietly loses the occurrences they actually answered. Resolving the time
 * from the stored state means the occurrence is attributed to a real scheduled
 * time even when the caller cannot supply one, so the answer is **saved
 * automatically** rather than discarded.
 *
 * ## How a time is chosen, and why in this order
 *
 *  1. **The newest outstanding ring.** A `FIRED` event for this habit today with
 *     no answer yet is direct evidence of which alarm is being answered, and it
 *     is preferred because it is a *fact the app recorded* rather than an
 *     inference.
 *  2. **The most recently passed scheduled time.** With no recorded ring (a
 *     reboot wiped the alarm, the ring was blocked by a muted channel, the
 *     install predates the history) the honest reading is "the alarm whose
 *     moment has just gone by". Taking the *latest* such time — not the
 *     earliest — is what makes a 07:00/12:00/18:00 habit answer correctly at
 *     18:05 instead of being filed under the morning.
 *
 * Returns null only when the habit does not exist or has no alarm times at all,
 * where there is genuinely no occurrence to attach to.
 */
fun AppData.occurrenceToAnswer(
    habitId: String,
    atEpochMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Int? {
    val habit = habits.firstOrNull { it.id == habitId } ?: return null
    val times = habit.alarmMinutes
    if (times.isEmpty()) return null

    val today = Dates.todayKey()
    alarmEvents
        .filter {
            it.habitId == habitId &&
                it.dayKey == today &&
                it.outcome == AlarmEventOutcome.FIRED
        }
        .maxByOrNull { it.firedAtEpochMs }
        ?.let { return it.scheduledMinutes }

    val nowMinutes = minuteOfDay(atEpochMs, zone)
    return times.lastOrNull { it <= nowMinutes }
}

/**
 * Minutes past local midnight for an epoch instant — the inverse of the
 * `scheduledMinutes` field, so a wall-clock answer can be compared with a
 * scheduled time on the same scale.
 */
fun minuteOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/**
 * The times in [savedTimes] that the habit's stored schedule does not already
 * contain — what a save is actually *adding*.
 *
 * ## Why a save must not be droppable
 *
 * The picker's confirm path branches on whether it was opened from an existing
 * habit card: with one it calls `setHabitAlarmTimes`, without one it creates a
 * new habit. That leaves a hole for the flow the user asked for — *"after an
 * alarm has notified, set another alarm for the same habit at a different
 * time"*. Re-opening the picker on a habit that already exists goes down the
 * **update** branch, so a time added there is persisted against that habit
 * (correct). Exposing the genuinely-new times is what lets a caller confirm it
 * attached the save rather than losing it — the failure mode being a picker that
 * appears to accept a time and stores nothing.
 *
 * Times equal to the stored schedule are excluded so a re-save is a no-op rather
 * than a duplicate.
 */
fun unattachedAlarmTimes(
    savedTimes: List<Int>,
    storedTimes: List<Int>,
): List<Int> {
    val normalisedSaved = savedTimes.filter { it in 0..1439 }.distinct().sorted()
    val stored = storedTimes.filter { it in 0..1439 }.toSet()
    return normalisedSaved.filterNot { it in stored }
}

/**
 * True when this save adds at least one time the habit did not have.
 *
 * Stated as its own function because both the ViewModel and its test should read
 * the same rule — the alternative is each re-deriving it and disagreeing about
 * whether a save was a no-op.
 */
fun isAddingNewAlarmTime(savedTimes: List<Int>, storedTimes: List<Int>): Boolean =
    unattachedAlarmTimes(savedTimes, storedTimes).isNotEmpty()

/**
 * The occurrence a stop belongs to when the ringing alarm's own time was not
 * carried through to the receiver.
 *
 * [occurrenceToAnswer] looks only at *today*, which is right for an answer
 * arriving during the day but wrong for the one case that can legitimately
 * cross midnight: an alarm at 23:55 dismissed at 00:10. At 00:10 "today" is the
 * new day, the ring happened yesterday, so no ring is found and the dismissal is
 * dropped — the user answers an alarm and their history does not show it.
 *
 * So the search widens to yesterday before falling back to the schedule. Same
 * preference order as [occurrenceToAnswer] (a recorded ring beats an inference),
 * just over a two-day window.
 */
fun AppData.crossDayOccurrenceMinutes(
    habitId: String,
    atEpochMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Int? {
    val habit = habits.firstOrNull { it.id == habitId } ?: return null
    if (habit.alarmMinutes.isEmpty()) return null

    val today = Dates.todayKey()
    val yesterday = Dates.key(LocalDate.now().minusDays(1))
    alarmEvents
        .filter {
            it.habitId == habitId &&
                (it.dayKey == today || it.dayKey == yesterday) &&
                it.outcome == AlarmEventOutcome.FIRED
        }
        .maxByOrNull { it.firedAtEpochMs }
        ?.let { return it.scheduledMinutes }

    return occurrenceToAnswer(habitId, atEpochMs, zone)
        ?: habit.alarmMinutes.lastOrNull()
}
