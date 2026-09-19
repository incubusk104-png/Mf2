package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An imported habit that **already exists** still contributes its history (D4).
 *
 * ## The bug behind these tests
 *
 * `planImport` classified an already-present habit as a duplicate and kept only
 * its **name** — a `List<String>`. `applyImport` then iterated `newHabits` only,
 * so the incoming history for a duplicate was never merged. The method carried a
 * comment claiming it merged them ("Existing duplicate habits may still carry
 * history the sender has and we don't"), but the data had been discarded one step
 * earlier, making the claim unreachable code.
 *
 * The user-visible symptom is D4: a shared habit arrives with its records present
 * in the file and absent on the receiving device. It is worst on the ordinary
 * re-share — share today, keep using the habit, share the same code again — where
 * the second share silently contributes nothing.
 */
class ShareImportHistoryTest {

    private fun habit(
        id: String,
        name: String = id,
        iconId: String? = "walk",
        alarmTimes: List<Int> = emptyList(),
    ) = Habit(id = id, name = name, iconId = iconId, alarmTimes = alarmTimes)

    private fun payload(vararg entries: HabitExportEntry) =
        HabitShareCodec.SharePayload(habits = entries.toList())

    private fun entry(
        h: Habit,
        checkIns: List<String> = emptyList(),
        logs: List<HabitLogEntry> = emptyList(),
        alarms: List<HabitAlarmEvent> = emptyList(),
        activity: List<ActivityRecord> = emptyList(),
        reflections: Map<String, String> = emptyMap(),
    ) = HabitExportEntry(
        habit = h,
        checkIns = checkIns,
        logs = logs,
        alarmEvents = alarms,
        activity = activity,
        reflections = reflections,
    )

    // ── The regression ────────────────────────────────────────────────────────

    @Test
    fun `re-importing a code whose history has grown merges the new days`() {
        // THE case: the habit already exists identically, but the sender has
        // checked in on days this device has never seen.
        val existing = AppData(
            habits = listOf(habit("h1")),
            checkIns = mapOf("h1" to listOf("2026-09-14")),
        )
        val incoming = payload(entry(habit("h1"), checkIns = listOf("2026-09-14", "2026-09-15", "2026-09-16")))

        val plan = HabitShareCodec.planImport(existing, incoming)
        val merged = HabitShareCodec.applyImport(existing, plan)

        assertEquals(
            "the sender's newer days must land",
            listOf("2026-09-14", "2026-09-15", "2026-09-16"),
            merged.checkIns["h1"],
        )
    }

    @Test
    fun `a duplicate habit appears in the plan as an entry, not just a name`() {
        // The structural half of the fix: the name alone was all that survived,
        // so there was nothing left to merge.
        val existing = AppData(habits = listOf(habit("h1")))
        val plan = HabitShareCodec.planImport(existing, payload(entry(habit("h1"), checkIns = listOf("2026-09-14"))))

        assertEquals(listOf("h1"), plan.duplicateHabits)
        assertTrue("the plan must keep so the history has somewhere to land", plan.duplicateEntryHabits.isNotEmpty())
        assertEquals("h1", plan.duplicateEntryHabits.single().habit.id)
    }

    @Test
    fun `duplicate history merges logs, alarms, activity and reflections`() {
        val existing = AppData(
            habits = listOf(habit("h1")),
            habitLogs = listOf(HabitLogEntry(id = "l1", habitId = "h1", dayKey = "2026-09-14", mode = HabitTrackingMode.CHECK)),
        )
        val incoming = payload(
            entry(
                h = habit("h1"),
                logs = listOf(
                    HabitLogEntry(id = "l1", habitId = "h1", dayKey = "2026-09-14", mode = HabitTrackingMode.CHECK),
                    HabitLogEntry(id = "l2", habitId = "h1", dayKey = "2026-09-15", mode = HabitTrackingMode.STOPWATCH),
                ),
                alarms = listOf(HabitAlarmEvent(habitId = "h1", dayKey = "2026-09-15", scheduledMinutes = 7 * 60)),
                activity = listOf(
                    ActivityRecord(
                        id = "a1",
                        habitId = "h1",
                        source = "strava",
                        activityType = "walking",
                        timestamp = 1_700_000_000_000L,
                    ),
                ),
                reflections = mapOf("2026-09-15" to "good walk"),
            ),
        )

        val merged = HabitShareCodec.applyImport(existing, HabitShareCodec.planImport(existing, incoming))
        val id = "h1"

        assertEquals("existing log kept, incoming one added, no duplicate", 2, merged.habitLogs.count { it.habitId == id })
        assertEquals(1, merged.alarmEvents.count { it.habitId == id })
        assertEquals(1, merged.activityRecords.count { it.habitId == id })
        assertEquals("good walk", merged.reflections["2026-09-15"])
    }

    @Test
    fun `re-importing identical history is idempotent`() {
        // A share code is meant to be safe to apply twice. Nothing may duplicate.
        val h = habit("h1")
        val e = entry(
            h = h,
            checkIns = listOf("2026-09-14", "2026-09-15"),
            logs = listOf(HabitLogEntry(id = "l1", habitId = "h1", dayKey = "2026-09-14", mode = HabitTrackingMode.CHECK)),
            alarms = listOf(HabitAlarmEvent(habitId = "h1", dayKey = "2026-09-14", scheduledMinutes = 7 * 60)),
        )
        val existing = AppData(habits = listOf(h))

        val once = HabitShareCodec.applyImport(existing, HabitShareCodec.planImport(existing, payload(e)))
        val twice = HabitShareCodec.applyImport(once, HabitShareCodec.planImport(once, payload(e)))

        assertEquals(1, twice.habits.size)
        assertEquals(listOf("2026-09-14", "2026-09-15"), twice.checkIns["h1"])
        assertEquals(1, twice.habitLogs.count { it.habitId == "h1" })
        assertEquals(1, twice.alarmEvents.count { it.habitId == "h1" })
    }

    @Test
    fun `an import never reverts the existing habit's own settings`() {
        // "Identical" for `planImport` means id + name + alarmTimes + repeat mask
        // all match, so the fixture has to match on all four — otherwise the
        // incoming habit is a genuine *collision*, is renamed, and legitimately
        // lands as a second habit (asserted separately below).
        val existing = AppData(habits = listOf(habit("h1", name = "My walk", alarmTimes = listOf(7 * 60, 18 * 60))))
        val incoming = payload(
            entry(habit("h1", name = "My walk", alarmTimes = listOf(7 * 60, 18 * 60)), checkIns = listOf("2026-09-14")),
        )

        val merged = HabitShareCodec.applyImport(existing, HabitShareCodec.planImport(existing, incoming))

        assertEquals("the habit must not be duplicated", 1, merged.habits.size)
        assertEquals("the local schedule must survive", listOf(7 * 60, 18 * 60), merged.habits.single().alarmTimes)
        assertEquals("only the history is added", listOf("2026-09-14"), merged.checkIns["h1"])
    }

    @Test
    fun `a habit that already exists by id but differs is renamed, not merged`() {
        // A genuine id collision with a DIFFERENT habit must still be renamed so
        // both survive — that behaviour must not be lost to the D4 fix.
        val existing = AppData(habits = listOf(habit("h1", name = "Mine")))
        val incoming = payload(entry(habit("h1", name = "Theirs")))

        val plan = HabitShareCodec.planImport(existing, incoming)
        val merged = HabitShareCodec.applyImport(existing, plan)

        assertEquals(2, merged.habits.size)
        assertTrue(plan.renamedHabits.isNotEmpty())
        assertTrue(merged.habits.map { it.name }.containsAll(listOf("Mine", "Theirs")))
    }

    @Test
    fun `alarm history for a duplicate keeps every occurrence of a day`() {
        // One habit ringing several times a day must not be collapsed by the
        // import path either.
        val existing = AppData(habits = listOf(habit("h1")))
        val events = listOf(7 * 60, 12 * 60, 18 * 60).map {
            HabitAlarmEvent(habitId = "h1", dayKey = "2026-09-15", scheduledMinutes = it)
        }
        val merged = HabitShareCodec.applyImport(
            existing,
            HabitShareCodec.planImport(existing, payload(entry(habit("h1"), alarms = events))),
        )

        assertEquals(3, merged.alarmEvents.count { it.habitId == "h1" })
    }

    @Test
    fun `an import of a brand-new habit still works`() {
        // The path that already worked must keep working.
        val existing = AppData(habits = listOf(habit("mine")))
        val incoming = payload(entry(habit("theirs"), checkIns = listOf("2026-09-14")))

        val merged = HabitShareCodec.applyImport(existing, HabitShareCodec.planImport(existing, incoming))

        assertEquals(2, merged.habits.size)
        assertEquals(listOf("2026-09-14"), merged.checkIns["theirs"])
    }

    @Test
    fun `an empty payload changes nothing`() {
        val existing = AppData(habits = listOf(habit("h1")), checkIns = mapOf("h1" to listOf("2026-09-14")))
        val merged = HabitShareCodec.applyImport(existing, HabitShareCodec.planImport(existing, payload()))
        assertEquals(existing.habits, merged.habits)
        assertEquals(existing.checkIns, merged.checkIns)
    }
}
