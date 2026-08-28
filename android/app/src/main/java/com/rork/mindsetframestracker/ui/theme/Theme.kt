package com.rork.mindsetframestracker.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.ThemeMode

/**
 * Motion profile per mood mode. Layout never changes across modes — only
 * accent color, copy tone, and this motion intensity.
 */
data class MoodMotion(
    /** false = near-static UI (Focused, Overwhelmed, or reduced-motion). */
    val enabled: Boolean,
    /** Multiplier applied to base animation durations (Calm is slower). */
    val durationScale: Float,
    /** 0 = no overshoot; Motivated gets a slight bounce. */
    val bouncy: Boolean,
) {
    fun <T> tween(baseMillis: Int): FiniteAnimationSpec<T> =
        if (!enabled) snap() else tween(durationMillis = (baseMillis * durationScale).toInt())

    fun springFloat(): FiniteAnimationSpec<Float> = when {
        !enabled -> snap()
        bouncy -> spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
        else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/** Theme variables that swap per mood mode. Same components, same positions. */
data class MoodTheme(
    val mode: MoodMode,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val gradient: List<Color>,
    val motion: MoodMotion,
)

val LocalMoodTheme = staticCompositionLocalOf {
    MoodTheme(
        mode = MoodMode.CALM,
        accent = Color(0xFF5D8A66),
        onAccent = Color(0xFFFFFCF5),
        accentSoft = Color(0xFFE3EDE0),
        onAccentSoft = Color(0xFF5D8A66),
        gradient = listOf(Color(0xFF6B9873), Color(0xFF4E8A7A)),
        motion = MoodMotion(enabled = true, durationScale = 1.4f, bouncy = false),
    )
}

private data class AccentPair(val light: Color, val dark: Color, val gradient: List<Color>)

/**
 * Accent packs: "classic" (brand palette from the app logo — sage, terracotta,
 * warm earth tones) is free; every other pack is a Premium exclusive.
 * 12 premium packs total — Sunrise, Forest, plus ten cozy/cute exclusives
 * (Lullaby, Sakura, Ocean, Lavender, Honey, Berry, Mint Candy, Peach,
 * Midnight, Rosewood).
 */
private val accentPacks: Map<String, Map<MoodMode, AccentPair>> = mapOf(
    "classic" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF5D8A66), Color(0xFFA9CDAB),
            listOf(Color(0xFF6B9873), Color(0xFF4E8A7A)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF33655A), Color(0xFF96CCBC),
            listOf(Color(0xFF33655A), Color(0xFF2E5B6B)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFB65C36), Color(0xFFF0A280),
            listOf(Color(0xFFC2643A), Color(0xFFD68A45)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF6E6A5D), Color(0xFFCBC5B5),
            listOf(Color(0xFF7A7466), Color(0xFF93897A)),
        ),
    ),
    "sunrise" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF20627E), Color(0xFF90CEF4),
            listOf(Color(0xFF2E7095), Color(0xFF6D82C4)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF6C3F97), Color(0xFFDDB8FF),
            listOf(Color(0xFF6C3F97), Color(0xFF9A4784)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFA83C22), Color(0xFFFFB4A0),
            listOf(Color(0xFFC24A24), Color(0xFFD98324)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF64596B), Color(0xFFCFC1D8),
            listOf(Color(0xFF6F6478), Color(0xFF8A7E93)),
        ),
    ),
    "forest" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF1E6559), Color(0xFF87D6C4),
            listOf(Color(0xFF25725F), Color(0xFF3E8A93)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF3E6425), Color(0xFFA9D383),
            listOf(Color(0xFF3E6425), Color(0xFF2A6B4E)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFF7E5700), Color(0xFFF2BF48),
            listOf(Color(0xFF946600), Color(0xFFAD7A18)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF56635B), Color(0xFFBECCC0),
            listOf(Color(0xFF64716A), Color(0xFF7D8B82)),
        ),
    ),
    // Lullaby — sleepy pastel blues and dusty pinks, soft as a nursery.
    "lullaby" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF5A7D9A), Color(0xFFAECBEB),
            listOf(Color(0xFF6A8DAB), Color(0xFF9A8DBE)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF566A93), Color(0xFFB3C3EE),
            listOf(Color(0xFF566A93), Color(0xFF6E5F9E)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFA96683), Color(0xFFF2B8CD),
            listOf(Color(0xFFB57390), Color(0xFFC495B4)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF6F6B7E), Color(0xFFCBC7DA),
            listOf(Color(0xFF7B7789), Color(0xFF938FA3)),
        ),
    ),
    // Sakura — cherry-blossom pinks with a fresh spring green.
    "sakura" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFFB0567A), Color(0xFFF4AFC6),
            listOf(Color(0xFFBC6488), Color(0xFFCE8AA5)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF8A4A66), Color(0xFFE3A4BD),
            listOf(Color(0xFF8A4A66), Color(0xFF9F5578)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFC44E62), Color(0xFFFFA9B6),
            listOf(Color(0xFFCE5A6D), Color(0xFFDC7E88)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF7C6870), Color(0xFFD8C2CB),
            listOf(Color(0xFF88737C), Color(0xFF9E8891)),
        ),
    ),
    // Ocean — deep sea blues and aqua glass.
    "ocean" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF1F6F8B), Color(0xFF8ED4E8),
            listOf(Color(0xFF2A7C97), Color(0xFF3D93AE)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF175873), Color(0xFF83C5DE),
            listOf(Color(0xFF175873), Color(0xFF1F4C6E)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFF0E7C7B), Color(0xFF74D5D4),
            listOf(Color(0xFF148887), Color(0xFF2AA1A0)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF5B6B72), Color(0xFFBFD0D7),
            listOf(Color(0xFF67777E), Color(0xFF7E8E95)),
        ),
    ),
    // Lavender — dreamy violets and soft lilac.
    "lavender" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF7A6BAF), Color(0xFFCDC0F2),
            listOf(Color(0xFF8677BB), Color(0xFF9C8FCB)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF5F5390), Color(0xFFBFB2E8),
            listOf(Color(0xFF5F5390), Color(0xFF6F5F9D)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFF9A5FA8), Color(0xFFE3B3EE),
            listOf(Color(0xFFA46CB2), Color(0xFFB588C1)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF6E6879), Color(0xFFCEC7D9),
            listOf(Color(0xFF7A7485), Color(0xFF908A9B)),
        ),
    ),
    // Honey — warm golden amber, toast and tea.
    "honey" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF9A6B1F), Color(0xFFEFC26B),
            listOf(Color(0xFFA5772B), Color(0xFFB98F45)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF7E5A14), Color(0xFFE0B45C),
            listOf(Color(0xFF7E5A14), Color(0xFF8F6A1E)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFB05F17), Color(0xFFFDB878),
            listOf(Color(0xFFBB6B23), Color(0xFFCE8A3C)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF75674F), Color(0xFFD6C7A9),
            listOf(Color(0xFF81735B), Color(0xFF978970)),
        ),
    ),
    // Berry — plum, mulberry, and raspberry jam.
    "berry" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF7D3A63), Color(0xFFE0A2C6),
            listOf(Color(0xFF894470), Color(0xFF9C5A84)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF612D53), Color(0xFFCE99BC),
            listOf(Color(0xFF612D53), Color(0xFF6F3560)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFA02C55), Color(0xFFF79BB8),
            listOf(Color(0xFFAC3961), Color(0xFFBE5A7D)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF6F6069), Color(0xFFCFC0C9),
            listOf(Color(0xFF7B6C75), Color(0xFF91828B)),
        ),
    ),
    // Mint Candy — cool minty greens with a sweet edge.
    "mint" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF2E8B6E), Color(0xFF95E3C8),
            listOf(Color(0xFF3A977A), Color(0xFF52AB8F)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF247661), Color(0xFF8AD6BE),
            listOf(Color(0xFF247661), Color(0xFF1F6A6B)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFF2F9D71), Color(0xFF8FF0C4),
            listOf(Color(0xFF3BA97D), Color(0xFF58BD93)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF5F6F68), Color(0xFFC2D4CC),
            listOf(Color(0xFF6B7B74), Color(0xFF82928B)),
        ),
    ),
    // Peach — soft coral and apricot warmth.
    "peach" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFFC26D51), Color(0xFFFFBFA4),
            listOf(Color(0xFFCC7A5E), Color(0xFFDB9377)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFFA55A42), Color(0xFFF3AF94),
            listOf(Color(0xFFA55A42), Color(0xFFB0674B)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFD05E3B), Color(0xFFFFAE8B),
            listOf(Color(0xFFDA6A47), Color(0xFFE78A64)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF7C6A61), Color(0xFFDCC6BB),
            listOf(Color(0xFF88766D), Color(0xFF9E8C83)),
        ),
    ),
    // Midnight — quiet indigo and starlit navy.
    "midnight" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF44518F), Color(0xFFAAB6F0),
            listOf(Color(0xFF505D9B), Color(0xFF6672AD)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF333E75), Color(0xFF9AA7E4),
            listOf(Color(0xFF333E75), Color(0xFF3C3670)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFF5A4D9E), Color(0xFFBCA9F2),
            listOf(Color(0xFF6659AA), Color(0xFF7E71BE)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF62687C), Color(0xFFC3C9DD),
            listOf(Color(0xFF6E7488), Color(0xFF848A9E)),
        ),
    ),
    // Rosewood — dusty rose and warm burgundy.
    "rosewood" to mapOf(
        MoodMode.CALM to AccentPair(
            Color(0xFF96525C), Color(0xFFE8AEB7),
            listOf(Color(0xFFA25E68), Color(0xFFB47680)),
        ),
        MoodMode.FOCUSED to AccentPair(
            Color(0xFF7A4149), Color(0xFFD79EA7),
            listOf(Color(0xFF7A4149), Color(0xFF8A4A55)),
        ),
        MoodMode.MOTIVATED to AccentPair(
            Color(0xFFAD4A50), Color(0xFFFCA5AA),
            listOf(Color(0xFFB9575D), Color(0xFFCA787D)),
        ),
        MoodMode.OVERWHELMED to AccentPair(
            Color(0xFF75636A), Color(0xFFD5C3CA),
            listOf(Color(0xFF816F76), Color(0xFF97858C)),
        ),
    ),
)

/**
 * Editorial serif used for display-level text (screen titles, hero numbers,
 * the splash wordmark). Bundled so it works offline; body text stays on the
 * system sans for readability.
 */
val DisplayFontFamily = FontFamily(Font(R.font.dm_serif_display))

private val baseTypography = Typography()

/** App type ramp: serif display voice on headlines, system sans elsewhere. */
val AppTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = DisplayFontFamily),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = DisplayFontFamily),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = DisplayFontFamily),
    headlineLarge = baseTypography.headlineLarge.copy(fontFamily = DisplayFontFamily),
    headlineMedium = baseTypography.headlineMedium.copy(
        fontFamily = DisplayFontFamily,
        letterSpacing = 0.sp,
    ),
    headlineSmall = baseTypography.headlineSmall.copy(
        fontFamily = DisplayFontFamily,
        letterSpacing = 0.sp,
    ),
    titleLarge = baseTypography.titleLarge.copy(
        fontFamily = DisplayFontFamily,
        fontSize = 23.sp,
        lineHeight = 30.sp,
    ),
)

private val moodMotions: Map<MoodMode, MoodMotion> = mapOf(
    MoodMode.CALM to MoodMotion(enabled = true, durationScale = 1.5f, bouncy = false),
    MoodMode.FOCUSED to MoodMotion(enabled = false, durationScale = 1f, bouncy = false),
    MoodMode.MOTIVATED to MoodMotion(enabled = true, durationScale = 0.8f, bouncy = true),
    MoodMode.OVERWHELMED to MoodMotion(enabled = false, durationScale = 1f, bouncy = false),
)

// Neutral base — warm cream from the brand logo (light) / deep espresso-black
// with high-contrast ivory text (dark). Never changes per mood.
private val LightBackground = Color(0xFFFAF3E9)
private val LightSurface = Color(0xFFFFFDF7)
private val LightSurfaceVariant = Color(0xFFF0E6D7)
private val LightOnBackground = Color(0xFF2B241B)
private val LightOnSurfaceVariant = Color(0xFF5D5546)
private val LightOutline = Color(0xFF8B8171)
private val LightOutlineVariant = Color(0xFFDCD3C2)
private val LightSurfaceContainerLow = Color(0xFFFBF6EC)
private val LightSurfaceContainerHigh = Color(0xFFF5EDDF)
private val LightInverseSurface = Color(0xFF332B20)
private val LightInverseOnSurface = Color(0xFFF7F0E3)

// Deep, high-contrast dark mode: near-black warm base (great on OLED) with
// bright ivory foregrounds (~17:1 contrast) and clearly separated layers.
private val DarkBackground = Color(0xFF0D0A06)
private val DarkSurface = Color(0xFF1A140B)
private val DarkSurfaceVariant = Color(0xFF2C2416)
private val DarkOnBackground = Color(0xFFF8F1E2)
private val DarkOnSurfaceVariant = Color(0xFFD9CEB9)
private val DarkOutline = Color(0xFFA99D86)
private val DarkOutlineVariant = Color(0xFF3B3222)
private val DarkSurfaceContainerLow = Color(0xFF140F08)
private val DarkSurfaceContainerHigh = Color(0xFF251E11)
private val DarkInverseSurface = Color(0xFFF3EBDC)
private val DarkInverseOnSurface = Color(0xFF241E14)

fun moodThemeFor(
    mode: MoodMode,
    darkTheme: Boolean,
    accentPack: String,
    reducedMotion: Boolean,
): MoodTheme {
    val pack = accentPacks[accentPack] ?: accentPacks.getValue("classic")
    val pair = pack.getValue(mode)
    val accent = if (darkTheme) pair.dark else pair.light
    val background = if (darkTheme) DarkBackground else LightBackground
    val soft = accent.copy(alpha = if (darkTheme) 0.24f else 0.13f).compositeOver(background)
    val baseMotion = moodMotions.getValue(mode)
    return MoodTheme(
        mode = mode,
        accent = accent,
        onAccent = if (darkTheme) Color(0xFF1A1309) else Color(0xFFFFFCF5),
        accentSoft = soft,
        onAccentSoft = accent,
        gradient = pair.gradient,
        motion = if (reducedMotion) baseMotion.copy(enabled = false) else baseMotion,
    )
}

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    moodMode: MoodMode = MoodMode.CALM,
    accentPack: String = "classic",
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val targetTheme = moodThemeFor(moodMode, darkTheme, accentPack, reducedMotion)
    // Accent rendered on the OPPOSITE surface (snackbar actions via inversePrimary).
    val inverseAccentTarget = moodThemeFor(moodMode, !darkTheme, accentPack, reducedMotion).accent

    // Detect a light↔dark flip so the whole palette cross-fades together, even
    // in moods whose own motion profile is static (Focused, Overwhelmed).
    var lastDarkTheme by remember { mutableStateOf(darkTheme) }
    val themeFlipped = lastDarkTheme != darkTheme
    SideEffect { lastDarkTheme = darkTheme }

    // Neutral (background/surface) colors animate on theme flips only —
    // they never change per mood.
    val neutralSpec: FiniteAnimationSpec<Color> = if (reducedMotion) {
        snap()
    } else {
        tween(durationMillis = 550, easing = FastOutSlowInEasing)
    }

    // Accents shift smoothly when the mood changes; on a theme flip they use
    // the same cross-fade as the neutrals so the switch feels like one motion.
    val colorSpec: FiniteAnimationSpec<Color> = when {
        themeFlipped -> neutralSpec
        targetTheme.motion.enabled ->
            tween(durationMillis = (600 * targetTheme.motion.durationScale).toInt(), easing = FastOutSlowInEasing)
        else -> snap()
    }
    val accent by animateColorAsState(targetTheme.accent, colorSpec, label = "moodAccent")
    val onAccent by animateColorAsState(targetTheme.onAccent, colorSpec, label = "moodOnAccent")
    val accentSoft by animateColorAsState(targetTheme.accentSoft, colorSpec, label = "moodAccentSoft")
    val onAccentSoft by animateColorAsState(targetTheme.onAccentSoft, colorSpec, label = "moodOnAccentSoft")
    val inversePrimary by animateColorAsState(inverseAccentTarget, colorSpec, label = "moodInversePrimary")

    val background by animateColorAsState(if (darkTheme) DarkBackground else LightBackground, neutralSpec, label = "themeBackground")
    val surface by animateColorAsState(if (darkTheme) DarkSurface else LightSurface, neutralSpec, label = "themeSurface")
    val surfaceVariant by animateColorAsState(if (darkTheme) DarkSurfaceVariant else LightSurfaceVariant, neutralSpec, label = "themeSurfaceVariant")
    val onBackground by animateColorAsState(if (darkTheme) DarkOnBackground else LightOnBackground, neutralSpec, label = "themeOnBackground")
    val onSurfaceVariant by animateColorAsState(if (darkTheme) DarkOnSurfaceVariant else LightOnSurfaceVariant, neutralSpec, label = "themeOnSurfaceVariant")
    val outline by animateColorAsState(if (darkTheme) DarkOutline else LightOutline, neutralSpec, label = "themeOutline")
    val outlineVariant by animateColorAsState(if (darkTheme) DarkOutlineVariant else LightOutlineVariant, neutralSpec, label = "themeOutlineVariant")
    val surfaceContainerLow by animateColorAsState(if (darkTheme) DarkSurfaceContainerLow else LightSurfaceContainerLow, neutralSpec, label = "themeSurfaceContainerLow")
    val surfaceContainerHigh by animateColorAsState(if (darkTheme) DarkSurfaceContainerHigh else LightSurfaceContainerHigh, neutralSpec, label = "themeSurfaceContainerHigh")
    val inverseSurface by animateColorAsState(if (darkTheme) DarkInverseSurface else LightInverseSurface, neutralSpec, label = "themeInverseSurface")
    val inverseOnSurface by animateColorAsState(if (darkTheme) DarkInverseOnSurface else LightInverseOnSurface, neutralSpec, label = "themeInverseOnSurface")

    val moodTheme = targetTheme.copy(
        accent = accent,
        onAccent = onAccent,
        accentSoft = accentSoft,
        onAccentSoft = onAccentSoft,
    )

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = moodTheme.accent,
            onPrimary = moodTheme.onAccent,
            primaryContainer = moodTheme.accentSoft,
            onPrimaryContainer = moodTheme.onAccentSoft,
            secondary = moodTheme.accent,
            onSecondary = moodTheme.onAccent,
            secondaryContainer = moodTheme.accentSoft,
            onSecondaryContainer = moodTheme.onAccentSoft,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainerHighest = surfaceVariant,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainer = surface,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = background,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
        )
    } else {
        lightColorScheme(
            primary = moodTheme.accent,
            onPrimary = moodTheme.onAccent,
            primaryContainer = moodTheme.accentSoft,
            onPrimaryContainer = moodTheme.onAccentSoft,
            secondary = moodTheme.accent,
            onSecondary = moodTheme.onAccent,
            secondaryContainer = moodTheme.accentSoft,
            onSecondaryContainer = moodTheme.onAccentSoft,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainerHighest = surfaceVariant,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainer = surface,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surface,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
        )
    }

    // Keep status/navigation bar icons legible when the in-app theme overrides
    // the system one (light icons on dark, dark icons on light).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalMoodTheme provides moodTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Unwraps a Compose view context to its host Activity (for window control). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
