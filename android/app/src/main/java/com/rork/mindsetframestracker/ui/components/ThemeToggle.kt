package com.rork.mindsetframestracker.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Circular sun/moon switch that flips the app between the premium light theme
 * and the deep, high-contrast dark theme. The sun rotates and shrinks away
 * while the moon rises in (and vice versa), synced with the app-wide palette
 * cross-fade driven by AppTheme.
 */
@Composable
fun ThemeToggleButton(
    isDark: Boolean,
    reducedMotion: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // 0f = light (sun showing), 1f = dark (moon showing).
    val progressSpec: FiniteAnimationSpec<Float> = if (reducedMotion) {
        snap()
    } else {
        spring(dampingRatio = 0.55f, stiffness = 380f)
    }
    val progress by animateFloatAsState(
        targetValue = if (isDark) 1f else 0f,
        animationSpec = progressSpec,
        label = "themeToggleProgress",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "themeTogglePress",
    )
    val actionLabel = if (isDark) "Switch to light theme" else "Switch to dark theme"

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = modifier
            .size(44.dp)
            .scale(pressScale)
            .clip(CircleShape)
            .semantics { contentDescription = actionLabel }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                onToggle()
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            // Sun — fully visible in light mode, spins away as dark engages.
            Icon(
                imageVector = Icons.Outlined.LightMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        alpha = (1f - progress).coerceIn(0f, 1f)
                        rotationZ = 90f * progress
                        scaleX = 1f - 0.4f * progress
                        scaleY = 1f - 0.4f * progress
                    },
            )
            // Moon — rises in as dark mode takes over.
            Icon(
                imageVector = Icons.Outlined.DarkMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(21.dp)
                    .graphicsLayer {
                        alpha = progress.coerceIn(0f, 1f)
                        rotationZ = -90f * (1f - progress)
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = 0.6f + 0.4f * progress
                    },
            )
        }
    }
}
