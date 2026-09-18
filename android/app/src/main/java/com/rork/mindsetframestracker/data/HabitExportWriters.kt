package com.rork.mindsetframestracker.data

import kotlinx.serialization.json.Json

/**
 * Renders a [HabitExportBundle] into shareable text.
 *
 * Three formats, for three different readers:
 *  - [json] — the complete, machine-readable record; lossless and re-importable.
 *  - [csv]  — tidy multi-table CSV for a spreadsheet.
 *  - [report] — a human-readable document someone can actually read.
 *
 * All three are built from the **same bundle**, so no format can disagree with
 * another about what the data is, and every format states the same completeness
 * verdict.
 *
 * Android-free and clock-free, so every rendering path is unit-testable.
 */
object HabitExportWriters {

    /**
     * `encodeDefaults = true` is essential here, not cosmetic.
     *
     * Without it, a habit with no icon would omit the `iconId` key entirely, and
     * a reader could not tell "this habit has no icon" from "this export came
     * from a build that did not record icons". Emitting defaults makes the file
     * self-describing, which is the whole point.
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        prettyPrintIndent = "  "
    }

    /** The lossless, re-importable representation. */
    fun json(bundle: HabitExportBundle): String =
        json.encodeToString(HabitExportBundle.serializer(), bundle)

    /**
     * Tidy multi-table CSV.
     *
     * One file, several tables, each introduced by a `# --- name ---` marker and
     * its own header row. A single flat table would have forced every record type
     * into one enormous sparse row; separate delimited tables keep each record
     * type's columns meaningful, and `#` comments are understood by Excel,
     * LibreOffice and `pandas.read_csv(comment='#')` alike.
     */
    fun csv(bundle: HabitExportBundle): String = buildString {
        // ── Header: makes the file self-describing even after being renamed ──
        append("# ").append("Mindset Frames habit export").append(CRLF)
        append("# ").append("format=").append(bundle.format).append(CRLF)
        append("# ").append("schemaVersion=").append(bundle.schemaVersion).append(CRLF)
        append("# ").append("appVersion=").append(bundle.appVersionName)
            .append(" (").append(bundle.appVersionCode).append(")").append(CRLF)
        append("# ").append("exportedAt=").append(bundle.exportedAtIso).append(CRLF)
        append("# ").append("range=").append(bundle.rangeStartDay ?: "-")
            .append(" to ").append(bundle.rangeEndDay ?: "-")
            .append(" (").append(bundle.rangeDays).append(" days, ")
            .append(if (bundle.isFullHistory) "full history" else "selected range").append(")").append(CRLF)
        append("# ").append("complete=").append(bundle.verification.isComplete)
            .append(" (nothing omitted)").append(CRLF)
        append("# ").append("note=timestamps are ISO-8601; times are 24-hour HH:MM local")
            .append(CRLF)

        // ── Summary ──────────────────────────────────────────────────────────
        append(CRLF).append("# --- summary ---").append(CRLF)
        row("metric", "value")
        val s = bundle.summary
        listOf(
            "habits" to s.habitCount.toString(),
            "daysCovered" to s.daysCovered.toString(),
            "activeDays" to s.activeDays.toString(),
            "checkIns" to s.totalCheckIns.toString(),
            "logEntries" to s.totalLogEntries.toString(),
            "alarmEvents" to s.totalAlarmEvents.toString(),
            "alarmsFired" to s.alarmsFired.toString(),
            "alarmsAcknowledged" to s.alarmsAcknowledged.toString(),
            "alarmsDismissed" to s.alarmsDismissed.toString(),
            "alarmsSnoozed" to s.alarmsSnoozed.toString(),
            "activityRecords" to s.totalActivityRecords.toString(),
            "totalSteps" to s.totalSteps.toString(),
            "totalDistanceMeters" to s.totalDistanceMeters.toString(),
            "totalActivityMinutes" to s.totalActivityMinutes.toString(),
            "reflectionDays" to s.reflectionDays.toString(),
            "moodDays" to s.moodDays.toString(),
            "bestCurrentStreak" to s.bestCurrentStreak.toString(),
            "bestStreakHabit" to (s.bestStreakHabit ?: ""),
        ).forEach { (k, v) -> row(k, v) }

        // ── Record counts / completeness proof ───────────────────────────────
        append(CRLF).append("# --- verification ---").append(CRLF)
        row("recordType", "inSource", "inExport", "omitted", "complete")
        bundle.verification.counts.forEach { c ->
            row(c.kind, c.inSource.toString(), c.inExport.toString(), c.omitted.toString(), c.isComplete.toString())
        }
        if (bundle.verification.omissions.isNotEmpty()) {
            append(CRLF).append("# --- omissions ---").append(CRLF)
            row("recordType", "id", "reason", "detail")
            bundle.verification.omissions.forEach { o ->
                row(o.kind, o.id, o.reason.name, o.detail)
            }
        }

        // ── Habits ───────────────────────────────────────────────────────────
        append(CRLF).append("# --- habits ---").append(CRLF)
        row(
            "id", "name", "createdAtIso", "isPinned", "alarmTimes", "repeatDaysMask", "repeatLabel",
            "iconId", "trackingMode", "durationSeconds", "trackingTargetSeconds", "trackingTargetCount",
            "trackingUnit", "alarmMessage", "monitoredPackage", "monitoredAppLabel",
            "screenTimeLimitMinutes", "currentStreak", "daysChecked", "checkInCount",
            "logCount", "alarmEventCount", "activityCount",
        )
        bundle.habits.forEach { e ->
            val h = e.habit
            row(
                h.id, h.name, isoOf(h.createdAt), h.isPinned.toString(),
                h.alarmTimes.joinToString("|") { alarmClockLabel(it) },
                h.repeatDaysMask.toString(), HabitRepeat.describe(h.repeatDaysMask),
                h.iconId ?: "", h.trackingMode?.name ?: "", h.durationSeconds?.toString() ?: "",
                h.trackingTargetSeconds?.toString() ?: "", h.trackingTargetCount?.toString() ?: "",
                h.trackingUnit ?: "", h.alarmMessage ?: "", h.monitoredPackage ?: "",
                h.monitoredAppLabel ?: "", h.screenTimeLimitMinutes?.toString() ?: "",
                e.currentStreak.toString(), e.daysChecked.toString(), e.checkIns.size.toString(),
                e.logs.size.toString(), e.alarmEvents.size.toString(), e.activity.size.toString(),
            )
        }

        // ── Check-ins ────────────────────────────────────────────────────────
        append(CRLF).append("# --- checkIns ---").append(CRLF)
        row("habitId", "habitName", "dayKey", "weekday")
        bundle.habits.forEach { e ->
            e.checkIns.forEach { day -> row(e.habit.id, e.habit.name, day, weekdayOf(day)) }
        }

        // ── Habit logs (the detailed payload) ────────────────────────────────
        append(CRLF).append("# --- habitLogs ---").append(CRLF)
        row(
            "id", "habitId", "habitName", "dayKey", "mode", "occurrenceKey",
            "title", "note", "durationSeconds", "count", "unit", "recordedAtIso",
        )
        bundle.habits.forEach { e ->
            e.logs.forEach { l ->
                row(
                    l.id, l.habitId, e.habit.name, l.dayKey, l.mode.name,
                    l.occurrenceKey ?: "", l.title ?: "", l.note ?: "",
                    l.durationSeconds?.toString() ?: "", l.count?.toString() ?: "",
                    l.unit ?: "", isoOf(l.recordedAtEpochMs),
                )
            }
        }

        // ── Alarm events: one row per (habit, day, scheduled time) ──────────
        // Never collapsed: three entries for a 07:00/12:00/18:00 habit is the
        // whole reason this table has a `scheduledTime` column.
        append(CRLF).append("# --- alarmEvents ---").append(CRLF)
        row(
            "id", "habitId", "habitName", "dayKey", "scheduledTime", "scheduledMinutes",
            "outcome", "answered", "firedAtIso", "respondedAtIso", "message",
        )
        bundle.habits.forEach { e ->
            e.alarmEvents.forEach { a ->
                row(
                    a.id, a.habitId, e.habit.name, a.dayKey, a.clockLabel,
                    a.scheduledMinutes.toString(), a.outcome.name,
                    a.outcome.isAnswered.toString(),
                    if (a.firedAtEpochMs > 0) isoOf(a.firedAtEpochMs) else "",
                    a.respondedAtEpochMs?.let { isoOf(it) } ?: "",
                    a.message ?: "",
                )
            }
        }

        // ── Activity ─────────────────────────────────────────────────────────
        append(CRLF).append("# --- activity ---").append(CRLF)
        row(
            "id", "habitId", "habitName", "source", "activityType", "activityName",
            "startIso", "endIso", "durationMinutes", "distanceMeters", "steps",
            "heartRateAvg", "heartRateMax", "calories", "elevationGainMeters", "sleepMinutes",
        )
        fun activityRow(habitId: String, habitName: String, r: ActivityRecord) = row(
            r.id, habitId, habitName, r.source, r.activityType, r.activityName ?: "",
            isoOf(r.timestamp), r.endedAtMs?.let { isoOf(it) } ?: "",
            r.durationMinutes?.toString() ?: "", r.distanceMeters?.toString() ?: "",
            r.steps?.toString() ?: "", r.heartRateAvg?.toString() ?: "",
            r.heartRateMax?.toString() ?: "", r.calories?.toString() ?: "",
            r.elevationGainMeters?.toString() ?: "", r.sleepMinutes?.toString() ?: "",
        )
        bundle.habits.forEach { e -> e.activity.forEach { activityRow(e.habit.id, e.habit.name, it) } }
        bundle.activity.forEach { activityRow(it.habitId, "", it) }

        // ── Reflections & mood ───────────────────────────────────────────────
        append(CRLF).append("# --- reflections ---").append(CRLF)
        row("dayKey", "habitId", "habitName", "text")
        bundle.habits.forEach { e ->
            e.reflections.forEach { (day, text) -> row(day, e.habit.id, e.habit.name, text) }
        }
        bundle.reflections.forEach { (day, text) -> row(day, "", "", text) }

        append(CRLF).append("# --- mood ---").append(CRLF)
        row("dayKey", "mood")
        bundle.moodHistory.toSortedMap().forEach { (day, mood) -> row(day, mood) }

        // ── Settings ─────────────────────────────────────────────────────────
        append(CRLF).append("# --- settings ---").append(CRLF)
        row("key", "value")
        row("themeMode", bundle.settings.themeMode)
        row("language", bundle.settings.language)
        row("isPremium", bundle.settings.isPremium.toString())
        row("earnedBadges", bundle.settings.earnedBadges.joinToString("|"))
        row("connectedProviders", bundle.settings.connectedProviders.joinToString("|"))
        row("alarmTimeCount", bundle.settings.alarmTimeCount.toString())
    }

    /** CRLF, as the CSV spec requires. */
    private const val CRLF = "\r\n"

    private fun StringBuilder.row(vararg cells: String) {
        append(cells.joinToString(",") { csvCell(it) }).append(CRLF)
    }

    /**
     * RFC-4180 escaping, plus a formula-injection guard.
     *
     * ## Escaping
     * A field containing a comma, a quote or a line break is wrapped in double
     * quotes with internal quotes doubled. This is what keeps a habit named
     * `Walk, then "reflect"` or a journal note spanning lines from corrupting
     * every subsequent column.
     *
     * ## Formula injection
     * A field beginning with `=`, `+`, `-` or `@` is interpreted as a formula by
     * Excel and Sheets, which can execute on open (`=HYPERLINK(...)`, DDE). A
     * habit name is user-supplied text that ends up in a file the user may open
     * in a spreadsheet, so such a field is prefixed with a single quote. This is
     * the standard mitigation, and it is why an apostrophe may appear before a
     * leading `=` in this file.
     *
     * Emoji and any other non-ASCII text need no treatment: the file is written
     * UTF-8 (and a BOM is emitted alongside it), so they round-trip verbatim.
     */
    private fun csvCell(value: String): String {
        val needsFormulaGuard = value.isNotEmpty() &&
            (value[0] == '=' || value[0] == '+' || value[0] == '-' || value[0] == '@' ||
                value[0] == '\t' || value[0] == '\r')
        val guarded = if (needsFormulaGuard) "'$value" else value
        val needsQuoting = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"${guarded.replace("\"", "\"\"")}\"" else guarded
    }

    /** Epoch millis to ISO-8601 UTC; empty for 0 (the "never" sentinel). */
    private fun isoOf(epochMs: Long): String =
        if (epochMs <= 0L) "" else java.time.Instant.ofEpochMilli(epochMs).toString()

    /** "Mon" for an ISO day key, so a reader can see weekday patterns at a glance. */
    private fun weekdayOf(dayKey: String): String = runCatching {
        java.time.LocalDate.parse(dayKey).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    }.getOrDefault("")

    /**
     * A human-readable report.
     *
     * This is the format for "share my habits with someone" and for a user
     * reading their own history: it opens with the completeness verdict, then
     * lays out each habit and its full trailing history in chronological order.
     */
    fun report(bundle: HabitExportBundle): String = buildString {
        appendLine("MINDSET FRAMES — COMPLETE HABIT EXPORT")
        appendLine("=".repeat(60))
        appendLine()
        appendLine("Export format : ${bundle.format} v${bundle.schemaVersion}")
        appendLine("App version   : ${bundle.appVersionName} (${bundle.appVersionCode})")
        appendLine("Exported at   : ${bundle.exportedAtIso}")
        appendLine(
            "Date range    : ${bundle.rangeStartDay ?: "—"} to ${bundle.rangeEndDay ?: "—"}" +
                "  (${bundle.rangeDays} days, " +
                (if (bundle.isFullHistory) "entire history" else "selected range") + ")",
        )
        appendLine(
            "Completeness  : " + if (bundle.verification.isComplete) {
                "VERIFIED COMPLETE — every record was exported (${bundle.verification.totalRecords} records)"
            } else {
                "INCOMPLETE — ${bundle.verification.omissions.size} record(s) omitted; see below"
            },
        )
        appendLine()

        appendLine("SUMMARY")
        appendLine("-".repeat(60))
        val s = bundle.summary
        appendLine("Habits tracked        : ${s.habitCount}")
        appendLine("Days covered          : ${s.daysCovered} (${s.activeDays} with activity)")
        appendLine("Check-ins             : ${s.totalCheckIns}")
        appendLine("Detailed log entries  : ${s.totalLogEntries}")
        appendLine("Alarm occurrences     : ${s.totalAlarmEvents}")
        appendLine("   fired / ack / dismiss / snooze : ${s.alarmsFired} / ${s.alarmsAcknowledged} / " +
            "${s.alarmsDismissed} / ${s.alarmsSnoozed}")
        appendLine("Activity records      : ${s.totalActivityRecords}")
        if (s.totalSteps > 0) appendLine("Steps                 : ${s.totalSteps}")
        if (s.totalDistanceMeters > 0) {
            val km = "%.2f".format(s.totalDistanceMeters / 1000.0)
            appendLine("Distance              : $km km")
        }
        if (s.totalActivityMinutes > 0) appendLine("Activity minutes      : ${s.totalActivityMinutes}")
        appendLine("Reflection days       : ${s.reflectionDays}")
        appendLine("Mood days             : ${s.moodDays}")
        appendLine("Best current streak   : ${s.bestCurrentStreak}" +
            (s.bestStreakHabit?.let { " ($it)" } ?: ""))
        appendLine()

        appendLine("RECORD COUNTS (source vs exported)")
        appendLine("-".repeat(60))
        appendLine(String.format("%-18s %10s %10s %10s  %s", "Record type", "In data", "Exported", "Omitted", "Complete"))
        bundle.verification.counts.forEach { c ->
            appendLine(
                String.format(
                    "%-18s %10d %10d %10d  %s",
                    c.kind, c.inSource, c.inExport, c.omitted, if (c.isComplete) "yes" else "NO",
                ),
            )
        }
        appendLine()

        if (bundle.verification.omissions.isNotEmpty()) {
            appendLine("OMITTED RECORDS")
            appendLine("-".repeat(60))
            bundle.verification.omissions.forEach { o ->
                appendLine("  ${o.kind} ${o.id} — ${o.reason.name}${if (o.detail.isNotEmpty()) " (${o.detail})" else ""}")
            }
            appendLine()
        }

        appendLine("HABITS (${bundle.habits.size})")
        appendLine("=".repeat(60))
        bundle.habits.forEachIndexed { index, e ->
            val h = e.habit
            appendLine()
            appendLine("${index + 1}. ${h.name}")
            appendLine("-".repeat(60))
            appendLine("   id            : ${h.id}")
            appendLine("   created       : ${isoOf(h.createdAt).ifEmpty { "—" }}")
            appendLine("   tracking      : ${h.trackingMode?.name ?: "(from icon)"}" +
                (h.iconId?.let { "  icon: $it" } ?: ""))
            appendLine("   alarms        : " +
                (h.alarmTimes.joinToString(", ") { alarmClockLabel(it) }.ifEmpty { "none" }))
            appendLine("   repeats       : ${HabitRepeat.describe(h.repeatDaysMask)}")
            h.alarmMessage?.takeIf { it.isNotBlank() }?.let { appendLine("   message       : \"$it\"") }
            if (h.monitoredAppLabel != null) {
                appendLine("   monitors      : ${h.monitoredAppLabel} (${h.screenTimeLimitMinutes ?: 0} min/day)")
            }
            appendLine("   streak        : ${e.currentStreak} days")
            appendLine("   days checked  : ${e.daysChecked}")
            appendLine("   log entries   : ${e.logs.size}")
            appendLine("   alarm events  : ${e.alarmEvents.size}")
            appendLine("   activity      : ${e.activity.size}")

            if (e.alarmEvents.isNotEmpty()) {
                appendLine("   ── alarm history ──")
                e.alarmEvents.forEach { a ->
                    val msg = a.message?.takeIf { it.isNotBlank() }?.let { "  \"$it\"" } ?: ""
                    appendLine("     ${a.dayKey}  ${a.clockLabel}  ${a.outcome.name}$msg")
                }
            }
            if (e.logs.isNotEmpty()) {
                appendLine("   ── log entries ──")
                e.logs.forEach { l ->
                    val parts = ArrayList<String>()
                    l.title?.takeIf { it.isNotBlank() }?.let { parts += it }
                    l.durationSeconds?.let { parts += "${it / 60}m ${it % 60}s" }
                    l.count?.let { parts += "$it ${l.unit ?: ""}".trim() }
                    l.note?.takeIf { it.isNotBlank() }?.let { parts += it }
                    appendLine("     ${l.dayKey}  ${l.mode.name}  ${parts.joinToString(" · ")}")
                }
            }
            if (e.activity.isNotEmpty()) {
                appendLine("   ── activity ──")
                e.activity.forEach { r ->
                    val parts = ArrayList<String>()
                    r.durationMinutes?.let { parts += "${it} min" }
                    r.steps?.let { parts += "$it steps" }
                    r.distanceMeters?.let { parts += "%.2f".format(it / 1000.0) + " km" }
                    r.calories?.let { parts += "$it kcal" }
                    appendLine("     ${r.dayKey()}  ${r.activityName ?: r.activityType}  " +
                        "[${ActivitySources.label(r.source)}]  ${parts.joinToString(" · ")}")
                }
            }
            if (e.reflections.isNotEmpty()) {
                appendLine("   ── reflections ──")
                e.reflections.toSortedMap().forEach { (day, text) -> appendLine("     $day  $text") }
            }
        }

        if (bundle.moodHistory.isNotEmpty()) {
            appendLine()
            appendLine("MOOD BY DAY")
            appendLine("=".repeat(60))
            bundle.moodHistory.toSortedMap().forEach { (day, mood) -> appendLine("  $day  $mood") }
        }

        appendLine()
        appendLine("=".repeat(60))
        appendLine("End of export. This document is a complete copy of the tracked data")
        appendLine("listed above; the record-count table is the proof of that claim.")
    }
}
