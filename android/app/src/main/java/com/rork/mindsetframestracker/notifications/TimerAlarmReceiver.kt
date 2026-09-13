package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rork.mindsetframestracker.data.TimerRepository

/**
 * Fired by the [android.app.AlarmManager] alarm that [TimerAlarmScheduler]
 * armed for a running timer's deadline.
 *
 * Runs in whatever process the OS chooses \u2014 often a fresh, UI-less one, since
 * the app may have been killed hours earlier mid-timer. It therefore touches
 * nothing but [TimerRepository] and [TimerCompletion], both of which are
 * process-safe.
 *
 * The race against [TimerService]'s in-process tick and against the UI's own
 * tick is intentional and harmless: [TimerCompletion.fire] funnels all three
 * through the same one-time gate, so exactly one alert is produced and exactly
 * one popup is later shown.
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerAlarmScheduler.ACTION_TIMER_DEADLINE) return

        val repo = TimerRepository(context)
        val active = repo.loadActive()
        if (active == null) {
            Log.d(TAG, "Deadline fired with no active timer \u2014 nothing to do")
            return
        }

        val timerId = intent.getStringExtra(EXTRA_TIMER_ID)
        if (timerId != null && timerId != active.id) {
            // The user stopped or replaced this run before its alarm fired.
            Log.d(TAG, "Deadline fired for stale timer $timerId (active is ${active.id})")
            return
        }

        val event = TimerCompletion.fire(context, active)
        if (event == null) {
            Log.d(TAG, "Timer ${active.id} not due yet or already handled \u2014 re-arming")
        }
    }

    companion object {
        private const val TAG = "TimerAlarmReceiver"
        const val EXTRA_TIMER_ID = "extra_timer_id"
        const val EXTRA_DEADLINE = "extra_deadline"
    }
}
