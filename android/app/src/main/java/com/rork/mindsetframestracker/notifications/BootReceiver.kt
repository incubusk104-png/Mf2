package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Re-schedules the daily check-in reminder and the streak-protection alert
 * after a device reboot or an app update. AlarmManager alarms do not survive
 * either event, so without this receiver both reminders would silently die
 * until the user next opened the app.
 *
 * Reads the persisted app data directly from SharedPreferences (same JSON
 * blob the repository writes) to recover the user's chosen reminder times,
 * and only schedules when onboarding has been completed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val settings = readSettings(context) ?: return
        if (!settings.onboardingDone) return

        val scheduler = NotificationScheduler(context)
        scheduler.scheduleDailyReminder(settings.notificationMinutes)
        if (settings.streakAlertEnabled) {
            scheduler.scheduleStreakAlert(settings.streakAlertMinutes)
        }
        if (settings.weeklyRecapEnabled) {
            scheduler.scheduleWeeklyRecap()
        }
        scheduler.scheduleEveningReflection()
        Log.i(TAG, "Reminders rescheduled after $action")
    }

    private data class ReminderSettings(
        val onboardingDone: Boolean,
        val notificationMinutes: Int,
        val streakAlertEnabled: Boolean,
        val streakAlertMinutes: Int,
        val weeklyRecapEnabled: Boolean,
    )

    private fun readSettings(context: Context): ReminderSettings? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null) ?: return null
            val settings = JSONObject(jsonStr).optJSONObject("settings") ?: return null
            ReminderSettings(
                onboardingDone = settings.optBoolean("onboardingDone", false),
                notificationMinutes = settings.optInt("notificationMinutes", DEFAULT_MINUTES),
                streakAlertEnabled = settings.optBoolean("streakAlertEnabled", true),
                streakAlertMinutes = settings.optInt(
                    "streakAlertMinutes",
                    DEFAULT_STREAK_ALERT_MINUTES,
                ),
                weeklyRecapEnabled = settings.optBoolean("weeklyRecapEnabled", true),
            )
        }.onFailure {
            Log.w(TAG, "Failed to read settings on boot: ${it.message}")
        }.getOrNull()
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val PREFS_NAME = "mindset_frames"
        const val KEY_DATA = "app_data"
        const val DEFAULT_MINUTES = 8 * 60
        const val DEFAULT_STREAK_ALERT_MINUTES = 20 * 60
    }
}
