package com.rork.mindsetframestracker.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Fired when the user taps "Snooze 5 min" on a habit reminder notification.
 * Dismisses the current notification and enqueues a WorkManager job 5
 * minutes later that re-shows the same reminder — it does NOT touch or
 * reschedule the habit's normal daily reminder chain, so the next day's
 * reminder still fires at its usual time regardless of a snooze today.
 *
 * Previously the 5-minute re-fire used `AlarmManager.setExactAndAllowWhileIdle()`,
 * which silently falls back to an inexact, permission-gated alarm the moment
 * the exact-alarm special permission isn't held — exactly the kind of thing
 * that made "Snooze" look broken. WorkManager needs no special permission
 * and reliably delivers the delayed re-fire through [HabitReminderWorker].
 */
class HabitSnoozeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HabitSnoozeReceiver"
        private const val SNOOZE_MINUTES = 5L
        private const val WORK_NAME_PREFIX = "habit_snooze_"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getStringExtra("habitId") ?: return
        val habitName = intent.getStringExtra("habitName") ?: return

        // Dismiss the notification that was just snoozed.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(HabitCheckInNotifier.notificationId(habitId))

        val inputData = Data.Builder()
            .putString(HabitReminderWorker.KEY_HABIT_ID, habitId)
            .putString(HabitReminderWorker.KEY_HABIT_NAME, habitName)
            // Marks this as a snooze re-fire so HabitReminderWorker does NOT
            // call scheduleNext() again for it — only the original daily
            // reminder chain should re-arm itself.
            .putBoolean(HabitReminderWorker.KEY_IS_SNOOZE_REFIRE, true)
            .build()

        val request = OneTimeWorkRequestBuilder<HabitReminderWorker>()
            .setInitialDelay(SNOOZE_MINUTES, TimeUnit.MINUTES)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME_PREFIX$habitId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.d(TAG, "Snoozed '$habitName' for $SNOOZE_MINUTES minutes")
    }
}
