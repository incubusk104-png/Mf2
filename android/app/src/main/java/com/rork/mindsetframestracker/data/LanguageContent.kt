package com.rork.mindsetframestracker.data

/**
 * Structured quote object for JSON-based quote storage.
 * Each quote in the per-language JSON files uses this structure.
 *
 * NOTE: This class is intentionally NOT `@Serializable`. The large nested
 * structure of [LanguageContent] caused Kotlinx.serialization's generated
 * deserializer to be over-optimized by R8 into a method with >64 DEX
 * registers, producing a `VerifyError` on release builds. We parse the
 * JSON manually with [com.rork.mindsetframestracker.util.LocalizationManager]
 * instead.
 */
data class QuoteObject(
    val id: String,
    val category: String,
    val text: String,
    val author: String,
    val mood_tag: String,
)

/**
 * All mood-themed content for a single language, loaded from
 * `assets/quotes/quotes_{code}.json`. Falls back to English for
 * any missing moods or fields.
 *
 * NOTE: This class is intentionally NOT `@Serializable`. See [QuoteObject]
 * for the rationale.
 */
data class LanguageContent(
    val modeCopy: Map<String, ModeCopy> = emptyMap(),
    val prompts: Map<String, List<String>> = emptyMap(),
    val premiumPrompts: Map<String, List<String>> = emptyMap(),
    val quotes: Map<String, List<QuoteObject>> = emptyMap(),
    val premiumQuotes: Map<String, List<QuoteObject>> = emptyMap(),
    val starterHabits: List<String> = emptyList(),
)
