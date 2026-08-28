package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.completedCountOn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** How many trailing weeks the heatmap displays (columns). */
private const val WEEK_COUNT = 14

/** One cell of the heatmap: a calendar day with its completion state. */
private data class HeatDay(
    val date: LocalDate,
    /** 0f..1f fraction of habits completed that day; -1f for future days. */
    val fraction: Float,
    /** True when every habit was completed on this day. */
    val isPerfect: Boolean,
)

/**
 * Calendar heatmap card for the dashboard. Shows the last [WEEK_COUNT] weeks
 * as a GitHub-style grid (columns = weeks, rows = Mon..Sun). Days where ALL
 * mindset frames (habits) were completed glow at full accent strength with a
 * highlight ring; partial days fade proportionally. Tapping a day shows its
 * date and completion summary.
 */
@Composable
fun CompletionHeatmap(
    data: AppData,
    modifier: Modifier = Modifier,
) {
    val habitCount = data.habits.size
    val today = LocalDate.now()

    // Rebuild the grid only when check-ins or the habit list change.
    val weeks: List<List<HeatDay>> = remember(data.checkIns, data.habits) {
        val end = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val start = end.minusWeeks(WEEK_COUNT.toLong()).plusDays(1)
        (0 until WEEK_COUNT).map { w ->
            (0 until 7).map { d ->
                val date = start.plusDays((w * 7 + d).toLong())
                if (date.isAfter(today)) {
                    HeatDay(date, -1f, false)
                } else {
                    val done = data.completedCountOn(Dates.key(date))
                    val fraction = if (habitCount > 0) done.toFloat() / habitCount else 0f
                    HeatDay(date, fraction, habitCount > 0 && done == habitCount)
                }
            }
        }
    }

    val perfectDays = remember(weeks) { weeks.flatten().count { it.isPerfect } }
    var selected by remember { mutableStateOf<HeatDay?>(null) }

    val primary = MaterialTheme.colorScheme.primary
    val emptyCell = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Your consistency",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                if (perfectDays > 0) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = if (perfectDays == 1) "1 perfect day" else "$perfectDays perfect days",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Weekday labels (Mon / Wed / Fri rows)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("M", "", "W", "", "F", "", "S").forEach { label ->
                        Box(
                            modifier = Modifier.size(width = 14.dp, height = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (label.isNotEmpty()) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    weeks.forEach { week ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            week.forEach { day ->
                                HeatCell(
                                    day = day,
                                    isSelected = selected?.date == day.date,
                                    primary = primary,
                                    emptyColor = emptyCell,
                                    onTap = {
                                        selected = if (selected?.date == day.date) null else day
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Selected-day detail or legend
            val sel = selected
            if (sel != null && sel.fraction >= 0f) {
                val done = (sel.fraction * habitCount).toInt()
                Text(
                    text = sel.date.format(DateTimeFormatter.ofPattern("EEE, MMM d")) + " — " +
                        when {
                            sel.isPerfect -> "all $habitCount frames completed"
                            done == 0 -> "no frames completed"
                            else -> "$done of $habitCount frames completed"
                        },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (sel.isPerfect) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Less",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    listOf(0f, 0.34f, 0.67f, 1f).forEach { f ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (f == 0f) emptyCell
                                    else primary.copy(alpha = 0.25f + 0.75f * f),
                                ),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "All done",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatCell(
    day: HeatDay,
    isSelected: Boolean,
    primary: Color,
    emptyColor: Color,
    onTap: () -> Unit,
) {
    val color = when {
        day.fraction < 0f -> Color.Transparent
        day.isPerfect -> primary
        day.fraction > 0f -> primary.copy(alpha = 0.25f + 0.55f * day.fraction)
        else -> emptyColor
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(shape)
            .background(color)
            .then(
                if (day.fraction >= 0f) Modifier.clickable(onClick = onTap) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (day.isPerfect) {
            // Inner highlight dot marks a "perfect" day where every frame was done.
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)),
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
            )
        }
    }
}
