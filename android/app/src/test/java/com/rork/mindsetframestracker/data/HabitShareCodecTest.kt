package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share-code round trip, and the import merge rules.
 *
 * ## Why the import rules are tested this strictly
 *
 * The store is local-only and there is no undo. An import that duplicated every
 * habit and every check-in would be unrecoverable, and one that silently merged
 * a stranger's habit into an existing one with a colliding id would corrupt the
 * user's own history. Both are much cheaper to prevent here than to explain
 * later, so the identity and dedupe behaviour is pinned explicitly.
 *
 * Pure JVM test - the codec has no Android dependency.
 */
class HabitShareCodecTest {

    private val walk = Habit(
        id = "habit-walk",
        name = "Walk",
        alarmTimes = listOf(7 * 60, 18 * 60),
        iconId = "walking",
        repeatDaysMask = REPEAT_DAILY,
    )

    private val water = Habit(
        id = "habit-water",
        name = "Water 💧",
        alarmTimes = listOf(21 * 60),
        iconId = "water",
    )

    private fun dataWithHistory(): AppData = AppData(
        habits = listOf(walk, water),
        checkIns = mapOf(
            "habit-walk" to listOf("2026-09-16", "2026-09-17"),
            "habit-water" to listOf("2026-09-17"),
        ),
        habitLogs = listOf(
            HabitLogEntry(
                id = "log-1", habitId = "habit-water", dayKey = "2026-09-17",
                mode = HabitTrackingMode.COUNT, count = 6, unit = "glasses",
            ),
        ),
        alarmEvents = listOf(
            HabitAlarmEvent(
                id = "ev-1", habitId = "habit-walk", dayKey = "2026-09-17",
                scheduledMinutes = 7 * 60, outcome = AlarmEventOutcome.ACKNOWLEDGED,
                message = "Morning walk 🌅",
            ),
            HabitAlarmEvent(
                id = "ev-2", habitId = "habit-walk", dayKey = "2026-09-17",
                scheduledMinutes = 18 * 60, outcome = AlarmEventOutcome.SNOOZED,
            ),
        ),
        moodHistory = mapOf("2026-09-17" to MoodMode.CALM),
        reflections = mapOf("2026-09-17" to "Steady day"),
    )

    private fun bundle(data: AppData = dataWithHistory()) =
        HabitDataExport.build(data, appVersionName = "1.1.1", appVersionCode = 22)

    // ── Encode / decode ──────────────────────────────────────────────────────

    /** A code round-trips, carrying habits and their history. */
    @Test
    fun `code round trips with history`() {
        val code = HabitShareCodec.encode(bundle())
        val result = HabitShareCodec.decode(code)
        assertTrue("decode must succeed", result is HabitShareCodec.DecodeResult.Success)

        val payload = (result as HabitShareCodec.DecodeResult.Success).payload
        assertEquals(2, payload.habits.size)
        assertEquals(HabitShareCodec.ShareScope.WITH_HISTORY.name, payload.scope)
        assertEquals("Walk", payload.habits.first { it.habit.id == "habit-walk" }.habit.name)
        assertEquals(2, payload.habits.first { it.habit.id == "habit-walk" }.alarmEvents.size)
        // The per-occurrence messages came across with the history.
        assertEquals(
            "Morning walk 🌅",
            payload.habits.first { it.habit.id == "habit-walk" }
                .alarmEvents.first { it.scheduledMinutes == 7 * 60 }.message,
        )
        assertEquals(1, payload.moodHistory.size)
    }

    /** Definitions-only drops history but keeps every habit and its settings. */
    @Test
    fun `definitions only code keeps habits and drops history`() {
        val code = HabitShareCodec.encode(bundle(), includeHistory = false)
        val payload = (HabitShareCodec.decode(code) as HabitShareCodec.DecodeResult.Success).payload

        assertEquals(2, payload.habits.size)
        assertEquals(HabitShareCodec.ShareScope.DEFINITIONS_ONLY.name, payload.scope)
        assertTrue(payload.habits.all { it.checkIns.isEmpty() })
        assertTrue(payload.habits.all { it.alarmEvents.isEmpty() })
        assertTrue(payload.moodHistory.isEmpty())
        // The definition itself - the thing being shared - is untouched.
        val walkEntry = payload.habits.first { it.habit.id == "habit-walk" }
        assertEquals(listOf(7 * 60, 18 * 60), walkEntry.habit.alarmTimes)
        assertEquals("walking", walkEntry.habit.iconId)
    }

    /** A definitions-only code is smaller than the full one (deflate is doing work). */
    @Test
    fun `definitions only code is shorter`() {
        val full = HabitShareCodec.encode(bundle(), includeHistory = true)
        val defs = HabitShareCodec.encode(bundle(), includeHistory = false)
        assertTrue(
            "definitions-only (${defs.length}) must be shorter than full (${full.length})",
            defs.length < full.length,
        )
    }

    /** The code is text-safe for any chat app or URL. */
    @Test
    fun `code uses only url safe characters`() {
        val code = HabitShareCodec.encode(bundle())
        assertTrue(code.startsWith(HabitShareCodec.PREFIX))
        val body = code.removePrefix(HabitShareCodec.PREFIX)
        assertTrue(
            "body must be base64url with no padding",
            body.all { it.isLetterOrDigit() || it == '-' || it == '_' },
        )
        assertFalse(body.contains('='))
        assertFalse(body.contains('+'))
        assertFalse(body.contains('/'))
    }

    /** Emoji and non-Latin names survive the deflate/base64/JSON pipeline. */
    @Test
    fun `emoji and unicode survive the code`() {
        val awkward = Habit(id = "h-😀", name = "Méditer 🧘🏽‍♀️ 中文 العربية", alarmMessage = "Breathe 🌬️")
        // The day must be checked in for its reflection to travel with the habit
        // (see HabitDataExport: a reflection is filed under the first habit
        // checked in that day, and a day with no check-in has no habit to
        // attach to).
        val data = AppData(
            habits = listOf(awkward),
            checkIns = mapOf("h-😀" to listOf("2026-09-17")),
            reflections = mapOf("2026-09-17" to "🎉 done"),
        )
        val payload = (
            HabitShareCodec.decode(HabitShareCodec.encode(bundle(data)))
                as HabitShareCodec.DecodeResult.Success
            ).payload

        assertEquals("Méditer 🧘🏽‍♀️ 中文 العربية", payload.habits.single().habit.name)
        assertEquals("Breathe 🌬️", payload.habits.single().habit.alarmMessage)
        assertEquals("🎉 done", payload.habits.single().reflections["2026-09-17"])
    }

    /** Whitespace from a wrapping chat client is tolerated. */
    @Test
    fun `code tolerates surrounding and wrapped whitespace`() {
        val code = HabitShareCodec.encode(bundle())
        val wrapped = "  " + code.chunked(40).joinToString("\n") + "  \n"
        assertTrue(HabitShareCodec.decode(wrapped) is HabitShareCodec.DecodeResult.Success)
    }

    // ── Failure modes, each distinguished ────────────────────────────────────

    @Test
    fun `plain text is not a share code`() {
        assertTrue(HabitShareCodec.decode("hello world") is HabitShareCodec.DecodeResult.NotAShareCode)
        assertTrue(HabitShareCodec.decode("") is HabitShareCodec.DecodeResult.NotAShareCode)
        assertTrue(HabitShareCodec.decode("{}") is HabitShareCodec.DecodeResult.NotAShareCode)
    }

    /** A damaged body is reported as corrupt, not as "not a code". */
    @Test
    fun `truncated code is reported as corrupt`() {
        val code = HabitShareCodec.encode(bundle())
        val truncated = code.substring(0, code.length - 20)
        assertTrue(HabitShareCodec.decode(truncated) is HabitShareCodec.DecodeResult.Corrupt)
    }

    /**
     * A code stamped with a future schema is refused rather than partially
     * imported - a newer build may have fields whose absence would silently
     * change what a habit means.
     *
     * The payload is re-serialised by hand so the guard is exercised for real
     * rather than merely asserted to exist.
     */
    @Test
    fun `future schema version is refused`() {
        val future = HabitShareCodec.SharePayload(
            schemaVersion = EXPORT_SCHEMA_VERSION + 1,
            habits = emptyList(),
        )
        val body = HabitShareCodec.encodePayload(future)
        val result = HabitShareCodec.decode(body)
        assertTrue(
            "a future schema must be reported as TooNew, not imported",
            result is HabitShareCodec.DecodeResult.TooNew,
        )
        assertEquals(EXPORT_SCHEMA_VERSION + 1, (result as HabitShareCodec.DecodeResult.TooNew).schemaVersion)
    }

    /** A payload that is not a Mindset Frames document is rejected outright. */
    @Test
    fun `foreign payload is reported as corrupt`() {
        val foreign = HabitShareCodec.SharePayload(format = "something.else")
        val body = HabitShareCodec.encodePayload(foreign)
        assertTrue(HabitShareCodec.decode(body) is HabitShareCodec.DecodeResult.Corrupt)
    }

    // ── Import planning ──────────────────────────────────────────────────────

    /** Importing into an empty install brings everything across. */
    @Test
    fun `import into empty install adds everything`() {
        val payload = (
            HabitShareCodec.decode(HabitShareCodec.encode(bundle()))
                as HabitShareCodec.DecodeResult.Success
            ).payload
        val plan = HabitShareCodec.planImport(AppData(), payload)

        assertEquals(2, plan.newHabits.size)
        assertTrue(plan.duplicateHabits.isEmpty())
        assertEquals(3, plan.newCheckIns)
        assertEquals(1, plan.newLogs)
        assertEquals(2, plan.newAlarmEvents)
        assertFalse(plan.isEmpty)

        val merged = HabitShareCodec.applyImport(AppData(), plan)
        assertEquals(2, merged.habits.size)
        assertEquals(3, merged.checkIns.values.sumOf { it.size })
        assertEquals(2, merged.alarmEvents.size)
    }

    /** Re-importing the same code is a no-op, not a duplication. */
    @Test
    fun `re-importing the same code changes nothing`() {
        val payload = (
            HabitShareCodec.decode(HabitShareCodec.encode(bundle()))
                as HabitShareCodec.DecodeResult.Success
            ).payload

        val first = HabitShareCodec.applyImport(AppData(), HabitShareCodec.planImport(AppData(), payload))
        val plan = HabitShareCodec.planImport(first, payload)

        assertTrue("second import must add no habits", plan.newHabits.isEmpty())
        assertEquals(2, plan.duplicateHabits.size)
        assertEquals(0, plan.newCheckIns)
        assertEquals(0, plan.newLogs)
        assertEquals(0, plan.newAlarmEvents)
        assertTrue("second import must be a no-op", plan.isEmpty)

        val second = HabitShareCodec.applyImport(first, plan)
        assertEquals(first.habits.size, second.habits.size)
        assertEquals(first.checkIns.values.sumOf { it.size }, second.checkIns.values.sumOf { it.size })
    }

    /**
     * A habit id already taken by a **different** habit is renamed, and its
     * history follows it rather than merging into the stranger.
     */
    @Test
    fun `colliding id is renamed and history follows`() {
        val mine = Habit(id = "habit-walk", name = "My own walk", alarmTimes = listOf(6 * 60))
        val existing = AppData(
            habits = listOf(mine),
            checkIns = mapOf("habit-walk" to listOf("2026-01-01")),
        )
        val payload = (
            HabitShareCodec.decode(HabitShareCodec.encode(bundle()))
                as HabitShareCodec.DecodeResult.Success
            ).payload

        val plan = HabitShareCodec.planImport(existing, payload)
        assertTrue("a rename must be reported", plan.renamedHabits.isNotEmpty())
        assertEquals("habit-walk", plan.renamedHabits.first().first)
        assertEquals("imported-habit-walk", plan.renamedHabits.first().second)

        val merged = HabitShareCodec.applyImport(existing, plan)
        // My own habit is untouched...
        assertTrue(merged.habits.any { it.id == "habit-walk" && it.name == "My own walk" })
        // ...and the imported one landed under a fresh id, with its history.
        val imported = merged.habits.first { it.id == "imported-habit-walk" }
        assertEquals("Walk", imported.name)
        assertEquals(listOf("2026-09-16", "2026-09-17"), merged.checkIns["imported-habit-walk"])
        // My own check-in was not merged into it.
        assertFalse((merged.checkIns["imported-habit-walk"] ?: emptyList()).contains("2026-01-01"))
        assertTrue(merged.alarmEvents.all { it.habitId != "habit-walk" })
    }

    /** An identical habit is deduped rather than duplicated. */
    @Test
    fun `identical habit is treated as a duplicate`() {
        val payload = (
            HabitShareCodec.decode(HabitShareCodec.encode(bundle()))
                as HabitShareCodec.DecodeResult.Success
            ).payload
        val existing = AppData(habits = listOf(walk))
        val plan = HabitShareCodec.planImport(existing, payload)
        assertTrue(plan.duplicateHabits.contains("Walk"))
        assertTrue(plan.newHabits.none { it.habit.id == "habit-walk" })
    }

    /** Importing history for a habit I already have adds only the new days. */
    @Test
    fun `import merges history for an existing identical habit`() {
        val existing = AppData(
            habits = listOf(walk, water),
            checkIns = mapOf("habit-walk" to listOf("2026-09-16")),
        )
        val payload = (
            HabitShareCodec.decode(HabitShareCodec.encode(bundle()))
                as HabitShareCodec.DecodeResult.Success
            ).payload
        val plan = HabitShareCodec.planImport(existing, payload)

        // Only 2026-09-17 is new for walk, plus water's 2026-09-17 is present too
        // in the incoming payload, so 2 new check-ins total.
        assertEquals(2, plan.newCheckIns)
        assertEquals(2, plan.duplicateHabits.size)
        assertTrue(plan.newHabits.isEmpty())
    }

    /** An empty export still encodes and decodes cleanly. */
    @Test
    fun `empty export produces a valid code`() {
        val code = HabitShareCodec.encode(HabitDataExport.build(AppData()))
        val payload = (HabitShareCodec.decode(code) as HabitShareCodec.DecodeResult.Success).payload
        assertTrue(payload.habits.isEmpty())
        assertFalse(payload.format.isBlank())
    }

    /** A code made from a big data set still decodes, and deflate keeps it sane. */
    @Test
    fun `large export compresses and round trips`() {
        val habits = (0 until 20).map { Habit(id = "h$it", name = "Habit $it", alarmTimes = listOf(7 * 60)) }
        val days = (0 until 100).map { java.time.LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString() }
        val data = AppData(habits = habits, checkIns = habits.associate { it.id to days })
        val code = HabitShareCodec.encode(HabitDataExport.build(data))
        val payload = (HabitShareCodec.decode(code) as HabitShareCodec.DecodeResult.Success).payload
        assertEquals(20, payload.habits.size)
        assertEquals(2000, payload.habits.sumOf { it.checkIns.size })
    }
}
