package com.rork.mindsetframestracker.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rork.mindsetframestracker.data.MindsetRepository

/**
 * The **manual "Stop alarm"** entry point, and the one place an alarm is
 * actually silenced.
 *
 * ## Why this is a broadcast receiver, and why the stop used to fail
 *
 * A notification action that starts a service is refused outright under the
 * Android 12+ background-start restrictions on some OEM builds, and the refusal
 * is **silent** — the user then presses a button that does nothing while the
 * alarm keeps ringing. A broadcast is always deliverable, so the action routes
 * through here.
 *
 * But being deliverable is only half of it: the receiver used to call
 * [AlarmRingService.stop], which asked the **service to stop itself**
 * (`context.startService(ACTION_STOP)`). That is a *new* service start — and a
 * start from the background is exactly what those same restrictions refuse.
 * The fallback then called `stopService`, and `stopService` alone does **not**
 * run the service's teardown: `onDestroy` is only reached on a normal stop, so a
 * killed/failed start left the MediaPlayer and the repeating vibrator alive with
 * nothing left to silence them. That is the reported symptom — "the stop button
 * doesn't work, the alarm keeps ringing".
 *
 * The teardown is therefore no longer delegated to the service alone. This
 * receiver performs it directly, and does so from **every** angle, because each
 * one is independently unreliable on some device:
 *
 *  1. **Cancel the alarm** for this habit/timer, so a re-delivered or re-armed
 *     occurrence cannot immediately ring again after the user stopped it.
 *  2. **Cancel the notifications** (the reminder, the completion alert and the
 *     ongoing "Alarm ringing" one) — a stop that leaves the alert sitting in
 *     the shade reads as "the stop didn't work" even when the sound is gone.
 *  3. **Deliver the stop action to the service in-process** via
 *     [AlarmRingService.requestStopFromContext], which is the only path that
 *     guarantees `onStartCommand` runs [AlarmRingService.stopSelfSafely] — and
 *     hence that the player and the vibrator are actually released.
 *  4. **Fall back to a hard `stopService`** so the service cannot outlive the
 *     stop even if the delivery above is refused.
 *  5. **Fall back again to a self-contained in-process teardown** for the case
 *     where the service is unreachable entirely — see
 *     [AlarmRingService.stopFromBroadcast], which cannot fail for lack of a
 *     service start.
 *
 * ## Why every path is wrapped
 *
 * This runs while an alarm is ringing. A throw escaping `onReceive` is not a
 * caught error — it kills the process, and the process being killed is what the
 * user sees as the app crashing mid-alarm. Nothing here is worth that: a failed
 * stop leaves the alarm ringable, a thrown stop takes the app down with it.
 */
class AlarmStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Guarded as a whole. Deliberately tolerant of an unexpected/missing
        // action rather than returning early: the user asked for the alarm to
        // stop, and the only worse outcome than a silent button is a crash.
        runCatching { stopAlarm(context, intent) }
            .onFailure { Log.w(TAG, "Could not stop the ring", it) }
    }

    private fun stopAlarm(context: Context, intent: Intent) {
        // One identity, two flavours: a habit reminder carries its habitId, a
        // timer completion carries its eventId. Both are needed, because the
        // alarm to cancel and the notification to clear are keyed differently
        // for each kind, and cancelling the wrong one leaves a live alarm
        // scheduled behind a stopped ring.
        val habitId = intent.getStringExtra(EXTRA_HABIT_ID)
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME)
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)

        // ── 1. Cancel the schedule itself ──────────────────────────────────
        // Without this, stopping a *snoozed* or re-armed alarm only silences
        // the ring in progress: the pending AlarmManager entry is still armed
        // and fires again minutes later, which the user experiences as "it
        // stopped and then started ringing again on its own".
        if (!habitId.isNullOrBlank()) {
            runCatching {
                MindsetRepository(context).load().habits
                    .firstOrNull { it.id == habitId }
                    ?.let { habit -> HabitAlarmScheduler.cancel(context, habit) }
            }.onFailure { Log.w(TAG, "Could not cancel the alarm for habit $habitId", it) }
        }

        // ── 2. Clear the visible evidence ──────────────────────────────────
        // Every notification this alarm could have posted, plus the service's
        // ongoing one. Best-effort per item: a failure to clear one must not
        // skip the others.
        val manager = NotificationManagerCompat.from(context)
        if (!habitId.isNullOrBlank()) {
            runCatching { manager.cancel(HabitCheckInNotifier.notificationId(habitId)) }
        }
        if (!eventId.isNullOrBlank()) {
            runCatching {
                manager.cancel(TimerNotifier.notificationId(eventId))
                manager.cancel(TimerNotifier.notificationId(eventId) + 3)
            }
        }
        runCatching { manager.cancel(ONGOING_NOTIFICATION_ID) }

        // ── 3, 4 & 5. Kill the sound and the vibration ─────────────────────
        // The service owns the audio, so it must be told to release it; the
        // helper escalates through every mechanism that can reach it and, in
        // the worst case, tears the ring down itself from this process. The
        // ring genuinely cannot survive this call on any device we can think
        // of — which is the entire point, since the previous version relied on
        // a single background service start that some OEM builds silently
        // refuse.
        AlarmRingService.stop(context)

        // ── Tell a live ringing screen to close ────────────────────────────
        // The screen is launched by the full-screen intent and may still be up.
        // It polls the ring state (see AlarmRingingActivity), so this is belt
        // and braces — but it makes the screen dismiss immediately rather
        // than up to a poll interval later.
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_ALARM_STOPPED).setPackage(context.packageName),
            )
        }.onFailure { Log.w(TAG, "Could not notify the ringing screen", it) }

        Log.i(
            TAG,
            "Stopped alarm (habit=$habitId event=$eventId name=$habitName) " +
                "via the authoritative stop path",
        )
    }

    companion object {
        private const val TAG = "AlarmStopReceiver"

        /**
         * Action for the notification's "Stop alarm" button. Kept explicit so a
         * mis-routed intent can never reach a different handler.
         */
        const val ACTION_STOP_ALARM = "com.rork.mindsetframestracker.action.STOP_ALARM"

        /** Broadcast telling a live ringing screen that the alarm was stopped. */
        const val ACTION_ALARM_STOPPED = "com.rork.mindsetframestracker.action.ALARM_STOPPED"

        /**
         * Extras let the receiver cancel the RIGHT alarm and clear the RIGHT
         * notification. Without them the stop could only silence the ring in
         * progress and had to guess at what to clean up.
         */
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_EVENT_ID = "eventId"

        /**
         * The ongoing "Alarm ringing" notification's id, mirrored here so the
         * stop path can clear it. Kept in sync with [AlarmRingService]; a
         * mismatch would leave a stale entry in the shade after every stop.
         */
        const val ONGOING_NOTIFICATION_ID = 7100

        /**
         * Builds the "Stop alarm" [PendingIntent] for a notification action.
         *
         * Returns null instead of throwing when the intent cannot be built. The
         * callers are all notification builders that run on the alarm path
         * (inside receivers and the ring service), where a throw is fatal — so a
         * missing button is the correct failure mode, not a dead process.
         *
         * ## Why the request code must be unique per alarm
         *
         * `FLAG_UPDATE_CURRENT` with an equal `requestCode` and an equal Intent
         * (same action, same component) returns the **same** PendingIntent, with
         * its extras replaced. Two different habits' reminders would therefore
         * share one stop button, and pressing it would cancel whichever habit
         * was armed last — stopping a ringing alarm and *also* silently killing
         * another habit's schedule. Each caller passes a distinct code so each
         * alarm gets its own button, carrying its own identity.
         *
         * `FLAG_IMMUTABLE` is required from API 31; everything the receiver
         * needs is baked in here, so there is nothing for a caller to fill in.
         */
        fun stopPendingIntent(
            context: Context,
            requestCode: Int,
            habitId: String? = null,
            habitName: String? = null,
            eventId: String? = null,
        ): PendingIntent? =
            runCatching {
                PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    Intent(context, AlarmStopReceiver::class.java).apply {
                        action = ACTION_STOP_ALARM
                        putExtra(EXTRA_HABIT_ID, habitId)
                        putExtra(EXTRA_HABIT_NAME, habitName)
                        putExtra(EXTRA_EVENT_ID, eventId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }.onFailure { Log.w(TAG, "Could not build the Stop alarm intent", it) }
                .getOrNull()
    }
}
