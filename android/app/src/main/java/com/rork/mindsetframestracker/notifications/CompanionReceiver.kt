package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires the one-shot companion alarms:
 *
 * - [NotificationScheduler.ACTION_EVENING_REFLECTION] — nightly reflection prompt.
 */
class CompanionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NotificationScheduler.ACTION_EVENING_REFLECTION -> {
                CompanionNotifier.showEveningReflection(context)
                NotificationScheduler(context).scheduleEveningReflection()
            }
        }
    }
}
