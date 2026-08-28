package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the AlarmManager alarm at the user's chosen streak-alert time.
 *
 * Delegates the "should we alert?" decision to [StreakAlertNotifier] — the
 * notification only appears when today's habits are still incomplete — then
 * reschedules the alarm for the following day so the guard stays perpetual.
 * Rescheduling happens even on silent days; tomorrow starts unfinished again.
 */
class StreakAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra(EXTRA_ALERT_MINUTES, DEFAULT_MINUTES)

        StreakAlertNotifier.showIfStreakAtRisk(context)

        // Reschedule for tomorrow at the same time.
        NotificationScheduler(context).scheduleStreakAlert(minutes)
    }

    companion object {
        const val EXTRA_ALERT_MINUTES = "streak_alert_minutes"
        const val DEFAULT_MINUTES = 20 * 60 // 8:00 PM fallback
    }
}
