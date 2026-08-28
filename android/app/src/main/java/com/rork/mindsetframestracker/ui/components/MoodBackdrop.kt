package com.rork.mindsetframestracker.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme

/**
 * Soft atmospheric wash tied to the active mood accent: two large radial
 * glows bleeding in from the top corners, fading into the neutral
 * background. Because the mood accent itself is animated by the theme,
 * the backdrop cross-fades smoothly when the mood changes.
 */
@Composable
fun Modifier.moodBackdrop(): Modifier {
    val moodTheme = LocalMoodTheme.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glowTop = moodTheme.accent.copy(alpha = if (isDark) 0.16f else 0.11f)
    val glowSide = moodTheme.gradient.last().copy(alpha = if (isDark) 0.11f else 0.07f)
    return drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glowTop, Color.Transparent),
                center = Offset(size.width * 0.12f, -size.height * 0.04f),
                radius = (size.width * 0.95f).coerceAtLeast(1f),
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glowSide, Color.Transparent),
                center = Offset(size.width * 0.94f, size.height * 0.14f),
                radius = (size.width * 0.78f).coerceAtLeast(1f),
            ),
        )
    }
}
