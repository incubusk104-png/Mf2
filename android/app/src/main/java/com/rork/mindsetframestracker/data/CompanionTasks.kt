package com.rork.mindsetframestracker.data

/**
 * Kinds of daily tasks / focus goals that unlock exclusive companion items
 * (outfits, expressions, pets) in the Companion Studio. Progress is always
 * derived from real app data — habits checked, moods logged, reflections
 * written — so unlocks are earned by showing up, never sold.
 */
enum class CompanionTaskType {
    /** Complete every habit today (target = 1 full day). */
    COMPLETE_ALL_TODAY,

    /** Check in (any habit) [CompanionTask.target] days in a row. */
    CHECKIN_STREAK,

    /** Complete ALL habits [CompanionTask.target] days in a row. */
    FULL_STREAK,

    /** Reach [CompanionTask.target] total habit check-ins, all time. */
    TOTAL_CHECKINS,

    /** Write [CompanionTask.target] one-line grounding reflections. */
    REFLECTIONS_WRITTEN,

    /** Log a mood on [CompanionTask.target] different days. */
    MOODS_LOGGED,
}

/** One unlock requirement: a task type plus the target count to reach. */
data class CompanionTask(
    val type: CompanionTaskType,
    val target: Int,
)

/** Current progress toward this task, derived live from [data]. */
fun CompanionTask.progress(data: AppData): Int = when (type) {
    CompanionTaskType.COMPLETE_ALL_TODAY ->
        if (data.isFullyCompleted(Dates.todayKey())) 1 else 0
    CompanionTaskType.CHECKIN_STREAK -> data.dailyCheckInStreak()
    CompanionTaskType.FULL_STREAK ->
        // Earned badges make past full streaks permanent, so a broken streak
        // never re-locks an item the user already worked for.
        maxOf(
            data.fullCompletionStreak(),
            data.settings.earnedBadges.maxOfOrNull { it.daysRequired } ?: 0,
        )
    CompanionTaskType.TOTAL_CHECKINS -> data.checkIns.values.sumOf { it.size }
    CompanionTaskType.REFLECTIONS_WRITTEN -> data.reflections.size
    CompanionTaskType.MOODS_LOGGED -> data.moodHistory.size
}

/** True when the task requirement is currently satisfied. */
fun CompanionTask.isMet(data: AppData): Boolean = progress(data) >= target

/**
 * True when the item guarded by [task] is usable right now: free items
 * (null task), items already earned permanently, or a task met live.
 */
fun isCompanionItemUnlocked(task: CompanionTask?, id: String, data: AppData): Boolean =
    task == null || id in data.settings.companionUnlocks || task.isMet(data)
