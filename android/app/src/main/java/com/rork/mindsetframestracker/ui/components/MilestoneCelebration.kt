package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.PI
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Streak milestones that trigger a celebration. */
val streakMilestones: List<Int> = listOf(7, 10, 30, 100)

private data class Particle(
    val startX: Float,
    val startY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val size: Float,
    val color: Color,
    val rotationSpeed: Float,
    val shape: Int,
    val delay: Float,
)

private const val DURATION_SECONDS = 2.6f
private const val GRAVITY = 900f

/**
 * Full-screen confetti burst celebrating a streak milestone. Fires once per
 * [trigger] change (increment the trigger to replay). Purely decorative:
 * hidden from accessibility and skipped entirely when [motionEnabled] is false.
 */
@Composable
fun MilestoneCelebration(
    trigger: Int,
    accentColors: List<Color>,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (trigger <= 0 || !motionEnabled) return

    val density = LocalDensity.current
    val particles = remember(trigger) {
        val random = Random(trigger * 31 + 7)
        val palette = accentColors.ifEmpty { listOf(Color(0xFF006876)) }
        List(90) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val speed = 350f + random.nextFloat() * 650f
            Particle(
                startX = 0.5f + (random.nextFloat() - 0.5f) * 0.25f,
                startY = 0.28f + (random.nextFloat() - 0.5f) * 0.1f,
                velocityX = cos(angle) * speed,
                velocityY = sin(angle) * speed - 450f,
                size = with(density) { (5 + random.nextInt(6)).dp.toPx() },
                color = palette[it % palette.size].copy(alpha = 0.85f + random.nextFloat() * 0.15f),
                rotationSpeed = (random.nextFloat() - 0.5f) * 720f,
                shape = random.nextInt(3),
                delay = random.nextFloat() * 0.25f,
            )
        }
    }

    var elapsed by remember(trigger) { mutableFloatStateOf(0f) }

    LaunchedEffect(trigger) {
        val start = withFrameNanos { it }
        while (elapsed < DURATION_SECONDS) {
            withFrameNanos { now ->
                elapsed = (now - start) / 1_000_000_000f
            }
        }
    }

    if (elapsed >= DURATION_SECONDS) return

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics { },
    ) {
        particles.forEach { particle ->
            val t = elapsed - particle.delay
            if (t <= 0f) return@forEach
            val life = t / (DURATION_SECONDS - particle.delay)
            if (life >= 1f) return@forEach

            val x = size.width * particle.startX + particle.velocityX * t
            val y = size.height * particle.startY + particle.velocityY * t + 0.5f * GRAVITY * t * t
            if (y > size.height + particle.size) return@forEach

            val alpha = (1f - life).coerceIn(0f, 1f)
            val color = particle.color.copy(alpha = particle.color.alpha * alpha)

            rotate(degrees = particle.rotationSpeed * t, pivot = Offset(x, y)) {
                when (particle.shape) {
                    0 -> drawCircle(color = color, radius = particle.size / 2f, center = Offset(x, y))
                    1 -> drawRect(
                        color = color,
                        topLeft = Offset(x - particle.size / 2f, y - particle.size / 4f),
                        size = Size(particle.size, particle.size / 2f),
                    )
                    else -> drawRect(
                        color = color,
                        topLeft = Offset(x - particle.size / 2f, y - particle.size / 2f),
                        size = Size(particle.size, particle.size),
                    )
                }
            }
        }
    }
}

/**
 * Brief banner announcing a streak milestone (e.g. "7-day milestone!").
 * Shows for ~2.6s to match the confetti duration, then dismisses itself.
 * Always shown when a milestone fires — even with Reduce motion on, where it
 * simply appears and disappears without slide/scale animation.
 */
@Composable
fun MilestoneBanner(
    trigger: Int,
    milestone: Int,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(trigger) {
        if (trigger <= 0 || milestone <= 0) return@LaunchedEffect
        visible = true
        delay(2_600)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (motionEnabled) {
            slideInVertically { -it } + scaleIn(initialScale = 0.9f) + fadeIn()
        } else {
            fadeIn(animationSpec = androidx.compose.animation.core.snap())
        },
        exit = if (motionEnabled) {
            slideOutVertically { -it } + fadeOut()
        } else {
            fadeOut(animationSpec = androidx.compose.animation.core.snap())
        },
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocalFireDepartment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "$milestone-day milestone!",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Returns the milestone just reached when the streak moves from [previous] to
 * [current], or null when no milestone boundary was crossed.
 */
fun milestoneReached(previous: Int, current: Int): Int? =
    streakMilestones.lastOrNull { previous < it && current >= it }
