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

object CheckInNotifier {

    const val CHANNEL_ID = "daily_check_in"
    const val NOTIFICATION_ID = 2001

    private const val TAG = "CheckInNotifier"
    private const val PREFS_NAME = "mindset_frames"
    private const val KEY_DATA = "app_data"

    fun show(context: Context, preview: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "Daily check-in not shown: notification permission missing")
                return
            }
        }
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
            title = s.ntfCheckInEmptyTitle
            text = s.ntfCheckInEmptyText
            bigText = s.ntfCheckInEmptyBig
        } else if (streakInfo.missedYesterday && !streakInfo.checkedToday) {
            title = s.ntfCheckInMissedTitle
            text = s.ntfCheckInMissedText
            bigText = s.ntfCheckInMissedBig
        } else if (streakInfo.checkInStreak > 0 && !streakInfo.checkedToday) {
            val days = streakInfo.checkInStreak
            title = String.format(s.ntfCheckInStreakTitle, days)
            text = s.ntfCheckInStreakText
            bigText = when {
                days >= 30 -> String.format(s.ntfCheckInStreakBig30, days)
                days >= 7 -> String.format(s.ntfCheckInStreakBig7, days)
                else -> String.format(s.ntfCheckInStreakBigLow, days)
            }
        } else if (streakInfo.checkedToday) {
            title = s.ntfCheckInDoneTitle
            text = s.ntfCheckInDoneText
            bigText = s.ntfCheckInDoneBig
        } else {
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
        val totalHabits: Int = 1,
        val missedYesterday: Boolean = false,
    )

    private fun getStreakInfo(context: Context): StreakInfo {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null) ?: return StreakInfo(0, false)
            val json = JSONObject(jsonStr)

            val totalHabits = json.optJSONArray("habits")?.length() ?: 0
            val checkIns = json.optJSONObject("checkIns")
                ?: return StreakInfo(0, false, totalHabits)
            val todayKey = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

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
