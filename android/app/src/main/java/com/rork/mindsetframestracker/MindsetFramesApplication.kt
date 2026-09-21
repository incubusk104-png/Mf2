package com.rork.mindsetframestracker

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.rork.mindsetframestracker.notifications.BootReceiver
import com.rork.mindsetframestracker.notifications.TimerCompletion
import java.io.File

/**
 * Installs a process-wide uncaught-exception handler so a crash that happens
 * anywhere — including while the app is backgrounded, mid-sync, or firing a
 * BroadcastReceiver — leaves a trace instead of only showing the system
 * "Mindset Frames keeps stopping" dialog with no diagnostic breadcrumb.
 *
 * This does NOT swallow the crash: after logging, it always hands off to the
 * previous (system) handler so the process still terminates normally. Its
 * only job is to make the next crash debuggable.
 */
class MindsetFramesApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installGlobalCrashLogger()
        // Restore the timer / stopwatch after a process restart.
        // This is the one case no BroadcastReceiver can cover: if the app was
        // force-stopped, Android drops its pending alarms WITHOUT sending
        // BOOT_COMPLETED, so a timer that expired while we were dead would
        // otherwise never fire its alert or leave its completion popup pending.
        // Re-arming here means an interrupted timer still reports its result —
        // once — the next time the app opens.
        runCatching { TimerCompletion.reconcileOnColdStart(this) }
            .onFailure { Log.w(TAG, "Timer cold-start reconcile failed", it) }
        registerClockChangeReceiver()
    }

    /**
     * Re-arms every alarm when the *running* app sees the clock or timezone
     * change.
     *
     * ## Why this is needed on top of the manifest registration
     *
     * [BootReceiver] declares `TIME_SET` / `TIMEZONE_CHANGED` in the manifest,
     * which is what covers the app-not-running case. Those actions are not
     * reliably delivered to a manifest receiver in a process that is **already
     * alive**, and it is exactly the running process whose schedule the user is
     * looking at when they change the zone or fix the clock. Without this, a
     * timezone change while the app is open would leave every already-armed
     * epoch pointing at the old zone until the next reboot.
     *
     * ## Why it cannot double-arm anything
     *
     * Both registrations call the same idempotent reschedule. Every alarm is
     * armed under a request code derived from `(habit, time)` with
     * `FLAG_UPDATE_CURRENT`, so a second pass **replaces** that entry instead of
     * adding a duplicate — the user can never end up with two alarms for one
     * reminder (see [com.rork.mindsetframestracker.notifications.HabitAlarmScheduler]).
     *
     * Registered with `RECEIVER_NOT_EXPORTED`: `TIME_SET` is a system-protected
     * broadcast, so only the system can reach it, and on API 34+ a dynamically
     * registered receiver without one of the export flags throws
     * `SecurityException`.
     */
    private fun registerClockChangeReceiver() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(
                this,
                clockChangeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "Could not register the clock-change receiver", it) }
    }

    /**
     * Delegates straight to [BootReceiver], which owns the whole "re-arm
     * everything" implementation and already accepts exactly these two actions.
     * Re-implementing the reschedule here would be a second path that could
     * drift from the one the reboot path takes.
     */
    private val clockChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            runCatching { BootReceiver().onReceive(this@MindsetFramesApplication, intent) }
                .onFailure { Log.w(TAG, "Clock-change re-arm failed", it) }
        }
    }

    private fun installGlobalCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val message = "${System.currentTimeMillis()} FATAL on thread '${thread.name}': " +
                    "${throwable.javaClass.name}: ${throwable.message}\n" +
                    throwable.stackTraceToString() + "\n\n"
                Log.e(TAG, message, throwable)
                File(cacheDir, CRASH_LOG_FILE).appendText(message)
            }
            // Always defer to the platform's default handler afterwards so
            // the crash dialog / process teardown behaves exactly as before.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "MindsetFramesApp"

        /** File under cacheDir where fatal crashes are appended, newest last. */
        const val CRASH_LOG_FILE = "crash_log.txt"
    }
}
