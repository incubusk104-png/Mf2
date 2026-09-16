package com.rork.mindsetframestracker.data

/**
 * The single normalisation rule for "what is this habit's schedule, really?".
 *
 * ## Why one shared function
 *
 * A habit's alarm times exist in two spellings: the modern [Habit.alarmTimes]
 * list, and the legacy single [Habit.reminderMinutes] every build before
 * multiple alarms understood. Three places have to reconcile them —
 * [Habit.alarmMinutes] (the local reader), [HabitStore] (the reboot/ring-time
 * reader), and `SupabaseSync` (both push and pull).
 *
 * They used to each decide for themselves, and they disagreed in a way that was
 * invisible until it bit a user: the **pull** path treated an empty
 * `alarm_times` as "the legacy `reminder_minutes` *is* the schedule", while the
 * **push** path sent `alarm_times` derived from the legacy column. So a user on
 * an older build who wrote only `reminder_minutes`, then read their data back on
 * a newer build, had their intent reinterpreted — and two devices on different
 * builds could disagree about the same row.
 *
 * Funnelling every decision through this one function makes that impossible: the
 * legacy column is a *fallback for an empty list*, never a competing source of
 * truth, and it is applied identically in both directions.
 *
 * ## The rule
 *
 *  - A non-empty [alarmTimes] wins outright. The list is the user's real intent;
 *    `reminderMinutes` is only ever its mirrored first entry (see
 *    [Habit.withAlarmTimes]).
 *  - An empty [alarmTimes] with a [reminderMinutes] means a habit created before
 *    multiple alarms existed, so its one legacy time is its whole schedule.
 *  - Both empty/null means no alarm at all.
 *
 * The result is always ascending, deduped and bounded to a real time of day,
 * because that is what every consumer assumes: the scheduler arms one alarm per
 * entry, the UI renders them in order, and the array column's CHECK constraint
 * accepts 0–1439.
 */
fun legacyAlarmTimes(alarmTimes: List<Int>?, reminderMinutes: Int?): List<Int> {
    val times = if (!alarmTimes.isNullOrEmpty()) alarmTimes else listOfNotNull(reminderMinutes)
    return times.filter { it in 0..1439 }.distinct().sorted()
}
