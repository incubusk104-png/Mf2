package com.rork.mindsetframestracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.notifications.HabitAlarmScheduler
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.MAX_HABIT_NAME_LENGTH
import com.rork.mindsetframestracker.ui.components.HabitPickerGrid
import com.rork.mindsetframestracker.ui.components.PremiumSheet
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Habits tab: a grid of icons. There is no "add habit" dialog — tapping an
 * icon instantly creates that habit and schedules its alarm at the icon's
 * default time. Tapping the same icon again removes it.
 *
 * The special "To-Do List" icon replaces the old add flow: it opens a dialog to
 * name your own item and pick the alarm time you want.
 */
@Composable
fun HabitsScreen(viewModel: AppViewModel) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val hasAccess = data.settings.hasFeatureAccess()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showTodoDialog by remember { mutableStateOf(false) }
    var showPremiumSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Which catalog icons already have a habit (so the grid can show the check badge).
    val selectedIconIds = remember(data.habits) {
        data.habits.mapNotNull { it.iconId }.toSet()
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        HabitPickerGrid(
            selectedIconIds = selectedIconIds,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 96.dp),
            header = {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
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
                }
            },
            onHabitAdded = { habit ->
                if (viewModel.addHabitObject(habit)) {
                    // Only arm the alarm once the habit was actually added.
                    HabitAlarmScheduler.schedule(context, habit)
                    scope.launch { snackbarHostState.showSnackbar("Added ${habit.name} with a reminder") }
                } else {
                    // Cap reached on the free tier — make it obvious instead of
                    // the tap looking like it did nothing.
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Free limit is $MAX_FREE_HABITS habits — remove one or go Premium.",
                            actionLabel = "Premium",
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) showPremiumSheet = true
                    }
                }
            },
            onHabitRemoved = { iconId ->
                val existing = data.habits.firstOrNull { it.iconId == iconId }
                if (existing != null) {
                    HabitAlarmScheduler.cancel(context, existing)
                    viewModel.deleteHabit(existing.id)
                    scope.launch { snackbarHostState.showSnackbar("Removed ${existing.name}") }
                }
            },
            onTodoListTapped = {
                if (viewModel.canAddHabit()) showTodoDialog = true
                else showPremiumSheet = true
            },
        )
    }

    if (showTodoDialog) {
        TodoListDialog(
            onDismiss = { showTodoDialog = false },
            onConfirm = { name, reminderMinutes ->
                val habit = Habit(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    createdAt = System.currentTimeMillis(),
                    reminderMinutes = reminderMinutes,
                    iconId = "todoList",
                )
                if (viewModel.addHabitObject(habit)) {
                    HabitAlarmScheduler.schedule(context, habit)
                    scope.launch { snackbarHostState.showSnackbar("Added ${habit.name} with a reminder") }
                } else {
                    showPremiumSheet = true
                }
                showTodoDialog = false
            },
        )
    }

    if (showPremiumSheet) {
        PremiumSheet(onDismiss = { showPremiumSheet = false })
    }
}

/**
 * The "To-Do List" creation dialog — the replacement for the old add-habit
 * flow. You type your own item and pick the alarm time you want it to fire.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoListDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, reminderMinutes: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a to-do") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_HABIT_NAME_LENGTH) },
                    placeholder = { Text("e.g. Call the dentist") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
                Text(
                    text = "Set the alarm for when you want to do it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), timeState.hour * 60 + timeState.minute) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
