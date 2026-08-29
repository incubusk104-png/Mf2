package com.rork.mindsetframestracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Fully local, on-device persistence. Single JSON blob in SharedPreferences —
 * no accounts, no cloud, no analytics.
 */
class MindsetRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppData =
        runCatching {
            prefs.getString(KEY_DATA, null)?.let { json.decodeFromString<AppData>(it) }
        }.onFailure {
            Log.w(TAG, "Failed to load local data, starting fresh")
        }.getOrNull() ?: AppData()

    fun save(data: AppData) {
        runCatching {
            prefs.edit().putString(KEY_DATA, json.encodeToString(AppData.serializer(), data)).apply()
        }.onFailure {
            Log.w(TAG, "Failed to persist local data")
        }
    }

    /** Appends one ActivityRecord and persists — used by Fitbit / Polar / Health Connect / Strava sync. */
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
        const val TAG = "MindsetRepository"
    }
}
