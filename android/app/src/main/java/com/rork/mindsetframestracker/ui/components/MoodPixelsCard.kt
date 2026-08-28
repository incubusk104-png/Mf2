package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.ui.appStrings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Print-friendly pixel color per mood (matches the share-card palette). */
private fun moodPixelColor(mode: MoodMode): Color = when (mode) {
    MoodMode.CALM -> Color(0xFF5D8A66)
    MoodMode.FOCUSED -> Color(0xFF33655A)
    MoodMode.MOTIVATED -> Color(0xFFC2643A)
    MoodMode.OVERWHELMED -> Color(0xFF8A8273)
}

/**
 * "Month in Pixels" — a 7-column calendar where every day is a small square
 * tinted by that day's mood check-in. Swipe through past months with the
 * chevrons; days without a logged mood stay neutral, future days stay faint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodPixelsCard(
    data: AppData,
    modifier: Modifier = Modifier,
) {
    val s = appStrings()
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    val month = remember(monthOffset) { YearMonth.now().plusMonths(monthOffset.toLong()) }
    val today = LocalDate.now()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.pixelsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { monthOffset-- }) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { monthOffset++ }, enabled = monthOffset < 0) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = if (monthOffset < 0) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            Text(
                text = s.pixelsSub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                DayOfWeek.entries.forEach { day ->
                    Text(
                        text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
            val cells: List<LocalDate?> =
                List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    week.forEach { day ->
                        if (day == null) {
                            Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                        } else {
                            val mood = data.moodHistory[Dates.key(day)]
                            val isFuture = day.isAfter(today)
                            val bg = when {
                                mood != null -> moodPixelColor(mood)
                                isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(bg, RoundedCornerShape(7.dp))
                                    .then(
                                        if (day == today) {
                                            Modifier.border(
                                                width = 1.5.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(7.dp),
                                            )
                                        } else Modifier,
                                    ),
                            ) {
                                Text(
                                    text = day.dayOfMonth.toString(),
                                    fontSize = 9.sp,
                                    color = if (mood != null) Color(0xFFFFFCF5).copy(alpha = 0.9f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (isFuture) 0.4f else 0.8f,
                                    ),
                                )
                            }
                        }
                    }
                    repeat(7 - week.size) {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                MoodMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(moodPixelColor(mode), CircleShape),
                        )
                        Text(
                            text = when (mode) {
                                MoodMode.CALM -> s.moodCalm
                                MoodMode.FOCUSED -> s.moodFocused
                                MoodMode.MOTIVATED -> s.moodMotivated
                                MoodMode.OVERWHELMED -> s.moodOverwhelmed
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                CircleShape,
                            ),
                    )
                    Text(
                        text = s.pixelsNone,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}
