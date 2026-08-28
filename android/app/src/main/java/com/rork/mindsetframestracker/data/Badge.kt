package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable

/**
 * Achievement badge tiers earned by completing all habits for consecutive days.
 * Each tier requires a longer streak of full daily completion. Once earned,
 * a badge is permanent — it stays in [AppSettings.earnedBadges] even if the
 * streak later breaks.
 */
@Serializable
enum class BadgeTier(val daysRequired: Int) {
    /** Complete all habits 3 days in a row. */
    THREE_DAYS(3),

    /** Complete all habits 7 days in a row. */
    SEVEN_DAYS(7),

    /** Complete all habits 14 days in a row. */
    FOURTEEN_DAYS(14),

    /** Complete all habits 30 days in a row. */
    THIRTY_DAYS(30),
}

/**
 * Returns the badge tier that was just crossed when moving from
 * [previousStreak] to [currentStreak], or null if no threshold was
 * crossed. Only returns tiers not already in [alreadyEarned].
 */
fun newlyEarnedBadge(
    previousStreak: Int,
    currentStreak: Int,
    alreadyEarned: Set<BadgeTier>,
): BadgeTier? =
    BadgeTier.entries.firstOrNull {
        previousStreak < it.daysRequired &&
            currentStreak >= it.daysRequired &&
            it !in alreadyEarned
    }
