package com.rork.mindsetframestracker.data

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The one authority on **which days** a habit's alarm rings, and **when its next
 * occurrence is**.
 *
 * ## Why this exists
 *
 * Day-of-week scheduling used to live entirely inside
 * `HabitAlarmScheduler.nextTriggerMillis`, which had a branch that made the
 * per-day selection unreachable from the UI:
 *
 * ```kotlin
 * if (repeatDaysMask == REPEAT_ONCE || repeatDaysMask == REPEAT_DAILY) {
 *     return cal.timeInMillis          // "next occurrence of this time"
 * }
 * ```
 *
 * `REPEAT_ONCE` is mask **0**, and 0 is also what you get from the day chips by
 * deselecting every day — which is trivially reachable, because the chips only
 * *toggle* a bit and nothing stops the user clearing the last one. So a user who
 * tapped Mon, then un-tapped it, landed on `repeatDaysMask = 0`, the scheduler
 * took the "next occurrence of this time" branch, and the alarm silently became
 * a **daily** alarm that ignored the day selection entirely. That is the
 * reported "I can't set alarms day by day": the day chips appeared to do
 * nothing, because the value they produced was intercepted before the per-day
 * logic could run.
 *
 * The fix is twofold, and both halves matter:
 *
 *  1. The picker can no longer *produce* a mask that means "no repeat" as a
 *     side effect of deselecting days (see `RepeatSelector`), and the scheduler
 *     refuses to arm a `REPEAT_ONCE` habit except on the one day it is still
 *     ahead of.
 *  2. This function handles every mask through **one** code path with no
 *     special-cased shortcut, so a value the UI cannot produce today still
 *     behaves correctly if an older build or a restored cloud row supplies it.
 *
 * ## Pure and injectable on purpose
 *
 * `now` and `zone` are parameters rather than reads of the device clock. That is
 * what makes "07:00 on weekdays rolls to Monday from Friday evening" a fact a
 * test can assert instead of something that can only be observed by waiting.
 *
 * ## DST is handled by the platform
 *
 * The result is a [LocalDateTime] and the caller attaches the zone. Resolving a
 * `LocalDateTime` through a `ZoneId` is what `java.time` is for: a time that does
 * not exist (the spring-forward gap) is shifted forward by the gap, and an
 * ambiguous time (the autumn overlap) resolves to the earlier offset. Both match
 * what the OS clock does, so an app alarm and the system alarm agree.
 */
object HabitRepeat {

    /** 7-bit mask with every day enabled — identical to [REPEAT_DAILY]. */
    const val EVERY_DAY: Int = REPEAT_DAILY

    /** Mask with no day enabled — identical to [REPEAT_ONCE] ("fire once"). */
    const val NO_DAYS: Int = REPEAT_ONCE

    /**
     * The mask for a set of days.
     *
     * Returning [NO_DAYS] for an empty [days] is deliberate and load-bearing:
     * "no days selected" and "fire once" are the same value in this model, which
     * is exactly why the UI must never produce that state by accident. Callers
     * that mean "no repeat" say so explicitly.
     */
    fun maskOf(days: Set<DayOfWeek>): Int =
        days.fold(0) { mask, day -> mask or bitValueFor(day) }

    /** The days a mask enables, Monday-first — the order the picker shows. */
    fun daysOf(mask: Int): List<DayOfWeek> = MONDAY_FIRST.filter { allows(mask, it) }

    /**
     * This day's bit **position** within the mask: Monday = 0 … Sunday = 6.
     *
     * A position, not a value — use [bitValueFor] to test or set the bit itself.
     * Conflating the two is a bug this file has already had once: see
     * [bitValueFor].
     */
    fun bitFor(day: DayOfWeek): Int = day.value - 1

    /**
     * This day's bit **value** in the mask: Monday = `0b0000001` … Sunday =
     * `0b1000000`.
     *
     * ## Why this is separate from [bitFor], and why that matters
     *
     * `allows` originally read `mask and bitFor(day) != 0` — ANDing the mask
     * against the day's *position*. That is only correct for Monday and Sunday:
     * Monday is position 0, so `anything and 0 == 0` and Monday was **never**
     * enabled under any mask; Sunday is position 6, so `127 and 6 == 6` and Sunday
     * appeared enabled under almost every mask. `0b0011111` (weekdays) against
     * Thursday (position 3) gave `31 and 3 == 3 == true` — right answer, wrong
     * reason, and only by coincidence.
     *
     * The net effect before the fix was that `maskOf`/`daysOf`/`allows` did not
     * agree with the constants the rest of the app already used, so a weekday
     * habit's next trigger could be null and its day selection meaningless. The
     * unit tests caught it; this comment and the separate function are so a
     * future edit cannot reintroduce it by treating the position as the value.
     */
    private fun bitValueFor(day: DayOfWeek): Int = 1 shl bitFor(day)

    /** True when [mask] enables [day]. [NO_DAYS] enables nothing. */
    fun allows(mask: Int, day: DayOfWeek): Boolean = mask and bitValueFor(day) != 0

    /**
     * The next moment this alarm should ring, or **null** when it should not be
     * armed at all.
     *
     * Null is returned for exactly one case: a one-shot ([NO_DAYS]) alarm whose
     * time has already passed today. Arming it would fire it tomorrow, which is
     * the opposite of "once". Every other mask is guaranteed to produce a result,
     * because at least one day is enabled and the search covers a full week.
     *
     * @param minutesFromMidnight the alarm time, 0..1439.
     * @param repeatDaysMask the habit's [Habit.repeatDaysMask].
     * @param now the current local date-time.
     * @param zone the zone to resolve the result in — needed because a local
     *   wall-clock time only becomes an instant once a zone is attached, and a
     *   DST transition changes that conversion.
     */
    fun nextTrigger(
        minutesFromMidnight: Int,
        repeatDaysMask: Int,
        now: LocalDateTime,
        zone: ZoneId,
    ): LocalDateTime? {
        val minutes = minutesFromMidnight.coerceIn(0, MINUTES_IN_DAY - 1)
        val time = LocalTime.of(minutes / 60, minutes % 60)

        if (repeatDaysMask == NO_DAYS) {
            // Fire once, then disarm: today if it is still ahead, otherwise
            // nothing. NOT tomorrow — that would make a one-shot alarm repeat.
            val today = now.toLocalDate().atTime(time)
            return if (today.isAfter(now)) today else null
        }

        // One pass, every mask. `offset` 0 is today and must be strictly ahead;
        // later offsets are whole days we can take as-is.
        for (offset in 0..DAYS_IN_WEEK) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (!allows(repeatDaysMask, day.dayOfWeek)) continue
            val candidate = day.atTime(time)
            if (offset == 0 && !candidate.isAfter(now)) continue
            return candidate
        }
        // Unreachable for any mask with a day set: 7 consecutive days contain
        // every day of the week. Returning null rather than a wrong day keeps the
        // failure honest — the caller logs it instead of arming a bogus alarm.
        return null
    }

    /**
     * The next trigger as epoch millis, or null when it must not be armed.
     * The thin `java.time` → instant conversion the Android layer needs.
     */
    fun nextTriggerMillis(
        minutesFromMidnight: Int,
        repeatDaysMask: Int,
        now: LocalDateTime,
        zone: ZoneId,
    ): Long? = nextTrigger(minutesFromMidnight, repeatDaysMask, now, zone)
        ?.atZone(zone)
        ?.toInstant()
        ?.toEpochMilli()

    /**
     * The next time **any** of [times] fires, given one mask shared by all of
     * them — the honest answer to "when will this habit next remind me?", which
     * the reliability check in the UI needs.
     *
     * Each time is computed independently and the earliest wins, because that is
     * what actually happens: a 07:00/12:00/18:00 habit's next ring is whichever
     * of the three comes first, not a single composite.
     */
    fun nextOfAll(
        times: List<Int>,
        repeatDaysMask: Int,
        now: LocalDateTime,
        zone: ZoneId,
    ): LocalDateTime? = times
        .mapNotNull { nextTrigger(it, repeatDaysMask, now, zone) }
        .minOrNull()

    /**
     * True when this habit has a real repeating schedule — at least one day
     * enabled. False for [REPEAT_ONCE] and for a habit with no alarms at all.
     *
     * Used by the scheduler to decide whether a fired alarm should be re-armed:
     * a one-shot deliberately is not, everything else is.
     */
    fun isRepeating(repeatDaysMask: Int): Boolean = repeatDaysMask != NO_DAYS

    /**
     * A short, unambiguous description of a mask, for the alarm dialog's summary
     * line and the confirmation snackbar.
     *
     * "Every day" / "Weekdays" / "Weekends" name the three presets the way users
     * think of them; anything else lists the actual days. The old UI rendered
     * `REPEAT_WEEKDAYS` and an arbitrary 5-day mask as the same phrase ("on
     * weekdays"), which is how a custom Mon/Tue/Wed selection came to be
     * described as something it was not.
     *
     * @param dayNames names for Monday..Sunday, so the caller controls
     *   localisation. Defaults to English three-letter labels — deliberately
     *   three letters, never single letters: "T" and "S" each name two days,
     *   which is what made the old day chips ambiguous to read.
     */
    fun describe(
        repeatDaysMask: Int,
        dayNames: List<String> = DEFAULT_DAY_NAMES,
    ): String {
        val mask = repeatDaysMask
        return when {
            mask == REPEAT_ONCE -> "once only"
            mask == EVERY_DAY -> "every day"
            mask == REPEAT_WEEKDAYS -> "on weekdays"
            mask == REPEAT_WEEKENDS -> "on weekends"
            mask == 0 -> "never"
            else -> daysOf(mask).joinToString(", ") { dayNames[bitFor(it)] }
        }
    }

    /** "Mon Tue Wed …" — the day names in Monday-first order. */
    val DEFAULT_DAY_NAMES: List<String> =
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Monday-first, matching the mask's bit order and every picker in the app. */
    val MONDAY_FIRST: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )

    private const val MINUTES_IN_DAY = 24 * 60
    private const val DAYS_IN_WEEK = 7
}

/**
 * The habit's next reminder as epoch millis, or null when it has none.
 *
 * Reads through [Habit.alarmMinutes], so a habit that carries only the legacy
 * [Habit.reminderMinutes] is included rather than silently reported as having no
 * alarm — the same normalisation [legacyAlarmTimes] applies everywhere else.
 *
 * Null is the honest answer in two cases, and callers must render it as such
 * rather than as "no alarm": a one-shot whose time has already gone by (it will
 * never ring), and a habit with no alarms at all.
 */
fun Habit.nextReminderMillis(
    now: LocalDateTime = LocalDateTime.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long? = HabitRepeat
    .nextOfAll(alarmMinutes, repeatDaysMask, now, zone)
    ?.atZone(zone)
    ?.toInstant()
    ?.toEpochMilli()

/**
 * True when the alarm's schedule means the user should have heard from it on
 * [day], given the mask.
 *
 * The habit-detail view uses this to distinguish "quiet today by design" (a
 * weekday habit on a Sunday) from "silent when it should have rung" — a
 * distinction the app previously could not make, which is why a correctly quiet
 * day and a dropped alarm looked identical.
 */
fun Habit.ringsOn(day: DayOfWeek): Boolean = HabitRepeat.allows(repeatDaysMask, day)
