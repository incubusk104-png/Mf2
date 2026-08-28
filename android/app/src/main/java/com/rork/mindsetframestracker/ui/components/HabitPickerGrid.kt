package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.notifications.HabitAlarmScheduler
import java.util.UUID

/**
 * The habit picker: a 2-column grid of icons. There is no "add habit" dialog —
 * tapping any icon instantly creates that habit and schedules its alarm at the
 * icon's default time. Tapping the special "To-Do List" icon instead opens a
 * flow to create your own custom item and pick its own alarm time.
 *
 * Icons have NO background tile — the artwork sits directly on the page. A green
 * check badge marks icons whose habit is already added.
 */
@Composable
fun HabitPickerGrid(
    selectedIconIds: Set<String>,
    onHabitAdded: (Habit) -> Unit,
    onHabitRemoved: (String) -> Unit,
    onTodoListTapped: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        items(HabitIconCatalog.icons, key = { it.id }) { icon ->
            val isSelected = icon.id in selectedIconIds

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable {
                        when {
                            icon.isTodoList -> onTodoListTapped()
                            isSelected -> onHabitRemoved(icon.id)
                            else -> {
                                val habit = Habit(
                                    id = UUID.randomUUID().toString(),
                                    name = icon.label,
                                    createdAt = System.currentTimeMillis(),
                                    reminderMinutes = icon.defaultReminderMinutes,
                                    iconId = icon.id,
                                )
                                // Click sets the alarm immediately — no separate step.
                                HabitAlarmScheduler.schedule(context, habit)
                                onHabitAdded(habit)
                            }
                        }
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
            ) {
                // Icon artwork with an optional green check badge — no background tile.
                Box(contentAlignment = Alignment.TopEnd) {
                    Image(
                        painter = painterResource(id = icon.drawableRes),
                        contentDescription = icon.label,
                        modifier = Modifier.size(56.dp),
                    )
                    if (isSelected && !icon.isTodoList) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Added",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    icon.label,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
