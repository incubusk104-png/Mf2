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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rork.mindsetframestracker.MainActivity
import com.rork.mindsetframestracker.R

object HabitCheckInNotifier {

    const val CHANNEL_ID = "habit_reminder"
    private const val NOTIFICATION_ID_BASE = 3000

    /** Stable notification id for a given habit — shared with snooze/cancel logic. */
    fun notificationId(habitId: String): Int = NOTIFICATION_ID_BASE + habitId.hashCode()

    /**
     * Posts the reminder. Returns false (and posts nothing) when the app
     * can't show notifications at all — the caller can surface that to the
     * user instead of the old silent no-op, which is what made the
     * "Send a test reminder now" button look broken when permission was
     * missing.
     */
    fun show(context: Context, habitId: String, habitName: String, reschedule: Boolean = true): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        ensureChannel(context)

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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_icon)
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
            // Wakes the screen and rings even through silent/DND/Bedtime
            // mode on devices that allow full-screen alarm intents.
            .setFullScreenIntent(ringingPendingIntent, true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId(habitId), notification)

        if (reschedule) {
            HabitAlarmScheduler.scheduleNext(context, habitId, habitName)
        }
        return true
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
            description = "Individual habit reminder alarms that fire at the time you set"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 100, 250)
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
}
