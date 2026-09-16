package com.rork.mindsetframestracker.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * The **one** reader of habit rows straight out of the persisted app-data blob.
 *
 * ## Why this exists
 *
 * Two callers need a habit while no UI is running, and both used to hand-walk
 * `SharedPreferences → JSONObject → habits[]` **independently**:
 *
 *  - [com.rork.mindsetframestracker.notifications.BootReceiver], to re-arm every
 *    alarm after a reboot or an app update.
 *  - [com.rork.mindsetframestracker.notifications.HabitReminderText], to resolve
 *    the exact sentence a reminder shows at ring time.
 *
 * Each of them named the serialized fields as string literals, so a **renamed
 * field silently broke both** — and, worse, the boot path reconstructed a
 * [Habit] from only the fields its author happened to remember. That is how
 * `alarm_times`, `icon_id` and `alarm_message` were each dropped in turn: a
 * habit with 07:00/12:00/18:00 re-armed as a *single* alarm after a reboot, lost
 * its artwork, and stopped saying the user's own motivational line — with nothing
 * signalling that it had happened.
 *
 * There is now exactly one place that knows these field names, and it returns a
 * **complete** [Habit]. A future field addition is a compile-time change here
 * rather than a silently-forgotten one at a call site.
 *
 * ## Why not `Json.decodeFromString<AppData>`
 *
 * This code runs inside alarm `BroadcastReceiver`s, where a deserialization
 * exception escaping would kill the process at the exact moment the user's alarm
 * is supposed to ring. A hand-walked [JSONObject] lookup cannot throw for any
 * reason other than the blob being absent, and every failure below is swallowed
 * into "no habits", never into "the app crashed instead of ringing".
 * [MindsetRepository] remains the *canonical* path for callers that can afford
 * it (see [BootReceiver]) — this is the durable, throw-proof one.
 */
object HabitStore {

    private const val TAG = "HabitStore"
    private const val PREFS_NAME = "mindset_frames"
    private const val KEY_DATA = "app_data"

    /**
     * Every habit in the persisted blob, fully populated, in stored order.
     *
     * Never throws and never returns null: an absent blob, invalid JSON or a
     * missing `habits` array all yield an empty list, which callers treat as
     * "nothing to do" rather than as an error to report.
     */
    fun snapshot(context: Context): List<Habit> = runCatching {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DATA, null) ?: return@runCatching emptyList()
        val habits = JSONObject(raw).optJSONArray("habits") ?: return@runCatching emptyList()

        (0 until habits.length()).mapNotNull { index ->
            habits.optJSONObject(index)?.let(::readHabit)
        }
    }.onFailure {
        Log.w(TAG, "Could not read the persisted habit list", it)
    }.getOrDefault(emptyList())

    /**
     * The single habit with [habitId], or null when it is not in the blob.
     *
     * The convenience form for the alarm path, which always knows which habit
     * it is ringing for. Reads the whole list because that is what the blob is —
     * a habit cannot be located without walking the array — but returns one
     * populated [Habit], so the caller never re-derives a single field (and
     * never forgets one).
     */
    fun find(context: Context, habitId: String): Habit? =
        snapshot(context).firstOrNull { it.id == habitId }

    /**
     * One habit from its own JSON object, or null when it is unusable.
     *
     * Every field the reminder path depends on is read here — identity, name,
     * **the whole alarm schedule**, the repeat mask, the artwork, and the user's
     * motivational line. Reading `reminderMinutes` without `alarmTimes` is
     * precisely the bug this object exists to prevent, so the two are read
     * together and reconciled by [legacyAlarmTimes].
     */
    private fun readHabit(habitJson: JSONObject): Habit? {
        val id = habitJson.optString("id", "").ifEmpty { return null }
        val name = habitJson.optString("name", "").ifEmpty { return null }

        val reminderMinutes = habitJson
            .optIntOrNull("reminderMinutes")
            ?.takeIf { it in 0..1439 }

        val alarmTimes = runCatching {
            val array = habitJson.optJSONArray("alarmTimes") ?: return@runCatching emptyList()
            (0 until array.length()).map { array.optInt(it, -1) }
        }.getOrDefault(emptyList())

        return Habit(
            id = id,
            name = name,
            createdAt = habitJson.optLong("createdAt", 0L),
            isPinned = habitJson.optBoolean("isPinned", false),
            reminderMinutes = reminderMinutes,
            // Shared with the sync layer and the scheduler, so all three
            // readers agree on what a legacy habit's schedule is.
            alarmTimes = legacyAlarmTimes(alarmTimes, reminderMinutes),
            durationSeconds = habitJson.optIntOrNull("durationSeconds"),
            iconId = habitJson.optStringOrNull("iconId"),
            repeatDaysMask = habitJson.optInt("repeatDaysMask", REPEAT_DAILY),
            monitoredPackage = habitJson.optStringOrNull("monitoredPackage"),
            screenTimeLimitMinutes = habitJson.optIntOrNull("screenTimeLimitMinutes"),
            monitoredAppLabel = habitJson.optStringOrNull("monitoredAppLabel"),
            // Sanitised on read as well as on write. A message can reach the
            // store without passing through the editor — a restored cloud row,
            // or a blob written by a build that predates the writer-side
            // sanitiser — and this value ends up as notification text.
            alarmMessage = MotivationalMessages.sanitize(habitJson.optString("alarmMessage"))
                .takeIf { it.isNotEmpty() },
        )
    }

    /**
     * `optInt` cannot tell "absent" from "explicitly 0", and for a nullable
     * field those are different answers: `durationSeconds` absent means "simple
     * checkbox habit", whereas 0 would mean "a timer of zero seconds". The
     * `isNull` test covers the JSON `null` the serializer writes for a null
     * value; a missing key is covered by `has`.
     */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key, 0)

    /** `optString` returns the literal `"null"` for a JSON null — never wanted here. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
