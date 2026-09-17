package com.rork.mindsetframestracker.integrations

import android.content.Context
import com.rork.mindsetframestracker.billing.Entitlements
import com.rork.mindsetframestracker.billing.Feature
import com.rork.mindsetframestracker.billing.SubscriptionTier
import com.rork.mindsetframestracker.data.AppSettings
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.isSportActivityIcon

/**
 * The three external fitness services the app can pull activity data from, as
 * one list — so the connect pop-up, Settings, and the auto-track sweep all name
 * them the same way and from the same place.
 *
 * The existing code already had a client for each of these and a row for each in
 * Settings, but nothing that could answer the question the pop-up has to answer
 * for **all three at once**: *is this one actually usable on this device and this
 * build, right now?* [TrackerStatus] is that answer.
 */
enum class TrackerProvider {
    HEALTH_CONNECT,
    STRAVA,
    POLAR;

    /** The label shown on the connector row and in any message about it. */
    val label: String
        get() = when (this) {
            HEALTH_CONNECT -> "Google Health Connect"
            STRAVA -> "Strava"
            POLAR -> "Polar"
        }
}

/**
 * Whether a provider can be connected, and — when it cannot — **why**.
 *
 * ## Why this is not a boolean
 *
 * Each provider fails for a genuinely different reason, and they need different
 * fixes:
 *
 *  - [NEEDS_SETUP] — this *build* has no OAuth client id for it, and the server
 *    has none either, so nothing the user does on this phone will make it work.
 *    Telling them to "try again" would be a lie; they need a new build.
 *  - [NOT_INSTALLED] — Health Connect isn't present/updated on this device. The
 *    fix is in the Play Store, not in this app.
 *  - [LOCKED] — Strava is a paid feature. The user should see the upsell instead
 *    of a dead button.
 *  - [READY] / [CONNECTED] — actionable now.
 *
 * Collapsing these into "can connect: yes/no" is what produces the failure mode
 * the old Settings rows had: a Connect button that appears to do nothing,
 * because a config gap and a permission denial produced the same silence.
 */
enum class TrackerState {
    /** Connected and holding credentials/permissions. */
    CONNECTED,

    /** Configured for this build and device; the user just needs to authorise. */
    READY,

    /** No OAuth client id in this build and none discoverable server-side. */
    NEEDS_SETUP,

    /** Health Connect is missing or needs an update on this device. */
    NOT_INSTALLED,

    /** Behind a paid tier the user isn't on. */
    LOCKED,
}

/**
 * One provider's complete state, ready to render — the shape the pop-up and
 * Settings both consume, so the two surfaces cannot describe a provider
 * differently.
 */
data class TrackerStatus(
    val provider: TrackerProvider,
    val state: TrackerState,
    /** One line naming what the app reads from this source. */
    val detail: String,
    /** Whether the row's primary action should do something. */
    val isConnected: Boolean,
    /** Whether this provider's auto-sync is on. */
    val autoSync: Boolean,
) {
    /** The row's action label, which is honest about what will happen. */
    val actionLabel: String
        get() = when {
            isConnected -> "Disconnect"
            state == TrackerState.READY -> "Connect"
            state == TrackerState.LOCKED -> "Upgrade"
            else -> "Not available"
        }

    /** True when tapping the action is meaningful. */
    val isActionable: Boolean
        get() = isConnected || state == TrackerState.READY || state == TrackerState.LOCKED
}

object TrackerConnections {

    /**
     * Which providers can track a habit with this icon, in the order they are
     * shown.
     *
     * Strava last on purpose: it is the only tier-gated one, so the two free
     * options appear first and a user on the free tier never has to read past a
     * lock to find something they can use.
     */
    fun sourcesFor(iconId: String?): List<TrackerProvider> {
        if (iconId.isNullOrBlank()) return emptyList()
        return buildList {
            if (MindsetHealthConnectClient.isActivitySupported(iconId)) {
                add(TrackerProvider.HEALTH_CONNECT)
            }
            if (PolarClient.isActivitySupported(iconId)) {
                add(TrackerProvider.POLAR)
            }
            // Strava records every sport, so it is offered for any sport icon
            // rather than only the ids in one hand-maintained list.
            if (isSportActivityIcon(iconId)) add(TrackerProvider.STRAVA)
        }
    }

    /**
     * The habits a tracker sweep should write into: every habit that names a
     * sport icon and has at least one source able to supply it.
     *
     * This is deliberately a list rather than "the first one". `runAutoSync` used
     * to sync only `habits.firstOrNull { … }`, so a user with Walk, Run and Gym
     * had exactly one of them ever updated from their tracker — the other two
     * sat permanently empty with no indication why. Syncing every eligible habit
     * is what "track habits like walking from those apps" actually means when a
     * user has more than one such habit.
     */
    fun trackableHabits(habits: List<Habit>): List<Habit> =
        habits.filter { sourcesFor(it.iconId).isNotEmpty() }

    /**
     * The current state of all three providers.
     *
     * Every fact here comes from the same source the connect flow itself reads
     * (`canAttemptConnect`, the Health Connect SDK status, the stored tokens), so
     * the row can never claim a provider is ready and then fail to open it.
     */
    fun statuses(
        context: Context,
        settings: AppSettings,
        tier: SubscriptionTier,
    ): List<TrackerStatus> = listOf(
        healthConnectStatus(context, settings),
        polarStatus(settings),
        stravaStatus(settings, tier),
    )

    /**
     * Health Connect: the only one whose availability is a device property rather
     * than a credential. `checkStatus` already distinguishes "not installed"
     * from "needs a provider update" from "ready", and both of the first two are
     * reported as [TrackerState.NOT_INSTALLED] with the SDK's own wording in
     * [TrackerStatus.detail] — the SDK's message ("needs an update") is more
     * useful than a generic "not available".
     */
    private fun healthConnectStatus(context: Context, settings: AppSettings): TrackerStatus {
        val connected = settings.healthConnectConnected
        val sdkStatus = runCatching { MindsetHealthConnectClient.checkStatus(context) }
            .getOrDefault(HealthConnectStatus.NotInstalled)
        // A connected state outranks the SDK probe: the permissions are held, so
        // the row is useful even if the status check is momentarily unavailable.
        if (connected) {
            return TrackerStatus(
                provider = TrackerProvider.HEALTH_CONNECT,
                state = TrackerState.CONNECTED,
                detail = "Steps and sleep sync from your phone & wearables.",
                isConnected = true,
                autoSync = settings.healthConnectAutoSync,
            )
        }
        val state = when (sdkStatus) {
            HealthConnectStatus.Ready, HealthConnectStatus.PermissionsNeeded -> TrackerState.READY
            HealthConnectStatus.NotInstalled, HealthConnectStatus.UpdateRequired ->
                TrackerState.NOT_INSTALLED
        }
        val detail = when (sdkStatus) {
            HealthConnectStatus.Ready, HealthConnectStatus.PermissionsNeeded ->
                "Reads steps, sleep and workouts from your phone and wearables. Free."
            HealthConnectStatus.UpdateRequired ->
                "Health Connect needs an update in your app store before it can be used."
            HealthConnectStatus.NotInstalled ->
                "Health Connect isn't on this device. Install it to sync steps and sleep. Free."
        }
        return TrackerStatus(
            provider = TrackerProvider.HEALTH_CONNECT,
            state = state,
            detail = detail,
            isConnected = false,
            autoSync = settings.healthConnectAutoSync,
        )
    }

    /**
     * Polar: needs an OAuth client id, which this build may not carry. When it
     * carries none, the id is resolved from the `polar-token-exchange` Edge
     * Function at connect time — so [PolarClient.canAttemptConnect] is the honest
     * test, and when it is false the server has no Polar credentials either.
     */
    private fun polarStatus(settings: AppSettings): TrackerStatus {
        if (!settings.polarAccessToken.isNullOrBlank()) {
            return TrackerStatus(
                provider = TrackerProvider.POLAR,
                state = TrackerState.CONNECTED,
                detail = "Daily activity and step totals sync from your Polar account.",
                isConnected = true,
                autoSync = settings.polarAutoSync,
            )
        }
        val ready = runCatching { PolarClient.canAttemptConnect }.getOrDefault(false)
        return TrackerStatus(
            provider = TrackerProvider.POLAR,
            state = if (ready) TrackerState.READY else TrackerState.NEEDS_SETUP,
            detail = if (ready) {
                "Syncs daily steps and activity from Polar Flow. Free."
            } else {
                // Names the actual missing piece rather than saying "unavailable".
                "Needs a Polar AccessLink client id (POLAR_CLIENT_ID) in this build " +
                    "or in the polar-token-exchange function."
            },
            isConnected = false,
            autoSync = settings.polarAutoSync,
        )
    }

    /** Strava: tier-gated, so its state depends on the subscription as well. */
    private fun stravaStatus(settings: AppSettings, tier: SubscriptionTier): TrackerStatus {
        val entitled = Entitlements.hasAccess(tier, Feature.STRAVA)
        if (!settings.stravaRefreshToken.isNullOrBlank()) {
            return TrackerStatus(
                provider = TrackerProvider.STRAVA,
                state = TrackerState.CONNECTED,
                detail = "Imports runs, rides and walks into your activity habits.",
                isConnected = true,
                autoSync = settings.stravaAutoSync,
            )
        }
        if (!entitled) {
            return TrackerStatus(
                provider = TrackerProvider.STRAVA,
                state = TrackerState.LOCKED,
                detail = "Included with Premium \u2014 imports runs, rides and walks.",
                isConnected = false,
                autoSync = settings.stravaAutoSync,
            )
        }
        val ready = runCatching { StravaAuthClient.canAttemptConnect }.getOrDefault(false)
        return TrackerStatus(
            provider = TrackerProvider.STRAVA,
            state = if (ready) TrackerState.READY else TrackerState.NEEDS_SETUP,
            detail = if (ready) {
                "Imports runs, rides and walks into your activity habits."
            } else {
                "Needs a Strava client id (STRAVA_CLIENT_ID) in this build or in the " +
                    "strava-token-exchange function."
            },
            isConnected = false,
            autoSync = settings.stravaAutoSync,
        )
    }
}
