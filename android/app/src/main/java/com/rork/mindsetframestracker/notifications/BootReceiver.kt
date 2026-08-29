package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.MindsetRepository
import org.json.JSONObject

/**
 * Re-schedules **all** alarms after a device reboot or an app update.
 * AlarmManager alarms do not survive either event, so without this receiver
 * the daily reminder, streak alert, weekly recap, **and every individual
 * habit alarm** would silently die until the user next opened the app.
 *
 * BUG FIX: Previously this receiver only rescheduled the global daily
 * reminder, streak alert, and weekly recap — it **did NOT** reschedule
 * the per-habit alarms created by [HabitAlarmScheduler]. That meant every
 * reboot silently killed every habit-specific notification. Now it loads
 * the full habit list from [MindsetRepository] and calls
 * [HabitAlarmScheduler.rescheduleAll] so every alarm is re-armed.
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

        // ── Global notification alarms (daily check-in, streak, recap) ──
        val scheduler = NotificationScheduler(context)
        scheduler.scheduleDailyReminder(settings.notificationMinutes)
        if (settings.streakAlertEnabled) {
            scheduler.scheduleStreakAlert(settings.streakAlertMinutes)
        }
        if (settings.weeklyRecapEnabled) {
            scheduler.scheduleWeeklyRecap()
        }
        scheduler.scheduleEveningReflection()

        // ── Per-habit alarms (the critical missing piece) ───────────────
        // Load the full persisted habit list and re-arm every individual
        // habit alarm that has a reminderMinutes value. Without this,
        // rebooting or updating the app silently kills all habit reminders.
        rescheduleHabitAlarms(context)

        Log.i(TAG, "All reminders (global + per-habit) rescheduled after $action")
    }

    /**
     * Reads the persisted habit list and re-schedules every habit alarm.
     * Uses [MindsetRepository] for the canonical deserialization path so we
     * don't duplicate JSON parsing logic. Falls back to manual JSON parsing
     * if the repository isn't available (defensive).
     */
    private fun rescheduleHabitAlarms(context: Context) {
        runCatching {
            val repo = MindsetRepository(context)
            val data = repo.load()
            val habitsWithReminders = data.habits.filter { it.reminderMinutes != null }
            if (habitsWithReminders.isNotEmpty()) {
                HabitAlarmScheduler.rescheduleAll(context, habitsWithReminders)
                Log.i(TAG, "Re-armed ${habitsWithReminders.size} individual habit alarm(s)")
            }
        }.onFailure { repoError ->
            Log.w(TAG, "Repository-based reschedule failed, trying manual parse", repoError)
            // Fallback: parse habits directly from SharedPreferences JSON
            rescheduleHabitAlarmsFromJson(context)
        }
    }

    /**
     * Fallback path: reads habit data directly from SharedPreferences JSON
     * when [MindsetRepository] is unavailable (e.g. class-loading issues
     * during early boot on some OEMs).
     */
    private fun rescheduleHabitAlarmsFromJson(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null) ?: return
            val root = JSONObject(jsonStr)
            val habitsArray = root.optJSONArray("habits") ?: return

            var count = 0
            for (i in 0 until habitsArray.length()) {
                val habitJson = habitsArray.optJSONObject(i) ?: continue
                val id = habitJson.optString("id", "").ifEmpty { continue }
                val name = habitJson.optString("name", "").ifEmpty { continue }
                if (!habitJson.has("reminderMinutes") || habitJson.isNull("reminderMinutes")) continue
                val reminderMinutes = habitJson.optInt("reminderMinutes", -1)
                if (reminderMinutes < 0) continue

                val habit = Habit(
                    id = id,
                    name = name,
                    reminderMinutes = reminderMinutes,
                )
                HabitAlarmScheduler.schedule(context, habit)
                count++
            }
            if (count > 0) {
                Log.i(TAG, "Re-armed $count habit alarm(s) via JSON fallback")
            }
        }.onFailure {
            Log.w(TAG, "JSON fallback habit alarm reschedule also failed: ${it.message}")
        }
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
