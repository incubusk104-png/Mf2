package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitCategory
import com.rork.mindsetframestracker.data.HabitIcon
import com.rork.mindsetframestracker.data.HabitIconCatalog
import java.util.UUID

/**
 * Habit picker grid — 2-column layout of large card tiles.
 *
 * Each card features:
 *  - A **120 dp** icon (much bigger than the previous 56/96 dp)
 *  - Unique pastel background per category (dark-mode aware)
 *  - Habit name top-left, alarm time below name, icon artwork bottom-right
 *  - Green check badge when the habit is already added
 *
 * Tapping any icon instantly creates the habit + schedules its alarm.
 * Tapping "To-Do List" opens the custom-habit creation dialog.
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
    val isDark = isSystemInDarkTheme()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        items(HabitIconCatalog.icons, key = { it.id }) { icon ->
            val isSelected = icon.id in selectedIconIds

            HabitCardTile(
                icon = icon,
                isSelected = isSelected,
                isDark = isDark,
                onClick = {
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
                            onHabitAdded(habit)
                        }
                    }
                },
            )
        }
    }
}

/**
 * A single big habit card. Layout:
 * ┌─────────────────────────────┐
 * │ Habit Name           [check]│
 * │ ⏰ 7:00 AM                  │
 * │                             │
 * │                    ┌───────┐│
 * │                    │ 120dp ││
 * │                    │ icon  ││
 * │                    └───────┘│
 * └─────────────────────────────┘
 */
@Composable
private fun HabitCardTile(
    icon: HabitIcon,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val tileBg = tileBackground(icon, isDark)
    val labelColor = tileLabelColor(icon, isDark)
    val subtitleColor = labelColor.copy(alpha = 0.7f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f) // taller card to fit the bigger icon
            .clip(RoundedCornerShape(22.dp))
            .background(tileBg)
            .clickable(onClick = onClick),
    ) {
        // ── Top-left: name + alarm time ──
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 14.dp, end = 44.dp),
        ) {
            Text(
                text = icon.label,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Alarm time badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Alarm,
                    contentDescription = null,
                    tint = subtitleColor,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = formatTime(icon.defaultReminderMinutes),
                    fontSize = 12.sp,
                    color = subtitleColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // ── Bottom-right: BIG icon artwork ──
        Image(
            painter = painterResource(id = icon.drawableRes),
            contentDescription = icon.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(120.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 6.dp, y = 6.dp),
        )

        // ── Top-right: green check badge ──
        if (isSelected && !icon.isTodoList) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Added",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

// ── Alarm time formatting ───────────────────────────────────────────────────

/** Converts minutes-from-midnight to "7:00 AM" / "9:30 PM" format. */
private fun formatTime(minutesFromMidnight: Int): String {
    val h = minutesFromMidnight / 60
    val m = minutesFromMidnight % 60
    val period = if (h >= 12) "PM" else "AM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$h12:${m.toString().padStart(2, '0')} $period"
}

// ── Per-category background palette (light + dark mode aware) ───────────────

/**
 * Returns a distinct pastel background for each icon, respecting dark mode.
 * Light mode: soft tinted white. Dark mode: deep tinted dark.
 */
private fun tileBackground(icon: HabitIcon, isDark: Boolean): Color {
    val base = Color(icon.colorHex)
    return if (isDark) {
        // Dark mode: desaturated dark version of the accent
        base.copy(alpha = 0.20f).compositeOver(Color(0xFF1C1C1E))
    } else {
        // Light mode: soft pastel on white
        base.copy(alpha = 0.18f).compositeOver(Color.White)
    }
}

/**
 * Label color that contrasts with the pastel background, mode-aware.
 */
private fun tileLabelColor(icon: HabitIcon, isDark: Boolean): Color {
    if (isDark) {
        return when (icon.category) {
            HabitCategory.HEALTH -> Color(0xFFA5D6A7)
            HabitCategory.MIND -> Color(0xFFCE93D8)
            HabitCategory.PRODUCTIVITY -> Color(0xFF90A4AE)
            HabitCategory.SOCIAL -> Color(0xFFF48FB1)
            HabitCategory.FINANCE -> Color(0xFFA5D6A7)
        }
    }
    return when (icon.category) {
        HabitCategory.HEALTH -> Color(0xFF1B5E20)
        HabitCategory.MIND -> Color(0xFF4A148C)
        HabitCategory.PRODUCTIVITY -> Color(0xFF37474F)
        HabitCategory.SOCIAL -> Color(0xFFAD1457)
        HabitCategory.FINANCE -> Color(0xFF1B5E20)
    }
}

/** Alpha-composites [this] colour over [bg]. */
private fun Color.compositeOver(bg: Color): Color {
    val a = this.alpha
    return Color(
        red = this.red * a + bg.red * (1 - a),
        green = this.green * a + bg.green * (1 - a),
        blue = this.blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}
