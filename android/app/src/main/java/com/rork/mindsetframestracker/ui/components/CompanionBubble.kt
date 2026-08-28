package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.ContentPack

import com.rork.mindsetframestracker.data.currentMood
import com.rork.mindsetframestracker.data.dailyCheckInStreak
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.isCheckedToday
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.avatar.CompanionAvatar
import kotlinx.coroutines.delay

/**
 * The companion's in-app notification surface: a speech bubble anchored to
 * the avatar that rotates through live updates — today's status, gentle
 * reminders, the daily motivational quote, streak celebrations, ad-pass
 * countdowns, and new Studio unlock alerts.
 *
 * Auto-rotates every few seconds (paused with Reduce Motion); the bell
 * advances manually and shows a badge dot while a new unlock is waiting.
 * Tapping the card opens the Companion Studio, same as before.
 */
@Composable
fun CompanionNotificationCard(
    data: AppData,
    hasNewUnlocks: Boolean,
    onOpenStudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = appStrings()
    val settings = data.settings
    val doneCount = data.habits.count { data.isCheckedToday(it.id) }
    val leftCount = (data.habits.size - doneCount).coerceAtLeast(0)
    val streak = data.dailyCheckInStreak()
    val mood = data.currentMood()

    // Ordered message feed: unlock alerts first, then status, reminders,
    // streak praise, the daily quote, and temporary-access countdowns.
    val messages: List<String> = remember(
        hasNewUnlocks, doneCount, data.habits.size, streak, mood,
        settings.language,
    ) {
        buildList {
            if (hasNewUnlocks) add(s.bubbleUnlockReady)
            add(
                when {
                    data.habits.isEmpty() -> s.companionEmpty
                    doneCount == 0 -> s.companionNone
                    doneCount < data.habits.size -> s.companionPartial
                    else -> s.companionAllDone
                }
            )
            if (leftCount == 1) {
                add(s.bubbleHabitsLeftOne)
            } else if (leftCount > 1) {
                add(String.format(s.bubbleHabitsLeftMany, leftCount))
            }
            if (streak >= 2) add(String.format(s.bubbleStreak, streak))
            add(ContentPack.quoteFor(mood, settings.hasFeatureAccess(), settings.language))

        }
    }

    var messageIndex by remember(messages.size) { mutableIntStateOf(0) }

    // Gentle auto-rotation; skipped entirely under Reduce Motion.
    LaunchedEffect(messages.size, settings.reducedMotion) {
        if (settings.reducedMotion || messages.size <= 1) return@LaunchedEffect
        while (true) {
            delay(7_000)
            messageIndex = (messageIndex + 1) % messages.size
        }
    }

    Card(
        onClick = onOpenStudio,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        ) {
            CompanionAvatar(
                config = settings.avatar,
                modifier = Modifier.size(46.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 4.dp),
            ) {
                Crossfade(
                    targetState = messages.getOrElse(messageIndex) { messages.firstOrNull().orEmpty() },
                    animationSpec = tween(if (settings.reducedMotion) 0 else 400),
                    label = "companionBubble",
                ) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (messages.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        messages.forEachIndexed { index, _ ->
                            Box(
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(if (index == messageIndex) 5.dp else 4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == messageIndex) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    ),
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(
                    onClick = {
                        if (messages.isNotEmpty()) {
                            messageIndex = (messageIndex + 1) % messages.size
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = s.bubbleUpdates,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (hasNewUnlocks) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = s.studioTitle,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(16.dp),
            )
        }
    }
}
