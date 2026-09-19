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

/**
 * The schedule to use when **restoring** a habit from the cloud.
 *
 * ## Why the plain [legacyAlarmTimes] rule is not enough on the restore path
 *
 * Everywhere else the two columns are written together, so "a non-empty
 * `alarm_times` wins outright" holds. The cloud is the one place where they can
 * land at *different times*, and that is a second way a removed alarm came back:
 *
 *  1. A habit has 21:00. The row is written with `alarm_times = [1260]` and
 *     `reminder_minutes = 1260`.
 *  2. The user removes the alarm. Locally that is `alarmTimes = []` and
 *     `reminderMinutes = null`.
 *  3. The push sends both — but the live project never had the `alarm_times`
 *     migration applied (`PGRST204`), so that field is dropped from the payload
 *     while `reminder_minutes = null` lands. See the dropped-column handling in
 *     `SupabaseSync.upsert`.
 *  4. The cloud row is now **inconsistent**: `alarm_times = [1260]` (stale) with
 *     `reminder_minutes = NULL` (fresh).
 *  5. The next pull applies [legacyAlarmTimes], sees a non-empty list, and hands
 *     the 21:00 alarm straight back.
 *
 * ## The rule
 *
 * The invariant shared by every writer is `alarm_times.firstOrNull() ==
 * reminder_minutes`. A row that violates it was therefore not written as one
 * unit, so its list cannot be trusted to reflect the user's current intent —
 * while `reminder_minutes` can, because it is the column that always lands.
 * `reminder_minutes == null` on such a row means the user cleared the alarm, so
 * the restored schedule is empty.
 *
 * A row that *is* consistent goes through [legacyAlarmTimes] unchanged, so a
 * habit that genuinely has 07:00/12:00/18:00 restores all three, and a project
 * that predates the column still restores its single legacy time.
 */
fun restoredAlarmTimes(cloudAlarmTimes: List<Int>?, cloudReminderMinutes: Int?): List<Int> =
    restoredSchedule(cloudAlarmTimes, cloudReminderMinutes).times

/**
 * A restored schedule together with whether the row that produced it was
 * structurally valid.
 *
 * The flag is the point. A row violating the invariant is **evidence of a
 * writer bug**, and the previous behaviour — silently reading it as "no alarm"
 * — destroyed the evidence along with the data. This keeps the schedule and
 * reports the breach, so the caller can log and surface it while the user's
 * alarms survive.
 */
data class RestoredSchedule(
    val times: List<Int>,
    /**
     * True when the cloud row had a non-empty `alarm_times` alongside a null
     * `reminder_minutes`, which no writer in this app can produce.
     */
    val inconsistent: Boolean,
)

/**
 * The schedule to use when **restoring** a habit from the cloud.
 *
 * ## Why the plain [legacyAlarmTimes] rule is not enough on the restore path
 *
 * Everywhere else the two columns are written together, so "a non-empty
 * `alarm_times` wins outright" holds. The cloud is the one place where they can
 * land at *different times*, and that was a second way a removed alarm came
 * back:
 *
 *  1. A habit has 21:00. The row is written with `alarm_times = [1260]` and
 *     `reminder_minutes = 1260`.
 *  2. The user removes the alarm. Locally that is `alarmTimes = []` and
 *     `reminderMinutes = null`.
 *  3. The push sends both — but the live project never had the `alarm_times`
 *     migration applied (`PGRST204`), so that field is dropped from the payload
 *     while `reminder_minutes = null` lands. See the dropped-column handling in
 *     `SupabaseSync.upsert`.
 *  4. The cloud row is now **inconsistent**: `alarm_times = [1260]` (stale) with
 *     `reminder_minutes = NULL` (fresh).
 *
 * ## Why this no longer "repairs" the row by clearing the schedule
 *
 * The first fix for that case treated the pair as "the user cleared the alarm"
 * and returned an empty schedule. That was wrong in two ways, and the second is
 * worse than the first:
 *
 *  - **It cannot distinguish the two readings.** ``alarm_times=[1260],
 *    reminder_minutes=null`` is either "a stale list plus a cleared alarm" or "a
 *    real 21:00 alarm whose mirrored column was written null by the dropped-field
 *    path". Nothing in the row says which. Choosing "no alarm" picks the reading
 *    that loses the user's data.
 *  - **It defends against an input the writer makes impossible.** The invariant
 *    `alarm_times.firstOrNull() == reminder_minutes` holds in both directions
 *    (see `Habit.withAlarmTimes` and the `legacyAlarmTimes` writer), so a row that
 *    breaks it is not a normal state to normalise — it is a bug report. Quietly
 *    emptying the schedule deleted every alarm on that habit with no message,
 *    which is precisely the "my alarm vanished / my alarm came back" symptom
 *    this whole path was meant to end.
 *
 * So the schedule is **preserved** and the breach is reported via
 * [RestoredSchedule.inconsistent], which the sync layer logs and surfaces. A row
 * that *is* consistent goes through [legacyAlarmTimes] unchanged, so a habit with
 * a genuine 07:00/12:00/18:00 restores all three, and a project that predates the
 * column still restores its single legacy time.
 */
fun restoredSchedule(
    cloudAlarmTimes: List<Int>?,
    cloudReminderMinutes: Int?,
): RestoredSchedule {
    val times = cloudAlarmTimes.orEmpty()
    return RestoredSchedule(
        times = legacyAlarmTimes(times, cloudReminderMinutes),
        inconsistent = times.isNotEmpty() && cloudReminderMinutes == null,
    )
}
