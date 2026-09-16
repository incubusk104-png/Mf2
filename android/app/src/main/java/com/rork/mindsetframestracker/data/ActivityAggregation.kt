package com.rork.mindsetframestracker.data

import java.time.Instant
import java.time.ZoneId

/**
 * The activity sources the app can capture from, and how each one is named.
 *
 * Kept in one place because the source string is written by four different
 * clients ([com.rork.mindsetframestracker.integrations.StravaAuthClient],
 * [com.rork.mindsetframestracker.integrations.PolarClient],
 * [com.rork.mindsetframestracker.integrations.ActivityMonitor] and
 * [com.rork.mindsetframestracker.integrations.MindsetHealthConnectClient]) and
 * read back by the Weekly and Insight screens. When the writer and the reader
 * disagree on a literal, the data silently vanishes from the UI, so the
 * literals live here rather than being re-typed at each end.
 */
object ActivitySources {
    const val STRAVA = "strava"
    const val POLAR = "polar"
    const val HEALTH_CONNECT = "health_connect"

    /** Records captured on-device by [com.rork.mindsetframestracker.integrations.ActivityMonitor]. */
    const val HEALTH_CONNECT_DEVICE = "health_connect_device"

    /** Display order — the order the per-source breakdown is rendered in. */
    val ALL = listOf(STRAVA, HEALTH_CONNECT_DEVICE, HEALTH_CONNECT, POLAR)

    /** Human-readable name, for headings and chips. */
    fun label(source: String): String = when (source) {
        STRAVA -> "Strava"
        POLAR -> "Polar"
        HEALTH_CONNECT, HEALTH_CONNECT_DEVICE -> "Google Health Connect"
        else -> source.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Groups the two Health Connect literals under one provider for display. */
    fun normalise(source: String): String = when (source) {
        HEALTH_CONNECT_DEVICE -> HEALTH_CONNECT
        else -> source
    }
}

/**
 * Totals over a set of [ActivityRecord]s.
 *
 * Every field defaults to zero and the empty value is distinguishable via
 * [isEmpty], so a caller can tell "no activity in this window" apart from
 * "activity that happened to be zero" — the two must never render the same,
 * because one means "nothing recorded" and the other would be a claim about
 * the user's body that no source actually made.
 *
 * Steps, distance, calories, duration and sleep are summed across sources.
 * Heart rate is an **average of the reported averages**, and keeps a count so
 * a window in which no source reported heart rate yields null rather than a
 * fabricated 0 bpm.
 */
data class ActivityTotals(
    val sessions: Int = 0,
    val steps: Long = 0L,
    val distanceMeters: Double = 0.0,
    val calories: Int = 0,
    val durationMinutes: Int = 0,
    val sleepMinutes: Int = 0,
    private val heartRateSum: Long = 0L,
    private val heartRateCount: Int = 0,
) {
    val isEmpty: Boolean get() = sessions == 0

    /** Mean of the reported averages, or null when nothing reported one. */
    val heartRateAvg: Int?
        get() = if (heartRateCount > 0) (heartRateSum / heartRateCount).toInt() else null

    companion object {
        val EMPTY = ActivityTotals()
    }
}

/** Local calendar day this record belongs to, from its own start time. */
fun ActivityRecord.dayKey(): String = Dates.key(
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate(),
)

/** One window's totals, summed per metric across every record present. */
fun List<ActivityRecord>.totals(): ActivityTotals {
    if (this.isEmpty()) return ActivityTotals.EMPTY
    val heartRates = mapNotNull { it.heartRateAvg }
    return ActivityTotals(
        sessions = size,
        steps = mapNotNull { it.steps }.sum(),
        distanceMeters = mapNotNull { it.distanceMeters }.sum(),
        calories = mapNotNull { it.calories }.sum(),
        durationMinutes = mapNotNull { it.durationMinutes }.sum(),
        sleepMinutes = mapNotNull { it.sleepMinutes }.sum(),
        heartRateSum = heartRates.sumOf { it.toLong() },
        heartRateCount = heartRates.size,
    )
}

/** Every record whose own start day is [dayKey]. */
fun AppData.activityRecordsOn(dayKey: String): List<ActivityRecord> =
    activityRecords.filter { it.dayKey() == dayKey }

/** Every record whose own start day falls inside [dayKeys]. */
fun AppData.activityRecordsOver(dayKeys: Collection<String>): List<ActivityRecord> {
    val days = dayKeys.toSet()
    return activityRecords.filter { it.dayKey() in days }
}

/** One day's totals. */
fun AppData.activityTotalsOn(dayKey: String): ActivityTotals =
    activityRecordsOn(dayKey).totals()

/** A window's totals. */
fun AppData.activityTotalsOver(dayKeys: Collection<String>): ActivityTotals =
    activityRecordsOver(dayKeys).totals()

/** True when any sourced activity was recorded on [dayKey]. */
fun AppData.hasActivityOn(dayKey: String): Boolean =
    activityRecords.any { it.dayKey() == dayKey }

/**
 * A window's totals **per source**, in [ActivitySources.ALL] order, with the
 * two Health Connect literals folded together.
 *
 * Only sources that actually produced something appear, so a user with no
 * Polar account never sees an empty Polar row — an absent integration renders
 * as nothing rather than as a row of zeros, which would read as "you did none
 * of this" instead of "this isn't connected".
 */
fun AppData.activitySourceTotals(dayKeys: Collection<String>): Map<String, ActivityTotals> {
    val records = activityRecordsOver(dayKeys)
    if (records.isEmpty()) return emptyMap()
    val ordered = LinkedHashMap<String, List<ActivityRecord>>()
    ActivitySources.ALL.forEach { source ->
        val matching = records.filter {
            ActivitySources.normalise(it.source) == ActivitySources.normalise(source)
        }
        if (matching.isNotEmpty()) ordered[source] = matching
    }
    // Any source literal not in ALL still gets its own row rather than being
    // dropped, so an unrecognised future provider is visible instead of silent.
    records.map { it.source }
        .distinct()
        .filterNot { raw -> ordered.keys.any { ActivitySources.normalise(it) == ActivitySources.normalise(raw) } }
        .forEach { raw -> ordered[raw] = records.filter { it.source == raw } }
    return ordered.mapValues { (_, matching) -> matching.totals() }
}

/** One habit's sourced activity over a window. */
fun AppData.activityTotalsForHabit(habitId: String, dayKeys: Collection<String>): ActivityTotals {
    val days = dayKeys.toSet()
    return activityRecords.filter { it.habitId == habitId && it.dayKey() in days }.totals()
}

/**
 * Distances a source-reported value the user can actually read: "1.2 km", or
 * "—" when nothing was recorded.
 *
 * A zero-distance record is rendered as "—" rather than "0.0 km" for the same
 * reason heart rate keeps a null: a phone with no GPS and no paired device
 * genuinely does not know the distance, and printing "0.0 km" states that the
 * user did not move.
 */
fun formatDistance(meters: Double?): String {
    if (meters == null || meters <= 0.0) return "—"
    return if (meters < 1000.0) "${meters.toInt()} m" else "%.1f km".format(meters / 1000.0)
}

/** Minutes as "1h 20m" / "45m"; "—" when nothing was recorded. */
fun formatMinutes(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "—"
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** Renders [value] or the em-dash placeholder when it is absent or zero. */
fun formatCount(value: Long?): String =
    if (value == null || value <= 0L) "—" else "%,d".format(value)

/** Renders [value] or the em-dash placeholder when it is absent or zero. */
fun formatCount(value: Int?): String =
    if (value == null || value <= 0) "—" else "%,d".format(value)
