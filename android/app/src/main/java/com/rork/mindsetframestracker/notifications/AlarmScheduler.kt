package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Shared, corrected AlarmManager access for **every** alarm in the app.
 *
 * ## The app's own alarm, not the phone's clock app
 *
 * Every alarm in this app used to be armed with
 * `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent), pi)`. That API
 * belongs to the *system Clock app*, and using it is exactly why the alarms
 * read as "the personal clock app is the one ringing":
 *
 *  - it draws **the system's alarm-clock icon in the status bar**,
 *  - it registers in the phone's **"next alarm" slot**, so the Clock app (and
 *    the lock screen) claim our reminder as its own,
 *  - and on several OEM skins (MIUI/HyperOS, OnePlus, Samsung) it is surfaced
 *    in the **Clock app's own alarm list**, so the user sees their personal
 *    alarm list change when they set a habit alarm here.
 *
 * The reminder was always *delivered* to our own receiver, but its *identity*
 * was handed to the system clock. [schedule] therefore no longer registers a
 * system alarm-clock alarm at all: it arms the strongest **app-owned** exact
 * alarm the device allows ([AlarmManager.setExactAndAllowWhileIdle], which is
 * Doze-exempt but never touches the Clock app), and the ring that follows is
 * entirely the app's own — our receiver, our notification
 * ([HabitCheckInNotifier] / [TimerNotifier]), our sound and vibration
 * ([AlarmRingService]) and our ring screen ([AlarmRingingActivity]).
 *
 * ## What was wrong before that
 *
 *  1. **`setAlarmClock()` was called without re-checking
 *     `canScheduleExactAlarms()` at the call site.** That value reflects the
 *     permission state at the moment it is read; if the user revoked
 *     "Alarms & reminders" while the app was backgrounded, the call throws
 *     `SecurityException` on Android 12+ (API 31/32 **included**). The old code
 *     wrapped the call in `runCatching`, so instead of crashing it silently
 *     scheduled **nothing at all** — the reminder simply never arrived, with
 *     one `Log.w` line as the only trace. This is the single most likely cause
 *     of "the alarm just didn't ring" on a device where every permission screen
 *     looks green.
 *  2. **A broadcast PendingIntent was used as `AlarmClockInfo`'s show-intent.**
 *     Android cannot launch an Activity from a broadcast PendingIntent, so the
 *     status-bar clock icon was dead when tapped.
 *  3. **Another app could cancel our alarm.** Passing the *same* PendingIntent
 *     in both slots of `setAlarmClock()` makes two apps sharing it cancel each
 *     other. Removing the clock registration removes this hazard with it.
 *
 * ## The ordering this enforces
 *
 * [canScheduleExact] → `setExactAndAllowWhileIdle` (`setAndAllowWhileIdle` when
 * that is unavailable) → `setWindow`. Every rung fires **without** an exact-alarm
 * grant, so a missing grant degrades timing instead of deleting the reminder.
 *
 * Plain (non-`allowWhileIdle`) `setExact` is deliberately **never** used: it is
 * deferred until the next Doze maintenance window — up to ~15 minutes of silence
 * in the exact scenario users complain about most (screen off, phone asleep,
 * "timer at 8:45 PM").
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** 15-minute window used by the last-resort inexact fallback. */
    const val WINDOW_MILLIS = 15L * 60L * 1000L

    /** Where an alarm's "how late can this be?" answer lands, for the UI. */
    enum class Precision { EXACT, APPROXIMATE }

    /** True when this OS version gates exact alarms behind a user grant. */
    fun exactAlarmsGated(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Whether the app may currently schedule exact alarms. Pre-API-31 always
     * true; from API 31 on, the real `canScheduleExactAlarms()` answer, with a
     * `SecurityException` treated as "no" instead of propagating.
     */
    fun canScheduleExact(context: Context): Boolean {
        if (!exactAlarmsGated(context)) return true
        return runCatching { alarmManager(context).canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * True when this app holds the *auto-granted* exact-alarm privilege
     * (`USE_EXACT_ALARM`) and therefore never needs to send the user to a
     * Settings page. Only meaningful from API 31 up.
     *
     * The sibling [canScheduleExact] answers the different, permission-state
     * based question. Keeping the two apart is what lets the permission UI say
     * the right thing: with `USE_EXACT_ALARM` granted, flipping
     * `SCHEDULE_EXACT_ALARM` off in Settings still leaves exact alarms legal,
     * so nagging the user to go and re-enable it would be wrong.
     */
    fun hasAutoGrantedExactAlarm(context: Context): Boolean {
        if (!exactAlarmsGated(context)) return true
        return runCatching {
            context.checkSelfPermission(android.Manifest.permission.USE_EXACT_ALARM) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * Arms [pendingIntent] for absolute wall-clock [triggerAtMillis], choosing
     * the strongest **app-owned** mechanism this device currently allows.
     *
     * Deliberately never calls `setAlarmClock()`: that registers with the system
     * Clock app (status-bar clock icon, the "next alarm" slot, the clock app's
     * own alarm list), which is what made these alarms behave like the phone's
     * personal clock app rather than this app's own alarm. The app owns the
     * whole ring from here on: `AlarmManager` → our receiver → our notification
     * → [AlarmRingService] for the sound → [AlarmRingingActivity] for the screen.
     *
     * @param allowWhileIdle whether the rungs may use the `allowWhileIdle`
     *   variants. `true` for anything the user is *waiting* on (habit reminders,
     *   snooze, timer completion) — that is what makes them Doze-exempt.
     */
    fun schedule(
        context: Context,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        wakeUp: Boolean = true,
        allowWhileIdle: Boolean = true,
    ): Precision {
        val type = if (wakeUp) AlarmManager.RTC_WAKEUP else AlarmManager.RTC
        val canExact = canScheduleExact(context)
        val manager = alarmManager(context)

        if (allowWhileIdle) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // The app-owned exact alarm: fires through Doze, and unlike
                // setAlarmClock it never registers with the system clock app.
                val ok = runCatching {
                    manager.setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
                }.onFailure {
                    Log.w(TAG, "setExactAndAllowWhileIdle rejected — falling through", it)
                }.isSuccess
                if (ok) return if (canExact) Precision.EXACT else Precision.APPROXIMATE
            }
            val ok = runCatching {
                manager.setAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
            }.onFailure {
                Log.w(TAG, "setAndAllowWhileIdle rejected — falling through", it)
            }.isSuccess
            if (ok) return Precision.APPROXIMATE
        }

        // Last resort: always available, no permission, may be postponed out
        // of Doze — but still *fires*, which beats a silently dropped alarm.
        runCatching {
            manager.setWindow(type, triggerAtMillis, WINDOW_MILLIS, pendingIntent)
        }.onFailure { Log.e(TAG, "Failed to schedule alarm on every fallback path", it) }

        return Precision.APPROXIMATE
    }

    /**
     * Cancels an alarm previously armed with [pendingIntent].
     *
     * Pass the **exact same** PendingIntent definition that was scheduled —
     * extras included — because `AlarmManager` matches on
     * `(requestCode, Intent filterEquals)`, and `Intent.filterEquals()` ignores
     * extras. A cancel built from a *different* extras set for the same
     * request code cancels the alarm but leaves a stale PendingIntent behind,
     * which is why every call site here cancels and then calls
     * `PendingIntent.cancel()`.
     */
    fun cancel(context: Context, pendingIntent: PendingIntent?) {
        if (pendingIntent == null) return
        runCatching { alarmManager(context).cancel(pendingIntent) }
            .onFailure { Log.w(TAG, "Failed to cancel alarm", it) }
        runCatching { pendingIntent.cancel() }
    }

    /**
     * Builds a broadcast PendingIntent for an alarm, or returns null when none
     * exists yet ([PendingIntent.FLAG_NO_CREATE]) — the safe shape for cancel
     * paths, which must never fabricate a PendingIntent just to cancel it.
     */
    fun broadcastIntent(
        context: Context,
        requestCode: Int,
        intent: Intent,
        createIfMissing: Boolean = true,
    ): PendingIntent? {
        val flags = if (createIfMissing) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * Whether the OS currently defers background alarms for this app because
     * it sits in a restricted App Standby bucket ("Rarely used" / "Restricted").
     *
     * This is the *silent* half of the battery problem that the permission
     * dialog never mentioned: on stock Android, an app the user hasn't opened
     * recently gets bucketed, and alarms in a restricted bucket can be deferred
     * by hours — with `SCHEDULE_EXACT_ALARM` fully granted. Surfacing it
     * alongside the OEM toggles is what makes the battery advice actionable.
     */
    fun standbyBucket(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? android.app.usage.UsageStatsManager ?: return null
            usageStats.appStandbyBucket
        }.getOrNull()
    }

    /** Opens the system "Alarms & reminders" page for this app (API 31+). */
    fun openExactAlarmSettings(context: Context) {
        if (!exactAlarmsGated(context)) return
        // When USE_EXACT_ALARM is granted there is nothing the user can change
        // that would affect us, so send them to the app page instead of a
        // toggle that will look enabled but do nothing.
        if (hasAutoGrantedExactAlarm(context)) {
            openAppDetailsSettings(context)
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
        }.onFailure { openAppDetailsSettings(context) }
    }

    fun openAppDetailsSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
        }.onFailure { Log.w(TAG, "Could not open app details settings", it) }
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
