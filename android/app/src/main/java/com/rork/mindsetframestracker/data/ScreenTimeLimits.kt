package com.rork.mindsetframestracker.data

import java.util.UUID

/**
 * The result of reconciling the user's chosen screen-time limits against the
 * habits that already exist.
 *
 * Returned as a whole rather than mutated in place so the rule "editing a limit
 * may never delete a habit" is a **property of a pure function** that a unit
 * test can assert, instead of a promise about a ViewModel's `update { }` block.
 */
data class ScreenTimePlan(
    /** The complete habit list the save should persist. */
    val habits: List<Habit>,
    /** The check-in map the save should persist. */
    val checkIns: Map<String, List<String>>,
    val added: List<Habit>,
    val updated: List<Habit>,
    val removed: List<Habit>,
) {
    /** How many apps are limited after this save, for the confirmation message. */
    val limitedCount: Int get() = habits.count { it.isScreenTimeHabit }

    /** Ids of the habits this save drops, so their cloud rows can be queued too. */
    val removedIds: List<String> get() = removed.map { it.id }
}

/**
 * Reconciles [limits] — the picker's **complete** desired set — against the
 * habits in [current], without ever touching a habit that is not a screen-time
 * limit.
 *
 * ## The bug this replaces
 *
 * `applyScreenTimeLimits` used to build its result as `kept + additions`, where
 * `kept` was `data.habits.filter { … }` — i.e. it **replaced the entire habit
 * list with its own filtered view of it**. Every habit the filter happened not
 * to reconstruct was gone: dropped from `habits`, and (because
 * `screenTimeLimitMinutes` was nullable and read through
 * `isScreenTimeHabit = monitoredPackage != null && screenTimeLimitMinutes != null`)
 * a single habit whose limit column was missing decoded as *not* a screen-time
 * habit, fell past the "keep" branch, and was silently deleted along with its
 * check-in history. Editing one limit could therefore wipe unrelated habits and
 * their streaks — the exact report.
 *
 * ## The rule
 *
 * A habit that is **not** a screen-time habit is copied through **verbatim**. It
 * is never filtered, re-derived, re-ordered or renamed by this function, because
 * a screen-time save has no business expressing an opinion about it. For
 * screen-time habits the only decisions are the three the picker actually makes:
 * *desired and new* → add, *desired and existing* → update the limit,
 * *existing and no longer desired* → remove.
 *
 * ## Duplicate packages
 *
 * If an install somehow holds two habits for one package, `associateBy` keeps
 * the **last** limit the user chose and both habits are updated to it rather than
 * one being left stale — the alternative (dropping one) would be a silent delete
 * on a path that must not delete.
 */
fun planScreenTimeLimits(
    current: AppData,
    limits: List<ScreenTimeLimitInput>,
    /**
     * The packages the picker was seeded with.
     *
     * Only a package in here can ever be *removed*. The picker hands back the
     * complete desired set, and "absent from that set" would otherwise be read
     * as "the user cleared it" — including for a habit that was never shown to
     * the user at all (a limit that had not finished loading, or one that
     * arrived from a cloud pull while the sheet was open). Treating that
     * absence as a removal is how a save could delete a habit nobody touched.
     */
    removablePackages: Set<String> = emptySet(),
): ScreenTimePlan {
    val desired = limits.associateBy { it.packageName }
    val existingPackages = current.habits
        .filter { it.isScreenTimeHabit }
        .mapNotNull { it.monitoredPackage }
        .toSet()

    val removed = mutableListOf<Habit>()
    val updated = mutableListOf<Habit>()

    // Kept in the user's original order. Only a screen-time habit whose package
    // the user actually cleared is dropped; everything else survives this pass
    // untouched.
    val kept = current.habits.filterNot { habit ->
        val packageName = habit.monitoredPackage.takeIf { habit.isScreenTimeHabit }
        if (packageName != null && packageName !in desired && packageName in removablePackages) {
            removed += habit
            true
        } else {
            false
        }
    }

    val reconciled = kept.map { habit ->
        val packageName = habit.monitoredPackage.takeIf { habit.isScreenTimeHabit }
        val want = packageName?.let { desired[it] } ?: return@map habit
        if (habit.screenTimeLimitMinutes == want.limitMinutes &&
            habit.monitoredAppLabel == want.appLabel
        ) {
            habit
        } else {
            val withNewLimit = habit.copy(
                screenTimeLimitMinutes = want.limitMinutes,
                monitoredAppLabel = want.appLabel,
            )
            val renamed = withNewLimit.copy(name = withNewLimit.screenTimeSummary())
            updated += renamed
            renamed
        }
    }

    val added = desired.values
        .filter { it.packageName !in existingPackages }
        .map { want ->
            val base = Habit(
                id = UUID.randomUUID().toString(),
                name = "",
                createdAt = System.currentTimeMillis(),
                iconId = "screenTime",
                monitoredPackage = want.packageName,
                screenTimeLimitMinutes = want.limitMinutes,
                monitoredAppLabel = want.appLabel,
            )
            base.copy(name = base.screenTimeSummary())
        }

    val removedIds = removed.map { it.id }.toSet()
    return ScreenTimePlan(
        habits = reconciled + added,
        // A removed limit drops its check-in history the same way deleteHabit
        // does, so the heatmap and the weekly count stop counting an app the
        // user no longer limits.
        checkIns = if (removedIds.isEmpty()) current.checkIns
        else current.checkIns - removedIds,
        added = added,
        updated = updated,
        removed = removed,
    )
}
