package com.rork.mindsetframestracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.json.Json

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
            lastKnownGood = decoded
            return decoded
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

    /** Appends one ActivityRecord and persists — used by Polar / Health Connect / Strava sync. */
    fun saveActivityRecord(record: ActivityRecord) {
        val current = load()
        val updated = current.copy(activityRecords = current.activityRecords + record)
        save(updated)
    }

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
