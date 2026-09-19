package com.rork.mindsetframestracker.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How an imported activity record keeps its link to the habit it serves once it
 * has been through the cloud.
 *
 * ## Why the link has to travel inside `raw_data`
 *
 * The `activity_data` table's `habit_id` column is **nullable and unused** — the
 * push never sends it and the pull never read it. What the pull did instead was
 * hardcode `habitId = ""` for *every* restored row. So on a second device every
 * activity arrived present but unattributed: the account-wide totals were right,
 * while per-habit sourced consistency read zero and the weekly view credited
 * nothing to the habit the workout was actually for. The data was all there and
 * none of it could be used.
 *
 * `raw_data jsonb` is the one column that carries an arbitrary client payload
 * end-to-end, and it already transports `source` and `sleep_minutes` for exactly
 * this reason. The habit id rides with them. The alternative — populating the
 * real column — needs a migration this build cannot assume has been applied (the
 * same `PGRST204` situation `alarm_times` is in), and a field the server may
 * silently drop is not a field to depend on for attribution.
 *
 * ## Why a blank id is preserved rather than invented
 *
 * A record with no habit id genuinely has none: Polar's daily step roll-up is not
 * attributable to a particular habit, and that is a fact about the record, not a
 * gap to paper over by picking the first sport habit. It round-trips as blank and
 * the caller reports it — see [partitionByAttributableHabitId].
 */
const val ACTIVITY_RAW_HABIT_ID: String = "habit_id"

/**
 * The habit id carried by a cloud activity row, or `""` when it has none.
 *
 * Tolerant on purpose: `raw_data` is server data this app does not control, so a
 * value that is not a plain string (an object, a number, an array) is read as
 * absent rather than allowed to throw. A malformed payload must not be able to
 * take down a restore — the record is still worth keeping for its steps.
 */
fun habitIdFromActivityRawData(raw: JsonObject?): String =
    runCatching { raw?.get(ACTIVITY_RAW_HABIT_ID)?.jsonPrimitive?.contentOrNull }
        .getOrNull()
        .orEmpty()

/**
 * The `raw_data` payload for `activity_data`, carrying the attribution the
 * server's own columns cannot.
 *
 * Sleep minutes ride here rather than in `calories_burned`, which the table has
 * no sleep column for — putting them in the calorie field would report sleep as
 * energy burned. Kept as one builder so the two transports cannot drift.
 */
fun activityRawData(
    source: String,
    habitId: String,
    sleepMinutes: Int? = null,
): JsonObject = JsonObject(
    buildMap {
        put("source", JsonPrimitive(source))
        // Omitted when blank so the field means "attributed to this habit"
        // rather than carrying an empty string that could be mistaken for an id.
        if (habitId.isNotBlank()) put(ACTIVITY_RAW_HABIT_ID, JsonPrimitive(habitId))
        sleepMinutes?.let { put("sleep_minutes", JsonPrimitive(it)) }
    },
)

/**
 * Splits activity records into those the server can store and those it cannot.
 *
 * ## Why this is a partition and not the silent filter it replaces
 *
 * The push filtered on `UUID.fromString(habitId).isSuccess` and discarded the
 * rest **without telling anyone** — no count, no message, no entry in the export
 * completeness list. Unlike the habit payload, nothing reconciled it, so the loss
 * was invisible; and because an unattributed record is never pushed, it can never
 * be attributed on any other device, so the loss reproduced on every device
 * forever. Turning a silent `filter` into a two-sided `partition` is what makes
 * the count available to report.
 *
 * A non-UUID habit id is not user error to be corrected: `habitId` is blank for
 * records no single habit owns, and a `share-import-`-prefixed id from a shared
 * habit is a legitimate local id that the `uuid` column simply cannot hold.
 */
fun partitionByAttributableHabitId(
    records: List<ActivityRecord>,
): Pair<List<ActivityRecord>, List<ActivityRecord>> = records.partition { record ->
    runCatching { java.util.UUID.fromString(record.habitId) }.isSuccess
}
