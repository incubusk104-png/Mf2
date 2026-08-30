package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One day on the insights trend chart. Values are normalized to 0..1. */
data class TrendPoint(
    val dayKey: String,
    /** Short label under the x-axis ("M", "22"). */
    val axisLabel: String,
    /** Full label for the scrub tooltip ("Wed, Jul 22"). */
    val detailLabel: String,
    /** Fraction of habits completed that day (0..1). */
    val completion: Float,
    /** Mood mapped to 0..1 (Overwhelmed low → Motivated high), null when not logged. */
    val moodLevel: Float?,
    /** Display name of the logged mood, null when not logged. */
    val moodName: String?,
    val isToday: Boolean,
    val showAxisLabel: Boolean,
    /** Total steps synced from Fitbit / Polar / Health Connect / Strava that day, null if none. */
    val activitySteps: Long? = null,
    /** Distinct sources that synced activity that day ("fitbit", "polar", "health_connect", "strava"). */
    val activitySources: List<String> = emptyList(),
)

private fun trendSourceLabel(source: String): String = when (source) {
    "fitbit" -> "Fitbit"
    "polar" -> "Polar"
    "health_connect" -> "Health Connect"
    "strava" -> "Strava"
    else -> source.replace("_", " ").replaceFirstChar { it.uppercase() }
}

/**
 * Smooth dual-series line chart: a filled gradient line for habit completion
 * and a dashed line for mood level. Lines draw themselves in on first show
 * and whenever the data range changes; touch or drag horizontally to scrub
 * a day and read its exact values in a floating chip.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current
    val colorScheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()

    val accent = moodTheme.accent
    val lineBrush = Brush.horizontalGradient(moodTheme.gradient)
    val moodColor = colorScheme.onSurfaceVariant
    val gridColor = colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val baselineColor = colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val labelColor = colorScheme.onSurfaceVariant
    val scrubColor = colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val ringColor = colorScheme.surface
    val chipBg = colorScheme.onSurface
    val chipText = colorScheme.surface

    val habitProgress = remember { Animatable(0f) }
    val moodProgress = remember { Animatable(0f) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val motion = moodTheme.motion
    LaunchedEffect(points, motion.enabled) {
        selectedIndex = null
        if (motion.enabled) {
            habitProgress.snapTo(0f)
            moodProgress.snapTo(0f)
            launch {
                habitProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = (850 * motion.durationScale).toInt(),
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                moodProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = (850 * motion.durationScale).toInt(),
                        delayMillis = (220 * motion.durationScale).toInt(),
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            habitProgress.snapTo(1f)
            moodProgress.snapTo(1f)
        }
    }

    // Pre-measured axis labels — avoids re-measuring text on every draw frame.
    val labelLayouts = remember(points, textMeasurer) {
        points.map { point ->
            if (!point.showAxisLabel) null
            else textMeasurer.measure(
                text = AnnotatedString(point.axisLabel),
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = if (point.isToday) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }

    val selectedPoint = selectedIndex?.let { points.getOrNull(it) }
    val tooltipLayout = remember(selectedPoint, textMeasurer) {
        selectedPoint?.let { point ->
            val pct = (point.completion * 100).roundToInt()
            val text = buildString {
                append(point.detailLabel)
                append("  ·  ")
                append(pct)
                append("% done")
                point.moodName?.let { mood ->
                    append("  ·  ")
                    append(mood)
                }
                point.activitySteps?.let { steps ->
                    append("  ·  ")
                    append(steps)
                    append(" steps")
                }
                if (point.activitySources.isNotEmpty()) {
                    append(" (")
                    append(point.activitySources.joinToString(", ") { trendSourceLabel(it) })
                    append(")")
                }
            }
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }

    fun selectAt(x: Float, widthPx: Float) {
        if (widthPx <= 0f || points.size < 2) return
        val index = ((x / widthPx) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
        if (index != selectedIndex) {
            selectedIndex = index
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(216.dp)
            .semantics { contentDescription = "Trend chart of habit completion and mood over time" }
            .pointerInput(points) {
                detectTapGestures { offset -> selectAt(offset.x, size.width.toFloat()) }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> selectAt(offset.x, size.width.toFloat()) },
                ) { change, _ ->
                    change.consume()
                    selectAt(change.position.x, size.width.toFloat())
                }
            },
    ) {
        if (points.size < 2) return@Canvas

        val labelSpace = 26.dp.toPx()
        val topPad = 10.dp.toPx()
        val plotHeight = size.height - topPad - labelSpace
        val baseY = topPad + plotHeight
        val stepX = size.width / (points.size - 1)

        fun xAt(index: Int): Float = index * stepX
        fun yAt(value: Float): Float = topPad + (1f - value.coerceIn(0f, 1f)) * plotHeight

        // Grid: dashed guides at 25/50/75/100%, solid baseline at 0%.
        val gridDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { fraction ->
            drawLine(
                color = gridColor,
                start = Offset(0f, yAt(fraction)),
                end = Offset(size.width, yAt(fraction)),
                strokeWidth = 1.dp.toPx(),
                pathEffect = gridDash,
            )
        }
        drawLine(
            color = baselineColor,
            start = Offset(0f, baseY),
            end = Offset(size.width, baseY),
            strokeWidth = 1.dp.toPx(),
        )

        // Habit completion series — gradient area + smooth stroked line.
        val habitOffsets = points.mapIndexed { i, p -> Offset(xAt(i), yAt(p.completion)) }
        val habitPath = smoothLinePath(habitOffsets)
        val habitReveal = habitProgress.value
        if (habitReveal > 0f) {
            val fillPath = Path().apply {
                addPath(habitPath)
                lineTo(habitOffsets.last().x, baseY)
                lineTo(habitOffsets.first().x, baseY)
                close()
            }
            clipRect(right = size.width * habitReveal) {
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0f)),
                        startY = topPad,
                        endY = baseY,
                    ),
                )
            }
            val habitMeasure = PathMeasure().apply { setPath(habitPath, false) }
            val partial = Path()
            habitMeasure.getSegment(0f, habitMeasure.length * habitReveal, partial, true)
            drawPath(
                path = partial,
                brush = lineBrush,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // Today marker: soft halo + solid dot at the end of the habit line.
        val endAlpha = ((habitReveal - 0.85f) / 0.15f).coerceIn(0f, 1f)
        if (endAlpha > 0f) {
            val end = habitOffsets.last()
            drawCircle(color = accent, radius = 9.dp.toPx(), center = end, alpha = 0.22f * endAlpha)
            drawCircle(color = accent, radius = 4.dp.toPx(), center = end, alpha = endAlpha)
        }

        // Mood series — dashed smooth line through logged days plus dots.
        val moodOffsets = points.mapIndexedNotNull { i, p ->
            p.moodLevel?.let { Offset(xAt(i), yAt(it)) }
        }
        val moodReveal = moodProgress.value
        if (moodOffsets.size >= 2 && moodReveal > 0f) {
            val moodPath = smoothLinePath(moodOffsets)
            val moodMeasure = PathMeasure().apply { setPath(moodPath, false) }
            val partial = Path()
            moodMeasure.getSegment(0f, moodMeasure.length * moodReveal, partial, true)
            drawPath(
                path = partial,
                color = moodColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }
        moodOffsets.forEach { offset ->
            drawCircle(color = moodColor, radius = 2.5.dp.toPx(), center = offset, alpha = moodReveal)
        }

        // X-axis labels (sparse for long ranges), today in accent.
        points.forEachIndexed { i, point ->
            val layout = labelLayouts.getOrNull(i) ?: return@forEachIndexed
            val labelX = (xAt(i) - layout.size.width / 2f)
                .coerceIn(0f, size.width - layout.size.width)
            drawText(
                textLayoutResult = layout,
                color = if (point.isToday) accent else labelColor,
                topLeft = Offset(labelX, baseY + 6.dp.toPx()),
            )
        }

        // Scrub selection: guide line, highlighted dots, floating value chip.
        val index = selectedIndex
        if (index != null && index in points.indices) {
            val sx = xAt(index)
            drawLine(
                color = scrubColor,
                start = Offset(sx, topPad),
                end = Offset(sx, baseY),
                strokeWidth = 1.dp.toPx(),
            )
            val habitDot = habitOffsets[index]
            drawCircle(color = ringColor, radius = 6.dp.toPx(), center = habitDot)
            drawCircle(color = accent, radius = 4.dp.toPx(), center = habitDot)
            points[index].moodLevel?.let { level ->
                val moodDot = Offset(sx, yAt(level))
                drawCircle(color = ringColor, radius = 5.dp.toPx(), center = moodDot)
                drawCircle(color = moodColor, radius = 3.dp.toPx(), center = moodDot)
            }
            tooltipLayout?.let { layout ->
                val padH = 10.dp.toPx()
                val padV = 6.dp.toPx()
                val chipWidth = layout.size.width + padH * 2
                val chipHeight = layout.size.height + padV * 2
                val chipX = (sx - chipWidth / 2f).coerceIn(0f, (size.width - chipWidth).coerceAtLeast(0f))
                drawRoundRect(
                    color = chipBg,
                    topLeft = Offset(chipX, 0f),
                    size = Size(chipWidth, chipHeight),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
                drawText(
                    textLayoutResult = layout,
                    color = chipText,
                    topLeft = Offset(chipX + padH, padV),
                )
            }
        }
    }
}

/**
 * Builds a smooth path through [points] using midpoint cubic segments —
 * gentle curves with no overshoot past the data values.
 */
private fun smoothLinePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2f
        path.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
    }
    return path
}
