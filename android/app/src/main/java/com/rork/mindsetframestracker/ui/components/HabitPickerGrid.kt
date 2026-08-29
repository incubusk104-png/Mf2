package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitCategory
import com.rork.mindsetframestracker.data.HabitIcon
import com.rork.mindsetframestracker.data.HabitIconCatalog
import java.util.UUID

/**
 * The habit picker: a 2-column grid of large card-style icons. Each card has a
 * unique pastel background color derived from the habit's category and its own
 * [HabitIcon.colorHex]. Tapping any icon instantly creates that habit and
 * schedules its alarm at the icon's default time. Tapping the special "To-Do
 * List" icon instead opens a flow to create your own custom item and pick the
 * alarm time you want.
 *
 * A green check badge marks icons whose habit is already added. The icon
 * artwork is rendered large (96 dp) inside a rounded card tile with the
 * category-tinted background — similar to the "Meditate / Running" cards in
 * popular habit trackers.
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
 * A single habit card tile — big rounded card with a pastel background,
 * the habit name in the top-left corner, and the large icon artwork
 * bottom-right, just like the reference design ("Meditate", "Running" cards).
 */
@Composable
private fun HabitCardTile(
    icon: HabitIcon,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tileBg = tileBackground(icon)
    val labelColor = tileLabelColor(icon)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f) // slightly taller than wide for card feel
            .clip(RoundedCornerShape(20.dp))
            .background(tileBg)
            .clickable(onClick = onClick),
    ) {
        // Habit name — top-left
        Text(
            text = icon.label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 14.dp, end = 40.dp),
        )

        // Large icon artwork — bottom-right, overflowing a little for style
        Image(
            painter = painterResource(id = icon.drawableRes),
            contentDescription = icon.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 4.dp),
        )

        // Green check badge — top-right corner
        if (isSelected && !icon.isTodoList) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Added",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── Per-category & per-icon background palette ──────────────────────────────

/**
 * Returns a distinct soft pastel background [Color] for each habit icon.
 * Icons within the same [HabitCategory] share a colour family but each
 * individual icon gets its own unique shade derived from its [HabitIcon.colorHex].
 */
private fun tileBackground(icon: HabitIcon): Color {
    // Use the icon's own accent as a base, then lighten it to a soft pastel
    val base = Color(icon.colorHex)
    return base.copy(alpha = 0.18f).compositeOver(Color.White).copy(alpha = 1f)
}

/**
 * A readable label color that contrasts well with the pastel tile background.
 * Darker variant of the category accent.
 */
private fun tileLabelColor(icon: HabitIcon): Color = when (icon.category) {
    HabitCategory.HEALTH -> Color(0xFF1B5E20)       // deep green
    HabitCategory.MIND -> Color(0xFF4A148C)          // deep purple
    HabitCategory.PRODUCTIVITY -> Color(0xFF37474F)  // blue-grey dark
    HabitCategory.SOCIAL -> Color(0xFFAD1457)        // deep pink
    HabitCategory.FINANCE -> Color(0xFF1B5E20)       // deep green
}

/**
 * Manual alpha compositing: layer [this] colour (with its alpha) over [bg].
 * This replicates what the GPU does when you put a translucent colour on top
 * of an opaque one.
 */
private fun Color.compositeOver(bg: Color): Color {
    val a = this.alpha
    return Color(
        red = this.red * a + bg.red * (1 - a),
        green = this.green * a + bg.green * (1 - a),
        blue = this.blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}
