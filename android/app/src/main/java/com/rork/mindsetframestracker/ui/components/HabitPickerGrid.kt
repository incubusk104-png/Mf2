package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitIcon
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.notifications.HabitAlarmScheduler
import java.util.UUID

@Composable
fun HabitPickerGrid(
    selectedIconIds: Set<String>,
    onHabitAdded: (Habit) -> Unit,
    onHabitRemoved: (String) -> Unit,
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(HabitIconCatalog.icons, key = { it.id }) { icon ->
            val isSelected = icon.id in selectedIconIds

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(icon.colorHex).copy(alpha = if (isSelected) 1f else 0.5f))
                    .clickable {
                        if (isSelected) {
                            onHabitRemoved(icon.id)
                        } else {
                            val habit = Habit(
                                id = UUID.randomUUID().toString(),
                                name = icon.label,
                                createdAt = System.currentTimeMillis(),
                                reminderMinutes = icon.defaultReminderMinutes,
                                iconId = icon.id,
                            )
                            // Click sets the alarm immediately — no separate step
                            HabitAlarmScheduler.schedule(context, habit)
                            onHabitAdded(habit)
                        }
                    }
                    .padding(16.dp),
            ) {
                Image(
                    painter = painterResource(id = icon.drawableRes),
                    contentDescription = icon.label,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(icon.label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    formatReminderTime(icon.defaultReminderMinutes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun formatReminderTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val period = if (h < 12) "AM" else "PM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "Reminder %d:%02d %s".format(h12, m, period)
}
