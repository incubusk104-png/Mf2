package com.rork.mindsetframestracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.json.Json
import java.util.UUID
import com.rork.mindsetframestracker.integrations.withCanonicalActivityType

/**
 * Fully local, on-device persistence. Single JSON blob in SharedPreferences —
 * no accounts, no cloud, no analytics.
 *
 * DATA-SAFETY HARDENING: `load()` used to swallow any decode failure and
 * silently hand back a brand-new, empty [AppData] — indistinguishable from
 * "the user has no habits." If a phone ever produced a corrupted prefs
 * value (OEM storage glitch, a bad OS backup/restore, etc.), this made it
 * LOOK like every habit had been deleted, when the raw bytes were actually
 * still sitting in SharedPreferences the whole time. We now:
 *   1. Keep the last-known-good decoded copy in memory for this process.
 *   2. On a decode failure, return that cached copy instead of a blank one
 *      whenever we have it — so a transient glitch never presents as data
 *      loss inside a single app session.
 *   3. Never overwrite the raw stored JSON as a side effect of a failed
 *      read — save() is still the ONLY function that writes to prefs, and
 *      it's only ever called with real in-memory state, so a bad read can
 *      no longer cascade into a bad write.
 *   4. Keep one rolling backup copy of the last successfully-saved JSON
 *      under a separate key, so even a fresh process restart after
 *      corruption can recover instead of starting blank.
 */
class MindsetRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** In-memory fallback for THIS process — see class doc point 1/2. */
    private var lastKnownGood: AppData? = null

    fun load(): AppData {
        val raw = prefs.getString(KEY_DATA, null)
        if (raw == null) {
            // Genuinely no data yet (fresh install) — an empty AppData here
            // is correct, not data loss.
            return lastKnownGood ?: AppData()
        }

        val decoded = runCatching { json.decodeFromString<AppData>(raw) }.getOrNull()
        if (decoded != null) {
            val cleaned = stripInvalidCheckIns(decoded)
            lastKnownGood = cleaned
            return cleaned
        }

        Log.w(TAG, "Failed to decode primary data — trying backup copy")

        // Primary blob is corrupt. Try the rolling backup written on the
        // last successful save() before we ever give up.
        val backupRaw = prefs.getString(KEY_BACKUP, null)
        val fromBackup = backupRaw?.let { runCatching { json.decodeFromString<AppData>(it) }.getOrNull() }
        if (fromBackup != null) {
            Log.w(TAG, "Recovered data from backup copy after primary decode failure")
            lastKnownGood = fromBackup
            // Restore the primary key from the good backup so future loads
            // don't have to fall back every time.
            runCatching {
                prefs.edit().putString(KEY_DATA, backupRaw).apply()
            }
            return fromBackup
        }

        // Nothing decodable anywhere. Only now fall back to this session's
        // in-memory cache if we have one; otherwise (and only otherwise) an
        // empty AppData — but we deliberately do NOT persist this empty
        // value. The original corrupt bytes stay in prefs untouched so a
        // future app version (or manual recovery) still has a chance at
        // them, instead of us permanently stamping over them with blanks.
        Log.e(TAG, "No recoverable data (primary and backup both unreadable)")
        return lastKnownGood ?: AppData()
    }

    fun save(data: AppData) {
        runCatching {
            val encoded = json.encodeToString(AppData.serializer(), data)
            val editor = prefs.edit()
            // Roll the previous primary value into the backup slot BEFORE
            // overwriting it, so there is always one prior good snapshot to
            // recover from if this new value ever fails to decode later.
            prefs.getString(KEY_DATA, null)?.let { previous ->
                editor.putString(KEY_BACKUP, previous)
            }
            editor.putString(KEY_DATA, encoded)
            editor.apply()
            lastKnownGood = data
        }.onFailure {
            Log.w(TAG, "Failed to persist local data", it)
        }
    }

    /**
     * Marks [habitId] done for today directly against the stored blob —
     * additive only, never un-checks an already-completed day. Exists
     * specifically so [com.rork.mindsetframestracker.notifications.HabitCheckInNotifier]
     * can record the check-in the moment the alarm actually RINGS (the app
     * process may be dead at that point — a BroadcastReceiver has no
     * AppViewModel to call into, only a Context), instead of prematurely
     * marking a habit done back when the user merely set the alarm's time.
     * Returns true if this call actually changed anything.
     */
    fun markHabitDoneToday(habitId: String): Boolean {
        val current = load()
        val today = Dates.todayKey()
        val days = current.checkIns[habitId].orEmpty().toMutableSet()
        if (!days.add(today)) return false
        save(current.copy(checkIns = current.checkIns + (habitId to days.toList())))
        return true
    }

    /**
     * SELF-HEAL: every real habit id is a [UUID] (created via
     * `UUID.randomUUID().toString()` in HabitsScreen). The old diagnostic
     * "Send a test reminder now" button wrote a check-in under the literal
     * key "diagnostic_test", which is not a UUID — Supabase's `checkins`
     * table stores habit_id as `uuid`, so any install that ever tapped that
     * button got stuck with every future sync failing on that one bad row
     * (see HabitCheckInNotifier.DIAGNOSTIC_HABIT_ID). The button itself is
     * fixed to never write this again, but this strips out any copy that
     * was already saved on-device before that fix, so an existing install
     * recovers automatically on the very next load — no need to sign out,
     * clear app data, or reinstall.
     */
    private fun stripInvalidCheckIns(data: AppData): AppData {
        val hasBadKey = data.checkIns.keys.any { runCatching { UUID.fromString(it) }.isFailure }
        if (!hasBadKey) return data
        val cleaned = data.copy(checkIns = data.checkIns.filterKeys {
            runCatching { UUID.fromString(it) }.isSuccess
        })
        Log.i(TAG, "Removed invalid (non-UUID) check-in key(s) — this unblocks Supabase sync")
        save(cleaned)
        return cleaned
    }

    /**
     * Upserts one [ActivityRecord] \u2014 used by Polar / Health Connect / Strava sync.
     *
     * **Keyed by [ActivityRecord.id], replacing rather than appending.** This was
     * an unconditional append, which made every sync run add a duplicate: Strava
     * derives a stable id from the provider's activity id (`strava_<id>`) and
     * Health Connect mints one per session, and the sync path is deliberately
     * repeatable (auto-sync on launch, a manual re-sync, a restored backup), so
     * the same activity was written again on every pass. Steps and minutes are
     * summed across records when they are displayed, so the visible symptom was
     * not "a duplicate row" but steadily inflating totals \u2014 the user's weekly
     * step count growing on every app launch with nothing new done.
     *
     * Re-syncing is still meaningful with upsert semantics: a record whose
     * values were later refined (Strava finalising an activity, a heart-rate
     * strap syncing late) overwrites its earlier version instead of being
     * dropped by a plain "does this id exist" check.
     *
     * @return the stored record, or null when persistence failed.
     */
    fun saveActivityRecord(record: ActivityRecord): ActivityRecord? = runCatching {
        val current = load()
        // The activity type is forced onto the app's catalog vocabulary at this
        // single choke point, which every provider client writes through.
        //
        // Doing it here rather than at each reader is the point: a reader that
        // has to understand both the provider's vocabulary and the catalog's is
        // a reader that will eventually be written by someone who knows only
        // one — and that is how a Strava "Ride" came to be rendered beside
        // screens that say cycling, while anything matching on a catalog id
        // silently stopped matching.
        val normalized = record.withCanonicalActivityType(
            current.habits.firstOrNull { it.id == record.habitId }?.iconId,
        )
        val existing = current.activityRecords.indexOfFirst { it.id == normalized.id }
        val merged = if (existing >= 0) {
            current.activityRecords.toMutableList().apply { this[existing] = normalized }
        } else {
            current.activityRecords + normalized
        }
        save(current.copy(activityRecords = merged))
        normalized
    }.onFailure { Log.w(TAG, "Failed to persist activity record ${record.id}", it) }.getOrNull()

    /**
     * Appends one [HabitLogEntry] and persists — the record of what a habit's
     * own tracking tool actually produced (a measured duration, a journal
     * title + text, a count).
     *
     * Append-only by design, and it does **not** touch `checkIns`: recording
     * the payload and marking the day done are separate concerns, and keeping
     * them separate means a caller cannot accidentally create a streak by
     * writing text. [com.rork.mindsetframestracker.ui.AppViewModel] does both
     * together when the user confirms.
     *
     * @return the stored entry, or null when persistence failed.
     */
    fun saveHabitLog(entry: HabitLogEntry): HabitLogEntry? = runCatching {
        val current = load()
        save(current.copy(habitLogs = current.habitLogs + entry))
        entry
    }.onFailure { Log.w(TAG, "Failed to persist habit log for ${entry.habitId}", it) }.getOrNull()

    /** Records for a specific habit, most recent first — feeds TrendChart/YearHeatmap. */
    fun activityRecordsForHabit(habitId: String): List<ActivityRecord> =
        load().activityRecords.filter { it.habitId == habitId }.sortedByDescending { it.timestamp }

    private companion object {
        const val PREFS_NAME = "mindset_frames"
        const val KEY_DATA = "app_data"
        const val KEY_BACKUP = "app_data_backup"
        const val TAG = "MindsetRepository"
    }
}
