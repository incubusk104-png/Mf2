package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by the AlarmManager alarm on Sunday evenings.
 *
 * Posts the weekly recap ("You checked in 5/7 days this week") via
 * [WeeklyRecapNotifier], then reschedules itself for the following Sunday so
 * the recap stays perpetual. Rescheduling happens even when the recap was
 * skipped (e.g. no habits configured yet) — next week may have data.
 */
class WeeklyRecapReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Guarded like every other alarm receiver. This one lands on a Sunday
        // evening when the app is almost certainly not running, and an uncaught
        // throw from a manifest receiver takes the process down with it.
        runCatching {
            WeeklyRecapNotifier.showRecap(context)
            NotificationScheduler(context).scheduleWeeklyRecap()
        }.onFailure { Log.w(TAG, "Weekly recap failed", it) }
    }

    private companion object {
        const val TAG = "WeeklyRecapReceiver"
    }
}
