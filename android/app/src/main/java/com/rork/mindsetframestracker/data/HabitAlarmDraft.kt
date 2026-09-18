package com.rork.mindsetframestracker.data

/**
 * The editable state of the alarm picker, as a pure value.
 *
 * ## Why this exists (the bug it removes)
 *
 * The picker used to hold its schedule in a `remember { ... }` inside the
 * composable, seeded like this:
 *
 * ```kotlin
 * var times by remember {
 *     mutableStateOf(initialTimes.ifEmpty { listOf(defaultMinutes) }.distinct().sorted())
 * }
 * ```
 *
 * `initialTimes` came from the habit's *saved* schedule, so `ifEmpty` could not
 * tell two very different situations apart:
 *
 *  * **A habit that has no alarm yet** — the icon's default time is a helpful
 *    starting point, and seeding it is right.
 *  * **A habit whose alarm the user just removed** — the saved list is also
 *    empty, so the same `ifEmpty` put the default time straight back.
 *
 * Worse, `remember` was keyed on nothing, and the dialog is constructed inside a
 * `if (alarmPickerIcon != null)` block in a screen that recomposes on every tick
 * of the app's data (`data.habits` is read for the picker's own pre-fill). Any
 * recomposition that discarded and recreated the remembered slot re-ran the
 * seed. The visible result was exactly what the user reported: *"every time I
 * remove it, the actual time defaults back in there."*
 *
 * ## The fix, stated as a property
 *
 * Whether a default may appear is a question about the **schedule**, not about
 * how many times the composable happened to run. [decided] answers it:
 *
 *  * [forNewHabit] — nothing chosen yet, so the icon's default is a genuine
 *    starting point.
 *  * [forExistingHabit] — the habit has a schedule, *including the legitimate
 *    empty one that means "no alarm"*. Decided, so no default can ever be
 *    injected into it.
 *
 * Once decided, [addTime]/[removeTime]/[clearTimes] keep it decided and update
 * [times] by exact value. A removed time therefore cannot come back, because
 * nothing downstream of a removal is allowed to add anything the user did not
 * ask for. Applying the default is idempotent
 * ([withDefaultIfUntouched] doesn't set [decided]), which is what makes it safe
 * to call on every frame.
 *
 * ## Not a UI concern
 *
 * Deliberately in the data layer, next to [legacyAlarmTimes] and
 * [Habit.withAlarmTimes] — the other two places that normalise a schedule. The
 * rule "a removed time stays removed" is a property of the schedule data, and
 * living here is what lets it be unit-tested without a Compose host.
 */
data class HabitAlarmDraft(
    /** The schedule being edited, ascending and deduped, possibly empty. */
    val times: List<Int> = emptyList(),
    /** The repeat mask shared by every time, as edited. */
    val repeatDaysMask: Int = REPEAT_DAILY,
    /** The motivational line as edited; blank means "use the curated pack". */
    val message: String = "",
    /**
     * True once this schedule is the user's own rather than an untouched
     * suggestion. The single guard that keeps a removed time removed, because a
     * default may only be applied while this is false.
     */
    val decided: Boolean = false,
) {

    /** True when this schedule has no alarms at all. */
    val isEmpty: Boolean get() = times.isEmpty()

    /**
     * The schedule with [minutes] added.
     *
     * Normalised through the same bound/dedupe/sort rule every other reader uses,
     * so the dialog can never accumulate a duplicate that would arm two alarms
     * for one time and ring the same reminder twice.
     */
    fun addTime(minutes: Int): HabitAlarmDraft =
        copy(times = normalise(times + minutes), decided = true)

    /**
     * The schedule with [minutes] removed.
     *
     * Removing the last time leaves an empty schedule and that emptiness is
     * carried as-is: nothing here re-seeds a default, which is the entire point
     * of [decided]. A habit with no alarm is a real, supported choice — the
     * picker's own "Skip — no alarm for this habit" depends on it.
     */
    fun removeTime(minutes: Int): HabitAlarmDraft =
        copy(times = times.filterNot { it == minutes }, decided = true)

    /**
     * Clears every time at once.
     *
     * The same contract as [removeTime] applied to the whole list, so the "no
     * alarm" path cannot behave differently from removing the times one by one.
     */
    fun clearTimes(): HabitAlarmDraft = copy(times = emptyList(), decided = true)

    /** The draft with its repeat mask replaced. */
    fun withRepeatMask(mask: Int): HabitAlarmDraft =
        copy(repeatDaysMask = mask, decided = true)

    /** The draft with its motivational line replaced. */
    fun withMessage(text: String): HabitAlarmDraft = copy(message = text, decided = true)

    /**
     * Applies [defaultMinutes] **only** when this schedule is still undecided and
     * empty.
     *
     * Both conditions matter, and the ORDER is the fix. `decided` is what makes a
     * removed time stay removed; testing `times.isEmpty()` alone is precisely the
     * original bug — after removing the last time the list is empty too, so an
     * emptiness-only test would re-add the default forever.
     *
     * Applying it does not set [decided], so calling this on every frame is
     * idempotent: the first call seeds the time and every later call is a no-op.
     */
    fun withDefaultIfUntouched(defaultMinutes: Int): HabitAlarmDraft =
        if (!decided && times.isEmpty()) copy(times = normalise(listOf(defaultMinutes))) else this

    /**
     * The pure function that turns this draft into the value a save writes.
     *
     * Returning the normalised list and the mask together is what keeps the write
     * path honest: the caller hands both to
     * [com.rork.mindsetframestracker.ui.AppViewModel.setHabitAlarmTimes], which
     * routes them through [Habit.withAlarmTimes] — so what the user saw in the
     * dialog is what gets persisted, with no second interpretation.
     */
    fun toSavePlan(): SavePlan = SavePlan(times = normalise(times), repeatDaysMask = repeatDaysMask)

    /** The exact values a save persists. */
    data class SavePlan(
        val times: List<Int>,
        val repeatDaysMask: Int,
    ) {
        /** True when this save clears the habit's alarm entirely. */
        val clearsAlarm: Boolean get() = times.isEmpty()
    }

    companion object {
        /**
         * The draft for a habit that does not exist yet, or the first tap on a
         * fresh icon: nothing is decided, so the icon's own default time is a
         * useful starting point rather than an imposition.
         *
         * Left **empty and undecided** on purpose: the default is applied by
         * [withDefaultIfUntouched] at the point of use, which is what makes the
         * "may a default appear here?" question live in one place instead of two.
         */
        fun forNewHabit(
            repeatDaysMask: Int = REPEAT_DAILY,
            message: String = "",
        ): HabitAlarmDraft = HabitAlarmDraft(
            times = emptyList(),
            repeatDaysMask = repeatDaysMask,
            message = message,
            decided = false,
        )

        /**
         * The draft for a habit that already exists: exactly its saved schedule,
         * **with no default injected, ever**.
         *
         * Empty in, empty out. A habit whose alarm the user removed opens with an
         * empty schedule and stays that way until they add a time — stated as a
         * property of this one constructor rather than as a rule the UI has to
         * remember to honour.
         */
        fun forExistingHabit(
            savedTimes: List<Int>,
            repeatDaysMask: Int = REPEAT_DAILY,
            message: String = "",
        ): HabitAlarmDraft = HabitAlarmDraft(
            times = normalise(savedTimes),
            repeatDaysMask = repeatDaysMask,
            message = message,
            decided = true,
        )

        /** Ascending, deduped, bounded to a real time of day — one shared rule. */
        private fun normalise(times: List<Int>): List<Int> =
            times.filter { it in 0..1439 }.distinct().sorted()
    }
}
