package com.rork.mindsetframestracker.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.rork.mindsetframestracker.data.HabitIcon
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.subscriptionTier
import com.rork.mindsetframestracker.integrations.PolarClient
import com.rork.mindsetframestracker.integrations.StravaAuthClient
import com.rork.mindsetframestracker.notifications.HabitAlarmScheduler
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.MAX_HABIT_NAME_LENGTH
import com.rork.mindsetframestracker.ui.components.ActivitySource
import com.rork.mindsetframestracker.ui.components.ActivitySourcePickerSheet
import com.rork.mindsetframestracker.ui.components.HabitPickerGrid
import com.rork.mindsetframestracker.ui.components.PremiumSheet
import com.rork.mindsetframestracker.ui.components.isActivityTrackableIcon
import com.rork.mindsetframestracker.ui.components.stravaActivityTypeFor
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Habits tab: a grid of large card-style icons. Tapping an icon instantly
 * creates that habit and schedules its alarm at the icon's default time.
 * Tapping the same icon again removes it.
 *
 * The special "To-Do List" icon opens a dialog to name your own custom
 * item and pick the alarm time you want.
 *
 * ## How alarm scheduling works
 *
 * 1. **Tap icon** -> `onHabitAdded` creates a [Habit] with `reminderMinutes`
 *    and `iconId`, calls [AppViewModel.addHabitObject] to persist it, then
 *    [HabitAlarmScheduler.schedule] to arm the AlarmManager alarm, and finally
 *    [AppViewModel.queueSync] to push the change to the cloud.
 *
 * 2. **Activity-trackable icons** -> After adding the habit, the
 *    [ActivitySourcePickerSheet] is shown so the user can connect Strava
 *    or Polar/Health Connect to automatically log activity data for that habit.
 *
 * 3. **To-Do List** -> [TodoListDialog] lets the user type a name and pick a
 *    time, then does the same create -> schedule -> sync flow.
 *
 * 4. **Remove** -> cancels the alarm, deletes the habit, syncs.
 *
 * 5. **Reboot/Update** -> [BootReceiver] calls [HabitAlarmScheduler.rescheduleAll]
 *    to re-arm every alarm from the persisted data.
 */
@Composable
fun HabitsScreen(viewModel: AppViewModel) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val hasAccess = data.settings.hasFeatureAccess()
    val currentTier = data.settings.subscriptionTier()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showTodoDialog by remember { mutableStateOf(false) }
    var showPremiumSheet by remember { mutableStateOf(false) }

    // Alarm picker state: when the user taps any non-TodoList icon, we show
    // a time picker pre-filled with the icon's default alarm time.
    var alarmPickerIcon by remember { mutableStateOf<HabitIcon?>(null) }

    // Activity source picker state: when the user taps an activity-trackable
    // icon, we store the newly created habit info here and show the sheet.
    var activityPickerHabitId by remember { mutableStateOf<String?>(null) }
    var activityPickerIconId by remember { mutableStateOf<String?>(null) }

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
            onIconTapped = { icon ->
                // Check free-tier cap before showing the time picker.
                if (viewModel.canAddHabit()) {
                    alarmPickerIcon = icon
                } else {
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
                    // Cancel the alarm BEFORE deleting the habit data.
                    HabitAlarmScheduler.cancel(context, existing)
                    viewModel.deleteHabit(existing.id)
                    // deleteHabit already calls queueSync internally.
                    scope.launch { snackbarHostState.showSnackbar("Removed ${existing.name}") }
                }
            },
            onTodoListTapped = {
                if (viewModel.canAddHabit()) showTodoDialog = true
                else showPremiumSheet = true
            },
        )
    }

    // ── Alarm time picker for any habit icon ───────────────────────────
    // Shown when the user taps any non-TodoList icon. Pre-filled with the
    // icon's default alarm time so they can customise it before adding.
    if (alarmPickerIcon != null) {
        val icon = alarmPickerIcon!!
        AlarmPickerDialog(
            habitName = icon.label,
            defaultMinutes = icon.defaultReminderMinutes,
            onDismiss = { alarmPickerIcon = null },
            onConfirm = { chosenMinutes ->
                alarmPickerIcon = null
                val habit = Habit(
                    id = UUID.randomUUID().toString(),
                    name = icon.label,
                    createdAt = System.currentTimeMillis(),
                    reminderMinutes = chosenMinutes,
                    iconId = icon.id,
                )
                if (viewModel.addHabitObject(habit)) {
                    HabitAlarmScheduler.schedule(context, habit)
                    viewModel.queueSync()
                    val timeStr = formatAlarmTime(chosenMinutes)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Added ${habit.name} — alarm set for $timeStr",
                        )
                    }
                    // Offer activity source picker for physical-activity icons
                    if (isActivityTrackableIcon(icon.id)) {
                        activityPickerHabitId = habit.id
                        activityPickerIconId = icon.id
                    }
                } else {
                    showPremiumSheet = true
                }
            },
        )
    }

    // ── Activity Source Picker ──────────────────────────────────────────
    // Shown after the user taps an activity-trackable icon (running, gym,
    // strava_yoga, strava_swim, etc.). Lets them pick Polar,
    // Health Connect, or Strava to auto-track activity data for that habit.
    if (activityPickerIconId != null && activityPickerHabitId != null) {
        ActivitySourcePickerSheet(
            habitIconId = activityPickerIconId!!,
            currentTier = currentTier,
            onSourceChosen = { source ->
                val habitId = activityPickerHabitId!!
                val iconId = activityPickerIconId!!

                when (source) {
                    ActivitySource.STRAVA -> {
                        if (viewModel.isStravaConnected()) {
                            val activityType = stravaActivityTypeFor(iconId)
                            viewModel.syncStravaActivities(habitId, activityType)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Syncing Strava activities...",
                                )
                            }
                        } else if (StravaAuthClient.isConfigured) {
                            val authIntent = StravaAuthClient.buildAuthIntent()
                            context.startActivity(authIntent)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Connect your Strava account to sync activities.",
                                )
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Strava is not configured. Please contact support.",
                                )
                            }
                        }
                    }

                    ActivitySource.HEALTH_CONNECT -> {
                        if (data.settings.healthConnectConnected) {
                            val activityType = stravaActivityTypeFor(iconId)
                            viewModel.syncHealthConnectToHabit(habitId, activityType)
                            scope.launch {
                                snackbarHostState.showSnackbar("Syncing from Health Connect...")
                            }
                        } else {
                            viewModel.setHealthConnectConnected(true)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Open Settings > Activity sync to finish Health Connect setup.",
                                )
                            }
                        }
                    }

                    ActivitySource.POLAR -> {
                        if (viewModel.isPolarConnected()) {
                            val activityType = stravaActivityTypeFor(iconId)
                            viewModel.syncPolarToHabit(habitId, activityType)
                            scope.launch {
                                snackbarHostState.showSnackbar("Syncing from Polar...")
                            }
                        } else if (PolarClient.isConfigured) {
                            context.startActivity(PolarClient.buildAuthIntent())
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Connect your Polar account to sync activities.",
                                )
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Polar is not configured. Please contact support.",
                                )
                            }
                        }
                    }
                }

                // Clear picker state
                activityPickerHabitId = null
                activityPickerIconId = null
            },
            onDismiss = {
                // User dismissed without choosing — that's fine, habit is
                // already added with its alarm. They can connect later via
                // Settings > Activity sync.
                activityPickerHabitId = null
                activityPickerIconId = null
            },
        )
    }

    // ── To-Do List creation dialog ──
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
                    // ── ARM THE ALARM ──
                    HabitAlarmScheduler.schedule(context, habit)

                    // ── SYNC TO CLOUD ──
                    viewModel.queueSync()

                    val timeStr = formatAlarmTime(reminderMinutes)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Added ${habit.name} — alarm set for $timeStr",
                        )
                    }
                } else {
                    showPremiumSheet = true
                }
                showTodoDialog = false
            },
        )
    }

    if (showPremiumSheet) {
        PremiumSheet(
            onDismiss = { showPremiumSheet = false },
            onPurchaseStarted = { viewModel.onSubscriptionPurchaseStarted(it) },
            onRestore = { viewModel.restoreSubscription() },
        )
    }
}

// ── Alarm time formatting ───────────────────────────────────────────────────

/** Converts minutes-from-midnight to "7:00 AM" / "9:30 PM" format. */
private fun formatAlarmTime(minutes: Int?): String {
    if (minutes == null) return "no alarm"
    val h = minutes / 60
    val m = minutes % 60
    val period = if (h >= 12) "PM" else "AM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$h12:${m.toString().padStart(2, '0')} $period"
}

/**
 * Alarm time picker dialog — shown when the user taps any habit icon.
 * Pre-filled with the icon's default alarm time; the user can adjust
 * the time before confirming to add the habit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmPickerDialog(
    habitName: String,
    defaultMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (reminderMinutes: Int) -> Unit,
) {
    val defaultHour = defaultMinutes / 60
    val defaultMinute = defaultMinutes % 60
    val timeState = rememberTimePickerState(
        initialHour = defaultHour,
        initialMinute = defaultMinute,
        is24Hour = false,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set alarm for $habitName") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Choose when you'd like to be reminded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TimePicker(state = timeState)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Alarm: ${formatAlarmTime(timeState.hour * 60 + timeState.minute)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(timeState.hour * 60 + timeState.minute) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Add with alarm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The "To-Do List" creation dialog — lets the user name a custom habit
 * and pick the alarm time they want it to fire.
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
        title = { Text("Create custom habit") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_HABIT_NAME_LENGTH) },
                    placeholder = { Text("e.g. Call the dentist") },
                    label = { Text("Habit name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
                Text(
                    text = "Pick the time for your daily reminder alarm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TimePicker(state = timeState)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Alarm: ${formatAlarmTime(timeState.hour * 60 + timeState.minute)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), timeState.hour * 60 + timeState.minute) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Add with alarm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
