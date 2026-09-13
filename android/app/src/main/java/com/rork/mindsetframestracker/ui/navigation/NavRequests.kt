package com.rork.mindsetframestracker.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny one-shot navigation channel for deep links that arrive *outside*
 * Compose — a tapped notification, or the running-timer notification's
 * \"Stop\" action, both of which land in [com.rork.mindsetframestracker.MainActivity]
 * before the nav graph exists.
 *
 * A `StateFlow<String?>` rather than a callback because the Activity may handle
 * the intent while the app is cold (the graph is not composed yet) or warm:
 * storing the request as state means a cold start still routes correctly once
 * [AppNavigation] finally collects it. The consumer always
 * [consume]s immediately, so the route is delivered exactly once and can never
 * re-navigate on a later recomposition.
 */
object NavRequests {

    private val _route = MutableStateFlow<String?>(null)

    /** The pending route to open, or null when there is nothing to do. */
    val route: StateFlow<String?> = _route.asStateFlow()

    /** Timer route — matches the `composable(\"timer\")` destination. */
    const val ROUTE_TIMER = "timer"

    /**
     * Habits route — matches the `composable("habits")` destination.
     *
     * The ring's "Timer / stopwatch" action lands here rather than on the
     * timer screen: the options belong to the habit's own icon, so the user is
     * taken to the icon (whose one-shot options sheet is waiting) instead of
     * to a screen with no habit attached to it.
     */
    const val ROUTE_HABITS = "habits"

    fun request(route: String) {
        _route.value = route
    }

    /** Clears the pending request; call before/while acting on it. */
    fun consume() {
        _route.value = null
    }
}
