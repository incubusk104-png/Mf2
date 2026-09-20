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
    val classicCalm = Mf2Palette.AccentPacks.fallback.getValue(MoodMode.CALM)
    MoodTheme(
        mode = MoodMode.CALM,
        accent = classicCalm.light,
        onAccent = Mf2Palette.OnBrandInk,
        accentSoft = Mf2Palette.AccentContainerLight,
        onAccentSoft = classicCalm.light,
        gradient = classicCalm.gradient,
        motion = MoodMotion(enabled = true, durationScale = 1.4f, bouncy = false),
    )
}

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

// ─────────────────────────────────────────────────────────────────────────────
// Neutral base — warm cream from the brand logo (light) / deep espresso-black
// with high-contrast ivory text (dark). Never changes per mood.
//
// Every value is an ALIAS into `Mf2Palette` (see Palette.kt). This file holds no
// colour literals of its own: the tokens are defined once there and referenced
// from here, so `MaterialTheme.colorScheme` follows the tokens automatically and
// there is exactly one place to change a colour.
// ─────────────────────────────────────────────────────────────────────────────
private val LightBackground = Mf2Palette.LightBackground
private val LightSurface = Mf2Palette.LightSurface
private val LightSurfaceVariant = Mf2Palette.LightSurfaceVariant
private val LightOnBackground = Mf2Palette.OnLight
private val LightOnSurfaceVariant = Mf2Palette.LightOnSurfaceVariant
private val LightOutline = Mf2Palette.LightOutline
private val LightOutlineVariant = Mf2Palette.LightOutlineVariant
private val LightSurfaceContainerLow = Mf2Palette.LightSurfaceContainerLow
private val LightSurfaceContainerHigh = Mf2Palette.LightSurfaceContainerHigh
private val LightInverseSurface = Mf2Palette.LightInverseSurface
private val LightInverseOnSurface = Mf2Palette.LightInverseOnSurface

// Deep, high-contrast dark mode: near-black warm base (great on OLED) with
// bright ivory foregrounds (~17:1 contrast) and clearly separated layers.
private val DarkBackground = Mf2Palette.DarkBackground
private val DarkSurface = Mf2Palette.DarkSurface
private val DarkSurfaceVariant = Mf2Palette.DarkSurfaceVariant
private val DarkOnBackground = Mf2Palette.OnDark
private val DarkOnSurfaceVariant = Mf2Palette.DarkOnSurfaceVariant
private val DarkOutline = Mf2Palette.DarkOutline
private val DarkOutlineVariant = Mf2Palette.DarkOutlineVariant
private val DarkSurfaceContainerLow = Mf2Palette.DarkSurfaceContainerLow
private val DarkSurfaceContainerHigh = Mf2Palette.DarkSurfaceContainerHigh
private val DarkInverseSurface = Mf2Palette.DarkInverseSurface
private val DarkInverseOnSurface = Mf2Palette.DarkInverseOnSurface

fun moodThemeFor(
    mode: MoodMode,
    darkTheme: Boolean,
    accentPack: String,
    reducedMotion: Boolean,
): MoodTheme {
    val pack = Mf2Palette.AccentPacks.all[accentPack] ?: Mf2Palette.AccentPacks.fallback
    val pair = pack.getValue(mode)
    val accent = if (darkTheme) pair.dark else pair.light
    val background = if (darkTheme) DarkBackground else LightBackground
    val soft = accent.copy(alpha = if (darkTheme) 0.24f else 0.13f).compositeOver(background)
    val baseMotion = moodMotions.getValue(mode)
    return MoodTheme(
        mode = mode,
        accent = accent,
        onAccent = if (darkTheme) Mf2Palette.OnAccentDeep else Mf2Palette.OnBrandInk,
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
