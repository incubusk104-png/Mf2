package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
        // Guarded, and this is the fix for the "crashes after I leave" report.
        //
        // This receiver fires from an AlarmManager alarm, so the app has almost
        // always been closed for hours and the process is started fresh in the
        // background. An uncaught throw out of a *manifest* receiver is not
        // caught by anything in the app: the system treats it as an unhandled
        // exception and kills the whole process. Every sibling alarm receiver
        // (BootReceiver, HabitReminderReceiver, TimerAlarmReceiver,
        // HabitSnoozeReceiver) had already been hardened exactly this way —
        // these three were the ones left exposed, which is why the crash only
        // ever appeared while the app sat unused in the background.
        runCatching { showAndReschedule(context, intent) }
            .onFailure { Log.w(TAG, "Check-in reminder failed", it) }
    }

    private fun showAndReschedule(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, DEFAULT_MINUTES)

        CheckInNotifier.show(context)

        // Reschedule for tomorrow at the same time.
        NotificationScheduler(context).scheduleDailyReminder(minutes)
    }

    companion object {
        private const val TAG = "CheckInReceiver"
        const val EXTRA_REMINDER_MINUTES = "reminder_minutes"
        const val DEFAULT_MINUTES = 8 * 60 // 8:00 AM fallback
    }
}
