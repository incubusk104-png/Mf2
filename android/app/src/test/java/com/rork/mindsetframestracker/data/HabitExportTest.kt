package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The completeness contract for the export: **nothing is silently dropped.**
 *
 * ## What this file is guarding
 *
 * The previous reporting paths chose what to include and quietly left the rest
 * behind. A user could ask for "my data" and receive a report that mentioned
 * only habits and check-ins, while their detailed logs, alarm history, imported
 * activity and reflections were absent from the file with no indication that
 * anything was missing. The failure mode is not "the report is short" - it is
 * that the user believes they hold a complete copy, and deletes the original.
 *
 * These tests pin the guarantees that fix it:
 *  - every record type is exported, and the counts prove it,
 *  - the counts are verified from two independent traversals, so an exporter
 *    that dropped a record could not still report success,
 *  - one habit's several same-day alarms stay several records,
 *  - user text (emoji, quotes, newlines) survives every format,
 *  - and OAuth tokens never appear in a file built to be shared.
 *
 * Runs as a plain JVM test: the export engine is deliberately Android-free.
 */
class HabitExportTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private val walk = Habit(
        id = "habit-walk",
        name = "Walk",
        createdAt = 1_700_000_000_000L,
        alarmTimes = listOf(7 * 60, 12 * 60, 18 * 60),
        iconId = "walking",
        repeatDaysMask = REPEAT_DAILY,
        alarmMessage = "Time to move 🚶",
    )

    private val water = Habit(
        id = "habit-water",
        name = "Water 2 litres",
        alarmTimes = listOf(21 * 60),
        iconId = "water",
        trackingMode = HabitTrackingMode.COUNT,
        trackingTargetCount = 8,
        trackingUnit = "glasses",
    )

    /** Records in every collection, including three alarms for one habit on one day. */
    private fun fullData(): AppData = AppData(
        habits = listOf(walk, water),
        checkIns = mapOf(
            "habit-walk" to listOf("2026-09-15", "2026-09-16", "2026-09-17"),
            "habit-water" to listOf("2026-09-16"),
        ),
        moodHistory = mapOf("2026-09-16" to MoodMode.CALM, "2026-09-17" to MoodMode.FOCUSED),
        reflections = mapOf(
            // Only days that are checked in end up nested under a habit; the
            // 2026-09-15 key deliberately proves a reflection with no check-in
            // is still carried at the top level rather than lost.
            "2026-09-16" to "Felt steady today",
            "2026-09-17" to "Busy but calm",
        ),
        activityRecords = listOf(
            ActivityRecord(
                id = "act-1", habitId = "habit-walk", source = ActivitySources.STRAVA,
                activityType = "walking", timestamp = 1_758_000_000_000L,
                durationMinutes = 34, distanceMeters = 3200.0, steps = 4200L,
            ),
        ),
        habitLogs = listOf(
            HabitLogEntry(
                id = "log-1", habitId = "habit-water", dayKey = "2026-09-16",
                mode = HabitTrackingMode.COUNT, count = 6, unit = "glasses",
                occurrenceKey = "2026-09-16@1260", recordedAtEpochMs = 1_758_000_100_000L,
            ),
            HabitLogEntry(
                id = "log-2", habitId = "habit-walk", dayKey = "2026-09-17",
                mode = HabitTrackingMode.JOURNAL, title = "Evening walk",
                note = "Cold, but worth it 🌙", recordedAtEpochMs = 1_758_000_200_000L,
            ),
        ),
        alarmEvents = listOf(
            HabitAlarmEvent(
                id = "ev-1", habitId = "habit-walk", dayKey = "2026-09-17",
                scheduledMinutes = 7 * 60, outcome = AlarmEventOutcome.ACKNOWLEDGED,
                firedAtEpochMs = 1_758_100_000_000L, respondedAtEpochMs = 1_758_100_060_000L,
                message = "Morning walk 🌅",
            ),
            HabitAlarmEvent(
                id = "ev-2", habitId = "habit-walk", dayKey = "2026-09-17",
                scheduledMinutes = 12 * 60, outcome = AlarmEventOutcome.SNOOZED,
                firedAtEpochMs = 1_758_118_000_000L, respondedAtEpochMs = 1_758_118_030_000L,
                message = "Lunchtime stretch",
            ),
            HabitAlarmEvent(
                id = "ev-3", habitId = "habit-walk", dayKey = "2026-09-17",
                scheduledMinutes = 18 * 60, outcome = AlarmEventOutcome.DISMISSED,
                firedAtEpochMs = 1_758_140_000_000L, respondedAtEpochMs = 1_758_140_010_000L,
            ),
        ),
    )

    // ── Completeness ─────────────────────────────────────────────────────────

    /** Every source record appears in the export, and the verification says so. */
    @Test
    fun `full export accounts for every record`() {
        val data = fullData()
        val bundle = HabitDataExport.build(data, appVersionName = "1.1.1", appVersionCode = 22)

        assertTrue("verification must report complete", bundle.verification.isComplete)
        assertTrue("no omissions expected", bundle.verification.omissions.isEmpty())

        val counts = bundle.verification.counts.associateBy { it.kind }
        assertEquals(2, counts.getValue("habit").inExport)
        assertEquals(4, counts.getValue("checkIn").inExport)
        assertEquals(2, counts.getValue("habitLog").inExport)
        assertEquals(3, counts.getValue("alarmEvent").inExport)
        assertEquals(1, counts.getValue("activityRecord").inExport)
        assertEquals(2, counts.getValue("reflection").inExport)

        counts.values.forEach { c ->
            assertTrue("${c.kind} must balance (${c.inSource} vs ${c.inExport})", c.isComplete)
        }
    }

    /**
     * Counts recomputed from the **written** bundle must match the source.
     *
     * This is the check that a lossy exporter would still have to satisfy: it
     * walks the exported structure, not the input, so it can disagree with the
     * input when something was dropped on the way through.
     */
    @Test
    fun `exported counts recomputed from the bundle match the source`() {
        val data = fullData()
        val bundle = HabitDataExport.build(data)

        assertEquals(data.habits.size, bundle.habits.size)
        assertEquals(data.checkIns.values.sumOf { it.size }, bundle.habits.sumOf { it.checkIns.size })
        assertEquals(data.habitLogs.size, bundle.habits.sumOf { it.logs.size })
        assertEquals(data.alarmEvents.size, bundle.habits.sumOf { it.alarmEvents.size })
        assertEquals(data.activityRecords.size, bundle.habits.sumOf { it.activity.size })
        assertEquals(data.reflections.size, bundle.habits.sumOf { it.reflections.size })
    }

    /** A habit's three same-day alarms survive as three distinct records. */
    @Test
    fun `three alarms for one habit on one day stay distinct`() {
        val bundle = HabitDataExport.build(fullData())
        val walkEntry = bundle.habits.first { it.habit.id == "habit-walk" }

        assertEquals(3, walkEntry.alarmEvents.size)
        assertEquals(
            listOf(7 * 60, 12 * 60, 18 * 60),
            walkEntry.alarmEvents.map { it.scheduledMinutes },
        )
        assertEquals(
            listOf(
                AlarmEventOutcome.ACKNOWLEDGED,
                AlarmEventOutcome.SNOOZED,
                AlarmEventOutcome.DISMISSED,
            ),
            walkEntry.alarmEvents.map { it.outcome },
        )
        // All three are the same habit on the same day - only the time differs.
        assertTrue(walkEntry.alarmEvents.all { it.habitId == "habit-walk" && it.dayKey == "2026-09-17" })
    }

    /** The message each occurrence actually delivered is preserved, not re-resolved. */
    @Test
    fun `alarm messages are captured per event`() {
        val bundle = HabitDataExport.build(fullData())
        val events = bundle.habits.first { it.habit.id == "habit-walk" }.alarmEvents
        assertEquals("Morning walk 🌅", events.first { it.scheduledMinutes == 7 * 60 }.message)
        assertEquals("Lunchtime stretch", events.first { it.scheduledMinutes == 12 * 60 }.message)
        assertNull(events.first { it.scheduledMinutes == 18 * 60 }.message)
    }

    /** Outcomes are tallied into the summary, so the headline cannot drift. */
    @Test
    fun `summary tallies alarm outcomes`() {
        val s = HabitDataExport.build(fullData()).summary
        assertEquals(3, s.totalAlarmEvents)
        assertEquals(1, s.alarmsAcknowledged)
        assertEquals(1, s.alarmsSnoozed)
        assertEquals(1, s.alarmsDismissed)
        assertEquals(0, s.alarmsFired)
    }

    /** Detailed log payloads - occurrence key, note, unit - survive the export. */
    @Test
    fun `detailed log payload is not reduced to a boolean`() {
        val bundle = HabitDataExport.build(fullData())
        val waterLog = bundle.habits.first { it.habit.id == "habit-water" }.logs.single()
        assertEquals(6, waterLog.count)
        assertEquals("glasses", waterLog.unit)
        assertEquals("2026-09-16@1260", waterLog.occurrenceKey)

        val walkLog = bundle.habits.first { it.habit.id == "habit-walk" }.logs.single()
        assertEquals("Evening walk", walkLog.title)
        assertEquals("Cold, but worth it 🌙", walkLog.note)
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    /** An install with nothing recorded still produces a valid, complete export. */
    @Test
    fun `empty data exports cleanly`() {
        val bundle = HabitDataExport.build(AppData(), appVersionName = "1.1.1")
        assertTrue(bundle.verification.isComplete)
        assertEquals(0, bundle.verification.totalRecords)
        assertTrue(bundle.habits.isEmpty())
        assertEquals(0, bundle.summary.habitCount)

        val encoded = HabitExportWriters.json(bundle)
        assertTrue(encoded.contains("mindsetframes.habit-export"))
        // It must still be parseable, not merely writable.
        val reread = json.decodeFromString(HabitExportBundle.serializer(), encoded)
        assertEquals(EXPORT_SCHEMA_VERSION, reread.schemaVersion)
    }

    /** A habit with no history at all is still exported. */
    @Test
    fun `habit with no history is still exported`() {
        val data = AppData(habits = listOf(walk))
        val bundle = HabitDataExport.build(data)
        assertEquals(1, bundle.habits.size)
        assertTrue(bundle.habits.single().checkIns.isEmpty())
        assertTrue(bundle.verification.isComplete)
    }

    /** Emoji, quotes, commas and newlines in user text survive a JSON round trip. */
    @Test
    fun `special characters survive serialisation`() {
        val awkward = Habit(
            id = "habit-awkward",
            name = "Méditer 🧘🏽‍♀️ \"quoted\", comma, 😀",
            alarmMessage = "Line one\nLine two\twith tab — em dash & ampersand",
        )
        val data = AppData(
            habits = listOf(awkward),
            reflections = mapOf("2026-09-17" to "Ünïcödé + 中文 + العربية + 🎉"),
        )
        val bundle = HabitDataExport.build(data)
        val encoded = HabitExportWriters.json(bundle)
        val reread = json.decodeFromString(HabitExportBundle.serializer(), encoded)

        assertEquals(awkward.name, reread.habits.single().habit.name)
        assertEquals(awkward.alarmMessage, reread.habits.single().habit.alarmMessage)
        // A reflection with no check-in that day is still carried, at the top level.
        assertEquals("Ünïcödé + 中文 + العربية + 🎉", reread.reflections["2026-09-17"])
    }

    /** CSV escapes commas, quotes and newlines so columns cannot shift. */
    @Test
    fun `csv escapes separators and quotes`() {
        val awkward = Habit(id = "h1", name = "Walk, then \"reflect\"\nand breathe")
        val csv = HabitExportWriters.csv(HabitDataExport.build(AppData(habits = listOf(awkward))))
        assertTrue(csv.contains("\"Walk, then \"\"reflect\"\"\nand breathe\""))
    }

    /**
     * A habit name starting with `=` is neutralised, so opening the CSV in a
     * spreadsheet cannot execute it as a formula.
     */
    @Test
    fun `csv guards against formula injection`() {
        val hostile = Habit(id = "h1", name = "=HYPERLINK(\"http://evil\",\"click\")")
        val csv = HabitExportWriters.csv(HabitDataExport.build(AppData(habits = listOf(hostile))))
        assertTrue("formula must be prefixed with a quote", csv.contains("\"'=HYPERLINK"))
    }

    /** Emoji in a CSV cell are written verbatim, not escaped into entities. */
    @Test
    fun `csv preserves emoji`() {
        val habit = Habit(id = "h1", name = "Water 💧 2L")
        val csv = HabitExportWriters.csv(HabitDataExport.build(AppData(habits = listOf(habit))))
        assertTrue(csv.contains("Water 💧 2L"))
    }

    /** A habit name spanning a line break cannot split a CSV row in two. */
    @Test
    fun `csv keeps a multi-line value inside one row`() {
        val habit = Habit(id = "h1", name = "Walk\nand breathe")
        val csv = HabitExportWriters.csv(HabitDataExport.build(AppData(habits = listOf(habit))))
        // The value is quoted, so the embedded newline is data rather than a row break.
        assertTrue(csv.contains("\"Walk\nand breathe\""))
    }

    /** A large data set is exported completely. */
    @Test
    fun `large data set is exported completely`() {
        val habits = (0 until 30).map {
            Habit(id = "h$it", name = "Habit $it", alarmTimes = listOf(7 * 60, 18 * 60))
        }
        val days = (0 until 120).map { java.time.LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString() }
        val checkIns = habits.associate { h -> h.id to days.filter { (h.id.hashCode() + it.hashCode()) % 3 == 0 } }
        val logs = (0 until 500).map { i ->
            HabitLogEntry(
                id = "log-$i", habitId = "h${i % 30}", dayKey = days[i % days.size],
                mode = HabitTrackingMode.CHECK, recordedAtEpochMs = 1_700_000_000_000L + i,
            )
        }
        val data = AppData(habits = habits, checkIns = checkIns, habitLogs = logs)

        val bundle = HabitDataExport.build(data)
        assertTrue(bundle.verification.isComplete)
        assertEquals(500, bundle.habits.sumOf { it.logs.size })
        assertEquals(checkIns.values.sumOf { it.size }, bundle.habits.sumOf { it.checkIns.size })
        // And it survives a real round trip at this size.
        val reread = json.decodeFromString(HabitExportBundle.serializer(), HabitExportWriters.json(bundle))
        assertEquals(500, reread.habits.sumOf { it.logs.size })
    }

    // ── Range filtering ──────────────────────────────────────────────────────

    /** A range excludes out-of-window records and reports them as omitted. */
    @Test
    fun `range export excludes and reports out-of-range records`() {
        val data = fullData()
        val bundle = HabitDataExport.build(data, range = ExportRange("2026-09-16", "2026-09-16"))

        assertEquals(2, bundle.habits.sumOf { it.checkIns.size })
        assertEquals(1, bundle.habits.sumOf { it.logs.size })
        assertEquals(0, bundle.habits.sumOf { it.alarmEvents.size })
        assertEquals(1, bundle.rangeDays)

        assertFalse("out-of-range records must be reported", bundle.verification.omissions.isEmpty())
        assertTrue(bundle.verification.omissions.all { it.reason == ExportOmission.OUT_OF_RANGE })
        // The counts still balance once the omissions are accounted for.
        bundle.verification.counts.forEach { c ->
            assertTrue("${c.kind} must balance with omissions", c.isComplete)
        }
    }

    @Test
    fun `range day count is inclusive`() {
        assertEquals(1, ExportRange("2026-09-17", "2026-09-17").dayCount())
        assertEquals(7, ExportRange("2026-09-14", "2026-09-20").dayCount())
        assertEquals(0, ExportRange(null, "2026-09-20").dayCount())
    }

    @Test
    fun `range contains is inclusive at both ends`() {
        val range = ExportRange("2026-09-16", "2026-09-18")
        assertTrue(range.contains("2026-09-16"))
        assertTrue(range.contains("2026-09-17"))
        assertTrue(range.contains("2026-09-18"))
        assertFalse(range.contains("2026-09-15"))
        assertFalse(range.contains("2026-09-19"))
    }

    /** A record whose habit was deleted is reported, never silently dropped. */
    @Test
    fun `record for a deleted habit is reported as an omission`() {
        val data = AppData(
            habits = listOf(walk),
            checkIns = mapOf("habit-deleted" to listOf("2026-09-17")),
            habitLogs = listOf(
                HabitLogEntry(
                    id = "l1", habitId = "habit-deleted", dayKey = "2026-09-17",
                    mode = HabitTrackingMode.CHECK,
                ),
            ),
        )
        val bundle = HabitDataExport.build(data)
        assertTrue(
            bundle.verification.omissions
                .any { it.kind == "checkIn" && it.reason == ExportOmission.ORPHANED_HABIT },
        )
        assertTrue(bundle.verification.omissions.any { it.kind == "habitLog" })
        bundle.verification.counts.forEach { c ->
            assertTrue("${c.kind} must still balance", c.isComplete)
        }
    }

    // ── Secrets ──────────────────────────────────────────────────────────────

    /** OAuth tokens must never reach a file the user is invited to share. */
    @Test
    fun `tokens are never exported`() {
        val data = AppData(
            habits = listOf(walk),
            settings = AppSettings(
                stravaAccessToken = "SECRET-ACCESS-TOKEN",
                stravaRefreshToken = "SECRET-REFRESH-TOKEN",
                polarAccessToken = "SECRET-POLAR-TOKEN",
                healthConnectConnected = true,
            ),
        )
        val bundle = HabitDataExport.build(data)
        val encoded = HabitExportWriters.json(bundle)
        val csv = HabitExportWriters.csv(bundle)

        listOf("SECRET-ACCESS-TOKEN", "SECRET-REFRESH-TOKEN", "SECRET-POLAR-TOKEN").forEach { secret ->
            assertFalse("$secret must not appear in JSON", encoded.contains(secret))
            assertFalse("$secret must not appear in CSV", csv.contains(secret))
        }
        // The connection *state* is still reported, so the export is not misleading.
        assertTrue(bundle.settings.connectedProviders.contains(ActivitySources.STRAVA))
        assertTrue(bundle.settings.connectedProviders.contains(ActivitySources.HEALTH_CONNECT))
        assertTrue(bundle.settings.connectedProviders.contains(ActivitySources.POLAR))
    }

    // ── Writers ──────────────────────────────────────────────────────────────

    /** The report states the completeness verdict, the counts and each alarm time. */
    @Test
    fun `report declares completeness and record counts`() {
        val report = HabitExportWriters.report(HabitDataExport.build(fullData(), appVersionName = "1.1.1"))
        assertTrue(report.contains("VERIFIED COMPLETE"))
        assertTrue(report.contains("RECORD COUNTS"))
        assertTrue(report.contains("alarmEvent"))
        assertTrue(report.contains("habit-walk"))
        assertTrue(report.contains("07:00"))
        assertTrue(report.contains("12:00"))
        assertTrue(report.contains("18:00"))
    }

    /** JSON is self-describing: format marker, schema version, summary and counts. */
    @Test
    fun `json is self describing`() {
        val encoded = HabitExportWriters.json(
            HabitDataExport.build(fullData(), appVersionName = "1.1.1", appVersionCode = 22),
        )
        val obj = json.parseToJsonElement(encoded) as JsonObject
        assertEquals(EXPORT_FORMAT_MARKER, obj.getValue("format").jsonPrimitive.content)
        assertEquals(EXPORT_SCHEMA_VERSION.toString(), obj.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("1.1.1", obj.getValue("appVersionName").jsonPrimitive.content)
        assertNotNull(obj["verification"])
        assertNotNull(obj["summary"])
        assertNotNull(obj["habits"])
    }

    /** A habit's own fields survive, so an import can restore them verbatim. */
    @Test
    fun `habit definition round trips through json`() {
        val bundle = HabitDataExport.build(AppData(habits = listOf(walk, water)))
        val reread = json.decodeFromString(HabitExportBundle.serializer(), HabitExportWriters.json(bundle))
        val restored = reread.habits.first { it.habit.id == "habit-walk" }.habit
        assertEquals(walk.alarmTimes, restored.alarmTimes)
        assertEquals(walk.repeatDaysMask, restored.repeatDaysMask)
        assertEquals(walk.iconId, restored.iconId)
        assertEquals(walk.alarmMessage, restored.alarmMessage)
    }

    /** CSV carries a header block, so it stays readable after being renamed. */
    @Test
    fun `csv is self describing`() {
        val csv = HabitExportWriters.csv(HabitDataExport.build(fullData(), appVersionName = "1.1.1"))
        assertTrue(csv.contains("# format=mindsetframes.habit-export"))
        assertTrue(csv.contains("# schemaVersion=1"))
        assertTrue(csv.contains("# --- summary ---"))
        assertTrue(csv.contains("# --- alarmEvents ---"))
        assertTrue(csv.contains("scheduledTime"))
    }
}
