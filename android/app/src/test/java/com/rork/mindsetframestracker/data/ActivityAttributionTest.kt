package com.rork.mindsetframestracker.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Activity attribution across the cloud round trip (D1) and the silent drop on
 * push (D2).
 *
 * ## The bugs behind these tests
 *
 * **D1** — the pull built every record with a hardcoded `habitId = ""`. Totals
 * stayed right while per-habit sourced consistency read zero on any second
 * device: all the data was present and none of it was usable.
 *
 * **D2** — the push `.filter`ed out every record whose habit id is not a UUID,
 * with no count, no message and no entry in the export's completeness list. It
 * was the only collection in the push with no reconciliation, so the loss was
 * invisible — and since an unattributed record is never pushed, it could never
 * be repaired from another device either.
 */
class ActivityAttributionTest {

    private fun record(
        id: String = "r1",
        habitId: String = "11111111-1111-1111-1111-111111111111",
        source: String = "strava",
    ) = ActivityRecord(
        id = id,
        habitId = habitId,
        source = source,
        activityType = "walking",
        timestamp = 1_700_000_000_000L,
    )

    // ── D1: the link survives the round trip ──────────────────────────────────

    @Test
    fun `a habit id written to raw_data is read back unchanged`() {
        // The round trip that was broken: written one way, read back as "".
        val id = "3f1c9b7e-1111-4222-8333-444455556666"
        val raw = activityRawData(source = "strava", habitId = id)
        assertEquals(id, habitIdFromActivityRawData(raw))
    }

    @Test
    fun `the payload carries source and sleep minutes alongside the link`() {
        val raw = activityRawData(source = "health_connect", habitId = "h1", sleepMinutes = 420)
        assertEquals("health_connect", raw["source"]?.let { it as JsonPrimitive }?.content)
        assertEquals("420", raw["sleep_minutes"]?.let { it as JsonPrimitive }?.content)
        assertEquals("h1", habitIdFromActivityRawData(raw))
    }

    @Test
    fun `a blank habit id is omitted rather than written as an empty string`() {
        val raw = activityRawData(source = "polar", habitId = "")
        assertFalse("a blank id must not be stored as a field", raw.containsKey(ACTIVITY_RAW_HABIT_ID))
        assertEquals("", habitIdFromActivityRawData(raw))
    }

    @Test
    fun `an unattributed record round-trips as unattributed, not as the first habit`() {
        // D1's other half: blank must stay blank. Inventing an id here would
        // silently attribute a Polar step roll-up to whichever habit happened to
        // be first, crediting a workout that never happened.
        val raw = activityRawData(source = "polar", habitId = "")
        assertEquals("", habitIdFromActivityRawData(raw))
    }

    @Test
    fun `a missing or absent raw payload yields no habit rather than throwing`() {
        // raw_data is server data this app does not control, and a restore must
        // not be able to be taken down by one malformed row.
        assertEquals("", habitIdFromActivityRawData(null))
        assertEquals("", habitIdFromActivityRawData(JsonObject(emptyMap())))
    }

    @Test
    fun `a non-string habit id value is tolerated rather than throwing`() {
        // raw_data is server data this app does not control, so a value that is
        // not the expected shape must not be able to take down a restore. A
        // primitive is read as its string form; a container cannot be a scalar
        // and is read as absent. Neither case may throw.
        val numeric = JsonObject(mapOf(ACTIVITY_RAW_HABIT_ID to JsonPrimitive(12345)))
        assertEquals("12345", habitIdFromActivityRawData(numeric))

        val nested = JsonObject(mapOf(ACTIVITY_RAW_HABIT_ID to JsonObject(mapOf("a" to JsonPrimitive(1)))))
        assertEquals("", habitIdFromActivityRawData(nested))
    }

    @Test
    fun `a non-UUID habit id still round-trips through raw_data`() {
        // The uuid COLUMN cannot hold these, which is exactly why the link rides
        // in raw_data: it must survive for an id the column would reject.
        val shared = "share-import-h1"
        val raw = activityRawData(source = "strava", habitId = shared)
        assertEquals(shared, habitIdFromActivityRawData(raw))
    }

    @Test
    fun `emoji and special characters in ids survive the round trip`() {
        val odd = "h-🌞-“walk”&<x>"
        assertEquals(odd, habitIdFromActivityRawData(activityRawData("strava", odd)))
    }

    // ── D2: the unattributable half is reported, not discarded ────────────────

    @Test
    fun `a record with no habit is routed to the reported half, not dropped`() {
        // THE D2 case. Under the old code this record simply ceased to exist.
        val (storable, reported) = partitionByAttributableHabitId(listOf(record(habitId = "")))

        assertTrue("nothing may be stored under a uuid column", storable.isEmpty())
        assertEquals("the record must still be counted and named", 1, reported.size)
    }

    @Test
    fun `a share-import habit id is routed to the reported half`() {
        val (storable, reported) = partitionByAttributableHabitId(listOf(record(habitId = "share-import-h1")))
        assertTrue(storable.isEmpty())
        assertEquals(1, reported.size)
    }

    @Test
    fun `a UUID habit id is storable`() {
        val (storable, reported) = partitionByAttributableHabitId(listOf(record()))
        assertEquals(1, storable.size)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `the partition never loses a record`() {
        // The whole point: `storable.size + reported.size == input.size`, for any
        // mix. A filter could not make this guarantee, which is why it is a
        // partition.
        val records = listOf(
            record(id = "a"),
            record(id = "b", habitId = ""),
            record(id = "c", habitId = "share-import-x"),
            record(id = "d", habitId = "22222222-2222-2222-2222-222222222222"),
            record(id = "e", habitId = "not-a-uuid"),
        )

        val (storable, reported) = partitionByAttributableHabitId(records)

        assertEquals(records.size, storable.size + reported.size)
        assertEquals(2, storable.size)
        assertEquals(3, reported.size)
    }

    @Test
    fun `an empty list partitions to two empty lists`() {
        val (storable, reported) = partitionByAttributableHabitId(emptyList())
        assertTrue(storable.isEmpty())
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a large set partitions without loss`() {
        // Large-data edge case, and the one that matters for D2: at scale, a
        // silent drop is easiest to miss.
        val records = (0 until 1000).map { i ->
            record(id = "r$i", habitId = if (i % 2 == 0) "" else "11111111-1111-1111-1111-111111111111")
        }
        val (storable, reported) = partitionByAttributableHabitId(records)
        assertEquals(1000, storable.size + reported.size)
        assertEquals(500, storable.size)
        assertEquals(500, reported.size)
    }
}
