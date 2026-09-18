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
    /** The subset of [dayKeys] this habit was checked on. */
    val doneKeys: Set<String>,
    /** The subset of [dayKeys] with at least one sourced activity record. */
    val sourcedKeys: Set<String>,
) {
    /** 0.0 – 1.0 share of the week completed. 0.0 for an empty window. */
    val ratio: Double
        get() = if (dayKeys.isEmpty()) 0.0 else doneKeys.size.toDouble() / dayKeys.size

    /** "5 of 7 days". */
    val label: String get() = "${doneKeys.size} of ${dayKeys.size} days"

    /** True when every day in the window was completed. */
    val isPerfect: Boolean get() = dayKeys.isNotEmpty() && doneKeys.size == dayKeys.size

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
        HabitWeekConsistency(
            habitId = habit.id,
            habitName = habit.name,
            dayKeys = days,
            doneKeys = checkIns[habit.id].orEmpty().toSet().intersect(daySet),
            sourcedKeys = sourcedByHabit[habit.id].orEmpty(),
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
