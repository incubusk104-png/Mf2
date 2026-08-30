package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.floor
import kotlinx.coroutines.launch

/** One day cell on the year heatmap. */
data class HeatmapDay(
    val dayKey: String,
    val detailLabel: String,
    val done: Int,
    val total: Int,
    val level: Int,
    val moodName: String?,
    val isToday: Boolean,
    /** Total steps synced from Polar / Health Connect / Strava that day, null if none. */
    val activitySteps: Long? = null,
    /** Distinct sources that synced activity that day ("polar", "health_connect", "strava"). */
    val activitySources: List<String> = emptyList(),
)

data class YearHeatmapData(
    val weeks: List<List<HeatmapDay?>>,
    val monthLabels: List<String?>,
    val activeDays: Int,
    val bestRun: Int,
)

private fun completionLevel(done: Int, total: Int): Int = when {
    total <= 0 || done <= 0 -> 0
    else -> (((done * 4) + total - 1) / total).coerceIn(1, 4)
}

private fun heatmapMoodTitle(mode: MoodMode): String =
    mode.name.lowercase().replaceFirstChar { it.uppercase() }

private fun sourceLabel(source: String): String = when (source) {
    "polar" -> "Polar"
    "health_connect" -> "Health Connect"
    "strava" -> "Strava"
    else -> source.replace("_", " ").replaceFirstChar { it.uppercase() }
}

/**
 * Builds the past-12-months contribution grid from raw check-in data plus
 * synced ActivityRecord data (Polar / Health Connect / Strava), in one pass over
 * both maps.
 */
fun buildYearHeatmapData(data: AppData): YearHeatmapData {
    val today = LocalDate.now()
    val windowStart = today.minusDays(364)
    val gridStart = windowStart.minusDays((windowStart.dayOfWeek.value - 1).toLong())
    val totalHabits = data.habits.size

    val doneByDay = HashMap<String, Int>()
    data.habits.forEach { habit ->
        data.checkIns[habit.id]?.forEach { key -> doneByDay.merge(key, 1, Int::plus) }
    }

    // dayKey -> total steps synced that day, and which sources contributed.
    val stepsByDay = HashMap<String, Long>()
    val sourcesByDay = HashMap<String, MutableSet<String>>()
    data.activityRecords.forEach { record ->
        val key = Dates.key(
            java.time.Instant.ofEpochMilli(record.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate(),
        )
        record.steps?.let { steps -> stepsByDay.merge(key, steps, Long::plus) }
        sourcesByDay.getOrPut(key) { mutableSetOf() }.add(record.source)
    }

    val detailFormat = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    val weeks = mutableListOf<List<HeatmapDay?>>()
    val monthLabels = mutableListOf<String?>()
    var prevMonth: Month? = null
    var activeDays = 0
    var bestRun = 0
    var run = 0

    var weekStart = gridStart
    while (!weekStart.isAfter(today)) {
        val column = (0 until 7).map { dayIndex ->
            val date = weekStart.plusDays(dayIndex.toLong())
            if (date.isBefore(windowStart) || date.isAfter(today)) {
                null
            } else {
                val key = Dates.key(date)
                val done = doneByDay[key] ?: 0
                if (done > 0) {
                    activeDays++
                    run++
                    if (run > bestRun) bestRun = run
                } else {
                    run = 0
                }
                HeatmapDay(
                    dayKey = key,
                    detailLabel = date.format(detailFormat),
                    done = done,
                    total = totalHabits,
                    level = completionLevel(done, totalHabits),
                    moodName = data.moodHistory[key]?.let { heatmapMoodTitle(it) },
                    isToday = date == today,
                    activitySteps = stepsByDay[key],
                    activitySources = sourcesByDay[key]?.toList() ?: emptyList(),
                )
            }
        }
        weeks.add(column)

        val labelDate = if (weekStart.isBefore(windowStart)) windowStart else weekStart
        monthLabels.add(
            if (labelDate.month != prevMonth) {
                labelDate.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
            } else {
                null
            },
        )
        prevMonth = labelDate.month
        weekStart = weekStart.plusWeeks(1)
    }

    if (monthLabels.size > 2 && monthLabels[0] != null &&
        (monthLabels[1] != null || monthLabels[2] != null)
    ) {
        monthLabels[0] = null
    }

    return YearHeatmapData(
        weeks = weeks,
        monthLabels = monthLabels,
        activeDays = activeDays,
        bestRun = bestRun,
    )
}

private val CellSize = 12.dp
private val CellGap = 3.dp
private val CellStep = CellSize + CellGap
private val MonthSpace = 18.dp

@Composable
fun YearHeatmap(
    data: YearHeatmapData,
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current
    val colorScheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val scrollState = rememberScrollState()

    val weeks = data.weeks
    if (weeks.isEmpty()) return

    var selectedKey by remember(weeks) { mutableStateOf<String?>(null) }

    val accent = moodTheme.accent
    val levelColors = listOf(
        colorScheme.surfaceVariant,
        accent.copy(alpha = 0.30f),
        accent.copy(alpha = 0.52f),
        accent.copy(alpha = 0.74f),
        accent,
    )
    val selectionColor = colorScheme.onSurface
    val labelColor = colorScheme.onSurfaceVariant

    val motion = moodTheme.motion
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(weeks, motion.enabled) {
        if (motion.enabled) {
            reveal.snapTo(0f)
            launch {
                reveal.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = (950 * motion.durationScale).toInt(),
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            reveal.snapTo(1f)
        }
    }

    var autoScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState.maxValue) {
        if (!autoScrolled && scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
            autoScrolled = true
        }
    }

    val monthLayouts = remember(data.monthLabels, textMeasurer) {
        data.monthLabels.map { label ->
            label?.let {
                textMeasurer.measure(
                    text = AnnotatedString(it),
                    style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }

    val selectedDay: HeatmapDay? = remember(selectedKey, weeks) {
        selectedKey?.let { key ->
            weeks.asSequence()
                .flatMap { it.asSequence() }
                .filterNotNull()
                .firstOrNull { it.dayKey == key }
        }
    }

    val gridWidth = CellStep * weeks.size - CellGap
    val gridHeight = MonthSpace + CellStep * 7 - CellGap

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(CellGap)) {
                Spacer(modifier = Modifier.height(MonthSpace - CellGap))
                (0 until 7).forEach { index ->
                    Box(
                        modifier = Modifier.height(CellSize),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (index % 2 == 0) {
                            Text(
                                text = DayOfWeek.of(index + 1)
                                    .getDisplayName(JavaTextStyle.NARROW, Locale.getDefault()),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = labelColor,
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
            ) {
                Canvas(
                    modifier = Modifier
                        .width(gridWidth)
                        .height(gridHeight)
                        .semantics {
                            contentDescription = "Heatmap of habit consistency over the past year"
                        }
                        .pointerInput(weeks) {
                            detectTapGestures { offset ->
                                val step = CellStep.toPx()
                                val top = MonthSpace.toPx()
                                val w = floor(offset.x / step).toInt()
                                val d = floor((offset.y - top) / step).toInt()
                                val day = weeks.getOrNull(w)?.getOrNull(d)
                                if (day != null) {
                                    selectedKey = if (selectedKey == day.dayKey) null else day.dayKey
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                ) {
                    val step = CellStep.toPx()
                    val cellPx = CellSize.toPx()
                    val top = MonthSpace.toPx()
                    val corner = CornerRadius(3.dp.toPx())
                    val ringWidth = 1.5.dp.toPx()
                    val fadeCols = 10f
                    val progress = reveal.value

                    monthLayouts.forEachIndexed { w, layout ->
                        if (layout != null) {
                            drawText(
                                textLayoutResult = layout,
                                color = labelColor,
                                topLeft = Offset(w * step, 0f),
                            )
                        }
                    }

                    weeks.forEachIndexed { w, column ->
                        val colReveal = ((progress * (weeks.size + fadeCols) - w) / fadeCols)
                            .coerceIn(0f, 1f)
                        if (colReveal <= 0f) return@forEachIndexed
                        column.forEachIndexed { d, day ->
                            if (day == null) return@forEachIndexed
                            val topLeft = Offset(w * step, top + d * step)
                            drawRoundRect(
                                color = levelColors[day.level.coerceIn(0, 4)],
                                topLeft = topLeft,
                                size = Size(cellPx, cellPx),
                                cornerRadius = corner,
                                alpha = colReveal,
                            )
                            val isSelected = day.dayKey == selectedKey
                            if (isSelected || day.isToday) {
                                drawRoundRect(
                                    color = if (isSelected) selectionColor else labelColor,
                                    topLeft = Offset(
                                        topLeft.x - ringWidth / 2f,
                                        topLeft.y - ringWidth / 2f,
                                    ),
                                    size = Size(cellPx + ringWidth, cellPx + ringWidth),
                                    cornerRadius = CornerRadius(3.5.dp.toPx()),
                                    style = Stroke(width = ringWidth),
                                    alpha = colReveal,
                                )
                            }
                            // Small dot marker in the corner when activity data
                            // synced that day — distinguishes "habit done manually"
                            // from "habit done + verified via Health Kit/Strava".
                            if (day.activitySteps != null || day.activitySources.isNotEmpty()) {
                                drawCircle(
                                    color = selectionColor,
                                    radius = 1.6.dp.toPx(),
                                    center = Offset(
                                        topLeft.x + cellPx - 2.dp.toPx(),
                                        topLeft.y + 2.dp.toPx(),
                                    ),
                                    alpha = colReveal * 0.85f,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Less",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            levelColors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
            Text(
                text = "More",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }

        val detailText = when {
            selectedDay == null -> "Tap a square to inspect a day. Swipe the grid to travel back in time."
            selectedDay.done == 0 && selectedDay.activitySteps == null ->
                "${selectedDay.detailLabel} · No check-ins"
            else -> buildString {
                append(selectedDay.detailLabel)
                append(" · ")
                append(selectedDay.done)
                append(" of ")
                append(selectedDay.total)
                append(if (selectedDay.total == 1) " habit" else " habits")
                selectedDay.moodName?.let { mood ->
                    append(" · ")
                    append(mood)
                }
                selectedDay.activitySteps?.let { steps ->
                    append(" · ")
                    append(steps)
                    append(" steps")
                }
                if (selectedDay.activitySources.isNotEmpty()) {
                    append(" (")
                    append(selectedDay.activitySources.joinToString(", ") { sourceLabel(it) })
                    append(")")
                }
            }
        }
        Text(
            text = detailText,
            style = MaterialTheme.typography.labelSmall,
            color = if (selectedDay != null) {
                colorScheme.onSurface
            } else {
                labelColor.copy(alpha = 0.8f)
            },
            fontWeight = if (selectedDay != null) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
