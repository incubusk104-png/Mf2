package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.util.Log
import com.rork.mindsetframestracker.data.AppLanguage
import com.rork.mindsetframestracker.data.DEFAULT_LANGUAGE
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.stringsFor
import com.rork.mindsetframestracker.util.LocalizationManager
import org.json.JSONObject

/**
 * Resolves the app's active string table from a background context
 * (BroadcastReceiver / AlarmManager), so every notification speaks the
 * user's chosen language.
 *
 * Reads the persisted app-data JSON to find the saved [AppLanguage] and
 * returns the merged string table for it (missing keys fall back to
 * English automatically via [stringsFor]).
 */
object NotificationStrings {

    private const val TAG = "NotificationStrings"
    private const val PREFS_NAME = "mindset_frames"
    private const val KEY_DATA = "app_data"

    fun resolve(context: Context): AppStrings {
        LocalizationManager.init(context)
        val language = runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null)
                ?: return@runCatching DEFAULT_LANGUAGE
            val name = JSONObject(jsonStr)
                .optJSONObject("settings")
                ?.optString("language")
                .orEmpty()
            AppLanguage.entries.firstOrNull { it.name == name } ?: DEFAULT_LANGUAGE
        }.onFailure {
            Log.w(TAG, "Failed to read language: ${it.message}")
        }.getOrDefault(DEFAULT_LANGUAGE)
        return stringsFor(language)
    }
}
