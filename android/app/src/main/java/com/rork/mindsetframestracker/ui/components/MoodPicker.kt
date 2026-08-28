package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme

private data class MoodOption(val mode: MoodMode, val labelKey: String, val icon: ImageVector)

private val moodOptions: List<MoodOption> = listOf(
    MoodOption(MoodMode.CALM, "moodCalm", Icons.Outlined.Spa),
    MoodOption(MoodMode.FOCUSED, "moodFocused", Icons.Outlined.TrackChanges),
    MoodOption(MoodMode.MOTIVATED, "moodMotivated", Icons.Outlined.Bolt),
    MoodOption(MoodMode.OVERWHELMED, "moodOverwhelmed", Icons.Outlined.Cloud),
)

/** Resolves a mood label key to the current language's string. */
@Composable
private fun moodLabel(key: String): String {
    val s = appStrings()
    return when (key) {
        "moodCalm" -> s.moodCalm
        "moodFocused" -> s.moodFocused
        "moodMotivated" -> s.moodMotivated
        "moodOverwhelmed" -> s.moodOverwhelmed
        else -> key
    }
}

/**
 * The four-mode mood picker. Identical position and layout in every mode —
 * selection is conveyed by border, check icon, and bold label (never color alone).
 */
@Composable
fun MoodPicker(
    selected: MoodMode?,
    onSelect: (MoodMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        moodOptions.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowOptions.forEach { option ->
                    MoodChip(
                        option = option,
                        label = moodLabel(option.labelKey),
                        isSelected = selected == option.mode,
                        onClick = { onSelect(option.mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoodChip(
    option: MoodOption,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val moodTheme = LocalMoodTheme.current
    val haptics = LocalHapticFeedback.current

    // Press feedback: chip compresses slightly while held.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = moodTheme.motion.springFloat(),
        label = "moodChipPress",
    )

    // Selection state cross-fades instead of snapping.
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) colors.primaryContainer else colors.surface,
        animationSpec = moodTheme.motion.tween<Color>(280),
        label = "moodChipContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary else colors.outline.copy(alpha = 0.4f),
        animationSpec = moodTheme.motion.tween<Color>(280),
        label = "moodChipBorder",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) colors.primary else colors.onSurfaceVariant,
        animationSpec = moodTheme.motion.tween<Color>(280),
        label = "moodChipIcon",
    )

    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        interactionSource = interactionSource,
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics { selected = isSelected },
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = isSelected,
                enter = if (moodTheme.motion.enabled) {
                    scaleIn(animationSpec = moodTheme.motion.springFloat(), initialScale = 0.4f) + fadeIn()
                } else {
                    fadeIn()
                },
                exit = fadeOut() + scaleOut(targetScale = 0.4f),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
