package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [legacyAlarmTimes] — the one rule reconciling a habit's schedule, tested
 * against the disagreement it was written to end.
 *
 * ## The bug behind these tests
 *
 * Three call sites used to decide this independently, and the **pull** path read
 * an empty `alarm_times` as "the legacy `reminder_minutes` *is* the schedule"
 * while the **push** path derived `alarm_times` from the legacy column. Two
 * devices on different builds could therefore disagree about the same row, and a
 * user reading their own data back had their intent reinterpreted.
 *
 * The rule is now: a non-empty list wins outright; the legacy column is only
 * ever a fallback for an empty list.
 */
class LegacyScheduleTest {

    // ── The four cases the rule has to handle ────────────────────────────────

    @Test
    fun `a non-empty alarmTimes list wins outright`() {
        val times = listOf(7 * 60, 12 * 60, 18 * 60)
        assertEquals(times, legacyAlarmTimes(times, reminderMinutes = 7 * 60))
    }

    @Test
    fun `the explicit list wins even when the legacy column disagrees`() {
        // The exact divergence that broke cross-build sync: the list says
        // 07:00/12:00/18:00, the mirrored legacy column says something else. The
        // list is the user's real intent, so it must win — NOT be merged, and
        // NOT be replaced by the legacy value.
        val explicit = listOf(7 * 60, 12 * 60, 18 * 60)
        val result = legacyAlarmTimes(explicit, reminderMinutes = 9 * 60)
        assertEquals(explicit, result)
        assertTrue("the legacy value must not leak in", 9 * 60 !in result)
    }

    @Test
    fun `a single legacy time is a legacy habit's whole schedule`() {
        assertEquals(listOf(7 * 60), legacyAlarmTimes(alarmTimes = null, reminderMinutes = 7 * 60))
        assertEquals(listOf(7 * 60), legacyAlarmTimes(alarmTimes = emptyList(), reminderMinutes = 7 * 60))
    }

    @Test
    fun `no list and no legacy time means no alarm at all`() {
        assertEquals(emptyList<Int>(), legacyAlarmTimes(null, null))
        assertEquals(emptyList<Int>(), legacyAlarmTimes(emptyList(), null))
    }

    @Test
    fun `the full expected list is returned, never a partial one`() {
        // The P0 symptom on the boot path was a habit re-arming as a *subset* of
        // its times. Asserted by count as well as by value, so a future
        // "take(1)"-style regression cannot pass on an ordering coincidence.
        val times = listOf(7 * 60, 12 * 60, 18 * 60)
        val result = legacyAlarmTimes(times, null)
        assertEquals(3, result.size)
        assertEquals(times, result)
    }

    // ── Normalisation ────────────────────────────────────────────────────────

    @Test
    fun `result is ascending, deduped and bounded to a real time of day`() {
        assertEquals(
            listOf(0, 7 * 60, 18 * 60, 1439),
            legacyAlarmTimes(listOf(18 * 60, 7 * 60, 0, 7 * 60, 1439), null),
        )
    }

    @Test
    fun `out-of-range entries are dropped rather than clamped`() {
        // The array column's CHECK constraint accepts 0..1439, so an out-of-range
        // value is corrupt data. Dropping it keeps the schedule valid; clamping
        // it would silently move a user's alarm to midnight or 23:59.
        assertEquals(
            listOf(7 * 60),
            legacyAlarmTimes(listOf(-1, 7 * 60, 1440, 99999), null),
        )
    }

    @Test
    fun `a legacy value out of range yields no alarm, not a midnight alarm`() {
        assertEquals(emptyList<Int>(), legacyAlarmTimes(null, 5000))
        assertEquals(emptyList<Int>(), legacyAlarmTimes(null, -10))
    }

    // ── The reader built on top of it ────────────────────────────────────────

    @Test
    fun `Habit alarmMinutes reads through the same rule`() {
        val multi = Habit(id = "h1", name = "Walk", alarmTimes = listOf(7 * 60, 18 * 60), reminderMinutes = 7 * 60)
        assertEquals(listOf(7 * 60, 18 * 60), multi.alarmMinutes)
        assertTrue(multi.hasMultipleAlarms)

        val legacy = Habit(id = "h2", name = "Legacy", reminderMinutes = 8 * 60)
        assertEquals(listOf(8 * 60), legacy.alarmMinutes)

        val none = Habit(id = "h3", name = "None")
        assertEquals(emptyList<Int>(), none.alarmMinutes)
    }

    @Test
    fun `withAlarmTimes keeps reminderMinutes mirrored to the first entry`() {
        // The invariant that lets single-time readers (the reminder_minutes
        // column, the picker's badge) keep working without knowing about lists.
        val habit = Habit(id = "h1", name = "Walk")
            .withAlarmTimes(listOf(18 * 60, 7 * 60, 12 * 60, 7 * 60))

        assertEquals(listOf(7 * 60, 12 * 60, 18 * 60), habit.alarmTimes)
        assertEquals(7 * 60, habit.reminderMinutes)
        assertEquals(habit.alarmTimes, habit.alarmMinutes)
    }

    @Test
    fun `clearing the times clears the mirrored legacy column too`() {
        val habit = Habit(id = "h1", name = "Walk", reminderMinutes = 7 * 60, alarmTimes = listOf(7 * 60))
            .withAlarmTimes(emptyList())
        assertEquals(emptyList<Int>(), habit.alarmTimes)
        assertEquals(null, habit.reminderMinutes)
        assertEquals(emptyList<Int>(), habit.alarmMinutes)
    }

    @Test
    fun `withAlarmTimes drops out-of-range entries instead of storing them`() {
        val habit = Habit(id = "h1", name = "Walk").withAlarmTimes(listOf(-5, 7 * 60, 2000))
        assertEquals(listOf(7 * 60), habit.alarmTimes)
    }
}
