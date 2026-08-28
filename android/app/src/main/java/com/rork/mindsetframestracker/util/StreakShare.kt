package com.rork.mindsetframestracker.util

import android.content.Context
import android.content.Intent
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.dailyCheckInStreak
import com.rork.mindsetframestracker.data.streakFor

/**
 * Builds a shareable streak message and launches the native Android share
 * sheet. Generates motivational copy based on the user's current check-in
 * streak and best habit streak.
 */
object StreakShare {

    /**
     * Launches the system share sheet with a pre-filled streak message.
     */
    fun shareStreak(context: Context, data: AppData) {
        val checkInStreak = data.dailyCheckInStreak()
        val bestHabit = data.habits.maxByOrNull { data.streakFor(it.id) }
        val bestHabitStreak = bestHabit?.let { data.streakFor(it.id) } ?: 0

        val message = buildShareMessage(checkInStreak, bestHabit?.name, bestHabitStreak)

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, message)
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, "Share your streak")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    /**
     * Shares a just-earned achievement badge — called from the unlock
     * celebration overlay at the moment of peak pride.
     */
    fun shareBadge(context: Context, badgeTitle: String, daysRequired: Int) {
        val message = "Achievement unlocked: $badgeTitle! \uD83C\uDFC6\n" +
            "$daysRequired consecutive days of completing every habit.\n\n" +
            "Mindset Frames — track habits that adapt to your mood.\n" +
            "#MindsetFrames #HabitTracker #AchievementUnlocked"
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, message)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share your achievement")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    private fun buildShareMessage(
        checkInStreak: Int,
        bestHabitName: String?,
        bestHabitStreak: Int,
    ): String {
        val streakLine = when {
            checkInStreak == 0 -> "Just started my mindset journey today!"
            checkInStreak == 1 -> "Day 1 of my mindset streak!"
            checkInStreak < 7 -> "$checkInStreak days into my mindset streak!"
            checkInStreak < 30 -> "$checkInStreak-day streak and counting!"
            else -> "$checkInStreak days strong! Building better habits every day."
        }

        val habitLine = if (bestHabitName != null && bestHabitStreak > 0) {
            "\nBest streak: $bestHabitName — $bestHabitStreak days"
        } else {
            ""
        }

        // Hashtags travel across every share target (TikTok, Instagram,
        // Facebook, Messenger, Reddit, Snapchat, X…) via the system share sheet.
        return "$streakLine$habitLine\n\nMindset Frames — track habits that adapt to your mood.\n#MindsetFrames #HabitTracker #DailyWins"
    }
}
