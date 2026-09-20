package com.rork.mindsetframestracker.data

import android.content.Context
import android.util.Log

/**
 * Remembers which habits have **already been shown** the ring-time "connect a
 * fitness app?" offer.
 *
 * ## Why this has to be persisted rather than held in the dialog
 *
 * The offer is raised by [com.rork.mindsetframestracker.ui.navigation]'s ring
 * host the moment a habit's alarm reaches the user — which may be with the app
 * backgrounded, with the process freshly cold-started from the alarm, or while
 * the user is mid-way through something else. Compose state cannot survive any
 * of those, so an in-memory "already asked" flag would reset on every ring: a
 * habit set for 07:00/12:00/18:00 would ask the same question three times a day,
 * and the offer would read as nagging rather than as a moment of help.
 *
 * ## Why "asked", not "connected"
 *
 * The two are deliberately different. The question "is anything connected yet?"
 * is answered by [com.rork.mindsetframestracker.integrations.TrackerConnections]
 * from the stored credentials. This answers the *other* half: **has the user
 * already been given this choice for this habit?** A user who tapped "Not now"
 * on Monday's walk should not be asked again on Tuesday's — they answered. A
 * user who connected Strava should never see the offer again either, and that
 * falls out of the same record because connecting is itself an answer.
 *
 * Keeping them separate is what lets the dialog policy be stated as one line:
 * show the offer when a provider can supply the habit **and** nothing is
 * connected **and** we have not asked this habit before.
 *
 * ## Why the record is per habit
 *
 * Connecting a tracker is meaningful only relative to a habit — the same fact
 * the connect sheet's required `habitLabel` encodes. A user who was asked about
 * their walk and declined has said nothing about their gym habit, which may well
 * be the one they *do* record on Strava. A single global "asked once" flag would
 * suppress the offer on every habit after the first, which is how a helpful
 * prompt becomes a feature nobody ever sees.
 *
 * ## Failure behaviour
 *
 * Every read and write is guarded. This runs on the **ring path** — a throw here
 * would take down the process at the exact moment the user's alarm is supposed
 * to ring, which is strictly worse than losing the record. A failed read yields
 * the empty set, which at worst re-offers the prompt once.
 */
object AlarmConnectPrompt {

    private const val TAG = "AlarmConnectPrompt"
    private const val PREFS = "mindset_alarm_connect_prompt"
    private const val KEY_ASKED = "asked_habit_ids"

    /**
     * Separator for the stored id list.
     *
     * A newline rather than a comma because habit ids are generated strings that
     * a future change could plausibly make comma-bearing, and a separator that
     * can appear inside a value is how one id silently splits into two.
     */
    private const val SEPARATOR = "\n"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every habit id the offer has been shown for. */
    fun askedHabitIds(context: Context): Set<String> = readRaw(context).toSet()

    /** True when this habit has already been offered the connection. */
    fun hasAsked(context: Context, habitId: String): Boolean =
        habitId.isNotBlank() && habitId in readRaw(context)

    /**
     * Records that the offer has now been shown for [habitId].
     *
     * Called when the dialog is *raised*, not when it is answered. The offer is
     * a one-time choice: whether the user connects, or taps "Not now", or
     * dismisses the dialog by any other means, they have seen it, and re-asking
     * is what makes a prompt obnoxious. Idempotent, so a ring that is replayed
     * cannot duplicate the entry.
     */
    fun markAsked(context: Context, habitId: String) {
        if (habitId.isBlank()) return
        val current = readRaw(context)
        if (habitId in current) return
        writeRaw(context, current + habitId)
    }

    /**
     * Forgets one habit, so the offer may be made again.
     *
     * Used when the user explicitly asks to be shown how a habit could be
     * tracked — the "ask me again" escape hatch that keeps a one-time prompt
     * from becoming a permanently unavailable feature.
     */
    fun forget(context: Context, habitId: String) {
        val current = readRaw(context)
        if (habitId !in current) return
        writeRaw(context, current - habitId)
    }

    /** Clears every record — sign-out, or a full data reset. */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_ASKED).apply() }
            .onFailure { Log.w(TAG, "Could not clear the ring-time connect prompt record", it) }
    }

    private fun readRaw(context: Context): List<String> =
        runCatching {
            prefs(context).getString(KEY_ASKED, null)
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }
            // A stored value of an unexpected type would throw a
            // ClassCastException here, on the ring path. Degrading to "nothing
            // asked yet" costs one extra prompt; throwing costs the ring.
            .onFailure { Log.w(TAG, "Could not read the ring-time connect prompt record", it) }
            .getOrDefault(emptyList())

    private fun writeRaw(context: Context, ids: List<String>) {
        val value = ids.distinct().filter { it.isNotBlank() }.joinToString(SEPARATOR)
        runCatching { prefs(context).edit().putString(KEY_ASKED, value).apply() }
            .onFailure { Log.w(TAG, "Could not record the ring-time connect prompt", it) }
    }
}
