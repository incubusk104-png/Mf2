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
 * ## What was wrong in the three ad-hoc copies this replaces
 *
 * [NotificationScheduler], [HabitAlarmScheduler] and [HabitSnoozeReceiver]
 * each re-implemented the same `canUseExact` branch, and all three shared the
 * same three defects:
 *
 * 1. **`setAlarmClock()` was called without checking `canScheduleExactAlarms()`
 *    *again* at the call site.** `canScheduleExactAlarms()` reflects the
 *    permission state at the moment it is read; if the user revoked
 *    \"Alarms & reminders\" while the app was backgrounded, or a restore put
 *    the app in that state, the call throws `SecurityException` on Android
 *    12+ (API 31/32 **included**, not just 14+). The old code wrapped the call
 *    in `runCatching`, so instead of crashing it silently scheduled **nothing at
 *    all** \u2014 the reminder simply never arrived, and the only trace was one
 *    `Log.w` line. This is the single most likely cause of \"the alarm just
 *    didn't ring\" on a device where every permission screen looks green.
 *
 * 2. **A shell `PendingIntent` was used for `AlarmClockInfo`'s show-intent.**
 *    Passing the *broadcast* PendingIntent as the show-intent told Android to
 *    launch a BroadcastReceiver when the user tapped the status-bar alarm
 *    icon. Android cannot start an Activity from a broadcast PendingIntent, so
 *    the clock icon was dead \u2014 tapping it did nothing. A real show-intent
 *    must be `getActivity()`.
 *
 * 3. **Another app could cancel our alarm.** All three used
 *    `setAlarmClock(AlarmClockInfo(t, pi), pi)` with the *same* PendingIntent
 *    in both slots, and `setAlarmClock()` pairs of two different apps sharing
 *    one PendingIntent cancel each other. With a dedicated show-intent this
 *    can no longer happen \u2014 **and** it is why the show-intent's request code
 *    is namespaced away from the firing intent's (see [showIntent]).
 *
 * ## The ordering this enforces
 *
 * `canScheduleExactAlarms()` \u2192 `setAlarmClock` (`setExactAndAllowWhileIdle`
 * with `allowWhileIdle = true`), and on refusal, in order of preference:
 * `setExactAndAllowWhileIdle` \u2192 `setAndAllowWhileIdle` \u2192 `setWindow`.
 * Every rung still fires *without* an exact-alarm grant, so a missing grant
 * degrades timing instead of deleting the reminder.
 *
 * Plain (non-`allowWhileIdle`) `setExact` is deliberately **never** used: it
 * is deferred until the next Doze maintenance window \u2014 up to ~15 minutes of
 * silence in the exact scenario users complain about most (screen off, phone
 * asleep, \"walk at 8:45 PM\").
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /**
     * Offset added to a show-intent's request code so it can never collide
     * with the firing PendingIntent's request code for the same alarm.
     */
    private const val SHOW_REQUEST_CODE_OFFSET = 900_000

    /** 15-minute window used by the last-resort inexact fallback. */
    const val WINDOW_MILLIS = 15L * 60L * 1000L

    /** Where an alarm's \"how late can this be?\" answer lands, for the UI. */
    enum class Precision { EXACT, APPROXIMATE }

    /** True when this OS version gates exact alarms behind a user grant. */
    fun exactAlarmsGated(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Whether the app may currently schedule exact alarms. Pre-API-31 always
     * true; from API 31 on, the real `canScheduleExactAlarms()` answer, with a
     * `SecurityException` treated as \"no\" instead of propagating.
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
     * the strongest mechanism this device currently allows.
     *
     * @param allowWhileIdle whether the fallback rungs may use the
     *   `allowWhileIdle` variants. `true` for anything the user is *waiting*
     *   on (habit reminders, snooze, timer completion); the value is ignored
     *   by `setAlarmClock`, which is already Doze-exempt.
     */
    fun schedule(
        context: Context,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        wakeUp: Boolean = true,
        allowWhileIdle: Boolean = true,
        showIntent: PendingIntent? = null,
    ): Precision {
        val type = if (wakeUp) AlarmManager.RTC_WAKEUP else AlarmManager.RTC
        val canExact = canScheduleExact(context)
        val manager = alarmManager(context)

        if (canExact) {
            // Primary: the system-clock-grade alarm. Exempt from Doze and App
            // Standby batching, and what draws the status-bar alarm icon.
            val clockInfo = if (showIntent != null) {
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
            } else {
                AlarmManager.AlarmClockInfo(triggerAtMillis, null)
            }
            val ok = runCatching {
                manager.setAlarmClock(clockInfo, pendingIntent)
            }.onFailure {
                Log.w(TAG, "setAlarmClock rejected \u2014 falling through to exactWhileIdle", it)
            }.isSuccess
            if (ok) return Precision.EXACT
        }

        if (allowWhileIdle) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val ok = runCatching {
                    manager.setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
                }.onFailure {
                    Log.w(TAG, "setExactAndAllowWhileIdle rejected \u2014 falling through", it)
                }.isSuccess
                if (ok) return if (canExact) Precision.EXACT else Precision.APPROXIMATE
            }
            val ok = runCatching {
                manager.setAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
            }.onFailure {
                Log.w(TAG, "setAndAllowWhileIdle rejected \u2014 falling through", it)
            }.isSuccess
            if (ok) return Precision.APPROXIMATE
        }

        // Last resort: always available, no permission, may be postponed out
        // of Doze \u2014 but still *fires*, which beats a silently dropped alarm.
        runCatching {
            manager.setWindow(type, triggerAtMillis, WINDOW_MILLIS, pendingIntent)
        }.onFailure { Log.e(TAG, "Failed to schedule alarm on every fallback path", it) }

        return Precision.APPROXIMATE
    }

    /**
     * Cancels an alarm previously armed with [pendingIntent].
     *
     * Pass the **exact same** PendingIntent definition that was scheduled \u2014
     * extras included \u2014 because `AlarmManager` matches on
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
     * The Activity PendingIntent for an alarm's status-bar \"show\" slot.
     *
     * Namespaced by [SHOW_REQUEST_CODE_OFFSET] so it can never alias a firing
     * (broadcast) PendingIntent, and pointing at [MainActivity] with a
     * `CLEAR_TOP` launch so tapping the clock icon opens the running app
     * instead of stacking a new copy of it.
     */
    fun showIntent(
        context: Context,
        requestCode: Int,
        deepLink: Uri? = null,
    ): PendingIntent {
        val intent = Intent(context, com.rork.mindsetframestracker.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (deepLink != null) data = deepLink
        }
        return PendingIntent.getActivity(
            context,
            requestCode + SHOW_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Builds a broadcast PendingIntent for an alarm, or returns null when none
     * exists yet ([PendingIntent.FLAG_NO_CREATE]) \u2014 the safe shape for cancel
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
     * it sits in a restricted App Standby bucket (\"Rarely used\" / \"Restricted\").
     *
     * This is the *silent* half of the battery problem that the permission
     * dialog never mentioned: on stock Android, an app the user hasn't opened
     * recently gets bucketed, and alarms in a restricted bucket can be deferred
     * by hours \u2014 with `SCHEDULE_EXACT_ALARM` fully granted. Surfacing it
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

    /** Opens the system \"Alarms & reminders\" page for this app (API 31+). */
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

    /**
     * When the *system-wide* next alarm clock fires, or null when none is set.
     *
     * This is the only public read-back Android offers for "did my
     * `setAlarmClock` actually take?" — it is the same value the status-bar
     * alarm icon is drawn from, and it is maintained by the platform across
     * process death, so it reflects what `AlarmManager` really holds rather
     * than what the app last *asked* for. It is the in-app equivalent of
     * `adb shell dumpsys alarm`.
     *
     * Caveats that matter when interpreting it, and which are why it is a
     * diagnostic rather than a hard assertion:
     *
     *  - **User-wide, not per-app.** A Clock-app alarm set for later will
     *    win here. Only a trigger time that matches ours is evidence about
     *    *our* alarm.
     *  - **Only `setAlarmClock` registers here.** Our fallback rungs
     *    (`setExactAndAllowWhileIdle`, `setAndAllowWhileIdle`, `setWindow`)
     *    are deliberately invisible to it, so a null here does **not** mean
     *    no alarm is armed — only that no *alarm-clock* alarm is. Reporting
     *    a false failure off a null would be worse than reporting nothing,
     *    so callers must combine this with [canScheduleExact] before
     *    concluding anything.
     */
    fun nextArmedAlarmClock(context: Context): Long? =
        runCatching { alarmManager(context).nextAlarmClock?.triggerTime }.getOrNull()

    /**
     * True when [expectedTriggerMillis] (within a second) is the alarm
     * currently registered as the system's next alarm clock. See
     * [nextArmedAlarmClock] for what this does and does not prove.
     */
    fun isArmedAsNextAlarmClock(context: Context, expectedTriggerMillis: Long): Boolean {
        val armed = nextArmedAlarmClock(context) ?: return false
        return kotlin.math.abs(armed - expectedTriggerMillis) < 1_000L
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
