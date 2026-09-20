package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reset rule for a ringing alarm, asserted as a pure function.
 *
 * ## The request these tests pin
 *
 * *"When an alarm has already rung once, it must reset properly — right now it
 * does not reset after ringing."* and *"make sure the dialog appears when the
 * alarm rings."*
 *
 * Those two pull in opposite directions, and that is exactly why they need
 * pinning: the dialog must be shown **once** per ring (so it does not reappear
 * forever after it has been dealt with), and it must still be shown for **every**
 * ring (so a habit that rings three times a day does not go silent after the
 * first). A key that is too coarse satisfies the first and breaks the second; a
 * key that resets too eagerly satisfies the second and re-opens the first.
 *
 * ## Why this is a plain JVM test
 *
 * The project has no `androidTest` source set, so nothing that needs a device or
 * a Compose harness runs in CI here. The decision is therefore deliberately a
 * pure function of `(stored, day, time)` — [AlarmRingState.isAcceptedKey] — which
 * makes the whole rule assertable on the JVM.
 */
class AlarmRingResetTest {

    private val day = "2026-09-21"
    private val nextDay = "2026-09-22"

    /** 07:00, 12:00 and 18:00 in minutes from midnight. */
    private val morning = 7 * 60
    private val midday = 12 * 60
    private val evening = 18 * 60

    // ── The reset: a different occurrence is NOT suppressed ─────────────

    @Test
    fun `each of a habit's times rings its own dialog`() {
        // The regression this guards: keying the acknowledgement on the habit (or
        // on the day) would show the 07:00 dialog, mark the habit handled, and
        // then silently suppress the 12:00 and 18:00 alarms. The user would see
        // the habit "not reset" — it went quiet after the first ring.
        val afterMorning = AlarmRingState.occurrenceKey(day, morning)

        assertFalse(
            "The 12:00 alarm must still raise its dialog after 07:00 was delivered.",
            AlarmRingState.isAcceptedKey(afterMorning, day, midday),
        )
        assertFalse(
            "The 18:00 alarm must still raise its dialog after 07:00 was delivered.",
            AlarmRingState.isAcceptedKey(afterMorning, day, evening),
        )
    }

    @Test
    fun `a delivered alarm resets for the next day`() {
        // "It does not reset after ringing" in its most literal form: today's
        // acknowledgement must not leak into tomorrow, or the habit rings once
        // and is then permanently mute.
        val afterMorning = AlarmRingState.occurrenceKey(day, morning)

        assertTrue(
            "The ring it belongs to is acknowledged.",
            AlarmRingState.isAcceptedKey(afterMorning, day, morning),
        )
        assertFalse(
            "Tomorrow's 07:00 is a NEW occurrence and must not be suppressed by today's.",
            AlarmRingState.isAcceptedKey(afterMorning, nextDay, morning),
        )
    }

    // ── Once per ring: the SAME occurrence is not re-shown ───────────────

    @Test
    fun `the same occurrence is delivered exactly once`() {
        // A re-delivered AlarmManager intent, a second poll tick of the on-disk
        // request, or a stale request left behind by a process that died mid-ring
        // all resolve to the SAME key — so none of them can bring the dialog back.
        val accepted = AlarmRingState.occurrenceKey(day, morning)

        assertTrue(
            "A re-delivered ring for the same time must not raise a second dialog.",
            AlarmRingState.isAcceptedKey(accepted, day, morning),
        )
    }

    @Test
    fun `nothing has been delivered yet when no acknowledgement is stored`() {
        // The state after a reset, and the state on a fresh install: the very
        // first ring must always be shown.
        assertFalse(
            "With no record at all, a ring is always shown.",
            AlarmRingState.isAcceptedKey(null, day, morning),
        )
    }

    // ── The key itself ──────────────────────────────────────────────

    @Test
    fun `occurrences are distinct per day and per time`() {
        val keys = setOf(
            AlarmRingState.occurrenceKey(day, morning),
            AlarmRingState.occurrenceKey(day, midday),
            AlarmRingState.occurrenceKey(day, evening),
            AlarmRingState.occurrenceKey(nextDay, morning),
        )
        assertEquals(
            "Four different occurrences must be four different keys, or the gate " +
                "collapses the day's alarms into one.",
            4,
            keys.size,
        )
    }

    @Test
    fun `a request with no alarm time is still delivered exactly once`() {
        // The legacy shape: an older arming whose intent carries no alarm time.
        // It must not be silently dropped (never acknowledged, so it would be
        // re-shown forever), and it must not alias a real time either.
        val legacy = AlarmRingState.occurrenceKey(day, null)

        assertTrue(
            "A time-less request is acknowledged by its own key.",
            AlarmRingState.isAcceptedKey(legacy, day, null),
        )
        assertNotEquals(
            "A time-less key must never equal a real time's — that would let an " +
                "untimed ring suppress a genuine 23:59 alarm.",
            legacy,
            AlarmRingState.occurrenceKey(day, 23 * 60 + 59),
        )
    }
}
