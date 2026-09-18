package com.rork.mindsetframestracker.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.HabitWeekConsistency
import com.rork.mindsetframestracker.data.formatCount
import com.rork.mindsetframestracker.data.formatDistance
import com.rork.mindsetframestracker.data.formatMinutes
import com.rork.mindsetframestracker.data.lastSevenDayKeys
import com.rork.mindsetframestracker.data.weeklyConsistency
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.ui.appStrings

/**
 * The weekly consistency view: every habit's seven days, and the activity that
 * fed them.
 *
 * ## Why this is a card and not a chart
 *
 * A percentage per habit answers "am I consistent?" but not "which day did I
 * miss?", and the day-level detail is what makes the number actionable. So each
 * habit gets a seven-dot row — filled for done, outlined for not — with the
 * count beside it. Habits that are **not** complete sort first, because the one
 * that is slipping is the one worth showing.
 *
 * ## Sourced days are marked
 *
 * A day completed from Strava / Health Connect carries an accent ring rather
 * than reading identically to a manual tap. That is the honest distinction the
 * request asks for — "import that data into the insights" only means something
 * if the user can see which days came from the tracker.
 */
@Composable
internal fun WeeklyConsistencyCard(
    data: AppData,
    modifier: Modifier = Modifier,
) {
    val s = appStrings()
    val week = remember(data) { data.weeklyConsistency(lastSevenDayKeys()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp),
    ) {
        Text(
            text = s.insightsWeeklyConsistency,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = s.insightsWeeklyConsistencyCaption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))

        if (week.habits.isEmpty()) {
            Text(
                text = s.insightsWeeklyConsistencyEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                week.habits.sortedBy { it.ratio }.forEach { row ->
                    ConsistencyRow(row = row)
                }
            }
        }

        // Only rendered when a source actually produced something, so an
        // un-connected user sees no row of zeroes that would read as "you did
        // none of this" rather than "this is not connected".
        if (!week.activity.isEmpty) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = s.insightsWeeklyActivity,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ActivityFact(s.insightsSteps, formatCount(week.activity.steps))
                ActivityFact(s.insightsMinutes, formatMinutes(week.activity.durationMinutes))
                ActivityFact(s.insightsDistance, formatDistance(week.activity.distanceMeters))
            }
        }
    }
}

/** One habit's row: name, seven day dots, and the "5 of 7 days" count. */
@Composable
private fun ConsistencyRow(row: HabitWeekConsistency) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.habitName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (row.isPerfect) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.dayKeys.forEach { dayKey ->
                val done = dayKey in row.doneKeys
                val sourced = dayKey in row.sourcedKeys
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                // Sourced and done: the tracker's day, in the
                                // accent colour so it reads as "imported".
                                done && sourced -> MaterialTheme.colorScheme.tertiary
                                done -> MaterialTheme.colorScheme.primary
                                // Not done, but the tracker still saw activity:
                                // outlined distinctly rather than left blank, so
                                // "you moved and did not check in" is visible.
                                sourced -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                            },
                        ),
                )
            }
        }
    }
}

/** A label/value pair in the sourced-activity strip. */
@Composable
private fun ActivityFact(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

