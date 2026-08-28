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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.AuthPromptSheet
import com.rork.mindsetframestracker.ui.components.SetNewPasswordSheet
import com.rork.mindsetframestracker.ui.components.SyncStatusBanner
import com.rork.mindsetframestracker.ui.components.moodBackdrop
import com.rork.mindsetframestracker.ui.screens.HabitsScreen
import com.rork.mindsetframestracker.ui.screens.HomeScreen
import com.rork.mindsetframestracker.ui.screens.InsightsScreen
import com.rork.mindsetframestracker.ui.screens.OnboardingScreen
import com.rork.mindsetframestracker.ui.screens.SettingsScreen
import com.rork.mindsetframestracker.ui.screens.SplashScreen
import com.rork.mindsetframestracker.ui.screens.WeeklyScreen
import com.rork.mindsetframestracker.util.rememberIsBatteryLow
import com.rork.mindsetframestracker.util.rememberIsOnline
import kotlinx.coroutines.delay
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
                composable("habits") { HabitsScreen(viewModel = viewModel) }
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
        }
    }
}
