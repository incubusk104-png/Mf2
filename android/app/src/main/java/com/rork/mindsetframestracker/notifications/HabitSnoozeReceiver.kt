package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rork.mindsetframestracker.data.AlarmEventOutcome
import com.rork.mindsetframestracker.data.HabitAlarmHistory
import java.util.concurrent.TimeUnit

/**
 * Fired when the user taps "Snooze 5 min" on a habit reminder notification.
 * Dismisses the current notification and arms an [AlarmManager] alarm 5
 * minutes later that re-shows the same reminder via [HabitReminderReceiver]
 * — it does NOT touch or reschedule the habit's normal daily reminder chain,
 * so the next day's reminder still fires at its usual time regardless of a
 * snooze today.
 *
 * Uses `setExactAndAllowWhileIdle()` (falling back to a short `setWindow()`
 * when the exact-alarm permission isn't held) instead of WorkManager: a
 * WorkManager `setInitialDelay()` job can be deferred well past its delay
 * once the screen is off, which for a 5-minute snooze the user is actively
 * waiting on would look exactly like "snooze does nothing."
 *
 * ## Which alarm it snoozes, now that a habit has several
 *
 * A habit may ring at 07:00, 12:00 and 18:00, and the snooze must apply to the
 * one whose notification the user actually tapped. The snooze alarm
 * therefore re-fires **that same alarm time** (carried on the notification's
 * intent), and its own request code is derived from `(habit, time)` — the same
 * identity the original alarm uses. Previously it was `habitId.hashCode() + 1`,
 * which collided with the [HabitAlarmScheduler] request code of the habit's
 * **second** alarm time; `FLAG_UPDATE_CURRENT` would then hand back that
 * alarm's pending intent, so snoozing the 07:00 reminder silently *replaced*
 * the live 12:00 alarm with a 5-minute snooze.
 */
class HabitSnoozeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HabitSnoozeReceiver"
        private val SNOOZE_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5)
        private val WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5)
        /**
         * Offset applied to the originating alarm's request code so the snooze
         * has its own identity.
         *
         * Added to a code already derived from `(habit, time)`, so it is
         * distinct per alarm time rather than per habit. Placed in a reserved
         * high band so it cannot collide with another of the habit's own alarm
         * times, which use the low range of the same derivation.
         */
        private const val SNOOZE_REQUEST_CODE_OFFSET = 100_000
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Guarded for the same reason as the other alarm receivers: a throw out
        // of a manifest receiver kills the process, and this one fires from the
        // user tapping "Snooze" on a ringing alarm.
        runCatching { snooze(context, intent) }
            .onFailure { Log.w(TAG, "Snooze handling failed", it) }
    }

    private fun snooze(context: Context, intent: Intent) {
        val habitId = intent.getStringExtra("habitId") ?: return
        val habitName = intent.getStringExtra("habitName") ?: return
        // Which alarm time was snoozed, so the re-fire is the SAME occurrence
        // and its request code is unique to that time.
        val alarmMinutes = intent.getIntExtra(
            HabitReminderReceiver.EXTRA_ALARM_MINUTES,
            HabitReminderReceiver.NO_ALARM_MINUTES,
        )

        // Dismiss the notification that was just snoozed.
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(HabitCheckInNotifier.notificationId(habitId))

        // ── Record the snooze against THIS occurrence ─────────────────────────
        // A snooze REFINES the occurrence that already fired rather than adding a
        // new event, because the user's model is "the 07:00 alarm" — not "the
        // 07:00 alarm, twice". HabitAlarmHistory upserts on (habit, day, time), so
        // the re-fire that follows updates this same entry instead of creating a
        // second one, and the day's history stays one row per scheduled time.
        runCatching {
            HabitAlarmHistory.markOutcome(
                context = context,
                habitId = habitId,
                scheduledMinutes = alarmMinutes.takeIf {
                    it != HabitReminderReceiver.NO_ALARM_MINUTES
                },
                outcome = AlarmEventOutcome.SNOOZED,
            )
        }.onFailure { Log.w(TAG, "Could not record the snooze for $habitId", it) }

        val reminderIntent = Intent(context, HabitReminderReceiver::class.java).apply {
            action = HabitAlarmScheduler.ACTION_HABIT_REMINDER
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_NAME, habitName)
            putExtra(HabitReminderReceiver.EXTRA_ALARM_MINUTES, alarmMinutes)
            // Marks this as a snooze re-fire so HabitReminderReceiver does NOT
            // call scheduleNext() again for it — only the original daily
            // reminder chain should re-arm itself.
            putExtra(HabitReminderReceiver.EXTRA_IS_SNOOZE_REFIRE, true)
        }
        // Derived from the alarm time, so two snoozes of two different times can
        // coexist and neither can be retargeted onto the other.
        val requestCode = HabitAlarmScheduler.requestCodeFor(habitId, alarmMinutes) +
            SNOOZE_REQUEST_CODE_OFFSET
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            reminderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val triggerAtMillis = System.currentTimeMillis() + SNOOZE_DELAY_MILLIS

        // Routed through the shared AlarmScheduler. The permission is now
        // re-checked at this call site: a grant the user revoked since the
        // original alarm was armed used to make setExactAndAllowWhileIdle throw
        // SecurityException straight into the old runCatching, which meant the
        // snooze was silently never armed at all — the user tapped "Snooze 5
        // min" and simply never heard from it again. The fallback ladder below
        // keeps the re-fire alive even without the grant.
        val precision = AlarmScheduler.schedule(
            context = context,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            wakeUp = true,
            allowWhileIdle = true,
        )
        Log.d(TAG, "Snoozed '$habitName' for 5 minutes (precision=$precision)")
    }
}
