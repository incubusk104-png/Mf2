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

/**
 * The app's "companion voice" notifications — warm, honest, human:
 *
 * 1. Evening reflection — a quiet companion question that lands around 9:15 PM.
 *
 * Verifies state from the persisted app-data JSON at fire time, so a stale
 * alarm stays silent.
 */
object CompanionNotifier {

    const val CHANNEL_ID = "companion"
    const val REFLECTION_CHANNEL_ID = "evening_reflection"
    const val REFLECTION_NOTIFICATION_ID = 2006

    private const val TAG = "CompanionNotifier"
    private const val PREFS_NAME = "mindset_frames"
    private const val KEY_DATA = "app_data"

    /**
     * Posts the nightly evening reflection — a quiet companion question
     * around 9:15 PM. Skips users who haven't finished onboarding yet.
     * The rotating prompts live in the string table (localized per language);
     * the day-of-year picks which, so the ritual never feels copy-pasted.
     * Returns true when shown.
     */
    fun showEveningReflection(context: Context): Boolean {
        val settings = readSettings(context) ?: return false
        if (!settings.optBoolean("onboardingDone", false)) {
            Log.i(TAG, "Skipping evening reflection: onboarding not done")
            return false
        }

        val s = NotificationStrings.resolve(context)
        ensureReflectionChannel(context, s.ntfChannelReflectionName, s.ntfChannelReflectionDesc)
        val reflectionPrompts = listOf(
            s.ntfReflection1,
            s.ntfReflection2,
            s.ntfReflection3,
            s.ntfReflection4,
            s.ntfReflection5,
            s.ntfReflection6,
            s.ntfReflection7,
        )
        val prompt = reflectionPrompts[
            java.time.LocalDate.now().dayOfYear % reflectionPrompts.size,
        ]
        val contentIntent = openAppIntent(context, requestCode = 43)

        val notification = NotificationCompat.Builder(context, REFLECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(s.ntfReflectionTitle)
            .setContentText(prompt)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$prompt\n\n${s.ntfReflectionBigSuffix}"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notify(context, REFLECTION_NOTIFICATION_ID, notification)
        return true
    }

    private fun ensureReflectionChannel(context: Context, name: String, desc: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                REFLECTION_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = desc
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        runCatching {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(id, notification)
        }.onFailure { Log.w(TAG, "Failed to post companion notification: ${it.message}") }
    }

    private fun readSettings(context: Context): JSONObject? = runCatching {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DATA, null) ?: return null
        JSONObject(jsonStr).optJSONObject("settings")
    }.onFailure {
        Log.w(TAG, "Failed to read settings: ${it.message}")
    }.getOrNull()

    @Suppress("unused")
    private fun ensureChannel(context: Context, name: String, desc: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
