package com.rork.mindsetframestracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The schema version of the export bundle written by this build.
 *
 * Bumped whenever the shape changes in a way an older reader could not cope
 * with. A reader that finds a **higher** version than it knows refuses the file
 * rather than guessing at fields it cannot understand, which is what keeps a
 * future export from being silently truncated by a past build.
 *
 * `1` is the first version that captures every record type, so nothing below
 * this number ever existed.
 */
const val EXPORT_SCHEMA_VERSION = 1

/** Marker written into every file so an unrelated JSON file can't be mistaken for an export. */
const val EXPORT_FORMAT_MARKER = "mindsetframes.habit-export"

/**
 * Why a record was left out of the export.
 *
 * An export that silently drops data is worse than one that fails: the user
 * believes they have a complete copy and deletes the original. Every omission
 * is therefore recorded and surfaced, never inferred.
 */
@Serializable
enum class ExportOmission {
    /** The record's habit is not among the habits exported (e.g. a deleted habit's leftovers). */
    ORPHANED_HABIT,
    /** The record's day key falls outside the requested range. */
    OUT_OF_RANGE,
    /** The record was unreadable/malformed and could not be represented. */
    UNREADABLE,
}

/** One concrete record that was not exported, with enough detail to find it. */
@Serializable
data class ExportOmissionDetail(
    /** "checkIn", "habitLog", "alarmEvent", "activityRecord", "reflection", "habit"… */
    val kind: String,
    /** Stable identifier of the dropped record, for the user to locate it. */
    val id: String,
    val reason: ExportOmission,
    /** Human-readable context (habit name, day key…). */
    val detail: String = "",
)

/**
 * A record count for one kind of data, with the verification result.
 *
 * [inSource] and [inExport] are counted **independently**: the first by walking
 * the live `AppData`, the second by walking what was actually written into the
 * bundle. They must agree. Counting the export by re-reading the same in-memory
 * list it was built from would prove nothing.
 */
@Serializable
data class ExportKindCount(
    val kind: String,
    val inSource: Int,
    val inExport: Int,
    val omitted: Int = 0,
) {
    /** True when every source record of this kind is present in the export. */
    val isComplete: Boolean get() = inSource == inExport + omitted
}

/**
 * The self-describing verification block: what was exported, from where, and
 * whether anything was left behind.
 *
 * This is the part that makes the export *checkable by its reader*. A shared
 * file can be opened by the recipient and shown to be complete without trusting
 * the sender's word for it.
 */
@Serializable
data class ExportVerification(
    val counts: List<ExportKindCount> = emptyList(),
    val omissions: List<ExportOmissionDetail> = emptyList(),
) {
    /** True when every kind balances and nothing was dropped. */
    val isComplete: Boolean get() = omissions.isEmpty() && counts.all { it.isComplete }

    /** Total records of every kind, as exported. */
    val totalRecords: Int get() = counts.sumOf { it.inExport }
}

/**
 * A habit and its **entire** history, nested.
 *
 * Grouping the history under its habit (rather than leaving five parallel flat
 * lists) is what makes the file readable on its own: a recipient can see one
 * habit's check-ins, logs, alarm events and activity together without joining
 * anything by hand.
 */
@Serializable
data class HabitExportEntry(
    /** The habit exactly as persisted, so an import restores every field verbatim. */
    val habit: Habit,
    /** ISO day keys this habit was checked. */
    val checkIns: List<String> = emptyList(),
    /** Every detailed log entry — duration, count, unit, note, occurrence key. */
    val logs: List<HabitLogEntry> = emptyList(),
    /**
     * Every alarm occurrence, one per `(habit, day, scheduled time)`.
     *
     * Deliberately a list of events and not a per-day summary: a habit that rang
     * at 07:00/12:00/18:00 has three entries here, and collapsing them would
     * destroy precisely the information the field exists to preserve.
     */
    val alarmEvents: List<HabitAlarmEvent> = emptyList(),
    /** Activity imported from Strava / Health Connect / Polar, attributed to this habit. */
    val activity: List<ActivityRecord> = emptyList(),
    /** Grounding micro-journal entries, keyed by ISO day. */
    val reflections: Map<String, String> = emptyMap(),
    /** Current consecutive-day streak for this habit. */
    val currentStreak: Int = 0,
    /** Total days this habit was ever checked. */
    val daysChecked: Int = 0,
)

/**
 * The whole export.
 *
 * Everything the app knows about the user's habits, in one versioned object.
 * Written as JSON, and additionally flattened to CSV for spreadsheet use.
 *
 * ## Why every field is present even when empty
 *
 * A reader must be able to tell "this install had no moods recorded" from "this
 * export forgot to include moods". `encodeDefaults = true` and explicit
 * (possibly empty) collections mean an absent list means *nothing*, never
 * *unknown*.
 */
@Serializable
data class HabitExportBundle(
    /** Always [EXPORT_FORMAT_MARKER]; lets a reader reject an unrelated JSON file. */
    val format: String = EXPORT_FORMAT_MARKER,
    /** Schema version of this document — see [EXPORT_SCHEMA_VERSION]. */
    @SerialName("schemaVersion") val schemaVersion: Int = EXPORT_SCHEMA_VERSION,
    /** App version that produced the file, for forensics ("which build wrote this?"). */
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    /** When the export ran, ISO-8601 UTC. */
    val exportedAtIso: String = "",
    /** Start of the covered window (ISO day), inclusive. Null = no lower bound. */
    val rangeStartDay: String? = null,
    /** End of the covered window (ISO day), inclusive. Null = no upper bound. */
    val rangeEndDay: String? = null,
    /** Number of days the window spans, inclusive. */
    val rangeDays: Int = 0,
    /** True when the window is the user's entire history rather than a chosen range. */
    val isFullHistory: Boolean = true,

    /** Headline totals — the "summary section" a reader sees first. */
    val summary: ExportSummary = ExportSummary(),
    /** Per-kind record counts and the completeness verdict. */
    val verification: ExportVerification = ExportVerification(),

    /** Every habit, each with its full nested history. */
    val habits: List<HabitExportEntry> = emptyList(),
    /** Mood per ISO day (mood-aware habit tracking). */
    val moodHistory: Map<String, String> = emptyMap(),
    /** Activity records not attributable to any single habit (or any habit still present). */
    val activity: List<ActivityRecord> = emptyList(),
    /** Grounding reflections keyed by ISO day, for days no habit covers. */
    val reflections: Map<String, String> = emptyMap(),
    /** App settings, minus secrets. */
    val settings: ExportSettings = ExportSettings(),
)

/**
 * The reader-facing summary block.
 *
 * Deliberately computed from the same source as the detail so the headline can
 * never disagree with the body — a mismatch there would be the first thing a
 * user notices and the last thing they'd trust again.
 */
@Serializable
data class ExportSummary(
    val habitCount: Int = 0,
    val daysCovered: Int = 0,
    /** Days on which at least one habit was completed. */
    val activeDays: Int = 0,
    val totalCheckIns: Int = 0,
    val totalLogEntries: Int = 0,
    /** Total alarm occurrences recorded, across every habit and time. */
    val totalAlarmEvents: Int = 0,
    val alarmsFired: Int = 0,
    val alarmsAcknowledged: Int = 0,
    val alarmsDismissed: Int = 0,
    val alarmsSnoozed: Int = 0,
    val totalActivityRecords: Int = 0,
    val totalSteps: Long = 0L,
    val totalDistanceMeters: Double = 0.0,
    val totalActivityMinutes: Int = 0,
    val reflectionDays: Int = 0,
    val moodDays: Int = 0,
    /** Longest current streak across all habits. */
    val bestCurrentStreak: Int = 0,
    /** The habit holding [bestCurrentStreak]. */
    val bestStreakHabit: String? = null,
)

/**
 * App settings that are safe to export.
 *
 * ## What is deliberately excluded, and why
 *
 * OAuth tokens (`stravaAccessToken`, `stravaRefreshToken`, `polarAccessToken`)
 * are **never** exported: this file is designed to be shared with another
 * person, and a bearer token in a shared file is a credential leak. The user's
 * *connection state* is exported so they can see what was connected, without
 * the secret that would let a recipient act as them.
 */
@Serializable
data class ExportSettings(
    val themeMode: String = "",
    val language: String = "",
    val isPremium: Boolean = false,
    val earnedBadges: List<String> = emptyList(),
    /** Providers that were connected at export time. Secrets are never included. */
    val connectedProviders: List<String> = emptyList(),
    val alarmTimeCount: Int = 0,
    val habitCount: Int = 0,
)
