package com.rork.mindsetframestracker.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.rork.mindsetframestracker.data.AvatarConfig
import kotlin.math.cos
import kotlin.math.sin

/**
 * Layered procedural companion avatar, clipped to a circle:
 * frame background → (cape/hood pre-layers) → torso outfit → neck → head →
 * expression (eyes + mouth) → hair → shoulder pet. Fully vector-drawn on
 * Canvas — zero image assets, so it stays pixel-perfect at any size
 * (44dp card chip up to the 180dp Studio preview).
 *
 * The [AvatarConfig.gender] field switches between the female and male
 * builds (shoulder width, neck, lashes) while keeping one consistent art
 * style; [AvatarConfig.expression] overrides eyes+mouth with preset artwork.
 */
@Composable
fun CompanionAvatar(
    config: AvatarConfig,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val frame = AvatarCatalog.frames[safeIndex(config.frame, AvatarCatalog.frames.size)]
            drawFrameBackground(frame.style)
            drawCompanionBody(config)
            drawShoulderPet(config)
        }
    }
}

private fun safeIndex(index: Int, size: Int): Int =
    if (size <= 0) 0 else ((index % size) + size) % size

// ── Frame backgrounds ──────────────────────────────────────────────────

/** Paints one circular frame style across the whole canvas. */
fun DrawScope.drawFrameBackground(style: FrameStyle) {
    val s = size.minDimension
    when (style) {
        is FrameStyle.Solid -> drawRect(style.color)
        is FrameStyle.Gradient -> drawRect(
            Brush.linearGradient(style.colors, start = Offset.Zero, end = Offset(s * 0.4f, s)),
        )
        is FrameStyle.Dots -> {
            drawRect(style.bg)
            val step = s / 5f
            for (row in 0..5) {
                for (col in 0..5) {
                    val shift = if (row % 2 == 0) 0f else step / 2f
                    drawCircle(
                        color = style.dot.copy(alpha = 0.85f),
                        radius = s * 0.035f,
                        center = Offset(col * step + shift, row * step + step / 2f),
                    )
                }
            }
        }
        is FrameStyle.Stripes -> {
            drawRect(style.bg)
            val stripeW = s / 9f
            var x = -s
            while (x < s * 2f) {
                val path = Path().apply {
                    moveTo(x, s)
                    lineTo(x + s, 0f)
                    lineTo(x + s + stripeW, 0f)
                    lineTo(x + stripeW, s)
                    close()
                }
                drawPath(path, style.stripe.copy(alpha = 0.8f))
                x += stripeW * 2.4f
            }
        }
        is FrameStyle.Rays -> {
            drawRect(style.bg)
            val center = Offset(s / 2f, s / 2f)
            repeat(12) { i ->
                val a0 = Math.toRadians((i * 30f).toDouble())
                val a1 = Math.toRadians((i * 30f + 14f).toDouble())
                val r = s
                val path = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(center.x + r * cos(a0).toFloat(), center.y + r * sin(a0).toFloat())
                    lineTo(center.x + r * cos(a1).toFloat(), center.y + r * sin(a1).toFloat())
                    close()
                }
                drawPath(path, style.ray.copy(alpha = 0.55f))
            }
        }
        is FrameStyle.NightSky -> {
            drawRect(Brush.verticalGradient(listOf(style.top, style.bottom)))
            val stars = listOf(
                0.18f to 0.22f, 0.34f to 0.12f, 0.55f to 0.18f, 0.74f to 0.10f,
                0.84f to 0.30f, 0.12f to 0.45f, 0.90f to 0.55f, 0.26f to 0.70f,
                0.68f to 0.78f, 0.46f to 0.88f, 0.08f to 0.82f, 0.60f to 0.42f,
            )
            stars.forEachIndexed { i, (fx, fy) ->
                drawCircle(
                    color = style.star.copy(alpha = if (i % 3 == 0) 0.95f else 0.6f),
                    radius = s * if (i % 4 == 0) 0.014f else 0.008f,
                    center = Offset(fx * s, fy * s),
                )
            }
            // Crescent moon: bright disc with a bite of sky taken out.
            drawCircle(style.star, radius = s * 0.075f, center = Offset(s * 0.78f, s * 0.20f))
            drawCircle(style.top, radius = s * 0.062f, center = Offset(s * 0.81f, s * 0.175f))
        }
        is FrameStyle.Hills -> {
            drawRect(style.sky)
            drawOval(
                color = style.far,
                topLeft = Offset(-s * 0.35f, s * 0.58f),
                size = Size(s * 0.95f, s * 0.7f),
            )
            drawOval(
                color = style.near,
                topLeft = Offset(s * 0.35f, s * 0.66f),
                size = Size(s * 1.05f, s * 0.8f),
            )
        }
        is FrameStyle.Waves -> {
            drawRect(style.sky)
            drawRect(style.sea, topLeft = Offset(0f, s * 0.62f), size = Size(s, s * 0.38f))
            for (i in 0..3) {
                drawOval(
                    color = if (i % 2 == 0) style.foam.copy(alpha = 0.5f) else style.sea.copy(alpha = 0.7f),
                    topLeft = Offset(s * (-0.15f + i * 0.28f), s * (0.585f + (i % 2) * 0.02f)),
                    size = Size(s * 0.36f, s * 0.07f),
                )
            }
        }
        is FrameStyle.Mountain -> {
            drawRect(style.sky)
            drawCircle(style.sun, radius = s * 0.09f, center = Offset(s * 0.72f, s * 0.24f))
            val peak = Path().apply {
                moveTo(-s * 0.05f, s)
                lineTo(s * 0.38f, s * 0.40f)
                lineTo(s * 0.62f, s * 0.68f)
                lineTo(s * 0.78f, s * 0.52f)
                lineTo(s * 1.1f, s)
                close()
            }
            drawPath(peak, style.peak)
        }
        is FrameStyle.Confetti -> {
            drawRect(style.bg)
            val spots = listOf(
                0.15f to 0.18f, 0.36f to 0.10f, 0.62f to 0.16f, 0.84f to 0.24f,
                0.10f to 0.44f, 0.88f to 0.48f, 0.20f to 0.74f, 0.48f to 0.86f,
                0.76f to 0.76f, 0.55f to 0.40f, 0.30f to 0.55f, 0.70f to 0.60f,
            )
            spots.forEachIndexed { i, (fx, fy) ->
                val c = style.pieces[i % style.pieces.size]
                if (i % 2 == 0) {
                    drawCircle(c, radius = s * 0.022f, center = Offset(fx * s, fy * s))
                } else {
                    drawRoundRect(
                        color = c,
                        topLeft = Offset(fx * s - s * 0.025f, fy * s - s * 0.012f),
                        size = Size(s * 0.05f, s * 0.024f),
                        cornerRadius = CornerRadius(s * 0.01f),
                    )
                }
            }
        }
    }
}

// ── Character layers ───────────────────────────────────────────────────

private fun DrawScope.drawCompanionBody(config: AvatarConfig) {
    val s = size.minDimension
    val skin = AvatarCatalog.skinTones[safeIndex(config.skinTone, AvatarCatalog.skinTones.size)]
    val outfit = AvatarCatalog.outfits[safeIndex(config.outfit, AvatarCatalog.outfits.size)]
    val hairColor = AvatarCatalog.hairColors[safeIndex(config.hairColor, AvatarCatalog.hairColors.size)]
    val ink = Color(0xFF3A2E24)
    val isMale = safeIndex(config.gender, AvatarCatalog.GENDER_COUNT) == 1

    // Neck behind the torso collar — broader for the male build.
    val neckW = if (isMale) 0.115f else 0.088f
    drawRoundRect(
        color = skin,
        topLeft = Offset(s * (0.5f - neckW / 2f), s * 0.60f),
        size = Size(s * neckW, s * 0.14f),
        cornerRadius = CornerRadius(s * 0.03f),
    )

    drawOutfit(outfit, isMale, skin)

    drawFace(config.faceShape, skin)
    drawFaceFeatures(config, ink, isMale)
    drawHair(config.hair, hairColor)
}

// ── Outfits — real clothing designs, not just colors ──────────────────

/**
 * Draws the torso garment. Each [OutfitDesign] renders distinct clothing
 * artwork: hoods, collars, lapels, straps, stripes, capes. The torso
 * extends past the bottom edge; the circle clip crops it.
 */
private fun DrawScope.drawOutfit(spec: OutfitSpec, isMale: Boolean, skin: Color) {
    val s = size.minDimension
    val cx = s * 0.5f
    // Male build: broader, squarer shoulders. Female: softer, narrower.
    val torsoW = if (isMale) 0.62f else 0.53f
    val corner = if (isMale) 0.09f else 0.15f
    val left = cx - s * torsoW / 2f
    val top = s * 0.70f
    val torso = Size(s * torsoW, s * 0.44f)

    fun torsoRect(color: Color) = drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = torso,
        cornerRadius = CornerRadius(s * corner),
    )

    fun torsoRectBrush(brush: Brush) = drawRoundRect(
        brush = brush,
        topLeft = Offset(left, top),
        size = torso,
        cornerRadius = CornerRadius(s * corner),
    )

    fun collarNotch() = drawCircle(color = skin, radius = s * 0.052f, center = Offset(cx, s * 0.715f))

    when (spec.design) {
        OutfitDesign.TEE -> {
            torsoRect(spec.base)
            collarNotch()
            // Ribbed collar trim + tiny chest pocket.
            drawArc(
                color = spec.accent.copy(alpha = 0.9f),
                startAngle = 10f, sweepAngle = 160f, useCenter = false,
                topLeft = Offset(cx - s * 0.065f, s * 0.685f),
                size = Size(s * 0.13f, s * 0.075f),
                style = Stroke(width = s * 0.016f, cap = StrokeCap.Round),
            )
            drawRoundRect(
                color = spec.accent.copy(alpha = 0.65f),
                topLeft = Offset(cx + s * 0.10f, s * 0.83f),
                size = Size(s * 0.075f, s * 0.06f),
                cornerRadius = CornerRadius(s * 0.015f),
            )
        }
        OutfitDesign.HOODIE -> {
            // Bunched hood behind the neck, then the body and kangaroo pocket.
            drawArc(
                color = spec.accent,
                startAngle = 180f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(cx - s * 0.165f, s * 0.655f),
                size = Size(s * 0.33f, s * 0.13f),
            )
            torsoRect(spec.base)
            collarNotch()
            // Drawstrings with bead ends.
            listOf(-1f, 1f).forEach { dir ->
                drawLine(
                    color = spec.accent,
                    start = Offset(cx + dir * s * 0.045f, s * 0.745f),
                    end = Offset(cx + dir * s * 0.055f, s * 0.83f),
                    strokeWidth = s * 0.013f,
                    cap = StrokeCap.Round,
                )
                drawCircle(spec.accent, s * 0.012f, Offset(cx + dir * s * 0.055f, s * 0.835f))
            }
            // Kangaroo pocket.
            drawRoundRect(
                color = spec.accent.copy(alpha = 0.55f),
                topLeft = Offset(cx - s * 0.115f, s * 0.90f),
                size = Size(s * 0.23f, s * 0.11f),
                cornerRadius = CornerRadius(s * 0.035f),
            )
        }
        OutfitDesign.SHIRT -> {
            torsoRect(spec.base)
            collarNotch()
            // Pointed collar wings + button placket.
            listOf(-1f, 1f).forEach { dir ->
                val wing = Path().apply {
                    moveTo(cx, s * 0.735f)
                    lineTo(cx + dir * s * 0.085f, s * 0.705f)
                    lineTo(cx + dir * s * 0.035f, s * 0.785f)
                    close()
                }
                drawPath(wing, spec.accent)
            }
            for (i in 0..2) {
                drawCircle(
                    color = spec.accent.copy(alpha = 0.9f),
                    radius = s * 0.012f,
                    center = Offset(cx, s * (0.82f + i * 0.065f)),
                )
            }
        }
        OutfitDesign.SAILOR -> {
            torsoRect(spec.base)
            // Broad square sailor collar draped over the shoulders.
            listOf(-1f, 1f).forEach { dir ->
                val flap = Path().apply {
                    moveTo(cx, s * 0.72f)
                    lineTo(cx + dir * s * (torsoW / 2f - 0.02f), s * 0.705f)
                    lineTo(cx + dir * s * (torsoW / 2f - 0.055f), s * 0.83f)
                    lineTo(cx + dir * s * 0.015f, s * 0.775f)
                    close()
                }
                drawPath(flap, spec.accent)
                drawLine(
                    color = Color.White.copy(alpha = 0.8f),
                    start = Offset(cx + dir * s * (torsoW / 2f - 0.045f), s * 0.72f),
                    end = Offset(cx + dir * s * (torsoW / 2f - 0.065f), s * 0.815f),
                    strokeWidth = s * 0.008f,
                )
            }
            collarNotch()
            // Neck knot.
            drawCircle(Color.White.copy(alpha = 0.9f), s * 0.022f, Offset(cx, s * 0.785f))
        }
        OutfitDesign.TURTLENECK -> {
            torsoRect(spec.base)
            // High folded collar covering the neck; ribbing lines.
            drawRoundRect(
                color = spec.accent,
                topLeft = Offset(cx - s * 0.075f, s * 0.615f),
                size = Size(s * 0.15f, s * 0.115f),
                cornerRadius = CornerRadius(s * 0.035f),
            )
            for (i in 0..3) {
                drawLine(
                    color = spec.base.copy(alpha = 0.5f),
                    start = Offset(cx - s * 0.055f + i * s * 0.037f, s * 0.625f),
                    end = Offset(cx - s * 0.055f + i * s * 0.037f, s * 0.72f),
                    strokeWidth = s * 0.007f,
                )
            }
        }
        OutfitDesign.STRIPES -> {
            torsoRect(spec.base)
            // Horizontal knit stripes clipped to the torso silhouette.
            val stripePath = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = left, top = top, right = left + torso.width, bottom = top + torso.height,
                        cornerRadius = CornerRadius(s * corner),
                    )
                )
            }
            clipPath(stripePath) {
                for (i in 0..2) {
                    drawRect(
                        color = spec.accent.copy(alpha = 0.85f),
                        topLeft = Offset(left, s * (0.80f + i * 0.095f)),
                        size = Size(torso.width, s * 0.038f),
                    )
                }
            }
            collarNotch()
        }
        OutfitDesign.OVERALLS -> {
            // Accent under-shirt, then the bib and straps in the base color.
            torsoRect(spec.accent)
            collarNotch()
            drawRoundRect(
                color = spec.base,
                topLeft = Offset(cx - s * 0.105f, s * 0.80f),
                size = Size(s * 0.21f, s * 0.34f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
            listOf(-1f, 1f).forEach { dir ->
                drawLine(
                    color = spec.base,
                    start = Offset(cx + dir * s * (torsoW / 2f - 0.08f), s * 0.705f),
                    end = Offset(cx + dir * s * 0.075f, s * 0.825f),
                    strokeWidth = s * 0.030f,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = spec.accent,
                    radius = s * 0.014f,
                    center = Offset(cx + dir * s * 0.068f, s * 0.825f),
                )
            }
        }
        OutfitDesign.BLAZER -> {
            torsoRect(spec.base)
            // Open jacket: shirt V + folded lapels + button.
            val shirtV = Path().apply {
                moveTo(cx - s * 0.065f, s * 0.70f)
                lineTo(cx + s * 0.065f, s * 0.70f)
                lineTo(cx, s * 0.88f)
                close()
            }
            drawPath(shirtV, spec.accent)
            collarNotch()
            listOf(-1f, 1f).forEach { dir ->
                val lapel = Path().apply {
                    moveTo(cx + dir * s * 0.065f, s * 0.70f)
                    lineTo(cx + dir * s * 0.115f, s * 0.755f)
                    lineTo(cx + dir * s * 0.02f, s * 0.845f)
                    lineTo(cx, s * 0.80f)
                    close()
                }
                drawPath(lapel, spec.base.darken(0.18f))
            }
            drawCircle(spec.accent, s * 0.011f, Offset(cx, s * 0.905f))
        }
        OutfitDesign.VEST -> {
            // Shirt base in accent, vest panels in the base color.
            torsoRect(spec.accent)
            collarNotch()
            listOf(-1f, 1f).forEach { dir ->
                val panel = Path().apply {
                    moveTo(cx + dir * s * 0.045f, s * 0.705f)
                    lineTo(cx + dir * s * (torsoW / 2f - 0.015f), s * 0.72f)
                    lineTo(cx + dir * s * (torsoW / 2f - 0.015f), s * 1.05f)
                    lineTo(cx + dir * s * 0.055f, s * 1.05f)
                    lineTo(cx + dir * s * 0.075f, s * 0.86f)
                    close()
                }
                drawPath(panel, spec.base)
            }
            for (i in 0..1) {
                drawCircle(
                    color = spec.base.darken(0.25f),
                    radius = s * 0.011f,
                    center = Offset(cx, s * (0.87f + i * 0.07f)),
                )
            }
        }
        OutfitDesign.SCARF -> {
            torsoRect(spec.base)
            // Wrapped scarf band + hanging tail with fringe.
            drawRoundRect(
                color = spec.accent,
                topLeft = Offset(cx - s * 0.10f, s * 0.665f),
                size = Size(s * 0.20f, s * 0.075f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
            drawRoundRect(
                color = spec.accent,
                topLeft = Offset(cx + s * 0.025f, s * 0.72f),
                size = Size(s * 0.07f, s * 0.17f),
                cornerRadius = CornerRadius(s * 0.02f),
            )
            for (i in 0..2) {
                drawLine(
                    color = spec.accent.darken(0.2f),
                    start = Offset(cx + s * (0.035f + i * 0.02f), s * 0.875f),
                    end = Offset(cx + s * (0.035f + i * 0.02f), s * 0.905f),
                    strokeWidth = s * 0.009f,
                    cap = StrokeCap.Round,
                )
            }
        }
        OutfitDesign.VARSITY -> {
            torsoRect(spec.base)
            collarNotch()
            // Contrast shoulder yokes, zip line, and a chest patch.
            listOf(-1f, 1f).forEach { dir ->
                val yoke = Path().apply {
                    moveTo(cx + dir * s * 0.05f, s * 0.70f)
                    lineTo(cx + dir * s * (torsoW / 2f - 0.01f), s * 0.70f)
                    lineTo(cx + dir * s * (torsoW / 2f - 0.01f), s * 0.80f)
                    close()
                }
                drawPath(yoke, spec.accent.copy(alpha = 0.85f))
            }
            drawLine(
                color = spec.accent,
                start = Offset(cx, s * 0.765f),
                end = Offset(cx, s * 1.02f),
                strokeWidth = s * 0.012f,
            )
            drawStarShape(
                center = Offset(cx - s * 0.115f, s * 0.855f),
                radius = s * 0.038f,
                color = spec.accent,
            )
        }
        OutfitDesign.CAPE -> {
            // Heroic cape billowing behind the shoulders, then the suit.
            listOf(-1f, 1f).forEach { dir ->
                val wing = Path().apply {
                    moveTo(cx + dir * s * 0.10f, s * 0.71f)
                    quadraticBezierTo(
                        cx + dir * s * 0.38f, s * 0.78f,
                        cx + dir * s * 0.34f, s * 1.08f,
                    )
                    lineTo(cx + dir * s * 0.12f, s * 1.05f)
                    close()
                }
                drawPath(wing, spec.accent)
            }
            torsoRect(spec.base)
            collarNotch()
            // Clasp + chest emblem.
            drawCircle(Color(0xFFE9B44C), s * 0.018f, Offset(cx, s * 0.755f))
            drawStarShape(center = Offset(cx, s * 0.87f), radius = s * 0.05f, color = Color(0xFFE9B44C))
        }
        OutfitDesign.GALAXY -> {
            // Night hoodie sprinkled with stars.
            drawArc(
                color = spec.accent.copy(alpha = 0.6f),
                startAngle = 180f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(cx - s * 0.165f, s * 0.655f),
                size = Size(s * 0.33f, s * 0.13f),
            )
            torsoRect(spec.base)
            collarNotch()
            val sparkles = listOf(
                Offset(cx - s * 0.14f, s * 0.83f), Offset(cx + s * 0.10f, s * 0.80f),
                Offset(cx - s * 0.04f, s * 0.92f), Offset(cx + s * 0.16f, s * 0.93f),
                Offset(cx - s * 0.18f, s * 0.99f), Offset(cx + s * 0.03f, s * 1.02f),
            )
            sparkles.forEachIndexed { i, o ->
                drawCircle(
                    color = spec.accent.copy(alpha = if (i % 2 == 0) 0.95f else 0.6f),
                    radius = s * if (i % 3 == 0) 0.013f else 0.008f,
                    center = o,
                )
            }
            drawCircle(spec.accent, s * 0.028f, Offset(cx + s * 0.14f, s * 0.84f))
            drawCircle(spec.base, s * 0.022f, Offset(cx + s * 0.152f, s * 0.833f))
        }
        OutfitDesign.SUNSET -> {
            torsoRect(spec.base)
            val stripePath = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = left, top = top, right = left + torso.width, bottom = top + torso.height,
                        cornerRadius = CornerRadius(s * corner),
                    )
                )
            }
            clipPath(stripePath) {
                val sunset = listOf(Color(0xFFE2794A), Color(0xFFCE5A6D), Color(0xFF6C5F9E))
                sunset.forEachIndexed { i, c ->
                    drawRect(
                        color = c.copy(alpha = 0.9f),
                        topLeft = Offset(left, s * (0.80f + i * 0.085f)),
                        size = Size(torso.width, s * 0.045f),
                    )
                }
            }
            collarNotch()
        }
        OutfitDesign.GRADIENT -> {
            // Moodweaver — all four mood tints woven into one knit.
            torsoRectBrush(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF9CAF88), Color(0xFF6D82C4),
                        Color(0xFFE9B44C), Color(0xFFCE5A6D),
                    ),
                    startY = top,
                    endY = top + torso.height,
                )
            )
            collarNotch()
        }
    }
}

/** Slightly darkened variant for lapels/fringe shading. */
private fun Color.darken(amount: Float): Color = Color(
    red = (red * (1f - amount)).coerceIn(0f, 1f),
    green = (green * (1f - amount)).coerceIn(0f, 1f),
    blue = (blue * (1f - amount)).coerceIn(0f, 1f),
    alpha = alpha,
)

/** Filled five-point star used for patches and emblems. */
private fun DrawScope.drawStarShape(center: Offset, radius: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else radius * 0.45f
        val angle = Math.toRadians((i * 36.0) - 90.0)
        val x = center.x + r * cos(angle).toFloat()
        val y = center.y + r * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.clipPath(path: Path, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipPath(path)
    block()
    drawContext.canvas.restore()
}

// ── Face ───────────────────────────────────────────────────────────────

private fun DrawScope.drawFace(shapeIndex: Int, skin: Color) {
    val s = size.minDimension
    val cx = s * 0.5f
    val cy = s * 0.42f
    when (safeIndex(shapeIndex, AvatarCatalog.FACE_COUNT)) {
        0 -> drawOval(skin, Offset(cx - s * 0.23f, cy - s * 0.24f), Size(s * 0.46f, s * 0.48f))
        1 -> drawOval(skin, Offset(cx - s * 0.205f, cy - s * 0.27f), Size(s * 0.41f, s * 0.54f))
        2 -> drawOval(skin, Offset(cx - s * 0.26f, cy - s * 0.215f), Size(s * 0.52f, s * 0.43f))
        3 -> drawRoundRect(
            color = skin,
            topLeft = Offset(cx - s * 0.225f, cy - s * 0.24f),
            size = Size(s * 0.45f, s * 0.48f),
            cornerRadius = CornerRadius(s * 0.14f),
        )
        4 -> {
            // Tapered chin: round crown flowing into a soft point.
            val path = Path().apply {
                moveTo(cx - s * 0.23f, cy - s * 0.02f)
                cubicTo(
                    cx - s * 0.245f, cy - s * 0.30f,
                    cx + s * 0.245f, cy - s * 0.30f,
                    cx + s * 0.23f, cy - s * 0.02f,
                )
                quadraticBezierTo(cx + s * 0.20f, cy + s * 0.18f, cx, cy + s * 0.26f)
                quadraticBezierTo(cx - s * 0.20f, cy + s * 0.18f, cx - s * 0.23f, cy - s * 0.02f)
                close()
            }
            drawPath(path, skin)
        }
        else -> {
            // Soft jaw: oval crown over a rounded jawline.
            drawOval(skin, Offset(cx - s * 0.235f, cy - s * 0.25f), Size(s * 0.47f, s * 0.36f))
            drawRoundRect(
                color = skin,
                topLeft = Offset(cx - s * 0.215f, cy - s * 0.06f),
                size = Size(s * 0.43f, s * 0.31f),
                cornerRadius = CornerRadius(s * 0.15f),
            )
        }
    }
}

// ── Expression system ──────────────────────────────────────────────────

/**
 * Renders eyes + mouth. Expression index 0 ("custom") draws the user's own
 * eye/mouth picks; other indices render full preset artwork. The female
 * build adds subtle lashes on open-eye expressions.
 */
private fun DrawScope.drawFaceFeatures(config: AvatarConfig, ink: Color, isMale: Boolean) {
    val exprIndex = safeIndex(config.expression, AvatarCatalog.expressions.size)
    val exprId = AvatarCatalog.expressions[exprIndex].id
    if (exprId == "custom") {
        drawEyes(config.eyes, ink)
        drawMouth(config.mouth, ink)
        if (!isMale) drawLashes(ink)
        return
    }
    drawExpression(exprId, ink)
    if (!isMale && exprId !in setOf("sleepy", "winking", "hearts", "starstruck")) {
        drawLashes(ink)
    }
}

/** Tiny outer-corner lashes — the one soft gender cue on the face. */
private fun DrawScope.drawLashes(ink: Color) {
    val s = size.minDimension
    val y = s * 0.405f
    val dx = s * 0.088f
    listOf(-1f, 1f).forEach { dir ->
        val cornerX = s * 0.5f + dir * (dx + s * 0.045f)
        for (i in 0..1) {
            val a = Math.toRadians(if (dir < 0) 200.0 + i * 22.0 else -20.0 - i * 22.0 + 360.0)
            drawLine(
                color = ink,
                start = Offset(cornerX, y - s * 0.012f - i * s * 0.008f),
                end = Offset(
                    cornerX + s * 0.024f * cos(a).toFloat(),
                    y - s * 0.012f - i * s * 0.008f + s * 0.024f * sin(a).toFloat(),
                ),
                strokeWidth = s * 0.009f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Preset expression artwork: unique eye + mouth combos per id. */
private fun DrawScope.drawExpression(id: String, ink: Color) {
    val s = size.minDimension
    val y = s * 0.405f
    val dx = s * 0.088f
    val left = Offset(s * 0.5f - dx, y)
    val right = Offset(s * 0.5f + dx, y)
    val cx = s * 0.5f
    val my = s * 0.525f
    val stroke = Stroke(width = s * 0.016f, cap = StrokeCap.Round)
    val lip = Color(0xFFB55A4C)
    val blushColor = Color(0xFFE8A6A0)

    fun happyArc(center: Offset) = drawArc(
        color = ink, startAngle = 180f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(center.x - s * 0.038f, center.y - s * 0.028f),
        size = Size(s * 0.076f, s * 0.056f), style = stroke,
    )

    fun closedArc(center: Offset) = drawArc(
        color = ink, startAngle = 0f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(center.x - s * 0.038f, center.y - s * 0.028f),
        size = Size(s * 0.076f, s * 0.056f), style = stroke,
    )

    fun dot(center: Offset, r: Float = s * 0.024f) = drawCircle(ink, r, center)

    fun ringEye(center: Offset) {
        drawCircle(Color.White, s * 0.045f, center)
        drawCircle(ink, s * 0.027f, center)
        drawCircle(Color.White, s * 0.010f, Offset(center.x + s * 0.010f, center.y - s * 0.010f))
    }

    fun smile() = drawArc(
        color = ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(cx - s * 0.062f, my - s * 0.048f),
        size = Size(s * 0.124f, s * 0.086f), style = stroke,
    )

    fun openSmile(withTongue: Boolean) {
        drawArc(
            color = ink, startAngle = 0f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(cx - s * 0.058f, my - s * 0.052f),
            size = Size(s * 0.116f, s * 0.104f),
        )
        if (withTongue) {
            drawArc(
                color = lip, startAngle = 0f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(cx - s * 0.030f, my + s * 0.004f),
                size = Size(s * 0.060f, s * 0.040f),
            )
        }
    }

    fun blush() {
        drawCircle(blushColor, s * 0.024f, Offset(left.x - s * 0.02f, y + s * 0.065f))
        drawCircle(blushColor, s * 0.024f, Offset(right.x + s * 0.02f, y + s * 0.065f))
    }

    when (id) {
        "smiling" -> {
            happyArc(left); happyArc(right)
            smile()
        }
        "winking" -> {
            closedArc(left)
            ringEye(right)
            drawArc(
                color = ink, startAngle = 20f, sweepAngle = 120f, useCenter = false,
                topLeft = Offset(cx - s * 0.055f, my - s * 0.042f),
                size = Size(s * 0.105f, s * 0.078f), style = stroke,
            )
        }
        "neutral" -> {
            dot(left); dot(right)
            drawLine(ink, Offset(cx - s * 0.045f, my), Offset(cx + s * 0.045f, my), s * 0.015f, StrokeCap.Round)
        }
        "focused" -> {
            // Level brows over steady eyes, mouth set — deep-work face.
            listOf(left, right).forEach { c ->
                drawLine(
                    color = ink,
                    start = Offset(c.x - s * 0.042f, c.y - s * 0.052f),
                    end = Offset(c.x + s * 0.042f, c.y - s * 0.058f),
                    strokeWidth = s * 0.016f,
                    cap = StrokeCap.Round,
                )
                dot(c, s * 0.021f)
            }
            drawLine(ink, Offset(cx - s * 0.032f, my), Offset(cx + s * 0.032f, my), s * 0.015f, StrokeCap.Round)
        }
        "cheerful" -> {
            ringEye(left); ringEye(right)
            openSmile(withTongue = true)
        }
        "sleepy" -> {
            closedArc(left.copy(y = left.y + s * 0.012f))
            closedArc(right.copy(y = right.y + s * 0.012f))
            drawCircle(ink, s * 0.018f, Offset(cx, my + s * 0.005f), style = Stroke(width = s * 0.013f))
            // Drifting z z z beside the head.
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(230, 58, 46, 36)
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
            listOf(
                Triple(0.76f, 0.26f, 0.075f),
                Triple(0.82f, 0.20f, 0.058f),
                Triple(0.87f, 0.155f, 0.045f),
            ).forEach { (fx, fy, sizeF) ->
                paint.textSize = s * sizeF
                drawContext.canvas.nativeCanvas.drawText("z", s * fx, s * fy, paint)
            }
        }
        "starstruck" -> {
            drawStarShape(left, s * 0.052f, Color(0xFFE2A93C))
            drawStarShape(right, s * 0.052f, Color(0xFFE2A93C))
            openSmile(withTongue = true)
        }
        "determined" -> {
            // Brows angled in, confident smirk.
            listOf(-1f, 1f).forEach { dir ->
                val c = if (dir < 0) left else right
                drawLine(
                    color = ink,
                    start = Offset(c.x - dir * s * 0.045f, c.y - s * 0.065f),
                    end = Offset(c.x + dir * s * 0.038f, c.y - s * 0.045f),
                    strokeWidth = s * 0.017f,
                    cap = StrokeCap.Round,
                )
            }
            dot(left, s * 0.022f); dot(right, s * 0.022f)
            drawArc(
                color = ink, startAngle = 30f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(cx - s * 0.020f, my - s * 0.052f),
                size = Size(s * 0.105f, s * 0.086f), style = stroke,
            )
        }
        "joyful" -> {
            happyArc(left); happyArc(right)
            openSmile(withTongue = true)
            blush()
        }
        "silly" -> {
            ringEye(left)
            dot(right, s * 0.020f)
            drawCircle(ink, s * 0.050f, right, style = Stroke(width = s * 0.013f))
            // Tongue lolling out of a grin.
            smile()
            drawOval(
                color = lip,
                topLeft = Offset(cx + s * 0.005f, my + s * 0.012f),
                size = Size(s * 0.048f, s * 0.058f),
            )
        }
        "hearts" -> {
            drawHeartShape(left, s * 0.055f, Color(0xFFD9536B))
            drawHeartShape(right, s * 0.055f, Color(0xFFD9536B))
            smile()
            blush()
        }
        else -> {
            dot(left); dot(right)
            smile()
        }
    }
}

/** Filled heart used for the "hearts" expression eyes. */
private fun DrawScope.drawHeartShape(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + radius * 0.85f)
        cubicTo(
            center.x - radius * 1.45f, center.y - radius * 0.25f,
            center.x - radius * 0.65f, center.y - radius * 1.1f,
            center.x, center.y - radius * 0.35f,
        )
        cubicTo(
            center.x + radius * 0.65f, center.y - radius * 1.1f,
            center.x + radius * 1.45f, center.y - radius * 0.25f,
            center.x, center.y + radius * 0.85f,
        )
        close()
    }
    drawPath(path, color)
}

// ── Custom eyes & mouths (expression = "custom") ───────────────────────

private fun DrawScope.drawEyes(eyesIndex: Int, ink: Color) {
    val s = size.minDimension
    val y = s * 0.405f
    val dx = s * 0.088f
    val left = Offset(s * 0.5f - dx, y)
    val right = Offset(s * 0.5f + dx, y)
    val stroke = Stroke(width = s * 0.016f, cap = StrokeCap.Round)

    fun happyArc(center: Offset) = drawArc(
        color = ink,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - s * 0.038f, center.y - s * 0.028f),
        size = Size(s * 0.076f, s * 0.056f),
        style = stroke,
    )

    fun closedArc(center: Offset) = drawArc(
        color = ink,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - s * 0.038f, center.y - s * 0.028f),
        size = Size(s * 0.076f, s * 0.056f),
        style = stroke,
    )

    fun dot(center: Offset, r: Float = s * 0.024f) = drawCircle(ink, r, center)

    when (safeIndex(eyesIndex, AvatarCatalog.EYES_COUNT)) {
        0 -> { dot(left); dot(right) }
        1 -> {
            listOf(left, right).forEach { c ->
                drawCircle(Color.White, s * 0.045f, c)
                drawCircle(ink, s * 0.027f, c)
                drawCircle(Color.White, s * 0.010f, Offset(c.x + s * 0.010f, c.y - s * 0.010f))
            }
        }
        2 -> { happyArc(left); happyArc(right) }
        3 -> { closedArc(left.copy(y = left.y + s * 0.01f)); closedArc(right.copy(y = right.y + s * 0.01f)) }
        4 -> {
            listOf(left, right).forEach { c ->
                drawOval(ink, Offset(c.x - s * 0.017f, c.y - s * 0.028f), Size(s * 0.034f, s * 0.056f))
            }
        }
        5 -> { dot(left); happyArc(right) }
        6 -> {
            listOf(left, right).forEach { c ->
                val path = Path().apply {
                    moveTo(c.x, c.y - s * 0.032f)
                    quadraticBezierTo(c.x + s * 0.008f, c.y - s * 0.008f, c.x + s * 0.032f, c.y)
                    quadraticBezierTo(c.x + s * 0.008f, c.y + s * 0.008f, c.x, c.y + s * 0.032f)
                    quadraticBezierTo(c.x - s * 0.008f, c.y + s * 0.008f, c.x - s * 0.032f, c.y)
                    quadraticBezierTo(c.x - s * 0.008f, c.y - s * 0.008f, c.x, c.y - s * 0.032f)
                    close()
                }
                drawPath(path, ink)
            }
        }
        7 -> {
            listOf(left, right).forEach { c ->
                dot(c, s * 0.020f)
                drawCircle(ink, s * 0.055f, c, style = stroke)
            }
            drawLine(ink, Offset(left.x + s * 0.055f, y), Offset(right.x - s * 0.055f, y), s * 0.014f, StrokeCap.Round)
        }
        8 -> {
            listOf(left, right).forEach { c ->
                dot(c, s * 0.020f)
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(c.x - s * 0.052f, c.y - s * 0.044f),
                    size = Size(s * 0.104f, s * 0.088f),
                    cornerRadius = CornerRadius(s * 0.024f),
                    style = stroke,
                )
            }
            drawLine(ink, Offset(left.x + s * 0.052f, y), Offset(right.x - s * 0.052f, y), s * 0.014f, StrokeCap.Round)
        }
        9 -> {
            listOf(left, right).forEach { c ->
                dot(c)
                for (i in -1..1) {
                    val a = Math.toRadians((250.0 + i * 28.0))
                    val from = Offset(
                        c.x + s * 0.028f * cos(a).toFloat(),
                        c.y + s * 0.028f * sin(a).toFloat(),
                    )
                    val to = Offset(
                        c.x + s * 0.052f * cos(a).toFloat(),
                        c.y + s * 0.052f * sin(a).toFloat(),
                    )
                    drawLine(ink, from, to, s * 0.011f, StrokeCap.Round)
                }
            }
        }
        10 -> {
            closedArc(left); closedArc(right)
            val blush = Color(0xFFE8A6A0)
            drawCircle(blush, s * 0.024f, Offset(left.x - s * 0.02f, y + s * 0.065f))
            drawCircle(blush, s * 0.024f, Offset(right.x + s * 0.02f, y + s * 0.065f))
        }
        else -> {
            listOf(left, right).forEach { c ->
                drawCircle(ink, s * 0.033f, c, style = Stroke(width = s * 0.013f))
                dot(c, s * 0.012f)
            }
        }
    }
}

private fun DrawScope.drawMouth(mouthIndex: Int, ink: Color) {
    val s = size.minDimension
    val cx = s * 0.5f
    val my = s * 0.525f
    val stroke = Stroke(width = s * 0.016f, cap = StrokeCap.Round)
    val lip = Color(0xFFB55A4C)

    when (safeIndex(mouthIndex, AvatarCatalog.MOUTH_COUNT)) {
        0 -> drawArc(
            color = ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(cx - s * 0.062f, my - s * 0.048f),
            size = Size(s * 0.124f, s * 0.086f), style = stroke,
        )
        1 -> {
            drawArc(
                color = ink, startAngle = 0f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(cx - s * 0.058f, my - s * 0.052f),
                size = Size(s * 0.116f, s * 0.104f),
            )
            drawArc(
                color = lip, startAngle = 0f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(cx - s * 0.030f, my + s * 0.004f),
                size = Size(s * 0.060f, s * 0.040f),
            )
        }
        2 -> drawLine(ink, Offset(cx - s * 0.045f, my), Offset(cx + s * 0.045f, my), s * 0.015f, StrokeCap.Round)
        3 -> drawCircle(ink, s * 0.022f, Offset(cx, my), style = Stroke(width = s * 0.014f))
        4 -> {
            drawRoundRect(
                color = ink,
                topLeft = Offset(cx - s * 0.065f, my - s * 0.028f),
                size = Size(s * 0.13f, s * 0.056f),
                cornerRadius = CornerRadius(s * 0.028f),
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cx - s * 0.048f, my - s * 0.020f),
                size = Size(s * 0.096f, s * 0.020f),
                cornerRadius = CornerRadius(s * 0.008f),
            )
        }
        5 -> {
            drawArc(
                color = ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - s * 0.056f, my - s * 0.030f),
                size = Size(s * 0.056f, s * 0.048f), style = stroke,
            )
            drawArc(
                color = ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx, my - s * 0.030f),
                size = Size(s * 0.056f, s * 0.048f), style = stroke,
            )
        }
        6 -> drawArc(
            color = ink, startAngle = 30f, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(cx - s * 0.020f, my - s * 0.052f),
            size = Size(s * 0.105f, s * 0.086f), style = stroke,
        )
        7 -> {
            drawArc(
                color = ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - s * 0.062f, my - s * 0.048f),
                size = Size(s * 0.124f, s * 0.086f), style = stroke,
            )
            drawArc(
                color = lip, startAngle = 0f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(cx - s * 0.024f, my + s * 0.020f),
                size = Size(s * 0.048f, s * 0.042f),
            )
        }
        8 -> drawOval(lip, Offset(cx - s * 0.026f, my - s * 0.014f), Size(s * 0.052f, s * 0.040f))
        else -> drawCircle(ink, s * 0.016f, Offset(cx - s * 0.02f, my), style = Stroke(width = s * 0.013f))
    }
}

// ── Hair ───────────────────────────────────────────────────────────────

private fun DrawScope.drawHair(hairIndex: Int, hc: Color) {
    val s = size.minDimension
    val cx = s * 0.5f
    val cy = s * 0.42f

    fun cap(height: Float = 0.30f, width: Float = 0.475f) = drawArc(
        color = hc,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(cx - s * width / 2f, cy - s * 0.27f),
        size = Size(s * width, s * height),
    )

    when (safeIndex(hairIndex, AvatarCatalog.HAIR_COUNT)) {
        0 -> Unit
        1 -> cap(height = 0.22f)
        2 -> cap(height = 0.34f)
        3 -> {
            cap(height = 0.30f)
            val sweep = Path().apply {
                moveTo(cx - s * 0.238f, cy - s * 0.12f)
                quadraticBezierTo(cx - s * 0.10f, cy - s * 0.30f, cx + s * 0.20f, cy - s * 0.20f)
                quadraticBezierTo(cx + s * 0.02f, cy - s * 0.16f, cx - s * 0.10f, cy - s * 0.05f)
                close()
            }
            drawPath(sweep, hc)
        }
        4 -> {
            for (i in 0..4) {
                val angle = Math.toRadians(200.0 + i * 35.0)
                drawCircle(
                    color = hc,
                    radius = s * 0.088f,
                    center = Offset(
                        cx + s * 0.185f * cos(angle).toFloat(),
                        cy - s * 0.10f + s * 0.16f * sin(angle).toFloat(),
                    ),
                )
            }
        }
        5 -> {
            for (i in 0..6) {
                val angle = Math.toRadians(180.0 + i * 30.0)
                drawCircle(
                    color = hc,
                    radius = s * 0.105f,
                    center = Offset(
                        cx + s * 0.21f * cos(angle).toFloat(),
                        cy - s * 0.065f + s * 0.20f * sin(angle).toFloat(),
                    ),
                )
            }
        }
        6 -> {
            cap(height = 0.32f)
            drawRoundRect(
                color = hc,
                topLeft = Offset(cx - s * 0.285f, cy - s * 0.14f),
                size = Size(s * 0.085f, s * 0.36f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
            drawRoundRect(
                color = hc,
                topLeft = Offset(cx + s * 0.20f, cy - s * 0.14f),
                size = Size(s * 0.085f, s * 0.36f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
        }
        7 -> {
            cap(height = 0.28f)
            drawCircle(hc, s * 0.075f, Offset(cx, cy - s * 0.31f))
        }
        8 -> {
            cap(height = 0.28f)
            drawCircle(hc, s * 0.065f, Offset(cx - s * 0.20f, cy - s * 0.27f))
            drawCircle(hc, s * 0.065f, Offset(cx + s * 0.20f, cy - s * 0.27f))
        }
        9 -> {
            cap(height = 0.30f)
            drawOval(hc, Offset(cx + s * 0.17f, cy - s * 0.30f), Size(s * 0.11f, s * 0.15f))
            drawRoundRect(
                color = hc,
                topLeft = Offset(cx + s * 0.21f, cy - s * 0.20f),
                size = Size(s * 0.06f, s * 0.26f),
                cornerRadius = CornerRadius(s * 0.03f),
            )
        }
        10 -> {
            val spikes = Path().apply {
                moveTo(cx - s * 0.235f, cy - s * 0.10f)
                var x = cx - s * 0.235f
                val top = cy - s * 0.36f
                val base = cy - s * 0.13f
                var up = true
                while (x < cx + s * 0.235f) {
                    x += s * 0.078f
                    lineTo(x, if (up) top else base)
                    up = !up
                }
                lineTo(cx + s * 0.235f, base)
                close()
            }
            drawPath(spikes, hc)
        }
        11 -> {
            cap(height = 0.30f)
            for (i in 0..2) {
                drawCircle(hc, s * 0.062f, Offset(cx - s * 0.235f, cy - s * 0.06f + i * s * 0.09f))
                drawCircle(hc, s * 0.062f, Offset(cx + s * 0.235f, cy - s * 0.06f + i * s * 0.09f))
            }
        }
        12 -> {
            val leftCurtain = Path().apply {
                moveTo(cx - s * 0.02f, cy - s * 0.28f)
                quadraticBezierTo(cx - s * 0.26f, cy - s * 0.26f, cx - s * 0.235f, cy + s * 0.02f)
                lineTo(cx - s * 0.16f, cy - s * 0.02f)
                quadraticBezierTo(cx - s * 0.16f, cy - s * 0.18f, cx - s * 0.02f, cy - s * 0.22f)
                close()
            }
            val rightCurtain = Path().apply {
                moveTo(cx + s * 0.02f, cy - s * 0.28f)
                quadraticBezierTo(cx + s * 0.26f, cy - s * 0.26f, cx + s * 0.235f, cy + s * 0.02f)
                lineTo(cx + s * 0.16f, cy - s * 0.02f)
                quadraticBezierTo(cx + s * 0.16f, cy - s * 0.18f, cx + s * 0.02f, cy - s * 0.22f)
                close()
            }
            drawPath(leftCurtain, hc)
            drawPath(rightCurtain, hc)
        }
        else -> {
            cap(height = 0.32f)
            drawArc(
                color = Color(0xFFF3E8D7),
                startAngle = 195f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(cx - s * 0.215f, cy - s * 0.235f),
                size = Size(s * 0.43f, s * 0.34f),
                style = Stroke(width = s * 0.035f, cap = StrokeCap.Round),
            )
        }
    }
}

// ── Shoulder pets ──────────────────────────────────────────────────────

/**
 * Draws the companion pet perched on the shoulder. Flying friends
 * (butterfly, star, bee) hover beside the head; the plant sits at ground
 * level; everyone else sits right on the shoulder line.
 */
private fun DrawScope.drawShoulderPet(config: AvatarConfig) {
    val pet = AvatarCatalog.pets[safeIndex(config.companion, AvatarCatalog.pets.size)]
    if (pet.emoji.isEmpty()) return
    val s = size.minDimension
    val (fx, fy, scale) = when (pet.id) {
        "butterfly" -> Triple(0.80f, 0.40f, 0.16f)
        "star" -> Triple(0.80f, 0.36f, 0.16f)
        "bee" -> Triple(0.79f, 0.42f, 0.14f)
        "plant" -> Triple(0.80f, 0.95f, 0.19f)
        "whale" -> Triple(0.78f, 0.80f, 0.18f)
        "dragon" -> Triple(0.78f, 0.79f, 0.19f)
        else -> Triple(0.775f, 0.795f, 0.18f)
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = s * scale
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(pet.emoji, s * fx, s * fy, paint)
}
