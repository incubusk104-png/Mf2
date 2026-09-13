package com.rork.mindsetframestracker

import android.app.Application
import android.util.Log
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
