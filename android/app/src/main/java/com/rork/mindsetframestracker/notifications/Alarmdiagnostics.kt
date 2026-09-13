package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * One-shot dump of every flag that can independently hide a habit reminder,
 * across every layer we've had to check one at a time in previous rounds of
 * debugging: runtime permission, app/channel-level notification settings,
 * Do Not Disturb, the Android 14+ full-screen-intent grant, exact-alarm
 * scheduling, and battery optimization. Every one of these has turned out to
 * matter on real devices, and each is invisible to the others — a green
 * checkmark on one tells you nothing about the rest.
 *
 * Surfaced via a "Copy full diagnostics" action in the alarm-permission
 * dialog so a single copy-paste captures the full picture in one shot,
 * instead of a screenshot-per-setting back-and-forth.
 */
object AlarmDiagnostics {

    fun report(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Mindset Frames alarm diagnostics")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        // --- Notification permission (Android 13+ runtime permission) ---
        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // not applicable before API 33
        }
        sb.appendLine("POST_NOTIFICATIONS granted: $postNotificationsGranted")

        // --- App-level + channel-level notification enablement ---
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        sb.appendLine("App notifications enabled (system-level toggle): $appNotificationsEnabled")

        val channel = notifManager.getNotificationChannel(HabitCheckInNotifier.CHANNEL_ID)
        if (channel == null) {
            sb.appendLine("\"Habit Reminders\" channel: not created yet (will be created on first reminder)")
        } else {
            sb.appendLine("\"Habit Reminders\" channel importance: ${importanceName(channel.importance)}")
            sb.appendLine("\"Habit Reminders\" channel sound usage: ${channel.audioAttributes?.usage ?: "none"}")
        }

        // --- Do Not Disturb / Focus mode ---
        val interruptionFilter = notifManager.currentInterruptionFilter
        sb.appendLine("Do Not Disturb / Focus mode active: ${interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL} (filter=${filterName(interruptionFilter)})")

        // --- Full-screen intent (Android 14+ special access) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sb.appendLine("Full-screen intent (ringing alarm screen) allowed: ${notifManager.canUseFullScreenIntent()}")
        } else {
            sb.appendLine("Full-screen intent allowed: yes (not gated before Android 14)")
        }

        // --- Exact alarm scheduling (Android 12+) ---
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.appendLine("Exact alarms allowed: ${alarmManager.canScheduleExactAlarms()}")
        } else {
            sb.appendLine("Exact alarms allowed: yes (not gated before Android 12)")
        }

        // --- Battery optimization ---
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
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
