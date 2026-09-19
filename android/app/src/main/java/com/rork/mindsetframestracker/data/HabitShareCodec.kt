package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A compact, copy-pasteable code carrying habits - and optionally their entire
 * history - between two people.
 *
 * ## Why a code as well as a file
 *
 * A file needs both parties to have file transfer working (a share target, an
 * attachment, a download). A code works anywhere text does: a chat message, a
 * note, a QR code. It is the same payload, so the two paths can never drift.
 *
 * ## Shape
 *
 * ```
 * MF2-1-<base64url(deflate(utf8(json)))>
 * ```
 *
 *  - `MF2` identifies the app, so a stray string is rejected rather than
 *    misparsed.
 *  - `1` is the payload schema version ([SHARE_CODE_VERSION]).
 *  - the body is **deflated then base64url** (no padding): habit JSON is highly
 *    repetitive, so deflate typically cuts it several-fold, and base64url is
 *    what keeps the result safe to paste into a URL or a message with no
 *    escaping. Order matters - compressing first is what makes the code short
 *    enough to actually send.
 *
 * Android-free, so the whole encode/decode/import path is unit-testable.
 */
object HabitShareCodec {

    /** Bumped only if the *envelope* changes; the payload carries its own version. */
    const val SHARE_CODE_VERSION = 1

    /** Recognisable prefix, so a malformed paste is rejected immediately. */
    const val PREFIX = "MF2-$SHARE_CODE_VERSION-"

    /** Guards against a hostile or corrupt code inflating without bound. */
    private const val MAX_DECODED_BYTES = 8 * 1024 * 1024

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /**
     * What a shared code carries.
     *
     * [DEFINITIONS_ONLY] sends the habits themselves - the common "here are the
     * habits I use, try them" case, and small enough for a chat message.
     * [WITH_HISTORY] sends the full per-habit history too, which is what makes
     * sharing "everything" meaningful: the recipient sees the streaks and the
     * alarm history, not just an empty habit list.
     */
    enum class ShareScope { DEFINITIONS_ONLY, WITH_HISTORY }

    /** The payload a code carries. Versioned and self-describing, like the file export. */
    @Serializable
    data class SharePayload(
        val format: String = EXPORT_FORMAT_MARKER,
        val schemaVersion: Int = EXPORT_SCHEMA_VERSION,
        val createdAtIso: String = "",
        /** "DEFINITIONS_ONLY" or "WITH_HISTORY". */
        val scope: String = ShareScope.WITH_HISTORY.name,
        val senderLabel: String = "",
        /** Habits, each optionally with history. */
        val habits: List<HabitExportEntry> = emptyList(),
        /** Mood is personal, not habit-definition data; carried only with history. */
        val moodHistory: Map<String, String> = emptyMap(),
    )

    /**
     * Encodes a bundle as a share code.
     *
     * @param includeHistory false sends definitions only (ignores history).
     */
    fun encode(
        bundle: HabitExportBundle,
        includeHistory: Boolean = true,
        senderLabel: String = "",
    ): String {
        val payload = SharePayload(
            schemaVersion = bundle.schemaVersion,
            createdAtIso = bundle.exportedAtIso,
            scope = if (includeHistory) ShareScope.WITH_HISTORY.name else ShareScope.DEFINITIONS_ONLY.name,
            senderLabel = senderLabel,
            habits = bundle.habits.map { entry ->
                if (includeHistory) entry else entry.copy(
                    checkIns = emptyList(),
                    logs = emptyList(),
                    alarmEvents = emptyList(),
                    activity = emptyList(),
                    reflections = emptyMap(),
                    currentStreak = 0,
                    daysChecked = 0,
                )
            },
            moodHistory = if (includeHistory) bundle.moodHistory else emptyMap(),
        )
        return encodePayload(payload)
    }

    /**
     * Encodes an arbitrary [SharePayload] — the exact inverse of [decode].
     *
     * Public because it is a genuinely useful direction on its own (a caller
     * that builds a payload itself, or a future feature that forwards one), and
     * because it is what makes the version guard testable: a code stamped with a
     * future schema can only be produced by encoding a payload directly.
     */
    fun encodePayload(payload: SharePayload): String {
        val raw = json.encodeToString(SharePayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(raw))
    }

    /** Why a code could not be read - shown verbatim rather than as "invalid code". */
    sealed interface DecodeResult {
        data class Success(val payload: SharePayload) : DecodeResult

        /** Not a Mindset Frames code at all (wrong prefix). */
        data object NotAShareCode : DecodeResult

        /** Prefix was right but the body is damaged or truncated. */
        data object Corrupt : DecodeResult

        /** Written by a newer build whose schema this one does not understand. */
        data class TooNew(val schemaVersion: Int) : DecodeResult
    }

    /**
     * Decodes a code.
     *
     * Every failure mode is distinguished: "this isn't a code" and "this code is
     * damaged" and "this code is from a newer app" need three different things
     * from the user, and collapsing them into one "invalid" message is how a
     * user retries a truncated paste forever.
     */
    fun decode(code: String): DecodeResult {
        // Pastes routinely arrive with surrounding whitespace or a chat client's
        // line wrapping; strip only whitespace and newlines, never inner content.
        val cleaned = code.trim().filterNot { it == '\n' || it == '\r' || it == ' ' }
        if (cleaned.isEmpty()) return DecodeResult.NotAShareCode
        val body = when {
            cleaned.startsWith(PREFIX) -> cleaned.removePrefix(PREFIX)
            // Tolerate a code pasted with a link wrapper or different version
            // number prefix, as long as the MF2 marker is genuinely present.
            cleaned.startsWith("MF2-") -> cleaned.substringAfter('-', "").substringAfter('-', "")
            else -> return DecodeResult.NotAShareCode
        }
        if (body.isEmpty()) return DecodeResult.Corrupt

        val payload = runCatching {
            val compressed = Base64.getUrlDecoder().decode(body)
            val raw = inflate(compressed) ?: return DecodeResult.Corrupt
            json.decodeFromString<SharePayload>(raw.toString(Charsets.UTF_8))
        }.getOrNull() ?: return DecodeResult.Corrupt

        if (payload.format != EXPORT_FORMAT_MARKER) return DecodeResult.Corrupt
        // A newer schema may contain fields whose absence would silently change
        // meaning; refuse rather than import a partial habit.
        if (payload.schemaVersion > EXPORT_SCHEMA_VERSION) return DecodeResult.TooNew(payload.schemaVersion)
        return DecodeResult.Success(payload)
    }

    /**
     * What importing a payload would do, computed **before** anything is written.
     *
     * Letting the user see "3 new habits, 1 already present, 42 check-ins added"
     * before committing is what makes import safe to attempt: an import that
     * silently duplicated everything would be unrecoverable in a local-only store.
     */
    data class ImportPlan(
        /** Habits that will be added, with ids already remapped. */
        val newHabits: List<HabitExportEntry>,
        /**
         * Habits already present and identical — skipped as *habits*, but their
         * history is still merged.
         *
         * ## Why the entries are kept rather than just their names (D4)
         *
         * This list used to be `List<String>` of names, and `applyImport`
         * iterated only `newHabits` — so a habit that already existed had its
         * incoming history **discarded entirely**, even when the sender had
         * days and records this device had never seen. `applyImport`'s own
         * comment claimed it merged them ("Existing duplicate habits may still
         * carry history the sender has and we don't"), but the data had been
         * dropped one step earlier, so the claim was unreachable code.
         *
         * That is the D4 symptom: a shared habit arrives with its records present
         * in the file and absent on the receiving device, unattributable to
         * anything. Keeping the entry means the history has somewhere to land.
         */
        val duplicateEntryHabits: List<HabitExportEntry>,
        /** Habits already present and identical - skipped. */
        val duplicateHabits: List<String>,
        /** Habits whose id collided with a *different* habit; renamed on import. */
        val renamedHabits: List<Pair<String, String>>,
        /** History rows that are genuinely new, per habit id (as imported). */
        val newCheckIns: Int,
        val newLogs: Int,
        val newAlarmEvents: Int,
        val newActivity: Int,
        val newReflections: Int,
    ) {
        val isEmpty: Boolean get() = newHabits.isEmpty() && newCheckIns == 0 && newLogs == 0 &&
            newAlarmEvents == 0 && newActivity == 0 && newReflections == 0

        val totalNewRecords: Int
            get() = newCheckIns + newLogs + newAlarmEvents + newActivity + newReflections
    }

    /**
     * Decides what an import would change, without changing anything.
     *
     * ## ID handling
     *
     * A habit id is only meaningful within one install. Importing a friend's
     * habit verbatim would collide with an existing habit that happens to share
     * the id, so:
     *  - an incoming habit with an id that is free, or identical to the existing
     *    one, keeps that id;
     *  - an incoming habit whose id is taken by a **different** habit is given a
     *    fresh id, and every nested reference is rewritten to match, so its
     *    history stays attached to it rather than merging into the stranger.
     *
     * ## Dedupe
     *
     * History is merged by natural key - check-in by day, log by id, alarm event
     * by `(dayKey, scheduledMinutes)`, activity by id. Re-importing the same code
     * is therefore a no-op rather than a duplicate, which is what lets a user
     * paste a code twice without harm.
     */
    fun planImport(existing: AppData, payload: SharePayload): ImportPlan {
        val existingById = existing.habits.associateBy { it.id }
        val newHabits = ArrayList<HabitExportEntry>()
        val duplicateEntries = ArrayList<HabitExportEntry>()
        val duplicates = ArrayList<String>()
        val renamed = ArrayList<Pair<String, String>>()
        var newCheckIns = 0
        var newLogs = 0
        var newAlarms = 0
        var newActivity = 0
        var newReflections = 0

        payload.habits.forEach { entry ->
            val incoming = entry.habit
            val clash = existingById[incoming.id]
            val targetId = when {
                clash == null -> incoming.id
                isSameHabit(clash, incoming) -> incoming.id
                else -> {
                    val fresh = "$SHARE_IMPORT_ID_PREFIX${incoming.id}"
                    renamed += incoming.id to fresh
                    fresh
                }
            }
            val remapped = if (targetId == incoming.id) entry else entry.copy(
                habit = incoming.copy(id = targetId),
                logs = entry.logs.map { it.copy(habitId = targetId) },
                alarmEvents = entry.alarmEvents.map { it.copy(habitId = targetId) },
                activity = entry.activity.map { it.copy(habitId = targetId) },
            )

            if (clash != null && isSameHabit(clash, incoming)) {
                duplicates += incoming.name
                // D4: kept so its history can still be merged in `applyImport`.
                duplicateEntries += remapped
            } else {
                newHabits += remapped
            }

            // Count only genuinely-new history, against whatever already exists
            // for the id this habit will land on.
            val existingDays = existing.checkIns[targetId].orEmpty().toSet()
            newCheckIns += remapped.checkIns.count { it !in existingDays }

            val existingLogIds = existing.habitLogs.filter { it.habitId == targetId }.map { it.id }.toSet()
            newLogs += remapped.logs.count { it.id !in existingLogIds }

            val existingEventKeys = existing.alarmEvents
                .filter { it.habitId == targetId }.map { it.eventKey }.toSet()
            newAlarms += remapped.alarmEvents.count { it.eventKey !in existingEventKeys }

            val existingActivityIds = existing.activityRecords.filter { it.habitId == targetId }.map { it.id }.toSet()
            newActivity += remapped.activity.count { it.id !in existingActivityIds }

            val existingReflectionDays = existing.reflections.keys
            newReflections += remapped.reflections.keys.count { it !in existingReflectionDays }
        }

        return ImportPlan(
            newHabits = newHabits,
            duplicateEntryHabits = duplicateEntries,
            duplicateHabits = duplicates,
            renamedHabits = renamed,
            newCheckIns = newCheckIns,
            newLogs = newLogs,
            newAlarmEvents = newAlarms,
            newActivity = newActivity,
            newReflections = newReflections,
        )
    }

    /**
     * Applies a plan, returning the merged [AppData].
     *
     * Pure: takes data in, returns data out, so the caller owns persistence and
     * the merge itself is testable.
     */
    fun applyImport(existing: AppData, plan: ImportPlan): AppData {
        val habits = existing.habits + plan.newHabits.map { it.habit }
        val checkIns = existing.checkIns.toMutableMap()
        val logs = existing.habitLogs.toMutableList()
        val alarms = existing.alarmEvents.toMutableList()
        val activity = existing.activityRecords.toMutableList()
        val reflections = existing.reflections.toMutableMap()

        plan.newHabits.forEach { entry ->
            mergeEntryHistory(entry, checkIns, logs, alarms, activity, reflections)
        }

        // D4: a habit that already existed still contributes its history. This is
        // the half that was unreachable before — the entries had been dropped by
        // `planImport`, so a re-share from a more active device added nothing.
        // Same merge, same id (a duplicate keeps the existing habit's id), so a
        // record already present is a no-op rather than a duplicate.
        plan.duplicateEntryHabits.forEach { entry ->
            mergeEntryHistory(entry, checkIns, logs, alarms, activity, reflections)
        }

        val knownHabitIds = habits.map { it.id }.toSet()
        return existing.copy(
            habits = habits,
            checkIns = checkIns,
            habitLogs = logs,
            alarmEvents = alarms.filter { it.habitId in knownHabitIds },
            activityRecords = activity,
            reflections = reflections,
        )
    }

    /**
     * Merges one imported entry's history into the accumulators, by natural key.
     *
     * Extracted so the "new habit" and "already had this habit" paths cannot
     * merge differently — the whole point of D4 is that the second path used to
     * not merge at all.
     */
    private fun mergeEntryHistory(
        entry: HabitExportEntry,
        checkIns: MutableMap<String, List<String>>,
        logs: MutableList<HabitLogEntry>,
        alarms: MutableList<HabitAlarmEvent>,
        activity: MutableList<ActivityRecord>,
        reflections: MutableMap<String, String>,
    ) {
        val id = entry.habit.id
        val days = checkIns.getOrPut(id) { emptyList() }.toMutableList()
        entry.checkIns.forEach { if (it !in days) days += it }
        checkIns[id] = days.sorted()

        val knownLogIds = logs.map { it.id }.toSet()
        entry.logs.forEach { if (it.id !in knownLogIds) logs += it }

        val knownEventKeys = alarms.filter { it.habitId == id }.map { it.eventKey }.toSet()
        entry.alarmEvents.forEach { if (it.eventKey !in knownEventKeys) alarms += it }

        val knownActivityIds = activity.map { it.id }.toSet()
        entry.activity.forEach { if (it.id !in knownActivityIds) activity += it }

        entry.reflections.forEach { (day, text) -> reflections.putIfAbsent(day, text) }
    }

    /**
     * Two habits are "the same" for import purposes when their identity and
     * content match. Compared on the meaningful fields rather than the whole
     * object so a re-share after an unrelated settings change still dedupes.
     */
    private fun isSameHabit(a: Habit, b: Habit): Boolean =
        a.id == b.id &&
            a.name == b.name &&
            a.alarmTimes == b.alarmTimes &&
            a.repeatDaysMask == b.repeatDaysMask

    /** Prefix marking a habit whose id had to be rewritten to avoid a collision. */
    const val SHARE_IMPORT_ID_PREFIX = "imported-"

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(input.size)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(input: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(input)
        val out = ByteArrayOutputStream(input.size * 4)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && inflater.needsInput()) return null
            out.write(buffer, 0, n)
            // A decompression bomb would otherwise exhaust memory before the
            // JSON parser ever saw it.
            if (out.size() > MAX_DECODED_BYTES) return null
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()
}
