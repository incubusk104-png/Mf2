package com.rork.mindsetframestracker.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.completedCountOn
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.isCheckedOn
import com.rork.mindsetframestracker.data.streakFor
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.EntranceItem
import com.rork.mindsetframestracker.ui.components.PremiumSheet
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import com.rork.mindsetframestracker.util.ProgressShareImage
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun moodIcon(mode: MoodMode): ImageVector = when (mode) {
    MoodMode.CALM -> Icons.Outlined.Spa
    MoodMode.FOCUSED -> Icons.Outlined.TrackChanges
    MoodMode.MOTIVATED -> Icons.Outlined.Bolt
    MoodMode.OVERWHELMED -> Icons.Outlined.Cloud
}

/** Weekly progress: per-day bars, per-habit grid, optional premium insights. */
@Composable
fun WeeklyScreen(viewModel: AppViewModel) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val week: List<LocalDate> = Dates.lastDays(7)
    val activity = LocalActivity.current
    val moodTheme = LocalMoodTheme.current
    val haptics = LocalHapticFeedback.current
    val s = appStrings()
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showPremiumSheet by remember { mutableStateOf(false) }
    val hasAccess = data.settings.hasFeatureAccess()


    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntranceItem(index = 0) {
            Column {
                Text(
                    text = s.weeklyThisWeek,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = s.weeklyCheckIns,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // Daily completion bars + mood markers
        EntranceItem(index = 1) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                week.forEachIndexed { index, day ->
                    val key = Dates.key(day)
                    val done = data.completedCountOn(key)
                    val total = data.habits.size.coerceAtLeast(1)
                    val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
                    val mood = data.moodHistory[key]
                    val isToday = day == LocalDate.now()

                    // Bars grow from the baseline with a small per-day stagger.
                    val barFill = remember { Animatable(0f) }
                    LaunchedEffect(fraction) {
                        if (moodTheme.motion.enabled) {
                            barFill.animateTo(
                                targetValue = fraction,
                                animationSpec = tween(
                                    durationMillis = 650,
                                    delayMillis = index * 55,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        } else {
                            barFill.snapTo(fraction)
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$done",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (done > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .width(18.dp)
                                .height(96.dp)
                                .background(
                                    color = if (isToday) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(9.dp),
                                ),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height((96 * barFill.value).dp)
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                moodTheme.gradient.last(),
                                                moodTheme.gradient.first(),
                                            ),
                                        ),
                                        shape = RoundedCornerShape(9.dp),
                                    ),
                            )
                        }
                        Text(
                            text = day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (mood != null) {
                            Icon(
                                imageVector = moodIcon(mood),
                                contentDescription = s.weeklyMood.format(mood.name.lowercase().replaceFirstChar { it.uppercase() }),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(14.dp),
                            )
                        } else {
                            Box(modifier = Modifier.padding(top = 4.dp).size(14.dp))
                        }
                    }
                }
            }
        }
        }

        // Per-habit weekly grid
        if (data.habits.isNotEmpty()) {
            EntranceItem(index = 2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = s.weeklyHabitGrid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    data.habits.forEach { habit ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = habit.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                week.forEach { day ->
                                    val checked = data.isCheckedOn(habit.id, Dates.key(day))
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .background(
                                                color = if (checked) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                                shape = CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (checked) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = s.weeklyDone.format(habit.name),
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        // Insights — Premium gets the full stats panel; free users see a
        // locked teaser with greyed placeholder rows and an upgrade CTA.
        if (hasAccess) {
            val totalPossible = data.habits.size * 7
            val totalDone = week.sumOf { data.completedCountOn(Dates.key(it)) }
            val rate = if (totalPossible > 0) (totalDone * 100 / totalPossible) else 0
            val bestDay = week.maxByOrNull { data.completedCountOn(Dates.key(it)) }
            val topHabit = data.habits.maxByOrNull { data.streakFor(it.id) }

            // Premium insights on a rich mood-gradient panel.
            val insightInk = Color(0xFFFFFCF5)
            EntranceItem(index = 3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(moodTheme.gradient.first(), moodTheme.gradient.last()),
                            ),
                        ),
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = s.weeklyInsights,
                            style = MaterialTheme.typography.titleLarge,
                            color = insightInk,
                        )
                        InsightRow(s.weeklyCompletionRate, "$rate%", insightInk)
                        if (bestDay != null && data.completedCountOn(Dates.key(bestDay)) > 0) {
                            InsightRow(
                                s.weeklyBestDay,
                                bestDay.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                                insightInk,
                            )
                        }
                        if (topHabit != null && data.streakFor(topHabit.id) > 0) {
                            InsightRow(s.weeklyMostConsistent, topHabit.name, insightInk)
                        }
                    }
                }
            }
        } else {
            EntranceItem(index = 3) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = s.weeklyInsights,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = s.settingsPremiumOnly,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.alpha(0.4f),
                        ) {
                            GreyedInsightRow(s.weeklyCompletionRate, "••%")
                            GreyedInsightRow(s.weeklyBestDay, "•••••")
                            GreyedInsightRow(s.weeklyMostConsistent, "•••••")
                        }
                        Text(
                            text = "See your completion rate, best day, and most consistent habit with Premium.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { showPremiumSheet = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .defaultMinSize(minHeight = 48.dp),
                        ) { Text(s.settingsLanguageLocked) }
                    }
                }
            }
        }

        // Share CTA — renders the stylized weekly habits + moods card and
        // opens the system share sheet (a copy is saved to the gallery too).
        EntranceItem(index = 4) {
            val shareInk = Color(0xFFFFFCF5)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(moodTheme.gradient.first(), moodTheme.gradient.last()),
                        ),
                    )
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        activity?.let { act ->
                            ProgressShareImage.shareWeeklySummary(act, data)
                        }
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(shareInk.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            tint = shareInk,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text(
                            text = s.weeklyShareMyWeek,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = shareInk,
                        )
                        Text(
                            text = s.weeklyShareDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = shareInk.copy(alpha = 0.85f),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = shareInk.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }

    if (showPremiumSheet) {
        PremiumSheet(
            onDismiss = { showPremiumSheet = false },
            onPurchaseStarted = { viewModel.onSubscriptionPurchaseStarted(it) },
            onRestore = { viewModel.restoreSubscription() },
        )
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }
}

@Composable
private fun InsightRow(label: String, value: String, contentColor: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

/** A greyed-out placeholder row shown in the locked insights teaser. */
@Composable
private fun GreyedInsightRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

