package com.rork.mindsetframestracker.billing

enum class SubscriptionTier {
    NONE,
    FOUNDING,
    REGULAR,
}

enum class Feature {
    UNLIMITED_HABITS,
    ALL_LANGUAGES,
    PDF_EXPORTS,
    FITBIT,
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
        Feature.FITBIT,
        Feature.POLAR,
        Feature.HEALTH_CONNECT -> true  // free for everyone, no tier check
        Feature.STRAVA -> tier == SubscriptionTier.REGULAR
    }
}
