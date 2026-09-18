package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the alarm picker's draft state — the fix for "every time I remove it,
 * the actual time defaults back in there".
 *
 * These are deliberately pure JVM tests with no Compose host: the bug was in the
 * *rule* about when a default may appear, not in the rendering, so asserting the
 * rule directly is both cheaper and a stronger guarantee than driving the dialog.
 */
class HabitAlarmDraftTest {

    // ── The bug: a removed time must never come back ────────────────────────

    @Test
    fun `removing the only time leaves an empty schedule`() {
        val draft = HabitAlarmDraft.forExistingHabit(savedTimes = listOf(21 * 60))
            .removeTime(21 * 60)

        assertTrue("the schedule must be empty after removing its only time", draft.isEmpty)
        assertEquals(emptyList<Int>(), draft.times)
    }

    @Test
    fun `removing a time is not undone by re-applying the icon default`() {
        // The exact reported sequence: the habit has one alarm, the user removes
        // it, and the dialog repaints (possibly several times).
        val default = 21 * 60
        var draft = HabitAlarmDraft.forExistingHabit(savedTimes = listOf(default))
            .removeTime(default)

        repeat(5) { draft = draft.withDefaultIfUntouched(default) }

        assertTrue(
            "the icon default must never be re-added to a schedule the user emptied",
            draft.isEmpty,
        )
    }

    @Test
    fun `removing one of several times keeps the rest and drops only that one`() {
        val draft = HabitAlarmDraft.forExistingHabit(savedTimes = listOf(420, 720, 1080))
            .removeTime(720)

        assertEquals(listOf(420, 1080), draft.times)
    }

    @Test
    fun `clearing every time is equivalent to removing them one by one`() {
        val cleared = HabitAlarmDraft.forExistingHabit(savedTimes = listOf(420, 720, 1080))
            .clearTimes()
        val removedOneByOne = listOf(420, 720, 1080).fold(
            HabitAlarmDraft.forExistingHabit(savedTimes = listOf(420, 720, 1080)),
        ) { draft, minutes -> draft.removeTime(minutes) }

        assertEquals(removedOneByOne.times, cleared.times)
        assertTrue(cleared.isEmpty)

        // And neither path can be re-seeded.
        repeat(3) {
            assertEquals(emptyList<Int>(), cleared.withDefaultIfUntouched(420).times)
            assertEquals(emptyList<Int>(), removedOneByOne.withDefaultIfUntouched(420).times)
        }
    }

    // ── The other half: a genuinely new habit still gets its default ────────

    @Test
    fun `a brand new habit is seeded with the icon default`() {
        val draft = HabitAlarmDraft.forNewHabit().withDefaultIfUntouched(9 * 60)

        assertEquals(listOf(9 * 60), draft.times)
    }

    @Test
    fun `seeding a new habit is idempotent across recompositions`() {
        val draft = HabitAlarmDraft.forNewHabit()
        val first = draft.withDefaultIfUntouched(9 * 60)
        val second = first.withDefaultIfUntouched(9 * 60)

        assertEquals(first.times, second.times)
        assertEquals(listOf(9 * 60), second.times)
    }

    @Test
    fun `a default added to a new habit can then be removed and stays removed`() {
        // New habit -> default appears -> user removes it -> it must not return.
        val default = 9 * 60
        var draft = HabitAlarmDraft.forNewHabit().withDefaultIfUntouched(default)
        draft = draft.removeTime(default)

        repeat(3) { draft = draft.withDefaultIfUntouched(default) }

        assertTrue(draft.isEmpty)
    }

    @Test
    fun `an existing habit with no alarm opens empty and stays empty`() {
        // The legacy habit whose alarm the user already cleared: `alarmTimes` is
        // empty and `reminderMinutes` is null. This is the state the old
        // `ifEmpty { default }` misread as "needs a default".
        var draft = HabitAlarmDraft.forExistingHabit(savedTimes = emptyList())

        assertTrue(draft.isEmpty)
        repeat(4) { draft = draft.withDefaultIfUntouched(21 * 60) }
        assertTrue("an existing empty schedule is a decision, not a gap", draft.isEmpty)
    }

    // ── Multiple times on the same day, same habit ──────────────────────────

    @Test
    fun `three times on the same day are all kept as distinct entries`() {
        val draft = HabitAlarmDraft.forExistingHabit(savedTimes = emptyList())
            .addTime(7 * 60)
            .addTime(12 * 60)
            .addTime(18 * 60)

        assertEquals(
            "each time of day is its own entry — never collapsed by habit id",
            listOf(7 * 60, 12 * 60, 18 * 60),
            draft.times,
        )
        assertEquals(3, draft.times.size)
    }

    @Test
    fun `adding an already-set time does not duplicate it`() {
        val draft = HabitAlarmDraft.forExistingHabit(savedTimes = listOf(7 * 60))
            .addTime(7 * 60)

        assertEquals(listOf(7 * 60), draft.times)
    }

    @Test
    fun `save plan round trips three same-day times with no dedup`() {
        val saved = listOf(7 * 60, 12 * 60, 18 * 60)
        val plan = HabitAlarmDraft.forExistingHabit(savedTimes = saved).toSavePlan()

        assertEquals(saved, plan.times)
        assertFalse(plan.clearsAlarm)

        // Reload: what the save wrote is what a fresh draft reads back.
        val reopened = HabitAlarmDraft.forExistingHabit(savedTimes = plan.times)
        assertEquals(saved, reopened.times)
    }

    @Test
    fun `save plan for an emptied schedule reports a clear and round trips empty`() {
        val plan = HabitAlarmDraft.forExistingHabit(savedTimes = listOf(7 * 60, 18 * 60))
            .clearTimes()
            .toSavePlan()

        assertTrue("an empty save is how a habit's alarm is removed", plan.clearsAlarm)
        assertEquals(emptyList<Int>(), plan.times)

        val reopened = HabitAlarmDraft.forExistingHabit(savedTimes = plan.times)
        assertTrue(reopened.isEmpty)
    }

    @Test
    fun `times are normalised and never carry an out-of-range value`() {
        val draft = HabitAlarmDraft.forExistingHabit(savedTimes = listOf(1440, -1, 60, 60))
            .addTime(2000)

        assertEquals(listOf(60), draft.times)
    }

    // ── The setup summary the overview renders ─────────────────────────────

    @Test
    fun `setup reports every scheduled time in its schedule label`() {
        val habit = Habit(
            id = "habit-1",
            name = "Walk",
            createdAt = 0L,
            alarmTimes = listOf(7 * 60, 12 * 60, 18 * 60),
        )

        val setup = HabitAlarmSetup.of(habit, dayKey = "2026-09-19")

        assertEquals(3, setup.alarmTimes.size)
        assertEquals("07:00, 12:00, 18:00", setup.scheduleLabel)
        assertTrue(setup.hasAlarm)
        assertTrue(setup.effectiveMessage.isNotBlank())
    }

    @Test
    fun `setup says plainly when a habit has no alarm`() {
        val habit = Habit(id = "habit-2", name = "Tidy Up", createdAt = 0L)

        val setup = HabitAlarmSetup.of(habit, dayKey = "2026-09-19")

        assertFalse(setup.hasAlarm)
        assertEquals(emptyList<Int>(), setup.alarmTimes)
        // The overview renders `habitSetupNoAlarm` off this flag, so a habit with
        // no alarm is stated outright rather than rendering a blank schedule.
        assertTrue(setup.scheduleLabel.isBlank())
    }

    @Test
    fun `setup prefers the user's own message and marks it as custom`() {
        val habit = Habit(
            id = "habit-3",
            name = "Walk",
            createdAt = 0L,
            alarmTimes = listOf(7 * 60),
            alarmMessage = "Lace up — 10 minutes is enough.",
        )

        val setup = HabitAlarmSetup.of(habit, dayKey = "2026-09-19")

        assertTrue(setup.hasCustomMessage)
        assertEquals("Lace up — 10 minutes is enough.", setup.effectiveMessage)
    }

    @Test
    fun `setup falls back to the curated line when the user wrote none`() {
        val habit = Habit(
            id = "habit-4",
            name = "Drink Water",
            createdAt = 0L,
            iconId = "water",
            alarmTimes = listOf(9 * 60),
        )

        val setup = HabitAlarmSetup.of(habit, dayKey = "2026-09-19")

        assertFalse(setup.hasCustomMessage)
        assertTrue(
            "the alarm always says something, so the overview must never read as blank",
            setup.effectiveMessage.isNotBlank(),
        )
    }

    @Test
    fun `setup describes the repeat rule in words`() {
        val habit = Habit(
            id = "habit-5",
            name = "Gym",
            createdAt = 0L,
            alarmTimes = listOf(6 * 60),
            repeatDaysMask = REPEAT_WEEKDAYS,
        )

        assertEquals("on weekdays", HabitAlarmSetup.of(habit).repeatLabel)
    }

    // ── The overview lists every occurrence, one row per time ──────────────

    @Test
    fun `day slots produce one row per scheduled time, never one per habit`() {
        val habit = Habit(
            id = "habit-6",
            name = "Walk",
            createdAt = 0L,
            alarmTimes = listOf(7 * 60, 12 * 60, 18 * 60),
        )
        val data = AppData(habits = listOf(habit))
        val today = Dates.todayKey()

        val slots = HabitAlarmHistory.daySlots(data, "habit-6", dayKey = today)

        assertEquals(
            "three times of day must be three rows, not one collapsed entry",
            3,
            slots.size,
        )
        assertEquals(listOf(7 * 60, 12 * 60, 18 * 60), slots.map { it.scheduledMinutes })
        // Ascending, so the dialog presents the day in chronological order.
        assertEquals(slots.map { it.scheduledMinutes }.sorted(), slots.map { it.scheduledMinutes })
        assertEquals(listOf("07:00", "12:00", "18:00"), slots.map { it.clockLabel })
    }

    @Test
    fun `day slots record a fired occurrence separately for each time`() {
        val habit = Habit(
            id = "habit-7",
            name = "Walk",
            createdAt = 0L,
            alarmTimes = listOf(7 * 60, 18 * 60),
        )
        val today = Dates.todayKey()
        // Two rings, same habit, same day, different times. Built directly rather
        // than through HabitAlarmHistory.record, which takes a Context and
        // persists — this test is about the shape of the day's records, not the
        // storage, so it stays a pure JVM test.
        val data = AppData(
            habits = listOf(habit),
            alarmEvents = listOf(
                HabitAlarmEvent(
                    habitId = "habit-7",
                    dayKey = today,
                    scheduledMinutes = 7 * 60,
                    outcome = AlarmEventOutcome.FIRED,
                    firedAtEpochMs = 1_000L,
                    message = "Morning walk",
                ),
                HabitAlarmEvent(
                    habitId = "habit-7",
                    dayKey = today,
                    scheduledMinutes = 18 * 60,
                    outcome = AlarmEventOutcome.FIRED,
                    firedAtEpochMs = 2_000L,
                    message = "Evening walk",
                ),
            ),
        )

        val slots = HabitAlarmHistory.daySlots(data, "habit-7", dayKey = today)

        assertEquals(2, slots.size)
        // Each time carries its OWN event — the 07:00 ring did not overwrite the
        // 18:00 one, and neither collapsed into a single per-habit record. The
        // key is (habit, day, time), so the two are distinct by construction.
        assertEquals(AlarmSlotState.FIRED, slots.first { it.scheduledMinutes == 7 * 60 }.state)
        assertEquals(AlarmSlotState.FIRED, slots.first { it.scheduledMinutes == 18 * 60 }.state)
        // Each row shows the line THAT occurrence delivered, not a shared one.
        assertEquals("Morning walk", slots.first { it.scheduledMinutes == 7 * 60 }.message)
        assertEquals("Evening walk", slots.first { it.scheduledMinutes == 18 * 60 }.message)
        assertEquals(2, data.alarmEventsFor("habit-7").size)
    }

    @Test
    fun `day slots show a time as missed when it passed with no event`() {
        // 07:00 and 12:00 already elapsed with nothing recorded, 23:59 still ahead.
        val habit = Habit(
            id = "habit-8",
            name = "Walk",
            createdAt = 0L,
            alarmTimes = listOf(7 * 60, 12 * 60, 23 * 60 + 59),
        )
        val data = AppData(habits = listOf(habit))

        val slots = HabitAlarmHistory.daySlots(
            data = data,
            habitId = "habit-8",
            dayKey = Dates.todayKey(),
            nowMinutes = 13 * 60,
        )

        assertEquals(AlarmSlotState.MISSED, slots.first { it.scheduledMinutes == 7 * 60 }.state)
        assertEquals(AlarmSlotState.MISSED, slots.first { it.scheduledMinutes == 12 * 60 }.state)
        assertEquals(
            AlarmSlotState.PENDING,
            slots.first { it.scheduledMinutes == 23 * 60 + 59 }.state,
        )
    }

    @Test
    fun `a habit with no alarm has no occurrences to list`() {
        val habit = Habit(id = "habit-9", name = "Tidy Up", createdAt = 0L)
        val data = AppData(habits = listOf(habit))

        assertTrue(HabitAlarmHistory.daySlots(data, "habit-9").isEmpty())
    }
}
