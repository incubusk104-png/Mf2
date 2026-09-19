package com.rork.mindsetframestracker.data

import java.time.LocalDate

/**
 * One habit's week, as a row of days plus the two numbers the user actually
 * reads off it: how many of the seven days it was done, and whether it kept
 * itself up via a tracker.
 *
 * ## Why this is derived here and not in a composable
 *
 * A consistency view has to answer three questions at once — *did I do it*, *on
 * which days*, and *did the activity come from a tracker rather than a manual
 * tap* — and the three are easy to get subtly inconsistent when each is computed
 * at the point of display. Resolving them together means the weekly card and any
 * other surface can never disagree about the same week.
 */
data class HabitWeekConsistency(
    val habitId: String,
    val habitName: String,
    /** The seven day keys, oldest first, ending on the week's last day. */
    val dayKeys: List<String>,
    /**
     * The subset of [dayKeys] this habit's schedule actually asked for.
     *
     * ## Why the denominator is not just `dayKeys`
     *
     * A habit that rings Monday–Friday was never due on a Sunday, so counting
     * that Sunday against it is not a measurement of anything the user chose.
     * Dividing by the full window meant a weekday habit's best possible reading
     * was "5 of 7 days" — it could never be perfect, was permanently listed in
     * [WeeklyConsistency.needsAttention], and the surfaced number contradicted
     * the repeat rule the user had just set in the alarm picker.
     *
     * The helper that fixes this (`HabitRepeat.allows`, via
     * `Habit.dueDayKeysIn`) already existed and was unused — this is that wire.
     *
     * Equal to [dayKeys] for a habit with no repeating schedule, so those habits
     * keep the previous reading rather than becoming a degenerate "0 of 0".
     */
    val dueDayKeys: List<String>,
    /** The subset of [dueDayKeys] this habit was checked on. */
    val doneKeys: Set<String>,
    /** The subset of [dueDayKeys] with at least one sourced activity record. */
    val sourcedKeys: Set<String>,
) {
    /**
     * 0.0 – 1.0 share of the **due** days completed. 0.0 for an empty window.
     *
     * `doneKeys` is already clipped to the due days, so this cannot exceed 1.0
     * even if a check-in somehow lands on a day the schedule excludes — that
     * would otherwise be a ratio above 1, which every consumer of this value
     * (a bar, a percentage) would render as nonsense.
     */
    val ratio: Double
        get() = if (dueDayKeys.isEmpty()) 0.0 else doneKeys.size.toDouble() / dueDayKeys.size

    /**
     * "5 of 5 days" for a weekday habit, "3 of 7 days" for a daily one — the
     * denominator names the days the habit was due, so the figure is a fair
     * reading of the user's own schedule.
     */
    val label: String get() = "${doneKeys.size} of ${dueDayKeys.size} days"

    /** True when every day the habit was **due** was completed. */
    val isPerfect: Boolean get() = dueDayKeys.isNotEmpty() && doneKeys.size == dueDayKeys.size

    /** How many of the completions a tracker supplied rather than a manual tap. */
    val sourcedCount: Int get() = doneKeys.count { it in sourcedKeys }
}

/**
 * The whole week's consistency across every habit, plus the sourced-activity
 * totals that give the "my walking habit is being kept up by Strava" answer.
 */
data class WeeklyConsistency(
    val dayKeys: List<String>,
    val habits: List<HabitWeekConsistency>,
    /** The window's activity totals, every source folded together. */
    val activity: ActivityTotals = ActivityTotals.EMPTY,
) {
    /** True when there is nothing at all to draw. */
    val isEmpty: Boolean get() = habits.isEmpty() && activity.isEmpty

    /** Mean completion ratio across habits, 0.0 when there are none. */
    val overallRatio: Double
        get() = if (habits.isEmpty()) 0.0 else habits.sumOf { it.ratio } / habits.size

    /** Habits ordered weakest-first, so the one needing attention reads first. */
    val needsAttention: List<HabitWeekConsistency>
        get() = habits.filter { !it.isPerfect }.sortedBy { it.ratio }
}

/**
 * Builds the week's consistency view over [days] (oldest first).
 *
 * ## Completeness over cleverness
 *
 * Every habit appears, including one with **zero** completions — a week view
 * that only lists what succeeded is exactly how a habit that is quietly not
 * happening stays invisible, which is the failure this view exists to prevent.
 *
 * Sourced days are counted from [ActivityRecord]s by the record's **own** start
 * day (see [ActivityRecord.dayKey]), not by when it was imported: a run synced
 * this morning may have happened last night, and bucketing it by import time
 * would credit the wrong day.
 *
 * [days] is passed in rather than derived so the window is explicit and the
 * function stays pure and testable against a fixed week.
 */
fun AppData.weeklyConsistency(days: List<String>): WeeklyConsistency {
    val daySet = days.toSet()
    val sourcedByHabit = activityRecords
        .filter { it.dayKey() in daySet }
        .groupBy { it.habitId }
        .mapValues { (_, records) -> records.map { it.dayKey() }.toSet() }

    val rows = habits.map { habit ->
        // The days this habit was due, from its own repeat mask. Everything the
        // row reports is then measured against that, so a weekday habit reads
        // "5 of 5" rather than being marked down for a Sunday it never wanted.
        val due = habit.dueDayKeysIn(days)
        val dueSet = due.toSet()
        HabitWeekConsistency(
            habitId = habit.id,
            habitName = habit.name,
            dayKeys = days,
            dueDayKeys = due,
            // Clipped to the due days: a check-in on a day the schedule excludes
            // is not counted, or the ratio could exceed 1.0 and "perfect" would
            // be reached with days to spare.
            doneKeys = checkIns[habit.id].orEmpty().toSet().intersect(dueSet),
            sourcedKeys = sourcedByHabit[habit.id].orEmpty().intersect(dueSet),
        )
    }

    return WeeklyConsistency(
        dayKeys = days,
        habits = rows,
        activity = activityTotalsOver(days),
    )
}

/** The last seven day keys, oldest first, ending today (or [today]). */
fun lastSevenDayKeys(today: LocalDate = LocalDate.now()): List<String> =
    (6 downTo 0).map { Dates.key(today.minusDays(it.toLong())) }
