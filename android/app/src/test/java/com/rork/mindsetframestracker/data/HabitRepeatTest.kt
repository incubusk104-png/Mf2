package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The day-of-week scheduling rule, tested as the pure function it now is.
 *
 * ## What these tests are actually guarding
 *
 * The P0-era code decided the next alarm with a shortcut:
 *
 * ```kotlin
 * if (repeatDaysMask == REPEAT_ONCE || repeatDaysMask == REPEAT_DAILY) {
 *     return cal.timeInMillis          // "next occurrence of this time"
 * }
 * ```
 *
 * `REPEAT_ONCE` is mask **0** — also the value a set of day chips produces when
 * the last selected day is deselected. So a user who picked a custom schedule and
 * then cleared the days got a silently *daily* alarm, and the day selection
 * appeared to do nothing. The tests below fail against that shortcut and pass
 * against [HabitRepeat], which special-cases no mask.
 *
 * Time is injected everywhere (`now`, `zone`), so a "Friday evening rolls to
 * Monday" assertion is a fact rather than something only observable by waiting.
 */
class HabitRepeatTest {

    private val london: ZoneId = ZoneId.of("Europe/London")

    /** A handy "2026-09-14 (Mon) at hh:mm" local date-time. */
    private fun at(day: Int, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(2026, 9, day, hour, minute)

    // ── Mask arithmetic ──────────────────────────────────────────────────────

    @Test
    fun `maskOf and daysOf round-trip Monday-first`() {
        assertEquals(REPEAT_WEEKDAYS, HabitRepeat.maskOf(HabitRepeat.MONDAY_FIRST.take(5).toSet()))
        assertEquals(REPEAT_WEEKENDS, HabitRepeat.maskOf(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
        assertEquals(
            HabitRepeat.EVERY_DAY,
            HabitRepeat.maskOf(HabitRepeat.MONDAY_FIRST.toSet()),
        )
        HabitRepeat.MONDAY_FIRST.forEach { day ->
            assertEquals(listOf(day), HabitRepeat.daysOf(HabitRepeat.maskOf(setOf(day))))
        }
    }

    @Test
    fun `an empty day set is REPEAT_ONCE and that is precisely the hazard`() {
        // Documents WHY the picker must never let the last day be cleared: an
        // empty selection and "fire once" are the same value, so clearing the
        // last day silently changes the meaning of the schedule.
        assertEquals(REPEAT_ONCE, HabitRepeat.maskOf(emptySet()))
        assertFalse(HabitRepeat.isRepeating(HabitRepeat.maskOf(emptySet())))
        assertTrue(HabitRepeat.daysOf(REPEAT_ONCE).isEmpty())
    }

    // ── Daily, and the multi-time case the P0 bug collapsed ──────────────────

    @Test
    fun `daily habit later today returns today`() {
        val next = HabitRepeat.nextTrigger(
            minutesFromMidnight = 7 * 60,          // 07:00
            repeatDaysMask = REPEAT_DAILY,
            now = at(day = 14, hour = 6),          // Mon 06:00
            zone = london,
        )
        assertEquals(at(day = 14, hour = 7), next)
    }

    @Test
    fun `a time already passed today rolls to tomorrow, not today`() {
        val next = HabitRepeat.nextTrigger(
            minutesFromMidnight = 7 * 60,
            repeatDaysMask = REPEAT_DAILY,
            now = at(day = 14, hour = 9),          // Mon 09:00, past 07:00
            zone = london,
        )
        assertEquals(at(day = 15, hour = 7), next)
    }

    @Test
    fun `a time exactly equal to now rolls to tomorrow`() {
        // Strictly-ahead semantics: arming a fire time equal to the current
        // minute would fire immediately, which reads as a mystery notification.
        val next = HabitRepeat.nextTrigger(
            minutesFromMidnight = 7 * 60,
            repeatDaysMask = REPEAT_DAILY,
            now = at(day = 14, hour = 7, minute = 0),
            zone = london,
        )
        assertEquals(at(day = 15, hour = 7), next)
    }

    @Test
    fun `a 07-00-12-00-18-00 habit yields three distinct occurrences in one day`() {
        // The exact case the P0 bug collapsed into a single alarm. Each time must
        // resolve to its OWN moment on the SAME day — not one shared occurrence,
        // and not three rolled to tomorrow.
        val times = listOf(7 * 60, 12 * 60, 18 * 60)
        val morning = at(day = 14, hour = 6)

        val triggers = times.map { minutes ->
            HabitRepeat.nextTrigger(minutes, REPEAT_DAILY, morning, london)
        }

        assertEquals(
            listOf(
                at(day = 14, hour = 7),
                at(day = 14, hour = 12),
                at(day = 14, hour = 18),
            ),
            triggers,
        )
        // Three distinct instants — the property that makes them three separate
        // AlarmManager entries rather than one overwriting the others.
        assertEquals(3, triggers.distinct().size)
    }

    @Test
    fun `mid-as-day the morning alarm has already fired and the later two remain`() {
        val times = listOf(7 * 60, 12 * 60, 18 * 60)
        val noon = at(day = 14, hour = 13)         // Mon 13:00

        val triggers = times.map { HabitRepeat.nextTrigger(it, REPEAT_DAILY, noon, london) }

        // 07:00 -> tomorrow; 12:00 -> tomorrow; 18:00 -> still today.
        assertEquals(at(day = 15, hour = 7), triggers[0])
        assertEquals(at(day = 15, hour = 12), triggers[1])
        assertEquals(at(day = 14, hour = 18), triggers[2])
    }

    @Test
    fun `nextOfAll reports the earliest of the habit's times`() {
        val noon = at(day = 14, hour = 13)
        val next = HabitRepeat.nextOfAll(listOf(7 * 60, 12 * 60, 18 * 60), REPEAT_DAILY, noon, london)
        // The next thing the user will actually hear is this evening's 18:00.
        assertEquals(at(day = 14, hour = 18), next)
    }

    // ── Boundaries ───────────────────────────────────────────────────────────

    @Test
    fun `midnight and the last minute of the day are both valid and distinct`() {
        val midnight = HabitRepeat.nextTrigger(0, REPEAT_DAILY, at(day = 14, hour = 0), london)
        assertEquals(at(day = 15, hour = 0), midnight)   // 00:00 == now -> tomorrow

        val endOfDay = HabitRepeat.nextTrigger(1439, REPEAT_DAILY, at(day = 14, hour = 12), london)
        assertEquals(at(day = 14, hour = 23, minute = 59), endOfDay)
    }

    @Test
    fun `out-of-range minutes are clamped rather than throwing`() {
        // A corrupt or restored row must not crash the scheduler on boot.
        // -30 clamps to 00:00, which has already passed at 06:00, so it rolls to
        // tomorrow — the same answer a legitimate 00:00 alarm would give.
        val negative = HabitRepeat.nextTrigger(-30, REPEAT_DAILY, at(day = 14, hour = 6), london)
        assertEquals(at(day = 15, hour = 0), negative)

        val excessive = HabitRepeat.nextTrigger(9999, REPEAT_DAILY, at(day = 14, hour = 6), london)
        assertEquals(at(day = 14, hour = 23, minute = 59), excessive)
    }

    // ── Day-of-week masks — the actual reported bug ──────────────────────────

    @Test
    fun `weekdays mask skips the weekend`() {
        // Fri 18 Sep 2026, 20:00 — the next weekday 07:00 is Monday the 21st.
        val fridayEvening = LocalDateTime.of(2026, 9, 18, 20, 0)
        assertEquals(DayOfWeek.FRIDAY, fridayEvening.dayOfWeek)

        val next = HabitRepeat.nextTrigger(7 * 60, REPEAT_WEEKDAYS, fridayEvening, london)
        assertEquals(LocalDateTime.of(2026, 9, 21, 7, 0), next)
        assertEquals(DayOfWeek.MONDAY, next!!.dayOfWeek)
    }

    @Test
    fun `weekends mask skips the working week`() {
        // Mon 14 Sep 2026 -> next weekend 07:00 is Saturday the 19th.
        val next = HabitRepeat.nextTrigger(7 * 60, REPEAT_WEEKENDS, at(day = 14, hour = 6), london)
        assertEquals(LocalDateTime.of(2026, 9, 19, 7, 0), next)
        assertEquals(DayOfWeek.SATURDAY, next!!.dayOfWeek)
    }

    @Test
    fun `every single-day mask resolves to that day and no other`() {
        // Guards the bit order: Monday must be bit 0, not bit 6.
        HabitRepeat.MONDAY_FIRST.forEachIndexed { index, day ->
            val mask = HabitRepeat.maskOf(setOf(day))
            // Mon 14 Sep 2026, 06:00 — offset index days ahead is that weekday.
            val next = HabitRepeat.nextTrigger(7 * 60, mask, at(day = 14, hour = 6), london)
            assertEquals("mask for $day resolved to the wrong day", day, next!!.dayOfWeek)
            assertEquals(14 + index, next.dayOfMonth)
        }
    }

    @Test
    fun `an arbitrary custom mask is honoured exactly, not widened to daily`() {
        // Mon+Wed+Fri. From Tuesday morning, the next is Wednesday — NOT today,
        // and not tomorrow-every-day. This is the assertion the old
        // REPEAT_ONCE/REPEAT_DAILY shortcut fails.
        val custom = HabitRepeat.maskOf(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        )
        val tuesdayMorning = at(day = 15, hour = 6)      // Tue 15 Sep 2026

        assertEquals(DayOfWeek.WEDNESDAY, HabitRepeat.nextTrigger(7 * 60, custom, tuesdayMorning, london)!!.dayOfWeek)
        assertEquals(at(day = 16, hour = 7), HabitRepeat.nextTrigger(7 * 60, custom, tuesdayMorning, london))
    }

    @Test
    fun `a single-day-per-week mask stays weekly and never drifts`() {
        val mondayOnly = HabitRepeat.maskOf(setOf(DayOfWeek.MONDAY))
        // Mon 07:30, alarm at 07:00 -> a full week away.
        val next = HabitRepeat.nextTrigger(7 * 60, mondayOnly, at(day = 14, hour = 7, minute = 30), london)
        assertEquals(LocalDateTime.of(2026, 9, 21, 7, 0), next)
    }

    // ── One-shot ─────────────────────────────────────────────────────────────

    @Test
    fun `a one-shot still ahead today fires today`() {
        val next = HabitRepeat.nextTrigger(18 * 60, REPEAT_ONCE, at(day = 14, hour = 12), london)
        assertEquals(at(day = 14, hour = 18), next)
    }

    @Test
    fun `a one-shot that has already passed returns null rather than firing tomorrow`() {
        // The whole point of "Once": arming it would fire it tomorrow, which is
        // the opposite of once. null is the honest answer, and the scheduler
        // treats it as "do not arm".
        val next = HabitRepeat.nextTrigger(7 * 60, REPEAT_ONCE, at(day = 14, hour = 9), london)
        assertNull(next)
    }

    // ── DST — the reason the zone is a parameter ─────────────────────────────

    @Test
    fun `a spring-forward gap shifts a 01-30 alarm forward instead of vanishing`() {
        // Europe/London springs forward 2026-03-29: 01:00 -> 02:00, so 01:30
        // does not exist on that date. java.time moves it to the first valid
        // instant (02:30 BST) rather than throwing or silently dropping the
        // alarm — the same thing the OS clock does.
        val next = HabitRepeat.nextTrigger(
            minutesFromMidnight = 90,                       // 01:30
            repeatDaysMask = REPEAT_DAILY,
            now = LocalDateTime.of(2026, 3, 28, 22, 0),     // night before
            zone = london,
        )
        assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30), next)   // local wall clock
        // …and resolved through the zone it lands on the real instant 02:30 BST.
        val instant = next!!.atZone(london).toInstant()
        val resolved = LocalDateTime.ofInstant(instant, london)
        assertEquals(2, resolved.hour)
        assertEquals(30, resolved.minute)
    }

    @Test
    fun `an alarm survives the autumn fall-back without drifting an hour`() {
        // Europe/London falls back 2026-10-25: 02:00 -> 01:00, so 07:00 is
        // unambiguous and must remain 07:00 local across the change — the case a
        // naive "now + 24h" computation gets wrong by an hour.
        val beforeChange = LocalDateTime.of(2026, 10, 24, 6, 0)
        val next = HabitRepeat.nextTrigger(7 * 60, REPEAT_DAILY, beforeChange, london)
        assertEquals(LocalDateTime.of(2026, 10, 24, 7, 0), next)

        val afterChange = LocalDateTime.of(2026, 10, 25, 6, 0)
        val nextAfter = HabitRepeat.nextTrigger(7 * 60, REPEAT_DAILY, afterChange, london)
        assertEquals(LocalDateTime.of(2026, 10, 25, 7, 0), nextAfter)
        assertEquals(7, LocalDateTime.ofInstant(nextAfter!!.atZone(london).toInstant(), london).hour)
    }

    @Test
    fun `the same wall-clock time is a different instant in two zones`() {
        // Proves the zone parameter is actually used, and that a local wall-clock
        // time is not silently treated as UTC.
        val now = LocalDateTime.of(2026, 9, 14, 6, 0)
        val londonMs = HabitRepeat.nextTriggerMillis(7 * 60, REPEAT_DAILY, now, london)!!
        val newYorkMs = HabitRepeat.nextTriggerMillis(
            7 * 60,
            REPEAT_DAILY,
            now,
            ZoneId.of("America/New_York"),
        )!!
        assertTrue("expected different instants for different zones", londonMs != newYorkMs)
        // The local wall clock is still 07:00 in each.
        assertEquals(7, LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(londonMs), london).hour)
        assertEquals(
            7,
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(newYorkMs), ZoneId.of("America/New_York")).hour,
        )
    }

    // ── describe() — the summary the UI shows back ───────────────────────────

    @Test
    fun `describe names the presets and never calls a custom mask a preset`() {
        assertEquals("every day", HabitRepeat.describe(REPEAT_DAILY))
        assertEquals("on weekdays", HabitRepeat.describe(REPEAT_WEEKDAYS))
        assertEquals("on weekends", HabitRepeat.describe(REPEAT_WEEKENDS))
        assertEquals("once only", HabitRepeat.describe(REPEAT_ONCE))

        // The old UI rendered EVERY 5-day mask as "on weekdays", so a custom
        // Mon/Tue/Wed selection was described as something it was not.
        val custom = HabitRepeat.maskOf(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY))
        assertEquals("Mon, Tue, Wed", HabitRepeat.describe(custom))
    }

    @Test
    fun `default day names are three-letter labels with no ambiguous single letters`() {
        // Single letters were the original problem: "T" and "S" each name two
        // days, so a day chip could not be read back to check the selection.
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), HabitRepeat.DEFAULT_DAY_NAMES)
        assertEquals(7, HabitRepeat.DEFAULT_DAY_NAMES.distinct().size)
        // …and the proof that single letters are insufficient: the initials are
        // NOT unique. Tue/Thu share "T"; Sat/Sun share "S". (Asserting 7 unique
        // initials here would be asserting something false — an earlier revision
        // of this test did exactly that and failed.)
        assertEquals(5, HabitRepeat.DEFAULT_DAY_NAMES.map { it.first() }.distinct().size)
        assertTrue(
            "initials must stay ambiguous, which is why three letters are used",
            HabitRepeat.DEFAULT_DAY_NAMES.map { it.first() }.distinct().size < 7,
        )
    }
}
