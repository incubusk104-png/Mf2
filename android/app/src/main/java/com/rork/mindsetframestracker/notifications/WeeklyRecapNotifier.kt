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
 * The Sunday-evening weekly recap: one calm notification summarizing how many
 * of the last 7 days had at least one habit checked in — "You checked in 5/7
 * days this week."
 *
 * Tone rules, matching the app's no-guilt positioning:
 * - Strong weeks get celebrated; quiet weeks get a fresh-start framing.
 * - Numbers are stated, never weaponized — urgency is the streak alert's job.
 *
 * Reads the persisted app-data JSON directly so it works from a
 * BroadcastReceiver without spinning up the whole repository stack.
 */
object WeeklyRecapNotifier {

    const val CHANNEL_ID = "weekly_recap"
    const val NOTIFICATION_ID = 2003

    private const val TAG = "WeeklyRecapNotifier"
    private const val PREFS_NAME = "mindset_frames"
    private const val KEY_DATA = "app_data"

    /**
     * Posts the weekly recap. Returns true when a notification was shown.
     *
     * [preview] forces a representative notification even with no habit data
     * so the Settings preview button always demonstrates the recap.
     */
    fun showRecap(context: Context, preview: Boolean = false): Boolean {
        val week = readWeekStats(context)

        if (week.totalHabits == 0 && !preview) {
            Log.i(TAG, "Skipping weekly recap: no habits configured")
            return false
        }

        val s = NotificationStrings.resolve(context)
        ensureChannel(context, s.ntfChannelRecapName, s.ntfChannelRecapDesc)

        val moodLabel = when (week.dominantMood) {
            "Calm" -> s.moodCalm
            "Focused" -> s.moodFocused
            "Motivated" -> s.moodMotivated
            "Overwhelmed" -> s.moodOverwhelmed
            else -> null
        }
        val moodLine = moodLabel?.let { String.format(s.ntfRecapMoodLine, it) } ?: ""
        val perfectLine = when {
            week.perfectDays <= 0 || week.daysActive == 0 -> ""
            week.perfectDays == 1 -> s.ntfRecapPerfectDaysOne
            else -> String.format(s.ntfRecapPerfectDaysMany, week.perfectDays)
        }

        val title: String
        val text: String
        val bigText: String

        when {
            week.totalHabits == 0 -> {
                // Preview on an empty app: show a representative sample.
                title = s.ntfRecapPreviewTitle
                text = s.ntfRecapPreviewText
                bigText = s.ntfRecapPreviewBig
            }
            week.daysActive >= 7 -> {
                title = s.ntfRecapPerfectTitle
                text = s.ntfRecapPerfectText
                bigText = String.format(s.ntfRecapPerfectBig, perfectLine, moodLine)
            }
            week.daysActive >= 5 -> {
                title = String.format(s.ntfRecapStrongTitle, week.daysActive)
                text = s.ntfRecapStrongText
                bigText = String.format(s.ntfRecapStrongBig, week.daysActive, perfectLine, moodLine)
            }
            week.daysActive >= 3 -> {
                title = String.format(s.ntfRecapMidTitle, week.daysActive)
                text = s.ntfRecapMidText
                bigText = String.format(s.ntfRecapMidBig, week.daysActive, perfectLine, moodLine)
            }
            week.daysActive >= 1 -> {
                title = String.format(s.ntfRecapLowTitle, week.daysActive)
                text = s.ntfRecapLowText
                bigText = String.format(s.ntfRecapLowBig, week.daysActive, moodLine)
            }
            else -> {
                title = s.ntfRecapEmptyTitle
                text = s.ntfRecapEmptyText
                bigText = s.ntfRecapEmptyBig
            }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            2,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .apply { if (preview) setSubText(s.ntfPreviewTag) }

        // "Share my week" — one-tap productivity snapshot straight from the
        // notification to any social app (TikTok, Instagram, Messenger…).
        if (week.daysActive >= 1) {
            val shareText = String.format(s.ntfRecapShareText, week.daysActive)
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                },
                s.ntfRecapShareChooser,
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val sharePending = PendingIntent.getActivity(
                context,
                3,
                chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, s.ntfRecapShareAction, sharePending)
        }
        val notification = builder.build()

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private data class WeekStats(
        val totalHabits: Int,
        /** Days in the last 7 (ending today) with at least one habit checked. */
        val daysActive: Int,
        /** Days in the last 7 where every habit was checked. */
        val perfectDays: Int,
        /** Display label of the most-logged mood this week, e.g. "Calm". */
        val dominantMood: String?,
    )

    /** Reads the local app-data JSON and summarizes the last 7 days. */
    private fun readWeekStats(context: Context): WeekStats {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null)
                ?: return WeekStats(0, 0, 0, null)
            val json = JSONObject(jsonStr)

            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            val weekKeys = (6 downTo 0).map {
                LocalDate.now().minusDays(it.toLong()).format(formatter)
            }

            val habits = json.optJSONArray("habits")
            val totalHabits = habits?.length() ?: 0

            val checkIns = json.optJSONObject("checkIns")
            val checkedPerDay = mutableMapOf<String, Int>()
            if (checkIns != null && habits != null) {
                for (i in 0 until habits.length()) {
                    val habitId = habits.optJSONObject(i)?.optString("id") ?: continue
                    val days = checkIns.optJSONArray(habitId) ?: continue
                    val daySet = mutableSetOf<String>()
                    for (d in 0 until days.length()) daySet.add(days.getString(d))
                    weekKeys.forEach { key ->
                        if (daySet.contains(key)) {
                            checkedPerDay[key] = (checkedPerDay[key] ?: 0) + 1
                        }
                    }
                }
            }

            val daysActive = weekKeys.count { (checkedPerDay[it] ?: 0) > 0 }
            val perfectDays = if (totalHabits == 0) 0
            else weekKeys.count { (checkedPerDay[it] ?: 0) >= totalHabits }

            val moodHistory = json.optJSONObject("moodHistory")
            val moodCounts = mutableMapOf<String, Int>()
            if (moodHistory != null) {
                weekKeys.forEach { key ->
                    val mood = moodHistory.optString(key, "")
                    if (mood.isNotEmpty()) moodCounts[mood] = (moodCounts[mood] ?: 0) + 1
                }
            }
            val dominantMood = when (moodCounts.maxByOrNull { it.value }?.key) {
                "CALM" -> "Calm"
                "FOCUSED" -> "Focused"
                "MOTIVATED" -> "Motivated"
                "OVERWHELMED" -> "Overwhelmed"
                else -> null
            }

            WeekStats(totalHabits, daysActive, perfectDays, dominantMood)
        }.onFailure {
            Log.w(TAG, "Failed to read week stats: ${it.message}")
        }.getOrDefault(WeekStats(0, 0, 0, null))
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
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = desc
            }
            manager.createNotificationChannel(channel)
        }
    }
}
