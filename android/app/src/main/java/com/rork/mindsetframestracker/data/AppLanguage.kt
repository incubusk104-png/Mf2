package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Supported app languages under the dual-free model:
 *
 * - English (US & UK) is free everywhere.
 * - ONE regional language — matched to the device locale on first launch —
 *   is unlocked free automatically (e.g. Simplified Chinese in China,
 *   Tagalog in the Philippines). See [regionalLanguageFor].
 * - Every other language is a Premium exclusive gated behind the language
 *   selector in Settings.
 *
 * [displayName] is the native name shown in the UI; [englishName] is used for
 * A-Z sorting in the picker; [flagEmoji] is a regional indicator pair for
 * visual identification.
 *
 * Every language ships a full `assets/strings/{code}.json` UI translation.
 * Mood copy and starter habits live in `assets/quotes/quotes_{code}.json`;
 * any missing key or file automatically falls back to English.
 */
@Serializable
enum class AppLanguage(
    val code: String,
    val displayName: String,
    val englishName: String,
    val flagEmoji: String,
) {
    ENGLISH_US("en", "English (US)", "English (US)", "\uD83C\uDDFA\uD83C\uDDF8"),
    ENGLISH("en", "English (UK)", "English (UK)", "\uD83C\uDDEC\uD83C\uDDE7"),
    ARABIC("ar", "العربية", "Arabic", "\uD83C\uDDF8\uD83C\uDDE6"),
    BENGALI("bn", "বাংলা", "Bengali", "\uD83C\uDDE7\uD83C\uDDE9"),
    CHINESE("zh", "中文", "Chinese", "\uD83C\uDDE8\uD83C\uDDF3"),
    DUTCH("nl", "Nederlands", "Dutch", "\uD83C\uDDF3\uD83C\uDDF1"),
    FRENCH("fr", "Français", "French", "\uD83C\uDDEB\uD83C\uDDF7"),
    GERMAN("de", "Deutsch", "German", "\uD83C\uDDE9\uD83C\uDDEA"),
    GREEK("el", "Ελληνικά", "Greek", "\uD83C\uDDEC\uD83C\uDDF7"),
    HINDI("hi", "हिन्दी", "Hindi", "\uD83C\uDDEE\uD83C\uDDF3"),
    INDONESIAN("id", "Bahasa Indonesia", "Indonesian", "\uD83C\uDDEE\uD83C\uDDE9"),
    ITALIAN("it", "Italiano", "Italian", "\uD83C\uDDEE\uD83C\uDDF9"),
    JAPANESE("ja", "日本語", "Japanese", "\uD83C\uDDEF\uD83C\uDDF5"),
    KOREAN("ko", "한국어", "Korean", "\uD83C\uDDF0\uD83C\uDDF7"),
    MALAY("ms", "Bahasa Melayu", "Malay", "\uD83C\uDDF2\uD83C\uDDFE"),
    NORWEGIAN("no", "Norsk", "Norwegian", "\uD83C\uDDF3\uD83C\uDDF4"),
    POLISH("pl", "Polski", "Polish", "\uD83C\uDDF5\uD83C\uDDF1"),
    PORTUGUESE("pt", "Português", "Portuguese", "\uD83C\uDDE7\uD83C\uDDF7"),
    RUSSIAN("ru", "Русский", "Russian", "\uD83C\uDDF7\uD83C\uDDFA"),
    SPANISH("es", "Español", "Spanish", "\uD83C\uDDEA\uD83C\uDDF8"),
    SWEDISH("sv", "Svenska", "Swedish", "\uD83C\uDDF8\uD83C\uDDEA"),
    TAGALOG("tl", "Tagalog / Taglish", "Tagalog (Filipino)", "\uD83C\uDDF5\uD83C\uDDED"),
    THAI("th", "ภาษาไทย", "Thai", "\uD83C\uDDF9\uD83C\uDDED"),
    TURKISH("tr", "Türkçe", "Turkish", "\uD83C\uDDF9\uD83C\uDDF7"),
    UKRAINIAN("uk", "Українська", "Ukrainian", "\uD83C\uDDFA\uD83C\uDDE6"),
    URDU("ur", "اردو", "Urdu", "\uD83C\uDDF5\uD83C\uDDF0"),
    VIETNAMESE("vi", "Tiếng Việt", "Vietnamese", "\uD83C\uDDFB\uD83C\uDDF3"),
}

/** Default language for free-tier users and fresh installs. */
val DEFAULT_LANGUAGE: AppLanguage = AppLanguage.ENGLISH_US

/** Universally free languages — available in every region, forever. */
val universallyFreeLanguages: Set<AppLanguage> = setOf(
    AppLanguage.ENGLISH_US,
    AppLanguage.ENGLISH,
)

/** ISO 639 language code (lowercase) → supported app language. */
private val languageCodeMap: Map<String, AppLanguage> = mapOf(
    "zh" to AppLanguage.CHINESE,
    "tl" to AppLanguage.TAGALOG,
    "fil" to AppLanguage.TAGALOG,
    "ar" to AppLanguage.ARABIC,
    "bn" to AppLanguage.BENGALI,
    "nl" to AppLanguage.DUTCH,
    "fr" to AppLanguage.FRENCH,
    "de" to AppLanguage.GERMAN,
    "el" to AppLanguage.GREEK,
    "hi" to AppLanguage.HINDI,
    // Java's Locale reports Indonesian with the legacy "in" code.
    "id" to AppLanguage.INDONESIAN,
    "in" to AppLanguage.INDONESIAN,
    "it" to AppLanguage.ITALIAN,
    "ja" to AppLanguage.JAPANESE,
    "ko" to AppLanguage.KOREAN,
    "ms" to AppLanguage.MALAY,
    "no" to AppLanguage.NORWEGIAN,
    "nb" to AppLanguage.NORWEGIAN,
    "nn" to AppLanguage.NORWEGIAN,
    "pl" to AppLanguage.POLISH,
    "pt" to AppLanguage.PORTUGUESE,
    "ru" to AppLanguage.RUSSIAN,
    "es" to AppLanguage.SPANISH,
    "sv" to AppLanguage.SWEDISH,
    "th" to AppLanguage.THAI,
    "tr" to AppLanguage.TURKISH,
    "uk" to AppLanguage.UKRAINIAN,
    "ur" to AppLanguage.URDU,
    "vi" to AppLanguage.VIETNAMESE,
)

/**
 * Country fallback for devices running an English system locale inside a
 * strong local-language market (e.g. en-PH → Tagalog, en-CN → Chinese), so
 * the regional free unlock still lands where it should.
 */
private val countryFallbackMap: Map<String, AppLanguage> = mapOf(
    "PH" to AppLanguage.TAGALOG,
    "CN" to AppLanguage.CHINESE,
    "SG" to AppLanguage.CHINESE,
    "TW" to AppLanguage.CHINESE,
    "HK" to AppLanguage.CHINESE,
    "MO" to AppLanguage.CHINESE,
    "IN" to AppLanguage.HINDI,
    "PK" to AppLanguage.URDU,
    "BD" to AppLanguage.BENGALI,
    "ID" to AppLanguage.INDONESIAN,
    "MY" to AppLanguage.MALAY,
    "TH" to AppLanguage.THAI,
    "VN" to AppLanguage.VIETNAMESE,
    "JP" to AppLanguage.JAPANESE,
    "KR" to AppLanguage.KOREAN,
)

/**
 * Resolves the ONE regional language a device unlocks for free, from its
 * locale. Returns null for English locales in English-speaking regions —
 * those devices simply keep the universal English tier.
 */
fun regionalLanguageFor(locale: Locale): AppLanguage? {
    val byLanguage = languageCodeMap[locale.language.lowercase(Locale.ROOT)]
    if (byLanguage != null) return byLanguage
    return countryFallbackMap[locale.country.uppercase(Locale.ROOT)]
}

/** Languages available without Premium under the given settings. */
fun AppSettings.unlockedFreeLanguages(): Set<AppLanguage> =
    freeRegionalLanguage?.let { universallyFreeLanguages + it } ?: universallyFreeLanguages

/** True when [language] is usable without Premium under these settings. */
fun AppSettings.isLanguageUnlocked(language: AppLanguage): Boolean =
    isPremium || language in unlockedFreeLanguages()

/**
 * Picker ordering: free languages pinned first (English US default, then UK,
 * then the regional unlock), everything else alphabetical A-Z by English name.
 */
fun languagePickerOrder(regional: AppLanguage?): List<AppLanguage> {
    val pinned = buildList {
        add(AppLanguage.ENGLISH_US)
        add(AppLanguage.ENGLISH)
        if (regional != null && regional !in universallyFreeLanguages) add(regional)
    }
    return pinned + AppLanguage.entries
        .filter { it !in pinned }
        .sortedBy { it.englishName }
}
