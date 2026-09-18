package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for the weekly consistency view.
 *
 * The request asks for the tracker data to be captured "into insights, including
 * a weekly consistency view", so these assert the two things that make such a
 * view trustworthy: **every** habit is represented (including one that is quietly
 * not happening, which is the whole point of looking), and a sourced day is
 * credited to the day the activity actually happened rather than the day it was
 * synced.
 */
class WeeklyConsistencyTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun habit(id: String, name: String = id) =
        Habit(id = id, name = name, createdAt = 0L, iconId = "walk")

    /** Epoch millis for a UTC wall-clock instant, so day buckets are deterministic. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val week = listOf(
        "2026-09-14", "2026-09-15", "2026-09-16",
        "2026-09-17", "2026-09-18", "2026-09-19", "2026-09-20",
    )

    private fun record(
        habitId: String,
        timestamp: Long,
        steps: Long? = 1000L,
        durationMinutes: Int? = 30,
        distanceMeters: Double? = 2000.0,
        source: String = "strava",
    ) = ActivityRecord(
        id = "r-${habitId}-$timestamp",
        habitId = habitId,
        source = source,
        activityType = "walking",
        timestamp = timestamp,
        durationMinutes = durationMinutes,
        distanceMeters = distanceMeters,
        steps = steps,
    )

    // ── Completeness ─────────────────────────────────────────────────────────

    @Test
    fun `every habit appears, including one with no completions at all`() {
        // A view that only lists what succeeded is exactly how a habit that is
        // quietly not happening stays invisible.
        val data = AppData(
            habits = listOf(habit("walk", "Walk"), habit("read", "Read")),
            checkIns = mapOf("walk" to listOf("2026-09-18")),
        )

        val result = data.weeklyConsistency(week)

        assertEquals(2, result.habits.size)
        assertEquals(setOf("walk", "read"), result.habits.map { it.habitId }.toSet())
        assertEquals(
            "the neglected habit must still be present, at zero",
            0,
            result.habits.first { it.habitId == "read" }.doneKeys.size,
        )
    }

    @Test
    fun `an install with no habits and no activity is empty`() {
        assertTrue(AppData().weeklyConsistency(week).isEmpty)
    }

    @Test
    fun `activity alone is not empty`() {
        val data = AppData(activityRecords = listOf(record("walk", at(2026, 9, 18))))
        val result = data.weeklyConsistency(week)
        assertFalse(result.isEmpty)
        assertEquals(1, result.activity.sessions)
    }

    // ── Days done ────────────────────────────────────────────────────────────

    @Test
    fun `done days come from check-ins and are clipped to the window`() {
        val data = AppData(
            habits = listOf(habit("walk")),
            checkIns = mapOf(
                "walk" to listOf("2026-09-14", "2026-09-18", "2026-01-01"),
            ),
        )

        val row = data.weeklyConsistency(week).habits.single()

        assertEquals(setOf("2026-09-14", "2026-09-18"), row.doneKeys)
        assertEquals("2 of 7 days", row.label)
    }

    @Test
    fun `a fully completed week is perfect and a partial one is not`() {
        val perfect = AppData(
            habits = listOf(habit("walk")),
            checkIns = mapOf("walk" to week),
        )
        val partial = AppData(
            habits = listOf(habit("walk")),
            checkIns = mapOf("walk" to week.dropLast(1)),
        )

        assertTrue(perfect.weeklyConsistency(week).habits.single().isPerfect)
        assertFalse(partial.weeklyConsistency(week).habits.single().isPerfect)
    }

    @Test
    fun `ratio is the share of the window completed`() {
        val data = AppData(
            habits = listOf(habit("walk")),
            checkIns = mapOf("walk" to listOf("2026-09-14", "2026-09-15")),
        )
        assertEquals(2.0 / 7.0, data.weeklyConsistency(week).habits.single().ratio, 0.0001)
    }

    @Test
    fun `overallRatio averages the habits`() {
        val data = AppData(
            habits = listOf(habit("a"), habit("b")),
            checkIns = mapOf("a" to week, "b" to emptyList()),
        )
        assertEquals(0.5, data.weeklyConsistency(week).overallRatio, 0.0001)
    }

    @Test
    fun `habits needing attention sort weakest first`() {
        val data = AppData(
            habits = listOf(habit("a"), habit("b"), habit("c")),
            checkIns = mapOf(
                "a" to week,                                              // perfect
                "b" to listOf("2026-09-14"),                              // 1/7
                "c" to listOf("2026-09-14", "2026-09-15", "2026-09-16"),   // 3/7
            ),
        )

        val order = data.weeklyConsistency(week).needsAttention.map { it.habitId }
        assertEquals(listOf("b", "c"), order)
    }

    // ── Sourced days ─────────────────────────────────────────────────────────

    @Test
    fun `a sourced day is marked on the day the activity happened`() {
        val data = AppData(
            habits = listOf(habit("walk")),
            activityRecords = listOf(record("walk", at(2026, 9, 16))),
        )

        val row = data.weeklyConsistency(week).habits.single()
        assertEquals(setOf("2026-09-16"), row.sourcedKeys)
        assertEquals("nothing was manually checked", emptySet<String>(), row.doneKeys)
    }

    @Test
    fun `an activity synced later is credited to the day it happened`() {
        // A run imported this morning that actually happened two days ago must
        // not be filed under today.
        val data = AppData(
            habits = listOf(habit("run")),
            activityRecords = listOf(record("run", at(2026, 9, 17))),
        )

        val row = data.weeklyConsistency(week).habits.single()
        assertTrue("the record's own day", "2026-09-17" in row.sourcedKeys)
        assertFalse("not the day it was read", "2026-09-19" in row.sourcedKeys)
    }

    @Test
    fun `sourcedCount counts only days that are both done and sourced`() {
        val data = AppData(
            habits = listOf(habit("walk")),
            checkIns = mapOf("walk" to listOf("2026-09-16", "2026-09-18")),
            // Sourced on the 16th and on the 15th, but the 15th was not checked.
            activityRecords = listOf(
                record("walk", at(2026, 9, 16)),
                record("walk", at(2026, 9, 15)),
            ),
        )

        assertEquals(1, data.weeklyConsistency(week).habits.single().sourcedCount)
    }

    @Test
    fun `activity outside the window is ignored`() {
        val data = AppData(
            habits = listOf(habit("walk")),
            activityRecords = listOf(
                record("walk", at(2026, 9, 1)),
                record("walk", at(2026, 9, 16)),
            ),
        )

        assertEquals(setOf("2026-09-16"), data.weeklyConsistency(week).habits.single().sourcedKeys)
    }

    @Test
    fun `a sourced day for another habit does not credit this one`() {
        val data = AppData(
            habits = listOf(habit("walk"), habit("swim")),
            activityRecords = listOf(record("swim", at(2026, 9, 16))),
        )

        val result = data.weeklyConsistency(week)
        assertEquals(setOf("2026-09-16"), result.habits.first { it.habitId == "swim" }.sourcedKeys)
        assertEquals(emptySet<String>(), result.habits.first { it.habitId == "walk" }.sourcedKeys)
    }

    // ── Activity totals ──────────────────────────────────────────────────────

    @Test
    fun `totals fold every source over the window`() {
        val data = AppData(
            activityRecords = listOf(
                record("walk", at(2026, 9, 16), steps = 3000L, durationMinutes = 20, distanceMeters = 2500.0, source = "strava"),
                record("walk", at(2026, 9, 17), steps = 2000L, durationMinutes = 10, distanceMeters = 1500.0, source = "health_connect"),
            ),
        )

        val totals = data.weeklyConsistency(week).activity
        assertEquals(2, totals.sessions)
        assertEquals(5000L, totals.steps)
        assertEquals(30, totals.durationMinutes)
        assertEquals(4000.0, totals.distanceMeters, 0.001)
    }

    @Test
    fun `totals exclude records outside the window`() {
        val data = AppData(
            activityRecords = listOf(
                record("walk", at(2026, 9, 16), steps = 1000L),
                record("walk", at(2026, 8, 16), steps = 9999L),
            ),
        )

        assertEquals(1000L, data.weeklyConsistency(week).activity.steps)
    }

    @Test
    fun `an empty window yields zero totals and no habits`() {
        val result = AppData(habits = listOf(habit("walk"))).weeklyConsistency(emptyList())
        assertEquals(0, result.habits.single().dayKeys.size)
        assertEquals(0.0, result.habits.single().ratio, 0.0001)
        assertEquals(0, result.activity.sessions)
    }

    // ── The rolling window ───────────────────────────────────────────────────

    @Test
    fun `lastSevenDayKeys is seven days, oldest first, ending today`() {
        val keys = lastSevenDayKeys(LocalDate.of(2026, 9, 19))

        assertEquals(7, keys.size)
        assertEquals("2026-09-13", keys.first())
        assertEquals("2026-09-19", keys.last())
        assertEquals("strictly ascending", keys.sorted(), keys)
    }

    @Test
    fun `the rolling window is contiguous with no gaps`() {
        val keys = lastSevenDayKeys(LocalDate.of(2026, 9, 19))
        val dates = keys.map { LocalDate.parse(it) }
        dates.zipWithNext().forEach { (a, b) ->
            assertEquals("each day follows the previous", a.plusDays(1), b)
        }
    }

    @Test
    fun `the rolling window handles a month boundary`() {
        val keys = lastSevenDayKeys(LocalDate.of(2026, 10, 2))
        assertEquals("2026-09-26", keys.first())
        assertEquals("2026-10-02", keys.last())
    }
}
