package com.rork.mindsetframestracker.ui.components

import android.text.format.DateUtils
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.RuleBasedInsight

/**
 * Simple single-insight sheet — used for quick per-record popups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityInsightSheet(
    title: String,
    insightText: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(insightText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Activity Report Sheet — full summary of synced fitness data from
 * Strava, Fitbit, Polar, and Google Health Connect.
 *
 * Shows:
 *  - Summary stats (total activities, distance, duration, calories)
 *  - Per-source breakdown
 *  - Insight text from the rule-based engine
 *  - Recent activity list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityReportSheet(
    records: List<ActivityRecord>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            // ── Header ──
            Text(
                text = "Activity Report",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Summary of your synced fitness data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            if (records.isEmpty()) {
                EmptyReportCard()
            } else {
                // ── Summary stats row ──
                val totalActivities = records.size
                val totalDurationMin = records.mapNotNull { it.durationMinutes }.sum()
                val totalDistanceKm = records.mapNotNull { it.distanceMeters }.sum() / 1000.0
                val totalCalories = records.mapNotNull { it.calories?.takeIf { c -> c > 0 } }.sum()
                val totalSteps = records.mapNotNull { it.steps }.sum()

                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatBubble(
                        icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                        value = "$totalActivities",
                        label = "Activities",
                        modifier = Modifier.weight(1f),
                    )
                    if (totalDurationMin > 0) {
                        StatBubble(
                            icon = Icons.Outlined.Schedule,
                            value = formatDuration(totalDurationMin),
                            label = "Duration",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (totalDistanceKm > 0.1) {
                        StatBubble(
                            icon = Icons.Outlined.Route,
                            value = "%.1f km".format(totalDistanceKm),
                            label = "Distance",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (totalCalories > 0) {
                        StatBubble(
                            icon = Icons.Outlined.LocalFireDepartment,
                            value = "$totalCalories",
                            label = "Calories",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (totalSteps > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatBubble(
                            icon = Icons.Outlined.Speed,
                            value = "%,d".format(totalSteps),
                            label = "Total Steps",
                            modifier = Modifier.width(140.dp),
                        )
                    }
                }

                // ── Source breakdown ──
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "By source",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val bySource = records.groupBy { it.source }
                bySource.forEach { (source, sourceRecords) ->
                    SourceBreakdownRow(
                        source = source,
                        count = sourceRecords.size,
                        totalMinutes = sourceRecords.mapNotNull { it.durationMinutes }.sum(),
                        totalDistanceKm = sourceRecords.mapNotNull { it.distanceMeters }.sum() / 1000.0,
                    )
                }

                // ── Insights ──
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Insights",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val avgHr = records.mapNotNull { it.heartRateAvg }.takeIf { it.isNotEmpty() }
                    ?.average()?.toInt()
                if (avgHr != null) {
                    InsightChip(text = RuleBasedInsight.forHeartRate(avgHr))
                }
                if (totalSteps > 0) {
                    InsightChip(text = RuleBasedInsight.forSteps(totalSteps))
                }
                if (totalDurationMin > 0) {
                    val avgPerActivity = totalDurationMin / totalActivities
                    InsightChip(
                        text = "Average activity duration: $avgPerActivity min across $totalActivities sessions.",
                    )
                }

                // ── Recent activities ──
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Recent activities",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                records.sortedByDescending { it.timestamp }.take(10).forEach { record ->
                    RecentActivityRow(record)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyReportCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FitnessCenter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = "No activity data yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "Connect Fitbit, Polar, Health Connect, or Strava in Settings to start syncing your workouts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun StatBubble(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceBreakdownRow(
    source: String,
    count: Int,
    totalMinutes: Int,
    totalDistanceKm: Double,
) {
    val displayName = when (source) {
        "strava" -> "Strava"
        "fitbit" -> "Fitbit"
        "polar" -> "Polar"
        "health_connect" -> "Health Connect"
        else -> source.replaceFirstChar { it.uppercase() }
    }
    val displayIcon = when (source) {
        "strava" -> Icons.AutoMirrored.Outlined.DirectionsRun
        "fitbit" -> Icons.Outlined.Watch
        "polar" -> Icons.Outlined.FavoriteBorder
        else -> Icons.Outlined.MonitorHeart
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = displayIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )
        Text(
            text = buildString {
                append("$count activities")
                if (totalMinutes > 0) append(" | ${formatDuration(totalMinutes)}")
                if (totalDistanceKm > 0.1) append(" | ${"%.1f".format(totalDistanceKm)} km")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InsightChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RecentActivityRow(record: ActivityRecord) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = record.activityType.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 10.dp)) {
            Text(
                text = record.activityType.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    append(record.source.replace("_", " ").replaceFirstChar { it.uppercase() })
                    if (record.timestamp > 0) {
                        append(" | ")
                        append(
                            DateUtils.getRelativeTimeSpanString(record.timestamp).toString(),
                        )
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (record.durationMinutes != null && record.durationMinutes > 0) {
                Text(
                    text = "${record.durationMinutes} min",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (record.steps != null && record.steps > 0) {
                Text(
                    text = "%,d steps".format(record.steps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (record.distanceMeters != null && record.distanceMeters > 100) {
                Text(
                    text = "${"%.1f".format(record.distanceMeters / 1000.0)} km",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}
