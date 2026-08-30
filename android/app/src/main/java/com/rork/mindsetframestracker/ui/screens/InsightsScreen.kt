package com.rork.mindsetframestracker.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingFlat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.TrackChanges
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.isCheckedOn
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.EntranceItem
import com.rork.mindsetframestracker.ui.components.MoodPixelsCard
import com.rork.mindsetframestracker.ui.components.TrendChart
import com.rork.mindsetframestracker.ui.components.TrendPoint
import com.rork.mindsetframestracker.ui.components.YearHeatmap
import com.rork.mindsetframestracker.ui.components.YearHeatmapData
import com.rork.mindsetframestracker.ui.components.buildYearHeatmapData
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** [label] resolves lazily against [AppStrings] so range options stay locale-correct
 * without any string-matching or prefix-stripping tricks. */
private data class RangeOption(val days: Int, val label: (AppStrings) -> String)

private val rangeOptions = listOf(
    RangeOption(7) { it.insightsWeek },
    RangeOption(14) { it.insights2Weeks },
    RangeOption(30) { it.insightsMonth },
)

/** Average completion on days a given mood was logged, within the range. */
private data class MoodStat(val mode: MoodMode, val daysLogged: Int, val rate: Int)

private fun moodIcon(mode: MoodMode): ImageVector = when (mode) {
    MoodMode.CALM -> Icons.Outlined.Spa
    MoodMode.FOCUSED -> Icons.Outlined.TrackChanges
    MoodMode.MOTIVATED -> Icons.Outlined.Bolt
    MoodMode.OVERWHELMED -> Icons.Outlined.Cloud
}

private fun moodTitle(mode: MoodMode, s: AppStrings): String = when (mode) {
    MoodMode.CALM -> s.moodCalm
    MoodMode.FOCUSED -> s.moodFocused
    MoodMode.MOTIVATED -> s.moodMotivated
    MoodMode.OVERWHELMED -> s.moodOverwhelmed
}

/** Mood mapped to chart height: Overwhelmed low → Motivated high. */
private fun moodScore(mode: MoodMode): Float = when (mode) {
    MoodMode.OVERWHELMED -> 0f
    MoodMode.CALM -> 1f / 3f
    MoodMode.FOCUSED -> 2f / 3f
    MoodMode.MOTIVATED -> 1f
}

/**
 * One pass over synced [ActivityRecord]s (Fitbit / Polar / Health Connect / Strava), indexed
 * by day for O(1) lookups everywhere the Insights screen needs them:
 * which habits a day's sync satisfies, plus the raw steps/sources for the
 * chart tooltip. Built once and shared by every stat below instead of
 * re-scanning the record list per calculation.
 */
private data class ActivityDayIndex(
    val habitIdsByDay: Map<String, Set<String>>,
    val stepsByDay: Map<String, Long>,
    val sourcesByDay: Map<String, List<String>>,
) {
    companion object {
        val EMPTY = ActivityDayIndex(emptyMap(), emptyMap(), emptyMap())
    }
}

private fun buildActivityDayIndex(records: List<ActivityRecord>): ActivityDayIndex {
    if (records.isEmpty()) return ActivityDayIndex.EMPTY
    val habitIdsByDay = HashMap<String, MutableSet<String>>()
    val stepsByDay = HashMap<String, Long>()
    val sourcesByDay = HashMap<String, MutableSet<String>>()
    records.forEach { record ->
        val key = Dates.key(
            Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
        habitIdsByDay.getOrPut(key) { mutableSetOf() }.add(record.habitId)
        record.steps?.let { steps -> stepsByDay.merge(key, steps, Long::plus) }
        sourcesByDay.getOrPut(key) { mutableSetOf() }.add(record.source)
    }
    return ActivityDayIndex(
        habitIdsByDay = habitIdsByDay,
        stepsByDay = stepsByDay,
        sourcesByDay = sourcesByDay.mapValues { it.value.toList() },
    )
}

/**
 * Habits done on [dayKey]: manually checked in, OR satisfied by a synced
 * Fitbit / Polar / Health Connect / Strava activity for that habit that day — a logged run
 * shouldn't need a duplicate manual tap to count toward completion.
 * Kept local to this screen (rather than changing the shared
 * `AppData.completedCountOn`) so streaks and badge tiers elsewhere keep
 * their existing manual-check-in-only semantics; this is purely an
 * Insights-tab reporting decision.
 */
private fun AppData.completedCountWithActivity(dayKey: String, activityIndex: ActivityDayIndex): Int {
    val syncedHabitIds = activityIndex.habitIdsByDay[dayKey] ?: emptySet()
    return habits.count { isCheckedOn(it.id, dayKey) || it.id in syncedHabitIds }
}

private fun buildTrendPoints(
    data: AppData,
    rangeDays: Int,
    s: AppStrings,
    activityIndex: ActivityDayIndex,
): List<TrendPoint> {
    val days = Dates.lastDays(rangeDays)
    val total = data.habits.size
    val labelStep = when {
        rangeDays <= 7 -> 1
        rangeDays <= 14 -> 2
        else -> 5
    }
    val detailFormat = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    return days.mapIndexed { index, day ->
        val key = Dates.key(day)
        val done = data.completedCountWithActivity(key, activityIndex)
        val mood = data.moodHistory[key]
        val isLast = index == days.lastIndex
        TrendPoint(
            dayKey = key,
            axisLabel = if (rangeDays <= 7) {
                day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
            } else {
                day.dayOfMonth.toString()
            },
            detailLabel = day.format(detailFormat),
            completion = if (total > 0) done.toFloat() / total else 0f,
            moodLevel = mood?.let { moodScore(it) },
            moodName = mood?.let { moodTitle(it, s) },
            isToday = isLast,
            showAxisLabel = isLast || index % labelStep == 0,
            activitySteps = activityIndex.stepsByDay[key],
            activitySources = activityIndex.sourcesByDay[key] ?: emptyList(),
        )
    }
}

/**
 * Insights tab: smooth line chart of habit completion vs mood level over a
 * selectable range, momentum stats, and a per-mood consistency breakdown.
 */
@Composable
fun InsightsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val s = appStrings()
    var rangeDays by rememberSaveable { mutableIntStateOf(7) }

    // Synced Fitbit / Polar / Health Connect / Strava activity, indexed once per data change
    // and shared by the trend chart, completion rate, momentum, and the
    // per-mood breakdown below.
    val activityIndex: ActivityDayIndex = remember(data.activityRecords) {
        buildActivityDayIndex(data.activityRecords)
    }

    val points: List<TrendPoint> = remember(data.habits, data.checkIns, data.moodHistory, activityIndex, rangeDays) {
        buildTrendPoints(data, rangeDays, s, activityIndex)
    }

    val yearHeatmap: YearHeatmapData = remember(data.habits, data.checkIns, data.moodHistory, data.activityRecords) {
        buildYearHeatmapData(data)
    }

    val habitCount = data.habits.size
    val rangeDayList = remember(rangeDays) { Dates.lastDays(rangeDays) }
    val totalDone = rangeDayList.sumOf { data.completedCountWithActivity(Dates.key(it), activityIndex) }
    val avgRate = if (habitCount > 0) totalDone * 100 / (habitCount * rangeDays) else 0

    // Momentum: this 7-day window vs the 7 days before it (percentage points).
    val thisWeekDone = Dates.lastDays(7).sumOf { data.completedCountWithActivity(Dates.key(it), activityIndex) }
    val prevWeekDone = (13 downTo 7)
        .map { LocalDate.now().minusDays(it.toLong()) }
        .sumOf { data.completedCountWithActivity(Dates.key(it), activityIndex) }
    val weekDelta: Int? = if (habitCount == 0 || prevWeekDone == 0) null
    else (thisWeekDone * 100 / (habitCount * 7)) - (prevWeekDone * 100 / (habitCount * 7))

    val moodStats: List<MoodStat> = remember(data.checkIns, data.moodHistory, activityIndex, rangeDays, habitCount) {
        if (habitCount == 0) emptyList()
        else MoodMode.entries.mapNotNull { mode ->
            val moodDays = rangeDayList.filter { data.moodHistory[Dates.key(it)] == mode }
            if (moodDays.isEmpty()) null
            else MoodStat(
                mode = mode,
                daysLogged = moodDays.size,
                rate = moodDays.sumOf { data.completedCountWithActivity(Dates.key(it), activityIndex) } * 100 /
                    (habitCount * moodDays.size),
            )
        }.sortedByDescending { it.rate }
    }

    // Reuse the selected range's own segmented-control label as the stat
    // caption — locale-correct by construction, replaces the old
    // string-prefix-stripping hack that only worked for English/Tagalog.
    val selectedRangeLabel = rangeOptions.first { it.days == rangeDays }.label(s)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntranceItem(index = 0) {
            Column {
                Text(
                    text = s.insightsTitle,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = s.insightsSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        EntranceItem(index = 1) {
            RangeSelector(
                selectedDays = rangeDays,
                onSelect = { days ->
                    if (days != rangeDays) {
                        rangeDays = days
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
            )
        }

        if (habitCount == 0) {
            EntranceItem(index = 2) { EmptyInsightsCard() }
        } else {
            EntranceItem(index = 2) {
                TrendCard(points = points, rangeDays = rangeDays)
            }

            EntranceItem(index = 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        icon = Icons.Outlined.TaskAlt,
                        value = "$avgRate%",
                        label = s.insightsAvgCompletion,
                        caption = selectedRangeLabel,
                        modifier = Modifier.weight(1f),
                    )
                    val deltaText = when {
                        weekDelta == null -> "—"
                        weekDelta > 0 -> "+$weekDelta pts"
                        weekDelta < 0 -> "$weekDelta pts"
                        else -> "0 pts"
                    }
                    val deltaIcon = when {
                        weekDelta == null || weekDelta == 0 -> Icons.AutoMirrored.Outlined.TrendingFlat
                        weekDelta > 0 -> Icons.AutoMirrored.Outlined.TrendingUp
                        else -> Icons.AutoMirrored.Outlined.TrendingDown
                    }
                    StatCard(
                        icon = deltaIcon,
                        value = deltaText,
                        label = s.insightsMomentum,
                        caption = s.insightsVsPrevWeek,
                        valueColor = if (weekDelta != null && weekDelta > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            EntranceItem(index = 4) {
                YearHeatmapCard(heatmap = yearHeatmap)
            }

            EntranceItem(index = 5) {
                MoodPixelsCard(data = data)
            }

            EntranceItem(index = 6) {
                MoodConsistencyCard(moodStats = moodStats)
            }
        }
    }
}

/**
 * Shared card chrome for every Insights section — single source of truth
 * for shape/color so a future style change is a one-line edit, not five.
 */
@Composable
private fun InsightsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Pill segmented control for the chart range. */
@Composable
private fun RangeSelector(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current
    val s = appStrings()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        rangeOptions.forEach { option ->
            val selected = option.days == selectedDays
            val background by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                animationSpec = moodTheme.motion.tween(240),
                label = "rangeBackground",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = moodTheme.motion.tween(240),
                label = "rangeText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(background)
                    .clickable { onSelect(option.days) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label(s),
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Card wrapping the trend chart with legend and reading hint. */
@Composable
private fun TrendCard(
    points: List<TrendPoint>,
    rangeDays: Int,
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current
    val s = appStrings()
    InsightsCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.insightsCompletionTrend,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = s.insightsLastDays.format(rangeDays),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(moodTheme.gradient)),
                )
                Text(
                    text = s.insightsHabitsDone,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                Text(
                    text = s.insightsMoodLevel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TrendChart(points = points)
        Text(
            text = s.insightsMoodChartHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/**
 * Contribution-style heatmap card: a full year of daily check-in intensity,
 * with active-day and longest-run stats in the header.
 */
@Composable
private fun YearHeatmapCard(
    heatmap: YearHeatmapData,
    modifier: Modifier = Modifier,
) {
    val s = appStrings()
    InsightsCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.insightsYearInFrames,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = s.insightsPast12Months,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (heatmap.activeDays == 0) {
                s.insightsNoCheckIns
            } else {
                val dayWord = if (heatmap.activeDays == 1) s.insightsDay else s.insightsDays
                val runWord = if (heatmap.bestRun == 1) s.insightsDay else s.insightsDays
                "${s.insightsActiveDays.format(heatmap.activeDays, dayWord)} · ${s.insightsLongestRun.format(heatmap.bestRun, runWord)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        YearHeatmap(data = heatmap)
    }
}

/** Small metric card: icon, hero value (serif), label, caption. */
@Composable
private fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .size(20.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = valueColor,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Per-mood average completion with animated bars, strongest frame first. */
@Composable
private fun MoodConsistencyCard(
    moodStats: List<MoodStat>,
    modifier: Modifier = Modifier,
) {
    val s = appStrings()
    InsightsCard(modifier = modifier) {
        Text(
            text = s.insightsMoodConsistency,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (moodStats.isEmpty()) {
            Text(
                text = s.insightsPickMood,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (moodStats.first().rate > 0) {
                Text(
                    text = s.insightsShowUpStrongest.format(moodTitle(moodStats.first().mode, s)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            moodStats.forEachIndexed { index, stat ->
                MoodStatRow(stat = stat, index = index)
            }
        }
    }
}

@Composable
private fun MoodStatRow(
    stat: MoodStat,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current
    val s = appStrings()
    val fill = remember(stat.mode, stat.rate) { Animatable(0f) }
    LaunchedEffect(stat.mode, stat.rate) {
        if (moodTheme.motion.enabled) {
            fill.animateTo(
                targetValue = stat.rate / 100f,
                animationSpec = tween(
                    durationMillis = 650,
                    delayMillis = index * 80,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            fill.snapTo(stat.rate / 100f)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = moodIcon(stat.mode),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = moodTitle(stat.mode, s),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${stat.rate}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (stat.daysLogged == 1) s.insightsDayLogged.format(stat.daysLogged) else s.insightsDaysLogged.format(stat.daysLogged),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fill.value)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(moodTheme.gradient)),
                )
            }
        }
    }
}

/** Shown when there are no habits yet — nothing to chart. */
@Composable
private fun EmptyInsightsCard(modifier: Modifier = Modifier) {
    val s = appStrings()
    InsightsCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Insights,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = s.insightsNoTrendsYet,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = s.insightsNoTrendsBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
