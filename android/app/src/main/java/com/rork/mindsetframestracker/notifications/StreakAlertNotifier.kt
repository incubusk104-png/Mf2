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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The streak-protection alert. Unlike [CheckInNotifier] (which always posts
 * something at reminder time), this notifier decides at fire time whether an
 * alert is warranted:
 *
 * - All of today's habits done   -> stays completely silent
 * - Nothing done + active streak -> urgent "streak ends tonight" alert
 * - Nothing done, no streak      -> gentle "day is slipping away" nudge
 * - Partially done               -> "finish the remaining N" reminder
 *
 * Reads the persisted app-data JSON directly so it works from a
 * BroadcastReceiver without spinning up the whole repository stack.
 */
object StreakAlertNotifier {

    const val CHANNEL_ID = "streak_alerts"
    const val NOTIFICATION_ID = 2002

    private const val TAG = "StreakAlertNotifier"
    private const val PREFS_NAME = "mindset_frames"
    private const val KEY_DATA = "app_data"

    /**
     * Posts the streak alert when today's habits are incomplete.
     * Returns true if a notification was shown, false when it was skipped.
     *
     * [preview] forces a notification even when today is already complete so
     * the Settings "preview" button always demonstrates the alert.
     */
    fun showIfStreakAtRisk(context: Context, preview: Boolean = false): Boolean {
        val status = readTodayStatus(context)

        if (status.totalHabits == 0 && !preview) {
            Log.i(TAG, "Skipping streak alert: no habits configured")
            return false
        }
        if (status.allDone && !preview) {
            Log.i(TAG, "Skipping streak alert: today already complete")
            return false
        }

        val s = NotificationStrings.resolve(context)
        ensureChannel(context, s.ntfChannelStreakName, s.ntfChannelStreakDesc)

        val remaining = (status.totalHabits - status.completedToday).coerceAtLeast(0)
        val streak = status.streakAtRisk

        val title: String
        val text: String
        val bigText: String

        when {
            preview && status.allDone -> {
                // Preview while everything is done: show a representative sample.
                title = s.ntfStreakPreviewTitle
                text = s.ntfStreakPreviewText
                bigText = s.ntfStreakPreviewBig
            }
            status.completedToday == 0 && streak > 0 -> {
                title = String.format(s.ntfStreakRiskTitle, streak)
                text = s.ntfStreakRiskText
                bigText = when {
                    streak >= 30 -> String.format(s.ntfStreakRiskBig30, streak)
                    streak >= 7 -> String.format(s.ntfStreakRiskBig7, streak)
                    else -> String.format(s.ntfStreakRiskBigLow, streak)
                }
            }
            status.completedToday == 0 -> {
                title = s.ntfStreakNoneTitle
                text = s.ntfStreakNoneText
                bigText = s.ntfStreakNoneBig
            }
            else -> {
                title = if (remaining == 1) s.ntfStreakPartialTitleOne
                else String.format(s.ntfStreakPartialTitleMany, remaining)
                text = s.ntfStreakPartialText
                bigText = String.format(
                    s.ntfStreakPartialBig,
                    status.completedToday,
                    status.totalHabits,
                    remaining,
                )
            }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .addAction(0, s.ntfActionCheckIn, contentIntent)
            .setAutoCancel(true)
            .apply { if (preview) setSubText(s.ntfPreviewTag) }
            .build()

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private data class TodayStatus(
        val totalHabits: Int,
        val completedToday: Int,
        /** Consecutive-day streak that would break if today stays empty. */
        val streakAtRisk: Int,
    ) {
        val allDone: Boolean get() = totalHabits > 0 && completedToday >= totalHabits
    }

    /**
     * Reads the local app-data JSON to compute how much of today is done and
     * the streak currently on the line.
     */
    private fun readTodayStatus(context: Context): TodayStatus {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null)
                ?: return TodayStatus(0, 0, 0)
            val json = JSONObject(jsonStr)

            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            val todayKey = LocalDate.now().format(formatter)

            val habits = json.optJSONArray("habits")
            val totalHabits = habits?.length() ?: 0

            val checkIns = json.optJSONObject("checkIns")
            var completedToday = 0
            val allDays = mutableSetOf<String>()
            if (checkIns != null && habits != null) {
                for (i in 0 until habits.length()) {
                    val habitId = habits.optJSONObject(i)?.optString("id") ?: continue
                    val days = checkIns.optJSONArray(habitId) ?: continue
                    var checkedToday = false
                    for (d in 0 until days.length()) {
                        val day = days.getString(d)
                        allDays.add(day)
                        if (day == todayKey) checkedToday = true
                    }
                    if (checkedToday) completedToday++
                }
            }

            // Streak that breaks tonight: counts back from yesterday, since the
            // alert only matters while today is still incomplete.
            var streak = 0
            var cursor = LocalDate.now().minusDays(1)
            while (allDays.contains(cursor.format(formatter))) {
                streak++
                cursor = cursor.minusDays(1)
            }

            TodayStatus(totalHabits, completedToday, streak)
        }.onFailure {
            Log.w(TAG, "Failed to read today's status: ${it.message}")
        }.getOrDefault(TodayStatus(0, 0, 0))
    }

    private fun ensureChannel(context: Context, name: String, desc: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Recreating the channel refreshes its user-visible name and
            // description to the active language.
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = desc
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
