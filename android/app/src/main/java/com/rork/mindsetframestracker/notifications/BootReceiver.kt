package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.AlarmRingState
import com.rork.mindsetframestracker.data.HabitStore
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.alarmMinutes
import org.json.JSONObject

/**
 * Re-schedules **all** alarms after a device reboot, an app update, or a change
 * to the device clock or timezone.
 *
 * AlarmManager alarms do not survive a reboot or an app update, so without this
 * receiver the daily reminder, streak alert, weekly recap, **and every
 * individual habit alarm** would silently die until the user next opened the
 * app.
 *
 * BUG FIX: this receiver used to reschedule only the global daily reminder,
 * streak alert, and weekly recap — it **did NOT** reschedule the per-habit
 * alarms created by [HabitAlarmScheduler]. That meant every reboot silently
 * killed every habit-specific notification. It now loads the full habit list
 * from [MindsetRepository] and calls [HabitAlarmScheduler.rescheduleAll] so
 * every alarm is re-armed.
 *
 * ## Why `TIME_SET` and `TIMEZONE_CHANGED` are handled here too
 *
 * Every alarm in this app is armed as an **absolute `RTC_WAKEUP` epoch
 * millisecond** ([AlarmScheduler.schedule]). The trigger instant is computed
 * from a *local wall-clock* time (`minutesFromMidnight`, read in the device's
 * current zone) and then frozen into an epoch. Change the clock or move
 * timezone and that frozen epoch is still a perfectly valid instant — it simply
 * no longer corresponds to the local time the user chose:
 *
 *  * **The alarm can be skipped entirely.** [HabitAlarmScheduler.enqueue] asks
 *    [com.rork.mindsetframestracker.data.HabitRepeat] for the next trigger and
 *    that resolver returns `null` for a one-shot whose time has already gone by
 *    — a deliberate "do not arm". Move the clock forward past an alarm's time
 *    and a re-arm pass computes a trigger in the past and arms nothing.
 *  * **Or it fires at the wrong hour.** Fly from UTC+8 to UTC-5 and an alarm
 *    left on the old epoch rings at 20:00 local instead of 07:00.
 *
 * Re-running the whole reschedule recomputes every epoch from the **new** zone,
 * which is what the platform's own alarm clock does. There was previously no
 * time/timezone handling anywhere in the app, so this was a silent
 * mis-fire/drop. [MindsetFramesApplication] also registers this receiver
 * *dynamically* for the running case, where a manifest broadcast is not always
 * delivered — the two paths are idempotent with each other because every arm is
 * keyed `(habit, time)` under `FLAG_UPDATE_CURRENT`, so a second pass replaces
 * an entry rather than adding a duplicate alarm.
 *
 * A clock change also **reopens the ring gate** ([AlarmRingState]): that gate
 * suppresses a dialog the user has already been shown for today's `(day, time)`
 * occurrence, and after the clock moves it is describing a day that may no
 * longer be today's. Clearing it costs at most one extra dialog and is what
 * stops a stale acknowledgement from muting an alarm that legitimately rings
 * again under the new clock.
 *
 * ## These four are all system broadcasts
 *
 * `ACTION_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`, `ACTION_TIME_CHANGED`
 * and `ACTION_TIMEZONE_CHANGED` are system/protected actions, so the manifest's
 * `exported="false"` is correct and does not stop them arriving: a non-exported
 * *manifest* receiver still receives system broadcasts, and a *dynamic*
 * registration is delivered from anywhere in the system. Nothing here is
 * reachable by another app.
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
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_TIME_CHANGED &&
            action != ACTION_TIMEZONE_CHANGED
        ) {
            return
        }

        // A clock/timezone change invalidates today's "already delivered"
        // acknowledgement, because the (day, time) it names was resolved under
        // the OLD clock. See the class note for why this is safe.
        val isClockChange = action == ACTION_TIME_CHANGED || action == ACTION_TIMEZONE_CHANGED
        if (isClockChange) {
            runCatching { AlarmRingState.clear(context) }
                .onFailure { Log.w(TAG, "Could not reset the ring gate after $action", it) }
        }

        val settings = readSettings(context)
        if (settings == null || !settings.onboardingDone) {
            // Onboarding not finished means there is nothing to re-arm — except
            // after a clock change.
            //
            // The alarm list is read from the habit store independently of
            // `settings` (a habit alarm can exist — and ring — while the
            // onboarding flag is in any state), so bailing out here would leave
            // those alarms frozen on an epoch computed in the old zone. The boot
            // and update paths keep the old behaviour of returning, since a fresh
            // install genuinely has nothing to arm yet.
            if (isClockChange) {
                rescheduleHabitAlarms(context)
                Log.i(TAG, "Re-armed habit alarms after $action (onboarding flag unset)")
            }
            return
        }

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
        // rebooting, updating the app, or changing the clock silently kills
        // or mis-times all habit reminders.
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

        /**
         * `android.intent.action.TIME_SET` — the device clock was changed (by
         * the user, by the network, or by an OEM sync).
         *
         * Spelled out as a literal rather than `Intent.ACTION_TIME_CHANGED`:
         * that constant has moved between public and system-visibility across
         * SDK releases, and this file must compile on every toolchain that
         * builds the app. The string is the contract the manifest filter uses,
         * and the platform sends exactly this action.
         */
        const val ACTION_TIME_CHANGED = "android.intent.action.TIME_SET"

        /**
         * `android.intent.action.TIMEZONE_CHANGED` — the device's timezone was
         * changed, e.g. the user flew across one. Same literal-over-constant
         * reasoning as [ACTION_TIME_CHANGED]; this one also carries the new
         * zone id in `Intent.EXTRA_TIMEZONE`, which the platform has already
         * applied by the time it is delivered, so nothing needs to read it.
         */
        const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
    }
}
