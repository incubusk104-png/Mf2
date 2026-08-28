package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.BadgeTier
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Badge label strings — resolved from the active language's JSON string
 * table via [appStrings]. Kept here so the component is self-contained.
 */
data class BadgeStrings(
    val sectionTitle: String,
    val sectionSubtitle: String,
    val tier3Title: String,
    val tier3Desc: String,
    val tier7Title: String,
    val tier7Desc: String,
    val tier14Title: String,
    val tier14Desc: String,
    val tier30Title: String,
    val tier30Desc: String,
    val newBadge: String,
)

/** Returns the display title for a badge tier from the given strings. */
fun badgeTitle(tier: BadgeTier, s: BadgeStrings): String = when (tier) {
    BadgeTier.THREE_DAYS -> s.tier3Title
    BadgeTier.SEVEN_DAYS -> s.tier7Title
    BadgeTier.FOURTEEN_DAYS -> s.tier14Title
    BadgeTier.THIRTY_DAYS -> s.tier30Title
}

/** Returns the description for a badge tier from the given strings. */
fun badgeDesc(tier: BadgeTier, s: BadgeStrings): String = when (tier) {
    BadgeTier.THREE_DAYS -> s.tier3Desc
    BadgeTier.SEVEN_DAYS -> s.tier7Desc
    BadgeTier.FOURTEEN_DAYS -> s.tier14Desc
    BadgeTier.THIRTY_DAYS -> s.tier30Desc
}

/**
 * Badge section shown on the HomeScreen. Displays all earned achievement
 * badges in a horizontal row. When a new badge is earned (detected via
 * [newlyEarnedTier]), it animates in with a scale+glow pop and a "NEW"
 * indicator that fades after a few seconds. Respects reduced-motion.
 */
@Composable
fun BadgeSection(
    earnedBadges: Set<BadgeTier>,
    newlyEarnedTier: BadgeTier?,
    currentFullCompletionStreak: Int,
    strings: BadgeStrings,
    modifier: Modifier = Modifier,
) {
    if (earnedBadges.isEmpty() && newlyEarnedTier == null) return

    val moodTheme = LocalMoodTheme.current
    val haptics = LocalHapticFeedback.current

    // Sorted by tier ascending — earliest achievement first.
    val sortedBadges = earnedBadges.sortedBy { it.daysRequired }

    // "NEW" indicator timer — shows for a few seconds after a badge is earned.
    var newBadgeVisible by remember { mutableIntStateOf(0) }
    LaunchedEffect(newlyEarnedTier) {
        if (newlyEarnedTier != null) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            newBadgeVisible++
            delay(4_000)
            newBadgeVisible = 0
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        text = strings.sectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = strings.sectionSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                sortedBadges.forEach { tier ->
                    BadgeChip(
                        tier = tier,
                        isNew = tier == newlyEarnedTier && newBadgeVisible > 0,
                        showNewLabel = tier == newlyEarnedTier && newBadgeVisible > 0,
                        newLabel = strings.newBadge,
                        title = badgeTitle(tier, strings),
                        motionEnabled = moodTheme.motion.enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Individual badge chip — a circular medal icon with a label below it.
 * When [isNew] is true, the chip scales in with a spring pop and a
 * radial glow. Respects reduced-motion by snapping instantly.
 */
@Composable
private fun BadgeChip(
    tier: BadgeTier,
    isNew: Boolean,
    showNewLabel: Boolean,
    newLabel: String,
    title: String,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current

    // Scale pop animation when a new badge appears.
    val popScale = remember { Animatable(0f) }
    LaunchedEffect(isNew) {
        if (isNew && motionEnabled) {
            popScale.snapTo(0.3f)
            popScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } else {
            popScale.snapTo(1f)
        }
    }

    // Ongoing gentle breathing glow for the newest badge.
    val glowAlpha = remember { Animatable(0f) }
    LaunchedEffect(isNew) {
        if (isNew && motionEnabled) {
            launch {
                delay(600)
                while (true) {
                    glowAlpha.animateTo(0.7f, tween(1200, easing = FastOutSlowInEasing))
                    glowAlpha.animateTo(0.3f, tween(1200, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Glow halo behind the medal for newly earned badges.
            if (glowAlpha.value > 0f) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    moodTheme.accent.copy(alpha = glowAlpha.value * 0.6f),
                                    moodTheme.accent.copy(alpha = 0f),
                                ),
                            ),
                        ),
                )
            }

            Icon(
                imageVector = when (tier) {
                    BadgeTier.THREE_DAYS -> Icons.Outlined.WorkspacePremium
                    BadgeTier.SEVEN_DAYS -> Icons.Outlined.EmojiEvents
                    BadgeTier.FOURTEEN_DAYS -> Icons.Outlined.EmojiEvents
                    BadgeTier.THIRTY_DAYS -> Icons.Outlined.WorkspacePremium
                },
                contentDescription = title,
                tint = moodTheme.accent,
                modifier = Modifier
                    .size(36.dp)
                    .scale(popScale.value)
                    .graphicsLayer { alpha = popScale.value.coerceIn(0f, 1f) },
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "$title",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )

        AnimatedVisibility(
            visible = showNewLabel,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Text(
                text = newLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = moodTheme.accent,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
