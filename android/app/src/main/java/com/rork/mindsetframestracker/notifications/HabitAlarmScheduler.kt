package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.MindsetRepository
import java.util.Calendar

object HabitAlarmScheduler {

    private const val TAG = "HabitAlarmScheduler"
    private const val REQUEST_CODE_BASE = 10_000

    /** 15-minute tolerance window used when exact alarms aren't permitted. */
    private const val WINDOW_MILLIS = 15L * 60L * 1000L

    fun schedule(context: Context, habit: Habit) {
        val minutes = habit.reminderMinutes ?: return
        setAlarm(context, habit.id, habit.name, minutes)
    }

    fun cancel(context: Context, habit: Habit) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, habit.id, habit.name)
        alarmManager.cancel(pendingIntent)
    }

    fun rescheduleAll(context: Context, habits: List<Habit>) {
        habits.forEach { if (it.reminderMinutes != null) schedule(context, it) }
    }

    /** Called by HabitCheckInNotifier right after firing, to re-arm tomorrow. */
    fun scheduleNext(context: Context, habitId: String, habitName: String) {
        val repo = MindsetRepository(context)
        val habit = repo.load().habits.find { it.id == habitId } ?: return
        val minutes = habit.reminderMinutes ?: return
        setAlarm(context, habitId, habitName, minutes)
    }

    private fun setAlarm(context: Context, habitId: String, habitName: String, minutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, habitId, habitName)
        val triggerTime = nextTriggerMillis(minutes)

        // On Android 12 (S) and above, setExactAndAllowWhileIdle() throws a
        // SecurityException — which force-stops the app — unless the app holds
        // the special SCHEDULE_EXACT_ALARM permission (it is NOT auto-granted).
        // So we only use an exact alarm when it's actually allowed, and fall
        // back to an inexact windowed alarm otherwise. Everything is wrapped in
        // runCatching so a scheduling failure can never crash the tap.
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        runCatching {
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent,
                )
            } else {
                // No exact-alarm permission: fire within a 15-minute window
                // around the target time. Perfectly fine for a daily reminder.
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    WINDOW_MILLIS,
                    pendingIntent,
                )
            }
        }.onFailure { error ->
            // As a last resort (e.g. an OEM that still rejects the call), fall
            // back to an inexact alarm rather than let the exception bubble up
            // and crash the UI.
            Log.w(TAG, "Exact alarm for '$habitName' failed; using inexact", error)
            runCatching {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }.onFailure { fallbackError ->
                Log.w(TAG, "Inexact alarm for '$habitName' also failed", fallbackError)
            }
        }
    }

    private fun buildPendingIntent(context: Context, habitId: String, habitName: String): PendingIntent {
        val intent = Intent(context, HabitAlarmReceiver::class.java).apply {
            putExtra("habitId", habitId)
            putExtra("habitName", habitName)
        }
        val requestCode = REQUEST_CODE_BASE + habitId.hashCode()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTriggerMillis(minutesFromMidnight: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesFromMidnight / 60)
            set(Calendar.MINUTE, minutesFromMidnight % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
