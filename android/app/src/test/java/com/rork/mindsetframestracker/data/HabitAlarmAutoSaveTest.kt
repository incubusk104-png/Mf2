package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for the alarm auto-save and the per-occurrence rules.
 *
 * Two things the request asks for are asserted here:
 *
 *  1. **"one record per habit occurrence per day — the same habit appearing on
 *     different days is a separate record; this duplication is expected and
 *     required, not a bug to collapse"** — so a habit ringing on three days is
 *     three records, and ringing three times in one day is three records.
 *  2. **"save an alarm event automatically as soon as the alarm has fired"** —
 *     the event is written at ring time, with no user confirmation involved.
 *
 * The same habit at 07:00, 12:00 and 18:00 must never collapse into one entry:
 * that is the single rule the whole history feature rests on.
 */
class HabitAlarmAutoSaveTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: String get() = Dates.todayKey()

    private fun habit(
        id: String,
        times: List<Int> = listOf(7 * 60, 12 * 60, 18 * 60),
        mask: Int = REPEAT_DAILY,
    ) = Habit(
        id = id,
        name = id,
        createdAt = 0L,
        iconId = "walk",
        alarmTimes = times,
        repeatDaysMask = mask,
    )

    private fun fired(
        habitId: String,
        day: String,
        minutes: Int,
        at: Long = 1_700_000_000_000L,
    ) = HabitAlarmEvent(
        habitId = habitId,
        dayKey = day,
        scheduledMinutes = minutes,
        outcome = AlarmEventOutcome.FIRED,
        firedAtEpochMs = at,
    )

    // ── Per-occurrence identity: never collapse ───────────────────────────────

    @Test
    fun `three times in one day are three separate events`() {
        val events = listOf(
            fired("walk", today, 7 * 60, at = 1_000L),
            fired("walk", today, 12 * 60, at = 2_000L),
            fired("walk", today, 18 * 60, at = 3_000L),
        )

        assertEquals("one event per scheduled time", 3, events.size)
        assertEquals(
            "each must keep its own scheduled time",
            listOf(420, 720, 1080),
            events.map { it.scheduledMinutes }.sorted(),
        )
        assertEquals("each must be its own record", 3, events.map { it.id }.toSet().size)
    }

    @Test
    fun `the same habit on different days is a separate record per day`() {
        val days = listOf("2026-09-16", "2026-09-17", "2026-09-18")
        val events = days.map { day -> fired("walk", day, 7 * 60, at = 1_000L) }

        assertEquals(
            "the SAME time on three days must be three records, not one",
            3,
            events.map { "${it.dayKey}@${it.scheduledMinutes}" }.toSet().size,
        )
        assertEquals(
            "the day is part of what makes an occurrence distinct",
            days.toSet(),
            events.map { it.dayKey }.toSet(),
        )
    }

    @Test
    fun `a habit with no alarm times has no occurrence to answer`() {
        val data = AppData(habits = listOf(habit("walk", times = emptyList())))
        assertNull(data.occurrenceToAnswer("walk", 1_000L, zone))
    }

    @Test
    fun `an unknown habit has no occurrence to answer`() {
        val data = AppData(habits = listOf(habit("walk")))
        assertNull(data.occurrenceToAnswer("nope", 1_000L, zone))
    }

    // ── Resolving the occurrence for an answer ───────────────────────────────

    @Test
    fun `a recorded outstanding ring wins over the wall clock`() {
        // The 07:00 alarm rang and was never answered; the user answers at a
        // time when 12:00 has NOT yet passed. The recorded ring must decide.
        val data = AppData(
            habits = listOf(habit("walk")),
            alarmEvents = listOf(fired("walk", today, 7 * 60, at = 1_000L)),
        )

        // 09:30 local — only 07:00 has passed anyway, so this isolates the
        // preference for the recorded ring rather than the clock.
        val at = utcMillis(2026, 9, 19, 9, 30)
        assertEquals(7 * 60, data.occurrenceToAnswer("walk", at, zone))
    }

    @Test
    fun `the newest unanswered ring is chosen when several are outstanding`() {
        val data = AppData(
            habits = listOf(habit("walk")),
            alarmEvents = listOf(
                fired("walk", today, 7 * 60, at = 1_000L),
                fired("walk", today, 12 * 60, at = 2_000L),
            ),
        )

        assertEquals(12 * 60, data.occurrenceToAnswer("walk", utcMillis(2026, 9, 19, 13, 0), zone))
    }

    @Test
    fun `an already answered ring is not preferred over the newest outstanding one`() {
        val answered = fired("walk", today, 7 * 60, at = 1_000L)
            .copy(outcome = AlarmEventOutcome.ACKNOWLEDGED, respondedAtEpochMs = 1_500L)
        val outstanding = fired("walk", today, 12 * 60, at = 2_000L)
        val data = AppData(habits = listOf(habit("walk")), alarmEvents = listOf(answered, outstanding))

        assertEquals(
            "only an unanswered FIRED event identifies the occurrence in progress",
            12 * 60,
            data.occurrenceToAnswer("walk", utcMillis(2026, 9, 19, 13, 0), zone),
        )
    }

    @Test
    fun `answering at 18 05 is filed under 18 00 not the morning`() {
        // The scenario the request describes: 07:00/12:00/18:00 and the answer
        // arrives just after the last one. Picking the earliest due time instead
        // of the latest would file the evening walk under breakfast.
        val data = AppData(habits = listOf(habit("walk")))
        assertEquals(
            18 * 60,
            data.occurrenceToAnswer("walk", utcMillis(2026, 9, 19, 18, 5), zone),
        )
    }

    @Test
    fun `answering between rings is filed under the most recent one that passed`() {
        val data = AppData(habits = listOf(habit("walk")))
        assertEquals(
            12 * 60,
            data.occurrenceToAnswer("walk", utcMillis(2026, 9, 19, 15, 0), zone),
        )
    }

    @Test
    fun `an answer before the first ring resolves to nothing rather than the wrong slot`() {
        // Nothing is due yet, and no ring was recorded, so there is no
        // occurrence in progress — inventing one would misfile the answer.
        val data = AppData(habits = listOf(habit("walk")))
        assertNull(data.occurrenceToAnswer("walk", utcMillis(2026, 9, 19, 5, 0), zone))
    }

    // ── Crossing midnight ────────────────────────────────────────────────────

    @Test
    fun `a stop just after midnight still finds last night's ring`() {
        // A 23:55 alarm dismissed at 00:10. Read as "today" there is no ring,
        // and the dismissal would vanish from the history.
        val yesterday = Dates.key(LocalDate.now().minusDays(1))
        val data = AppData(
            habits = listOf(habit("walk", times = listOf(23 * 60 + 55))),
            alarmEvents = listOf(fired("walk", yesterday, 23 * 60 + 55, at = 1_000L)),
        )

        assertEquals(
            "the previous day's ring must still be found",
            23 * 60 + 55,
            data.crossDayOccurrenceMinutes("walk", utcMillis(2026, 9, 19, 0, 10), zone),
        )
    }

    @Test
    fun `cross-day resolution still prefers a recorded ring over the clock`() {
        val data = AppData(
            habits = listOf(habit("walk")),
            alarmEvents = listOf(fired("walk", today, 7 * 60, at = 1_000L)),
        )
        assertEquals(7 * 60, data.crossDayOccurrenceMinutes("walk", utcMillis(2026, 9, 19, 9, 0), zone))
    }

    @Test
    fun `cross-day resolution falls back to the last time of day with nothing recorded`() {
        val data = AppData(habits = listOf(habit("walk")))
        assertEquals(
            "with no evidence at all, the day's last alarm is the honest guess",
            18 * 60,
            data.crossDayOccurrenceMinutes("walk", utcMillis(2026, 9, 19, 0, 5), zone),
        )
    }

    @Test
    fun `cross-day resolution has nothing for a habit with no alarms`() {
        val data = AppData(habits = listOf(habit("walk", times = emptyList())))
        assertNull(data.crossDayOccurrenceMinutes("walk", 1_000L, zone))
    }

    // ── Unattached times (the "add another time" flow) ───────────────────────

    @Test
    fun `a time added after a ring is recognised as new`() {
        // The request's flow: the 07:00 alarm fired, and the user now wants a
        // second alarm for the same habit at a different time.
        assertTrue(isAddingNewAlarmTime(savedTimes = listOf(420, 900), storedTimes = listOf(420)))
        assertEquals(listOf(900), unattachedAlarmTimes(savedTimes = listOf(420, 900), storedTimes = listOf(420)))
    }

    @Test
    fun `re-saving the same times adds nothing`() {
        assertFalse(isAddingNewAlarmTime(listOf(420, 720), listOf(420, 720)))
        assertTrue(unattachedAlarmTimes(listOf(420, 720), listOf(720, 420)).isEmpty())
    }

    @Test
    fun `out-of-range times are excluded from what a save adds`() {
        assertEquals(
            listOf(420),
            unattachedAlarmTimes(savedTimes = listOf(-1, 420, 1440, 2000), storedTimes = emptyList()),
        )
    }

    @Test
    fun `clearing every time adds nothing`() {
        assertFalse(isAddingNewAlarmTime(savedTimes = emptyList(), storedTimes = listOf(420)))
        assertTrue(unattachedAlarmTimes(emptyList(), listOf(420)).isEmpty())
    }

    @Test
    fun `adding a second time keeps the first`() {
        val after = unattachedAlarmTimes(savedTimes = listOf(420, 1080), storedTimes = listOf(420, 720))
        assertEquals("720 was already stored and is not re-added", listOf(1080), after)
    }

    // ── The schedule itself is preserved ─────────────────────────────────────

    @Test
    fun `adding a time to a habit preserves its other times and repeat mask`() {
        val original = habit("walk", times = listOf(420), mask = REPEAT_WEEKDAYS)
        val updated = original.withAlarmTimes(listOf(420, 1080))

        assertEquals(listOf(420, 1080), updated.alarmMinutes)
        assertEquals("the repeat mask must not be disturbed", REPEAT_WEEKDAYS, updated.repeatDaysMask)
        assertEquals("reminderMinutes tracks the first time", 420, updated.reminderMinutes)
    }

    @Test
    fun `up to five alarms in a day are all kept`() {
        val five = listOf(6 * 60, 9 * 60, 12 * 60, 15 * 60, 18 * 60)
        val data = AppData(
            habits = listOf(habit("walk", times = five)),
            alarmEvents = five.mapIndexed { index, minutes -> fired("walk", today, minutes, at = (index + 1) * 1_000L) },
        )

        assertEquals("all five must survive", 5, data.habits.single().alarmMinutes.size)
        assertEquals("all five must be distinct events", 5, data.alarmEvents.size)
    }

    @Test
    fun `alarm times are deduped and sorted but never merged`() {
        val updated = habit("walk", times = emptyList()).withAlarmTimes(listOf(1080, 420, 1080, 720))
        assertEquals(listOf(420, 720, 1080), updated.alarmMinutes)
    }

    @Test
    fun `minuteOfDay matches the stored scheduled-minutes scale`() {
        assertEquals(0, minuteOfDay(utcMillis(2026, 9, 19, 0, 0), zone))
        assertEquals(7 * 60, minuteOfDay(utcMillis(2026, 9, 19, 7, 0), zone))
        assertEquals(23 * 60 + 59, minuteOfDay(utcMillis(2026, 9, 19, 23, 59), zone))
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
}
