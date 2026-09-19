package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [restoredSchedule] — the restore path's own rule for a habit's alarm
 * schedule (D6), and the invariant breach it now reports instead of
 * "repairing".
 *
 * ## The bug behind these tests
 *
 * A cloud row with a **non-empty `alarm_times` and a null `reminder_minutes`**
 * breaches the invariant every writer in this app maintains
 * (`alarm_times.firstOrNull() == reminder_minutes`). It is reachable: the live
 * project never had the `alarm_times` migration applied, so a push drops that
 * field while `reminder_minutes = null` lands — leaving a stale list beside a
 * fresh null.
 *
 * The first fix for that case treated the pair as "the user cleared the alarm"
 * and returned an **empty schedule**. That quietly deleted every alarm on the
 * habit with no message, which is the "my alarm vanished" symptom this whole
 * path exists to end. The row cannot distinguish the two readings, and the
 * reading it picked was the one that loses data.
 *
 * These tests pin the safe direction: **keep the schedule, report the breach**.
 */
class RestoredScheduleTest {

    // ── The destructive case ──────────────────────────────────────────────────

    @Test
    fun `an inconsistent row keeps its alarms instead of clearing them`() {
        // THE regression. Under the old rule this returned emptyList().
        val restored = restoredSchedule(cloudAlarmTimes = listOf(21 * 60), cloudReminderMinutes = null)

        assertEquals("the user's alarm must not be deleted", listOf(21 * 60), restored.times)
        assertTrue("the breach must be reported", restored.inconsistent)
    }

    @Test
    fun `a multi-time schedule survives an inconsistent row intact`() {
        val times = listOf(7 * 60, 12 * 60, 18 * 60)
        val restored = restoredSchedule(times, null)
        assertEquals(times, restored.times)
        assertTrue(restored.inconsistent)
    }

    @Test
    fun `only the genuinely inconsistent pair is flagged`() {
        // Guards the flag itself: a flag that fires on valid rows would be noise
        // nobody reads, and one that never fires would be useless.
        assertTrue(restoredSchedule(listOf(7 * 60), null).inconsistent)
        assertFalse(restoredSchedule(listOf(7 * 60), 7 * 60).inconsistent)
        assertFalse(restoredSchedule(null, 7 * 60).inconsistent)
        assertFalse(restoredSchedule(emptyList(), null).inconsistent)
        assertFalse(restoredSchedule(null, null).inconsistent)
    }

    // ── The consistent rows still behave exactly as before ────────────────────

    @Test
    fun `a consistent row restores every time of a multi-alarm habit`() {
        val times = listOf(7 * 60, 12 * 60, 18 * 60)
        val restored = restoredSchedule(times, 7 * 60)
        assertEquals(times, restored.times)
        assertFalse(restored.inconsistent)
    }

    @Test
    fun `a pre-migration project still restores its single legacy time`() {
        val restored = restoredSchedule(cloudAlarmTimes = null, cloudReminderMinutes = 8 * 60)
        assertEquals(listOf(8 * 60), restored.times)
        assertFalse(restored.inconsistent)
    }

    @Test
    fun `a habit with no alarm anywhere restores as no alarm, unflagged`() {
        val restored = restoredSchedule(null, null)
        assertEquals(emptyList<Int>(), restored.times)
        assertFalse(restored.inconsistent)
    }

    @Test
    fun `restoredAlarmTimes delegates to the same rule`() {
        // The two must not drift: `restoredAlarmTimes` is the shape the sync layer
        // calls, `restoredSchedule` adds the flag.
        assertEquals(listOf(21 * 60), restoredAlarmTimes(listOf(21 * 60), null))
        assertEquals(listOf(7 * 60), restoredAlarmTimes(null, 7 * 60))
        assertEquals(emptyList<Int>(), restoredAlarmTimes(emptyList(), null))
        assertEquals(listOf(7 * 60, 18 * 60), restoredAlarmTimes(listOf(7 * 60, 18 * 60), 7 * 60))
    }

    @Test
    fun `out-of-range values are still dropped on the restore path`() {
        // The invariant report must not become a reason to keep corrupt times.
        assertEquals(listOf(7 * 60), restoredSchedule(listOf(-5, 7 * 60, 2000), 7 * 60).times)
    }
}
