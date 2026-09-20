package com.rork.mindsetframestracker.ui.navigation

import com.rork.mindsetframestracker.ui.theme.Mf2Palette

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
 * below 20% and not charging - background sync is paused to save energy.
 */
@Composable
private fun ConnectivityStatusIcon(
    isOnline: Boolean,
    isLowPower: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = when {
            isLowPower -> Mf2Palette.StatusAmber
            isOnline -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> Mf2Palette.StatusIdle
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
                contentDescription = "Low Power - sync paused below 20% battery to conserve energy",
                tint = Mf2Palette.StatusAmber,
                modifier = Modifier.size(14.dp),
            )
        }
        Icon(
            imageVector = if (isOnline) Icons.Filled.Cloud else Icons.Filled.CloudOff,
            contentDescription = when {
                isLowPower -> "Sync paused - Low Power mode"
                isOnline -> "Online"
                else -> "Offline - data saved on this device"
            },
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Root-level owner of the dialog a **ringing habit alarm** raises.
 * Hosted at the app root so the choice appears on its own the moment the alarm
 * rings, wherever the user is, and without depending on the full-screen-intent
 * grant that AlarmRingingActivity needs to launch.
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

    // The pending record is re-read on a short tick. This is not a poll of
    // anything expensive: SharedPreferences is an in-memory map after the first
    // read, so each pass is a map lookup.
    LaunchedEffect(Unit) {
        while (true) {
            val pending = HabitTimerRequests.peek(context)
            if (pending != null && pending.habitId != request?.habitId) {
                HabitTimerRequests.consume(context)
                request = pending
            }
            delay(POPUP_POLL_MILLIS)
        }
    }

    val pending = request ?: return

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

    val mode = habit?.trackingModeOrDefault ?: pending.mode ?: HabitTrackingMode.CHECK
    val name = habit?.name?.takeIf { it.isNotBlank() } ?: pending.habitName.ifBlank { "Habit" }

    val appData by viewModel.state.collectAsStateWithLifecycle()

    var showTrackerConnect by remember { mutableStateOf(false) }
    val settings = appData.settings
    val trackerStatuses = remember(
        settings.healthConnectConnected,
        settings.polarAccessToken,
        settings.stravaRefreshToken,
    ) { TrackerConnections.statuses(context, settings, settings.subscriptionTier()) }
    val ringIconId = habit?.iconId ?: pending.iconId
    val trackerProviders = remember(ringIconId) { TrackerConnections.sourcesFor(ringIconId) }
    val connectOffer = remember(pending.habitId, ringIconId, trackerStatuses) {
        AlarmRingGate.shouldOfferConnect(
            sources = trackerProviders,
            statuses = trackerStatuses,
            alreadyAsked = AlarmConnectPrompt.hasAsked(context, pending.habitId),
        )
    }
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
        alarmSlots = HabitAlarmHistory.daySlots(appData, pending.habitId),
        alarmSetup = habit?.let { HabitAlarmSetup.of(it) },
        copy = remember(name, ringIconId, mode, trackerProviders) {
            AlarmDialogs.forHabit(
                habitName = name,
                iconId = ringIconId,
                mode = mode,
                unit = habit?.trackingUnit.orEmpty(),
                sourceNames = trackerProviders.map { it.label },
            )
        },
        connectOffer = connectOffer,
        trackerProviders = trackerProviders,
        trackerStatuses = trackerStatuses,
        onOpenConnect = { showTrackerConnect = true },
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
            viewModel.recordHabitTracking(
                habitId = pending.habitId,
                mode = mode,
                title = title,
                note = note,
                durationSeconds = durationSeconds,
                count = count,
                unit = habit?.trackingUnit,
                alarmMinutes = pending.alarmMinutes,
            )
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
            HabitTimerRequests.clearMinimized(context)
            request = null
        },
        onMinimize = {
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
 * The dialog is rendered from a persisted record, not transient UI state.
 * Rendering it immediately acknowledges the event, which appends the event's id
 * to an append-only handled-ledger on disk and clears the pending slot - so the
 * popup can never repeat.
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
    // the app was in the background reaches the screen - and because showing it
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
    // deliberately does NOT fire the completion itself - that is the alarm
    // receiver / service / TimerScreen's job - it only re-reads the result.
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

    // Health Connect permission launcher, registered here (top of the
    // composable, before any conditional return) so it lives for the entire
    // Activity lifecycle - a requirement of rememberLauncherForActivityResult.
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        contract = MindsetHealthConnectClient.permissionRequestContract(),
    ) { granted ->
        viewModel.onHealthConnectPermissionResult(granted)
        runCatching {
            com.rork.mindsetframestracker.integrations.ActivityMonitor
                .captureAllSupportedHabits(context)
        }
    }
    val hcPermissionRequested by viewModel.healthConnectPermissionRequested
        .collectAsStateWithLifecycle()
    LaunchedEffect(hcPermissionRequested) {
        if (hcPermissionRequested) {
            viewModel.consumeHealthConnectPermissionRequest()
            try {
                hcPermissionLauncher.launch(MindsetHealthConnectClient.requiredPermissions)
            } catch (e: Exception) {
                viewModel.onHealthConnectPermissionResult(emptySet())
            }
        }
    }

    // Automatic sign-in / sign-up popup: slides up shortly after the user
    // first lands on Today - right after onboarding. Shown ONCE ever
    // (persisted), fully skippable, re-armed by an explicit sign-out, and
    // re-openable any time via Settings -> Back up & restore.
    LaunchedEffect(currentRoute) {
        if (currentRoute == "home") {
            delay(900)
            viewModel.maybeShowAuthPrompt()
        }
    }

    // Requests that arrive from outside Compose - a tapped notification, or the
    // running-timer notification's Stop action. Handled here (not in the
    // screens) because this is the only place that owns the NavController, and
    // consumed immediately so the navigation can never replay.
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
                        onOpenTimerScreen = {
                            navController.navigate("timer") { launchSingleTop = true }
                        },
                    )
                }
                composable("habits") {
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

            // The dialog a ringing habit alarm raises, hosted at the app root.
            HabitTimerOptionsHost(
                viewModel = viewModel,
                onOpenTimerScreen = {
                    navController.navigate("timer") { launchSingleTop = true }
                },
            )

            // Minimized sessions, hosted at the app root directly above the
            // bottom bar, so a running stopwatch or a dialog the user minimized
            // is reachable from EVERY tab.
            MinimizedSessionChip(
                context = context,
                onResumeSheet = { minimized ->
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
