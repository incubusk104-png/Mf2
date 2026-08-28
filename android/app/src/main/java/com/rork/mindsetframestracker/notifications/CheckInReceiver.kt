package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the [android.app.AlarmManager] alarm clock at the user's chosen
 * reminder time.
 *
 * Posts the daily check-in notification via [CheckInNotifier], then
 * reschedules the alarm for the following day so the reminder stays perpetual
 * without relying on a repeating-alarm primitive (which can drift across DST
 * and reboots).
 */
class CheckInReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, DEFAULT_MINUTES)

        CheckInNotifier.show(context)

        // Reschedule for tomorrow at the same time.
        NotificationScheduler(context).scheduleDailyReminder(minutes)
    }

    companion object {
        const val EXTRA_REMINDER_MINUTES = "reminder_minutes"
        const val DEFAULT_MINUTES = 8 * 60 // 8:00 AM fallback
    }
}
