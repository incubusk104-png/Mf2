package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiParticle(
    val angle: Float,
    val speed: Float,
    val pieceSize: Float,
    val colorIndex: Int,
    val spin: Float,
)

/**
 * One-shot confetti burst: tinted paper pieces shoot outward from the
 * center, drift down under light gravity, spin, and fade over ~1.8 seconds.
 * Pure Canvas — no per-frame allocations beyond the draw pass.
 *
 * Shared by the badge-unlock celebration (large burst) and the per-habit
 * check celebration (small burst around the checkmark). Callers are
 * responsible for skipping it entirely under reduced motion.
 */
@Composable
fun ConfettiBurst(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    particleCount: Int = 40,
    durationMillis: Int = 1_800,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
    }
    val particles = remember {
        val rng = Random(42)
        List(particleCount) { i ->
            ConfettiParticle(
                angle = rng.nextFloat() * 6.2832f,
                speed = 0.45f + rng.nextFloat() * 0.55f,
                pieceSize = 8f + rng.nextFloat() * 10f,
                colorIndex = i % colors.size,
                spin = rng.nextFloat() * 720f - 360f,
            )
        }
    }
    Canvas(modifier = modifier) {
        val p = progress.value
        if (p <= 0.01f || p >= 0.99f) return@Canvas
        val maxRadius = size.minDimension * 0.72f
        val cx = size.width / 2f
        val cy = size.height / 2f
        particles.forEach { particle ->
            val distance = maxRadius * particle.speed * p
            val x = cx + cos(particle.angle) * distance
            // Light gravity: pieces sink as the burst progresses.
            val y = cy + sin(particle.angle) * distance + p * p * size.minDimension * 0.30f
            val alpha = (1f - p).coerceIn(0f, 1f)
            rotate(degrees = particle.spin * p, pivot = Offset(x, y)) {
                drawRect(
                    color = colors[particle.colorIndex].copy(alpha = alpha),
                    topLeft = Offset(x - particle.pieceSize / 2f, y - particle.pieceSize / 3f),
                    size = Size(particle.pieceSize, particle.pieceSize * 0.6f),
                )
            }
        }
    }
}
