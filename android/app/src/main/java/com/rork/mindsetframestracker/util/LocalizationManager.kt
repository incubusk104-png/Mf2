package com.rork.mindsetframestracker.util

import android.content.Context
import com.rork.mindsetframestracker.data.AppLanguage
import com.rork.mindsetframestracker.data.LanguageContent
import com.rork.mindsetframestracker.data.ModeCopy
import com.rork.mindsetframestracker.data.QuoteObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Centralised asset loader for JSON-based i18n resources.
 *
 * Loads UI string JSON and mood content JSON from `assets/strings/{code}.json`
 * and `assets/quotes/quotes_{code}.json` respectively. Results are cached in
 * memory so language switches are instant after the first load.
 *
 * English (`en`) is always the fallback — any missing key in a target language
 * falls back to the English value.
 *
 * NOTE: We parse the JSON manually instead of using `@Serializable` data classes.
 * The large nested `LanguageContent` structure generated a deserializer that R8
 * could over-optimize into a method with >64 DEX registers, causing a
 * `java.lang.VerifyError` on release builds. Manual parsing keeps the bytecode
 * simple and avoids the generated serializer entirely.
 */
object LocalizationManager {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private var appContext: Context? = null

    private val contentCache = mutableMapOf<String, LanguageContent>()
    private val rawStringCache = mutableMapOf<String, String?>()

    /** Must be called once at app launch (e.g. from AppViewModel.init). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Loads the raw JSON string for UI strings, or null if the file is missing. */
    fun loadStringJson(code: String): String? {
        rawStringCache[code]?.let { return it }
        val ctx = appContext ?: return null
        val result = try {
            ctx.assets.open("strings/$code.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
        rawStringCache[code] = result
        return result
    }

    /** Loads, caches, and returns [LanguageContent] for [language] with English fallback. */
    fun contentFor(language: AppLanguage): LanguageContent {
        val code = language.code
        contentCache[code]?.let { return it }

        val ctx = appContext ?: return LanguageContent()
        val enContent = loadContentFile(ctx, "en") ?: LanguageContent()

        if (code == "en") {
            contentCache[code] = enContent
            return enContent
        }

        val langContent = loadContentFile(ctx, code)
        val merged = if (langContent != null) mergeContent(enContent, langContent) else enContent
        contentCache[code] = merged
        return merged
    }

    /** Clears all caches — useful for testing or forced reload. */
    fun clearCache() {
        contentCache.clear()
        rawStringCache.clear()
    }

    private fun loadContentFile(ctx: Context, code: String): LanguageContent? {
        return try {
            val text = ctx.assets.open("quotes/quotes_$code.json").bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(text).jsonObject
            LanguageContent(
                modeCopy = parseModeCopyMap(root["modeCopy"]?.jsonObject),
                prompts = parseStringListMap(root["prompts"]?.jsonObject),
                premiumPrompts = parseStringListMap(root["premiumPrompts"]?.jsonObject),
                quotes = parseQuoteListMap(root["quotes"]?.jsonObject),
                premiumQuotes = parseQuoteListMap(root["premiumQuotes"]?.jsonObject),
                starterHabits = parseStringList(root["starterHabits"]?.jsonArray),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun mergeContent(base: LanguageContent, override: LanguageContent): LanguageContent {
        return LanguageContent(
            modeCopy = base.modeCopy + override.modeCopy,
            prompts = mergeMapLists(base.prompts, override.prompts),
            premiumPrompts = mergeMapLists(base.premiumPrompts, override.premiumPrompts),
            quotes = mergeMapLists(base.quotes, override.quotes),
            premiumQuotes = mergeMapLists(base.premiumQuotes, override.premiumQuotes),
            starterHabits = override.starterHabits.ifEmpty { base.starterHabits },
        )
    }

    private fun <V> mergeMapLists(
        base: Map<String, List<V>>,
        override: Map<String, List<V>>,
    ): Map<String, List<V>> {
        val result = base.toMutableMap()
        override.forEach { (key, value) ->
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    // ── Manual JSON parsers ─────────────────────────────────────────────────

    private fun parseModeCopyMap(obj: JsonObject?): Map<String, ModeCopy> {
        if (obj == null) return emptyMap()
        return obj.mapValues { (_, value) ->
            val o = value.jsonObject
            ModeCopy(
                label = stringOrEmpty(o, "label"),
                tagline = stringOrEmpty(o, "tagline"),
                promptHeader = stringOrEmpty(o, "promptHeader"),
                habitsHeader = stringOrEmpty(o, "habitsHeader"),
                habitsSub = stringOrEmpty(o, "habitsSub"),
                allDone = stringOrEmpty(o, "allDone"),
                emptyHabits = stringOrEmpty(o, "emptyHabits"),
            )
        }
    }

    private fun parseStringListMap(obj: JsonObject?): Map<String, List<String>> {
        if (obj == null) return emptyMap()
        return obj.mapValues { (_, value) ->
            value.jsonArray.map { it.jsonPrimitive.content }
        }
    }

    private fun parseQuoteListMap(obj: JsonObject?): Map<String, List<QuoteObject>> {
        if (obj == null) return emptyMap()
        return obj.mapValues { (_, value) ->
            value.jsonArray.map { element ->
                val o = element.jsonObject
                QuoteObject(
                    id = stringOrEmpty(o, "id"),
                    category = stringOrEmpty(o, "category"),
                    text = stringOrEmpty(o, "text"),
                    author = stringOrEmpty(o, "author"),
                    mood_tag = stringOrEmpty(o, "mood_tag"),
                )
            }
        }
    }

    private fun parseStringList(array: JsonArray?): List<String> {
        if (array == null) return emptyList()
        return array.map { it.jsonPrimitive.content }
    }

    private fun stringOrEmpty(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.content ?: ""
}
