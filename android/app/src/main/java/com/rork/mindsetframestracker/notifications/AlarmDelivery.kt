package com.rork.mindsetframestracker.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * The one place that answers the only question that matters when a reminder
 * "doesn't ring": **is there actually a path from this alarm to the user?**
 *
 * Setting an alarm writes an `AlarmManager` entry, and that part is almost
 * never what fails — [AlarmScheduler] and [HabitAlarmScheduler] were already
 * careful about it, including the Doze-safe fallback ladder. What fails is
 * *delivery*. The sequence is:
 *
 * 1. the OS fires our `BroadcastReceiver` **exactly on time**,
 * 2. the receiver builds the notification and hands it to the system,
 * 3. the system **silently discards it** because one of several independent
 *    switches is off,
 * 4. nothing is thrown, nothing is logged where the user can see it, and the
 *    app's own snackbar has already promised "Alarm set — daily at 7:00 AM."
 *
 * Step 3 is the whole bug: notifications off at the app level, the
 * `habit_reminder` channel set to `IMPORTANCE_NONE`, "Alarms & reminders"
 * revoked after the alarm was armed, or an aggressive OEM battery manager
 * holding the app back. Every one of them is invisible to the arming code,
 * invisible to `canScheduleExactAlarms()`, and independent of the others — a
 * green checkmark on one tells you nothing about the rest.
 *
 * So arming and delivery are audited **separately** here: [audit] reports
 * every switch that can independently hide a reminder, letting the UI name
 * the exact reason instead of leaving the user with a silent phone and a
 * settings screen that looks fine.
 */
object AlarmDelivery {

    /** One independent reason a reminder can fail to reach the user. */
    enum class Blocker {
        /** App notifications are off at the system level. */
        NOTIFICATIONS_OFF,

        /** The reminder channel itself is set to IMPORTANCE_NONE. */
        CHANNEL_OFF,

        /**
         * `SCHEDULE_EXACT_ALARM` was revoked after the alarm was armed.
         * Degrades timing (up to 15 min) rather than silencing the alarm —
         * which is exactly why it needs to be reported here: the fallback
         * ladder means a revoked grant no longer *loses* the alarm, so the
         * only symptom left is lateness the user would blame on the app.
         */
        EXACT_ALARMS_REVOKED,

        /** Battery optimisation is on; OEM managers can defer late alarms. */
        BATTERY_OPTIMIZED,

        /** Android 14+: no full-screen intent, so no ringing alarm screen. */
        FULL_SCREEN_INTENT_OFF,

        /** Do Not Disturb / a Focus mode is active. Advisory, never blocking. */
        DO_NOT_DISTURB,
    }

    /**
     * Snapshot of every delivery switch. [canRing] is the honest answer to
     * "if this alarm fires right now, will the user notice?" — and the only
     * thing the notification layer should be judged on.
     */
    data class Audit(
        val notificationsEnabled: Boolean,
        val channelDisabled: Boolean,
        val exactAlarmsAllowed: Boolean,
        val batteryOptimized: Boolean,
        val fullScreenIntentAllowed: Boolean,
        val doNotDisturb: Boolean,
    ) {
        val blockers: List<Blocker>
            get() = buildList {
                if (!notificationsEnabled) add(Blocker.NOTIFICATIONS_OFF)
                if (channelDisabled) add(Blocker.CHANNEL_OFF)
                if (!exactAlarmsAllowed) add(Blocker.EXACT_ALARMS_REVOKED)
                if (batteryOptimized) add(Blocker.BATTERY_OPTIMIZED)
                if (!fullScreenIntentAllowed) add(Blocker.FULL_SCREEN_INTENT_OFF)
                if (doNotDisturb) add(Blocker.DO_NOT_DISTURB)
            }

        /** Blockers that make the reminder *completely* silent. */
        val silencingBlockers: List<Blocker>
            get() = blockers.filter {
                it == Blocker.NOTIFICATIONS_OFF || it == Blocker.CHANNEL_OFF
            }

        /** True when a fired alarm would actually reach the user. */
        val canRing: Boolean get() = silencingBlockers.isEmpty()

        /** True when anything — even a timing/UX degradation — is off. */
        val needsAttention: Boolean get() = blockers.isNotEmpty()

        /**
         * The single most important sentence to show the user, or null when
         * the alarm is fully healthy. Deliberately concrete: it names the
         * consequence, not the setting.
         */
        val headline: String?
            get() = when {
                !notificationsEnabled ->
                    "This alarm will NOT ring: notifications are switched off for this app."
                channelDisabled ->
                    "This alarm will NOT ring: the \"Habit Reminders\" notification channel is turned off."
                else -> null
            }
    }

    /** Short human name for a blocker, for a list row. */
    fun label(blocker: Blocker): String = when (blocker) {
        Blocker.NOTIFICATIONS_OFF -> "Notifications off"
        Blocker.CHANNEL_OFF -> "\"Habit Reminders\" channel off"
        Blocker.EXACT_ALARMS_REVOKED -> "Exact alarm timing revoked"
        Blocker.BATTERY_OPTIMIZED -> "Battery optimization on"
        Blocker.FULL_SCREEN_INTENT_OFF -> "No full-screen alarm"
        Blocker.DO_NOT_DISTURB -> "Do Not Disturb / Focus on"
    }

    /** One-line explanation of *why it matters*, for a list row. */
    fun detail(blocker: Blocker): String = when (blocker) {
        Blocker.NOTIFICATIONS_OFF ->
            "The OS accepts the reminder and then discards it. No error, nothing in the shade, nothing in the log."
        Blocker.CHANNEL_OFF ->
            "Turned off for this channel specifically — the app permission can still read as granted."
        Blocker.EXACT_ALARMS_REVOKED ->
            "Reminders still fire, but can arrive up to 15 minutes late."
        Blocker.BATTERY_OPTIMIZED ->
            "Xiaomi, Samsung, Oppo, Honor and similar phones can defer or kill scheduled alarms unless this app is exempted."
        Blocker.FULL_SCREEN_INTENT_OFF ->
            "Android 14+: the reminder shows as a quiet notification instead of a ringing alarm screen."
        Blocker.DO_NOT_DISTURB ->
            "The reminder is posted, but can be hidden from the shade while this is on."
    }

    /**
     * Reads every switch. Cheap enough to call on each foreground resume —
     * which matters, because the interesting cases are precisely the ones
     * that change *while the app is closed* (the user revoking exact alarms,
     * an OEM battery manager flipping a setting, a Focus mode engaging
     * overnight).
     */
    fun audit(context: Context, channelId: String = HabitCheckInNotifier.CHANNEL_ID): Audit {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // App-level toggle first: it exists on every API level, and
        // notify() no-ops completely and silently when it is off — no
        // exception, nothing in the shade.
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

        // A channel's importance is the ONLY place a per-channel block is
        // visible; canUseFullScreenIntent()/areNotificationsEnabled() both
        // still read as fine while the channel itself is set to NONE.
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.getNotificationChannel(channelId)
        } else {
            null
        }
        val channelDisabled = channel != null &&
            channel.importance == NotificationManager.IMPORTANCE_NONE

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimized = runCatching {
            !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        val fullScreenIntentAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
        } else {
            true
        }

        val doNotDisturb = runCatching {
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)

        return Audit(
            notificationsEnabled = notificationsEnabled,
            channelDisabled = channelDisabled,
            exactAlarmsAllowed = AlarmScheduler.canScheduleExact(context),
            batteryOptimized = batteryOptimized,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            doNotDisturb = doNotDisturb,
        )
    }

    /**
     * True when the alarm that was just armed cannot be trusted to ring, or
     * is materially degraded. Drives "should we tell the user *now*?" at the
     * moment they set an alarm — the only moment they still have the context
     * to act on it.
     */
    fun requiresRepair(context: Context, channelId: String = HabitCheckInNotifier.CHANNEL_ID): Boolean =
        audit(context, channelId).needsAttention

    /**
     * Plain-text dump for `adb logcat`, so a "still not ringing" report can
     * be diagnosed from the log rather than a screenshot of five settings
     * screens. Pairs with [HasNotificationPermission]'s sibling diagnostic
     * report, which covers the same ground for the habit-reminder path.
     */
    fun describe(context: Context, channelId: String = HabitCheckInNotifier.CHANNEL_ID): String {
        val audit = audit(context, channelId)
        return buildList {
            add("alarm delivery: canRing=${audit.canRing}")
            add("notificationsEnabled=${audit.notificationsEnabled}")
            add("channelDisabled=${audit.channelDisabled}")
            add("exactAlarmsAllowed=${audit.exactAlarmsAllowed}")
            add("batteryOptimized=${audit.batteryOptimized}")
            add("fullScreenIntentAllowed=${audit.fullScreenIntentAllowed}")
            add("doNotDisturb=${audit.doNotDisturb}")
        }.joinToString(", ")
    }
}
