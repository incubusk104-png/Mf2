package com.rork.mindsetframestracker.data

/**
 * The one rule for "which copy of a habit wins when two sources disagree".
 *
 * ## Why this is a named function rather than an inline `distinctBy`
 *
 * `distinctBy { it.id }` keeps the **first** occurrence, so the winner is
 * silently decided by the order of the concatenation that feeds it. Restore
 * built its list as `snapshot.habits + data.habits` — cloud first — so the
 * cloud copy won every conflict. That is the opposite of what the rest of the
 * same function does: check-ins, logs and activity are all unioned with the
 * **local** entry winning (see `AppViewModel.restoreFromCloud`). One function,
 * two contradictory tie-break rules, and the habit one was the invisible
 * half — nothing in the code said "cloud wins", it was an accident of `+`.
 *
 * The user-visible result was that a habit edited on this device could be
 * reverted to an older cloud copy by an unrelated background restore, with a
 * clean merge shown and no message. Naming the rule — and making it
 * `local first` in one place — is what stops the ordering choice from being
 * able to change the semantics again.
 *
 * ## Why local wins
 *
 * The merge is a **union**, not a true restore: habits present on only one side
 * survive either way. In a union the local copy is the one the user can still
 * see and edit, and any local edit is queued to be pushed; discarding it in
 * favour of the cloud copy silently destroys work that was going to be
 * uploaded. Picking the cloud copy would only be right for a destructive
 * overwrite, which this is not.
 *
 * [incoming] is deliberately a plain list rather than "the cloud": the same
 * rule is what an import needs, so the two paths cannot drift apart.
 */
data class HabitMergeResult(
    val habits: List<Habit>,
    /** Ids where both sides had a copy and the local one was kept. */
    val keptLocal: List<String>,
    /** Ids that existed only in [incoming] and were adopted. */
    val addedFromIncoming: List<String>,
) {
    /** True when the merge changed nothing about the habit set. */
    val isNoOp: Boolean get() = addedFromIncoming.isEmpty()
}

/**
 * Merges [incoming] into [local] by habit id, keeping the local copy on a
 * conflict and appending the rest in their original order.
 *
 * Local order is preserved exactly — the user's habit list is a thing they
 * arranged, and a merge is not a reason to reshuffle it. Incoming habits are
 * appended after the local ones, in the order they arrived.
 *
 * Duplicate ids within a single input are collapsed on the same rule (the
 * first occurrence in [local] wins), so a list that already contains a
 * duplicate cannot leak two rows for one habit into the result.
 */
fun mergeHabitsPreferringLocal(
    local: List<Habit>,
    incoming: List<Habit>,
): HabitMergeResult {
    val localIds = LinkedHashSet<String>()
    val merged = ArrayList<Habit>(local.size + incoming.size)
    local.forEach { habit ->
        if (localIds.add(habit.id)) merged += habit
    }

    val keptLocal = ArrayList<String>()
    val added = ArrayList<String>()
    incoming.forEach { habit ->
        if (habit.id in localIds) {
            // Both sides have a copy. The local one is already in `merged` and
            // stays there; recorded so the caller can report the conflict
            // instead of resolving it in silence.
            if (habit.id !in keptLocal) keptLocal += habit.id
        } else {
            localIds += habit.id
            added += habit.id
            merged += habit
        }
    }

    return HabitMergeResult(
        habits = merged,
        keptLocal = keptLocal,
        addedFromIncoming = added,
    )
}

/**
 * The `LocalDate` day keys a habit was **due** within [dayKeys].
 *
 * A habit that rings Monday–Friday was never due on a Sunday, so counting that
 * Sunday against it is not a measurement of anything the user chose. The weekly
 * view's denominator has to be the days the schedule actually asked for, or a
 * weekday habit can never read as perfect and is permanently ranked as needing
 * attention — see [HabitWeekConsistency].
 *
 * A habit with **no repeating schedule at all** (`repeatDaysMask == 0`, which
 * is also "fire once") has declared no cadence to measure against, so every day
 * in the window counts. That keeps the previous behaviour for those habits
 * rather than producing a degenerate "0 of 0".
 *
 * Unparseable keys are dropped rather than guessed at: the window is built from
 * [Dates.key] and is always ISO, so a key that will not parse is corrupt input,
 * and treating it as a due day would credit a day that may not exist.
 */
fun Habit.dueDayKeysIn(dayKeys: List<String>): List<String> {
    if (repeatDaysMask == REPEAT_ONCE) return dayKeys
    return dayKeys.filter { key ->
        val date = runCatching { java.time.LocalDate.parse(key) }.getOrNull()
        date != null && HabitRepeat.allows(repeatDaysMask, date.dayOfWeek)
    }
}
