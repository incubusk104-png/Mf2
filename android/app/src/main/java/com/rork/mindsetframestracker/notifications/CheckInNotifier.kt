package com.rork.mindsetframestracker.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rork.mindsetframestracker.MainActivity
import com.rork.mindsetframestracker.R
import org.json.JSONObject
import java.io.File

/**
 * Builds and posts the daily check-in notification.
 *
 * Shared between [CheckInReceiver] (fired by the AlarmManager alarm) and the
 * preview action in Settings, so the preview is always identical to the real
 * reminder. When [show]'s preview flag is set, the notification carries a
 * small "Preview" tag so it isn't mistaken for a scheduled reminder.
 *
 * When a streak is active, the notification copy shifts to loss-aversion
 * messaging to motivate the user to check in before the day ends and break
 * their streak.
 */
object CheckInNotifier {

    const val CHANNEL_ID = "daily_check_in"
    const val NOTIFICATION_ID = 2001

    private const val TAG = "CheckInNotifier"
    private const val PREFS_NAME = "mindset_frames"
    private const val KEY_DATA = "app_data"

    fun show(context: Context, preview: Boolean = false) {
        val s = NotificationStrings.resolve(context)
        ensureChannel(context, s.ntfChannelCheckInName, s.ntfChannelCheckInDesc)

        val streakInfo = getStreakInfo(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title: String
        val text: String
        val bigText: String

        if (streakInfo.totalHabits == 0) {
            // Companion voice — no habits configured yet. Instead of a generic
            // reminder, ask what they're working on and offer to build it
            // together. Humans respond to curiosity, not commands.
            title = s.ntfCheckInEmptyTitle
            text = s.ntfCheckInEmptyText
            bigText = s.ntfCheckInEmptyBig
        } else if (streakInfo.missedYesterday && !streakInfo.checkedToday) {
            // Empathetic voice — yesterday slipped by. Sad but never guilt-y;
            // the message always lands on encouragement and a fresh start.
            title = s.ntfCheckInMissedTitle
            text = s.ntfCheckInMissedText
            bigText = s.ntfCheckInMissedBig
        } else if (streakInfo.checkInStreak > 0 && !streakInfo.checkedToday) {
            // Streak-loss-aversion messaging — user has a streak to protect
            val days = streakInfo.checkInStreak
            title = String.format(s.ntfCheckInStreakTitle, days)
            text = s.ntfCheckInStreakText
            bigText = when {
                days >= 30 -> String.format(s.ntfCheckInStreakBig30, days)
                days >= 7 -> String.format(s.ntfCheckInStreakBig7, days)
                else -> String.format(s.ntfCheckInStreakBigLow, days)
            }
        } else if (streakInfo.checkedToday) {
            // Already checked in today — friendly encouragement
            title = s.ntfCheckInDoneTitle
            text = s.ntfCheckInDoneText
            bigText = s.ntfCheckInDoneBig
        } else {
            // No active streak — standard reminder
            title = s.ntfCheckInDefaultTitle
            text = s.ntfCheckInDefaultText
            bigText = s.ntfCheckInDefaultBig
        }

        val displayBigText = if (preview) {
            "$bigText\n\n${s.ntfPreviewNote}"
        } else {
            bigText
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(displayBigText)
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .apply { if (preview) setSubText(s.ntfPreviewTag) }
            .build()

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private data class StreakInfo(
        val checkInStreak: Int,
        val checkedToday: Boolean,
        /** Number of habits the user currently tracks (0 = empty list). */
        val totalHabits: Int = 1,
        /** True when history exists but yesterday had no check-ins at all. */
        val missedYesterday: Boolean = false,
    )

    /**
     * Reads the local app data JSON to compute the current streak and whether
     * the user has already checked in today. This avoids needing the full
     * repository in a BroadcastReceiver context.
     */
    private fun getStreakInfo(context: Context): StreakInfo {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null) ?: return StreakInfo(0, false)
            val json = JSONObject(jsonStr)

            val totalHabits = json.optJSONArray("habits")?.length() ?: 0
            val checkIns = json.optJSONObject("checkIns")
                ?: return StreakInfo(0, false, totalHabits)
            val todayKey = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

            // Check if any habit was checked today
            val allDays = mutableSetOf<String>()
            val keys = checkIns.keys()
            while (keys.hasNext()) {
                val habitId = keys.next()
                val days = checkIns.optJSONArray(habitId)
                if (days != null) {
                    for (i in 0 until days.length()) {
                        allDays.add(days.getString(i))
                    }
                }
            }

            val checkedToday = allDays.contains(todayKey)
            val yesterdayKey = java.time.LocalDate.now().minusDays(1)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val missedYesterday = allDays.isNotEmpty() && !allDays.contains(yesterdayKey)

            // Compute streak
            var cursor = java.time.LocalDate.now()
            if (!allDays.contains(cursor.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE))) {
                cursor = cursor.minusDays(1)
            }
            var streak = 0
            while (allDays.contains(cursor.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE))) {
                streak++
                cursor = cursor.minusDays(1)
            }

            StreakInfo(streak, checkedToday, totalHabits, missedYesterday)
        }.onFailure {
            Log.w(TAG, "Failed to read streak info: ${it.message}")
        }.getOrDefault(StreakInfo(0, false))
    }

    private fun ensureChannel(context: Context, name: String, desc: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Recreating the channel refreshes its user-visible name and
            // description to the active language without touching the user's
            // sound or importance overrides.
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = desc
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
