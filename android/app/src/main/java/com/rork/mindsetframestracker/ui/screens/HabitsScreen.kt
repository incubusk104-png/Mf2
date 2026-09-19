package com.rork.mindsetframestracker.ui.screens

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.data.AlarmDaySlot
import com.rork.mindsetframestracker.data.AlarmSlotState
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.trackingModeOrDefault
import com.rork.mindsetframestracker.data.trackingTargetSecondsOrDefault
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.notifications.TimerController
import com.rork.mindsetframestracker.ui.components.HabitActivityToolsRow
import com.rork.mindsetframestracker.data.HabitAlarmDraft
import com.rork.mindsetframestracker.data.HabitAlarmHistory
import com.rork.mindsetframestracker.data.HabitAlarmSetup
import com.rork.mindsetframestracker.data.alarmMinutes
import com.rork.mindsetframestracker.data.alarmTimeOfDayLabel
import com.rork.mindsetframestracker.data.formatAlarmTimes
import com.rork.mindsetframestracker.data.withAlarmTimes
import com.rork.mindsetframestracker.data.withAlarmMessage
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MotivationalMessages
import com.rork.mindsetframestracker.data.HabitIcon
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.data.REPEAT_DAILY
import com.rork.mindsetframestracker.data.REPEAT_ONCE
import com.rork.mindsetframestracker.data.REPEAT_WEEKDAYS
import com.rork.mindsetframestracker.data.REPEAT_WEEKENDS
import com.rork.mindsetframestracker.data.HabitRepeat
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.isScreenTimeHabit
import com.rork.mindsetframestracker.data.subscriptionTier
import com.rork.mindsetframestracker.integrations.TrackerConnections
import com.rork.mindsetframestracker.integrations.TrackerState
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.PolarClient
import com.rork.mindsetframestracker.integrations.ScreenTimeMonitor
import com.rork.mindsetframestracker.integrations.StravaAuthClient
import com.rork.mindsetframestracker.notifications.HabitAlarmScheduler
import com.rork.mindsetframestracker.notifications.HabitTimerRequests
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.MAX_HABIT_NAME_LENGTH
import com.rork.mindsetframestracker.ui.components.ActivitySource
import com.rork.mindsetframestracker.ui.components.ActivitySourcePickerSheet
import com.rork.mindsetframestracker.ui.components.TrackerConnectSheet
import com.rork.mindsetframestracker.ui.components.AlarmOccurrenceDetailDialog
import com.rork.mindsetframestracker.ui.components.HabitAlarmOverviewSection
import com.rork.mindsetframestracker.ui.components.HabitPickerGrid
import com.rork.mindsetframestracker.ui.components.IntegrationConsent
import com.rork.mindsetframestracker.ui.components.IntegrationConsentDialog
import com.rork.mindsetframestracker.ui.components.PremiumSheet
import com.rork.mindsetframestracker.ui.components.ScreenTimeHabitSheet
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
fun HabitsScreen(
    viewModel: AppViewModel,
    onOpenTimer: () -> Unit,
) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val hasAccess = data.settings.hasFeatureAccess()
    val currentTier = data.settings.subscriptionTier()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showTodoDialog by remember { mutableStateOf(false) }
    var showPremiumSheet by remember { mutableStateOf(false) }

    // ── Tracker connect pop-up ────────────────────────────────────────────────
    // "add a pop-up that lets the user connect external fitness trackers —
    // Strava, Google Health (Health Connect) and Polar". One sheet, all three,
    // with each provider's real state, reachable from the Habits header — where
    // a user who wants their walking tracked is already looking.
    var showTrackerSheet by remember { mutableStateOf(false) }
    // The provider currently mid-connect, so its row can show progress instead of
    // looking like the tap was ignored (the OAuth path resolves a client id over
    // the network before it can open the browser).
    var trackerBusyProvider by remember { mutableStateOf<TrackerProvider?>(null) }

    // Screen-time habit flow: privacy consent → app + limit picker → add.
    var showScreenTimeConsent by remember { mutableStateOf(false) }
    var showScreenTimeSheet by remember { mutableStateOf(false) }
    /**
     * The packages the screen-time sheet was SEEDED with when it opened.
     *
     * Captured at open time and held for the save, so "absent from the sheet's
     * result" can only mean "the user cleared it" for an app they were actually
     * shown. Without this the save compared the result against a fresh
     * computation of what *should* have been there, and anything the user had no
     * chance to see — a limit whose row had not rendered yet — was
     * indistinguishable from one they deliberately removed.
     */
    var screenTimeSheetPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Privacy consent gate for activity-source connects launched from the
    // picker sheet. Holds the pending connect action until the user agrees.
    var pendingSourceConsent by remember { mutableStateOf<IntegrationConsent?>(null) }
    var pendingSourceAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Alarm picker state: when the user taps any non-TodoList icon, we show
    // a time picker pre-filled with the icon's default alarm time.
    var alarmPickerIcon by remember { mutableStateOf<HabitIcon?>(null) }
    // Non-null when the picker was opened via "Set up alarm" on an existing
    // habit that has none — on confirm this UPDATES that habit's reminder
    // instead of creating a brand-new habit.
    var alarmSetupExistingHabitId by remember { mutableStateOf<String?>(null) }
    // "Even a single/double tap on an already-added habit deleted it
    // instantly" — tapping a selected tile used to call onHabitRemoved
    // directly with no confirmation at all. This holds the icon id pending
    // confirmation instead of deleting immediately.
    var pendingRemoveIconId by remember { mutableStateOf<String?>(null) }
    // "Automatic — no need to hunt for permissions in Settings" — set to
    // true right after any alarm is actually scheduled, only if something
    // is still missing. The dialog itself no-ops (never shown) once
    // everything's granted, so this never nags after the first fix.
    var showAlarmPermissionPrompt by remember { mutableStateOf(false) }

    // Activity source picker state: when the user taps an activity-trackable
    // icon, we store the newly created habit info here and show the sheet.
    var activityPickerHabitId by remember { mutableStateOf<String?>(null) }
    var activityPickerIconId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    /**
     * Every provider's resolved state, hoisted to the screen so the habit
     * dialog's tool rows and the connect sheet read from the **same** source.
     * Resolving it twice would let a row say "ready" while the sheet it opens
     * says "not available".
     *
     * Keyed on the three inputs `TrackerConnections.statuses` actually reads, so
     * a state change (a new connection, a permission grant) is reflected without
     * re-resolving on every unrelated recomposition of this screen — and so
     * quiet recompositions cannot be mistaken for a status change, which is what
     * would make a row flicker.
     */
    val allTrackerStatuses = remember(
        data.settings.healthConnectConnected,
        data.settings.polarAccessToken,
        data.settings.stravaRefreshToken,
        currentTier,
    ) { TrackerConnections.statuses(context, data.settings, currentTier) }

    // Which catalog icons already have a habit (so the grid can show the check badge).
    val selectedIconIds = remember(data.habits) {
        data.habits.mapNotNull { it.iconId }.toSet()
    }
    // Real reminder time per added habit (null = no alarm actually set),
    // so the grid can tell "no alarm" apart from "hasn't loaded yet".
    val reminderMinutesByIconId = remember(data.habits) {
        data.habits.mapNotNull { habit -> habit.iconId?.let { it to habit.reminderMinutes } }.toMap()
    }

    // ── Timer/stopwatch options, opened from the habit icon itself ──
    // The habit's alarm leaves a one-shot request (see [HabitTimerRequests]);
    // we read it and CONSUME it in the same breath. Consuming immediately is
    // what makes the options appear exactly once per ring: a recomposition, a
    // resume, a navigation or a reboot all find the request already gone.
    //
    // Keyed on a resume counter rather than `Unit` because the ringing screen
    // is a separate activity — when it finishes, this screen is merely
    // resumed, and a `Unit`-keyed effect would never re-run to notice.
    var pendingAutoSyncHabitId by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // NOTE: the timer/stopwatch options request is deliberately NOT read here.
    // It used to be consumed on this screen as well as at the app root, so
    // whichever happened to resume first won — a race that made the popup
    // appear from one place or the other depending on timing. Exactly one
    // composable owns it now: HabitTimerOptionsHost in AppNavigation, which
    // sits above the NavHost and is therefore reachable from every screen.
    LaunchedEffect(resumeTick) {
        pendingAutoSyncHabitId = HabitTimerRequests.peekAutoSync(context)
    }

    // ── Auto-sync: pull the habit's activity from the connected app ──
    // A finished timer/stopwatch leaves the habit id behind. We clear the flag
    // only once the sync has actually been dispatched, so an empty first
    // composition (habits still loading) cannot silently swallow it.
    LaunchedEffect(pendingAutoSyncHabitId, data.habits) {
        val habitId = pendingAutoSyncHabitId ?: return@LaunchedEffect
        val habit = data.habits.firstOrNull { it.id == habitId } ?: return@LaunchedEffect
        val iconId = habit.iconId ?: return@LaunchedEffect

        if (!isActivityTrackableIcon(iconId)) {
            // Nothing to pull for a habit with no activity source — clear it
            // rather than leave a flag that can never be satisfied.
            HabitTimerRequests.consumeAutoSync(context)
            pendingAutoSyncHabitId = null
            return@LaunchedEffect
        }

        val activityType = stravaActivityTypeFor(iconId)
        val synced = if (viewModel.isStravaConnected()) {
            viewModel.syncStravaActivities(habitId, activityType); true
        } else if (data.settings.healthConnectConnected) {
            viewModel.syncHealthConnectToHabit(habitId, activityType); true
        } else if (viewModel.isPolarConnected()) {
            viewModel.syncPolarToHabit(habitId, activityType); true
        } else {
            false
        }

        HabitTimerRequests.consumeAutoSync(context)
        pendingAutoSyncHabitId = null
        if (synced) {
            scope.launch {
                snackbarHostState.showSnackbar("Synced today's activity for ${habit.name}")
            }
        }
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
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
                    // A plain entry point to the pop-up, so connecting a tracker
                    // does not require knowing it lives under Settings.
                    TextButton(
                        onClick = { showTrackerSheet = true },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Connect fitness trackers")
                    }
                }
            },
            onIconTapped = { icon ->
                // Check free-tier cap before showing the time picker.
                if (viewModel.canAddHabit()) {
                    if (icon.isScreenTime) {
                        // The packages the sheet is about to show, captured
                        // BEFORE it opens so the save knows what the user was
                        // actually given the chance to remove.
                        screenTimeSheetPackages = data.habits
                            .filter { it.isScreenTimeHabit }
                            .mapNotNull { it.monitoredPackage }
                            .toSet()
                        // Screen-time habits go through the privacy consent +
                        // app-picker flow instead of the plain alarm picker.
                        showScreenTimeConsent = true
                    } else {
                        alarmPickerIcon = icon
                    }
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
                // No longer deletes on the spot — a stray tap (or a fast
                // double-tap) on an already-added tile must not be able to
                // silently wipe out a habit and its whole history. Just
                // arms the confirmation dialog below.
                pendingRemoveIconId = iconId
            },
            onTodoListTapped = {
                if (viewModel.canAddHabit()) showTodoDialog = true
                else showPremiumSheet = true
            },
            reminderMinutesByIconId = reminderMinutesByIconId,
            onSetupAlarmTapped = { icon ->
                val existing = data.habits.firstOrNull { it.iconId == icon.id }
                if (existing != null) {
                    alarmSetupExistingHabitId = existing.id
                    alarmPickerIcon = icon
                }
            },
        )
    }

    // ── Alarm time picker for any habit icon ───────────────────────────
    // Shown when the user taps any non-TodoList icon. Pre-filled with the
    // icon's default alarm time so they can customise it before adding.
    if (alarmPickerIcon != null) {
        val icon = alarmPickerIcon!!
        // The habit this icon maps to, when one exists — used to pre-seed the
        // editor with the schedule already set. Resolved once here rather than
        // inside the dialog so the dialog stays a pure function of its inputs.
        val existingForIcon = data.habits.firstOrNull { it.iconId == icon.id }
        AlarmPickerDialog(
            habitName = icon.label,
            habitIconId = icon.id,
            defaultMinutes = icon.defaultReminderMinutes,
            // The habit being re-edited, or null for a first tap on a fresh
            // icon. Passed whole rather than pre-split into times/mask/message
            // because the seed rule turns on the distinction between "no habit
            // yet" and "a habit that currently has no alarm" — states those
            // three split values collapse into the same defaults.
            existingHabit = existingForIcon,
            // ── Today's alarm history ────────────────────────────────────
            // Derived from the persisted events, one slot per scheduled time, so
            // a habit ringing at 07:00, 12:00 and 18:00 shows all three rather
            // than one collapsed entry. Empty for a habit that does not exist yet
            // (first tap on an icon) — there is no occurrence to have happened.
            todayAlarmSlots = existingForIcon
                ?.let { habit -> HabitAlarmHistory.daySlots(data, habit.id) }
                .orEmpty(),
            // The dialog starts the habit's own tool. Handed the timer service
            // directly, exactly as the tracking sheet does, so a running clock
            // outlives the dialog (backgrounded app, locked screen, killed
            // process) and its completion records itself through the normal
            // once-only funnel rather than being written here.
            onStartTimer = { kind, targetSeconds ->
                val habit = existingForIcon
                if (habit != null) {
                    TimerController.start(
                        context = context,
                        kind = kind,
                        targetSeconds = targetSeconds,
                        label = habit.name,
                        habitId = habit.id,
                    )
                    scope.launch {
                        snackbarHostState.showSnackbar("Timer started for ${habit.name}")
                    }
                }
            },
            // Opens the same connect pop-up the Habits screen's tracker button
            // opens, so the dialog's provider rows are a real entry point rather
            // than a status list with nowhere to go.
            onOpenTracker = { showTrackerSheet = true },
            trackerStatuses = allTrackerStatuses,
            onDismiss = {
                alarmPickerIcon = null
                alarmSetupExistingHabitId = null
            },
            onConfirm = onConfirm@{ times, repeatMask, alarmMessage ->
                alarmPickerIcon = null
                val existingHabitId = alarmSetupExistingHabitId
                alarmSetupExistingHabitId = null

                if (existingHabitId != null) {
                    // "Set up alarm" / re-edit path — update the existing
                    // habit in place. No permission prompt is shown here
                    // anymore: if the user is re-editing an alarm, they've
                    // already been through that dialog once (when the alarm
                    // was first created), and re-showing it on every single
                    // edit was the extra popup being flagged. Permission
                    // gaps are still checked and surfaced the first time an
                    // alarm is created, and any time from the Settings tab.
                    viewModel.setHabitAlarmTimes(existingHabitId, times, repeatMask, alarmMessage)
                    val updated = data.habits.firstOrNull { it.id == existingHabitId }
                        ?.withAlarmTimes(times)
                        ?.copy(repeatDaysMask = repeatMask)
                    if (updated != null) {
                        // schedule() arms one alarm per time (and clears the
                        // pre-multi-alarm entry), cancel() clears all of them —
                        // so switching a 3-alarm habit back to "no alarm" really
                        // does quiet all three rather than leaving two ringing.
                        if (times.isNotEmpty()) {
                            HabitAlarmScheduler.schedule(context, updated)
                        } else {
                            HabitAlarmScheduler.cancel(context, updated)
                        }
                    }
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (times.isNotEmpty())
                                "Alarm set — ${formatRepeat(repeatMask)} at ${formatAlarmTimes(times)}"
                            else
                                "Alarm removed for this habit.",
                        )
                    }
                    return@onConfirm
                }

                val habit = Habit(
                    id = UUID.randomUUID().toString(),
                    name = icon.label,
                    createdAt = System.currentTimeMillis(),
                    // Both fields set from the same source so the list and the
                    // legacy primary time can never disagree.
                    reminderMinutes = times.firstOrNull(),
                    alarmTimes = times,
                    iconId = icon.id,
                    repeatDaysMask = repeatMask,
                )
                if (viewModel.addHabitObject(habit, alarmMessage)) {
                    if (times.isNotEmpty()) {
                        // Armed with the message resolved back into it, because
                        // that is the object the ring path and the ringing screen
                        // read the habit's identity from.
                        HabitAlarmScheduler.schedule(context, habit.withAlarmMessage(alarmMessage))
                        if (AlarmPermissions.needsAttention(context)) showAlarmPermissionPrompt = true
                    }
                    viewModel.queueSync()
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (times.isNotEmpty())
                                "Added ${habit.name} — ${formatRepeat(repeatMask)} at ${formatAlarmTimes(times)}"
                            else
                                "Added ${habit.name} — no alarm. Tap it anytime to add one.",
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

    // ── Remove-habit confirmation ───────────────────────────────────────
    // Deleting a habit also wipes its check-in history and (via
    // queueHabitDeletion) removes it from the cloud — a destructive,
    // unrecoverable action, so it now always requires an explicit tap on
    // "Remove" rather than firing straight off a tap on the card.
    if (pendingRemoveIconId != null) {
        val iconId = pendingRemoveIconId!!
        val existing = data.habits.firstOrNull { it.iconId == iconId }
        AlertDialog(
            onDismissRequest = { pendingRemoveIconId = null },
            title = { Text("Remove ${existing?.name ?: "this habit"}?") },
            text = { Text("This deletes its check-in history too, on this device and in the cloud. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRemoveIconId = null
                        if (existing != null) {
                            HabitAlarmScheduler.cancel(context, existing)
                            viewModel.deleteHabit(existing.id)
                            scope.launch { snackbarHostState.showSnackbar("Removed ${existing.name}") }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveIconId = null }) { Text("Cancel") }
            },
        )
    }

    // ── Automatic alarm-permission prompt ───────────────────────────────
    // Pops up right after an alarm is set, only if something's missing —
    // replaces the old standalone Settings card entirely.
    if (showAlarmPermissionPrompt) {
        AlarmPermissionPromptDialog(onDismiss = { showAlarmPermissionPrompt = false })
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
                        } else if (StravaAuthClient.canAttemptConnect) {
                            // Privacy consent BEFORE the OAuth page opens.
                            pendingSourceConsent = IntegrationConsent.STRAVA
                            pendingSourceAction = { viewModel.connectStrava() }
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
                            // Privacy consent BEFORE the permission dialog.
                            pendingSourceConsent = IntegrationConsent.HEALTH_CONNECT
                            pendingSourceAction = { viewModel.requestHealthConnectPermissions() }
                        }
                    }

                    ActivitySource.POLAR -> {
                        if (viewModel.isPolarConnected()) {
                            val activityType = stravaActivityTypeFor(iconId)
                            viewModel.syncPolarToHabit(habitId, activityType)
                            scope.launch {
                                snackbarHostState.showSnackbar("Syncing from Polar...")
                            }
                        } else if (PolarClient.canAttemptConnect) {
                            // Privacy consent BEFORE the OAuth page opens.
                            pendingSourceConsent = IntegrationConsent.POLAR
                            pendingSourceAction = { viewModel.connectPolar() }
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

    // ── Tracker connect pop-up ───────────────────────────────────────────────
    if (showTrackerSheet) {
        val trackerStatuses = allTrackerStatuses
        val connectedWithAuto = trackerStatuses.filter { it.isConnected }
        // One switch for the whole set: on means every connected provider sweeps
        // into the activity habits on app open. Presented as a single decision
        // because that is how the user thinks about it — they connected a tracker
        // *so that* the habit keeps itself up to date.
        val autoTrackOn = connectedWithAuto.isNotEmpty() && connectedWithAuto.all { it.autoSync }
        val trackerMessage by viewModel.stravaMessage.collectAsStateWithLifecycle()

        // Clears the in-flight spinner on whichever happens first: a provider's
        // connected state actually changing, or a status message arriving. Either
        // means the attempt has resolved, so the row can never spin forever.
        LaunchedEffect(
            data.settings.healthConnectConnected,
            data.settings.polarAccessToken,
            data.settings.stravaRefreshToken,
            trackerMessage,
        ) {
            trackerBusyProvider = null
        }

        TrackerConnectSheet(
            statuses = trackerStatuses,
            autoTrack = autoTrackOn,
            busyProvider = trackerBusyProvider,
            message = trackerMessage,
            onAutoTrackChange = { enabled ->
                // Applies to every CONNECTED provider: turning this off must
                // actually stop the syncing, and a provider that isn't connected
                // has nothing to switch.
                connectedWithAuto.forEach { status ->
                    when (status.provider) {
                        TrackerProvider.HEALTH_CONNECT -> viewModel.setHealthConnectAutoSync(enabled)
                        TrackerProvider.POLAR -> viewModel.setPolarAutoSync(enabled)
                        TrackerProvider.STRAVA -> viewModel.setStravaAutoSync(enabled)
                    }
                }
            },
            onConnect = { provider ->
                // Screenshot defect 4: the tier-locked row was DRAWN as locked
                // (lock icon, "Upgrade" label) and `isActionable` includes LOCKED
                // — but this handler branched straight on the provider and ran
                // the real OAuth flow for Strava regardless. So the lock icon and
                // the "Upgrade" label were decoration: tapping them opened
                // Strava's authorisation page for a feature the account had not
                // paid for. The locked state now routes to the upgrade sheet, so
                // the label and the behaviour finally agree.
                if (trackerStatuses.firstOrNull { it.provider == provider }?.state == TrackerState.LOCKED) {
                    showTrackerSheet = false
                    showPremiumSheet = true
                } else {
                    trackerBusyProvider = provider
                    when (provider) {
                        // Privacy consent first — before the OAuth page or the system
                        // permission dialog opens. Required by AppGallery review and
                        // GDPR Art. 13, and it is also just the honest order.
                        TrackerProvider.HEALTH_CONNECT -> {
                            pendingSourceConsent = IntegrationConsent.HEALTH_CONNECT
                            pendingSourceAction = { viewModel.requestHealthConnectPermissions() }
                        }
                        TrackerProvider.POLAR -> {
                            pendingSourceConsent = IntegrationConsent.POLAR
                            pendingSourceAction = { viewModel.connectPolar() }
                        }
                        TrackerProvider.STRAVA -> {
                            pendingSourceConsent = IntegrationConsent.STRAVA
                            pendingSourceAction = { viewModel.connectStrava() }
                        }
                    }
                }
            },
            onDisconnect = { provider ->
                when (provider) {
                    TrackerProvider.HEALTH_CONNECT -> viewModel.disconnectHealthConnect()
                    TrackerProvider.POLAR -> viewModel.disconnectPolar()
                    TrackerProvider.STRAVA -> viewModel.disconnectStrava()
                }
            },
            onDismiss = { showTrackerSheet = false },
        )
    }

    // ── Privacy consent gate for activity-source connects ──
    if (pendingSourceConsent != null) {
        IntegrationConsentDialog(
            consent = pendingSourceConsent!!,
            onAgree = {
                val action = pendingSourceAction
                pendingSourceConsent = null
                pendingSourceAction = null
                action?.invoke()
            },
            onDismiss = {
                pendingSourceConsent = null
                pendingSourceAction = null
            },
        )
    }

    // ── Screen-time habit: consent → app picker → add ──
    if (showScreenTimeConsent) {
        IntegrationConsentDialog(
            consent = IntegrationConsent.SCREEN_TIME,
            onAgree = {
                showScreenTimeConsent = false
                showScreenTimeSheet = true
            },
            onDismiss = { showScreenTimeConsent = false },
        )
    }
    if (showScreenTimeSheet) {
        ScreenTimeHabitSheet(
            habits = data.habits,
            onDismiss = { showScreenTimeSheet = false },
            onSave = { limits ->
                showScreenTimeSheet = false
                // The picker returns the COMPLETE desired set, so a save both
                // adds new limits and drops cleared ones — the ViewModel
                // reconciles. The free-tier cap is checked here rather than in
                // the sheet: the sheet has no business knowing about tiers, and
                // a partial save would silently lose limits the user chose.
                val existingCount = data.habits.count { it.isScreenTimeHabit }
                val additions = limits.count { want ->
                    data.habits.none { it.monitoredPackage == want.packageName }
                }
                if (!viewModel.canAddHabit() && existingCount + additions > existingCount) {
                    showPremiumSheet = true
                    return@ScreenTimeHabitSheet
                }
                // Only an app the sheet was seeded with can be read as removed.
                // Anything else absent from the result is a limit the user never
                // saw, and calling that a deletion is what wiped habits.
                val total = viewModel.applyScreenTimeLimits(
                    limits = limits,
                    removablePackages = screenTimeSheetPackages,
                )
                if (!ScreenTimeMonitor.hasPermission(context)) {
                    // Send the user to the system Usage Access switch —
                    // Android never auto-grants this special permission.
                    runCatching {
                        context.startActivity(ScreenTimeMonitor.buildSettingsIntent(context))
                    }
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Saved $total app limit${if (total == 1) "" else "s"} — " +
                                "turn on Usage access to start measuring screen time.",
                        )
                    }
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (total == 0) "Screen-time limits cleared."
                            else "$total app limit${if (total == 1) "" else "s"} saved.",
                        )
                    }
                }
            },
        )
    }

    // ── To-Do List creation dialog ──
    if (showTodoDialog) {
        TodoListDialog(
            onDismiss = { showTodoDialog = false },
            onConfirm = { name, reminderMinutes, repeatMask, alarmMessage ->
                val habit = Habit(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    createdAt = System.currentTimeMillis(),
                    reminderMinutes = reminderMinutes,
                    iconId = "todoList",
                    repeatDaysMask = repeatMask,
                )
                if (viewModel.addHabitObject(habit, alarmMessage)) {
                    // ── ARM THE ALARM (only if one was actually set) ──
                    if (reminderMinutes != null) {
                        HabitAlarmScheduler.schedule(context, habit)
                        if (AlarmPermissions.needsAttention(context)) showAlarmPermissionPrompt = true
                    }

                    // ── SYNC TO CLOUD ──
                    viewModel.queueSync()

                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (reminderMinutes != null)
                                "Added ${habit.name} — alarm ${formatRepeat(repeatMask)} at ${formatAlarmTime(reminderMinutes)}"
                            else
                                "Added ${habit.name} — no alarm. Tap it anytime to add one.",
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
            foundingEligibility = { viewModel.checkFoundingMemberEligibility() },
        )
    }

    // ── The timer / stopwatch choice, anchored to its habit icon ──
    // Opened only from the habit's own alarm (or its alarm dialog). The sheet
    // names the habit and draws its catalog artwork, so the choice always
    // reads as belonging to the icon the user was looking at.
    // The timer/stopwatch choice is owned by HabitTimerOptionsHost at the app
    // root (see AppNavigation), so it is not rendered here — one owner, so the
    // sheet can never be raised twice for the same ring.
}

// ── Alarm time formatting ───────────────────────────────────────────────────

/** "2h" / "1h 30m" / "45m" formatting for a screen-time limit. */
private fun formatLimitLabel(minutes: Int): String = when {
    minutes % 60 == 0 && minutes >= 60 -> "${minutes / 60}h"
    minutes > 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}

/** Human summary of a repeat mask for snackbars. */
private fun formatRepeat(mask: Int): String = HabitRepeat.describe(mask)

/**
 * "Mon 07:00" — when a schedule next rings, for the alarm dialog's summary.
 *
 * Null renders as an explicit "not scheduled" rather than a blank, because the
 * case it represents is important: a one-shot alarm whose time has already gone
 * by will never fire again, and silently showing nothing there is how the user
 * ends up believing an alarm is set when it is not.
 */
private fun formatNextRing(next: java.time.LocalDateTime?): String {
    if (next == null) return "not scheduled"
    val day = next.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$day ${String.format(java.util.Locale.US, "%02d:%02d", next.hour, next.minute)}"
}

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
    /** The habit's catalog icon id, which selects its curated message pack. */
    habitIconId: String? = null,
    /**
     * The habit being re-edited, or null when this is a first tap on a fresh
     * icon.
     *
     * Passed as the whole habit rather than as pre-split `initialTimes` /
     * `initialRepeatMask` / `initialMessage` values because the seed rule turns
     * on the *distinction* between "no habit yet" and "a habit that currently
     * has no alarm" — two states those three defaults collapse into one value.
     */
    existingHabit: Habit? = null,
    /**
     * Every one of this habit's alarms for today, in chronological order — one
     * entry per scheduled time, from [HabitAlarmHistory.daySlots].
     *
     * Passed in rather than read here so the dialog stays a pure function of its
     * inputs (the same reason `initialTimes` is a parameter): it renders what the
     * caller resolved, and the caller is the one that holds the loaded
     * [com.rork.mindsetframestracker.data.AppData].
     */
    todayAlarmSlots: List<AlarmDaySlot> = emptyList(),
    /**
     * Starts this habit's own activity tool (stopwatch or timer).
     *
     * Defaulted to a no-op so the dialog still composes in a preview or a
     * caller that has no timer host — but the Habits screen always passes a
     * real one, because it *is* the host (see the sheet's `onStartTimed`).
     */
    onStartTimer: (kind: com.rork.mindsetframestracker.data.TimerKind, targetSeconds: Int) -> Unit = { _, _ -> },
    /**
     * Opens the tracker connect/disconnect pop-up from this dialog's tracker rows.
     *
     * Separate from [onStartTimer] because they are different actions on
     * different things: one starts a local clock, the other opens a connection
     * flow. Sharing one callback is what made a tracker row start a stopwatch,
     * which is the bug this parameter exists to fix.
     *
     * Takes **no provider argument** on purpose. The connect pop-up lists all
     * three providers and is not opened "for" a particular one, so a provider
     * parameter would be a value every caller ignores — and the row carrying it
     * would look like it did something provider-specific when it does not.
     */
    onOpenTracker: (() -> Unit)? = null,
    /** Each provider's resolved state, so a row never offers a connect that cannot succeed. */
    trackerStatuses: List<com.rork.mindsetframestracker.integrations.TrackerStatus> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (times: List<Int>, repeatMask: Int, alarmMessage: String) -> Unit,
) {
    val s = appStrings()
    val timeState = rememberTimePickerState(
        initialHour = defaultMinutes / 60,
        initialMinute = defaultMinutes % 60,
        is24Hour = false,
    )
    /**
     * The whole editable state — schedule, repeat mask and message — as ONE
     * value, seeded exactly once from [existingHabit] and never re-seeded.
     *
     * ## The bug this replaces
     *
     * This used to be three separate `remember` slots, with the schedule seeded
     * as `initialTimes.ifEmpty { listOf(defaultMinutes) }`. Because
     * `initialTimes` came from the habit's *saved* list, that `ifEmpty` could not
     * tell two different situations apart:
     *
     *  * a habit with no alarm yet, where the icon's default time is a helpful
     *    starting point; and
     *  * a habit whose alarm the user just removed — whose saved list is *also*
     *    empty, so the default went straight back in.
     *
     * And `remember` was keyed on nothing, inside a dialog constructed by an `if`
     * in a screen that recomposes on every tick of the app's data (`data.habits`
     * is read above for the picker's own pre-fill), so any recomposition that
     * discarded and recreated the slot re-ran the seed. That is exactly the
     * reported "every time I remove it, the actual time defaults back in there".
     *
     * [HabitAlarmDraft] makes that distinction explicit and keeps it: an existing
     * habit's draft is `decided`, so no default can ever be injected into a
     * schedule the user has emptied, however many times this recomposes.
     */
    var draft by remember {
        mutableStateOf(
            existingHabit
                ?.let { habit ->
                    HabitAlarmDraft.forExistingHabit(
                        savedTimes = habit.alarmMinutes,
                        repeatDaysMask = habit.repeatDaysMask,
                        message = habit.alarmMessage.orEmpty(),
                    )
                }
                ?: HabitAlarmDraft.forNewHabit(),
        )
    }
    /**
     * The draft with the icon's default applied if — and only if — the user has
     * not decided the schedule yet.
     *
     * Every view below reads from this rather than from [draft] directly, so the
     * default is applied in exactly one place and the confirm button can never
     * disagree with the schedule the user is looking at. Idempotent by design
     * (applying it does not mark the draft decided), which is what makes it safe
     * to call on every frame.
     */
    val effectiveDraft = draft.withDefaultIfUntouched(defaultMinutes)
    val times = effectiveDraft.times
    val repeatMask = effectiveDraft.repeatDaysMask
    val alarmMessage = effectiveDraft.message
    // ── The habit's motivational reminder line ──────────────────────────────
    // The habit's motivational reminder line lives on `draft` above, so that a
    // message edit and a schedule edit cannot be saved from different states.
    /**
     * Which occurrence's detail sheet is open, if any.
     *
     * Held on the dialog rather than inside [AlarmHistorySection] so the sheet
     * survives the section recomposing — a nested holder would drop the
     * selection on the next keystroke in the message field.
     */
    var detailSlot by remember { mutableStateOf<AlarmDaySlot?>(null) }
    // The user's own words when they wrote some, otherwise the curated line for
    // this habit — so the live preview below always shows exactly what the
    // alarm will say, including when the user has overridden it.
    val previewLine = MotivationalMessages.lineFor(
        habitId = "preview",
        iconId = habitIconId,
        dayKey = Dates.todayKey(),
        customMessage = alarmMessage,
    )
    val messageSuggestions = remember(habitIconId) { MotivationalMessages.itemsFor(habitIconId) }
    // The schedule comes from `draft` above — deliberately NOT seeded here. See
    // the note on the draft declaration for why an `ifEmpty { default }` at this
    // spot is what made a removed time come back.
    val pendingMinutes = timeState.hour * 60 + timeState.minute
    val alreadyAdded = pendingMinutes in times

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set alarm for $habitName") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    // The schedule, the message editor, the new history section and
                    // the time picker together exceed a short screen, and the
                    // dialog has no scroll of its own — without this the time
                    // picker and the Save button become unreachable on a small
                    // device, which is a functional loss, not a cosmetic one.
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Add as many times as you like — you can always change them later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // ── The schedule so far ────────────────────────────────
                // Stacked rather than a scrolling row: with three or four times
                // a wrapped column is easier to read than a horizontal scroller,
                // and each chip is its own removable target the user can hit
                // without precision.
                if (times.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        times.forEach { minutes ->
                            InputChip(
                                selected = false,
                                // Removal edits the DRAFT, which marks it decided —
                                // so this removal cannot be undone by the default
                                // re-seeding itself on the next recomposition.
                                onClick = { draft = draft.removeTime(minutes) },
                                label = { Text(formatAlarmTime(minutes)) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Remove ${formatAlarmTime(minutes)}",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                        // Clearing the whole schedule in one tap, which is what "I
                        // don't want an alarm on this habit any more" usually
                        // means. Only shown for two or more times: with one time
                        // the chip's own ✕ already is this action, and two
                        // controls doing the same thing is how a user clears the
                        // list and then wonders which one they pressed.
                        if (times.size > 1) {
                            TextButton(onClick = { draft = draft.clearTimes() }) {
                                Text("Remove all alarm times")
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No alarms set for this habit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                TimePicker(state = timeState)
                Spacer(Modifier.height(4.dp))

                // Adding is explicit and disabled while the selected time is
                // already in the list, so the schedule can never accumulate
                // duplicates — which would arm two alarms for one time and ring
                // the same reminder twice.
                TextButton(
                    enabled = !alreadyAdded,
                    onClick = { draft = draft.addTime(pendingMinutes) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (alreadyAdded) {
                            "${formatAlarmTime(pendingMinutes)} is already set"
                        } else {
                            "Add ${formatAlarmTime(pendingMinutes)}"
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))
                RepeatSelector(mask = repeatMask, onMaskChange = { draft = draft.withRepeatMask(it) })
                Spacer(Modifier.height(12.dp))
                MotivationalMessageEditor(
                    habitLabel = habitName,
                    value = alarmMessage,
                    onValueChange = { draft = draft.withMessage(it) },
                    previewLine = previewLine,
                    suggestions = messageSuggestions,
                )
                Spacer(Modifier.height(12.dp))
                // ── Today's alarms ──────────────────────────────────────────
                // Rendered for the habit's own schedule, so every time it has
                // ever been set to is represented — the fired ones, the answered
                // ones, and the ones still to come.
                HabitAlarmOverviewSection(
                    title = s.habitsAlarmHistoryTitle,
                    slots = todayAlarmSlots,
                    // The habit's current setup, described from the SAVED habit so
                    // the block is a stable reference while the user edits the
                    // schedule above it. Null on a first tap, where there is no
                    // setup to describe yet.
                    plan = existingHabit?.let { HabitAlarmSetup.of(it) },
                    trackerProviders = TrackerConnections.sourcesFor(habitIconId),
                    onOpenTracker = onOpenTracker,
                    onSelect = { detailSlot = it },
                )
                // ── The habit's activity tools ────────────────────────────────
                // A walk measured by a stopwatch, or kept up by Strava / Health
                // Connect. Rendered inside the alarm editor because this dialog
                // IS the habit's dialog — tapping an added habit opens it — so
                // this is where a user looking for "the stopwatch for my walk"
                // actually arrives. It is also the second half of the flow the
                // request describes: the alarm notifies, and then the tool is
                // started from the same place the alarm was configured.
                // Bound to a local first: reading `existingHabit` directly
                // inside the lambda would not smart-cast it to non-null (it is a
                // captured `val` from another scope), so the compiler would reject
                // the `.trackingModeOrDefault` access even under the null check.
                val setupHabit = existingHabit
                if (setupHabit != null) {
                    Spacer(Modifier.height(12.dp))
                    HabitActivityToolsRow(
                        trackingMode = setupHabit.trackingModeOrDefault,
                        targetSeconds = setupHabit.trackingTargetSecondsOrDefault,
                        trackerProviders = TrackerConnections.sourcesFor(habitIconId),
                        onStartTool = {
                            onStartTimer(
                                if (setupHabit.trackingModeOrDefault == HabitTrackingMode.TIMER)
                                    TimerKind.TIMER else TimerKind.STOPWATCH,
                                setupHabit.trackingTargetSecondsOrDefault,
                            )
                        },
                        // The fix for the reported bug: the tracker rows used to
                        // be handed `onStartTool`, so "Strava — ready to import"
                        // started a count-up clock instead of connecting. They now
                        // open the connect pop-up, and carry the real statuses so a
                        // provider this build cannot use is not drawn as tappable.
                        onOpenTracker = onOpenTracker,
                        trackerStatuses = trackerStatuses,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val nextRingAt = HabitRepeat.nextOfAll(
                    times = times,
                    repeatDaysMask = repeatMask,
                    now = java.time.LocalDateTime.now(),
                    zone = java.time.ZoneId.systemDefault(),
                )
                Text(
                    text = when {
                        times.isEmpty() -> "No alarm"
                        nextRingAt == null ->
                            "Alarm ${formatAlarmTimes(times)} \u00b7 ${formatRepeat(repeatMask)} \u2014 " +
                                "its time has already passed, so it won't ring"
                        else ->
                            "Alarm ${formatAlarmTimes(times)} \u00b7 ${formatRepeat(repeatMask)} " +
                                "\u00b7 next ${formatNextRing(nextRingAt)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                // "I want to remove setup because I didn't setup all of
                // them alarm" — not every habit needs a reminder. This lets
                // the user add the habit with no alarm at all instead of
                // being forced to pick a time for every single one.
                TextButton(
                    onClick = { onConfirm(emptyList(), repeatMask, alarmMessage) },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Skip — no alarm for this habit") }
            }
        },
        confirmButton = {
            Button(
                // Deliberately NOT gated on `times.isNotEmpty()`. Saving an EMPTY
                // schedule is a real, supported outcome — it is how the user
                // removes a habit's alarm — and the old `enabled = times.isNotEmpty()`
                // made that unsavable, which is half of why removals never stuck.
                onClick = { onConfirm(times, repeatMask, alarmMessage) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text(
                    when {
                        times.size > 1 -> "Save ${times.size} alarms"
                        times.size == 1 -> "Save alarm"
                        else -> "Save — no alarm"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    // ── One alarm's detail ──────────────────────────────────────────────
    // A second dialog layered over this one rather than a navigation route: the
    // editor stays exactly as the user left it (their unsaved schedule and
    // message included), which is what "tapping an entry shows the alarm detail"
    // should feel like. Dismissing returns them to the editor they came from.
    detailSlot?.let { slot ->
        AlarmOccurrenceDetailDialog(slot = slot, onDismiss = { detailSlot = null })
    }
}

/**
 * The motivational message editor shared by both habit-creation dialogs.
 *
 * ## What it is for
 *
 * The default reminder is now an encouraging line rather than a bare alert, so
 * this is where the user overrides it — or picks one of the suggestions — to make
 * the alarm sound like something they would actually say to themselves. The
 * water case the feature was built around ("It's time to water up! 💧 Stay
 * hydrated, you've got this!") is the suggestion row's first entry for a water
 * habit.
 *
 * ## Why the preview is the real sentence, not a mock
 *
 * [previewLine] is produced by the same [MotivationalMessages.lineFor] the alarm
 * receiver uses, so what the user reads here is exactly what will land in their
 * notification shade — including the empty-field case, where it correctly shows
 * the curated pack line rather than a blank.
 *
 * ## Bounded on input
 *
 * The field trims to [MotivationalMessages.MAX_MESSAGE_LENGTH] as the user types.
 * The limit is enforced again on write and again at ring time, but catching it
 * here is what stops the user from composing a line they cannot save.
 */
@Composable
private fun MotivationalMessageEditor(
    habitLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    previewLine: String,
    suggestions: List<MotivationalMessages.MessagePack>,
) {
    // The editor's own labels come from the string table (see
    // assets/strings/*.json) so they translate with the rest of the app. Only
    // the SUGGESTION values stay in Kotlin: those are content — the lines the
    // user is picking between — and they deliberately fall back to the English
    // pack rather than being half-translated.
    val s = appStrings()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = s.habitsMotivationalTitle,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Text(
            text = s.habitsMotivationalHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.take(MotivationalMessages.MAX_MESSAGE_LENGTH)) },
            placeholder = { Text(s.habitsMotivationalPlaceholder) },
            label = { Text(s.habitsMotivationalLabel) },
            // Two lines is the useful ceiling for a notification preview; more
            // than that and the text is cut in the shade anyway.
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // One tap to adopt an encouraging line, so the feature is useful without
        // anyone having to write anything. Suggestions come from this habit's own
        // pack first (water → hydration lines), so the first chip is the one that
        // fits the habit rather than a generic platitude.
        Text(
            text = s.habitsMotivationalSuggestions.format(habitLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            suggestions.firstOrNull()?.lines.orEmpty().take(4).forEach { suggestion ->
                FilterChip(
                    selected = value.trim() == suggestion,
                    onClick = { onValueChange(suggestion) },
                    label = { Text(suggestion, maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = s.habitsMotivationalPreview.format(previewLine),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Repeat schedule selector — mirrors the system Clock app's "Repeat" row:
 * Once / Every day / Weekdays / Weekends presets plus per-day chips.
 *
 * ## Why the day chips can no longer silently disable the alarm
 *
 * These chips used to be bare toggles over the mask bits, so deselecting every
 * day produced mask `0` — which is [REPEAT_ONCE], "fire once, then disarm". The
 * user was never told that, and two things then went wrong invisibly: the
 * scheduler took its `REPEAT_ONCE || REPEAT_DAILY` shortcut and armed a *daily*
 * alarm anyway, so the day selection appeared to do nothing at all; and had that
 * shortcut not existed, the habit would quietly have become a single-use alarm.
 *
 * Fixed on both sides. [HabitRepeat] now owns the maths and special-cases no
 * mask, and this row refuses to *produce* mask 0 by accident — tapping the last
 * remaining day is declined with a hint, because "no day at all" is what the
 * explicit **Once** chip means, and it says so.
 *
 * The labels are three letters — `Mon`, never `M` — because single letters are
 * ambiguous: `T` and `S` each name two days, so the old row could not even be
 * read back to check what had been selected.
 */
@Composable
private fun RepeatSelector(mask: Int, onMaskChange: (Int) -> Unit) {
    // Set when the user tries to clear the final selected day. The refusal
    // itself is the behaviour; this only explains it.
    var lastDayRefused by remember { mutableStateOf(false) }
    val selectedDayCount = HabitRepeat.daysOf(mask).size

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Repeat",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            listOf(
                "Once" to REPEAT_ONCE,
                "Every day" to HabitRepeat.EVERY_DAY,
                "Weekdays" to REPEAT_WEEKDAYS,
                "Weekends" to REPEAT_WEEKENDS,
            ).forEach { (label, preset) ->
                FilterChip(
                    // Exact-match only, deliberately. The old `mask == preset`
                    // comparison was already exact, but the row sat next to a
                    // summary that described ANY 5-day mask as "weekdays" —
                    // which is how a custom Mon/Tue/Wed selection came to be
                    // reported as something it was not.
                    selected = mask == preset,
                    onClick = { onMaskChange(preset) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Per-day chips (Mon..Sun → bits 0..6).
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            HabitRepeat.DEFAULT_DAY_NAMES.forEachIndexed { index, label ->
                val bit = 1 shl index
                val selected = mask and bit != 0
                FilterChip(
                    selected = selected,
                    onClick = {
                        if (selected && selectedDayCount == 1) {
                            // Refused: clearing the last day would produce mask
                            // 0, which is REPEAT_ONCE. Turning a repeating alarm
                            // into a one-shot as a side effect of deselecting a
                            // day is never what the user meant.
                            lastDayRefused = true
                        } else {
                            lastDayRefused = false
                            onMaskChange(mask xor bit)
                        }
                    },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            // One place decides how a mask reads, so this summary and the
            // snackbar shown after saving can never describe the same schedule
            // differently.
            text = if (mask == REPEAT_ONCE) {
                "Fires once, then stops. Pick the days above to repeat."
            } else {
                "Rings ${HabitRepeat.describe(mask)}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lastDayRefused) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Keep at least one day selected \u2014 use \u201cOnce\u201d for a single alarm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The "To-Do List" creation dialog — lets the user name a custom habit
 * and pick the alarm time they want it to fire.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoListDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, reminderMinutes: Int?, repeatMask: Int, alarmMessage: String) -> Unit,
) {
    var name by remember { mutableStateOf("Custom habit") }
    var repeatMask by remember { mutableStateOf(REPEAT_DAILY) }
    // A custom habit is not a catalog icon, so it inherits no curated pack and
    // would otherwise have nothing to say at ring time beyond its own name. An
    // empty value still resolves to the general encouragement pack, so this is
    // genuinely optional — but the field is here because the user knows their own
    // reason for the habit better than the app does.
    var alarmMessage by remember { mutableStateOf("") }
    val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = false)
    val subtotalPreview = MotivationalMessages.lineFor(
        habitId = "preview",
        iconId = "todoList",
        dayKey = Dates.todayKey(),
        customMessage = alarmMessage,
    )

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
                    text = "Pick a time for a daily reminder alarm, or skip it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                MotivationalMessageEditor(
                    habitLabel = "this habit",
                    value = alarmMessage,
                    onValueChange = { alarmMessage = it },
                    previewLine = subtotalPreview,
                    suggestions = MotivationalMessages.itemsFor("todoList"),
                )
                Spacer(Modifier.height(12.dp))
                TimePicker(state = timeState)
                Spacer(Modifier.height(8.dp))
                RepeatSelector(mask = repeatMask, onMaskChange = { repeatMask = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Alarm: ${formatAlarmTime(timeState.hour * 60 + timeState.minute)} · ${formatRepeat(repeatMask)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Same "no alarm" escape hatch as the icon-picker flow — a
                // custom to-do habit shouldn't be forced to carry an alarm.
                TextButton(
                    onClick = { onConfirm(name.trim(), null, repeatMask, alarmMessage) },
                    enabled = name.trim().isNotEmpty(),
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Skip — no alarm for this habit") }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), timeState.hour * 60 + timeState.minute, repeatMask, alarmMessage) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Add with alarm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}