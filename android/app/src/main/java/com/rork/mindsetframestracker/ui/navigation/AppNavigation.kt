package com.rork.mindsetframestracker.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.activity.compose.rememberLauncherForActivityResult
import com.rork.mindsetframestracker.integrations.MindsetHealthConnectClient
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.HabitAlarmHistory
import com.rork.mindsetframestracker.data.HabitAlarmSetup
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.habitLogsFor
import com.rork.mindsetframestracker.data.trackingModeOrDefault
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.AuthPromptSheet
import com.rork.mindsetframestracker.data.TimerRepository
import com.rork.mindsetframestracker.data.TimerStatus
import com.rork.mindsetframestracker.notifications.HabitTimerRequests
import com.rork.mindsetframestracker.notifications.TimerController
import com.rork.mindsetframestracker.notifications.TimerService
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.ui.screens.TimerCompletionPopup
import com.rork.mindsetframestracker.ui.components.MinimizedSessionChip
import com.rork.mindsetframestracker.ui.components.SetNewPasswordSheet
import com.rork.mindsetframestracker.ui.components.SyncStatusBanner
import com.rork.mindsetframestracker.ui.components.moodBackdrop
import com.rork.mindsetframestracker.ui.screens.HabitsScreen
import com.rork.mindsetframestracker.ui.screens.HomeScreen
import com.rork.mindsetframestracker.ui.screens.InsightsScreen
import com.rork.mindsetframestracker.ui.screens.OnboardingScreen
import com.rork.mindsetframestracker.ui.screens.SettingsScreen
import com.rork.mindsetframestracker.ui.screens.SplashScreen
import com.rork.mindsetframestracker.ui.screens.TimerScreen
import com.rork.mindsetframestracker.ui.components.AlarmActionDialog
import com.rork.mindsetframestracker.ui.components.HabitTrackerConnectHost
import com.rork.mindsetframestracker.ui.components.stravaActivityTypeFor
import com.rork.mindsetframestracker.integrations.TrackerConnections
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.data.AlarmConnectPrompt
import com.rork.mindsetframestracker.data.AlarmDialogs
import com.rork.mindsetframestracker.data.AlarmRingGate
import com.rork.mindsetframestracker.data.subscriptionTier
import com.rork.mindsetframestracker.ui.screens.WeeklyScreen
import com.rork.mindsetframestracker.util.rememberIsBatteryLow
import com.rork.mindsetframestracker.util.rememberIsOnline
import com.rork.mindsetframestracker.ui.theme.Mf2Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement

private data class BottomDestination(
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination("home", Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
    BottomDestination("habits", Icons.Filled.Checklist, Icons.Outlined.Checklist),
    BottomDestination("weekly", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    BottomDestination("insights", Icons.Filled.Insights, Icons.Outlined.Insights),
    BottomDestination("settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

private val tabRoutes: List<String> = bottomDestinations.map { it.route }

/**
 * Direction of travel between two bottom-bar tabs: 1 = rightward,
 * -1 = leftward, 0 = not a tab-to-tab move (splash/onboarding/home).
 */
private fun tabDirection(from: String?, to: String?): Int {
    val fromIndex = tabRoutes.indexOf(from)
    val toIndex = tabRoutes.indexOf(to)
    if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return 0
    return if (toIndex > fromIndex) 1 else -1
}

/**
 * Tiny status cluster pinned to the corner of the bottom navigation bar.
 * Cloud icon: grey cloud-off when offline, tinted cloud when online.
 * Low Power: an amber battery-alert badge appears when the battery is
 * below 20% and not charging — background sync is paused to save energy.
 */
@Composable
private fun ConnectivityStatusIcon(
    isOnline: Boolean,
    isLowPower: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = when {
            isLowPower -> Mf2Palette.Warning
            isOnline -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> Mf2Palette.OnDarkMuted
        },
        label = "connectivityTint",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AnimatedVisibility(visible = isLowPower) {
            Icon(
                imageVector = Icons.Filled.BatteryAlert,
                contentDescription = "Low Power — sync paused below 20% battery to conserve energy",
                tint = Mf2Palette.Warning,
                modifier = Modifier.size(14.dp),
            )
        }
        Icon(
            imageVector = if (isOnline) Icons.Filled.Cloud else Icons.Filled.CloudOff,
            contentDescription = when {
                isLowPower -> "Sync paused — Low Power mode"
                isOnline -> "Online"
                else -> "Offline — data saved on this device"
            },
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Root-level owner of the dialog a **ringing habit alarm** raises.
 *
 * ## Why the dialog is hosted here and not in the ringing screen
 *
 * [com.rork.mindsetframestracker.notifications.AlarmRingingActivity] is launched
 * through the reminder's *full-screen intent*. From Android 14 (API 34)
 * `USE_FULL_SCREEN_INTENT` is revoked by default for apps whose primary purpose
 * isn't alarms/calls — it is granted on a *separate* "Special app access"
 * screen, not the permissions list. When it is missing,
 * [com.rork.mindsetframestracker.notifications.HabitCheckInNotifier] correctly
 * posts the notification **without** a full-screen intent, which means that
 * Activity never launches at all.
 *
 * Hosting the choice inside that screen therefore made it silently depend on a
 * grant the user had no reason to have given — the alarm rang, the
 * notification appeared, and the dialog never came up. Hosted here on the app
 * root, it appears on its own at the moment the alarm rings whenever the app is
 * in the foreground, and the one-shot request it reads survives a ring that
 * happens while the app is in the background — so the user still gets it the
 * moment they next open the app.
 *
 * ## The connect-fitness offer, decided once per ring
 *
 * This host no longer sends the user off to a separate "connect a tracker" step.
 * The offer is made **inside** [AlarmActionDialog], and only when a fitness app
 * can actually supply this habit — see [AlarmRingGate.shouldOfferConnect]. A
 * habit no fitness app can record (a journal entry, a vitamin) simply never gets
 * the offer, which is the request: *show the alarm, not the connect option*.
 * Deciding it costs no I/O on the ring path: the provider states come from the
 * already-loaded snapshot and the asked-before record is one SharedPreferences
 * lookup.
 *
 * ## Appearing exactly once per ring
 *
 * The request is written to disk when the ring happens (see
 * [HabitTimerRequests]) and **consumed here the instant the dialog is shown**,
 * so recomposition, resume, navigation, rotation and reboot all find nothing.
 * The dialog keeps rendering from the value already read, so this ring still
 * shows it exactly once.
 */
@Composable
private fun HabitTimerOptionsHost(
    viewModel: AppViewModel,
    onOpenTimerScreen: () -> Unit,
) {
    val context = LocalContext.current
    var request by remember { mutableStateOf<HabitTimerRequests.Request?>(null) }

    // A minimized sheet is restored from disk, not from memory: the point of
    // minimizing is that the user comes back later, which may be after the app
    // was backgrounded, rotated, or killed outright. `minimizeNonce` is bumped
    // when the user minimizes so the read re-runs on the same frame.
    var minimizeNonce by remember { mutableStateOf(0) }
    LaunchedEffect(minimizeNonce) {
        if (request == null) {
            request = runCatching { HabitTimerRequests.peekMinimized(context) }.getOrNull()
        }
    }

    // ── Why this polls instead of waiting for ON_RESUME ──
    // A one-shot request is written to disk by HabitReminderReceiver at the
    // instant the alarm reaches the user. This host has to notice it *without
    // anything else happening*, because the whole point is that the choice
    // appears on its own, the moment the alarm rings.
    //
    // A lifecycle-only trigger is not enough for that: when the alarm fires
    // while the app is already in the foreground *and* USE_FULL_SCREEN_INTENT
    // is not granted (the Android 14+ default), the ringing screen never
    // launches, so no pause/resume ever occurs and an ON_RESUME-driven host
    // would sit there having missed it entirely. It would also miss the
    // request if the user stayed put in the app.
    //
    // So the pending record is re-read on a short tick. This is not a poll of
    // anything expensive: SharedPreferences is an in-memory map after the first
    // read, so each pass is a map lookup — and it makes "the dialog appears when
    // the alarm rings" independent of which screen the user is on, whether the
    // app was backgrounded, and whether the full-screen grant exists.
    LaunchedEffect(Unit) {
        while (true) {
            val pending = HabitTimerRequests.peek(context)
            if (pending != null && pending.habitId != request?.habitId) {
                // Consumed in the same step it is detected, before the dialog is
                // even handed the value, so this ring shows it exactly once and
                // no later tick can resurrect it.
                HabitTimerRequests.consume(context)
                request = pending
            }
            delay(POPUP_POLL_MILLIS)
        }
    }

    val pending = request ?: return

    // Everything the ring dialog needs about the habit, resolved off the main
    // thread: decoding the app blob synchronously in composition is exactly the
    // main-thread stall that has to stay off the ring path. The dialog renders
    // its generic glyph for the first frame and fills in when this lands.
    var habit by remember(pending.habitId) {
        mutableStateOf<com.rork.mindsetframestracker.data.Habit?>(null)
    }
    LaunchedEffect(pending.habitId) {
        habit = withContext(Dispatchers.IO) {
            runCatching {
                MindsetRepository(context).load().habits
                    .firstOrNull { it.id == pending.habitId }
            }.getOrNull()
        }
    }

    // The habit's OWN tool. Recorded at ring time by HabitReminderReceiver and
    // authoritative; the fallbacks only cover a ring whose habit has since been
    // edited away. One-tap habits never reach this host at all — the receiver
    // does not raise a request for them — so this dialog is only ever shown for
    // a habit that genuinely needs an input.
    val mode = habit?.trackingModeOrDefault ?: pending.mode ?: HabitTrackingMode.CHECK
    val name = habit?.name?.takeIf { it.isNotBlank() } ?: pending.habitName.ifBlank { "Habit" }

    // The habit's own data, so the dialog shows what is already inside it
    // rather than asking the user to add blind: "3 of 8 glasses today" for
    // water, this week's entries for a journal. Read from the already-loaded
    // in-memory state rather than the repository, because this must not add
    // I/O to the ring path — the dialog's own recent-logs section renders it.
    val appData by viewModel.state.collectAsStateWithLifecycle()

    // ── The connect-fitness offer, decided once per ring ──────────────────
    // The offer is made INSIDE the dialog the alarm raises, only when a fitness
    // app can actually supply this habit. Everything the decision needs is
    // already in memory — the loaded snapshot for the provider states, one
    // SharedPreferences lookup for the asked-before record — so deciding it adds
    // no I/O to the ring path.
    var showTrackerConnect by remember { mutableStateOf(false) }
    val settings = appData.settings
    val trackerStatuses = remember(
        settings.healthConnectConnected,
        settings.polarAccessToken,
        settings.stravaRefreshToken,
    ) { TrackerConnections.statuses(context, settings, settings.subscriptionTier()) }
    val ringIconId = habit?.iconId ?: pending.iconId
    // The providers that can supply THIS habit — the habit's own rule for what it
    // may be offered, so a journal entry is never shown Strava.
    val trackerProviders = remember(ringIconId) { TrackerConnections.sourcesFor(ringIconId) }
    val connectOffer = remember(pending.habitId, ringIconId, trackerStatuses) {
        AlarmRingGate.shouldOfferConnect(
            sources = trackerProviders,
            statuses = trackerStatuses,
            alreadyAsked = AlarmConnectPrompt.hasAsked(context, pending.habitId),
        )
    }
    // Recorded the moment the offer is raised, not when it is answered: the offer
    // is a one-time choice, so connecting, tapping "Not now" and dismissing the
    // dialog all count as having been asked. Written in an effect rather than
    // during composition so the ring path never writes to disk on a mere
    // recomposition — only when the offer actually appears.
    LaunchedEffect(connectOffer, pending.habitId) {
        if (connectOffer) AlarmConnectPrompt.markAsked(context, pending.habitId)
    }

    AlarmActionDialog(
        habitName = name,
        habitIconId = ringIconId,
        trackingMode = mode,
        targetSeconds = habit?.trackingTargetSeconds ?: 0,
        targetCount = habit?.trackingTargetCount ?: 0,
        unit = habit?.trackingUnit.orEmpty(),
        recentLogs = appData.habitLogsFor(pending.habitId).take(3),
        // The ring path's view of the same day. Resolved from `appData` — the
        // already-loaded in-memory state — rather than from the repository, so
        // this adds no I/O to the ring path. Null setup when the habit could not
        // be resolved, which leaves the timeline alone rather than inventing a
        // configuration to describe.
        alarmSlots = HabitAlarmHistory.daySlots(appData, pending.habitId),
        alarmSetup = habit?.let { HabitAlarmSetup.of(it) },
        // The habit's own wording, so the dialog is written for THIS habit rather
        // than generically. `sourceNames` is what lets a trackable habit say
        // "Connect Strava and this walk records itself" while a journal entry gets
        // wording that never mentions a fitness app.
        copy = remember(name, ringIconId, mode, trackerProviders) {
            AlarmDialogs.forHabit(
                habitName = name,
                iconId = ringIconId,
                mode = mode,
                unit = habit?.trackingUnit.orEmpty(),
                sourceNames = trackerProviders.map { it.label },
            )
        },
        // False for a habit no fitness app can supply, one already connected, or
        // one already offered — see [AlarmRingGate].
        connectOffer = connectOffer,
        trackerProviders = trackerProviders,
        trackerStatuses = trackerStatuses,
        // Opens the full connect flow; it owns the consent gate and the tier
        // upsell, so the ring does not re-implement either.
        onOpenConnect = { showTrackerConnect = true },
        // A tracker that just became connected FROM this dialog is the case the
        // offer exists for: the user connected Strava because the walk alarm just
        // told them to walk, so today's activity is pulled in immediately rather
        // than left for a sync they would have to remember. Mirrors the branch the
        // habits screen uses on resume, so both paths import identically.
        onActivityCaptured = { captured ->
            val activityType = ringIconId?.let { stravaActivityTypeFor(it) }.orEmpty()
            captured.forEach { provider ->
                when (provider) {
                    TrackerProvider.STRAVA ->
                        viewModel.syncStravaActivities(pending.habitId, activityType)
                    TrackerProvider.HEALTH_CONNECT ->
                        viewModel.syncHealthConnectToHabit(pending.habitId, activityType)
                    TrackerProvider.POLAR ->
                        viewModel.syncPolarToHabit(pending.habitId, activityType)
                }
            }
        },
        onRecord = { title, note, durationSeconds, count ->
            // Recorded through the single tracking entry point, so the ring
            // writes the same payload (and the same [HabitLogEntry]) the
            // Habits screen writes. The mode decides the shape of the record,
            // which is why it is passed explicitly rather than inferred.
            viewModel.recordHabitTracking(
                habitId = pending.habitId,
                mode = mode,
                title = title,
                note = note,
                durationSeconds = durationSeconds,
                count = count,
                unit = habit?.trackingUnit,
                // Attributed to the alarm time that raised this dialog, so the
                // record answers *this* occurrence. Without it a second ring of
                // the day would be indistinguishable from the first, and the
                // per-occurrence history the user asked for would collapse.
                alarmMinutes = pending.alarmMinutes,
            )
            // Recorded, so nothing is left waiting: the minimized copy (if any)
            // is cleared BEFORE the dialog closes, so the chip cannot outlive
            // the record it belonged to.
            HabitTimerRequests.clearMinimized(context)
            request = null
        },
        onStartTimed = { kind, target ->
            TimerController.start(
                context = context,
                kind = kind,
                targetSeconds = target,
                label = name,
                habitId = pending.habitId,
            )
            request = null
            onOpenTimerScreen()
        },
        onDismiss = {
            // Dismissing for real clears any minimized copy, so a later tick in
            // the poll loop cannot resurrect a dialog the user explicitly closed.
            HabitTimerRequests.clearMinimized(context)
            request = null
        },
        onMinimize = {
            // Keep the dialog reachable under the minimized chip. The request is
            // COPIED to the minimized slot first, because the poll loop/consume
            // discipline means the one-shot record is already gone by now.
            HabitTimerRequests.minimize(context, pending)
            request = null
            minimizeNonce++
        },
    )

    if (showTrackerConnect) {
        HabitTrackerConnectHost(
            viewModel = viewModel,
            statuses = trackerStatuses,
            habitLabel = name,
            onDismiss = { showTrackerConnect = false },
        )
    }
}

/**
 * How often the root host re-reads the one-shot timer/stopwatch request.
 *
 * Short enough that the choice is on screen effectively the instant the alarm
 * rings, long enough to be free: SharedPreferences resolves from an in-memory
 * map, so each pass is a single lookup rather than I/O.
 */
private const val POPUP_POLL_MILLIS = 1_000L

/**
 * Root-level owner of the **one-time** timer completion popup.
 *
 * Lives outside the `NavHost` so the popup appears wherever the user is when a
 * timer finishes, and so exactly one composable can ever decide to show it.
 *
 * ## When the popup may appear — and when it must not
 *
 * The popup reports the end of a timer the user was on. It must therefore only
 * ever exist for a run the user actually started, and only while that result is
 * still current. That is enforced one layer down, by
 * [TimerRepository.loadPopupEvent], which this host reads through instead of
 * raw [TimerRepository.loadPendingEvent]:
 *
 *  - **No started run, no popup.** A completion whose run id is absent from the
 *    started-run ledger is *not* something the user did — an orphaned record
 *    left by an older build, or a half-written event. It is discarded here, so
 *    merely opening the app (landing on Home, or anywhere else) can never
 *    produce a popup for a timer that was never set.
 *  - **No live result, no popup.** A completion older than
 *    [com.rork.mindsetframestracker.data.TIMER_POPUP_GRACE_MILLIS] is expired
 *    rather than shown late.
 *  - **Already shown, no popup.** The handled-ledger still gets the final say.
 *
 * Rejected events are *consumed*, never merely hidden, so none of them can
 * resurface on a later recomposition, resume, navigation or launch.
 *
 * Note the host deliberately stays at the app root: a run that finishes while
 * the user is on Home *should* announce itself there. What must never happen is
 * a popup with no timer behind it — which is the failure this gate removes.
 *
 * ## Why the popup cannot repeat
 *
 * The dialog is rendered from [TimerRepository.loadPopupEvent] — a persisted
 * record, not transient UI state. Rendering it immediately calls
 * [TimerController.acknowledgeEvent], which appends the event's id to an
 * append-only handled-ledger on disk and clears the pending slot. From that
 * instant the event can no longer be produced, no matter how often this
 * composable recomposes, how many times the Activity resumes, how the user
 * navigates, or whether the phone is rebooted mid-session.
 */
@Composable
private fun TimerCompletionHost(
    context: android.content.Context,
    onOpenHabits: () -> Unit,
) {
    val repo = remember { TimerRepository(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingEvent by remember { mutableStateOf(repo.loadPopupEvent()) }

    // Re-read on every resume. This is the *only* way an event that fired while
    // the app was in the background reaches the screen — and because showing it
    // consumes it, the next resume finds nothing.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pendingEvent = repo.loadPopupEvent()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A short in-app fallback tick, so a timer that completes while the user is
    // looking at the app pops immediately instead of waiting for a resume. It
    // deliberately does NOT fire the completion itself — that is the alarm
    // receiver / service / TimerScreen's job — it only re-reads the result.
    LaunchedEffect(Unit) {
        while (true) {
            val next = repo.loadPopupEvent()
            if (next?.eventId != pendingEvent?.eventId) pendingEvent = next
            kotlinx.coroutines.delay(1_000L)
        }
    }

    val event = pendingEvent ?: return

    // Acknowledge immediately on first composition of the dialog: this is the
    // single line that turns "pending" into "shown once, forever".
    LaunchedEffect(event.eventId) {
        TimerController.acknowledgeEvent(context, event.eventId)
        pendingEvent = null
    }

    TimerCompletionPopup(
        event = event,
        onPrimary = {
            pendingEvent = null
            onOpenHabits()
        },
        onSecondary = { pendingEvent = null },
        onDismiss = { pendingEvent = null },
    )
}

/**
 * Root composable: the bottom-bar shell and the nav graph, plus the app-wide
 * host for the one-time timer completion popup.
 */
@Composable
fun AppNavigation(viewModel: AppViewModel) {
    val navController: NavHostController = rememberNavController()
    val data by viewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabRoutes
    val reducedMotion = data.settings.reducedMotion
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val showAuthPrompt by viewModel.showAuthPrompt.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current


    // ── Health Connect permission launcher ────────────────────────────
    // Registered here (top of the composable, before any conditional
    // return) so it lives for the entire Activity lifecycle — a
    // requirement of rememberLauncherForActivityResult. When the
    // ViewModel's healthConnectPermissionRequested flag goes true we
    // launch the system permission dialog; the result callback forwards
    // the granted permission set back to the ViewModel.
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        contract = MindsetHealthConnectClient.permissionRequestContract(),
    ) { granted ->
        viewModel.onHealthConnectPermissionResult(granted)
        // Access was just granted — capture what the device actually recorded for
        // every movement habit, so the sports habits carry real numbers instead of
        // being a label. Fire-and-forget on the monitor's own IO scope: this is a
        // permission-result callback and must not block the UI thread.
        runCatching {
            com.rork.mindsetframestracker.integrations.ActivityMonitor
                .captureAllSupportedHabits(context)
        }
    }
    val hcPermissionRequested by viewModel.healthConnectPermissionRequested
        .collectAsStateWithLifecycle()
    LaunchedEffect(hcPermissionRequested) {
        if (hcPermissionRequested) {
            // Consume the one-shot flag FIRST so rotation / recomposition
            // can never replay the launch, then attempt the actual launch
            // inside a try-catch. If the Health Connect provider is not
            // resolvable (e.g. missing <queries> entry, HC uninstalled
            // between the pre-check and now) the launch() call throws
            // ActivityNotFoundException — we must not swallow that silently.
            viewModel.consumeHealthConnectPermissionRequest()
            try {
                hcPermissionLauncher.launch(MindsetHealthConnectClient.requiredPermissions)
            } catch (e: Exception) {
                // Could not resolve the Health Connect permission activity.
                // Report the empty set so the ViewModel shows an error
                // message instead of leaving the user without feedback.
                viewModel.onHealthConnectPermissionResult(emptySet())
            }
        }
    }

    // Automatic sign-in / sign-up popup: slides up shortly after the user
    // first lands on Today — right after onboarding. Shown ONCE ever
    // (persisted), fully skippable, re-armed by an explicit sign-out, and
    // re-openable any time via Settings → Back up & restore; also the
    // restore path for returning users (signing in pulls their cloud data).
    LaunchedEffect(currentRoute) {
        if (currentRoute == "home") {
            delay(900)
            viewModel.maybeShowAuthPrompt()
        }
    }

    // Requests that arrive from outside Compose — a tapped notification, or the
    // running-timer notification's Stop action. Handled here (not in the
    // screens) because this is the only place that owns the NavController, and
    // consumed immediately so the navigation can never replay on a later
    // recomposition.
    val requestedRoute by NavRequests.route.collectAsStateWithLifecycle()
    LaunchedEffect(requestedRoute) {
        val target = requestedRoute ?: return@LaunchedEffect
        NavRequests.consume()
        if (currentRoute != target) {
            navController.navigate(target) { launchSingleTop = true }
        }
    }

    // High priority intercept: if coming from a password reset link, block app with SetNewPasswordSheet
    if (syncState.showSetNewPasswordSheet) {
        SetNewPasswordSheet(
            syncState = syncState,
            onUpdatePassword = viewModel::setNewPassword,
        )
    } else if (showAuthPrompt) {
        AuthPromptSheet(
            syncState = syncState,
            privacyConsentAccepted = data.settings.privacyConsentAccepted,
            onAcceptPrivacyConsent = { viewModel.acceptPrivacyConsent() },
            onHuaweiSignIn = {
                activity?.let { act ->
                    // startSignIn returns a user-facing message when the flow
                    // can't launch (HMS missing, agconnect config absent, …).
                    com.rork.mindsetframestracker.auth.HuaweiAuthClient.startSignIn(act)
                        ?.let(viewModel::onHuaweiSignInFailed)
                }
            },
            onSignIn = viewModel::signIn,
            onSignUp = viewModel::signUp,
            onForgotPassword = viewModel::sendPasswordReset,
            onConsumeSuggestSignIn = viewModel::consumeSuggestSignIn,
            onDismiss = viewModel::dismissAuthPrompt,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar && !syncState.showSetNewPasswordSheet,
                enter = if (reducedMotion) EnterTransition.None
                else slideInVertically(
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                    initialOffsetY = { it },
                ) + fadeIn(tween(240)),
                exit = if (reducedMotion) ExitTransition.None
                else slideOutVertically(
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                    targetOffsetY = { it },
                ) + fadeOut(tween(160)),
            ) {
                val isOnline by rememberIsOnline()
                val isLowPower by rememberIsBatteryLow()
                Box {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val strings = appStrings()
                        val tabLabels = mapOf(
                            "home" to strings.navToday,
                            "habits" to strings.navHabits,
                            "weekly" to strings.navWeekly,
                            "insights" to strings.navInsights,
                            "settings" to strings.navSettings,
                        )
                        bottomDestinations.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        navController.navigate(destination.route) {
                                            popUpTo("home") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon
                                        else destination.unselectedIcon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(tabLabels[destination.route] ?: "") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                    ConnectivityStatusIcon(
                        isOnline = isOnline,
                        isLowPower = isLowPower,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 10.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .moodBackdrop(),
        ) {
            NavHost(
                navController = navController,
                startDestination = "splash",
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (reducedMotion) {
                        EnterTransition.None
                    } else {
                        val dir = tabDirection(
                            initialState.destination.route,
                            targetState.destination.route,
                        )
                        if (dir != 0) {
                            slideInHorizontally(
                                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                                initialOffsetX = { full -> dir * full / 5 },
                            ) + fadeIn(tween(280))
                        } else {
                            fadeIn(tween(durationMillis = 420, easing = FastOutSlowInEasing)) +
                                scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                                )
                        }
                    }
                },
                exitTransition = {
                    if (reducedMotion) {
                        ExitTransition.None
                    } else {
                        val dir = tabDirection(
                            initialState.destination.route,
                            targetState.destination.route,
                        )
                        if (dir != 0) {
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                                targetOffsetX = { full -> -dir * full / 5 },
                            ) + fadeOut(tween(180))
                        } else {
                            fadeOut(tween(240))
                        }
                    }
                },
                popEnterTransition = {
                    if (reducedMotion) {
                        EnterTransition.None
                    } else {
                        val dir = tabDirection(
                            initialState.destination.route,
                            targetState.destination.route,
                        )
                        if (dir != 0) {
                            slideInHorizontally(
                                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                                initialOffsetX = { full -> dir * full / 5 },
                            ) + fadeIn(tween(280))
                        } else {
                            fadeIn(tween(durationMillis = 420, easing = FastOutSlowInEasing)) +
                                scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                                )
                        }
                    }
                },
                popExitTransition = {
                    if (reducedMotion) {
                        ExitTransition.None
                    } else {
                        val dir = tabDirection(
                            initialState.destination.route,
                            targetState.destination.route,
                        )
                        if (dir != 0) {
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                                targetOffsetX = { full -> -dir * full / 5 },
                            ) + fadeOut(tween(180))
                        } else {
                            fadeOut(tween(240))
                        }
                    }
                },
            ) {
                composable("splash") {
                    SplashScreen(
                        reducedMotion = data.settings.reducedMotion,
                        onFinished = {
                            val target = if (data.settings.onboardingDone) "home" else "onboarding"
                            navController.navigate(target) {
                                popUpTo("splash") { inclusive = true }
                            }
                        },
                    )
                }
                composable("onboarding") {
                    OnboardingScreen(
                        onFinish = { habits, mood ->
                            viewModel.completeOnboarding(habits, mood)
                            navController.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                    )
                }
                composable("home") {
                    HomeScreen(
                        viewModel = viewModel,
                        onGoToHabits = {
                            navController.navigate("habits") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // A timed habit started from its tracking sheet opens the
                        // timer screen, the same destination the alarm's timer
                        // flow uses — one place to watch a run, whichever route
                        // started it.
                        onOpenTimerScreen = {
                            navController.navigate("timer") { launchSingleTop = true }
                        },
                    )
                }
                composable("habits") {
                    // The timer / stopwatch entry point lives on Habits
                    // (not Today), so the route is wired through here.
                    HabitsScreen(
                        viewModel = viewModel,
                        onOpenTimer = {
                            navController.navigate("timer") { launchSingleTop = true }
                        },
                    )
                }
                // Timer / stopwatch. Its own full screen (with its own
                // top bar) rather than a tab: it is a focused, modal-ish task,
                // and the one-time completion popup needs a predictable
                // destination to return to.
                composable("timer") {
                    TimerScreen(
                        onBack = {
                            if (navController.previousBackStackEntry != null) navController.popBackStack()
                            else navController.navigate("home") { launchSingleTop = true }
                        },
                        onOpenHabits = {
                            navController.navigate("habits") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable("weekly") { WeeklyScreen(viewModel = viewModel) }
                composable("insights") { InsightsScreen(viewModel = viewModel) }
                composable("settings") { SettingsScreen(viewModel = viewModel) }
            }

            SyncStatusBanner(
                syncState = syncState,
                visible = showBottomBar && currentRoute != "settings" && !showAuthPrompt && !syncState.showSetNewPasswordSheet,
                reducedMotion = reducedMotion,
                onRetry = viewModel::retrySync,
                onDismiss = viewModel::clearSyncMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp),
            )

            // ── The dialog a ringing habit alarm raises ──────────────────
            // Hosted at the app root, not inside TimerScreen / the ringing
            // screen, so it appears on its own the moment the alarm rings
            // wherever the user is — and so it does not depend on the
            // full-screen-intent grant AlarmRingingActivity needs to launch.
            // It carries the habit's own tool AND, when a fitness app can
            // actually supply that habit, the connect offer inside the same
            // card — see HabitTimerOptionsHost.
            HabitTimerOptionsHost(
                viewModel = viewModel,
                onOpenTimerScreen = {
                    navController.navigate("timer") { launchSingleTop = true }
                },
            )

            // ── Minimized sessions ───────────────────────────────────────
            // Hosted at the app root, directly above the bottom bar, so a
            // running stopwatch or a dialog the user minimized is reachable from
            // EVERY tab — which is the whole point of "take it anytime".
            // Restoring a minimized sheet drives the same host that the alarm
            // path uses, so there is exactly one component that can put a habit
            // dialog on screen and no way for two to compete.
            MinimizedSessionChip(
                context = context,
                onResumeSheet = { minimized ->
                    // Stashed where HabitTimerOptionsHost looks, then cleared from
                    // the minimized slot so the chip and the dialog cannot both
                    // believe they own it.
                    HabitTimerRequests.request(
                        context = context,
                        habitId = minimized.habitId,
                        habitName = minimized.habitName,
                        iconId = minimized.iconId,
                        mode = minimized.mode,
                        isSport = minimized.isSport,
                        alarmMinutes = minimized.alarmMinutes,
                    )
                    HabitTimerRequests.clearMinimized(context)
                },
                onOpenTimer = {
                    navController.navigate("timer") { launchSingleTop = true }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showBottomBar) 74.dp else 12.dp),
            )

            TimerCompletionHost(
                context = context,
                onOpenHabits = {
                    navController.navigate("habits") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}
