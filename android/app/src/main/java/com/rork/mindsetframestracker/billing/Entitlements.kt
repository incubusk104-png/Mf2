package com.rork.mindsetframestracker.billing

import com.rork.mindsetframestracker.data.MAX_FREE_HABITS

enum class SubscriptionTier {
    NONE,
    FOUNDING,
    REGULAR,
}

enum class Feature {
    UNLIMITED_HABITS,
    ALL_LANGUAGES,
    PDF_EXPORTS,
    POLAR,
    HEALTH_CONNECT,
    STRAVA,
    AI_INSIGHTS,
}

object Entitlements {

    fun tierForProductId(productId: String): SubscriptionTier = when (productId) {
        "mindset_premium_founding_monthly",
        "mindset_premium_founding_yearly" -> SubscriptionTier.FOUNDING
        "mindset_premium_monthly",
        "mindset_premium_yearly" -> SubscriptionTier.REGULAR
        else -> SubscriptionTier.NONE
    }

    fun hasAccess(tier: SubscriptionTier, feature: Feature): Boolean = when (feature) {
        Feature.UNLIMITED_HABITS,
        Feature.ALL_LANGUAGES,
        Feature.PDF_EXPORTS,
        Feature.AI_INSIGHTS -> tier != SubscriptionTier.NONE
        Feature.POLAR,
        Feature.HEALTH_CONNECT -> true  // free for everyone, no tier check
        Feature.STRAVA -> tier == SubscriptionTier.REGULAR
    }

    /**
     * Whether a habit may be created for this [tier] given [activeCount] habits
     * already occupying a slot.
     *
     * ## What "active" means, and where it comes from
     *
     * [activeCount] is `AppData.activeHabitCount` — Phase 1's active set, in
     * which **archived habits are excluded and paused ones are counted**. Better to
     * pass the count than the habit list: the caller has already applied
     * `Habit.isActive`, so this function cannot accidentally count a habit using a
     * different rule from the one the "x of 5" indicator uses.
     *
     * ## Why it goes through [Feature.UNLIMITED_HABITS]
     *
     * The feature flag is the entitlement half of the same question, and routing
     * through it is what keeps this in step with the rest of the billing layer —
     * a change to what a tier includes is a change in [hasAccess], not in a second
     * comparison written out here.
     *
     * ## UX ONLY — this is not the enforcement point
     *
     * This runs on the user's device. A modified APK can answer `true` forever, so
     * the cap that actually holds is the one the habits Edge Function applies to
     * `POST /habits` and `POST /habits/sync` (refusing with `403 habit_limit`).
     * This function exists so the *unmodified* app shows the right prompt at the
     * right moment instead of letting the user fill in a form that the server will
     * then refuse. The two agree because both read the one `MAX_FREE_HABITS` and
     * both count the active set.
     */
    fun canAddHabit(tier: SubscriptionTier, activeCount: Int): Boolean =
        hasAccess(tier, Feature.UNLIMITED_HABITS) || activeCount < MAX_FREE_HABITS
}
