package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired when the user taps "Snooze 5 min" on a habit reminder notification.
 * Dismisses the current notification and schedules a one-shot alarm 5
 * minutes later that re-shows the same reminder — it does NOT touch or
 * reschedule the habit's normal daily alarm chain, so the next day's
 * reminder still fires at its usual time regardless of a snooze today.
 */
class HabitSnoozeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HabitSnoozeReceiver"
        private const val SNOOZE_MILLIS = 5L * 60L * 1000L
        private const val SNOOZE_REQUEST_CODE_BASE = 20_000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getStringExtra("habitId") ?: return
        val habitName = intent.getStringExtra("habitName") ?: return

        // Dismiss the notification that was just snoozed.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(HabitCheckInNotifier.notificationId(habitId))

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val snoozeIntent = Intent(context, HabitAlarmReceiver::class.java).apply {
            putExtra("habitId", habitId)
            putExtra("habitName", habitName)
            // Marks this as a snooze re-fire so HabitAlarmReceiver does NOT
            // call scheduleNext() again for it — only the original daily
            // alarm chain should re-arm itself.
            putExtra("isSnoozeRefire", true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_CODE_BASE + habitId.hashCode(),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val triggerAt = System.currentTimeMillis() + SNOOZE_MILLIS
        runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }.onFailure {
            // Fall back to inexact if exact isn't permitted right now.
            runCatching { alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
        }
        Log.d(TAG, "Snoozed '$habitName' for 5 minutes")
    }
}
