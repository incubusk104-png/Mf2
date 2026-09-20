package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.mindsetframestracker.data.HabitIcon
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.ui.theme.Mf2Palette

/**
 * Habit picker grid — clean 2-column layout inspired by premium habit apps.
 *
 * Design language (modelled after the reference screenshot):
 *  - One of six Mf2 reference card fills per habit (not a tint per icon)
 *  - Large centred artwork that dominates the card
 *  - Compact habit name at top-left, small alarm time underneath
 *  - Generous 16 dp rounded corners, 14 dp gaps
 *  - Green check badge when a habit is already added
 */
@Composable
fun HabitPickerGrid(
    selectedIconIds: Set<String>,
    onIconTapped: (HabitIcon) -> Unit,
    onHabitRemoved: (String) -> Unit,
    onTodoListTapped: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable () -> Unit)? = null,
    /** Actual reminder minutes for an already-added habit, keyed by icon id.
     *  null (or a missing entry) means that habit has no alarm configured —
     *  the card shows "Set up alarm" instead of a time in that case. */
    reminderMinutesByIconId: Map<String, Int?> = emptyMap(),
    /** Called when the user taps "Set up alarm" on a habit that has none. */
    onSetupAlarmTapped: (HabitIcon) -> Unit = {},
) {
    val isDark = isSystemInDarkTheme()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        items(HabitIconCatalog.icons, key = { it.id }) { icon ->
            val isSelected = icon.id in selectedIconIds
            // Only meaningful once the habit is actually added — for
            // not-yet-added catalog tiles this key is simply absent, and the
            // tile falls back to previewing the catalog's default time.
            val hasNoAlarmSet = isSelected && reminderMinutesByIconId[icon.id] == null

            HabitCardTile(
                icon = icon,
                isSelected = isSelected,
                isDark = isDark,
                hasNoAlarmSet = hasNoAlarmSet,
                actualReminderMinutes = reminderMinutesByIconId[icon.id],
                onClick = {
                    when {
                        icon.isTodoList -> onTodoListTapped()
                        // Tapping an already-added habit now opens the alarm
                        // editor (re-edit / re-arm its reminder) instead of
                        // deleting it — deletion moved to a dedicated tap on
                        // the checkmark badge below, so it's a deliberate
                        // action instead of the card's default behaviour.
                        isSelected -> onSetupAlarmTapped(icon)
                        else -> onIconTapped(icon)
                    }
                },
                onSetupAlarmClick = { onSetupAlarmTapped(icon) },
                onRemoveClick = { onHabitRemoved(icon.id) },
            )
        }
    }
}

/**
 * A single habit card — clean, premium look.
 *
 * ┌──────────────────────────────┐
 * │ Habit Name            [✓]   │
 * │ ⏰ 7:00 AM                   │
 * │                              │
 * │       ┌──────────────┐       │
 * │       │  centred      │       │
 * │       │  artwork      │       │
 * │       │  (fills card) │       │
 * │       └──────────────┘       │
 * └──────────────────────────────┘
 */
@Composable
private fun HabitCardTile(
    icon: HabitIcon,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    hasNoAlarmSet: Boolean = false,
    actualReminderMinutes: Int? = null,
    onSetupAlarmClick: () -> Unit = {},
    onRemoveClick: () -> Unit = {},
) {
    val tileBg = Mf2Palette.habitCardBackground(icon.colorHex, isDark)
    val textColor = Mf2Palette.habitCardTitleColor(isDark)
    val subtitleColor = Mf2Palette.habitCardMutedColor(isDark)
    // "Set up alarm" needs to stand out as something needing attention,
    // not just another muted subtitle line.
    val warningColor = Mf2Palette.AttentionStrong

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(16.dp))
            .background(tileBg)
            .clickable(onClick = onClick),
    ) {
        // ── Top-left: name + alarm ──
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 12.dp, end = 40.dp),
        ) {
            Text(
                text = icon.label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            if (hasNoAlarmSet) {
                // Independently clickable — tapping this specific row opens
                // the alarm picker directly, without triggering the card's
                // own onClick (which would remove the habit if selected).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onSetupAlarmClick),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Alarm,
                        contentDescription = null,
                        tint = warningColor,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "Set up alarm",
                        fontSize = 11.sp,
                        color = warningColor,
                        fontWeight = FontWeight.Bold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )
                }
            } else if (isSelected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Alarm,
                        contentDescription = null,
                        tint = subtitleColor,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        // Only reachable when a habit actually exists with a
                        // real, non-null reminder — never a guessed default.
                        text = formatTime(actualReminderMinutes ?: icon.defaultReminderMinutes),
                        fontSize = 11.sp,
                        color = subtitleColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                // Not added yet — show nothing suggesting an alarm already
                // exists. Previously this previewed the catalog's default
                // time here, which looked like an alarm was already set
                // before the user had chosen anything at all.
                Text(
                    text = "Tap to add",
                    fontSize = 11.sp,
                    color = subtitleColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // ── Centre-bottom: artwork (hero of the card) ──
        Image(
            painter = painterResource(id = icon.drawableRes),
            contentDescription = icon.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
        )

        // ── Top-right: green check badge — tap to remove this habit ──
        if (isSelected && !icon.isTodoList) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Mf2Palette.SuccessFill)
                    // Independently clickable — consumes the tap so it never
                    // falls through to the card's own onClick (which now
                    // opens the alarm editor for selected habits).
                    .clickable(onClick = onRemoveClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Added — tap to remove",
                    tint = Mf2Palette.OnBrandInk,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ── Alarm time formatting ────────────────────────────────────────────────────

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

// ── Per-icon colour palette ──────────────────────────────────────────────────
//
// Card colour comes from `Mf2Palette`: `habitColorForSeed(icon.colorHex)` buckets
// the icon's seed colour into one of the six reference habit hues (OLIVE, SLATE,
// FOREST, INDIGO, TEAL, CHOCOLATE) and `habitCardBackground` resolves that bucket
// for the current theme. The six dark fills are the exact samples from the
// reference screenshot; the light fills are the same seeds over cream.
//
// This replaced a per-icon tint that alpha-blended 60+ distinct seed colours over
// the surface, which produced muddy, hard-to-tell-apart cards and text that could
// not hold a fixed contrast ratio. A fixed six-colour set lets the card text use
// flat tokens (ivory / ivory-muted) with measured contrast instead of a
// per-icon lighten/darken blend.
//
// The token definitions live in `ui/theme/Palette.kt` — the only file in the app
// allowed to contain colour literals.
