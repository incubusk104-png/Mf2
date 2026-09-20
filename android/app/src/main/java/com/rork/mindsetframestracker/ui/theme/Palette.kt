package com.rork.mindsetframestracker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Mf2 design tokens — SINGLE SOURCE OF TRUTH.
 *
 * The user's original dark-mode palette, sampled directly from the reference
 * screenshot (1080x2400, "Your habits" screen):
 *
 *   card 1 of 6  dark olive green       #283120
 *   card 2 of 6  dark slate blue        #1A2E37
 *   card 3 of 6  deep forest green      #2B3123
 *   card 4 of 6  dark indigo / purple   #222431
 *   card 5 of 6  dark teal              #1A2C2C
 *   card 6 of 6  dark chocolate brown   #382D1B
 *   accent       muted green            #5E755B  (icon artwork)
 *   accent link  brighter muted green   #7E9A78  ("Connect fitness trackers")
 *   headings     warm ivory serif       #F7F4EC  (DisplayFontFamily, dm_serif_display)
 *   background   near-black warm        #140F09
 *
 * EVERY colour in the app must come from this file. Do not inline `Color(0x…)`
 * literals in screens or components — reference a token instead.
 *
 * Contrast (WCAG, measured vs each surface):
 *   ivory heading   12.2:1 – 17.3:1  (AAA)
 *   secondary text   5.4:1 –  7.7:1  (AA)
 *   accent bright    6.2:1           (AA)
 *   accent muted     3.8:1           (AA Large / decorative artwork only)
 */
object Mf2Palette {

    // ─────────────────────────────────────────────────────────────────────
    // 1. DARK SURFACE STACK — warm near-black, sampled from the reference
    // ─────────────────────────────────────────────────────────────────────
    /** App background — the near-black behind every card. */
    val DarkBackground = Color(0xFF140F09)
    /** Slightly lifted container (bottom sheets, dialogs). */
    val DarkSurface = Color(0xFF1A140B)
    /** Higher elevation layer (picker rows, inputs). */
    val DarkSurfaceVariant = Color(0xFF2C2416)
    /** Sheet / modal background from the reference bottom sheet. */
    val DarkSheet = Color(0xFF1C1610)
    /** Nav bar + status bar chrome. */
    val DarkChrome = Color(0xFF140F09)
    /** Hairline dividers inside sheets. */
    val DarkOutlineVariant = Color(0xFF3B3222)
    val DarkOutline = Color(0xFFA99D86)

    // ─────────────────────────────────────────────────────────────────────
    // 2. LIGHT SURFACE STACK — warm cream (unchanged, brand cream from logo)
    // ─────────────────────────────────────────────────────────────────────
    val LightBackground = Color(0xFFFAF3E9)
    val LightSurface = Color(0xFFFFFDF7)
    val LightSurfaceVariant = Color(0xFFF0E6D7)
    val LightSheet = Color(0xFFFFFDF7)
    val LightOutlineVariant = Color(0xFFDCD3C2)
    val LightOutline = Color(0xFF8B8171)

    // ─────────────────────────────────────────────────────────────────────
    // 3. FOREGROUND / TEXT — white serif headings on warm dark
    // ─────────────────────────────────────────────────────────────────────
    /** Headings + primary text. Pure-feeling ivory, not clinical #FFFFFF. */
    val OnDark = Color(0xFFF7F4EC)
    /** Screen titles are the SAME ivory — the serif face carries the display weight. */
    val OnDarkHeading = Color(0xFFF7F4EC)
    /** Card titles (habit names) on a tinted card. */
    val OnDarkCardTitle = Color(0xFFF7F4EC)
    /** Secondary / subtitle text on background. Sampled #A7A49B. */
    val OnDarkMuted = Color(0xFFA7A49B)
    /** Subtitle text inside a tinted card (slightly warmer). */
    val OnDarkCardMuted = Color(0xFFCFCBC0)
    val OnLight = Color(0xFF2B241B)
    val OnLightMuted = Color(0xFF5D5546)

    // ─────────────────────────────────────────────────────────────────────
    // 4. ACCENT — the muted green
    // ─────────────────────────────────────────────────────────────────────
    /** Muted green used for icon artwork (large shapes / decorative only). */
    val AccentMuted = Color(0xFF5E755B)
    /** Brighter muted green for links, active nav, focus rings, CTAs on dark. */
    val AccentBright = Color(0xFF7E9A78)
    /** Extra-lifted accent for hover/pressed and small accents on dark cards. */
    val AccentBrightHigh = Color(0xFF8CA482)
    /** Text/icon colour on top of an accent-filled button. */
    val OnAccent = Color(0xFF10130F)
    /** Darker accent for use on the cream light surfaces. */
    val AccentOnLight = Color(0xFF41603F)
    /** Accent container (soft tint) on dark. */
    val AccentContainerDark = Color(0xFF26301F)
    val OnAccentContainerDark = Color(0xFFC9D6BE)
    /** Accent container on light. */
    val AccentContainerLight = Color(0xFFE3EDE0)

    // ─────────────────────────────────────────────────────────────────────
    // 5. SEMANTIC
    // ─────────────────────────────────────────────────────────────────────
    val Success = Color(0xFF7E9A78)
    val Warning = Color(0xFFD9A44C)
    val Error = Color(0xFFC4796A)
    val Info = Color(0xFF6E93A8)
    /** "Set up alarm" attention state (kept legible on every dark card). */
    val Attention = Color(0xFFE0B054)

    // ─────────────────────────────────────────────────────────────────────
    // 6. HABIT-CARD PALETTE — the six reference colours
    //    Dark values are EXACT samples from the screenshot.
    //    Light values are the same hues lifted onto cream.
    // ─────────────────────────────────────────────────────────────────────
    enum class HabitColor { OLIVE, FOREST, SLATE, INDIGO, TEAL, CHOCOLATE }

    /** Dark-mode card fills — verbatim from the reference screenshot. */
    val HabitDark: Map<HabitColor, Color> = mapOf(
        HabitColor.OLIVE to Color(0xFF283120),      // dark olive green
        HabitColor.FOREST to Color(0xFF2B3123),     // deep forest green
        HabitColor.SLATE to Color(0xFF1A2E37),      // dark slate blue
        HabitColor.INDIGO to Color(0xFF222431),     // dark indigo / purple
        HabitColor.TEAL to Color(0xFF1A2C2C),       // dark teal
        HabitColor.CHOCOLATE to Color(0xFF382D1B),  // dark chocolate brown
    )

    /** Vivid seeds for the same six hues — used to derive light-mode tints. */
    val HabitSeed: Map<HabitColor, Color> = mapOf(
        HabitColor.OLIVE to Color(0xFF6B7F3A),
        HabitColor.FOREST to Color(0xFF4F6B3A),
        HabitColor.SLATE to Color(0xFF2F6E85),
        HabitColor.INDIGO to Color(0xFF4A4A8C),
        HabitColor.TEAL to Color(0xFF2E7A78),
        HabitColor.CHOCOLATE to Color(0xFF8A6234),
    )

    /** Light-mode card fills (seed @ 20% over cream) — exactly as in the app today. */
    val HabitLight: Map<HabitColor, Color> = mapOf(
        HabitColor.OLIVE to Color(0xFFDDDCC6),
        HabitColor.FOREST to Color(0xFFD8D8C6),
        HabitColor.SLATE to Color(0xFFD1D8D5),
        HabitColor.INDIGO to Color(0xFFD7D1D6),
        HabitColor.TEAL to Color(0xFFD1DBD2),
        HabitColor.CHOCOLATE to Color(0xFFE4D6C5),
    )

    /** Icon-artwork tint drawn on top of each card fill. */
    val HabitIconOnDark: Color = AccentMuted      // #5E755B
    val HabitIconOnLight: Color = AccentOnLight   // #41603F

    /**
     * Deterministic mapping from a habit's stored [seed] colour to one of the
     * six palette entries. Keeps every card visibly distinct (as in the
     * reference) while guaranteeing the app only ever paints palette colours.
     *
     * Nearest-hue in HSV space, so a blue habit always lands on slate/teal and
     * a green one on olive/forest.
     */
    fun habitColorForSeed(seed: Long): HabitColor {
        val r = ((seed shr 16) and 0xFF).toInt() / 255f
        val g = ((seed shr 8) and 0xFF).toInt() / 255f
        val b = (seed and 0xFF).toInt() / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var hue = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (hue < 0f) hue += 360f

        // Explicit hue bands, ordered to match the six reference swatches.
        // Verified against every seed in HabitIconCatalog: all six buckets are
        // used (chocolate 6, slate 4, indigo 3, olive 2, teal 2, forest 1).
        return when {
            hue < 45f -> HabitColor.CHOCOLATE    // warm reds, ambers, tan
            hue < 100f -> HabitColor.OLIVE       // yellow-greens
            hue < 160f -> HabitColor.FOREST      // true greens
            hue < 190f -> HabitColor.TEAL        // cyan / teal
            hue < 225f -> HabitColor.SLATE       // blues
            hue < 300f -> HabitColor.INDIGO      // violet / purple
            else -> HabitColor.CHOCOLATE         // magenta → warm brown
        }
    }

    /** Card fill for a habit seed, in the active light/dark mode. */
    fun habitCardBackground(seed: Long, isDark: Boolean): Color {
        val key = habitColorForSeed(seed)
        return if (isDark) HabitDark.getValue(key) else HabitLight.getValue(key)
    }

    /** Title / body foreground on a habit card. */
    fun habitCardTitleColor(isDark: Boolean): Color =
        if (isDark) OnDarkCardTitle else OnLight

    fun habitCardMutedColor(isDark: Boolean): Color =
        if (isDark) OnDarkCardMuted else OnLightMuted

    /** Icon artwork tint on a habit card. */
    fun habitCardIconColor(isDark: Boolean): Color =
        if (isDark) HabitIconOnDark else HabitIconOnLight
}
