package com.rork.mindsetframestracker.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.MindsetRepository
import java.util.Calendar

object HabitAlarmScheduler {

    private const val REQUEST_CODE_BASE = 10_000

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
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
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
