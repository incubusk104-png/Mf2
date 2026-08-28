package com.rork.mindsetframestracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HabitAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getStringExtra("habitId") ?: return
        val habitName = intent.getStringExtra("habitName") ?: return
        HabitCheckInNotifier.show(context, habitId, habitName)
    }
}
