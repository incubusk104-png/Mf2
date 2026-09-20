package com.rork.mindsetframestracker.ui.theme

import androidx.compose.ui.graphics.Color
import com.rork.mindsetframestracker.data.MoodMode

/**
 * The six habit-card hues.
 *
 * Every habit card in the app derives its fill from one of these six buckets,
 * chosen deterministically from the habit icon's seed colour by
 * [Mf2Palette.habitColorForSeed]. Keeping the set at six (rather than one
 * colour per icon) is what makes the "Your habits" grid read as a curated
 * palette instead of a rainbow.
 */
enum class HabitColor { OLIVE, SLATE, FOREST, INDIGO, TEAL, CHOCOLATE }

/**
 * One mood's accent trio: the colour used on light surfaces, the colour used
 * on dark surfaces, and the gradient swept across hero cards.
 */
data class AccentPair(val light: Color, val dark: Color, val gradient: List<Color>)

/**
 * **Mf2 palette — the app's single source of truth for colour.**
 *
 * Screens and components must reference `Mf2Palette.*`; they must never inline a
 * `Color(0x…)` literal. This file (together with [Mf2Artwork]) is the only place
 * those literals are allowed to appear, and a JVM unit test
 * (`Mf2PaletteTokenTest`) walks `src/main` and fails if one reappears anywhere
 * else.
 *
 * Token values are sampled from the reference design (see `PALETTE_TOKENS.md`);
 * the contrast ratios quoted below are measured against the token they name, not
 * estimated. Where a role is decorative only, that is stated — [AccentMuted] at
 * 2.7:1 must never carry text.
 */
object Mf2Palette {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Habit cards — the six reference fills
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Dark values are the exact samples from the reference screenshot (1080×2400,
    // dark-mode "Your habits"). Light values are the same seeds at ~20 % over the
    // cream background, so a habit keeps its identity across a theme flip.

    val HabitDark: Map<HabitColor, Color> = mapOf(
        HabitColor.OLIVE to Color(0xFF283120),      // card 1 — To-Do List
        HabitColor.SLATE to Color(0xFF1A2E37),      // card 2 — Drink Water
        HabitColor.FOREST to Color(0xFF2B3123),     // card 3 — Walk
        HabitColor.INDIGO to Color(0xFF222431),     // card 4 — Sleep Early
        HabitColor.TEAL to Color(0xFF1A2C2C),       // card 5 — Stretch
        HabitColor.CHOCOLATE to Color(0xFF382D1B),  // card 6 — Journal
    )

    val HabitLight: Map<HabitColor, Color> = mapOf(
        HabitColor.OLIVE to Color(0xFFDDDCC6),
        HabitColor.FOREST to Color(0xFFD8D8C6),
        HabitColor.SLATE to Color(0xFFD1D8D5),
        HabitColor.INDIGO to Color(0xFFD7D1D6),
        HabitColor.TEAL to Color(0xFFD1DBD2),
        HabitColor.CHOCOLATE to Color(0xFFE4D6C5),
    )

    /**
     * Maps a habit icon's seed colour to one of the six [HabitColor] buckets.
     *
     * Deterministic and pure (no Android dependency, so it is unit-testable):
     * the seed is converted to HSV and its hue banded. The band edges are
     * placed so that all 18 seeds in `HabitIconCatalog` resolve to a stable
     * bucket and every one of the six is reached — the catalog resolves to
     * chocolate 5, slate 4, forest 3, olive 2, indigo 2, teal 2.
     *
     * Saturation/value are deliberately ignored: two seeds that differ only in
     * lightness should still read as the same family, and the token table is
     * what supplies the final contrast.
     */
    fun habitColorForSeed(seed: Long): HabitColor = habitColorForHue(hueOf(seed))

    /** Card fill for [seed]. Pass the habit icon's `colorHex`. */
    fun habitCardBackground(seed: Long, isDark: Boolean): Color =
        habitCardBackgroundFor(habitColorForSeed(seed), isDark)

    /** Card fill for an already-resolved bucket. */
    fun habitCardBackgroundFor(color: HabitColor, isDark: Boolean): Color =
        if (isDark) HabitDark.getValue(color) else HabitLight.getValue(color)

    /**
     * Habit name on a card. Ivory on every dark card (12.20–14.00:1 across the
     * six fills) and near-black on every light card.
     */
    fun habitCardTitleColor(isDark: Boolean): Color = if (isDark) OnDarkCardTitle else OnLight

    /**
     * Alarm time / subtitle on a card. [OnDarkCardMuted] measures 8.27–9.49:1 on
     * the six dark fills, which is why it is used whole here rather than being
     * faded with an alpha — an alpha would drop it below AA on the lightest card.
     */
    fun habitCardMutedColor(isDark: Boolean): Color = if (isDark) OnDarkCardMuted else OnLightMuted

    /**
     * Tint for the small informational glyphs that sit on a card beside text
     * (the alarm icon, status marks). Deliberately *not* [AccentMuted]: at
     * 2.66–3.06:1 that token is decorative-only and would be unreadable at 11 dp.
     */
    fun habitCardIconColor(isDark: Boolean): Color = if (isDark) OnDarkCardMuted else OnLightMuted

    private fun hueOf(seed: Long): Float {
        val r = ((seed shr 16) and 0xFF).toFloat() / 255f
        val g = ((seed shr 8) and 0xFF).toFloat() / 255f
        val b = (seed and 0xFF).toFloat() / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return 0f
        val h = when {
            max == r -> ((g - b) / delta).let { if (it < 0f) it + 6f else it }
            max == g -> ((b - r) / delta) + 2f
            else -> ((r - g) / delta) + 4f
        }
        return h * 60f
    }

    private fun habitColorForHue(hue: Float): HabitColor = when {
        // Band order matters: the warm band is tested first and owns 0°–60°
        // (plus pink/magenta, which is red-saturated) so that the green band
        // starts at 60° where chartreuse lives.
        //
        // Boundaries are 60/95/176/215/275. Each is at least ~0.6° away from the
        // nearest seed in `HabitIconCatalog`, so the mapping is stable rather
        // than sitting on a knife edge, and all six buckets are reached.
        hue < 60f -> HabitColor.CHOCOLATE   //   0°– 60°  (red → orange → amber)
        hue < 95f -> HabitColor.OLIVE       //  60°– 95°  (yellow-green → green)
        hue < 176f -> HabitColor.FOREST     //  95°–176°  (green → spring green)
        hue < 215f -> HabitColor.SLATE      // 176°–215°  (teal → cyan blue)
        hue < 275f -> HabitColor.INDIGO     // 215°–275°  (blue → indigo/violet)
        else -> HabitColor.TEAL             // 275°–360°  (violet → magenta → red)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Accent — the muted green
    // ─────────────────────────────────────────────────────────────────────────

    /** Icon artwork / large decorative shapes on dark. 2.7–3.1:1 — never text. */
    val AccentMuted: Color = Color(0xFF5E755B)

    /** Links, active nav, focus ring, CTA on dark. 6.15:1 on [DarkBackground] — AA. */
    val AccentBright: Color = Color(0xFF7E9A78)

    /** Pressed / hover lift of [AccentBright]. 7.03:1 — AA. */
    val AccentBrightHigh: Color = Color(0xFF8CA482)

    /** Text/icon on an accent-filled button. 6.04:1 on [AccentBright] — AA. */
    val OnAccent: Color = Color(0xFF10130F)

    /** Accent for text on the cream light surfaces. 6.42:1 on cream — AA. */
    val AccentOnLight: Color = Color(0xFF41603F)

    /** Accent container fills. */
    val AccentContainerDark: Color = Color(0xFF26301F)
    val AccentContainerLight: Color = Color(0xFFE3EDE0)

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Surfaces
    // ─────────────────────────────────────────────────────────────────────────

    /** App background, dark. */
    val DarkBackground: Color = Color(0xFF140F09)

    /** Sheet / dialog surface, dark. */
    val DarkSheet: Color = Color(0xFF1C1610)

    /** Raised surface, dark. */
    val DarkSurface: Color = Color(0xFF1A140B)

    /** Input / higher layer, dark. */
    val DarkSurfaceVariant: Color = Color(0xFF2C2416)

    /** Nav + status chrome, dark. */
    val DarkChrome: Color = Color(0xFF140F09)

    /** Dividers and hairline strokes, dark. */
    val DarkOutlineVariant: Color = Color(0xFF3B3222)

    /** Cream background, light. */
    val LightBackground: Color = Color(0xFFFAF3E9)

    /** Raised surface, light. */
    val LightSurface: Color = Color(0xFFFFFDF7)

    /** Input / higher layer, light. */
    val LightSurfaceVariant: Color = Color(0xFFF0E6D7)

    /** Dividers and hairline strokes, light. */
    val LightOutlineVariant: Color = Color(0xFFDCD3C2)

    /** Container tiers, light. */
    val LightSurfaceContainerLow: Color = Color(0xFFFBF6EC)
    val LightSurfaceContainerHigh: Color = Color(0xFFF5EDDF)

    /** Container tiers, dark. */
    val DarkSurfaceContainerLow: Color = Color(0xFF140F08)
    val DarkSurfaceContainerHigh: Color = Color(0xFF251E11)

    /** Inverse (snackbar-style) surfaces. */
    val LightInverseSurface: Color = Color(0xFF332B20)
    val LightInverseOnSurface: Color = Color(0xFFF7F0E3)
    val DarkInverseSurface: Color = Color(0xFFF3EBDC)
    val DarkInverseOnSurface: Color = Color(0xFF241E14)

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Text
    // ─────────────────────────────────────────────────────────────────────────

    /** Serif headings + primary text, dark. 17.34:1 on [DarkBackground] — AAA. */
    val OnDarkHeading: Color = Color(0xFFF7F4EC)

    /** Primary body text, dark. */
    val OnDark: Color = Color(0xFFF7F4EC)

    /** Habit-card title, dark. */
    val OnDarkCardTitle: Color = Color(0xFFF7F4EC)

    /** Secondary text, dark. 7.65:1 on [DarkBackground] — AAA. */
    val OnDarkMuted: Color = Color(0xFFA7A49B)

    /** Card subtitle, dark. */
    val OnDarkCardMuted: Color = Color(0xFFCFCBC0)

    /** Text on cream, light. */
    val OnLight: Color = Color(0xFF2B241B)

    /** Secondary text on cream, light. */
    val OnLightMuted: Color = Color(0xFF5D5546)

    /** Outline that still reads as a boundary on dark. */
    val DarkOutline: Color = Color(0xFFA99D86)

    /** Outline that still reads as a boundary on light. */
    val LightOutline: Color = Color(0xFF8B8171)

    /** Secondary text on the dark inverse surface. */
    val DarkOnSurfaceVariant: Color = Color(0xFFD9CEB9)

    /** Secondary text on the light inverse surface. */
    val LightOnSurfaceVariant: Color = Color(0xFF5D5546)

    /** Ink used on top of saturated brand/accent chips (screens, share cards). */
    val OnBrandInk: Color = Color(0xFFFFFCF5)

    /** Ink pressed onto an accent-filled dark chip, used where OnBrandInk is too hot. */
    val OnAccentDeep: Color = Color(0xFF1A1309)

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Semantic
    // ─────────────────────────────────────────────────────────────────────────

    /** Success / confirmation. */
    val Success: Color = Color(0xFF7E9A78)

    /** Warning. */
    val Warning: Color = Color(0xFFD9A44C)

    /** Error. */
    val Error: Color = Color(0xFFC4796A)

    /** Informational. */
    val Info: Color = Color(0xFF6E93A8)

    /** Attention / needs-action. 6.71–7.70:1 on the dark habit cards — AA. */
    val Attention: Color = Color(0xFFE0B054)

    /** Filled success chip (check badges, "done" ticks). */
    val SuccessFill: Color = Color(0xFF4CAF50)

    /** Needs-action text on a card ("Set up alarm"). */
    val AttentionStrong: Color = Color(0xFFFF9800)

    /** Low-power state in the nav bar. */
    val StatusAmber: Color = Color(0xFFFFB300)

    /** Idle / unknown state in the nav bar. */
    val StatusIdle: Color = Color(0xFF9E9E9E)

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Brand marks
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Third-party marks and the app's own logo palette. These are NOT themeable:
    // a partner logo must stay recognisable in both themes, so they are declared
    // once here and referenced, never re-tinted or alpha-blended into the theme.

    /** The app logo's sage — also the celebration confetti's green. */
    val BrandSage: Color = Color(0xFF9CAF88)

    /** The app logo's terracotta — celebration confetti. */
    val BrandTerracotta: Color = Color(0xFFC7724F)

    /** The app logo's gold — celebration confetti and milestone rays. */
    val BrandGold: Color = Color(0xFFE9B44C)

    /** Deep teal used by milestone art when a habit has no accent of its own. */
    val BrandTeal: Color = Color(0xFF006876)

    /** HUAWEI wordmark red (IAP / sign-in surfaces). */
    val HuaweiRed: Color = Color(0xFFC7000B)

    /** Elevated HUAWEI red used on the dark sign-in button. */
    val HuaweiRedBright: Color = Color(0xFFEF484B)

    /** HUAWEI gold used on the dark companion-studio plate. */
    val HuaweiGold: Color = Color(0xFFF3CB63)

    /** The warm plate the HUAWEI mark is set on. */
    val HuaweiPlate: Color = Color(0xFF4C3A1E)

    /** Facebook — BrandLogos. */
    val FacebookBlue: Color = Color(0xFF1877F2)

    /** Reddit — BrandLogos. */
    val RedditOrange: Color = Color(0xFFFF4500)

    /** Instagram — BrandLogos. */
    val InstagramPink: Color = Color(0xFFE4405F)

    /** TikTok — BrandLogos. */
    val TikTokRed: Color = Color(0xFFFE2C55)

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Mood pixels
    // ─────────────────────────────────────────────────────────────────────────
    //
    // The mood-mode swatches drawn by MoodPixelsCard. Kept as their own tokens
    // rather than read off `AccentPacks["classic"]` because that pack's
    // MOTIVATED/OVERWHELMED entries are the *gradient* start and the *dark*
    // variant respectively — using them here would visibly change the card.

    val MoodPixelCalm: Color = Color(0xFF5D8A66)
    val MoodPixelFocused: Color = Color(0xFF33655A)
    val MoodPixelMotivated: Color = Color(0xFFC2643A)
    val MoodPixelOverwhelmed: Color = Color(0xFF8A8273)

    /** Swatch label ink on the mood-pixel card. */
    val MoodPixelInk: Color = Color(0xFFFFFCF5)

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Accent packs
    // ─────────────────────────────────────────────────────────────────────────
    //
    // "classic" (the app logo's sage/terracotta/earth tones) is free; the other
    // twelve are premium exclusives. The data lives here rather than in Theme.kt
    // so that Theme.kt contains no colour literals at all — it is a pure alias
    // layer over this object.

    /**
     * The thirteen accent packs. "classic" is free; the rest are Premium exclusives.
     *
     * Each pack carries one [AccentPair] per [MoodMode]. The values are the colour
     * literals that used to live in `Theme.kt`; they moved here so that file is a
     * pure alias layer and the whole app's colour data sits in one place.
     */
    object AccentPacks {

        // ── classic ──
        private val ClassicCalmLight = Color(0xFF5D8A66)
        private val ClassicCalmDark = Color(0xFFA9CDAB)
        private val ClassicCalmGradient = listOf(Color(0xFF6B9873), Color(0xFF4E8A7A))
        private val ClassicFocusedLight = Color(0xFF33655A)
        private val ClassicFocusedDark = Color(0xFF96CCBC)
        private val ClassicFocusedGradient = listOf(Color(0xFF33655A), Color(0xFF2E5B6B))
        private val ClassicMotivatedLight = Color(0xFFB65C36)
        private val ClassicMotivatedDark = Color(0xFFF0A280)
        private val ClassicMotivatedGradient = listOf(Color(0xFFC2643A), Color(0xFFD68A45))
        private val ClassicOverwhelmedLight = Color(0xFF6E6A5D)
        private val ClassicOverwhelmedDark = Color(0xFFCBC5B5)
        private val ClassicOverwhelmedGradient = listOf(Color(0xFF7A7466), Color(0xFF93897A))

        val classic: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(ClassicCalmLight, ClassicCalmDark, ClassicCalmGradient),
            MoodMode.FOCUSED to AccentPair(ClassicFocusedLight, ClassicFocusedDark, ClassicFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(ClassicMotivatedLight, ClassicMotivatedDark, ClassicMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(ClassicOverwhelmedLight, ClassicOverwhelmedDark, ClassicOverwhelmedGradient),
        )

        // ── sunrise ──
        private val SunriseCalmLight = Color(0xFF20627E)
        private val SunriseCalmDark = Color(0xFF90CEF4)
        private val SunriseCalmGradient = listOf(Color(0xFF2E7095), Color(0xFF6D82C4))
        private val SunriseFocusedLight = Color(0xFF6C3F97)
        private val SunriseFocusedDark = Color(0xFFDDB8FF)
        private val SunriseFocusedGradient = listOf(Color(0xFF6C3F97), Color(0xFF9A4784))
        private val SunriseMotivatedLight = Color(0xFFA83C22)
        private val SunriseMotivatedDark = Color(0xFFFFB4A0)
        private val SunriseMotivatedGradient = listOf(Color(0xFFC24A24), Color(0xFFD98324))
        private val SunriseOverwhelmedLight = Color(0xFF64596B)
        private val SunriseOverwhelmedDark = Color(0xFFCFC1D8)
        private val SunriseOverwhelmedGradient = listOf(Color(0xFF6F6478), Color(0xFF8A7E93))

        val sunrise: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(SunriseCalmLight, SunriseCalmDark, SunriseCalmGradient),
            MoodMode.FOCUSED to AccentPair(SunriseFocusedLight, SunriseFocusedDark, SunriseFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(SunriseMotivatedLight, SunriseMotivatedDark, SunriseMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(SunriseOverwhelmedLight, SunriseOverwhelmedDark, SunriseOverwhelmedGradient),
        )

        // ── forest ──
        private val ForestCalmLight = Color(0xFF1E6559)
        private val ForestCalmDark = Color(0xFF87D6C4)
        private val ForestCalmGradient = listOf(Color(0xFF25725F), Color(0xFF3E8A93))
        private val ForestFocusedLight = Color(0xFF3E6425)
        private val ForestFocusedDark = Color(0xFFA9D383)
        private val ForestFocusedGradient = listOf(Color(0xFF3E6425), Color(0xFF2A6B4E))
        private val ForestMotivatedLight = Color(0xFF7E5700)
        private val ForestMotivatedDark = Color(0xFFF2BF48)
        private val ForestMotivatedGradient = listOf(Color(0xFF946600), Color(0xFFAD7A18))
        private val ForestOverwhelmedLight = Color(0xFF56635B)
        private val ForestOverwhelmedDark = Color(0xFFBECCC0)
        private val ForestOverwhelmedGradient = listOf(Color(0xFF64716A), Color(0xFF7D8B82))

        val forest: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(ForestCalmLight, ForestCalmDark, ForestCalmGradient),
            MoodMode.FOCUSED to AccentPair(ForestFocusedLight, ForestFocusedDark, ForestFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(ForestMotivatedLight, ForestMotivatedDark, ForestMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(ForestOverwhelmedLight, ForestOverwhelmedDark, ForestOverwhelmedGradient),
        )

        // ── lullaby ──
        private val LullabyCalmLight = Color(0xFF5A7D9A)
        private val LullabyCalmDark = Color(0xFFAECBEB)
        private val LullabyCalmGradient = listOf(Color(0xFF6A8DAB), Color(0xFF9A8DBE))
        private val LullabyFocusedLight = Color(0xFF566A93)
        private val LullabyFocusedDark = Color(0xFFB3C3EE)
        private val LullabyFocusedGradient = listOf(Color(0xFF566A93), Color(0xFF6E5F9E))
        private val LullabyMotivatedLight = Color(0xFFA96683)
        private val LullabyMotivatedDark = Color(0xFFF2B8CD)
        private val LullabyMotivatedGradient = listOf(Color(0xFFB57390), Color(0xFFC495B4))
        private val LullabyOverwhelmedLight = Color(0xFF6F6B7E)
        private val LullabyOverwhelmedDark = Color(0xFFCBC7DA)
        private val LullabyOverwhelmedGradient = listOf(Color(0xFF7B7789), Color(0xFF938FA3))

        val lullaby: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(LullabyCalmLight, LullabyCalmDark, LullabyCalmGradient),
            MoodMode.FOCUSED to AccentPair(LullabyFocusedLight, LullabyFocusedDark, LullabyFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(LullabyMotivatedLight, LullabyMotivatedDark, LullabyMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(LullabyOverwhelmedLight, LullabyOverwhelmedDark, LullabyOverwhelmedGradient),
        )

        // ── sakura ──
        private val SakuraCalmLight = Color(0xFFB0567A)
        private val SakuraCalmDark = Color(0xFFF4AFC6)
        private val SakuraCalmGradient = listOf(Color(0xFFBC6488), Color(0xFFCE8AA5))
        private val SakuraFocusedLight = Color(0xFF8A4A66)
        private val SakuraFocusedDark = Color(0xFFE3A4BD)
        private val SakuraFocusedGradient = listOf(Color(0xFF8A4A66), Color(0xFF9F5578))
        private val SakuraMotivatedLight = Color(0xFFC44E62)
        private val SakuraMotivatedDark = Color(0xFFFFA9B6)
        private val SakuraMotivatedGradient = listOf(Color(0xFFCE5A6D), Color(0xFFDC7E88))
        private val SakuraOverwhelmedLight = Color(0xFF7C6870)
        private val SakuraOverwhelmedDark = Color(0xFFD8C2CB)
        private val SakuraOverwhelmedGradient = listOf(Color(0xFF88737C), Color(0xFF9E8891))

        val sakura: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(SakuraCalmLight, SakuraCalmDark, SakuraCalmGradient),
            MoodMode.FOCUSED to AccentPair(SakuraFocusedLight, SakuraFocusedDark, SakuraFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(SakuraMotivatedLight, SakuraMotivatedDark, SakuraMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(SakuraOverwhelmedLight, SakuraOverwhelmedDark, SakuraOverwhelmedGradient),
        )

        // ── ocean ──
        private val OceanCalmLight = Color(0xFF1F6F8B)
        private val OceanCalmDark = Color(0xFF8ED4E8)
        private val OceanCalmGradient = listOf(Color(0xFF2A7C97), Color(0xFF3D93AE))
        private val OceanFocusedLight = Color(0xFF175873)
        private val OceanFocusedDark = Color(0xFF83C5DE)
        private val OceanFocusedGradient = listOf(Color(0xFF175873), Color(0xFF1F4C6E))
        private val OceanMotivatedLight = Color(0xFF0E7C7B)
        private val OceanMotivatedDark = Color(0xFF74D5D4)
        private val OceanMotivatedGradient = listOf(Color(0xFF148887), Color(0xFF2AA1A0))
        private val OceanOverwhelmedLight = Color(0xFF5B6B72)
        private val OceanOverwhelmedDark = Color(0xFFBFD0D7)
        private val OceanOverwhelmedGradient = listOf(Color(0xFF67777E), Color(0xFF7E8E95))

        val ocean: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(OceanCalmLight, OceanCalmDark, OceanCalmGradient),
            MoodMode.FOCUSED to AccentPair(OceanFocusedLight, OceanFocusedDark, OceanFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(OceanMotivatedLight, OceanMotivatedDark, OceanMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(OceanOverwhelmedLight, OceanOverwhelmedDark, OceanOverwhelmedGradient),
        )

        // ── lavender ──
        private val LavenderCalmLight = Color(0xFF7A6BAF)
        private val LavenderCalmDark = Color(0xFFCDC0F2)
        private val LavenderCalmGradient = listOf(Color(0xFF8677BB), Color(0xFF9C8FCB))
        private val LavenderFocusedLight = Color(0xFF5F5390)
        private val LavenderFocusedDark = Color(0xFFBFB2E8)
        private val LavenderFocusedGradient = listOf(Color(0xFF5F5390), Color(0xFF6F5F9D))
        private val LavenderMotivatedLight = Color(0xFF9A5FA8)
        private val LavenderMotivatedDark = Color(0xFFE3B3EE)
        private val LavenderMotivatedGradient = listOf(Color(0xFFA46CB2), Color(0xFFB588C1))
        private val LavenderOverwhelmedLight = Color(0xFF6E6879)
        private val LavenderOverwhelmedDark = Color(0xFFCEC7D9)
        private val LavenderOverwhelmedGradient = listOf(Color(0xFF7A7485), Color(0xFF908A9B))

        val lavender: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(LavenderCalmLight, LavenderCalmDark, LavenderCalmGradient),
            MoodMode.FOCUSED to AccentPair(LavenderFocusedLight, LavenderFocusedDark, LavenderFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(LavenderMotivatedLight, LavenderMotivatedDark, LavenderMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(LavenderOverwhelmedLight, LavenderOverwhelmedDark, LavenderOverwhelmedGradient),
        )

        // ── honey ──
        private val HoneyCalmLight = Color(0xFF9A6B1F)
        private val HoneyCalmDark = Color(0xFFEFC26B)
        private val HoneyCalmGradient = listOf(Color(0xFFA5772B), Color(0xFFB98F45))
        private val HoneyFocusedLight = Color(0xFF7E5A14)
        private val HoneyFocusedDark = Color(0xFFE0B45C)
        private val HoneyFocusedGradient = listOf(Color(0xFF7E5A14), Color(0xFF8F6A1E))
        private val HoneyMotivatedLight = Color(0xFFB05F17)
        private val HoneyMotivatedDark = Color(0xFFFDB878)
        private val HoneyMotivatedGradient = listOf(Color(0xFFBB6B23), Color(0xFFCE8A3C))
        private val HoneyOverwhelmedLight = Color(0xFF75674F)
        private val HoneyOverwhelmedDark = Color(0xFFD6C7A9)
        private val HoneyOverwhelmedGradient = listOf(Color(0xFF81735B), Color(0xFF978970))

        val honey: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(HoneyCalmLight, HoneyCalmDark, HoneyCalmGradient),
            MoodMode.FOCUSED to AccentPair(HoneyFocusedLight, HoneyFocusedDark, HoneyFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(HoneyMotivatedLight, HoneyMotivatedDark, HoneyMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(HoneyOverwhelmedLight, HoneyOverwhelmedDark, HoneyOverwhelmedGradient),
        )

        // ── berry ──
        private val BerryCalmLight = Color(0xFF7D3A63)
        private val BerryCalmDark = Color(0xFFE0A2C6)
        private val BerryCalmGradient = listOf(Color(0xFF894470), Color(0xFF9C5A84))
        private val BerryFocusedLight = Color(0xFF612D53)
        private val BerryFocusedDark = Color(0xFFCE99BC)
        private val BerryFocusedGradient = listOf(Color(0xFF612D53), Color(0xFF6F3560))
        private val BerryMotivatedLight = Color(0xFFA02C55)
        private val BerryMotivatedDark = Color(0xFFF79BB8)
        private val BerryMotivatedGradient = listOf(Color(0xFFAC3961), Color(0xFFBE5A7D))
        private val BerryOverwhelmedLight = Color(0xFF6F6069)
        private val BerryOverwhelmedDark = Color(0xFFCFC0C9)
        private val BerryOverwhelmedGradient = listOf(Color(0xFF7B6C75), Color(0xFF91828B))

        val berry: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(BerryCalmLight, BerryCalmDark, BerryCalmGradient),
            MoodMode.FOCUSED to AccentPair(BerryFocusedLight, BerryFocusedDark, BerryFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(BerryMotivatedLight, BerryMotivatedDark, BerryMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(BerryOverwhelmedLight, BerryOverwhelmedDark, BerryOverwhelmedGradient),
        )

        // ── mint ──
        private val MintCalmLight = Color(0xFF2E8B6E)
        private val MintCalmDark = Color(0xFF95E3C8)
        private val MintCalmGradient = listOf(Color(0xFF3A977A), Color(0xFF52AB8F))
        private val MintFocusedLight = Color(0xFF247661)
        private val MintFocusedDark = Color(0xFF8AD6BE)
        private val MintFocusedGradient = listOf(Color(0xFF247661), Color(0xFF1F6A6B))
        private val MintMotivatedLight = Color(0xFF2F9D71)
        private val MintMotivatedDark = Color(0xFF8FF0C4)
        private val MintMotivatedGradient = listOf(Color(0xFF3BA97D), Color(0xFF58BD93))
        private val MintOverwhelmedLight = Color(0xFF5F6F68)
        private val MintOverwhelmedDark = Color(0xFFC2D4CC)
        private val MintOverwhelmedGradient = listOf(Color(0xFF6B7B74), Color(0xFF82928B))

        val mint: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(MintCalmLight, MintCalmDark, MintCalmGradient),
            MoodMode.FOCUSED to AccentPair(MintFocusedLight, MintFocusedDark, MintFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(MintMotivatedLight, MintMotivatedDark, MintMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(MintOverwhelmedLight, MintOverwhelmedDark, MintOverwhelmedGradient),
        )

        // ── peach ──
        private val PeachCalmLight = Color(0xFFC26D51)
        private val PeachCalmDark = Color(0xFFFFBFA4)
        private val PeachCalmGradient = listOf(Color(0xFFCC7A5E), Color(0xFFDB9377))
        private val PeachFocusedLight = Color(0xFFA55A42)
        private val PeachFocusedDark = Color(0xFFF3AF94)
        private val PeachFocusedGradient = listOf(Color(0xFFA55A42), Color(0xFFB0674B))
        private val PeachMotivatedLight = Color(0xFFD05E3B)
        private val PeachMotivatedDark = Color(0xFFFFAE8B)
        private val PeachMotivatedGradient = listOf(Color(0xFFDA6A47), Color(0xFFE78A64))
        private val PeachOverwhelmedLight = Color(0xFF7C6A61)
        private val PeachOverwhelmedDark = Color(0xFFDCC6BB)
        private val PeachOverwhelmedGradient = listOf(Color(0xFF88766D), Color(0xFF9E8C83))

        val peach: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(PeachCalmLight, PeachCalmDark, PeachCalmGradient),
            MoodMode.FOCUSED to AccentPair(PeachFocusedLight, PeachFocusedDark, PeachFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(PeachMotivatedLight, PeachMotivatedDark, PeachMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(PeachOverwhelmedLight, PeachOverwhelmedDark, PeachOverwhelmedGradient),
        )

        // ── midnight ──
        private val MidnightCalmLight = Color(0xFF44518F)
        private val MidnightCalmDark = Color(0xFFAAB6F0)
        private val MidnightCalmGradient = listOf(Color(0xFF505D9B), Color(0xFF6672AD))
        private val MidnightFocusedLight = Color(0xFF333E75)
        private val MidnightFocusedDark = Color(0xFF9AA7E4)
        private val MidnightFocusedGradient = listOf(Color(0xFF333E75), Color(0xFF3C3670))
        private val MidnightMotivatedLight = Color(0xFF5A4D9E)
        private val MidnightMotivatedDark = Color(0xFFBCA9F2)
        private val MidnightMotivatedGradient = listOf(Color(0xFF6659AA), Color(0xFF7E71BE))
        private val MidnightOverwhelmedLight = Color(0xFF62687C)
        private val MidnightOverwhelmedDark = Color(0xFFC3C9DD)
        private val MidnightOverwhelmedGradient = listOf(Color(0xFF6E7488), Color(0xFF848A9E))

        val midnight: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(MidnightCalmLight, MidnightCalmDark, MidnightCalmGradient),
            MoodMode.FOCUSED to AccentPair(MidnightFocusedLight, MidnightFocusedDark, MidnightFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(MidnightMotivatedLight, MidnightMotivatedDark, MidnightMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(MidnightOverwhelmedLight, MidnightOverwhelmedDark, MidnightOverwhelmedGradient),
        )

        // ── rosewood ──
        private val RosewoodCalmLight = Color(0xFF96525C)
        private val RosewoodCalmDark = Color(0xFFE8AEB7)
        private val RosewoodCalmGradient = listOf(Color(0xFFA25E68), Color(0xFFB47680))
        private val RosewoodFocusedLight = Color(0xFF7A4149)
        private val RosewoodFocusedDark = Color(0xFFD79EA7)
        private val RosewoodFocusedGradient = listOf(Color(0xFF7A4149), Color(0xFF8A4A55))
        private val RosewoodMotivatedLight = Color(0xFFAD4A50)
        private val RosewoodMotivatedDark = Color(0xFFFCA5AA)
        private val RosewoodMotivatedGradient = listOf(Color(0xFFB9575D), Color(0xFFCA787D))
        private val RosewoodOverwhelmedLight = Color(0xFF75636A)
        private val RosewoodOverwhelmedDark = Color(0xFFD5C3CA)
        private val RosewoodOverwhelmedGradient = listOf(Color(0xFF816F76), Color(0xFF97858C))

        val rosewood: Map<MoodMode, AccentPair> = mapOf(
            MoodMode.CALM to AccentPair(RosewoodCalmLight, RosewoodCalmDark, RosewoodCalmGradient),
            MoodMode.FOCUSED to AccentPair(RosewoodFocusedLight, RosewoodFocusedDark, RosewoodFocusedGradient),
            MoodMode.MOTIVATED to AccentPair(RosewoodMotivatedLight, RosewoodMotivatedDark, RosewoodMotivatedGradient),
            MoodMode.OVERWHELMED to AccentPair(RosewoodOverwhelmedLight, RosewoodOverwhelmedDark, RosewoodOverwhelmedGradient),
        )

        /** Every pack, keyed by the id stored in the user's settings. */
        val all: Map<String, Map<MoodMode, AccentPair>> = mapOf(
            "classic" to classic,
            "sunrise" to sunrise,
            "forest" to forest,
            "lullaby" to lullaby,
            "sakura" to sakura,
            "ocean" to ocean,
            "lavender" to lavender,
            "honey" to honey,
            "berry" to berry,
            "mint" to mint,
            "peach" to peach,
            "midnight" to midnight,
            "rosewood" to rosewood,
        )

        /** Pack used when a stored id is unknown (or blank). */
        val fallback: Map<MoodMode, AccentPair> = all.getValue("classic")
    }
}

/**
 * **Artwork palette — companion avatars, frames, celebration confetti.**
 *
 * These are illustration colours: skin tones, hair, garment fabrics, frame
 * fills, confetti. They are deliberately *not* theme tokens — a character has to
 * look the same in light and dark mode, and a skin tone someone chose must not
 * be re-tinted by a theme flip, so none of this routes through
 * `MaterialTheme.colorScheme`. It lives in this file (rather than inline in the
 * drawing code) so the app's colour data is in one auditable place and the
 * "no inline hex outside the palette" rule can be enforced mechanically.
 */
object Mf2Artwork {

    // ── Companion skin tones (light → deep) ─────────────────────────────────
    // Persisted BY INDEX (`AvatarConfig.skinTone`). Never reorder or remove.
    val skinTones: List<Color> = listOf(
        Color(0xFFFFE4D0), Color(0xFFFAD7B8), Color(0xFFF2C49B),
        Color(0xFFE8B088), Color(0xFFD99C6B), Color(0xFFC98850),
        Color(0xFFB57544), Color(0xFF9C5F35), Color(0xFF84492A),
        Color(0xFF6B3A22), Color(0xFF54301E), Color(0xFF3E2418),
    )

    // ── Hair colours ────────────────────────────────────────────────────────
    // Persisted BY INDEX (`AvatarConfig.hairColor`). Never reorder or remove.
    val hairColors: List<Color> = listOf(
        Color(0xFF2B2118), Color(0xFF4A3220), Color(0xFF6B4A2B),
        Color(0xFF8B5E3C), Color(0xFFB5854E), Color(0xFFE0C068),
        Color(0xFFEDE3D0), Color(0xFFA84E32), Color(0xFF7A7A85),
        Color(0xFFC98BC0),
    )

    // ── Character line work ─────────────────────────────────────────────────
    /** Eye / mouth / brow ink — a warm near-black, never pure black. */
    val CharacterInk: Color = Color(0xFF3A2E24)

    /** Lip fill. */
    val CharacterLip: Color = Color(0xFFB55A4C)

    /** Cheek blush (also the "joyful" eye fill). */
    val CharacterBlush: Color = Color(0xFFE8A6A0)

    /** Paper-cream highlight arc across the top of the avatar capsule. */
    val CharacterHighlight: Color = Color(0xFFF3E8D7)

    /** Star pupils ("starstruck"). */
    val StarGold: Color = Color(0xFFE2A93C)

    /** Heart pupils ("hearts"). */
    val HeartPink: Color = Color(0xFFD9536B)

    /** Sunset stripes on the "sunset" outfit. */
    val SunsetStripes: List<Color> = listOf(
        Color(0xFFE2794A), Color(0xFFCE5A6D), Color(0xFF6C5F9E),
    )

    /** The four mood tints woven into the "moodweaver" knit. */
    val MoodweaverKnit: List<Color> = listOf(
        Color(0xFF9CAF88), Color(0xFF6D82C4),
        Color(0xFFE9B44C), Color(0xFFCE5A6D),
    )

    // ── Garment fabrics ─────────────────────────────────────────────────────

    /** Base fabric + trim for one outfit. */
    data class OutfitColors(val base: Color, val accent: Color)

    private val Cream = Color(0xFFEFE5D2)
    private val PaperWhite = Color(0xFFF6F1E4)
    private val Sand = Color(0xFFF6EBD7)
    private val Parchment = Color(0xFFF3E8D7)

    /** Outfit fabrics, keyed by [com.rork.mindsetframestracker.ui.avatar.OutfitSpec.id]. */
    val outfits: Map<String, OutfitColors> = mapOf(
        "tee" to OutfitColors(Color(0xFF5D8A66), Cream),
        "hoodie" to OutfitColors(Color(0xFFC7724F), Color(0xFFA85A3B)),
        "shirt" to OutfitColors(Color(0xFF33655A), PaperWhite),
        "sailor" to OutfitColors(Color(0xFF93B5C6), Color(0xFF2E4A5B)),
        "turtleneck" to OutfitColors(Color(0xFF9C8FCB), Color(0xFF6C5F9E)),
        "stripes" to OutfitColors(Color(0xFFB0567A), Parchment),
        "overalls" to OutfitColors(Color(0xFFE0A25E), Sand),
        "blazer" to OutfitColors(Color(0xFF4C463C), PaperWhite),
        "vest" to OutfitColors(Color(0xFF2E5B6B), Cream),
        "scarf" to OutfitColors(Color(0xFFCE5A6D), Color(0xFF8F3448)),
        "varsity" to OutfitColors(Color(0xFF7A7466), Cream),
        "linen" to OutfitColors(Cream, Color(0xFF9CAF88)),
        "sunset" to OutfitColors(Color(0xFFF6E7CB), Color(0xFFE2794A)),
        "gardener" to OutfitColors(Color(0xFF6B9873), Sand),
        "galaxy" to OutfitColors(Color(0xFF2B3160), Color(0xFFAAB6F0)),
        "champion" to OutfitColors(Color(0xFFE2B33C), Color(0xFF4C3A1E)),
        "moodweaver" to OutfitColors(Color(0xFF9CAF88), Color(0xFFE9B44C)),
        "cape" to OutfitColors(Color(0xFF3E4E6B), Color(0xFFC94F4F)),
    )

    /** Fabric pair for [id]. Throws if the id is not a known outfit. */
    fun outfitColors(id: String): OutfitColors = outfits.getValue(id)

    // ── Avatar frames ───────────────────────────────────────────────────────
    //
    // Declared flat with a `Frame` prefix rather than nested per style, so a
    // frame's swatch can be re-tuned without touching the frame list.

    val FrameSage: Color = Color(0xFF9CAF88)
    val FrameCream: Color = Color(0xFFF3E8D7)
    val FrameTerracotta: Color = Color(0xFFD08B6A)
    val FrameDustyBlue: Color = Color(0xFF93B5C6)
    val FrameBlush: Color = Color(0xFFE8B4B8)
    val FrameCharcoal: Color = Color(0xFF4C463C)

    val FrameMeadowOuter: Color = Color(0xFFA8C686)
    val FrameMeadowInner: Color = Color(0xFF6B9873)

    val FrameDawnOuter: Color = Color(0xFFF6D8AE)
    val FrameDawnInner: Color = Color(0xFFE8A87C)

    val FrameMistOuter: Color = Color(0xFFCFE0E8)
    val FrameMistInner: Color = Color(0xFF93B5C6)

    val FrameLilacOuter: Color = Color(0xFFD7CDE8)
    val FrameLilacInner: Color = Color(0xFF9C8FCB)

    val FrameSunriseOuter: Color = Color(0xFFF2B880)
    val FrameSunriseInner: Color = Color(0xFFD96C4F)

    val FrameOceanOuter: Color = Color(0xFF8ED4E8)
    val FrameOceanInner: Color = Color(0xFF2A7C97)

    val FrameDotsSageBg: Color = Color(0xFFF7F0E1)
    val FrameDotsSageDot: Color = Color(0xFF9CAF88)

    val FrameStripesSandBg: Color = Color(0xFFF6E7CB)
    val FrameStripesSandStripe: Color = Color(0xFFE2A25E)

    val FrameRoseOuter: Color = Color(0xFFF4AFC6)
    val FrameRoseInner: Color = Color(0xFFB0567A)

    val FrameRaysHoneyBg: Color = Color(0xFFF7E7B2)
    val FrameRaysHoneyRay: Color = Color(0xFFEFC26B)

    val FrameWavesSky: Color = Color(0xFFCDEAF0)
    val FrameWavesSea: Color = Color(0xFF4EA8C2)

    /** Wave foam / crest — pure white reads as spray against every sky. */
    val FrameFoam: Color = Color(0xFFFFFFFF)

    val FrameDotsNightBg: Color = Color(0xFF3B4368)
    val FrameDotsNightDot: Color = Color(0xFFAAB6F0)

    val FrameStripesSageBg: Color = Color(0xFFE3EDE0)
    val FrameStripesSageStripe: Color = Color(0xFF5D8A66)

    val FrameConfettiBg: Color = Color(0xFFFDF6E9)

    /** Confetti pieces — also the confetti swept across the habits screen. */
    val ConfettiPieces: List<Color> = listOf(
        Color(0xFF9CAF88), Color(0xFFC7724F), Color(0xFFE9B44C), Color(0xFF6D82C4),
    )

    val FrameHillsSky: Color = Color(0xFFFBE8C9)
    val FrameHillsFar: Color = Color(0xFFA8C686)
    val FrameHillsNear: Color = Color(0xFF6B9873)

    val FrameMountainSky: Color = Color(0xFFFCE3C8)
    val FrameMountainPeak: Color = Color(0xFF8A6650)
    val FrameMountainSun: Color = Color(0xFFE9B44C)

    val FrameNightSkyTop: Color = Color(0xFF2B3160)
    val FrameNightSkyBottom: Color = Color(0xFF141830)
    val FrameNightSkyStar: Color = Color(0xFFF8F1E2)

    val FrameWavesDuskSky: Color = Color(0xFFF4C7A1)
    val FrameWavesDuskSea: Color = Color(0xFFC2643A)
    val FrameWavesDuskFoam: Color = Color(0xFFFFF3E0)

    val FrameHillsEveningSky: Color = Color(0xFFD7CDE8)
    val FrameHillsEveningFar: Color = Color(0xFF9C8FCB)
    val FrameHillsEveningNear: Color = Color(0xFF6C5F9E)

    val FrameAuroraGreen: Color = Color(0xFF7BE0AD)
    val FrameAuroraBlue: Color = Color(0xFF5AA9E6)
    val FrameAuroraViolet: Color = Color(0xFF9C8FCB)

    val FrameGalaxyTop: Color = Color(0xFF1B1035)
    val FrameGalaxyBottom: Color = Color(0xFF3D2B6B)
    val FrameGalaxyStar: Color = Color(0xFFFFD9F2)

    val FrameRaysGoldBg: Color = Color(0xFFF9EDD2)
    val FrameRaysGoldRay: Color = Color(0xFFE2B33C)

    val FrameChampionBg: Color = Color(0xFFFFF7E6)

    /** Champion confetti — a warmer sweep than [ConfettiPieces]. */
    val FrameChampionPieces: List<Color> = listOf(
        Color(0xFFE2B33C), Color(0xFFC7724F), Color(0xFF9CAF88), Color(0xFFCE5A6D),
    )

    val FrameFoundingGlowBg: Color = Color(0xFFFDEBD2)
    val FrameFoundingGlowRay: Color = Color(0xFFD98A2B)
}
