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
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.AuthPromptSheet
import com.rork.mindsetframestracker.data.TimerRepository
import com.rork.mindsetframestracker.data.TimerStatus
import com.rork.mindsetframestracker.notifications.HabitTimerRequests
import com.rork.mindsetframestracker.notifications.TimerController
import com.rork.mindsetframestracker.notifications.TimerService
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.ui.screens.TimerCompletionPopup
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
import com.rork.mindsetframestracker.ui.screens.HabitTimerOptionsSheet
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
            isLowPower -> Color(0xFFFFB300)
            isOnline -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> Color(0xFF9E9E9E)
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
                tint = Color(0xFFFFB300),
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
 * Root-level owner of the **timer / stopwatch popup for a ringing habit alarm**.
 *
 * ## Why the popup is hosted here and not in the ringing screen
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
 * notification appeared, and the timer/stopwatch popup never came up. Hosted
 * here on the app root, it appears on its own at the moment the alarm rings
 * whenever the app is in the foreground, and the one-shot request it reads
 * survives a ring that happens while the app is in the background — so the
 * user still gets it the moment they next open the app.
 *
 * ## Appearing exactly once per ring
 *
 * The request is written to disk when the ring happens (see
 * [HabitTimerRequests]) and **consumed here the instant the sheet is shown**,
 * so recomposition, resume, navigation, rotation and reboot all find nothing.
 * The sheet keeps rendering from the value already read, so this ring still
 * shows it exactly once.
 */
@Composable
private fun HabitTimerOptionsHost(
    onOpenTimerScreen: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf(HabitTimerRequests.peek(context)) }

    // Re-read on every resume: this is how a ring that happened while the app
    // was backgrounded still surfaces, and the consume below is why a second
    // resume finds nothing.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                request = HabitTimerRequests.peek(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pending = request ?: return

    // Consume the moment the choice is shown, so it can never come back. The
    // sheet below keeps rendering from the value already read, so this ring
    // still shows it exactly once.
    LaunchedEffect(pending.habitId) {
        HabitTimerRequests.consume(context)
    }

    // The ringing habit's own catalog artwork. Resolved off the main thread —
    // decoding the app blob synchronously in composition is exactly the
    // main-thread stall that has to stay off the ring path. The sheet falls
    // back to its generic timer glyph for the first frame.
    var iconId by remember(pending.habitId) { mutableStateOf(pending.iconId) }
    if (pending.iconId == null) {
        LaunchedEffect(pending.habitId) {
            iconId = withContext(Dispatchers.IO) {
                runCatching {
                    MindsetRepository(context).load().habits
                        .firstOrNull { it.id == pending.habitId }?.iconId
                }.getOrNull()
            }
        }
    }

    HabitTimerOptionsSheet(
        habitId = pending.habitId,
        habitName = pending.habitName.ifBlank { "Habit" },
        habitIconId = iconId,
        onOpenTimerScreen = {
            request = null
            onOpenTimerScreen()
        },
        onDismiss = { request = null },
    )
}

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
 *
 * Concretely, each of the usual ways a Compose popup "comes back" is closed
 * off:
 *
 *  - **Recomposition / re-render** — nothing here is keyed on a value that a
 *    recomposition can re-arm; the pending record is read once per resume and
 *    cleared as soon as the dialog appears.
 *  - **App resume / `ON_RESUME`** — the resume handler *reloads* the pending
 *    event (which is how a completion that fired while backgrounded still
 *    surfaces) but the ledger makes a second resume find nothing.
 *  - **Navigation** — the host sits above the nav graph, so returning to a
 *    screen does not re-create it with a stale event.
 *  - **Rotation / process death** — the ledger is in SharedPreferences, so
 *    even a kill between the alarm and the tap cannot double-fire.
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


    // ── Health Connect permission launcher ─────────────────────────────
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

            // ── One-time timer completion popup ──────────────────────────
            // Hosted at the app root, not inside TimerScreen, for two reasons:
            //
            // 1. A timer can finish while the user is on ANY tab (or with the app
            //    backgrounded, if the alarm fired); the popup must appear wherever
            //    they are, not only if they happened to leave the timer screen open.
            // 2. Exactly one composable owns the event, so two screens can never
            //    both decide to show it.
            //
            // The dialog is driven purely by the persisted pending record, and
            // acknowledging it writes the event id into an append-only ledger — so
            // it shows once per event and never again, across recomposition,
            // navigation, resume, rotation or a reboot.
            // ── Timer / stopwatch choice for a ringing habit alarm ───────
            // Appears on its own the moment a habit's alarm rings — the same
            // moment its notification shows — and is hosted at the root so it
            // does not depend on the full-screen-intent grant that
            // AlarmRingingActivity needs to launch at all.
            HabitTimerOptionsHost(
                onOpenTimerScreen = {
                    navController.navigate("timer") { launchSingleTop = true }
                },
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
