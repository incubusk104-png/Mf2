package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.HabitStore
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.alarmMinutes
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
        // Guarded like every other alarm receiver: a throw out of onReceive
        // kills the process. This one fires during boot (and on app update),
        // when the device is at its busiest — the worst possible moment to take
        // an uncaught exception — and it is the ONLY path that re-arms habit
        // alarms after a reboot, so a crash here leaves every alarm dead until
        // the app is next opened.
        runCatching { rescheduleAll(context, intent) }
            .onFailure { Log.w(TAG, "Boot reschedule failed", it) }
    }

    private fun rescheduleAll(context: Context, intent: Intent) {
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
            // `alarmMinutes` rather than the legacy `reminderMinutes` field: a
            // habit may ring at several times, and every consumer of this list
            // arms one alarm per entry.
            val habitsWithReminders = data.habits.filter { it.alarmMinutes.isNotEmpty() }
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
     * Fallback path: re-arms from the persisted blob **without** going through
     * [MindsetRepository], for the OEMs where class-loading the full repository
     * during early boot fails.
     *
     * ## Why this is no longer hand-rolled
     *
     * This used to parse the JSON itself and rebuild each habit from the fields
     * its author remembered. It carried `repeatDaysMask` across (a previous fix
     * for exactly this class of failure) but still dropped three others:
     *
     *  - **`alarmTimes`** — a habit ringing at 07:00, 12:00 and 18:00 re-armed as
     *    *one* alarm, so two reminders silently disappeared on reboot.
     *  - **`iconId`** — the habit lost its artwork, and with it the curated
     *    motivational pack chosen for it.
     *  - **`alarmMessage`** — the user's own motivational line stopped being
     *    delivered at all.
     *
     * A fallback that reconstructs *part* of a record is worse than no fallback,
     * because nothing signals the degradation: the user simply starts getting a
     * different schedule and different reminder text. [HabitStore] returns a
     * **complete** habit from the same blob, so this path and the primary one
     * re-arm exactly the same thing — and a future field cannot be forgotten
     * here, because there is no longer a field list here to forget.
     */
    private fun rescheduleHabitAlarmsFromJson(context: Context) {
        runCatching {
            val habits = HabitStore.snapshot(context).filter { it.alarmMinutes.isNotEmpty() }
            if (habits.isEmpty()) return
            HabitAlarmScheduler.rescheduleAll(context, habits)
            Log.i(TAG, "Re-armed ${habits.size} habit(s) via JSON fallback")
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
