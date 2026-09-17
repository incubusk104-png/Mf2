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
import com.rork.mindsetframestracker.data.AlarmEventOutcome
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.HabitAlarmHistory
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.alarmMinutes
import com.rork.mindsetframestracker.ui.AppStrings

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
        showResult(context, habitId, habitName, reschedule = reschedule) is NotifyResult.Posted

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
    fun showResult(
        context: Context,
        habitId: String,
        habitName: String,
        alarmMinutes: Int? = null,
        reschedule: Boolean = true,
    ): NotifyResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // Re-arm BEFORE reporting: this occurrence will not reach the
                // user, but the next one must still be armed or the habit goes
                // permanently silent. See [rearmAfterUndeliveredRing].
                rearmAfterUndeliveredRing(context, habitId, habitName, alarmMinutes)
                return NotifyResult.PermissionMissing
            }
        }

        // The user's chosen language, resolved from a bare Context because this
        // runs inside the alarm's receiver with no Activity and no ViewModel.
        // Labels (actions, channel name) are translated; the motivational LINE
        // itself is the user's own words or the curated pack, never a
        // translation of them.
        val strings = NotificationStrings.resolve(context)

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
            rearmAfterUndeliveredRing(context, habitId, habitName, alarmMinutes)
            return NotifyResult.Blocked
        }

        return runCatching {
            ensureChannel(context, strings)

            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                rearmAfterUndeliveredRing(context, habitId, habitName, alarmMinutes)
                return NotifyResult.Blocked
            }

            // ── The motivating copy for THIS ring ───────────────────────────
            // The user's own line when they wrote one, otherwise the curated
            // pack for this habit (a water habit gets the hydration lines). Never
            // blank: HabitReminderText degrades to a generic encouragement
            // rather than to an empty notification body, which would render as a
            // reminder with nothing in it at all.
            val reminderLine = HabitReminderText.lineFor(
                context = context,
                habitId = habitId,
                iconId = HabitReminderText.iconIdFor(context, habitId),
                alarmMinutes = alarmMinutes,
            )
            val occurrenceLabel = HabitReminderText.subtitleFor(context, habitId, alarmMinutes)

            // ── Past the point of no return: this ring IS happening ────────
            // The alarm has cleared every gate above — permission held, channel
            // enabled, notification postable — so it is about to reach the user.
            // THAT is the moment to write it into the alarm history.
            //
            // Deliberately here and not in HabitReminderReceiver: a ring that was
            // suppressed by a missing permission or a muted channel never reached
            // the user, and recording it would put an alarm in the day's history
            // that never actually rang.
            //
            // Keyed by (habit, day, scheduled time). A habit ringing at 07:00,
            // 12:00 and 18:00 therefore leaves THREE events — nothing on this
            // path may collapse them to one per habit, which is precisely the
            // collapse this history exists to prevent.
            val effectiveAlarmMinutes = alarmMinutes
                ?: HabitReminderText.alarmTimesFor(context, habitId).firstOrNull()
            if (habitId != DIAGNOSTIC_HABIT_ID && effectiveAlarmMinutes != null) {
                runCatching {
                    HabitAlarmHistory.record(
                        context = context,
                        habitId = habitId,
                        dayKey = Dates.todayKey(),
                        scheduledMinutes = effectiveAlarmMinutes,
                        outcome = AlarmEventOutcome.FIRED,
                        // The line this occurrence actually delivered, so the
                        // history shows what the alarm said even after the user
                        // later edits or clears their message.
                        message = reminderLine,
                    )
                }.onFailure { Log.w(TAG, "Failed to record the ring for '$habitName'", it) }
            }

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentIntent = PendingIntent.getActivity(
                context, habitId.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // The occurrence travels with the snooze, so the receiver knows WHICH
            // of the habit's alarms the user tapped. Without it a snooze of the
            // 07:00 reminder re-armed "the habit", making the 12:00 and 18:00
            // alarms indistinguishable from it — and its history entry would
            // have been written against the wrong time.
            val snoozeIntent = Intent(context, HabitSnoozeReceiver::class.java).apply {
                putExtra("habitId", habitId)
                putExtra("habitName", habitName)
                alarmMinutes?.let { putExtra(HabitReminderReceiver.EXTRA_ALARM_MINUTES, it) }
            }
            // Request code derived from (habit, time) rather than the habit alone:
            // one code per habit with FLAG_UPDATE_CURRENT made snoozing one time
            // silently REPLACE another time's button, so a habit with three alarms
            // ended up with one shared snooze that always hit the last one armed.
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                HabitAlarmScheduler.requestCodeFor(
                    habitId,
                    alarmMinutes ?: HabitReminderReceiver.NO_ALARM_MINUTES,
                ),
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Full-screen intent: turns this from a heads-up notification (which
            // silent/Do-Not-Disturb/Bedtime modes can mute or dim entirely) into
            // an actual ringing alarm screen — this is the fix for "I set an
            // alarm but it never actually rang."
            // ── Ring at THIS occurrence ──
            // The alarm's own time travels on the intent so the record, the
            // notification identity and the re-arm all refer to the same
            // occurrence. Without it every one of the day's alarms was
            // indistinguishable from the others.
            val ringingIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                putExtra("habitId", habitId)
                putExtra("habitName", habitName)
                alarmMinutes?.let { putExtra(HabitReminderReceiver.EXTRA_ALARM_MINUTES, it) }
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
                // ── The motivational line ──────────────────────────────────
                // This is the whole point of the feature: the body is an
                // ENCOURAGEMENT, not the habit name restated. "Drink Water" as
                // the title plus "It's time to water up! 💧 Stay hydrated,
                // you've got this!" as the body is a reminder the user wants to
                // read; the old fixed "Time for your habit" was a bare alert
                // that said nothing about *this* habit.
                //
                // Resolved from storage HERE rather than carried on the alarm's
                // intent, so an armed alarm always delivers the message the user
                // has right now — see HabitReminderText for why that matters.
                .setContentText(reminderLine)
                // Without BigTextStyle the line is ellipsised in the collapsed
                // row and the encouraging half of the sentence is the half that
                // gets cut. This makes the full line readable on expand.
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminderLine))
                .setContentIntent(contentIntent)
                // Which occurrence this is ("18:00 · 3 of 3 today"), so a habit
                // that rings several times a day cannot read as one alarm
                // firing repeatedly. Left unset when there is nothing useful to
                // say rather than set to an empty string, which some OEM shades
                // render as a blank second line.
                .apply { if (occurrenceLabel.isNotBlank()) setSubText(occurrenceLabel) }
                .setAutoCancel(true)
                // Ensure heads-up display + sound on all API levels
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                // Vibrate pattern for attention
                .setVibrate(longArrayOf(0, 250, 100, 250))
                .addAction(0, strings.notifHabitSnooze, snoozePendingIntent)
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
                alarmMinutes = alarmMinutes,
            )?.let { stopIntent -> notificationBuilder.addAction(0, strings.notifHabitStop, stopIntent) }
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

            // ── Record the occurrence that just rang ───────────────────────
            // Previously this marked the day done unconditionally, which was
            // wrong twice over:
            //
            //  * It marked the habit done for ANY habit whose alarm rang —
            //    including a JOURNAL habit, where a dismissal cannot possibly
            //    mean "I wrote my entry", and a TOOL habit, whose measurement
            //    has not happened yet. The user's habit was ticked for doing
            //    nothing. It also contradicted the sheet's own behavior: a
            //    CHECK habit logged an entry when answered in the sheet but
            //    got a bare check-in when answered by dismissal.
            //  * It wrote nothing at all about WHAT or WHEN, so with several
            //    alarms a day there was no way to tell which occurrence had
            //    been answered.
            //
            // HabitAlarmRecords.recordRingOccurrence decides per behavior: it
            // records only the ONE_TAP case (where the dismissal genuinely IS
            // the completion — "Take a vitamin" plus the ✓ the user hears is
            // the record), and deliberately records nothing for a habit whose
            // input only its sheet or timer can supply. "No completion without
            // a record" is honoured there by NOT marking the completion, rather
            // than by inventing a record.
            //
            // The diagnostic test button is not a real habit — never write for
            // it (see DIAGNOSTIC_HABIT_ID doc above).
            if (habitId != DIAGNOSTIC_HABIT_ID) {
                runCatching {
                    HabitAlarmRecords.recordRingOccurrence(context, habitId, alarmMinutes)
                }.onFailure { Log.w(TAG, "Failed to record the occurrence for '$habitName'", it) }
            }

            // ── Re-arm ONLY this time ──────────────────────────────────────
            // Per-time, not per-habit: when the 07:00 alarm fires, the 12:00 and
            // 18:00 alarms are separate live entries and must be left alone.
            // Re-arming the whole habit here would push the later ones a day
            // forward and the user would silently lose them. Falls back to the
            // habit's first time for a re-fire whose intent predates multi-time
            // alarms.
            if (reschedule) {
                val nextMinutes = alarmMinutes
                    ?: com.rork.mindsetframestracker.data.MindsetRepository(context)
                        .load().habits.firstOrNull { it.id == habitId }
                        ?.alarmMinutes?.firstOrNull()
                if (nextMinutes != null) {
                    // The fired time is passed explicitly: scheduleNext re-arms
                    // THIS occurrence only, leaving the habit's other times
                    // untouched (see its own note). It refuses if the time was
                    // edited away in the meantime, which is the correct
                    // no-op — the editor's schedule() already owns the new set.
                    HabitAlarmScheduler.scheduleNext(context, habitId, habitName, nextMinutes)
                } else {
                    // The habit was deleted (or its alarms cleared) between the
                    // ring and this re-arm, so there is nothing left to arm.
                    Log.d(TAG, "No alarm time to re-arm for '$habitName'")
                }
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

    /**
     * Re-arms the NEXT occurrence after a ring that could not be delivered.
     *
     * ## The bug this closes
     *
     * The re-arm used to sit at the very END of the successful path only, after
     * every gate (POST_NOTIFICATIONS granted, notifications enabled, channel
     * unmuted). Every early return — `PermissionMissing`, and `Blocked` (both
     * the notifications-disabled case and the muted-channel case) — therefore
     * skipped it. Since each habit alarm is a one-shot `AlarmManager` entry that
     * only re-arms itself *after* it fires, a skipped re-arm meant the habit
     * **never rang again**: not tomorrow, not ever, until the app was reinstalled
     * or every alarm was rescheduled by hand. For a habit tracker that is the
     * worst possible failure, and it is silent — the only thing that would have
     * told the user was the alarm that no longer exists.
     *
     * A user fixing their notification settings an hour later would find nothing
     * to fix: the alarm that should have prompted them was already gone.
     *
     * ## Why this is safe
     *
     * [HabitAlarmScheduler.scheduleNext] is idempotent per `(habit, time)` — it
     * arms the next occurrence under the same request code with
     * `FLAG_UPDATE_CURRENT`, so a later successful ring re-arming the same
     * occurrence replaces that entry instead of adding a duplicate. The user can
     * therefore never end up with two alarms for one reminder.
     */
    private fun rearmAfterUndeliveredRing(
        context: Context,
        habitId: String,
        habitName: String,
        alarmMinutes: Int?,
    ) {
        // The diagnostic test button is not a real habit — never arm for it.
        if (habitId == DIAGNOSTIC_HABIT_ID) return
        runCatching {
            val habit = MindsetRepository(context).load().habits.firstOrNull { it.id == habitId }
                ?: return
            // The occurrence that failed to post, or the habit's first time for a
            // re-fire whose intent predates multi-time alarms.
            val minutes = alarmMinutes ?: habit.alarmMinutes.firstOrNull() ?: return
            HabitAlarmScheduler.scheduleNext(context, habitId, habitName, minutes)
            Log.w(
                TAG,
                "Reminder for '$habitName' could not be posted, but its next occurrence was " +
                    "still armed — the habit keeps ringing",
            )
        }.onFailure { Log.w(TAG, "Could not re-arm the undelivered reminder for '$habitName'", it) }
    }

    /**
     * The string table is passed in rather than resolved here: [showResult]
     * already resolved it, and re-reading `settings.language` out of the blob
     * once more per notification would be a second parse on the alarm's critical
     * path for no benefit.
     */
    private fun ensureChannel(context: Context, strings: AppStrings) {
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
            strings.notifHabitChannelName,
            NotificationManager.IMPORTANCE_HIGH,  // heads-up + sound + vibrate
        ).apply {
            description = strings.notifHabitChannelDesc
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
