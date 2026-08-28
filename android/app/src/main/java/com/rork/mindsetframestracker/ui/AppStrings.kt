package com.rork.mindsetframestracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.rork.mindsetframestracker.data.AppLanguage
import com.rork.mindsetframestracker.util.LocalizationManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Centralized string table for the entire app. Every user-visible string
 * lives here so the language can be switched at runtime without touching
 * screen code. English is the default; all other languages are Premium.
 *
 * Values are loaded from JSON asset files (`assets/strings/{code}.json`).
 * Any key missing in a non-English file falls back to the English value
 * automatically via [stringsFor].
 *
 * IMPORTANT — why this is a map-backed class and NOT a data class:
 * A data class with one constructor parameter per string (~240 parameters)
 * compiles fine, but the constructor invocation needs more argument
 * registers than the ART bytecode verifier allows per invoke (max 64).
 * On modern Android runtimes the class fails verification and the app dies
 * at startup with `java.lang.VerifyError`. Each property below is a tiny
 * getter reading from the merged string table instead, so no method in
 * this file ever needs more than a couple of registers.
 */
class AppStrings(private val table: Map<String, String>) {

    private fun s(key: String): String = table[key] ?: ""

    // ── Navigation ──────────────────────────────────────────
    val navToday: String get() = s("navToday")
    val navHabits: String get() = s("navHabits")
    val navWeekly: String get() = s("navWeekly")
    val navInsights: String get() = s("navInsights")
    val navSettings: String get() = s("navSettings")

    // ── Mood labels ─────────────────────────────────────────
    val moodCalm: String get() = s("moodCalm")
    val moodFocused: String get() = s("moodFocused")
    val moodMotivated: String get() = s("moodMotivated")
    val moodOverwhelmed: String get() = s("moodOverwhelmed")

    // ── Onboarding ──────────────────────────────────────────
    val onbSkip: String get() = s("onbSkip")
    val onbSeeHowItWorks: String get() = s("onbSeeHowItWorks")
    val onbNext: String get() = s("onbNext")
    val onbContinue: String get() = s("onbContinue")
    val onbStartMyDay: String get() = s("onbStartMyDay")
    val onbWelcomeTitle: String get() = s("onbWelcomeTitle")
    val onbWelcomeBody: String get() = s("onbWelcomeBody")
    val onbFoundingMember: String get() = s("onbFoundingMember")
    val onbSwipeToSee: String get() = s("onbSwipeToSee")
    val onbMoodTitle: String get() = s("onbMoodTitle")
    val onbMoodBody: String get() = s("onbMoodBody")
    val onbHabitsTitle: String get() = s("onbHabitsTitle")
    val onbHabitsBody: String get() = s("onbHabitsBody")
    val onbPickStarter: String get() = s("onbPickStarter")
    val onbChooseFew: String get() = s("onbChooseFew")
    val onbAddYourOwn: String get() = s("onbAddYourOwn")
    val onbAddCustomHabit: String get() = s("onbAddCustomHabit")
    val onbSelected: String get() = s("onbSelected")
    val onbNotSelected: String get() = s("onbNotSelected")
    val onbAdded: String get() = s("onbAdded")
    val onbThisWeek: String get() = s("onbThisWeek")
    val onbHowArriving: String get() = s("onbHowArriving")
    val onbAnswerShapes: String get() = s("onbAnswerShapes")
    val onbLogoDesc: String get() = s("onbLogoDesc")

    // ── Home ────────────────────────────────────────────────
    val homeHowArriving: String get() = s("homeHowArriving")
    val homeDoneToday: String get() = s("homeDoneToday")
    val homeAddHabit: String get() = s("homeAddHabit")
    val homeLast7Days: String get() = s("homeLast7Days")
    val homeThisWeek: String get() = s("homeThisWeek")
    val homeLast30Days: String get() = s("homeLast30Days")
    val homeThisMonth: String get() = s("homeThisMonth")
    val homeShareWeekly: String get() = s("homeShareWeekly")
    val homeShareMonthly: String get() = s("homeShareMonthly")
    val homePremiumPrompts: String get() = s("homePremiumPrompts")
    val homeUpgrade: String get() = s("homeUpgrade")
    val homeShareAchievement: String get() = s("homeShareAchievement")
    val homeStreakDay: String get() = s("homeStreakDay")
    val homeStreakDays: String get() = s("homeStreakDays")
    val homeAllDoneToday: String get() = s("homeAllDoneToday")
    val homeAddHabitToStart: String get() = s("homeAddHabitToStart")
    val homeMindsetFrames: String get() = s("homeMindsetFrames")
    val homeMindsetFramesCompleted: String get() = s("homeMindsetFramesCompleted")
    val homeAddHabitToTrack: String get() = s("homeAddHabitToTrack")
    val homePremiumContent: String get() = s("homePremiumContent")

    // ── Habits ──────────────────────────────────────────────
    val habitsYourHabits: String get() = s("habitsYourHabits")
    val habitsFreeLimitReached: String get() = s("habitsFreeLimitReached")
    val habitsFreeHabitsUsed: String get() = s("habitsFreeHabitsUsed")
    val habitsUnlimited: String get() = s("habitsUnlimited")
    val habitsNoHabitsYet: String get() = s("habitsNoHabitsYet")
    val habitsNewHabit: String get() = s("habitsNewHabit")
    val habitsGoPremium: String get() = s("habitsGoPremium")
    val habitsGoPremiumAdd: String get() = s("habitsGoPremiumAdd")
    val habitsEditHabit: String get() = s("habitsEditHabit")
    val habitsDeleteHistory: String get() = s("habitsDeleteHistory")
    val habitsPlaceholder: String get() = s("habitsPlaceholder")
    val habitsAutoSave: String get() = s("habitsAutoSave")
    val habitsDone: String get() = s("habitsDone")
    val habitsSave: String get() = s("habitsSave")
    val habitsCancel: String get() = s("habitsCancel")
    val habitsDeleted: String get() = s("habitsDeleted")
    val habitsUndo: String get() = s("habitsUndo")
    val habitsNoStreak: String get() = s("habitsNoStreak")
    val habitsDelete: String get() = s("habitsDelete")
    val habitsPinToTop: String get() = s("habitsPinToTop")
    val habitsUnpin: String get() = s("habitsUnpin")
    val habitsEdit: String get() = s("habitsEdit")
    val habitsPremiumFeature: String get() = s("habitsPremiumFeature")
    val habitsReset: String get() = s("habitsReset")

    // ── Weekly ──────────────────────────────────────────────
    val weeklyThisWeek: String get() = s("weeklyThisWeek")
    val weeklyCheckIns: String get() = s("weeklyCheckIns")
    val weeklyHabitGrid: String get() = s("weeklyHabitGrid")
    val weeklyInsights: String get() = s("weeklyInsights")
    val weeklyCompletionRate: String get() = s("weeklyCompletionRate")
    val weeklyBestDay: String get() = s("weeklyBestDay")
    val weeklyMostConsistent: String get() = s("weeklyMostConsistent")
    val weeklyDeeperInsights: String get() = s("weeklyDeeperInsights")
    val weeklyGoPremium: String get() = s("weeklyGoPremium")
    val weeklyShareMyWeek: String get() = s("weeklyShareMyWeek")
    val weeklyShareDesc: String get() = s("weeklyShareDesc")
    val weeklyPremiumLocked: String get() = s("weeklyPremiumLocked")
    val weeklyDone: String get() = s("weeklyDone")
    val weeklyMood: String get() = s("weeklyMood")

    // ── Insights ────────────────────────────────────────────
    val insightsTitle: String get() = s("insightsTitle")
    val insightsSubtitle: String get() = s("insightsSubtitle")
    val insightsWeek: String get() = s("insightsWeek")
    val insights2Weeks: String get() = s("insights2Weeks")
    val insightsMonth: String get() = s("insightsMonth")
    val insightsCompletionTrend: String get() = s("insightsCompletionTrend")
    val insightsLastDays: String get() = s("insightsLastDays")
    val insightsHabitsDone: String get() = s("insightsHabitsDone")
    val insightsMoodLevel: String get() = s("insightsMoodLevel")
    val insightsAvgCompletion: String get() = s("insightsAvgCompletion")
    val insightsMomentum: String get() = s("insightsMomentum")
    val insightsVsPrevWeek: String get() = s("insightsVsPrevWeek")
    val insightsYearInFrames: String get() = s("insightsYearInFrames")
    val insightsPast12Months: String get() = s("insightsPast12Months")
    val insightsNoCheckIns: String get() = s("insightsNoCheckIns")
    val insightsActiveDays: String get() = s("insightsActiveDays")
    val insightsLongestRun: String get() = s("insightsLongestRun")
    val insightsMoodConsistency: String get() = s("insightsMoodConsistency")
    val insightsNoTrendsYet: String get() = s("insightsNoTrendsYet")
    val insightsNoTrendsBody: String get() = s("insightsNoTrendsBody")
    val insightsPickMood: String get() = s("insightsPickMood")
    val insightsShowUpStrongest: String get() = s("insightsShowUpStrongest")
    val insightsDaysLogged: String get() = s("insightsDaysLogged")
    val insightsDayLogged: String get() = s("insightsDayLogged")
    val insightsMoodChartHint: String get() = s("insightsMoodChartHint")
    val insightsDay: String get() = s("insightsDay")
    val insightsDays: String get() = s("insightsDays")

    // ── Settings ────────────────────────────────────────────
    val settingsTitle: String get() = s("settingsTitle")
    val settingsPremiumActive: String get() = s("settingsPremiumActive")
    val settingsMindsetPremium: String get() = s("settingsMindsetPremium")
    val settingsPremiumDescPremium: String get() = s("settingsPremiumDescPremium")
    val settingsPremiumDescFree: String get() = s("settingsPremiumDescFree")
    val settingsSeePlans: String get() = s("settingsSeePlans")
    val settingsManagePlay: String get() = s("settingsManagePlay")
    val settingsYearlyPlan: String get() = s("settingsYearlyPlan")
    val settingsMonthlyPlan: String get() = s("settingsMonthlyPlan")
    val settingsSubActive: String get() = s("settingsSubActive")
    val settingsTrialEnding: String get() = s("settingsTrialEnding")
    val settingsTrialLastDay: String get() = s("settingsTrialLastDay")
    val settingsTrialDaysLeft: String get() = s("settingsTrialDaysLeft")
    val settingsChoosePlan: String get() = s("settingsChoosePlan")

    // Settings — Ads
    val settingsAds: String get() = s("settingsAds")
    val settingsAdsPremium: String get() = s("settingsAdsPremium")
    val settingsAdsFree: String get() = s("settingsAdsFree")

    // Settings — Account
    val settingsAccountSync: String get() = s("settingsAccountSync")
    val settingsConnectedEmail: String get() = s("settingsConnectedEmail")
    val settingsLastBackup: String get() = s("settingsLastBackup")
    val settingsNoBackup: String get() = s("settingsNoBackup")
    val settingsSyncNow: String get() = s("settingsSyncNow")
    val settingsSignOut: String get() = s("settingsSignOut")
    val settingsDeleteAccount: String get() = s("settingsDeleteAccount")
    val settingsSignOutConfirm: String get() = s("settingsSignOutConfirm")
    val settingsSignOutConfirmBtn: String get() = s("settingsSignOutConfirmBtn")
    val settingsDeleteConfirm: String get() = s("settingsDeleteConfirm")
    val settingsDeleteConfirmBtn: String get() = s("settingsDeleteConfirmBtn")
    val settingsJustNow: String get() = s("settingsJustNow")

    // Settings — Report
    val settingsReport: String get() = s("settingsReport")
    val settingsExportPdf: String get() = s("settingsExportPdf")
    val settingsReportDesc: String get() = s("settingsReportDesc")
    val settingsExportMonth: String get() = s("settingsExportMonth")
    val settingsCustomRange: String get() = s("settingsCustomRange")
    val settingsIncludedPremium: String get() = s("settingsIncludedPremium")
    val settingsSavedDownloads: String get() = s("settingsSavedDownloads")
    val settingsExportReport: String get() = s("settingsExportReport")
    val settingsPickMonth: String get() = s("settingsPickMonth")
    val settingsInprogress: String get() = s("settingsInprogress")
    val settingsCustomDateRange: String get() = s("settingsCustomDateRange")
    val settingsExportPdfBtn: String get() = s("settingsExportPdfBtn")
    val settingsTooLong: String get() = s("settingsTooLong")
    val settings1Day: String get() = s("settings1Day")
    val settingsDaysSelected: String get() = s("settingsDaysSelected")
    val settingsPickEnd: String get() = s("settingsPickEnd")
    val settingsPickStartEnd: String get() = s("settingsPickStartEnd")

    // Settings — Appearance
    val settingsAppearance: String get() = s("settingsAppearance")
    val settingsTheme: String get() = s("settingsTheme")
    val settingsSystem: String get() = s("settingsSystem")
    val settingsLight: String get() = s("settingsLight")
    val settingsDark: String get() = s("settingsDark")
    val settingsDarkDesc: String get() = s("settingsDarkDesc")
    val settingsReduceMotion: String get() = s("settingsReduceMotion")
    val settingsReduceMotionDesc: String get() = s("settingsReduceMotionDesc")
    val settingsAccentPack: String get() = s("settingsAccentPack")
    val settingsTerracotta: String get() = s("settingsTerracotta")
    val settingsSunrise: String get() = s("settingsSunrise")
    val settingsForest: String get() = s("settingsForest")
    val settingsPremiumOnly: String get() = s("settingsPremiumOnly")

    // Settings — Language
    val settingsLanguage: String get() = s("settingsLanguage")
    val settingsLanguageDesc: String get() = s("settingsLanguageDesc")
    val settingsLanguageRegionalFree: String get() = s("settingsLanguageRegionalFree")
    val settingsEnglish: String get() = s("settingsEnglish")
    val settingsEnglishDefault: String get() = s("settingsEnglishDefault")
    val settingsTagalog: String get() = s("settingsTagalog")
    val settingsLanguageLocked: String get() = s("settingsLanguageLocked")
    val settingsLanguagePaywallTitle: String get() = s("settingsLanguagePaywallTitle")
    val settingsLanguagePaywallBody: String get() = s("settingsLanguagePaywallBody")
    val settingsLanguagePaywallCta: String get() = s("settingsLanguagePaywallCta")
    val settingsLanguagePreviewIn: String get() = s("settingsLanguagePreviewIn")

    // Settings — Reminder
    val settingsDailyReminder: String get() = s("settingsDailyReminder")
    val settingsQuickPick: String get() = s("settingsQuickPick")
    val settingsLongPress: String get() = s("settingsLongPress")
    val settingsCustomTime: String get() = s("settingsCustomTime")
    val settingsDailyAt: String get() = s("settingsDailyAt")
    val settingsChange: String get() = s("settingsChange")
    val settingsReminderUpdated: String get() = s("settingsReminderUpdated")
    val settingsPreviewReminder: String get() = s("settingsPreviewReminder")
    val settingsPreviewSent: String get() = s("settingsPreviewSent")
    val settingsPreviewSentReal: String get() = s("settingsPreviewSentReal")
    val settingsNotificationsBlocked: String get() = s("settingsNotificationsBlocked")
    val settingsReminderTime: String get() = s("settingsReminderTime")

    // Settings — Streak
    val settingsStreakProtection: String get() = s("settingsStreakProtection")
    val settingsEveningAlert: String get() = s("settingsEveningAlert")
    val settingsEveningAlertDesc: String get() = s("settingsEveningAlertDesc")
    val settingsCheckupTime: String get() = s("settingsCheckupTime")
    val settingsStreakUpdated: String get() = s("settingsStreakUpdated")
    val settingsStreakSilent: String get() = s("settingsStreakSilent")
    val settingsPreviewStreak: String get() = s("settingsPreviewStreak")
    val settingsStreakCheckupTime: String get() = s("settingsStreakCheckupTime")
    val settingsStreakCheckupDesc: String get() = s("settingsStreakCheckupDesc")

    // Settings — Recap
    val settingsWeeklyRecap: String get() = s("settingsWeeklyRecap")
    val settingsSundayRecap: String get() = s("settingsSundayRecap")
    val settingsSundayRecapDesc: String get() = s("settingsSundayRecapDesc")
    val settingsRecapArrives: String get() = s("settingsRecapArrives")
    val settingsPreviewRecap: String get() = s("settingsPreviewRecap")
    val settingsRecapPreviewSent: String get() = s("settingsRecapPreviewSent")

    // Settings — Streak share
    val settingsYourStreak: String get() = s("settingsYourStreak")
    val settingsShareProgress: String get() = s("settingsShareProgress")
    val settingsShare: String get() = s("settingsShare")

    // Settings — About
    val settingsAbout: String get() = s("settingsAbout")
    val settingsVersion: String get() = s("settingsVersion")
    val settingsAboutDesc: String get() = s("settingsAboutDesc")
    val settingsPrivacyPolicy: String get() = s("settingsPrivacyPolicy")
    val settingsHowData: String get() = s("settingsHowData")
    val settingsViewPrivacy: String get() = s("settingsViewPrivacy")
    val settingsFollow: String get() = s("settingsFollow")
    val settingsFollowOn: String get() = s("settingsFollowOn")
    val settingsQuestions: String get() = s("settingsQuestions")
    val settingsGotIt: String get() = s("settingsGotIt")

    // Settings — Premium benefits
    val settingsWhatsInPremium: String get() = s("settingsWhatsInPremium")
    val settingsActive: String get() = s("settingsActive")
    val settingsTrial: String get() = s("settingsTrial")
    val settingsPremiumBodyPurchased: String get() = s("settingsPremiumBodyPurchased")
    val settingsPremiumBodyTrial: String get() = s("settingsPremiumBodyTrial")
    val settingsPremiumBodyFree: String get() = s("settingsPremiumBodyFree")
    val settingsGetPremium: String get() = s("settingsGetPremium")
    val settingsYourBenefits: String get() = s("settingsYourBenefits")
    val settingsAsPremium: String get() = s("settingsAsPremium")
    val settingsExtendedPrompts: String get() = s("settingsExtendedPrompts")
    val settingsExtendedPromptsDesc: String get() = s("settingsExtendedPromptsDesc")
    val settingsExclusiveQuotes: String get() = s("settingsExclusiveQuotes")
    val settingsExclusiveQuotesDesc: String get() = s("settingsExclusiveQuotesDesc")
    val settingsAdvancedInsights: String get() = s("settingsAdvancedInsights")
    val settingsAdvancedInsightsDesc: String get() = s("settingsAdvancedInsightsDesc")
    val settingsAccentThemes: String get() = s("settingsAccentThemes")
    val settingsAccentThemesDesc: String get() = s("settingsAccentThemesDesc")
    val settingsAdFree: String get() = s("settingsAdFree")
    val settingsAdFreeDesc: String get() = s("settingsAdFreeDesc")
    val settingsUnlimitedHabits: String get() = s("settingsUnlimitedHabits")
    val settingsUnlimitedHabitsDesc: String get() = s("settingsUnlimitedHabitsDesc")
    val settingsPdfReports: String get() = s("settingsPdfReports")
    val settingsPdfReportsDesc: String get() = s("settingsPdfReportsDesc")
    val settingsSeePlansBtn: String get() = s("settingsSeePlansBtn")
    val settingsClose: String get() = s("settingsClose")

    // Settings — Profile
    val settingsLocalUser: String get() = s("settingsLocalUser")
    val settingsProPurchased: String get() = s("settingsProPurchased")
    val settingsPremiumMember: String get() = s("settingsPremiumMember")
    val settingsFreePlan: String get() = s("settingsFreePlan")
    val settingsPro: String get() = s("settingsPro")
    val settingsPremium: String get() = s("settingsPremium")
    val settingsProBadgePlay: String get() = s("settingsProBadgePlay")
    val settingsProBadgePremium: String get() = s("settingsProBadgePremium")
    val settingsUpgradeBadge: String get() = s("settingsUpgradeBadge")
    val settingsFoundingMemberBadge: String get() = s("settingsFoundingMemberBadge")

    // ── Premium Sheet ───────────────────────────────────────
    val premiumEyebrow: String get() = s("premiumEyebrow")
    val premiumHeadline: String get() = s("premiumHeadline")
    val premiumLimitedOffer: String get() = s("premiumLimitedOffer")
    val premiumBestValue: String get() = s("premiumBestValue")
    val premium7DaysFree: String get() = s("premium7DaysFree")
    val premiumChoosePlan: String get() = s("premiumChoosePlan")
    val premiumTrialBody: String get() = s("premiumTrialBody")
    val premiumNoTrialBody: String get() = s("premiumNoTrialBody")
    val premiumStartTrial: String get() = s("premiumStartTrial")
    val premiumSubscribe: String get() = s("premiumSubscribe")
    val premiumCancelAnytime: String get() = s("premiumCancelAnytime")
    val premiumAutoRenews: String get() = s("premiumAutoRenews")
    val premiumPrivacyPolicy: String get() = s("premiumPrivacyPolicy")
    val premiumRestore: String get() = s("premiumRestore")
    val premiumMaybeLater: String get() = s("premiumMaybeLater")
    val premiumToday: String get() = s("premiumToday")
    val premiumFree: String get() = s("premiumFree")
    val premiumAfter7Days: String get() = s("premiumAfter7Days")
    val premiumLoadingOffer: String get() = s("premiumLoadingOffer")
    val premiumFirstMonth: String get() = s("premiumFirstMonth")
    val premiumSpotsLeft: String get() = s("premiumSpotsLeft")
    val premiumOfflineNotice: String get() = s("premiumOfflineNotice")
    val premiumTrustCancel: String get() = s("premiumTrustCancel")
    val premiumTrustPlaySecured: String get() = s("premiumTrustPlaySecured")
    val premiumTrustInstant: String get() = s("premiumTrustInstant")

    // ── Trial expiring dialog ───────────────────────────────
    val trialExpiringTitle: String get() = s("trialExpiringTitle")
    val trialExpiringBody: String get() = s("trialExpiringBody")
    val trialExpiringCta: String get() = s("trialExpiringCta")
    val trialExpiringDismiss: String get() = s("trialExpiringDismiss")

    // ── Review prompt ───────────────────────────────────────
    val reviewTitle: String get() = s("reviewTitle")
    val reviewBody: String get() = s("reviewBody")
    val reviewRate: String get() = s("reviewRate")
    val reviewLater: String get() = s("reviewLater")

    // ── Achievement badges ─────────────────────────────────
    val badgeSectionTitle: String get() = s("badgeSectionTitle")
    val badgeSectionSubtitle: String get() = s("badgeSectionSubtitle")
    val badge3Title: String get() = s("badge3Title")
    val badge3Desc: String get() = s("badge3Desc")
    val badge7Title: String get() = s("badge7Title")
    val badge7Desc: String get() = s("badge7Desc")
    val badge14Title: String get() = s("badge14Title")
    val badge14Desc: String get() = s("badge14Desc")
    val badge30Title: String get() = s("badge30Title")
    val badge30Desc: String get() = s("badge30Desc")
    val badgeNew: String get() = s("badgeNew")
    val badgeUnlocked: String get() = s("badgeUnlocked")
    val badgeShareCta: String get() = s("badgeShareCta")
    val badgeKeepGoing: String get() = s("badgeKeepGoing")

    // ── Companion mood indicator ─────────────────────────
    val companionEmpty: String get() = s("companionEmpty")
    val companionNone: String get() = s("companionNone")
    val companionPartial: String get() = s("companionPartial")
    val companionAllDone: String get() = s("companionAllDone")

    // ── Ad Pass / Feature Lock ────────────────────────────────
    val adPassDialogTitle: String get() = s("adPassDialogTitle")
    val adPassDialogBody: String get() = s("adPassDialogBody")
    val adPassWatchAd: String get() = s("adPassWatchAd")
    val adPassWatchAdDesc: String get() = s("adPassWatchAdDesc")
    val adPassBuyPremium: String get() = s("adPassBuyPremium")
    val adPassBuyPremiumDesc: String get() = s("adPassBuyPremiumDesc")
    val adPassDailyCapReached: String get() = s("adPassDailyCapReached")
    val adPassGranted: String get() = s("adPassGranted")
    val adPassNotReady: String get() = s("adPassNotReady")
    val adPassMaybeLater: String get() = s("adPassMaybeLater")
    val adPassActiveBanner: String get() = s("adPassActiveBanner")
    val adPassMinutesLeft: String get() = s("adPassMinutesLeft")
    val habitsSoftLocked: String get() = s("habitsSoftLocked")
    val adPassBannerTitle: String get() = s("adPassBannerTitle")
    val adPassBannerBody: String get() = s("adPassBannerBody")
    val adPassBannerCta: String get() = s("adPassBannerCta")
    val adPassCreditsToday: String get() = s("adPassCreditsToday")

    // ── Settings — premium language & theme benefits ───────
    val settingsAllLanguages: String get() = s("settingsAllLanguages")
    val settingsAllLanguagesDesc: String get() = s("settingsAllLanguagesDesc")

    // ── Settings — free language tag ─────────────────────
    val settingsFreeTag: String get() = s("settingsFreeTag")

    // ── Notifications — shared ───────────────────────
    val ntfPreviewNote: String get() = s("ntfPreviewNote")
    val ntfPreviewTag: String get() = s("ntfPreviewTag")
    val ntfActionCheckIn: String get() = s("ntfActionCheckIn")

    // ── Notifications — daily check-in ─────────────────
    val ntfChannelCheckInName: String get() = s("ntfChannelCheckInName")
    val ntfChannelCheckInDesc: String get() = s("ntfChannelCheckInDesc")
    val ntfCheckInEmptyTitle: String get() = s("ntfCheckInEmptyTitle")
    val ntfCheckInEmptyText: String get() = s("ntfCheckInEmptyText")
    val ntfCheckInEmptyBig: String get() = s("ntfCheckInEmptyBig")
    val ntfCheckInMissedTitle: String get() = s("ntfCheckInMissedTitle")
    val ntfCheckInMissedText: String get() = s("ntfCheckInMissedText")
    val ntfCheckInMissedBig: String get() = s("ntfCheckInMissedBig")
    val ntfCheckInStreakTitle: String get() = s("ntfCheckInStreakTitle")
    val ntfCheckInStreakText: String get() = s("ntfCheckInStreakText")
    val ntfCheckInStreakBig30: String get() = s("ntfCheckInStreakBig30")
    val ntfCheckInStreakBig7: String get() = s("ntfCheckInStreakBig7")
    val ntfCheckInStreakBigLow: String get() = s("ntfCheckInStreakBigLow")
    val ntfCheckInDoneTitle: String get() = s("ntfCheckInDoneTitle")
    val ntfCheckInDoneText: String get() = s("ntfCheckInDoneText")
    val ntfCheckInDoneBig: String get() = s("ntfCheckInDoneBig")
    val ntfCheckInDefaultTitle: String get() = s("ntfCheckInDefaultTitle")
    val ntfCheckInDefaultText: String get() = s("ntfCheckInDefaultText")
    val ntfCheckInDefaultBig: String get() = s("ntfCheckInDefaultBig")

    // ── Notifications — streak protection ───────────────
    val ntfChannelStreakName: String get() = s("ntfChannelStreakName")
    val ntfChannelStreakDesc: String get() = s("ntfChannelStreakDesc")
    val ntfStreakPreviewTitle: String get() = s("ntfStreakPreviewTitle")
    val ntfStreakPreviewText: String get() = s("ntfStreakPreviewText")
    val ntfStreakPreviewBig: String get() = s("ntfStreakPreviewBig")
    val ntfStreakRiskTitle: String get() = s("ntfStreakRiskTitle")
    val ntfStreakRiskText: String get() = s("ntfStreakRiskText")
    val ntfStreakRiskBig30: String get() = s("ntfStreakRiskBig30")
    val ntfStreakRiskBig7: String get() = s("ntfStreakRiskBig7")
    val ntfStreakRiskBigLow: String get() = s("ntfStreakRiskBigLow")
    val ntfStreakNoneTitle: String get() = s("ntfStreakNoneTitle")
    val ntfStreakNoneText: String get() = s("ntfStreakNoneText")
    val ntfStreakNoneBig: String get() = s("ntfStreakNoneBig")
    val ntfStreakPartialTitleOne: String get() = s("ntfStreakPartialTitleOne")
    val ntfStreakPartialTitleMany: String get() = s("ntfStreakPartialTitleMany")
    val ntfStreakPartialText: String get() = s("ntfStreakPartialText")
    val ntfStreakPartialBig: String get() = s("ntfStreakPartialBig")

    // ── Notifications — weekly recap ───────────────────
    val ntfChannelRecapName: String get() = s("ntfChannelRecapName")
    val ntfChannelRecapDesc: String get() = s("ntfChannelRecapDesc")
    val ntfRecapPreviewTitle: String get() = s("ntfRecapPreviewTitle")
    val ntfRecapPreviewText: String get() = s("ntfRecapPreviewText")
    val ntfRecapPreviewBig: String get() = s("ntfRecapPreviewBig")
    val ntfRecapPerfectTitle: String get() = s("ntfRecapPerfectTitle")
    val ntfRecapPerfectText: String get() = s("ntfRecapPerfectText")
    val ntfRecapPerfectBig: String get() = s("ntfRecapPerfectBig")
    val ntfRecapStrongTitle: String get() = s("ntfRecapStrongTitle")
    val ntfRecapStrongText: String get() = s("ntfRecapStrongText")
    val ntfRecapStrongBig: String get() = s("ntfRecapStrongBig")
    val ntfRecapMidTitle: String get() = s("ntfRecapMidTitle")
    val ntfRecapMidText: String get() = s("ntfRecapMidText")
    val ntfRecapMidBig: String get() = s("ntfRecapMidBig")
    val ntfRecapLowTitle: String get() = s("ntfRecapLowTitle")
    val ntfRecapLowText: String get() = s("ntfRecapLowText")
    val ntfRecapLowBig: String get() = s("ntfRecapLowBig")
    val ntfRecapEmptyTitle: String get() = s("ntfRecapEmptyTitle")
    val ntfRecapEmptyText: String get() = s("ntfRecapEmptyText")
    val ntfRecapEmptyBig: String get() = s("ntfRecapEmptyBig")
    val ntfRecapMoodLine: String get() = s("ntfRecapMoodLine")
    val ntfRecapPerfectDaysOne: String get() = s("ntfRecapPerfectDaysOne")
    val ntfRecapPerfectDaysMany: String get() = s("ntfRecapPerfectDaysMany")
    val ntfRecapShareAction: String get() = s("ntfRecapShareAction")
    val ntfRecapShareChooser: String get() = s("ntfRecapShareChooser")
    val ntfRecapShareText: String get() = s("ntfRecapShareText")

    // ── Notifications — companion & reflection ────────────
    val ntfChannelCompanionName: String get() = s("ntfChannelCompanionName")
    val ntfChannelCompanionDesc: String get() = s("ntfChannelCompanionDesc")
    val ntfChannelReflectionName: String get() = s("ntfChannelReflectionName")
    val ntfChannelReflectionDesc: String get() = s("ntfChannelReflectionDesc")
    val ntfTrialTitle: String get() = s("ntfTrialTitle")
    val ntfTrialText: String get() = s("ntfTrialText")
    val ntfTrialBig: String get() = s("ntfTrialBig")
    val ntfTrialActionKeep: String get() = s("ntfTrialActionKeep")
    val ntfTrialActionManage: String get() = s("ntfTrialActionManage")
    val ntfAdPassTitle: String get() = s("ntfAdPassTitle")
    val ntfAdPassText: String get() = s("ntfAdPassText")
    val ntfAdPassBig: String get() = s("ntfAdPassBig")
    val ntfAdPassAction: String get() = s("ntfAdPassAction")
    val ntfReflectionTitle: String get() = s("ntfReflectionTitle")
    val ntfReflectionBigSuffix: String get() = s("ntfReflectionBigSuffix")
    val ntfReflection1: String get() = s("ntfReflection1")
    val ntfReflection2: String get() = s("ntfReflection2")
    val ntfReflection3: String get() = s("ntfReflection3")
    val ntfReflection4: String get() = s("ntfReflection4")
    val ntfReflection5: String get() = s("ntfReflection5")
    val ntfReflection6: String get() = s("ntfReflection6")
    val ntfReflection7: String get() = s("ntfReflection7")

    // ── Companion Studio ─────────────────────────────
    val studioTitle: String get() = s("studioTitle")
    val studioSubtitle: String get() = s("studioSubtitle")
    val studioCatFrame: String get() = s("studioCatFrame")
    val studioCatSkin: String get() = s("studioCatSkin")
    val studioCatFace: String get() = s("studioCatFace")
    val studioCatEyes: String get() = s("studioCatEyes")
    val studioCatMouth: String get() = s("studioCatMouth")
    val studioCatHair: String get() = s("studioCatHair")
    val studioCatHairColor: String get() = s("studioCatHairColor")
    val studioCatOutfit: String get() = s("studioCatOutfit")
    val studioCatCompanion: String get() = s("studioCatCompanion")
    val studioLockedStreak: String get() = s("studioLockedStreak")
    val studioLockedFounding: String get() = s("studioLockedFounding")
    val studioNone: String get() = s("studioNone")
    val studioCatGender: String get() = s("studioCatGender")
    val studioCatExpression: String get() = s("studioCatExpression")
    val studioFemale: String get() = s("studioFemale")
    val studioMale: String get() = s("studioMale")
    val studioTaskLocked: String get() = s("studioTaskLocked")
    val studioTaskProgress: String get() = s("studioTaskProgress")
    val studioUnlockedNew: String get() = s("studioUnlockedNew")
    val studioEarnHint: String get() = s("studioEarnHint")
    val studioTaskCompleteAllToday: String get() = s("studioTaskCompleteAllToday")
    val studioTaskCheckinStreak: String get() = s("studioTaskCheckinStreak")
    val studioTaskFullStreak: String get() = s("studioTaskFullStreak")
    val studioTaskTotalCheckins: String get() = s("studioTaskTotalCheckins")
    val studioTaskReflections: String get() = s("studioTaskReflections")
    val studioTaskMoods: String get() = s("studioTaskMoods")

    // ── Companion bubble (in-app notifications) ──────
    val bubbleUnlockReady: String get() = s("bubbleUnlockReady")
    val bubbleHabitsLeftOne: String get() = s("bubbleHabitsLeftOne")
    val bubbleHabitsLeftMany: String get() = s("bubbleHabitsLeftMany")
    val bubbleStreak: String get() = s("bubbleStreak")
    val bubbleAdPass: String get() = s("bubbleAdPass")
    val bubbleUpdates: String get() = s("bubbleUpdates")

    /**
     * Direct table lookup for generated keys — pet, outfit, and expression
     * display names built from catalog ids (e.g. "petOwl", "outfitCape").
     */
    fun nameFor(key: String): String = s(key)

    // ── Grounding toolkit ────────────────────────────
    val groundingTitle: String get() = s("groundingTitle")
    val groundingEntrySub: String get() = s("groundingEntrySub")
    val groundingTabBreathe: String get() = s("groundingTabBreathe")
    val groundingTabSenses: String get() = s("groundingTabSenses")
    val groundingTabNote: String get() = s("groundingTabNote")
    val breatheHint: String get() = s("breatheHint")
    val breatheInhale: String get() = s("breatheInhale")
    val breatheHold: String get() = s("breatheHold")
    val breatheExhale: String get() = s("breatheExhale")
    val breatheStart: String get() = s("breatheStart")
    val breathePause: String get() = s("breathePause")
    val breatheReset: String get() = s("breatheReset")
    val breatheCycles: String get() = s("breatheCycles")
    val sensesIntro: String get() = s("sensesIntro")
    val senses5: String get() = s("senses5")
    val senses4: String get() = s("senses4")
    val senses3: String get() = s("senses3")
    val senses2: String get() = s("senses2")
    val senses1: String get() = s("senses1")
    val sensesNext: String get() = s("sensesNext")
    val sensesDone: String get() = s("sensesDone")
    val sensesRestart: String get() = s("sensesRestart")
    val noteTitle: String get() = s("noteTitle")
    val notePlaceholder: String get() = s("notePlaceholder")
    val noteSave: String get() = s("noteSave")
    val noteSaved: String get() = s("noteSaved")
    val noteRecent: String get() = s("noteRecent")

    // ── Month in Pixels ─────────────────────────────
    val pixelsTitle: String get() = s("pixelsTitle")
    val pixelsSub: String get() = s("pixelsSub")
    val pixelsNone: String get() = s("pixelsNone")

    // ── Settings — companion & wellbeing card ────────────
    val settingsWellbeingTitle: String get() = s("settingsWellbeingTitle")
    val settingsWellbeingStudioDesc: String get() = s("settingsWellbeingStudioDesc")
    val settingsWellbeingGroundingDesc: String get() = s("settingsWellbeingGroundingDesc")
    val settingsWellbeingPixelsDesc: String get() = s("settingsWellbeingPixelsDesc")
    val settingsOpen: String get() = s("settingsOpen")
}

// ── JSON-based loading ──────────────────────────────────────

private val stringsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Per-language cache of built string tables. Only populated once the
 * English asset loads successfully, so a too-early call (before
 * [LocalizationManager.init]) never poisons the cache with empty tables.
 */
private val stringsCache = mutableMapOf<String, AppStrings>()

/**
 * Returns the string table for the given language.
 *
 * Loads JSON from `assets/strings/{code}.json`, merges it over the English
 * base so any missing key falls back to the English value, and wraps the
 * merged map in [AppStrings].
 */
fun stringsFor(language: AppLanguage): AppStrings {
    stringsCache[language.code]?.let { return it }
    val en = loadStringTable("en")
    val merged = if (language.code == "en") en else en + loadStringTable(language.code)
    val result = AppStrings(merged)
    if (en.isNotEmpty()) stringsCache[language.code] = result
    return result
}

/** Parses `assets/strings/{code}.json` into a flat key→value map. */
private fun loadStringTable(code: String): Map<String, String> {
    val raw = LocalizationManager.loadStringJson(code) ?: return emptyMap()
    return try {
        val obj = stringsJson.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        buildMap {
            obj.forEach { (key, value) ->
                (value as? JsonPrimitive)?.let { put(key, it.content) }
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * CompositionLocal that provides the active string table. Screens access it
 * via [appStrings] — they never read this directly.
 */
val LocalAppStrings = staticCompositionLocalOf<AppStrings?> { null }

/**
 * Convenience accessor for the current string table inside composables.
 * Falls back to a computed English instance if no provider is set.
 */
@Composable
fun appStrings(): AppStrings {
    return LocalAppStrings.current ?: stringsFor(AppLanguage.ENGLISH_US)
}
