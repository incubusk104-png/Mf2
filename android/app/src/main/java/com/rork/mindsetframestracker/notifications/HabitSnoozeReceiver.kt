package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
 */
class HabitSnoozeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HabitSnoozeReceiver"
        private val SNOOZE_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5)
        private val WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5)
        // Offset from the habit's main-alarm request code so the snooze's
        // PendingIntent never clobbers the next scheduled occurrence.
        private const val SNOOZE_REQUEST_CODE_OFFSET = 1
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

        // Dismiss the notification that was just snoozed.
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(HabitCheckInNotifier.notificationId(habitId))

        val reminderIntent = Intent(context, HabitReminderReceiver::class.java).apply {
            action = HabitAlarmScheduler.ACTION_HABIT_REMINDER
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_NAME, habitName)
            // Marks this as a snooze re-fire so HabitReminderReceiver does NOT
            // call scheduleNext() again for it — only the original daily
            // reminder chain should re-arm itself.
            putExtra(HabitReminderReceiver.EXTRA_IS_SNOOZE_REFIRE, true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            habitId.hashCode() + SNOOZE_REQUEST_CODE_OFFSET,
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
            showIntent = AlarmScheduler.showIntent(
                context,
                habitId.hashCode() + SNOOZE_REQUEST_CODE_OFFSET,
            ),
        )
        Log.d(TAG, "Snoozed '$habitName' for 5 minutes (precision=$precision)")
    }
}
