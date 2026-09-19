package com.rork.mindsetframestracker.data

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weekly view must be measured against the days a habit was **due** (D3).
 *
 * ## The bug behind these tests
 *
 * The denominator was `dayKeys.size` — always 7. A habit that rings Monday–Friday
 * was therefore never due on a Sunday, but the Sunday still counted against it, so
 * a **perfect weekday habit could never read above "5 of 7 days"**, was
 * permanently listed in `needsAttention`, and the number contradicted the repeat
 * rule the user had just set in the alarm picker.
 *
 * `HabitRepeat.allows` (via `Habit.dueDayKeysIn`) already existed and was unused.
 * These tests are that wire, asserted in the direction that was broken.
 */
class WeeklyDueDaysTest {

    /** 2026-09-14 is a Monday, so the fixture week is Mon…Sun. */
    private val week = listOf(
        "2026-09-14", "2026-09-15", "2026-09-16",
        "2026-09-17", "2026-09-18", "2026-09-19", "2026-09-20",
    )

    private fun habit(id: String, mask: Int) =
        Habit(id = id, name = id, iconId = "walk", repeatDaysMask = mask)

    private fun data(habits: List<Habit>, checkIns: Map<String, List<String>> = emptyMap()) =
        AppData(habits = habits, checkIns = checkIns)

    // ── The regression itself ─────────────────────────────────────────────────

    @Test
    fun `a Monday-to-Friday habit is perfect when all five weekdays are done`() {
        // THE bug: this read `false` and "5 of 7".
        val monToFri = REPEAT_WEEKDAYS
        val weekdays = listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18")

        val row = data(
            listOf(habit("walk", monToFri)),
            mapOf("walk" to weekdays),
        ).weeklyConsistency(week).habits.single()

        assertTrue("five of five due days is perfect", row.isPerfect)
        assertEquals("5 of 5 days", row.label)
        assertEquals(1.0, row.ratio, 0.0001)
    }

    @Test
    fun `a weekend habit is perfect when both weekend days are done`() {
        val row = data(
            listOf(habit("walk", REPEAT_WEEKENDS)),
            mapOf("walk" to listOf("2026-09-19", "2026-09-20")),
        ).weeklyConsistency(week).habits.single()

        assertTrue(row.isPerfect)
        assertEquals("2 of 2 days", row.label)
    }

    @Test
    fun `a daily habit still divides by all seven days`() {
        // The other direction: the fix must not make a daily habit's denominator
        // smaller than its window.
        val row = data(
            listOf(habit("walk", REPEAT_DAILY)),
            mapOf("walk" to listOf("2026-09-14", "2026-09-15")),
        ).weeklyConsistency(week).habits.single()

        assertEquals("2 of 7 days", row.label)
        assertEquals(2.0 / 7.0, row.ratio, 0.0001)
        assertFalse(row.isPerfect)
    }

    @Test
    fun `a single-day custom mask is measured against that one day`() {
        // Only Mondays. One Monday, and it is a full week for this habit.
        val mondayOnly = 1 shl 0
        val row = data(
            listOf(habit("walk", mondayOnly)),
            mapOf("walk" to listOf("2026-09-14")),
        ).weeklyConsistency(week).habits.single()

        assertTrue(row.isPerfect)
        assertEquals("1 of 1 days", row.label)
    }

    @Test
    fun `a check-in on a day the habit was not due cannot inflate the ratio`() {
        // Otherwise the ratio exceeds 1.0 — a bar past 100%, "perfect" with days
        // to spare — which is how a scheduling bug would show up as a *better*
        // score instead of a worse one.
        val mondayOnly = 1 shl 0
        val row = data(
            listOf(habit("walk", mondayOnly)),
            mapOf("walk" to listOf("2026-09-14", "2026-09-15", "2026-09-16")),
        ).weeklyConsistency(week).habits.single()

        assertEquals("1 of 1 days", row.label)
        assertEquals(1.0, row.ratio, 0.0001)
    }

    // ── dueDayKeysIn directly ─────────────────────────────────────────────────

    @Test
    fun `dueDayKeysIn returns the weekdays for a weekday mask`() {
        assertEquals(
            listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18"),
            habit("h", REPEAT_WEEKDAYS).dueDayKeysIn(week),
        )
    }

    @Test
    fun `dueDayKeysIn returns every day for a non-repeating habit`() {
        // Mask 0 is also "fire once" and declares no cadence, so there is nothing
        // to measure against. Every day counts — the previous behaviour — rather
        // than a degenerate "0 of 0".
        assertEquals(week, habit("h", REPEAT_ONCE).dueDayKeysIn(week))
    }

    @Test
    fun `dueDayKeysIn maps each bit to the right weekday`() {
        // Bit i is the i-th day of week, and the positions must not be off by one
        // (the neighbouring defect in this area was a mask read by position
        // instead of by bit, which lost Monday entirely).
        val mondayOnly = 1 shl 0
        val sundayOnly = 1 shl 6
        assertEquals(listOf("2026-09-14"), habit("h", mondayOnly).dueDayKeysIn(week))
        assertEquals(listOf("2026-09-20"), habit("h", sundayOnly).dueDayKeysIn(week))
    }

    @Test
    fun `dueDayKeysIn tolerates a malformed day key rather than crediting it`() {
        // The window is always ISO, so an unparseable key is corrupt input.
        // Treating it as due would credit a day that may not exist.
        assertEquals(listOf("2026-09-14"), habit("h", REPEAT_WEEKDAYS).dueDayKeysIn(listOf("2026-09-14", "not-a-date")))
    }

    @Test
    fun `dueDayKeysIn handles an empty window`() {
        assertEquals(emptyList<String>(), habit("h", REPEAT_WEEKDAYS).dueDayKeysIn(emptyList()))
    }

    @Test
    fun `every day of week is representable and no two bits collide`() {
        // Exhaustive: for each single-day mask, exactly that one day is due.
        DayOfWeek.entries.forEachIndexed { index, day ->
            val mask = 1 shl index
            val due = habit("h", mask).dueDayKeysIn(week)
            assertEquals("mask for $day must select exactly one day", 1, due.size)
            assertEquals(day, LocalDate.parse(due.single()).dayOfWeek)
        }
    }

    @Test
    fun `the weekly view does not mark a weekday habit as needing attention`() {
        val weekdays = listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18")
        val result = data(
            listOf(habit("walk", REPEAT_WEEKDAYS)),
            mapOf("walk" to weekdays),
        ).weeklyConsistency(week)

        assertFalse(
            "a habit that met its own schedule must not be flagged",
            result.habits.single().habitId in result.needsAttention.map { it.habitId },
        )
    }

    @Test
    fun `a weekday habit that misses a due day is still flagged`() {
        // The fix must not make the flag useless: 3 of 5 due days is a miss.
        val result = data(
            listOf(habit("walk", REPEAT_WEEKDAYS)),
            mapOf("walk" to listOf("2026-09-14", "2026-09-15", "2026-09-16")),
        ).weeklyConsistency(week)

        assertEquals("3 of 5 days", result.habits.single().label)
        assertTrue(result.habits.single().habitId in result.needsAttention.map { it.habitId })
    }

    @Test
    fun `a rest day is not counted as a missed day`() {
        // The point of the whole finding: Sunday was never due.
        val row = data(
            listOf(habit("walk", REPEAT_WEEKDAYS)),
            mapOf("walk" to listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18")),
        ).weeklyConsistency(week).habits.single()

        assertFalse("2026-09-20" in row.dueDayKeys)
        assertFalse("2026-09-20" in row.doneKeys)
    }

    @Test
    fun `sourced activity for a habit is clipped to its due days too`() {
        val mondayOnly = 1 shl 0
        val records = listOf(
            ActivityRecord(
                id = "r1",
                habitId = "walk",
                source = "strava",
                activityType = "walking",
                timestamp = LocalDate.parse("2026-09-14")
                    .atStartOfDay(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli() + 12 * 3600_000L,
            ),
            ActivityRecord(
                id = "r2",
                habitId = "walk",
                source = "strava",
                activityType = "walking",
                timestamp = LocalDate.parse("2026-09-15")
                    .atStartOfDay(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli() + 12 * 3600_000L,
            ),
        )
        val row = AppData(
            habits = listOf(habit("walk", mondayOnly)),
            checkIns = mapOf("walk" to listOf("2026-09-14")),
            activityRecords = records,
        ).weeklyConsistency(week).habits.single()

        assertEquals("only the due-day record counts", 1, row.sourcedCount)
    }
}
