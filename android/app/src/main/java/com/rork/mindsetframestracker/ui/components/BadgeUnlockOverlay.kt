package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rork.mindsetframestracker.data.BadgeTier
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen celebration shown the moment an achievement badge is earned.
 *
 * The medal pops in with a bouncy spring inside a slowly rotating sunburst,
 * with the badge title, description, and two actions: a primary
 * "Share my achievement" CTA (social-share incentive at the peak-pride
 * moment) and a quiet dismiss. All motion respects the mood engine's
 * reduced-motion profile — with motion off, everything renders statically.
 */
@Composable
fun BadgeUnlockOverlay(
    tier: BadgeTier,
    title: String,
    description: String,
    unlockedLabel: String,
    shareCta: String,
    dismissLabel: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val moodTheme = LocalMoodTheme.current
    val motionOn = moodTheme.motion.enabled
    val accent = moodTheme.gradient.first()
    val haptics = LocalHapticFeedback.current

    // Celebration haptic the moment the overlay lands.
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Medal pop — springy scale from zero.
    val medalScale = remember { Animatable(if (motionOn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motionOn) {
            medalScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
    }

    // Slow sunburst rotation behind the medal.
    val rayTransition = rememberInfiniteTransition(label = "badgeRays")
    val rayAngle by rayTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "badgeRayAngle",
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Celebratory confetti burst — fires once, then fades out.
                    if (motionOn) {
                        ConfettiBurst(
                            colors = listOf(
                                moodTheme.gradient.first(),
                                moodTheme.gradient.last(),
                                Color(0xFFE9B44C),
                                Color(0xFF9CAF88),
                                Color(0xFFC7724F),
                            ),
                            modifier = Modifier.size(240.dp),
                        )
                    }
                    // Sunburst rays — drawn behind the medal, slowly rotating.
                    Canvas(modifier = Modifier.size(160.dp)) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val inner = size.minDimension * 0.28f
                        val outer = size.minDimension * 0.5f
                        val angleOffset = if (motionOn) rayAngle else 0f
                        repeat(12) { i ->
                            val angle = Math.toRadians((i * 30f + angleOffset).toDouble())
                            val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                            drawLine(
                                color = accent.copy(alpha = 0.25f),
                                start = center + dir * inner,
                                end = center + dir * outer,
                                strokeWidth = 6f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    // Medal disc with gradient fill and trophy glyph.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .scale(medalScale.value)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        moodTheme.gradient.first(),
                                        moodTheme.gradient.last(),
                                    ),
                                ),
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color(0xFFFFFCF5),
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }

                Text(
                    text = unlockedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )

                // Share CTA — the peak-pride moment is the best time to ask.
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShare()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .height(52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.IosShare,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = shareCta,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(48.dp),
                ) { Text(dismissLabel) }
            }
        }
    }
}

// The shared one-shot ConfettiBurst composable lives in ConfettiEffects.kt
// and is reused here for the badge celebration and in HomeScreen for the
// per-habit check celebration.
