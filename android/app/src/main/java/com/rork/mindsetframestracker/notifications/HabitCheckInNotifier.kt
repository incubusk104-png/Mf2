package com.rork.mindsetframestracker.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rork.mindsetframestracker.MainActivity
import com.rork.mindsetframestracker.R

object HabitCheckInNotifier {

    private const val TAG = "HabitCheckInNotifier"
    const val CHANNEL_ID = "habit_reminder"
    private const val NOTIFICATION_ID_BASE = 3000

    /**
     * habitId used by the "Send a test reminder now" diagnostic button in
     * AlarmPermissionPromptDialog. It is NOT a real habit and NOT a valid
     * UUID.
     *
     * BUG FIX: showResult() used to call markHabitDoneToday() for this id
     * unconditionally, same as any real habit. That wrote a "diagnostic_test"
     * key into the local checkIns map, and SupabaseSync.pushSnapshot() later
     * tried to upsert it into the `checkins` table, whose habit_id column is
     * type uuid — Postgres rejected it with
     * `invalid input syntax for type uuid: "diagnostic_test"` on every sync
     * from then on. Because pushSnapshot() returns on the first failed
     * upsert, this didn't just fail check-in syncing — it silently blocked
     * settings/mood/backup syncing too, for good, until the bad key was
     * removed. Recognizing this id and skipping the write fixes it at the
     * source; MindsetRepository.load() also strips any pre-existing bad
     * entry so already-affected installs self-heal without clearing data.
     */
    const val DIAGNOSTIC_HABIT_ID = "diagnostic_test"

    /** Stable notification id for a given habit — shared with snooze/cancel logic. */
    fun notificationId(habitId: String): Int = NOTIFICATION_ID_BASE + habitId.hashCode()

    /** Outcome of a [showResult] call — lets a caller (like the diagnostic
     * "Send a test reminder now" button) tell the user EXACTLY what happened
     * instead of a generic true/false. */
    sealed class NotifyResult {
        /**
         * The system accepted the notification with no error — but that is
         * NOT the same as "the user will actually see it." Two independent
         * gaps can each hide it even after every other check passes:
         *
         * [doNotDisturbActive]: Do Not Disturb / a Focus mode can be on at
         * the OS/OEM level (a crossed-out bell icon in the status bar) and
         * hide a notification from the shade entirely, even one on an
         * IMPORTANCE_HIGH / CATEGORY_ALARM channel — the alarm audio-stream
         * trick only guarantees the SOUND bypasses silent mode, not that
         * every OEM's DND implementation renders the visual entry.
         *
         * [fullScreenIntentUnavailable]: On Android 14+ (API 34), attaching
         * a full-screen intent (what turns this into an actual ringing
         * alarm screen instead of a plain heads-up) requires the separate
         * USE_FULL_SCREEN_INTENT special-access permission, granted via its
         * own dedicated system settings screen — completely independent of
         * POST_NOTIFICATIONS, areNotificationsEnabled(), and channel
         * importance, all of which can be fully granted while this one is
         * not. AOSP is supposed to silently fall back to a normal heads-up
         * notification when it's missing, but some OEM builds (including
         * some HyperOS builds) have been observed dropping the notification
         * entirely instead of degrading gracefully. This case is only
         * checked, and the full-screen intent only attached, when the
         * permission is actually granted — otherwise a plain notification
         * (no full-screen intent) is still built and posted, so a missing
         * grant here can no longer make the whole reminder disappear.
         */
        data class Posted(
            val doNotDisturbActive: Boolean,
            val fullScreenIntentUnavailable: Boolean = false,
        ) : NotifyResult()
        object PermissionMissing : NotifyResult()
        /**
         * POST_NOTIFICATIONS is granted, but the app (or specifically the
         * "Habit Reminders" channel) has been turned off at the system
         * level — via Settings > Apps > notifications, or a MIUI-specific
         * notification management screen. Android does NOT revoke the
         * runtime permission when this happens, so this can only be
         * detected separately from the permission check, and only right
         * before actually posting.
         */
        object Blocked : NotifyResult()
        data class Failed(val error: String) : NotifyResult()
    }

    /**
     * Posts the reminder. Returns false (and posts nothing) when the app
     * can't show notifications at all, OR when building/posting the
     * notification itself throws for any reason — see [showResult] for the
     * distinction and the actual error text.
     */
    fun show(context: Context, habitId: String, habitName: String, reschedule: Boolean = true): Boolean =
        showResult(context, habitId, habitName, reschedule) is NotifyResult.Posted

    /**
     * BUG FIX: previously the entire notification-building/posting body ran
     * with NO try/catch. If it ever threw — a bad icon resource, a null
     * Uri from RingtoneManager, anything — the exception propagated all the
     * way up through the caller (e.g. the "Send a test reminder now" button,
     * or [HabitReminderReceiver]'s own runCatching, which only wraps ITS
     * call to [show], not this function's internals) and could crash the
     * app or the broadcast dispatch silently, with the ONLY visible symptom
     * being "I tapped the button and literally nothing happened" — no
     * notification, no toast, no obvious crash dialog if the OS recovered
     * quickly. Wrapping the whole body here means a failure is now always
     * captured as a [NotifyResult.Failed] with the real exception text
     * instead of an invisible crash.
     */
    fun showResult(context: Context, habitId: String, habitName: String, reschedule: Boolean = true): NotifyResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return NotifyResult.PermissionMissing
        }

        // BUG FIX: POST_NOTIFICATIONS being granted is necessary but NOT
        // sufficient. If the app's notifications get turned off from system
        // settings after that permission was granted — including MIUI's own
        // notification-management screen, which is separate from the
        // Android permission — Android does NOT revoke the permission
        // grant. The check above still passes, ensureChannel() below still
        // succeeds, and manager.notify() further down returns completely
        // normally with no exception. The system just silently discards the
        // notification before it reaches the shade. That is exactly "the
        // app said it sent successfully but nothing appears." Checking
        // NotificationManagerCompat.areNotificationsEnabled() — and, once
        // the channel exists, that channel's own importance — is the only
        // way to catch this instead of wrongly reporting success.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return NotifyResult.Blocked
        }

        return runCatching {
            ensureChannel(context)

            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return NotifyResult.Blocked
            }

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentIntent = PendingIntent.getActivity(
                context, habitId.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val snoozeIntent = Intent(context, HabitSnoozeReceiver::class.java).apply {
                putExtra("habitId", habitId)
                putExtra("habitName", habitName)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context, habitId.hashCode(), snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Full-screen intent: turns this from a heads-up notification (which
            // silent/Do-Not-Disturb/Bedtime modes can mute or dim entirely) into
            // an actual ringing alarm screen — this is the fix for "I set an
            // alarm but it never actually rang."
            val ringingIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                putExtra("habitId", habitId)
                putExtra("habitName", habitName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            val ringingPendingIntent = PendingIntent.getActivity(
                context, NOTIFICATION_ID_BASE + habitId.hashCode(), ringingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // BUG FIX: USE_FULL_SCREEN_INTENT is a separate special-access
            // permission on API 34+, independent of every check already
            // done above (POST_NOTIFICATIONS, areNotificationsEnabled,
            // channel importance). AOSP is documented to silently downgrade
            // to a normal heads-up notification when it's missing — but
            // some OEM builds instead drop the notification entirely rather
            // than degrading gracefully. Checking canUseFullScreenIntent()
            // and only attaching .setFullScreenIntent() when it is actually
            // granted means a missing grant can, at worst, cost the
            // ring-like full-screen behavior — never the whole notification.
            val canUseFullScreenIntent = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                manager.canUseFullScreenIntent()

            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                // A flat, alpha-only vector, NOT `splash_icon` (a <layer-list>
                // whose <bitmap> layer points at an anydpi-v26 adaptive icon,
                // which BitmapDrawable cannot inflate) and NOT a full-colour
                // launcher icon. Building the notification with an invalid
                // small icon throws here — inside the alarm's own receiver.
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(habitName)
                .setContentText("Time for your habit")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                // Ensure heads-up display + sound on all API levels
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                // Vibrate pattern for attention
                .setVibrate(longArrayOf(0, 250, 100, 250))
                .addAction(0, "Snooze 5 min", snoozePendingIntent)
            // ── The manual "Stop alarm" action ──────────────────────────────
            // Sits beside Snooze so a ringing alarm can be silenced from the shade
            // alone — without unlocking the phone, and without depending on the
            // ringing screen being launchable (Android 14+ revokes
            // USE_FULL_SCREEN_INTENT by default, so it frequently never appears).
            // Built through the guarded helper, so a failure to construct the
            // PendingIntent drops the button rather than throwing inside this
            // receiver — which runs at the exact moment the alarm fires.
            AlarmStopReceiver.stopPendingIntent(
                context = context,
                requestCode = notificationId(habitId) + 1,
                habitId = habitId,
                habitName = habitName,
            )?.let { stopIntent -> notificationBuilder.addAction(0, "Stop alarm", stopIntent) }
            if (canUseFullScreenIntent) {
                // Wakes the screen and rings even through silent/DND/Bedtime
                // mode on devices that allow full-screen alarm intents.
                notificationBuilder.setFullScreenIntent(ringingPendingIntent, true)
            }
            val notification = notificationBuilder.build()

            manager.notify(notificationId(habitId), notification)

            // ── Ring ─────────────────────────────────────────────────────
            // The alarm sound is started from here, the notification path, and
            // NOT from AlarmRingingActivity. That distinction is the whole fix:
            // the full-screen intent below is only attached when
            // canUseFullScreenIntent() is granted, and on Android 14+ that
            // permission is revoked by default for apps that aren't primarily
            // alarms — the notification still posts, but with no ringing screen.
            // While the audio lived inside that screen, a missing grant meant
            // the reminder appeared and made no sound at all. Now the sound is
            // independent of it: at worst the user loses the full-screen UI,
            // never the ring.
            AlarmRingService.start(
                context,
                habitId = habitId,
                eventId = null,
            )

            // Record whether the OS will actually DELIVER it. notify() returns
            // normally even when the notification is silently dropped (app
            // notifications off, or the channel set to NONE), so a clean call
            // here is not evidence the user saw or heard anything \u2014 this
            // line is what makes the difference visible in a bug report
            // instead of leaving "I set it and nothing happened".
            if (habitId != DIAGNOSTIC_HABIT_ID) {
                val delivery = AlarmDelivery.audit(context, CHANNEL_ID)
                when {
                    !delivery.canRing -> Log.w(
                        TAG,
                        "Reminder for '$habitName' was posted but will NOT reach the user: " +
                            AlarmDelivery.describe(context, CHANNEL_ID),
                    )
                    delivery.needsAttention -> Log.i(
                        TAG,
                        "Reminder for '$habitName' posted with degraded delivery: " +
                            AlarmDelivery.describe(context, CHANNEL_ID),
                    )
                    else -> Log.d(TAG, "Reminder for '$habitName' posted; delivery path is clear")
                }
            }

            // Finalize today's check-in the moment the alarm actually rings
            // — this is the "record" the user's habit-tracking is built on,
            // not a guess made back when they merely picked a time. Kept in
            // its own runCatching: if persistence ever hiccups, the
            // notification the user actually sees/hears should still count
            // as successfully posted.
            //
            // The diagnostic test button is not a real habit — never write a
            // check-in for it (see DIAGNOSTIC_HABIT_ID doc above).
            if (habitId != DIAGNOSTIC_HABIT_ID) {
                runCatching {
                    com.rork.mindsetframestracker.data.MindsetRepository(context).markHabitDoneToday(habitId)
                }.onFailure { Log.w(TAG, "Failed to record check-in for '$habitName' at ring-time", it) }
            }

            if (reschedule) {
                HabitAlarmScheduler.scheduleNext(context, habitId, habitName)
            }
        }.fold(
            onSuccess = {
                val dndActive = manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                val fsiUnavailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    !manager.canUseFullScreenIntent()
                NotifyResult.Posted(doNotDisturbActive = dndActive, fullScreenIntentUnavailable = fsiUnavailable)
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to post reminder for '$habitName'", error)
                NotifyResult.Failed("${error.javaClass.simpleName}: ${error.message}")
            },
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Delete the old channel whenever its sound stream changed so the
        // upgrade takes effect — Android ignores importance/sound changes to
        // an existing channel; the only way to change them is delete + recreate.
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null &&
            (existing.importance < NotificationManager.IMPORTANCE_HIGH ||
                existing.audioAttributes?.usage != AudioAttributes.USAGE_ALARM)
        ) {
            manager.deleteNotificationChannel(CHANNEL_ID)
        }

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Habit Reminders",
            NotificationManager.IMPORTANCE_HIGH,  // heads-up + sound + vibrate
        ).apply {
            description = "Individual habit reminders that fire at the time you set"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 100, 250)
            // RING, don't just buzz. IMPORTANCE_HIGH alone does NOT bypass
            // Do Not Disturb on most OEM skins — the channel also has to
            // declare that it carries alarms, which is what makes it
            // eligible for DND's alarm exception and gives it a real alarm
            // ringtone instead of the default notification blip. Without
            // this the channel was HIGH-severity but still "just a
            // notification", so a phone left in DND or Bedtime mode
            // overnight silently swallowed the reminder. `setBypassDnd`
            // is deliberately NOT used: it needs a notification-policy
            // access grant the app doesn't hold, and asking for one is a
            // worse UX than the user simply setting an alarm sound.
            // NOTE: there is deliberately no setAudioAttributes() call here.
            // NotificationChannel exposes no such method (only setSound(Uri,
            // AudioAttributes) and getAudioAttributes()), so the call that used
            // to sit here never compiled. The sound *stream* is set by the
            // setSound(...) below, which is where USAGE_ALARM — i.e. alarm
            // volume, unaffected by silent mode / DND — actually comes from.
            // USAGE_ALARM plays on the phone's Alarm volume, which most
            // "silent mode" / Do Not Disturb / Bedtime toggles leave
            // untouched — this is what makes the reminder actually ring
            // instead of getting silently swallowed like a normal notification.
            setSound(
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * One-shot dump of every flag that can independently hide a habit
     * reminder, across every layer that's had to be checked one at a time
     * across previous rounds of debugging: runtime permission, app/channel
     * notification settings, Do Not Disturb, the Android 14+
     * full-screen-intent grant, exact-alarm scheduling, and battery
     * optimization. Every one of these can be off while every other one is
     * on — a green checkmark on one setting says nothing about the rest.
     *
     * Deliberately placed here rather than in its own file: a standalone
     * "AlarmDiagnostics.kt" file has repeatedly failed to make it into the
     * actual build even though it appeared to exist in exports of the
     * project, so this lives inside a file that has synced correctly every
     * time so far.
     */
    fun diagnosticsReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Mindset Frames alarm diagnostics")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // not applicable before API 33
        }
        sb.appendLine("POST_NOTIFICATIONS granted: $postNotificationsGranted")

        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        sb.appendLine("App notifications enabled (system-level toggle): $appNotificationsEnabled")

        val channelForReport = notifManager.getNotificationChannel(CHANNEL_ID)
        if (channelForReport == null) {
            sb.appendLine("\"Habit Reminders\" channel: not created yet (will be created on first reminder)")
        } else {
            sb.appendLine("\"Habit Reminders\" channel importance: ${importanceName(channelForReport.importance)}")
            sb.appendLine("\"Habit Reminders\" channel sound usage: ${channelForReport.audioAttributes?.usage ?: "none"}")
        }

        val interruptionFilter = notifManager.currentInterruptionFilter
        sb.appendLine("Do Not Disturb / Focus mode active: ${interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL} (filter=${filterName(interruptionFilter)})")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sb.appendLine("Full-screen intent (ringing alarm screen) allowed: ${notifManager.canUseFullScreenIntent()}")
        } else {
            sb.appendLine("Full-screen intent allowed: yes (not gated before Android 14)")
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.appendLine("Exact alarms allowed: ${alarmManager.canScheduleExactAlarms()}")
        } else {
            sb.appendLine("Exact alarms allowed: yes (not gated before Android 12)")
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        sb.appendLine("Battery optimization disabled for this app: $ignoringBatteryOptimizations")

        return sb.toString()
    }

    private fun importanceName(importance: Int): String = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> "NONE (blocked)"
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        NotificationManager.IMPORTANCE_HIGH -> "HIGH"
        NotificationManager.IMPORTANCE_MAX -> "MAX"
        else -> "UNKNOWN ($importance)"
    }

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL (DND off)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE (total silence)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS only"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "UNKNOWN"
        else -> "UNKNOWN ($filter)"
    }
}
