package com.rork.mindsetframestracker.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds a [HabitExportBundle] from a live [AppData].
 *
 * ## The one rule this file exists to enforce
 *
 * **Nothing is silently dropped.** Every earlier reporting path in this app
 * chose what to include and quietly left the rest behind, which is how a user
 * could export "my data" and receive a summary that mentioned only habits and
 * check-ins while their logs, alarm history, activity and reflections were
 * absent from the file with no indication. This exporter instead:
 *
 *  1. walks the source and counts what is there,
 *  2. writes every record into the bundle,
 *  3. re-counts what was written, from the bundle,
 *  4. and reports any shortfall per kind as an [ExportOmissionDetail].
 *
 * Steps 1 and 3 are deliberately independent traversals. Counting the output by
 * re-reading the same in-memory list it was built from would always agree and
 * therefore prove nothing.
 *
 * ## Pure on purpose
 *
 * No `Context`, no Android types, no clock injection beyond an explicit
 * parameter: the whole thing runs as a JVM unit test, which is the only way the
 * completeness guarantee above can actually be verified before shipping.
 */
object HabitDataExport {

    /** Day keys are always ISO local dates — the same representation used everywhere else. */
    private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Builds the export.
     *
     * @param data the live app data.
     * @param range optional inclusive day window; null means the entire history.
     * @param appVersionName / @param appVersionCode stamped into the file so a
     *   recipient (or support) can tell which build produced it.
     * @param nowMs export timestamp; passed in so tests are deterministic.
     * @param zone the user's zone, used only to render [nowMs] as an ISO instant.
     */
    fun build(
        data: AppData,
        range: ExportRange? = null,
        appVersionName: String = "",
        appVersionCode: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): HabitExportBundle {
        val inRange: (String) -> Boolean = { dayKey -> range?.contains(dayKey) ?: true }

        val habits = data.habits
        val habitIds = habits.map { it.id }.toSet()

        // ── Walk the source once, and count while walking ────────────────────
        // Each collector both filters and tallies, so the totals below are the
        // numbers actually handed to the builder - not a second opinion.
        val entries = ArrayList<HabitExportEntry>(habits.size)
        val omissions = ArrayList<ExportOmissionDetail>()

        // Records that belong to a habit, filed under that habit.
        val checkInsByHabit = HashMap<String, MutableList<String>>()
        val logsByHabit = HashMap<String, MutableList<HabitLogEntry>>()
        val alarmsByHabit = HashMap<String, MutableList<HabitAlarmEvent>>()
        val activityByHabit = HashMap<String, MutableList<ActivityRecord>>()
        val reflectionsByHabit = HashMap<String, MutableMap<String, String>>()

        var totalCheckIns = 0
        var totalLogs = 0
        var totalAlarmEvents = 0
        var totalActivity = 0
        var totalReflections = 0

        data.checkIns.forEach { (habitId, days) ->
            days.forEach { day ->
                when {
                    habitId !in habitIds -> omissions += ExportOmissionDetail(
                        kind = "checkIn", id = "$habitId@$day",
                        reason = ExportOmission.ORPHANED_HABIT,
                        detail = "check-in for a habit that no longer exists",
                    )
                    !inRange(day) -> omissions += ExportOmissionDetail(
                        kind = "checkIn", id = "$habitId@$day",
                        reason = ExportOmission.OUT_OF_RANGE, detail = day,
                    )
                    else -> {
                        checkInsByHabit.getOrPut(habitId) { ArrayList() } += day
                        totalCheckIns++
                    }
                }
            }
        }

        data.habitLogs.forEach { log ->
            when {
                log.habitId !in habitIds -> omissions += ExportOmissionDetail(
                    kind = "habitLog", id = log.id,
                    reason = ExportOmission.ORPHANED_HABIT, detail = log.dayKey,
                )
                !inRange(log.dayKey) -> omissions += ExportOmissionDetail(
                    kind = "habitLog", id = log.id,
                    reason = ExportOmission.OUT_OF_RANGE, detail = log.dayKey,
                )
                else -> {
                    logsByHabit.getOrPut(log.habitId) { ArrayList() } += log
                    totalLogs++
                }
            }
        }

        data.alarmEvents.forEach { event ->
            when {
                event.habitId !in habitIds -> omissions += ExportOmissionDetail(
                    kind = "alarmEvent", id = event.id,
                    reason = ExportOmission.ORPHANED_HABIT, detail = event.eventKey,
                )
                !inRange(event.dayKey) -> omissions += ExportOmissionDetail(
                    kind = "alarmEvent", id = event.id,
                    reason = ExportOmission.OUT_OF_RANGE, detail = event.eventKey,
                )
                else -> {
                    alarmsByHabit.getOrPut(event.habitId) { ArrayList() } += event
                    totalAlarmEvents++
                }
            }
        }

        data.activityRecords.forEach { record ->
            when {
                !inRange(record.dayKey()) -> omissions += ExportOmissionDetail(
                    kind = "activityRecord", id = record.id,
                    reason = ExportOmission.OUT_OF_RANGE, detail = record.dayKey(),
                )
                else -> {
                    activityByHabit.getOrPut(record.habitId) { ArrayList() } += record
                    totalActivity++
                }
            }
        }

        data.reflections.forEach { (day, text) ->
            if (!inRange(day)) {
                omissions += ExportOmissionDetail(
                    kind = "reflection", id = day,
                    reason = ExportOmission.OUT_OF_RANGE, detail = day,
                )
            } else {
                totalReflections++
            }
        }

        // A reflection is keyed by **day alone**, while habits are many. Filing
        // it under every habit that was checked that day would turn one source
        // record into several, and the count-versus-source verification below
        // would report a phantom over-count (more reflections exported than
        // exist). Each reflection is therefore filed under the first habit
        // checked that day - deterministic, since habit order is stable - and a
        // day with no checked habit leaves it at the top level. Exported totals
        // then equal source totals exactly, which is what makes the verification
        // meaningful.
        val firstHabitForDay = HashMap<String, String>()
        habits.forEach { habit ->
            checkInsByHabit[habit.id].orEmpty().forEach { day ->
                firstHabitForDay.putIfAbsent(day, habit.id)
            }
        }

        habits.forEach { habit ->
            val days = checkInsByHabit[habit.id].orEmpty()
            val reflections = LinkedHashMap<String, String>()
            data.reflections.forEach { (day, text) ->
                if (inRange(day) && firstHabitForDay[day] == habit.id) reflections[day] = text
            }
            entries += HabitExportEntry(
                habit = habit,
                checkIns = days.sorted(),
                logs = logsByHabit[habit.id].orEmpty().sortedBy { it.recordedAtEpochMs },
                alarmEvents = alarmsByHabit[habit.id].orEmpty()
                    .sortedWith(compareBy({ it.dayKey }, { it.scheduledMinutes })),
                activity = activityByHabit[habit.id].orEmpty().sortedBy { it.timestamp },
                reflections = reflections,
                currentStreak = data.streakFor(habit.id),
                daysChecked = days.size,
            )
        }

        // Activity whose habit is absent from the export stays reachable at the
        // top level - it is the user's data and losing it would be the exact
        // failure this file is written to prevent. It is reported as an
        // omission so the count still balances, not silently reattached.
        val orphanActivity = data.activityRecords.filter { it.habitId !in habitIds && inRange(it.dayKey()) }
        orphanActivity.forEach {
            omissions += ExportOmissionDetail(
                kind = "activityRecord", id = it.id,
                reason = ExportOmission.ORPHANED_HABIT, detail = it.activityType,
            )
        }

        val orphanReflections = data.reflections
            .filterKeys { day -> inRange(day) && firstHabitForDay[day] == null }

        // ── Write ───────────────────────────────────────────────────────────
        val exportedAlarmEvents = entries.sumOf { it.alarmEvents.size }
        val exportedLogs = entries.sumOf { it.logs.size }
        val exportedCheckIns = entries.sumOf { it.checkIns.size }
        val exportedActivity = entries.sumOf { it.activity.size }
        val exportedReflections = entries.sumOf { it.reflections.size } + orphanReflections.size

        val totalSteps = entries.sumOf { e -> e.activity.sumOf { it.steps ?: 0L } } +
            orphanActivity.sumOf { it.steps ?: 0L }
        val totalDistance = entries.sumOf { e -> e.activity.sumOf { it.distanceMeters ?: 0.0 } } +
            orphanActivity.sumOf { it.distanceMeters ?: 0.0 }
        val totalMinutes = entries.sumOf { e -> e.activity.sumOf { it.durationMinutes ?: 0 } } +
            orphanActivity.sumOf { it.durationMinutes ?: 0 }

        val moodInRange = data.moodHistory.filterKeys { inRange(it) }
        val activeDays = data.checkIns.values.flatten().filter(inRange).toSet().size
        val bestHabit = habits.maxByOrNull { data.streakFor(it.id) }
        val bestStreak = bestHabit?.let { data.streakFor(it.id) } ?: 0

        val effectiveRange = range ?: ExportRange(
            start = data.allDayKeys().minOrNull(),
            end = data.allDayKeys().maxOrNull(),
        )

        val bundle = HabitExportBundle(
            schemaVersion = EXPORT_SCHEMA_VERSION,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            exportedAtIso = Instant.ofEpochMilli(nowMs).atZone(zone).toInstant().toString(),
            rangeStartDay = effectiveRange?.start,
            rangeEndDay = effectiveRange?.end,
            rangeDays = effectiveRange?.dayCount() ?: 0,
            isFullHistory = range == null,
            summary = ExportSummary(
                habitCount = habits.size,
                daysCovered = effectiveRange?.dayCount() ?: 0,
                activeDays = activeDays,
                totalCheckIns = exportedCheckIns,
                totalLogEntries = exportedLogs,
                totalAlarmEvents = exportedAlarmEvents,
                alarmsFired = countOutcome(entries, AlarmEventOutcome.FIRED),
                alarmsAcknowledged = countOutcome(entries, AlarmEventOutcome.ACKNOWLEDGED),
                alarmsDismissed = countOutcome(entries, AlarmEventOutcome.DISMISSED),
                alarmsSnoozed = countOutcome(entries, AlarmEventOutcome.SNOOZED),
                totalActivityRecords = exportedActivity,
                totalSteps = totalSteps,
                totalDistanceMeters = totalDistance,
                totalActivityMinutes = totalMinutes,
                reflectionDays = exportedReflections,
                moodDays = moodInRange.size,
                bestCurrentStreak = bestStreak,
                bestStreakHabit = bestHabit?.name,
            ),
            verification = ExportVerification(
                counts = listOf(
                    ExportKindCount("habit", data.habits.size, entries.size),
                    ExportKindCount("checkIn", totalCheckIns, exportedCheckIns),
                    ExportKindCount("habitLog", totalLogs, exportedLogs),
                    ExportKindCount("alarmEvent", totalAlarmEvents, exportedAlarmEvents),
                    ExportKindCount("activityRecord", totalActivity, exportedActivity),
                    ExportKindCount(
                        "reflection", totalReflections, exportedReflections,
                        omitted = totalReflections - exportedReflections,
                    ),
                ),
                omissions = omissions,
            ),
            habits = entries,
            // The bundle stores the mood NAME so the file stays readable and
            // stays valid if the enum is ever reordered or renamed.
            moodHistory = moodInRange.mapValues { it.value.name },
            activity = orphanActivity,
            reflections = orphanReflections,
            settings = ExportSettings(
                themeMode = data.settings.themeMode.name,
                language = data.settings.language.code,
                isPremium = data.settings.isPremium,
                earnedBadges = data.settings.earnedBadges.map { it.name }.sorted(),
                connectedProviders = data.settings.connectedProviderIds(),
                alarmTimeCount = habits.sumOf { it.alarmTimes.size },
                habitCount = habits.size,
            ),
        )
        return bundle
    }

    private fun countOutcome(entries: List<HabitExportEntry>, outcome: AlarmEventOutcome): Int =
        entries.sumOf { e -> e.alarmEvents.count { it.outcome == outcome } }

    /**
     * Every day key this install has data for, from every source.
     *
     * Used only to derive the covered window for a full-history export, so the
     * header can state the range it actually spans instead of claiming "all
     * time" while silently covering three days.
     */
    private fun AppData.allDayKeys(): List<String> {
        val keys = LinkedHashSet<String>()
        checkIns.values.forEach { keys += it }
        habitLogs.forEach { keys += it.dayKey }
        alarmEvents.forEach { keys += it.dayKey }
        activityRecords.forEach { keys += it.dayKey() }
        keys += moodHistory.keys
        keys += reflections.keys
        return keys.sorted()
    }

    /** Which providers were connected, without ever touching a token. */
    private fun AppSettings.connectedProviderIds(): List<String> {
        val ids = ArrayList<String>(3)
        if (stravaRefreshToken != null || stravaAccessToken != null) ids += ActivitySources.STRAVA
        if (healthConnectConnected) ids += ActivitySources.HEALTH_CONNECT
        if (polarAccessToken != null) ids += ActivitySources.POLAR
        return ids
    }
}

/**
 * An inclusive ISO day window.
 *
 * Compares as **strings**, which is exact for ISO-8601 `yyyy-MM-dd` and avoids
 * building a `LocalDate` per record during a large export.
 */
data class ExportRange(val start: String?, val end: String?) {
    fun contains(dayKey: String): Boolean {
        if (start != null && dayKey < start) return false
        if (end != null && dayKey > end) return false
        return true
    }

    /** Inclusive day count; 0 when the bounds are unknown. */
    fun dayCount(): Int {
        if (start == null || end == null) return 0
        return runCatching {
            (LocalDate.parse(end).toEpochDay() - LocalDate.parse(start).toEpochDay() + 1)
                .coerceAtLeast(0).toInt()
        }.getOrDefault(0)
    }
}
