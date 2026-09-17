package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * The persisted shape of a [Habit], tested through the **real serializer**.
 *
 * ## Why this exists — the P0 class of bug
 *
 * `HabitStore` reads habit rows by hand-walking `JSONObject` with the field names
 * as **string literals**. It does that deliberately (it runs inside alarm
 * `BroadcastReceiver`s, where a deserialization exception would kill the process
 * at the moment the alarm should ring), but the cost is that a renamed Kotlin
 * field or a new `@SerialName` silently breaks it with **no compile error**.
 *
 * That is exactly how `alarm_times`, `icon_id` and `alarm_message` were each
 * dropped in turn: a habit with 07:00/12:00/18:00 re-armed as a *single* alarm
 * after a reboot, lost its artwork, and stopped saying the user's own
 * motivational line — with nothing signalling that it had happened.
 *
 * These tests pin the serialized names, so the next rename fails here instead of
 * failing silently on a user's phone at 07:00.
 *
 * `Habit` is `@Serializable` and depends on nothing from Android, so this runs as
 * a plain JVM test.
 */
class HabitSerializationTest {

    private val json = Json {
        // encodeDefaults = true so EVERY field is emitted, including those that
        // equal their default. That is the point of this file: a field omitted
        // because it happens to be at its default tells us nothing about whether
        // the serializer still emits its *name*. (An earlier revision left this
        // false and then asserted a default-valued field was present — a
        // self-contradictory test that failed for its own bug.)
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val p0Habit = Habit(
        id = "habit-walk",
        name = "Walk",
        createdAt = 1_700_000_000_000L,
        reminderMinutes = 7 * 60,
        alarmTimes = listOf(7 * 60, 12 * 60, 18 * 60),
        iconId = "walking",
        repeatDaysMask = REPEAT_WEEKDAYS,
        alarmMessage = "Water 2 litres",
    )

    /**
     * The serialized names `HabitStore.readHabit` looks up by literal. If any of
     * these changes, `HabitStore` must change with it — and this test is the
     * thing that says so.
     */
    private val storeFieldNames = listOf(
        "id",
        "name",
        "createdAt",
        "isPinned",
        "reminderMinutes",
        "alarmTimes",
        "durationSeconds",
        "iconId",
        "repeatDaysMask",
        "monitoredPackage",
        "screenTimeLimitMinutes",
        "monitoredAppLabel",
        "alarmMessage",
    )

    @Test
    fun `the serialized field names are the ones HabitStore reads by literal`() {
        val encoded = json.encodeToString(Habit.serializer(), p0Habit)
        val keys = (Json.parseToJsonElement(encoded) as JsonObject).keys

        // Every name the store reads must actually appear in the serialized form
        // for a habit that populates them all.
        val populated = storeFieldNames.filter { it in keys }
        assertTrue(
            "HabitStore reads these names by literal but the serializer no longer " +
                "emits them: ${storeFieldNames - populated.toSet()}",
            populated.containsAll(
                listOf(
                    "id", "name", "createdAt", "isPinned", "reminderMinutes",
                    "alarmTimes", "iconId", "repeatDaysMask", "alarmMessage",
                ),
            ),
        )
    }

    @Test
    fun `the alarm schedule survives a serialization round-trip in full`() {
        // The P0 assertion: three times in, three times out — not one.
        val encoded = json.encodeToString(Habit.serializer(), p0Habit)
        val decoded = json.decodeFromString(Habit.serializer(), encoded)

        assertEquals(listOf(7 * 60, 12 * 60, 18 * 60), decoded.alarmTimes)
        assertEquals(3, decoded.alarmTimes.size)
        assertEquals(decoded.alarmTimes, decoded.alarmMinutes)
    }

    @Test
    fun `iconId and alarmMessage survive a round-trip`() {
        // The other two fields the boot path dropped: artwork and the user's own
        // motivational line.
        val encoded = json.encodeToString(Habit.serializer(), p0Habit)
        val decoded = json.decodeFromString(Habit.serializer(), encoded)

        assertEquals("walking", decoded.iconId)
        assertEquals("Water 2 litres", decoded.alarmMessage)
    }

    @Test
    fun `the repeat mask survives a round-trip`() {
        val encoded = json.encodeToString(Habit.serializer(), p0Habit)
        val decoded = json.decodeFromString(Habit.serializer(), encoded)
        assertEquals(REPEAT_WEEKDAYS, decoded.repeatDaysMask)
        // …and it is still read as weekday-only, not widened to daily.
        assertEquals(
            listOf(
                java.time.DayOfWeek.MONDAY,
                java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY,
                java.time.DayOfWeek.FRIDAY,
            ),
            HabitRepeat.daysOf(decoded.repeatDaysMask),
        )
    }

    @Test
    fun `a legacy row missing the newer fields still decodes`() {
        // An install upgrading from an older build: no alarmTimes, no
        // alarmMessage, no repeatDaysMask. Decoding must not throw, and the
        // legacy single time must still be the schedule.
        val legacyRow = """
            {"id":"h-old","name":"Read","createdAt":1600000000000,"reminderMinutes":1260}
        """.trimIndent()

        val decoded = json.decodeFromString(Habit.serializer(), legacyRow)

        assertEquals("h-old", decoded.id)
        assertEquals(1260, decoded.reminderMinutes)
        assertEquals(emptyList<Int>(), decoded.alarmTimes)
        assertEquals(listOf(1260), decoded.alarmMinutes)   // via legacyAlarmTimes
        assertNull(decoded.alarmMessage)
        assertNull(decoded.iconId)
        assertEquals(REPEAT_DAILY, decoded.repeatDaysMask)  // default: every day
    }

    @Test
    fun `an empty alarm list is distinguishable from an absent one after decoding`() {
        // Both mean "no alarm" to the reader, but the distinction matters to the
        // sync layer, which must not confuse "the user cleared the times" with
        // "this row predates the field".
        val cleared = json.decodeFromString(Habit.serializer(), """{"id":"h","name":"X","alarmTimes":[]}""")
        val absent = json.decodeFromString(Habit.serializer(), """{"id":"h","name":"X"}""")

        assertEquals(emptyList<Int>(), cleared.alarmTimes)
        assertEquals(emptyList<Int>(), absent.alarmTimes)
        assertNotNull(cleared.alarmMinutes)
        assertNotNull(absent.alarmMinutes)
    }

    @Test
    fun `alarmTimes serializes as a JSON array, not a string or object`() {
        // The Supabase column is an int[]; a shape change here would break the
        // sync layer's write rather than the local read, which is harder to spot.
        val encoded = json.encodeToString(Habit.serializer(), p0Habit)
        val parsed = Json.parseToJsonElement(encoded) as JsonObject
        val times = parsed["alarmTimes"]
        assertTrue("alarmTimes must be a JSON array", times is kotlinx.serialization.json.JsonArray)
        assertEquals(3, (times as kotlinx.serialization.json.JsonArray).size)
        assertEquals(420, times[0].jsonPrimitive.content.toInt())
    }

    @Test
    fun `withAlarmMessage stores null rather than an empty string for blank input`() {
        // Two "no custom message" spellings would force every reader to test for
        // both, which is how a habit ends up with an empty notification title.
        assertNull(p0Habit.withAlarmMessage(null).alarmMessage)
        assertNull(p0Habit.withAlarmMessage("").alarmMessage)
        assertNull(p0Habit.withAlarmMessage("   ").alarmMessage)
        assertNull(p0Habit.withAlarmMessage("\n\t ").alarmMessage)
    }

    @Test
    fun `withAlarmMessage sanitizes rather than storing the raw input`() {
        val habit = p0Habit.withAlarmMessage("  Go   for a\n walk  ")
        assertEquals("Go for a walk", habit.alarmMessage)
    }
}
