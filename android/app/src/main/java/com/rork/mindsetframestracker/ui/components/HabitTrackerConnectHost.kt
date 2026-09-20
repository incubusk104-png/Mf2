package com.rork.mindsetframestracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerState
import com.rork.mindsetframestracker.integrations.TrackerStatus
import com.rork.mindsetframestracker.ui.AppViewModel

/**
 * The connect-fitness flow, as one drop-in owned by the **habit dialog**.
 *
 * ## Why this exists at all
 *
 * Connecting a fitness tracker used to be a single global control sitting in its
 * own position — a "Connect fitness trackers" row at the top of the habits list.
 * That reads as a separate feature that happens to live next to the habits, not
 * as something belonging to one of them. The request was literal: *move* Connect
 * fitness so it sits **inside the habit's own dialog**, so opening a habit (a
 * walk, say) offers the connections that can actually supply that habit.
 *
 * Moving the *button* is not enough on its own, which is what makes this a host
 * rather than a call site. The flow is one sheet plus a consent gate plus a tier
 * gate, and every one of those has a way to leave a stranded state behind:
 *
 *  - **The consent gate must come first.** The OAuth page or the system
 *    permission dialog opens only after the user has agreed to what is read
 *    (AppGallery review and GDPR Art. 13 both require it). If each caller owned
 *    its own copy of that ordering, one of them would eventually skip it.
 *  - **The in-flight spinner must always resolve.** The OAuth path resolves a
 *    client id over the network before it can open a browser, so the row spins.
 *    Clearing that on "the connect call returned" is wrong — it returned long
 *    before the user finished authorising. It is cleared when the provider's
 *    connected state actually *changes*, or a status message arrives, whichever
 *    happens first.
 *  - **A tier-locked provider must reach the upgrade path, not OAuth.** The row
 *    is drawn with a padlock and reads "Upgrade"; tapping it used to launch the
 *    real authorisation page for a feature the account had not paid for. The
 *    label and the behaviour have to agree.
 *
 * Owning all four here is what makes "connect from inside the habit dialog" true
 * on every dialog the user can reach it from — the alarm editor, the tap-to-track
 * sheet, and the alarm-ring sheet — instead of true on whichever one was updated
 * most recently. The alternative, letting each caller re-implement the ordering,
 * is precisely how the four defects this flow has already had got in.
 *
 * ## Only one modal at a time
 *
 * The tier gate renders the premium sheet **instead of** the connect sheet rather
 * than on top of it: two stacked bottom sheets would double the scrim and leave
 * the user with two "dismiss" gestures to reason about. Callers whose own surface
 * is a bottom sheet close it before opening this (see `HomeScreen`), for the same
 * reason.
 */
@Composable
fun HabitTrackerConnectHost(
    viewModel: AppViewModel,
    /** Each provider's resolved state, from the caller that already holds them. */
    statuses: List<TrackerStatus>,
    /**
     * The habit this flow was opened *for*, so the sheet can say whose
     * connection it is setting up. Required, not optional: there is no longer
     * any global position that opens this flow, so a caller always has a habit.
     */
    habitLabel: String,
    onDismiss: () -> Unit,
) {
    /** The provider mid-connect, so its row shows progress instead of looking ignored. */
    var busyProvider by remember { mutableStateOf<TrackerProvider?>(null) }
    /** The consent the user is being asked for, if any; the action waits behind it. */
    var pendingConsent by remember { mutableStateOf<IntegrationConsent?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** True once a locked provider has been tapped, so the upsell replaces the sheet. */
    var showPremium by remember { mutableStateOf(false) }

    val message by viewModel.stravaMessage.collectAsStateWithLifecycle()

    /**
     * Clears the in-flight spinner on whichever happens first: a provider's
     * connected state actually changing, or a status message arriving. Either
     * means the attempt has resolved, so the row can never spin forever.
     *
     * Keyed on the *connected-ness* rather than on `statuses` itself. `statuses`
     * is rebuilt by every caller on every recomposition, so keying the effect on
     * it would restart the effect each frame and clear the spinner on the first
     * one — the row would never appear busy at all. A list of
     * `(provider, isConnected)` pairs compares structurally, so only a real
     * change restarts it.
     */
    val connectedKey = statuses.map { it.provider to it.isConnected }
    LaunchedEffect(connectedKey, message) { busyProvider = null }

    val connectedWithAuto = statuses.filter { it.isConnected }
    // One switch for the whole set: on means every connected provider sweeps
    // into the activity habits on app open. Presented as a single decision
    // because that is how the user thinks about it — they connected a tracker
    // *so that* the habit keeps itself up to date.
    val autoTrackOn = connectedWithAuto.isNotEmpty() && connectedWithAuto.all { it.autoSync }

    if (showPremium) {
        PremiumSheet(
            onDismiss = { showPremium = false },
            onPurchaseStarted = { viewModel.onSubscriptionPurchaseStarted(it) },
            onRestore = { viewModel.restoreSubscription() },
            foundingEligibility = { viewModel.checkFoundingMemberEligibility() },
        )
        return
    }

    TrackerConnectSheet(
        statuses = statuses,
        habitLabel = habitLabel,
        autoTrack = autoTrackOn,
        busyProvider = busyProvider,
        message = message,
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
            if (statuses.firstOrNull { it.provider == provider }?.state == TrackerState.LOCKED) {
                // The lock icon and the "Upgrade" label are finally honest: this
                // routes to the upgrade path instead of running the real OAuth
                // flow for a tier the account has not paid for.
                showPremium = true
            } else {
                busyProvider = provider
                // Privacy consent first — before the OAuth page or the system
                // permission dialog opens. Required by AppGallery review and
                // GDPR Art. 13, and it is also just the honest order.
                when (provider) {
                    TrackerProvider.HEALTH_CONNECT -> {
                        pendingConsent = IntegrationConsent.HEALTH_CONNECT
                        pendingAction = { viewModel.requestHealthConnectPermissions() }
                    }
                    TrackerProvider.POLAR -> {
                        pendingConsent = IntegrationConsent.POLAR
                        pendingAction = { viewModel.connectPolar() }
                    }
                    TrackerProvider.STRAVA -> {
                        pendingConsent = IntegrationConsent.STRAVA
                        pendingAction = { viewModel.connectStrava() }
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
        onDismiss = onDismiss,
    )

    if (pendingConsent != null) {
        IntegrationConsentDialog(
            consent = pendingConsent!!,
            onAgree = {
                val action = pendingAction
                pendingConsent = null
                pendingAction = null
                action?.invoke()
            },
            onDismiss = {
                // Declining leaves the attempt unfinished, so the row must stop
                // spinning — otherwise it reports work that was never started.
                pendingConsent = null
                pendingAction = null
                busyProvider = null
            },
        )
    }
}
