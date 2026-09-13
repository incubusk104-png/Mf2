package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.TimerRepository

/**
 * The **one-shot** follow-up for a timer result the user never looked at.
 *
 * Why this exists: the completion alert is a normal notification, so swiping it
 * away is easy \u2014 especially when it arrives as the user is finishing the walk.
 * Without a follow-up the result is simply lost. With a naive follow-up
 * (\"repeat until dismissed\") the user gets nagged, which is worse.
 *
 * So exactly one reminder is scheduled, some minutes after the completion, and
 * [TimerRepository.markReminderSent] guarantees it can only ever post once per
 * event \u2014 a reboot, a redelivery, or a second alarm all find the flag already
 * set and stay silent. If the event was consumed in the meantime (the user
 * opened the popup), [TimerNotifier.maybePostReminder] drops it.
 */
class TimerReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TIMER_REMINDER) return

        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val repo = TimerRepository(context)
        val event = repo.loadPendingEvent()

        if (event == null) {
            Log.d(TAG, "Reminder fired but nothing is pending \u2014 user already handled it")
            return
        }
        if (eventId != null && event.eventId != eventId) {
            Log.d(TAG, "Reminder fired for stale event $eventId (pending is ${event.eventId})")
            return
        }

        TimerNotifier.maybePostReminder(context, event)
    }

    companion object {
        private const val TAG = "TimerReminderReceiver"
        const val ACTION_TIMER_REMINDER = "com.rork.mindsetframestracker.TIMER_REMINDER"
        const val EXTRA_EVENT_ID = "extra_event_id"

        /** How long to wait before nudging. Long enough not to feel like nagging. */
        const val REMINDER_DELAY_MILLIS = 4L * 60L * 1000L

        private const val REQUEST_CODE = 7200

        /**
         * Arms the single reminder for [eventId]. Safe to call repeatedly \u2014 the
         * same request code replaces any previous reminder rather than stacking,
         * and the repository flag makes a duplicate post impossible anyway.
         */
        fun schedule(context: Context, eventId: String, delayMillis: Long = REMINDER_DELAY_MILLIS) {
            runCatching {
                val pendingIntent = AlarmScheduler.broadcastIntent(
                    context,
                    REQUEST_CODE,
                    Intent(context, TimerReminderReceiver::class.java).apply {
                        action = ACTION_TIMER_REMINDER
                        putExtra(EXTRA_EVENT_ID, eventId)
                    },
                ) ?: return
                AlarmScheduler.schedule(
                    context = context,
                    triggerAtMillis = System.currentTimeMillis() + delayMillis,
                    pendingIntent = pendingIntent,
                    wakeUp = true,
                    // A quiet nth-chance nudge has no business waking a sleeping
                    // phone \u2014 allowWhileIdle=false keeps it in the batched path.
                    allowWhileIdle = false,
                )
            }.onFailure { Log.w(TAG, "Could not schedule timer reminder", it) }
        }

        fun cancel(context: Context) {
            val existing = AlarmScheduler.broadcastIntent(
                context = context,
                requestCode = REQUEST_CODE,
                intent = Intent(context, TimerReminderReceiver::class.java).apply {
                    action = ACTION_TIMER_REMINDER
                },
                createIfMissing = false,
            )
            AlarmScheduler.cancel(context, existing)
        }
    }
}
