package com.rork.mindsetframestracker.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitSuggestion
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.sortedHabits
import com.rork.mindsetframestracker.data.streakFor
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.BulkAddHabitsSheet
import com.rork.mindsetframestracker.ui.components.PremiumSheet
import com.rork.mindsetframestracker.ui.components.ActivityInsightSheet
import com.rork.mindsetframestracker.util.VoiceInputClient
import android.speech.SpeechRecognizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme

/** Habit list with add/edit/delete and swipe-to-delete. */
@Composable
fun HabitsScreen(viewModel: AppViewModel) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    val context = LocalContext.current
    val s = appStrings()
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var viewingActivityHabit by remember { mutableStateOf<Habit?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showBulkAdd by remember { mutableStateOf(false) }
    var showPremiumSheet by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    val hasAccess = data.settings.hasFeatureAccess()
    val atFreeCap = !hasAccess && data.habits.size >= MAX_FREE_HABITS
    val snackbarHostState = remember { SnackbarHostState() }
    var suggestions by remember { mutableStateOf<List<HabitSuggestion>>(emptyList()) }
    LaunchedEffect(data.habits.size) {
        suggestions = if (data.habits.isNotEmpty()) viewModel.getSuggestions() else emptyList()
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val moodTheme = LocalMoodTheme.current

    // Transparent so the shared mood backdrop wash shows through.
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (viewModel.canAddHabit()) {
                        showAddDialog = true
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showPremiumSheet = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    Icon(
                        imageVector = if (atFreeCap) Icons.Outlined.Lock else Icons.Outlined.Add,
                        contentDescription = null,
                    )
                },
                text = { Text("New habit") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = "Your habits",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = if (hasAccess) "${data.habits.size} habits"
                        else "${data.habits.size} of $MAX_FREE_HABITS free habits",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(
                        onClick = { showBulkAdd = true },
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text("Add multiple at once")
                    }
                }
            }

            if (data.habits.isNotEmpty() && suggestions.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Suggested for you",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        suggestions.take(3).forEach { suggestion ->
                            AssistChip(
                                onClick = {
                                    if (viewModel.canAddHabit()) {
                                        viewModel.addHabit(suggestion.name)
                                    } else {
                                        showPremiumSheet = true
                                    }
                                },
                                label = { Text("${suggestion.name} — ${suggestion.reason}") },
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            if (data.habits.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Text(
                            text = "No habits yet. Tap “New habit” to add your first small daily win.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }

            if (atFreeCap) {
                item {
                    Card(
                        onClick = { showPremiumSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "You've reached the free limit of $MAX_FREE_HABITS habits. Premium removes the cap.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                            )
                        }
                    }
                }
            }

            items(items = data.sortedHabits(), key = { it.id }) { habit ->
                val streak = data.streakFor(habit.id)
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
                        .animateItem(),
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
                    val cardInteraction = remember { MutableInteractionSource() }
                    val cardPressed by cardInteraction.collectIsPressedAsState()
                    val cardScale by animateFloatAsState(
                        targetValue = if (cardPressed) 0.975f else 1f,
                        animationSpec = moodTheme.motion.springFloat(),
                        label = "habitCardPress",
                    )
                    Card(
                        onClick = { 
                            val repo = MindsetRepository(context)
                            val records = repo.activityRecordsForHabit(habit.id)
                            if (records.isNotEmpty()) {
                                viewingActivityHabit = habit
                            } else {
                                editingHabit = habit
                            }
                        },
                        interactionSource = cardInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = cardScale
                                scaleY = cardScale
                            },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (habit.isPinned) {
                                        Icon(
                                            imageVector = Icons.Filled.PushPin,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .padding(end = 0.dp),
                                        )
                                    }
                                    Text(
                                        text = habit.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        modifier = if (habit.isPinned) Modifier.padding(start = 4.dp) else Modifier,
                                    )
                                }
                                if (streak > 0) {
                                    Surface(
                                        shape = MaterialTheme.shapes.large,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.padding(top = 4.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.LocalFireDepartment,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                text = "$streak-day streak",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(start = 4.dp),
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "No streak yet — check in today",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.togglePinned(habit.id)
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = if (habit.isPinned) Icons.Filled.PushPin
                                    else Icons.Outlined.PushPin,
                                    contentDescription = if (habit.isPinned) "Unpin ${habit.name}"
                                    else "Pin ${habit.name} to top",
                                    tint = if (habit.isPinned) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { editingHabit = habit },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit ${habit.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        HabitDialog(
            title = "New habit",
            initialName = "",
            onConfirm = { name ->
                viewModel.addHabit(name)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
            voiceInputUnlocked = hasAccess,
            onRequirePremium = { showPremiumSheet = true },
        )
    }

    editingHabit?.let { habit ->
        HabitDialog(
            title = "Edit habit",
            initialName = habit.name,
            onAutoSave = { name -> viewModel.renameHabit(habit.id, name) },
            onConfirm = { name ->
                viewModel.renameHabit(habit.id, name)
                editingHabit = null
            },
            onDismiss = { editingHabit = null },
            onDelete = {
                viewModel.deleteHabit(habit.id)
                editingHabit = null
            },
            voiceInputUnlocked = hasAccess,
            onRequirePremium = { showPremiumSheet = true },
        )
    }

    viewingActivityHabit?.let { habit ->
        val repo = remember { MindsetRepository(context) }
        val records = repo.activityRecordsForHabit(habit.id)
        val latest = records.firstOrNull()
        if (latest != null) {
            ActivityInsightSheet(
                title = "Latest ${latest.activityType}",
                insightText = com.rork.mindsetframestracker.data.RuleBasedInsight.forActivity(latest),
                onDismiss = { viewingActivityHabit = null },
            )
        } else {
            viewingActivityHabit = null
            editingHabit = habit
        }
    }

    if (showBulkAdd) {
        BulkAddHabitsSheet(
            suggestions = suggestions,
            voiceInputUnlocked = hasAccess,
            onRequirePremium = { showPremiumSheet = true },
            onConfirm = { names ->
                val result = viewModel.addHabits(names)
                showBulkAdd = false
                if (result.blockedByLimit.isNotEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "${result.blockedByLimit.size} habit(s) need Premium — unlimited habits unlock the rest.",
                        )
                    }
                }
            },
            onDismiss = { showBulkAdd = false },
        )
    }

    if (showPremiumSheet) {
        PremiumSheet(onDismiss = { showPremiumSheet = false })
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }
}

@Composable
private fun HabitDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onAutoSave: ((String) -> Unit)? = null,
    voiceInputUnlocked: Boolean = true,
    onRequirePremium: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    val context = LocalContext.current
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var micError by remember { mutableStateOf<String?>(null) }

    val startVoiceInput: () -> Unit = {
        recognizer = VoiceInputClient.startListening(
            context = context,
            onResult = { text -> name = text },
            onError = { msg -> micError = msg },
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startVoiceInput() else micError = "Microphone permission is needed for voice input." }

    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    if (onAutoSave != null) {
        LaunchedEffect(name) {
            if (name != initialName && name.trim().isNotEmpty()) {
                kotlinx.coroutines.delay(700)
                onAutoSave(name)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("e.g. Read 10 pages") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            if (!voiceInputUnlocked) {
                                onRequirePremium?.invoke()
                                return@IconButton
                            }
                            micError = null
                            if (VoiceInputClient.isAvailable(context)) {
                                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else {
                                micError = "Voice input isn't available on this device."
                            }
                        }) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Add habit by voice",
                                tint = if (voiceInputUnlocked) LocalContentColor.current
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    },
                    supportingText = when {
                        micError != null -> { { Text(micError!!, color = MaterialTheme.colorScheme.error) } }
                        onAutoSave != null -> { { Text("Changes save automatically") } }
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Delete habit and its history",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty(),
            ) { Text(if (onAutoSave != null) "Done" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
