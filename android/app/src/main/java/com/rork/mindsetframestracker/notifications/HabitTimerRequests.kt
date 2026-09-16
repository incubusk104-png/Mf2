package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The one-shot hand-off from a habit's **alarm** to that habit's own
 * timer/stopwatch options.
 *
 * ## Why a persisted hand-off instead of passing extras around
 *
 * The habit alarm rings in a process that may have no UI at all
 * ([HabitReminderReceiver] -> [HabitCheckInNotifier] ->
 * [AlarmRingingActivity]), and the options sheet belongs to the Habits screen
 * where the habit's icon actually is. Between "the alarm rang" and "the sheet
 * opened" the app can be backgrounded, the ringing screen dismissed, the
 * activity recreated for a configuration change, or the process killed
 * outright. A request held in memory would be lost by any of those; a request
 * in an Intent would be re-delivered on every redelivery of that Intent.
 *
 * So the request is written to disk once and **consumed** the first time the
 * Habits screen reads it. `consume*` is the single place the flag is cleared,
 * which is what makes the sheet "appear once when the alarm rings" hold
 * against re-render, resume, navigation and reboot — exactly the same
 * once-only discipline [com.rork.mindsetframestracker.data.TimerRepository]
 * uses for the completion popup.
 *
 * Two independent flags live here:
 *
 *  - **options** — "open the timer/stopwatch sheet for this habit".
 *  - **autoSync** — "a habit timer just finished; pull its activity data
 *    from the connected app". Kept separate so consuming one never swallows
 *    the other.
 */
object HabitTimerRequests {

    private const val TAG = "HabitTimerRequests"
    private const val PREFS = "mindset_habit_timer_requests"
    private const val KEY_HABIT_ID = "pending_habit_id"
    private const val KEY_HABIT_NAME = "pending_habit_name"
    private const val KEY_ICON_ID = "pending_icon_id"
    private const val KEY_MODE = "pending_mode"
    private const val KEY_IS_SPORT = "pending_is_sport"
    private const val KEY_ALARM_MINUTES = "pending_alarm_minutes"

    /** Sentinel for "this request has no specific alarm time". */
    private const val NO_ALARM_MINUTES = -1
    private const val KEY_AUTO_SYNC_HABIT_ID = "pending_auto_sync_habit_id"

    // ── The minimized sheet's OWN storage ──────────────────────────────────
    // Deliberately separate from the one-shot request keys above rather than a
    // boolean flag on them.
    //
    // The one-shot request is *consumed* the instant the sheet is shown — that is
    // what makes "the sheet appears exactly once per ring" hold against
    // re-render, resume and reboot. A minimized sheet therefore cannot live on
    // that record: by the time the user taps Minimize the request is already
    // gone, so a flag stored alongside it could never be written — and reading
    // it back would return null, silently turning "minimize" into "dismiss".
    // So minimizing SAVES a copy of the request here, and the one-shot record is
    // consumed as normal.
    private const val KEY_MIN_HABIT_ID = "minimized_habit_id"
    private const val KEY_MIN_HABIT_NAME = "minimized_habit_name"
    private const val KEY_MIN_ICON_ID = "minimized_icon_id"
    private const val KEY_MIN_MODE = "minimized_mode"
    private const val KEY_MIN_IS_SPORT = "minimized_is_sport"
    private const val KEY_MIN_ALARM_MINUTES = "minimized_alarm_minutes"

    /** A pending "show me this habit's timer options" request. */
    data class Request(
        val habitId: String,
        val habitName: String,
        val iconId: String?,
        /**
         * The habit's tracking mode at ring time, so the sheet can render the
         * input this habit actually needs rather than guessing from the icon.
         * Null only for a request written by an older build.
         */
        val mode: com.rork.mindsetframestracker.data.HabitTrackingMode? = null,
        /**
         * True when the habit is a physical-movement activity, which is what
         * decides whether the sheet also offers the Strava / Health Connect /
         * Polar sources. Additive only — it never changes whether the sheet
         * opens at all.
         */
        val isSport: Boolean = false,
        /**
         * Which of the habit's alarm times rang, in minutes from midnight.
         *
         * Carried on the request so the sheet's record can be attributed to
         * *this* occurrence rather than merely to the day — see
         * [com.rork.mindsetframestracker.data.HabitLogEntry.occurrenceKey]. Null
         * only for a request written by an older build, in which case the record
         * falls back to a plain day-scoped entry.
         */
        val alarmMinutes: Int? = null,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Records that [habitId]'s alarm rang and its timer/stopwatch options
     * should open. Overwrites any earlier unconsumed request, so the newest
     * ring always wins rather than queueing a backlog of sheets.
     */
    fun request(
        context: Context,
        habitId: String,
        habitName: String,
        iconId: String?,
        mode: com.rork.mindsetframestracker.data.HabitTrackingMode? = null,
        isSport: Boolean = false,
        alarmMinutes: Int? = null,
    ) {
        if (habitId.isBlank()) return
        prefs(context).edit()
            .putString(KEY_HABIT_ID, habitId)
            .putString(KEY_HABIT_NAME, habitName)
            .putString(KEY_ICON_ID, iconId)
            .putString(KEY_MODE, mode?.name)
            .putBoolean(KEY_IS_SPORT, isSport)
            .putInt(KEY_ALARM_MINUTES, alarmMinutes ?: NO_ALARM_MINUTES)
            .apply()
    }

    /**
     * Reads a stored Int that must never be allowed to take the process down.
     *
     * `SharedPreferences.getInt` throws `ClassCastException` when the stored
     * value has a different type. Verified against this repo's history: these
     * two keys have only ever been written with `putInt`, so a build of this
     * app did not create the mismatch. The reachable ways to get one anyway are
     * a restore of a backup written by different code, an OEM storage glitch,
     * or any future edit that writes the key with another type. What makes that
     * worth guarding here specifically is the consequence: these values are read
     * on the launch path after a process kill, the bad value lives on disk, and
     * every subsequent launch re-reads it — so a one-off bad write becomes an
     * app that cannot be opened at all, with no in-app way out.
     *
     * Falling back to the "no alarm time" sentinel degrades that to "the sheet
     * opens without a pre-selected time" — the same behaviour as a habit with
     * no alarm configured. The bad key is dropped so the next `consume` /
     * `minimize` writes a value of the correct type and it cannot recur.
     */
    private fun safeInt(p: SharedPreferences, key: String, fallback: Int): Int =
        runCatching { p.getInt(key, fallback) }
            .onFailure {
                Log.w(TAG, "Stored '$key' was not an Int — resetting it", it)
                p.edit().remove(key).apply()
            }
            .getOrDefault(fallback)

    /** The pending request, without clearing it. */
    fun peek(context: Context): Request? {
        val p = prefs(context)
        val id = p.getString(KEY_HABIT_ID, null)
        if (id.isNullOrBlank()) return null
        return Request(
            habitId = id,
            habitName = p.getString(KEY_HABIT_NAME, null).orEmpty(),
            iconId = p.getString(KEY_ICON_ID, null),
            mode = p.getString(KEY_MODE, null)?.let { name ->
                runCatching {
                    com.rork.mindsetframestracker.data.HabitTrackingMode.valueOf(name)
                }.getOrNull()
            },
            isSport = p.getBoolean(KEY_IS_SPORT, false),
            alarmMinutes = safeInt(p, KEY_ALARM_MINUTES, NO_ALARM_MINUTES)
                .takeIf { it != NO_ALARM_MINUTES },
        )
    }

    /** Clears the pending request. Called the moment the sheet is shown. */
    fun consume(context: Context) {
        prefs(context).edit()
            .remove(KEY_HABIT_ID)
            .remove(KEY_HABIT_NAME)
            .remove(KEY_ICON_ID)
            .remove(KEY_MODE)
            .remove(KEY_IS_SPORT)
            .remove(KEY_ALARM_MINUTES)
            .apply()
    }

    /**
     * Keeps [request] as the sheet the user put away, so the minimized chip can
     * offer it back later.
     *
     * Survives navigation, backgrounding, a configuration change and a process
     * kill because it is on disk, not in Compose state — those are precisely the
     * things that happen between minimizing and coming back, and an in-memory
     * flag would turn "minimize" into "dismiss" for all of them.
     *
     * Overwrites any earlier minimized sheet: there is one chip, and showing two
     * would reintroduce exactly the "which one is running?" confusion this is
     * meant to remove.
     */
    fun minimize(context: Context, request: Request) {
        if (request.habitId.isBlank()) return
        prefs(context).edit()
            .putString(KEY_MIN_HABIT_ID, request.habitId)
            .putString(KEY_MIN_HABIT_NAME, request.habitName)
            .putString(KEY_MIN_ICON_ID, request.iconId)
            .putString(KEY_MIN_MODE, request.mode?.name)
            .putBoolean(KEY_MIN_IS_SPORT, request.isSport)
            .putInt(KEY_MIN_ALARM_MINUTES, request.alarmMinutes ?: NO_ALARM_MINUTES)
            .apply()
    }

    /** The minimized sheet, without clearing it — what the chip renders. */
    fun peekMinimized(context: Context): Request? {
        val p = prefs(context)
        val id = p.getString(KEY_MIN_HABIT_ID, null)
        if (id.isNullOrBlank()) return null
        return Request(
            habitId = id,
            habitName = p.getString(KEY_MIN_HABIT_NAME, null).orEmpty(),
            iconId = p.getString(KEY_MIN_ICON_ID, null),
            mode = p.getString(KEY_MIN_MODE, null)?.let { name ->
                runCatching {
                    com.rork.mindsetframestracker.data.HabitTrackingMode.valueOf(name)
                }.getOrNull()
            },
            isSport = p.getBoolean(KEY_MIN_IS_SPORT, false),
            alarmMinutes = safeInt(p, KEY_MIN_ALARM_MINUTES, NO_ALARM_MINUTES)
                .takeIf { it != NO_ALARM_MINUTES },
        )
    }

    /** True when a sheet is currently minimized and the chip should show it. */
    fun hasMinimized(context: Context): Boolean = peekMinimized(context) != null

    /** Clears the minimized sheet — it was restored, or dismissed. */
    fun clearMinimized(context: Context) {
        prefs(context).edit()
            .remove(KEY_MIN_HABIT_ID)
            .remove(KEY_MIN_HABIT_NAME)
            .remove(KEY_MIN_ICON_ID)
            .remove(KEY_MIN_MODE)
            .remove(KEY_MIN_IS_SPORT)
            .remove(KEY_MIN_ALARM_MINUTES)
            .apply()
    }

    /**
     * Records that a timer/stopwatch for [habitId] finished, so its activity
     * data can be pulled from the connected app on next resume.
     */
    fun requestAutoSync(context: Context, habitId: String) {
        if (habitId.isBlank()) return
        prefs(context).edit().putString(KEY_AUTO_SYNC_HABIT_ID, habitId).apply()
    }

    /** The habit awaiting an activity auto-sync, without clearing it. */
    fun peekAutoSync(context: Context): String? =
        prefs(context).getString(KEY_AUTO_SYNC_HABIT_ID, null)?.takeIf { it.isNotBlank() }

    /** Clears the auto-sync flag. */
    fun consumeAutoSync(context: Context) {
        prefs(context).edit().remove(KEY_AUTO_SYNC_HABIT_ID).apply()
    }
}
