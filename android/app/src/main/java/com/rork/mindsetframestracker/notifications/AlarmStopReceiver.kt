package com.rork.mindsetframestracker.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The **manual "Stop alarm"** entry point.
 *
 * Reachable from two places, both routed here so there is exactly one stop
 * implementation:
 *
 * 1. the **"Stop alarm" action on the alarm notification** (and on the ongoing
 *    "Alarm ringing" notification), built by [stopPendingIntent];
 * 2. implicitly, the ringing screen's own Stop button \u2014 it calls
 *    [AlarmRingService.stop] directly, which is the same guarded teardown.
 *
 * ## Why a receiver and not a service PendingIntent
 *
 * A notification action that starts a service is refused outright under the
 * Android 12+ background-start restrictions on some OEM builds, and the refusal
 * is **silent**. The user then presses a button that does nothing while the
 * alarm keeps ringing \u2014 the exact complaint this exists to fix. A broadcast is
 * always deliverable.
 *
 * [AlarmRingService.stop] carries its own `startService` \u2192 `stopService`
 * fallback, and `stopService` is permitted regardless of background state, so the
 * stop lands either way.
 *
 * ## Why every path is wrapped
 *
 * This runs while an alarm is ringing. A throw escaping `onReceive` is not a
 * caught error \u2014 it kills the process, and the process being killed is what the
 * user sees as the app crashing mid-alarm. Nothing here is worth that: a failed
 * stop leaves the alarm ringable, a thrown stop takes the app down with it.
 */
class AlarmStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Guarded as a whole. Deliberately tolerant of an unexpected/missing
        // action rather than returning early: the user asked for the alarm to
        // stop, and the only worse outcome than a silent button is a crash.
        runCatching { AlarmRingService.stop(context) }
            .onFailure { Log.w(TAG, "Could not stop the ring", it) }
    }

    companion object {
        private const val TAG = "AlarmStopReceiver"

        /**
         * Action for the notification's "Stop alarm" button. Kept explicit so a
         * mis-routed intent can never reach a different handler.
         */
        const val ACTION_STOP_ALARM = "com.rork.mindsetframestracker.action.STOP_ALARM"

        /**
         * Builds the "Stop alarm" [PendingIntent] for a notification action.
         *
         * Returns null instead of throwing when the intent cannot be built. The
         * callers are all notification builders that run on the alarm path
         * (inside receivers and the ring service), where a throw is fatal \u2014 so a
         * missing button is the correct failure mode, not a dead process.
         *
         * `FLAG_IMMUTABLE` is required from API 31; the intent is fully
         * specified here, so there is nothing for a caller to fill in.
         */
        fun stopPendingIntent(context: Context, requestCode: Int): PendingIntent? =
            runCatching {
                PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    Intent(context, AlarmStopReceiver::class.java).apply {
                        action = ACTION_STOP_ALARM
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }.onFailure { Log.w(TAG, "Could not build the Stop alarm intent", it) }
                .getOrNull()
    }
}
