package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The four mood modes that drive the adaptive theme. Exactly these four. */
@Serializable
enum class MoodMode { CALM, FOCUSED, MOTIVATED, OVERWHELMED }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Habit(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    /** Pinned (favorite) habits always sort to the top of habit lists. */
    val isPinned: Boolean = false,
    /** Minutes from midnight for this habit's own reminder. Null = no individual alarm. */
    val reminderMinutes: Int? = null,
    /** For timed habits (meditation, workout). Null = simple checkbox habit. */
    val durationSeconds: Int? = null,
    /** Links to HabitIconCatalog.HabitIcon.id for visual picker display. */
    val iconId: String? = null,
)

@Serializable
data class ActivityRecord(
    val id: String,
    val habitId: String,
    val source: String,          // "fitbit" | "polar" | "health_connect" | "strava"
    val activityType: String,    // "walking", "running", "cycling", etc.
    val timestamp: Long,
    val durationMinutes: Int? = null,
    val distanceMeters: Double? = null,
    val steps: Long? = null,
    val heartRateAvg: Int? = null,
    val calories: Int? = null,
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reducedMotion: Boolean = false,
    /** Daily reminder time as minutes from midnight. */
    val notificationMinutes: Int = 8 * 60,
    /**
     * Streak-protection alert: fires once a day at [streakAlertMinutes] but
     * ONLY when today's habits are not all completed yet. Stays silent when
     * the day is already done.
     */
    val streakAlertEnabled: Boolean = true,
    /** Streak alert time as minutes from midnight (default 8:00 PM). */
    val streakAlertMinutes: Int = 20 * 60,
    /**
     * Weekly recap: one Sunday-evening notification summarizing the week
     * ("You checked in 5/7 days"). Strong weeks get celebrated, quiet weeks
     * get a fresh-start nudge — never a guilt trip.
     */
    val weeklyRecapEnabled: Boolean = true,
    /** Accent pack id: "classic" (free). */
    val accentPack: String = "classic",
    /** Custom default times for the quick-pick presets ("morning"/"midday"/"evening" -> minutes from midnight). */
    val presetTimes: Map<String, Int> = emptyMap(),
    val onboardingDone: Boolean = false,
    /**
     * One-time sign-in popup guard: true once the save-your-progress sheet
     * has been offered. It never re-appears on later launches — the ONLY
     * thing that re-arms it is an explicit sign-out (the popup is the sole
     * sign-in surface, so the user needs a way back in).
     */
    val authPromptDone: Boolean = false,
    /**
     * One-time guard for whether the user has agreed to the Privacy Policy
     * before any login trigger is initialized. Required by Huawei AppGallery.
     */
    val privacyConsentAccepted: Boolean = false,
    /**
     * Premium entitlement. Free tier keeps the core tracker; Premium unlocks
     * extended prompts/quotes, advanced insights, exclusive themes, all
     * languages, unlimited habits, and PDF reports. Upgrade is offered
     * through the Mindset Frames listing on Huawei AppGallery.
     */
    val isPremium: Boolean = false,
    /**
     * The active Huawei IAP subscription product id (e.g.
     * "mindset_premium_monthly"). Set by SubscriptionBilling on a verified
     * purchase or restore, cleared when the store reports no active sub.
     * Drives the SubscriptionTier used by Entitlements (Strava is
     * REGULAR-tier only). Null while [isPremium] is false or when premium
     * was granted through a legacy path.
     */
    val subscriptionProductId: String? = null,
    /** Fitbit OAuth access token — held only on-device. */
    val fitbitAccessToken: String? = null,
    /** Fitbit OAuth refresh token — held only on-device. */
    val fitbitRefreshToken: String? = null,
    /** Epoch millis of the last successful Fitbit sync (0 = never). */
    val fitbitLastSyncMs: Long = 0,
    /** Auto-sync Fitbit data when opening the app. */
    val fitbitAutoSync: Boolean = true,
    /** Polar AccessLink access token — held only on-device. */
    val polarAccessToken: String? = null,
    /** Epoch millis of the last successful Polar sync (0 = never). */
    val polarLastSyncMs: Long = 0,
    /** Auto-sync Polar data when opening the app. */
    val polarAutoSync: Boolean = true,
    /** Strava OAuth tokens — held only on-device; exchange/refresh happens
     *  through the strava-token-exchange Edge Function (secret never ships). */
    val stravaAccessToken: String? = null,
    val stravaRefreshToken: String? = null,
    /** Strava access-token expiry, epoch SECONDS (Strava's own unit). */
    val stravaExpiresAt: Long = 0,
    /** Epoch millis of the last successful Strava sync (0 = never). */
    val stravaLastSyncMs: Long = 0,
    /** Auto-sync Strava activities when opening the app. */
    val stravaAutoSync: Boolean = true,
    /** Android Health Connect (Google) connected state. */
    val healthConnectConnected: Boolean = false,
    /** Epoch millis of last Health Connect sync. */
    val healthConnectLastSyncMs: Long = 0,
    /** Auto-sync Health Connect data when opening the app. */
    val healthConnectAutoSync: Boolean = true,
    /**
     * App display language. English (US/UK) is free everywhere, one regional
     * language ([freeRegionalLanguage]) is free for this install; all other
     * languages are Premium.
     */
    val language: AppLanguage = DEFAULT_LANGUAGE,
    /**
     * The ONE regional language this install unlocked for free, resolved
     * from the device locale on first launch (e.g. Simplified Chinese in
     * China, Tagalog in the Philippines). Null when the locale maps to no
     * supported language (plain English regions). Persisted locally and
     * synced to Supabase inside the settings payload so the unlock follows
     * the user across restores.
     */
    val freeRegionalLanguage: AppLanguage? = null,
    /**
     * Achievement badges earned by completing all habits for consecutive
     * days. Once earned, a badge is permanent — it stays even if the streak
     * later breaks. See [BadgeTier] for the tier thresholds.
     */
    val earnedBadges: Set<BadgeTier> = emptySet(),
    /**
     * The user's companion avatar. Customization is free; background frames
     * unlock through streak achievements (see AvatarCatalog).
     */
    val avatar: AvatarConfig = AvatarConfig(),
    /**
     * Permanently earned Companion Studio exclusives (outfit/expression/pet
     * ids). An item lands here the first time its CompanionTask requirement
     * is met and never leaves — a later streak break can't re-lock it.
     */
    val companionUnlocks: Set<String> = emptySet(),
)

/** Root of everything persisted locally on device. No PII, no cloud. */
@Serializable
data class AppData(
    val habits: List<Habit> = emptyList(),
    /** habitId -> list of ISO day keys ("2026-07-23") the habit was checked. */
    val checkIns: Map<String, List<String>> = emptyMap(),
    /** ISO day key -> mood selected that day (used for weekly view). */
    val moodHistory: Map<String, MoodMode> = emptyMap(),
    /** ISO day key -> one-line grounding micro-journal entry for that day. */
    val reflections: Map<String, String> = emptyMap(),
    val activityRecords: List<ActivityRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

object Dates {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun todayKey(): String = LocalDate.now().format(formatter)

    fun key(date: LocalDate): String = date.format(formatter)

    /** Last [n] days, oldest first, ending today. */
    fun lastDays(n: Int): List<LocalDate> =
        (n - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
}

/**
 * Habits for display: pinned favorites first, otherwise preserving the
 * user's original order (stable sort).
 */
fun AppData.sortedHabits(): List<Habit> = habits.sortedByDescending { it.isPinned }

fun AppData.isCheckedOn(habitId: String, dayKey: String): Boolean =
    checkIns[habitId]?.contains(dayKey) == true

fun AppData.isCheckedToday(habitId: String): Boolean = isCheckedOn(habitId, Dates.todayKey())

/**
 * Consecutive-day streak for a habit. Counts back from today; if today is not
 * yet checked the streak is preserved from yesterday.
 */
fun AppData.streakFor(habitId: String): Int {
    val days = checkIns[habitId]?.toSet() ?: return 0
    var cursor = LocalDate.now()
    if (!days.contains(Dates.key(cursor))) cursor = cursor.minusDays(1)
    var streak = 0
    while (days.contains(Dates.key(cursor))) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/**
 * Overall consecutive daily check-in streak. A day counts when at least one
 * habit was checked in on it. Counts back from today; if today has no
 * check-in yet, the streak carries over from yesterday.
 */
fun AppData.dailyCheckInStreak(): Int {
    val days: Set<String> = checkIns.values.flatten().toSet()
    if (days.isEmpty()) return 0
    var cursor = LocalDate.now()
    if (!days.contains(Dates.key(cursor))) cursor = cursor.minusDays(1)
    var streak = 0
    while (days.contains(Dates.key(cursor))) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/** Mood for today, falling back to the most recent recorded mood, then Calm. */
fun AppData.currentMood(): MoodMode =
    moodHistory[Dates.todayKey()]
        ?: moodHistory.entries.maxByOrNull { it.key }?.value
        ?: MoodMode.CALM

fun AppData.completedCountOn(dayKey: String): Int =
    habits.count { isCheckedOn(it.id, dayKey) }

/**
 * True when every current habit was checked in on the given day. A day with
 * zero habits is never considered complete.
 */
fun AppData.isFullyCompleted(dayKey: String): Boolean =
    habits.isNotEmpty() && completedCountOn(dayKey) == habits.size

/**
 * Consecutive-day streak of completing ALL habits. Counts back from today;
 * if today isn't fully done yet, the streak carries from yesterday so the
 * count isn't lost mid-day. Used for badge tier qualification.
 */
fun AppData.fullCompletionStreak(): Int {
    if (habits.isEmpty()) return 0
    var cursor = LocalDate.now()
    if (!isFullyCompleted(Dates.key(cursor))) cursor = cursor.minusDays(1)
    var streak = 0
    while (isFullyCompleted(Dates.key(cursor))) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/** Max number of habits on the free tier. Premium removes the cap. */
const val MAX_FREE_HABITS = 5

/**
 * Premium-level content access — gates extended prompt packs, the exclusive
 * quote library, advanced weekly insights, premium accent themes, premium
 * languages, unlimited habits, and PDF reports.
 */
fun AppSettings.hasFeatureAccess(): Boolean = isPremium

/**
 * The billing tier this install is entitled to, derived from the active
 * subscription product. Legacy premium grants without a stored product id
 * map to REGULAR so no existing premium user loses features on update.
 */
fun AppSettings.subscriptionTier(): com.rork.mindsetframestracker.billing.SubscriptionTier {
    if (!isPremium) return com.rork.mindsetframestracker.billing.SubscriptionTier.NONE
    val fromProduct = subscriptionProductId?.let {
        com.rork.mindsetframestracker.billing.Entitlements.tierForProductId(it)
    }
    return if (fromProduct != null && fromProduct != com.rork.mindsetframestracker.billing.SubscriptionTier.NONE) {
        fromProduct
    } else {
        com.rork.mindsetframestracker.billing.SubscriptionTier.REGULAR
    }
}
