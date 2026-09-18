package com.rork.mindsetframestracker.data

/**
 * What is actually set up inside a habit's alarm — the answer to "what will this
 * habit do, and when will it say it?".
 *
 * ## Why this is a value and not three ad-hoc reads
 *
 * The habit-today dialog has to describe the habit's schedule, its repeat rule
 * and the line its alarm will deliver. Those three facts are each read through
 * a different normalisation ([Habit.alarmMinutes] reconciles the modern and
 * legacy spellings, [HabitRepeat.describe] turns a mask into words,
 * [MotivationalMessages.lineFor] resolves the user's own message against the
 * curated pack for the icon). Reading them straight in a composable is how the
 * "07:00, 12:00, 18:00" the user set came to be described differently in the
 * dialog and the notification.
 *
 * Resolving them once, here, means the overview and the alarm itself cannot
 * disagree about what the habit contains.
 *
 * ## The effective line is resolved, not assumed
 *
 * [effectiveMessage] is the line the *next* alarm will actually deliver, with
 * the user's own words taking priority and the curated pack filling in when
 * they wrote none. Showing [alarmMessage] directly would render an empty field
 * for the common case and read as "no message set" — when in fact the alarm
 * always says something.
 */
data class HabitAlarmSetup(
    /** Every time this habit rings at, ascending, deduped, possibly empty. */
    val alarmTimes: List<Int>,
    /** The mask shared by every one of those times. */
    val repeatDaysMask: Int,
    /** The user's own line, or null when the curated pack supplies it. */
    val customMessage: String?,
    /** The line the next alarm will deliver — never blank. */
    val effectiveMessage: String,
) {

    /** True when this habit has at least one alarm. */
    val hasAlarm: Boolean get() = alarmTimes.isNotEmpty()

    /** "07:00, 12:00, 18:00" — the schedule, in the app's 24-hour clock. */
    val scheduleLabel: String get() = formatAlarmTimes(alarmTimes)

    /** "every day" / "on weekdays" / "Mon, Tue, Wed" — the repeat rule in words. */
    val repeatLabel: String get() = HabitRepeat.describe(repeatDaysMask)

    /** True when the user wrote their own line rather than using the curated one. */
    val hasCustomMessage: Boolean get() = !customMessage.isNullOrBlank()

    companion object {
        /**
         * Resolves a habit's setup for [dayKey].
         *
         * [dayKey] matters because the curated line is day-dependent: the same
         * habit carries a different encouraging sentence on different days, so a
         * preview computed for the wrong day would show a line the alarm will
         * never say on the day the user is looking at.
         */
        fun of(habit: Habit, dayKey: String = Dates.todayKey()): HabitAlarmSetup {
            val times = habit.alarmMinutes
            return HabitAlarmSetup(
                alarmTimes = times,
                repeatDaysMask = habit.repeatDaysMask,
                customMessage = habit.alarmMessage,
                // Resolved against the habit's first time of day, which is the
                // occurrence this line is an example of. `lineFor` is total for a
                // habit with no alarms (it falls back to the icon's pack), so no
                // null handling is needed here.
                effectiveMessage = MotivationalMessages.lineFor(
                    habit = habit,
                    dayKey = dayKey,
                    alarmMinutes = times.firstOrNull(),
                    alarmIndex = 0,
                ),
            )
        }
    }
}
