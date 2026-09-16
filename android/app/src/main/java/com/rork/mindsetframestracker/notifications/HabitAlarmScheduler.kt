package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.REPEAT_DAILY
import com.rork.mindsetframestracker.data.REPEAT_ONCE
import com.rork.mindsetframestracker.data.alarmMinutes
import java.util.Calendar

/**
 * Schedules, cancels, and reschedules per-habit reminders through the shared
 * [AlarmScheduler] — the same mechanism [NotificationScheduler]
 * already uses for the daily check-in, streak alert, and evening reflection.
 *
 * ## Why this is AlarmManager again, not WorkManager
 *
 * A previous revision moved per-habit reminders to WorkManager's
 * `setInitialDelay()` to sidestep the exact-alarm permission entirely. That
 * traded a *rare* failure (a user denying the exact-alarm permission) for a
 * *routine* one: `setInitialDelay()` only sets a **minimum** delay — Android's
 * Doze / App Standby batching is free to defer the job well past that once the
 * delay spans idle, screen-off time, which "timer at 8:45 PM" always does. That
 * is exactly the reported symptom: 8:45 PM comes and goes with no sound and no
 * vibration, because the job hadn't run yet.
 *
 * The `allowWhileIdle` exact alarm [AlarmScheduler] arms is the one Android
 * primitive that's explicitly exempt from Doze/App-Standby deferral, and it
 * does **not** hand the alarm to the system Clock app (the old
 * `setAlarmClock()` arming did — that is why the ring used to read as the
 * phone's personal clock app). On API 31+ we check [AlarmManager.canScheduleExactAlarms] first and fall back to a
 * 15-minute [AlarmManager.setWindow] the same way [NotificationScheduler]
 * already does, so the app never crashes and a reminder still fires close to
 * on time even without the permission.
 *
 * ## Many alarms per habit — one AlarmManager entry each
 *
 * A habit may now ring at several times of day (`Habit.alarmMinutes`: 07:00,
 * 12:00, 18:00), so this is no longer one alarm per habit. Each time gets its
 * **own** pending intent and its **own** request code, because that is the only
 * way they can fire independently: `AlarmManager` identifies an alarm by
 * `(requestCode, intent)`, so arming 12:00 under the same request code as 07:00
 * would silently *replace* the morning alarm instead of adding an evening one.
 *
 * The request code is therefore [requestCode] of `(habitId, minutes)` rather
 * than the habit's hash alone. That change is also why [LEGACY_REQUEST_CODE] is
 * cancelled on every schedule: an install upgrading from the single-alarm build
 * still has an alarm armed under the old code, and arming the new one without
 * clearing the old would leave **two** live entries for the same time — the user
 * would get each reminder twice.
 *
 * Day-of-week still comes from the single `Habit.repeatDaysMask`, shared by
 * every time in the list: "this habit rings at these times on these days" is the
 * user's mental model, and a per-time mask would mean the repeat row silently
 * applied only to whichever time happened to be selected.
 */
object HabitAlarmScheduler {

    private const val TAG = "HabitAlarmScheduler"
    private const val WINDOW_MILLIS = 15L * 60L * 1000L
    const val ACTION_HABIT_REMINDER = "com.rork.mindsetframestracker.HABIT_REMINDER"

    /**
     * The request code the single-alarm build used for a habit's only alarm.
     *
     * Kept purely so it can be **cancelled**: `requestCode()` below is derived
     * from `(habitId, minutes)` and can never equal this, so without an explicit
     * legacy cancel an upgraded install keeps its old alarm armed alongside the
     * new one and rings twice for the same reminder.
     */
    private fun legacyRequestCode(habitId: String) = habitId.hashCode()

    /** Distinct per (habit, time) — the identity `AlarmManager` dedupes on. */
    private fun requestCode(habitId: String, minutes: Int): Int =
        habitId.hashCode() * 31 + minutes

    /**
     * The public form of [requestCode], for callers outside this object that
     * need to share or offset an alarm's identity.
     *
     * [HabitSnoozeReceiver] uses it so a snooze is keyed to the same
     * `(habit, time)` as the alarm it snoozes. That is what stops a snooze from
     * being handed the pending intent of the habit's *other* alarm time and
     * quietly replacing it — which is what the old `habitId.hashCode() + 1`
     * derivation did, since it collided with the second time's code.
     */
    fun requestCodeFor(habitId: String, minutes: Int): Int =
        if (minutes == HabitReminderReceiver.NO_ALARM_MINUTES) legacyRequestCode(habitId)
        else requestCode(habitId, minutes)

    /** Arms every alarm time this habit has (no-op when it has none). */
    fun schedule(context: Context, habit: Habit) {
        // Clear the pre-multi-alarm entry first, so an upgrade cannot leave a
        // duplicate live for the same time.
        cancelLegacy(context, habit.id)
        habit.alarmMinutes.forEach { minutes ->
            enqueue(context, habit.id, habit.name, minutes, habit.repeatDaysMask)
        }
    }

    /**
     * Cancels **every** alarm this habit has, at every time.
     *
     * All times are cancelled rather than a caller-supplied one because every
     * caller that cancels is expressing "this habit should no longer ring"
     * (removed, alarm switched off) — and a cancel that only cleared one of
     * three times would leave the habit ringing twice more after the user
     * switched it off.
     */
    fun cancel(context: Context, habit: Habit) {
        cancelLegacy(context, habit.id)
        val minutesList = habit.alarmMinutes.ifEmpty { listOfNotNull(habit.reminderMinutes) }
        minutesList.forEach { minutes -> cancelAt(context, habit.id, habit.name, minutes) }
        Log.d(TAG, "Cancelled ${minutesList.size} reminder(s) for '${habit.name}'")
    }

    /**
     * Cancels a habit's reminder at one specific time.
     *
     * Used by the ring's "Stop alarm" path, where cancelling *all* times would
     * be wrong: the user is stopping the 07:00 alarm, and the 12:00 and 18:00
     * ones are still wanted.
     */
    fun cancelAt(context: Context, habitId: String, habitName: String, minutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(habitId, minutes),
            reminderIntent(context, habitId, habitName, minutes),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            pendingIntent.cancel()
        }
        Log.d(TAG, "Cancelled reminder for '$habitName' at ${minutes / 60}:${minutes % 60}")
    }

    private fun cancelLegacy(context: Context, habitId: String) {
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                legacyRequestCode(habitId),
                reminderIntent(context, habitId, "", habitId.hashCode() % 1440),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }.onFailure { Log.w(TAG, "Could not clear the legacy alarm for habit $habitId", it) }
    }

    /** Arms every alarm time for every habit that has one. */
    fun rescheduleAll(context: Context, habits: List<Habit>) {
        var count = 0
        habits.forEach { habit ->
            if (habit.alarmMinutes.isNotEmpty()) {
                schedule(context, habit)
                count += habit.alarmMinutes.size
            }
        }
        Log.i(TAG, "Rescheduled $count habit reminder(s)")
    }

    /**
     * Called by [HabitReminderReceiver] right after a specific time fired, to
     * re-arm **that time's** next occurrence.
     *
     * Per-time rather than per-habit on purpose: when the 07:00 alarm fires, the
     * 12:00 and 18:00 alarms are separate entries that are still correctly armed
     * and must not be touched — re-arming the whole habit here would push the
     * later ones a day forward and the user would silently lose their afternoon
     * and evening reminders.
     *
     * A repeat mask of [REPEAT_ONCE] means it was a one-shot reminder — it is NOT
     * re-armed (mirrors the system Clock's "Repeat: Once" behaviour).
     */
    fun scheduleNext(context: Context, habitId: String, habitName: String, firedAlarmMinutes: Int) {
        val repo = MindsetRepository(context)
        val habit = repo.load().habits.find { it.id == habitId } ?: return
        // The time may have been edited or removed since it fired; in that case
        // there is nothing to re-arm, and re-arming the *new* times is the
        // editor's job (it calls schedule()).
        if (habit.alarmMinutes.none { it == firedAlarmMinutes }) return
        if (habit.repeatDaysMask == REPEAT_ONCE) {
            Log.d(TAG, "'${habit.name}' repeats Once — not re-arming")
            return
        }
        enqueue(context, habitId, habitName, firedAlarmMinutes, habit.repeatDaysMask)
    }

    private fun enqueue(
        context: Context,
        habitId: String,
        habitName: String,
        minutes: Int,
        repeatDaysMask: Int = REPEAT_DAILY,
    ) {
        val triggerAtMillis = nextTriggerMillis(minutes, repeatDaysMask)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(habitId, minutes),
            reminderIntent(context, habitId, habitName, minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canUseExact = AlarmScheduler.canScheduleExact(context)

        AlarmScheduler.schedule(
            context = context,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            wakeUp = true,
            allowWhileIdle = true,
        )
        Log.d(
            TAG,
            "Reminder for '$habitName' at ${minutes / 60}:${minutes % 60} scheduled (exact=$canUseExact) at $triggerAtMillis",
        )
    }

    private fun reminderIntent(
        context: Context,
        habitId: String,
        habitName: String,
        alarmMinutes: Int,
    ): Intent =
        Intent(context, HabitReminderReceiver::class.java).apply {
            action = ACTION_HABIT_REMINDER
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_NAME, habitName)
            // Which of the habit's alarm times this is. Carried on the intent so
            // the record written at ring time can be attributed to *this*
            // occurrence rather than merely to the day — the difference between
            // "I walked today" and "I walked at 07:00 and again at 18:00".
            putExtra(HabitReminderReceiver.EXTRA_ALARM_MINUTES, alarmMinutes)
        }

    /**
     * Next trigger time honouring the repeat day mask (bit 0 = Monday …
     * bit 6 = Sunday). [REPEAT_ONCE] (mask 0) behaves like "next occurrence
     * of this time" — today if still ahead, otherwise tomorrow — and the
     * alarm simply isn't re-armed after it fires.
     */
    private fun nextTriggerMillis(minutesFromMidnight: Int, repeatDaysMask: Int = REPEAT_DAILY): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesFromMidnight / 60)
            set(Calendar.MINUTE, minutesFromMidnight % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (repeatDaysMask == REPEAT_ONCE || repeatDaysMask == REPEAT_DAILY) {
            return cal.timeInMillis
        }
        // Step forward (max 7 days) to the next enabled day-of-week.
        repeat(7) {
            // Calendar: SUNDAY=1..SATURDAY=7 → our mask bit: Monday=0..Sunday=6
            val bit = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                else -> 6 // SUNDAY
            }
            if (repeatDaysMask and (1 shl bit) != 0) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis // unreachable for any non-zero mask
    }
}
