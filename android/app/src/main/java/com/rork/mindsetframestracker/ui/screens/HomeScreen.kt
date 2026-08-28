package com.rork.mindsetframestracker.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.ContentPack
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.BadgeTier
import com.rork.mindsetframestracker.data.completedCountOn
import com.rork.mindsetframestracker.data.currentMood
import com.rork.mindsetframestracker.data.dailyCheckInStreak
import com.rork.mindsetframestracker.data.fullCompletionStreak
import com.rork.mindsetframestracker.data.isCheckedToday
import com.rork.mindsetframestracker.data.newlyEarnedBadge
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.sortedHabits
import com.rork.mindsetframestracker.data.streakFor
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.ThemeMode
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.CompletionHeatmap
import com.rork.mindsetframestracker.ui.components.CompanionNotificationCard
import com.rork.mindsetframestracker.ui.components.ConfettiBurst
import com.rork.mindsetframestracker.ui.avatar.CompanionStudioSheet
import com.rork.mindsetframestracker.ui.components.DailyGoalShareDialog
import com.rork.mindsetframestracker.ui.components.EntranceItem
import com.rork.mindsetframestracker.ui.components.BadgeSection
import com.rork.mindsetframestracker.ui.components.BadgeStrings
import com.rork.mindsetframestracker.ui.components.BadgeUnlockOverlay
import com.rork.mindsetframestracker.ui.components.badgeTitle
import com.rork.mindsetframestracker.ui.components.badgeDesc
import com.rork.mindsetframestracker.ui.components.MilestoneBanner
import com.rork.mindsetframestracker.ui.components.MilestoneCelebration
import com.rork.mindsetframestracker.ui.components.MoodPicker
import com.rork.mindsetframestracker.ui.components.ThemeToggleButton
import com.rork.mindsetframestracker.ui.components.milestoneReached
import com.rork.mindsetframestracker.ui.components.TipSheet
import com.rork.mindsetframestracker.billing.TipBilling
import com.rork.mindsetframestracker.ui.theme.DisplayFontFamily
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import com.rork.mindsetframestracker.util.StreakShare
import com.rork.mindsetframestracker.util.ProgressShareImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Home / daily check-in: mood picker, prompt, quote, habit checklist. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onGoToHabits: () -> Unit,
) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val newCompanionUnlocks by viewModel.newCompanionUnlocks.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    val moodTheme = LocalMoodTheme.current
    val mood = data.currentMood()
    val copy = ContentPack.copyFor(mood, data.settings.language)
    val doneCount = data.habits.count { data.isCheckedToday(it.id) }
    val streak = data.dailyCheckInStreak()
    val hasAccess = data.settings.hasFeatureAccess()
    var showCompanionStudio by remember { mutableStateOf(false) }
    var showGrounding by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showTipSheet by remember { mutableStateOf(false) }
    var tipPurchaseInFlight by remember { mutableStateOf(false) }
    val s = appStrings()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Huawei IAP resolves through Activity.startActivityForResult, handled in
    // MainActivity.onActivityResult (not a Compose launcher — see TipBilling.kt
    // for why). The result arrives here as a one-shot ViewModel event instead.
    val tipMessage by viewModel.tipMessage.collectAsStateWithLifecycle()
    LaunchedEffect(tipMessage) {
        val message = tipMessage ?: return@LaunchedEffect
        tipPurchaseInFlight = false
        snackbarHostState.showSnackbar(message)
        viewModel.consumeTipMessage()
    }

    // Subscription purchase/restore outcome — same one-shot event pattern.
    val subscriptionMessage by viewModel.subscriptionMessage.collectAsStateWithLifecycle()
    LaunchedEffect(subscriptionMessage) {
        val message = subscriptionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSubscriptionMessage()
    }

    // Strava / Huawei Health connection outcome (deep-link + auth results).
    val stravaMessage by viewModel.stravaMessage.collectAsStateWithLifecycle()
    LaunchedEffect(stravaMessage) {
        val message = stravaMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeStravaMessage()
    }

    // Effective theme (SYSTEM resolved) for the header's quick light/dark toggle.
    val systemDark = isSystemInDarkTheme()
    val isDarkTheme = when (data.settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    var previousStreak by remember { mutableIntStateOf(streak) }
    var celebrationTrigger by remember { mutableIntStateOf(0) }
    var celebratedMilestone by remember { mutableIntStateOf(0) }
    LaunchedEffect(streak) {
        val milestone = milestoneReached(previousStreak, streak)
        if (milestone != null && milestone != celebratedMilestone) {
            celebratedMilestone = milestone
            celebrationTrigger++
        }
        previousStreak = streak
    }

    // Badge detection: tracks the full-completion streak (all habits done
    // for consecutive days) and awards badges when tier thresholds are crossed.
    val fullCompletionStreak = data.fullCompletionStreak()
    var previousFullStreak by remember { mutableIntStateOf(fullCompletionStreak) }
    var newlyEarnedTier by remember { mutableStateOf<BadgeTier?>(null) }
    var showBadgeUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(fullCompletionStreak) {
        val tier = newlyEarnedBadge(
            previousStreak = previousFullStreak,
            currentStreak = fullCompletionStreak,
            alreadyEarned = data.settings.earnedBadges,
        )
        if (tier != null) {
            viewModel.awardBadge(tier)
            newlyEarnedTier = tier
            showBadgeUnlock = true
        }
        previousFullStreak = fullCompletionStreak
    }

    // Full-screen unlock celebration — shown once per newly earned badge,
    // with a share CTA at the peak-pride moment.
    val badgeStrings = BadgeStrings(
        sectionTitle = s.badgeSectionTitle,
        sectionSubtitle = s.badgeSectionSubtitle,
        tier3Title = s.badge3Title,
        tier3Desc = s.badge3Desc,
        tier7Title = s.badge7Title,
        tier7Desc = s.badge7Desc,
        tier14Title = s.badge14Title,
        tier14Desc = s.badge14Desc,
        tier30Title = s.badge30Title,
        tier30Desc = s.badge30Desc,
        newBadge = s.badgeNew,
    )
    val unlockTier = newlyEarnedTier
    if (showBadgeUnlock && unlockTier != null) {
        BadgeUnlockOverlay(
            tier = unlockTier,
            title = badgeTitle(unlockTier, badgeStrings),
            description = badgeDesc(unlockTier, badgeStrings),
            unlockedLabel = s.badgeUnlocked,
            shareCta = s.badgeShareCta,
            dismissLabel = s.badgeKeepGoing,
            onShare = {
                activity?.let { act ->
                    StreakShare.shareBadge(
                        act as android.content.Context,
                        badgeTitle(unlockTier, badgeStrings),
                        unlockTier.daysRequired,
                    )
                }
                showBadgeUnlock = false
            },
            onDismiss = { showBadgeUnlock = false },
        )
    }

    // Shareable daily-goal card: the moment the LAST habit of the day is
    // checked, offer a branded image card to share. Auto-offers once per day;
    // the "All done" card keeps a permanent share entry point afterwards.
    val habitsAllDone = data.habits.isNotEmpty() && doneCount == data.habits.size
    var wasAllDone by remember { mutableStateOf(habitsAllDone) }
    var shareCardOfferedDay by rememberSaveable { mutableStateOf("") }
    var showDailyShareDialog by remember { mutableStateOf(false) }
    LaunchedEffect(habitsAllDone) {
        if (habitsAllDone && !wasAllDone && shareCardOfferedDay != Dates.todayKey()) {
            shareCardOfferedDay = Dates.todayKey()
            // Let the checkmark pop / ring-close animation land first.
            kotlinx.coroutines.delay(900)
            showDailyShareDialog = true
        }
        wasAllDone = habitsAllDone
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // Pull-to-refresh triggers a Supabase sync. syncNow() itself enforces the
    // 15s cooldown, so pulls during the cooldown (or while busy) are no-ops —
    // the indicator simply retracts because `busy` never turns on.
    PullToRefreshBox(
        isRefreshing = syncState.busy,
        onRefresh = { viewModel.syncNow() },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            EntranceItem(index = 0) {
                HomeHeader(
                    taglineKey = mood.name,
                    tagline = copy.tagline,
                    streak = streak,
                    isDarkTheme = isDarkTheme,
                    reducedMotion = data.settings.reducedMotion,
                    onToggleTheme = {
                        viewModel.setThemeMode(
                            if (isDarkTheme) ThemeMode.LIGHT else ThemeMode.DARK
                        )
                    },
                    onShareStreak = {
                        activity?.let { act ->
                            StreakShare.shareStreak(act as android.content.Context, data)
                        }
                    },
                )
            }
        }

        item(key = "mood") {
            EntranceItem(index = 1) {
                Column {
                    Text(
                        text = "How are you arriving today?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    MoodPicker(
                        selected = mood,
                        onSelect = { viewModel.selectMood(it) },
                    )
                }
            }
        }

        item(key = "prompt") {
            EntranceItem(index = 2) {
                // Hero prompt card: mood gradient, cream text, soft watermark.
                val promptInk = Color(0xFFFFFCF5)
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
                    Icon(
                        imageVector = Icons.Outlined.SelfImprovement,
                        contentDescription = null,
                        tint = promptInk.copy(alpha = 0.12f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp)
                            .size(104.dp),
                    )
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.SelfImprovement,
                                contentDescription = null,
                                tint = promptInk.copy(alpha = 0.9f),
                                modifier = Modifier.size(18.dp),
                            )
                            Crossfade(
                                targetState = copy.promptHeader.uppercase(Locale.getDefault()),
                                animationSpec = moodTheme.motion.tween(500),
                                label = "promptHeader",
                            ) { header ->
                                Text(
                                    text = header,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp,
                                    color = promptInk.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        Crossfade(
                            targetState = ContentPack.promptFor(mood, hasAccess, data.settings.language),
                            animationSpec = moodTheme.motion.tween(500),
                            label = "prompt",
                        ) { prompt ->
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.titleLarge,
                                color = promptInk,
                                modifier = Modifier.padding(top = 12.dp, end = 24.dp),
                            )
                        }
                    }
                }
            }
        }

        item(key = "quote") {
            EntranceItem(index = 3) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                ) {
                    Row(modifier = Modifier.padding(20.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.FormatQuote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Crossfade(
                            targetState = ContentPack.quoteFor(mood, hasAccess, data.settings.language),
                            animationSpec = moodTheme.motion.tween(500),
                            label = "quote",
                        ) { quote ->
                            Text(
                                text = quote,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = DisplayFontFamily,
                                    lineHeight = 26.sp,
                                ),
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        // Companion mood indicator — a tiny empathetic presence that mirrors
        // the day's state: curious when the list is empty, gently encouraging
        // when nothing is checked, cheering as habits complete.
        item(key = "companionMood") {
            EntranceItem(index = 4) {
                // The companion's in-app notification surface — live status,
                // reminders, quotes, and Studio unlock alerts — and still the
                // one-tap entry into the Companion Studio.
                CompanionNotificationCard(
                    data = data,
                    hasNewUnlocks = newCompanionUnlocks.isNotEmpty(),
                    onOpenStudio = { showCompanionStudio = true },
                )
            }
        }

        // Grounding toolkit entry — always one tap away; tinted with the
        // accent when today's mood is Overwhelmed, the moment it helps most.
        item(key = "groundingEntry") {
            EntranceItem(index = 4) {
                Card(
                    onClick = { showGrounding = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (mood == MoodMode.OVERWHELMED) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SelfImprovement,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = s.groundingTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = s.groundingEntrySub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        item(key = "habitsHeader") {
            EntranceItem(index = 4) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Crossfade(
                        targetState = copy.habitsHeader,
                        animationSpec = moodTheme.motion.tween(500),
                        label = "habitsHeader",
                    ) { header ->
                        Text(
                            text = header,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Crossfade(
                        targetState = copy.habitsSub,
                        animationSpec = moodTheme.motion.tween(500),
                        label = "habitsSub",
                    ) { sub ->
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (data.habits.isNotEmpty()) {
                        Text(
                            text = "$doneCount of ${data.habits.size} done today",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (data.habits.isNotEmpty()) {
                    TodayProgressRing(
                        done = doneCount,
                        total = data.habits.size,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            }
        }

        if (data.habits.isEmpty()) {
            item(key = "empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = copy.emptyHabits,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onGoToHabits) {
                            Text("Add a habit")
                        }
                    }
                }
            }
        } else {
            // Completed habits sink to the bottom of the checklist (stable sort,
            // so pinned-first ordering is preserved within each group).
            val displayHabits = data.sortedHabits().sortedBy { data.isCheckedToday(it.id) }
            items(count = displayHabits.size, key = { displayHabits[it].id }) { index ->
                val habit = displayHabits[index]
                // All habits are unlocked — no soft-locking.
                val isSoftLocked = false
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            val removedCheckIns = viewModel.state.value.checkIns[habit.id].orEmpty()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.deleteHabit(habit.id)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = s.habitsDeleted.format(habit.name),
                                    actionLabel = s.habitsUndo,
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.restoreHabit(habit, removedCheckIns)
                                }
                            }
                            true
                        } else {
                            false
                        }
                    },
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(
                            // Brief delay lets the checkmark pop finish before the row
                            // glides to its new position. Disabled with reduced motion.
                            placementSpec = if (moodTheme.motion.enabled) {
                                tween(durationMillis = 500, delayMillis = 250, easing = FastOutSlowInEasing)
                            } else {
                                null
                            },
                        ),
                    backgroundContent = {
                        val targeted = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                        val bgColor by animateColorAsState(
                            targetValue = if (targeted) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.surfaceVariant,
                            label = "swipeDeleteBg",
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = if (targeted) 1.15f else 0.85f,
                            label = "swipeDeleteIcon",
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(bgColor)
                                .padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete ${habit.name}",
                                tint = if (targeted) MaterialTheme.colorScheme.onError
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.scale(iconScale),
                            )
                        }
                    },
                ) {
                    HabitRow(
                        habit = habit,
                        data = data,
                        moodKey = mood.name,
                        staggerIndex = index,
                        isSoftLocked = isSoftLocked,
                        onToggle = { viewModel.toggleHabitToday(habit.id) },
                        onSoftLockClick = { },
                    )
                }
            }
            if (doneCount == data.habits.size) {
                item(key = "allDone") {
                    AllDoneCard(
                        message = copy.allDone,
                        streak = streak,
                        onShare = {
                            activity?.let { act ->
                                ProgressShareImage.shareDailyCompletion(act as android.content.Context, data)
                            }
                        },
                    )
                }
            }

            // Achievement badges — earned by completing all habits for
            // consecutive days. Shown only when at least one badge exists.
            if (data.settings.earnedBadges.isNotEmpty()) {
                item(key = "badges") {
                    BadgeSection(
                        earnedBadges = data.settings.earnedBadges,
                        newlyEarnedTier = newlyEarnedTier,
                        currentFullCompletionStreak = fullCompletionStreak,
                        strings = BadgeStrings(
                            sectionTitle = s.badgeSectionTitle,
                            sectionSubtitle = s.badgeSectionSubtitle,
                            tier3Title = s.badge3Title,
                            tier3Desc = s.badge3Desc,
                            tier7Title = s.badge7Title,
                            tier7Desc = s.badge7Desc,
                            tier14Title = s.badge14Title,
                            tier14Desc = s.badge14Desc,
                            tier30Title = s.badge30Title,
                            tier30Desc = s.badge30Desc,
                            newBadge = s.badgeNew,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item(key = "weeklySummary") {
                PeriodSummaryCard(
                    data = data,
                    dayCount = 7,
                    title = "Last 7 days",
                    periodLabel = "this week",
                    shareDescription = "Share your weekly progress",
                    onShare = {
                        activity?.let { act ->
                            ProgressShareImage.shareWeeklySummary(act as android.content.Context, data)
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    entranceIndex = 0,
                )
            }
            item(key = "monthlySummary") {
                PeriodSummaryCard(
                    data = data,
                    dayCount = 30,
                    title = "Last 30 days",
                    periodLabel = "this month",
                    shareDescription = "Share your monthly progress",
                    onShare = {
                        activity?.let { act ->
                            ProgressShareImage.shareMonthlySummary(act as android.content.Context, data)
                        }
                    },
                    entranceIndex = 1,
                )
            }
            item(key = "heatmap") {
                CompletionHeatmap(
                    data = data,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!hasAccess) {
                item(key = "supportTip") {
                    Card(
                        onClick = { showTipSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            ) {
                                Text(
                                    text = "Enjoying the app?",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Leave a small tip to support development",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

    }
    }

    // Support tip entry now lives inline in the habit list (see "supportTip"
    // item above) instead of floating over content — was landing in dead
    // space above the nav bar and reading as an accidental leftover element.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp),
    )

    MilestoneCelebration(
        trigger = celebrationTrigger,
        accentColors = listOf(
            moodTheme.accent,
            moodTheme.gradient.first(),
            moodTheme.gradient.last(),
            MaterialTheme.colorScheme.primaryContainer,
        ),
        motionEnabled = moodTheme.motion.enabled,
    )

    MilestoneBanner(
        trigger = celebrationTrigger,
        milestone = celebratedMilestone,
        motionEnabled = moodTheme.motion.enabled,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 20.dp),
    )
    }

    if (showDailyShareDialog) {
        DailyGoalShareDialog(
            data = data,
            onShare = {
                showDailyShareDialog = false
                activity?.let { act ->
                    ProgressShareImage.shareDailyCompletion(act as android.content.Context, data)
                }
            },
            onDismiss = { showDailyShareDialog = false },
        )
    }

    if (showTipSheet) {
        TipSheet(
            onDismiss = { showTipSheet = false },
            onSendTip = { productId ->
                if (tipPurchaseInFlight) return@TipSheet
                showTipSheet = false
                activity?.let { act ->
                    tipPurchaseInFlight = true
                    TipBilling.purchase(
                        activity = act,
                        productId = productId,
                        onError = { message ->
                            tipPurchaseInFlight = false
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                    )
                }
            }
        )
    }

    if (showCompanionStudio) {
        CompanionStudioSheet(
            viewModel = viewModel,
            onDismiss = { showCompanionStudio = false },
        )
    }

    if (showGrounding) {
        GroundingSheet(
            viewModel = viewModel,
            onDismiss = { showGrounding = false },
        )
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }

}

@Composable
private fun AllDoneCard(
    message: String,
    streak: Int,
    onShare: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (streak > 0) {
                Text(
                    text = if (streak == 1) {
                        "That's a 1-day streak — tell the world!"
                    } else {
                        "That's a $streak-day streak — tell the world!"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            androidx.compose.material3.Button(
                onClick = onShare,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.IosShare,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Share achievement",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Summary card shown above the calendar heatmap: percentage of mindset
 * frames (habit check-ins) completed over the last [dayCount] days, with
 * an animated progress bar, a supporting count line, and a share button
 * that renders a social-media-ready progress image.
 */
@Composable
private fun PeriodSummaryCard(
    data: AppData,
    dayCount: Int,
    title: String,
    periodLabel: String,
    shareDescription: String,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    entranceIndex: Int = 0,
) {
    val moodTheme = LocalMoodTheme.current
    val habitCount = data.habits.size
    val summary: Triple<Int, Int, Int> = remember(data.checkIns, habitCount, dayCount) {
        val days: List<java.time.LocalDate> = Dates.lastDays(dayCount)
        val done: Int = days.sumOf { day -> data.completedCountOn(Dates.key(day)) }
        val total: Int = habitCount * dayCount
        val pct: Int = if (total > 0) ((done * 100f) / total).toInt().coerceIn(0, 100) else 0
        Triple(done, total, pct)
    }
    val (completed, possible, percent) = summary

    val enterOffset = remember { Animatable(60f) }
    val enterAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (moodTheme.motion.enabled) {
            kotlinx.coroutines.delay(entranceIndex * 120L)
            launch {
                enterOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                )
            }
            launch {
                enterAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                )
            }
        } else {
            enterOffset.snapTo(0f)
            enterAlpha.snapTo(1f)
        }
    }

    var sharePulseCount by remember { mutableIntStateOf(0) }
    val cardScale = remember { Animatable(1f) }
    val cardAlpha = remember { Animatable(1f) }
    LaunchedEffect(sharePulseCount) {
        if (sharePulseCount > 0 && moodTheme.motion.enabled) {
            launch {
                cardScale.animateTo(0.97f, animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing))
                cardScale.animateTo(1f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
            }
            launch {
                cardAlpha.animateTo(0.85f, animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing))
                cardAlpha.animateTo(1f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
            }
        }
    }

    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(percent) {
        if (moodTheme.motion.enabled) {
            animatedProgress.animateTo(
                targetValue = percent / 100f,
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            )
        } else {
            animatedProgress.snapTo(percent / 100f)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale.value
                scaleY = cardScale.value
                alpha = cardAlpha.value * enterAlpha.value
                translationY = enterOffset.value * density
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (possible > 0) {
                    IconButton(
                        onClick = {
                            sharePulseCount += 1
                            onShare()
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.IosShare,
                            contentDescription = shareDescription,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animatedProgress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.small),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                gapSize = 0.dp,
            )
            Text(
                text = if (possible > 0) {
                    "$completed of $possible mindset frames completed $periodLabel"
                } else {
                    "Add a habit to start tracking your progress"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Circular progress ring shown at the top of the daily habit checklist.
 * Fills clockwise from 12 o'clock based on the share of today's habits
 * completed, with the live percentage in the center. The fill animates
 * smoothly on every check-in/uncheck (skipped when reduced motion is on).
 */
@Composable
private fun TodayProgressRing(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current
    val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
    val percent = (fraction * 100f).toInt().coerceIn(0, 100)

    val animatedFraction = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        if (moodTheme.motion.enabled) {
            animatedFraction.animateTo(
                targetValue = fraction,
                animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            )
        } else {
            animatedFraction.snapTo(fraction)
        }
    }

    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val ringBrush = Brush.linearGradient(
        colors = listOf(moodTheme.gradient.first(), moodTheme.gradient.last()),
    )
    val isComplete = total > 0 && done == total

    // Celebratory pop the moment the ring closes.
    val completionPop = remember { Animatable(1f) }
    LaunchedEffect(isComplete) {
        if (isComplete && moodTheme.motion.enabled) {
            completionPop.snapTo(0.82f)
            completionPop.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
            )
        }
    }

    Box(
        modifier = modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = completionPop.value
                scaleY = completionPop.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 6.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            val sweep = 360f * animatedFraction.value.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(
                    brush = ringBrush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        if (isComplete) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "All habits completed today",
                tint = ringColor,
                modifier = Modifier.size(26.dp),
            )
        } else {
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ringColor,
            )
        }
    }
}

@Composable
private fun HomeHeader(
    taglineKey: String,
    tagline: String,
    streak: Int,
    isDarkTheme: Boolean,
    reducedMotion: Boolean,
    onToggleTheme: () -> Unit,
    onShareStreak: () -> Unit,
) {
    val moodTheme = LocalMoodTheme.current
    val s = appStrings()
    val today = LocalDate.now()
    val dateLine = "${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, " +
        today.format(DateTimeFormatter.ofPattern("MMMM d"))

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dateLine,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (streak > 0) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.clickable(onClick = onShareStreak),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocalFireDepartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = if (streak == 1) s.homeStreakDay.format(1) else s.homeStreakDays.format(streak),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        Icon(
                            imageVector = Icons.Outlined.IosShare,
                            contentDescription = "Share your streak",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .size(14.dp)
                                .padding(start = 4.dp),
                        )
                    }
                }
            }
            ThemeToggleButton(
                isDark = isDarkTheme,
                reducedMotion = reducedMotion,
                onToggle = onToggleTheme,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = "Today's check-in",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        Crossfade(
            targetState = tagline,
            animationSpec = moodTheme.motion.tween(500),
            label = "tagline$taglineKey",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HabitRow(
    habit: Habit,
    data: AppData,
    moodKey: String,
    staggerIndex: Int,
    isSoftLocked: Boolean = false,
    onToggle: () -> Unit,
    onSoftLockClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val moodTheme = LocalMoodTheme.current
    val haptics = LocalHapticFeedback.current
    val isDone = data.isCheckedToday(habit.id)
    val streak = data.streakFor(habit.id)
    val s = appStrings()

    val checkScale by animateFloatAsState(
        targetValue = if (isDone) 1f else 0.92f,
        animationSpec = moodTheme.motion.springFloat(),
        label = "check${habit.id}",
    )

    // Completion celebration: a soft ripple ring expanding out of the checkmark
    // plus a quick scale "pop" and a confetti burst, fired only when the habit
    // flips to done. The burst grows when this check completes every habit for
    // the day. All of it is skipped when motion is reduced.
    val ripple = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    var wasDone by remember { mutableStateOf(isDone) }
    var confettiId by remember { mutableStateOf(0) }
    var confettiBig by remember { mutableStateOf(false) }
    LaunchedEffect(isDone) {
        if (isDone && !wasDone) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (moodTheme.motion.enabled) {
                confettiBig = data.habits.isNotEmpty() &&
                    data.habits.all { data.isCheckedToday(it.id) }
                confettiId++
                ripple.snapTo(0f)
                launch {
                    pop.snapTo(0.6f)
                    pop.animateTo(1f, animationSpec = moodTheme.motion.springFloat())
                }
                ripple.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
                )
                ripple.snapTo(0f)
            }
        }
        wasDone = isDone
    }
    val rippleColor = MaterialTheme.colorScheme.primary

    // Subtle staggered ripple when the mood mode changes: each row briefly
    // dips in opacity and slides in, offset by its position in the list.
    // Disabled entirely for moods with motion off / reduced motion.
    val moodShift = remember { Animatable(1f) }
    var lastMood by remember { mutableStateOf(moodKey) }
    LaunchedEffect(moodKey) {
        if (moodKey != lastMood && moodTheme.motion.enabled) {
            lastMood = moodKey
            moodShift.snapTo(0f)
            kotlinx.coroutines.delay(staggerIndex * 60L)
            moodShift.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = (450 * moodTheme.motion.durationScale).toInt(),
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            lastMood = moodKey
            moodShift.snapTo(1f)
        }
    }

    // Whole row is tappable; pressing gently compresses the card.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = moodTheme.motion.springFloat(),
        label = "press${habit.id}",
    )

    val lockAlpha by animateFloatAsState(
        targetValue = if (isSoftLocked) 0.45f else 1f,
        animationSpec = moodTheme.motion.tween(300),
        label = "softLock${habit.id}",
    )

    Card(
        onClick = if (isSoftLocked) onSoftLockClick else onToggle,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val t = moodShift.value
                alpha = (0.35f + 0.65f * t) * lockAlpha
                translationX = (1f - t) * 24.dp.toPx()
                val s = (0.97f + 0.03f * t) * pressScale
                scaleX = s
                scaleY = s
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (isSoftLocked) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSoftLocked) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Habit locked — unlock with Premium",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp),
                ) {
                    // Confetti celebration around the checkmark. requiredSize
                    // lets the burst overflow the 48dp slot without growing
                    // the row; each new confettiId restarts the one-shot burst.
                    if (confettiId > 0 && moodTheme.motion.enabled) {
                        key(confettiId) {
                            ConfettiBurst(
                                colors = listOf(
                                    moodTheme.gradient.first(),
                                    moodTheme.gradient.last(),
                                    Color(0xFFE9B44C),
                                    Color(0xFF9CAF88),
                                    Color(0xFFC7724F),
                                ),
                                particleCount = if (confettiBig) 64 else 26,
                                modifier = Modifier.requiredSize(if (confettiBig) 220.dp else 130.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggle,
                        modifier = Modifier
                            .size(48.dp)
                            .drawBehind {
                                val t = ripple.value
                                if (t > 0f) {
                                    drawCircle(
                                        color = rippleColor.copy(alpha = 0.35f * (1f - t)),
                                        radius = size.minDimension * (0.3f + 0.65f * t),
                                    )
                                }
                            },
                    ) {
                        Icon(
                            imageVector = if (isDone) Icons.Filled.CheckCircle
                            else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (isDone) "Mark ${habit.name} as not done"
                            else "Mark ${habit.name} as done",
                            tint = if (isDone) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(28.dp)
                                .scale(checkScale * pop.value),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isDone) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (isSoftLocked) {
                    Text(
                        text = s.habitsSoftLocked,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (isDone) {
                    Text(
                        text = "Done today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (streak > 0) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocalFireDepartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = if (streak == 1) "1 day" else "$streak days",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.size(8.dp))
            }
        }
    }
}
