package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.content.SharedPreferences

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

    private const val PREFS = "mindset_habit_timer_requests"
    private const val KEY_HABIT_ID = "pending_habit_id"
    private const val KEY_HABIT_NAME = "pending_habit_name"
    private const val KEY_ICON_ID = "pending_icon_id"
    private const val KEY_MODE = "pending_mode"
    private const val KEY_IS_SPORT = "pending_is_sport"
    private const val KEY_AUTO_SYNC_HABIT_ID = "pending_auto_sync_habit_id"

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
    ) {
        if (habitId.isBlank()) return
        prefs(context).edit()
            .putString(KEY_HABIT_ID, habitId)
            .putString(KEY_HABIT_NAME, habitName)
            .putString(KEY_ICON_ID, iconId)
            .putString(KEY_MODE, mode?.name)
            .putBoolean(KEY_IS_SPORT, isSport)
            .apply()
    }

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
