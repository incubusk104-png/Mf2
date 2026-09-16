package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires the one-shot companion alarms:
 *
 * - [NotificationScheduler.ACTION_EVENING_REFLECTION] — nightly reflection prompt.
 *
 * ## Why the body is guarded
 *
 * This is the last manifest receiver in the app that let exceptions escape
 * `onReceive`, and it is the worst one to leave unguarded: it fires from an
 * AlarmManager alarm on a device whose app has almost certainly been closed
 * for hours, so the process is started fresh just to deliver it. An uncaught
 * throw from a manifest receiver does not fail quietly — it kills that
 * process. Every sibling alarm receiver ([CheckInReceiver],
 * [StreakAlertReceiver], [WeeklyRecapReceiver], [BootReceiver]) already wraps
 * its work this way; this one was missed, which made it the one remaining path
 * where an evening alarm could take the app down.
 */
class CompanionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                NotificationScheduler.ACTION_EVENING_REFLECTION -> {
                    CompanionNotifier.showEveningReflection(context)
                    NotificationScheduler(context).scheduleEveningReflection()
                }
            }
        }.onFailure { Log.w(TAG, "Companion alarm handling failed", it) }
    }

    private companion object {
        const val TAG = "CompanionReceiver"
    }
}
