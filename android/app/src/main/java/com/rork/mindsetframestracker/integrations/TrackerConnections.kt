package com.rork.mindsetframestracker.integrations

import android.content.Context
import com.rork.mindsetframestracker.billing.Entitlements
import com.rork.mindsetframestracker.billing.Feature
import com.rork.mindsetframestracker.billing.SubscriptionTier
import com.rork.mindsetframestracker.data.AppSettings
import com.rork.mindsetframestracker.data.Habit

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
    /**
     * The habit this provider is linked to, or null when it is linked to none.
     *
     * Carried on the status (rather than looked up by the row) so a rendered row
     * can never disagree with the import path about which habit owns this
     * provider — they read the same snapshot.
     */
    val linkedHabitId: String? = null,
    /**
     * The **name** of the habit holding the link, when that is a different habit
     * from the one being viewed.
     *
     * A name rather than an id because the only use is to tell the user where
     * their tracker currently points ("Linked to Walk"). Without it, tapping
     * Link on a second habit silently moved the tracker and the first habit's
     * statistics simply stopped arriving, with nothing on screen explaining it.
     */
    val linkedElsewhereLabel: String? = null,
    /**
     * True when this provider's link belongs to the habit currently on screen.
     *
     * Defaults to false so a status built without per-habit context never
     * *claims* a link — the safe direction, since a false negative only omits an
     * affordance while a false positive would offer to unlink something that
     * belongs to another habit.
     */
    val linkedToThisHabit: Boolean = false,
) {
    /**
     * The row's action label, which is honest about what will happen.
     *
     * ## Why this depends on the per-habit link and not just connectivity
     *
     * The account-level connected state (`isConnected`) answers "has the user
     * authorised Strava", not "is Strava serving *this* habit". The two came
     * apart the moment the link became per habit, and the old label — a flat
     * `isConnected -> "Disconnect"` — then read as *"this habit is connected"* on
     * a habit that had never been bound, and as *"disconnect my Strava account"*
     * on a habit where the user only meant to stop using it here.
     *
     * The distinction it now draws: **Unlink** (this habit stops using it; the
     * account stays connected, and the user's other habits are untouched) versus
     * **Disconnect** (reserved for the account-level action in Settings).
     */
    val actionLabel: String
        get() = when {
            // This habit holds the link: the only correct action is to give it up.
            linkedToThisHabit && isConnected -> "Unlink"
            // Authorised at the account level but not serving this habit — one tap,
            // no OAuth round trip, because the grant already exists.
            !linkedToThisHabit && isConnected -> "Link"
            linkedToThisHabit -> "Finish setup"
            state == TrackerState.READY -> "Connect"
            state == TrackerState.LOCKED -> "Upgrade"
            else -> "Not available"
        }

    /** True when tapping the action is meaningful. */
    val isActionable: Boolean
        get() = linkedToThisHabit ||
            !linkedToThisHabit && (isConnected || state == TrackerState.READY) ||
            state == TrackerState.LOCKED
}

object TrackerConnections {

    /**
     * Which providers *could* serve a habit with this icon.
     *
     * **This is not the binding.** It answers "what is this icon compatible
     * with", which is the right question for deciding what to OFFER, and the
     * wrong question for deciding what to IMPORT. Every call site used to treat
     * it as the latter, and that is the defect the per-habit link removes: a
     * habit the user never connected anything to was advertised as having three
     * sources, and was swept by all three.
     *
     * Use [HabitTrackerLinks.Index.activeProvidersFor] to ask what a habit is
     * actually linked to. Kept as a named function only so existing UI that
     * genuinely wants the candidate list (the connect sheet's offer) does not
     * have to reach across a package; new code should call [candidateSourcesFor].
     */
    @Deprecated(
        "Name and intent are now split: use candidateSourcesFor(iconId) for what may be offered, " +
            "and HabitTrackerLinks for what is actually linked. See HabitTrackerLink.kt.",
        ReplaceWith("candidateSourcesFor(iconId)"),
    )
    fun sourcesFor(iconId: String?): List<TrackerProvider> = candidateSourcesFor(iconId)

    /**
     * The habits a tracker sweep should write into: every habit with a real
     * tracker link whose icon the linked provider can actually supply.
     *
     * ## What changed, and why the old rule was wrong
     *
     * This used to be `habits.filter { sourcesFor(it.iconId).isNotEmpty() }` —
     * pure icon compatibility, with no knowledge of what the user had connected.
     * `runAutoSync` then ran **every** connected provider against **every** such
     * habit, so a user with a Walk and a Run habit had one account-wide Strava
     * report appended under the Walk id and again under the Run id; the ids are
     * `strava_<activityId>`, so the rows were distinct, nothing deduped them, and
     * the same workout was counted against two habits. The same loop wrote
     * Polar's *unattributable daily step roll-up* onto every sport habit.
     *
     * Requiring a binding makes "which habit does this data belong to?" a
     * question with an answer the user supplied, instead of one this function
     * guesses. An unbound habit is not swept at all — which is the correct
     * outcome, not a regression: there is no tracker data that belongs to it.
     *
     * Kept (rather than deleted) because several callers ask the coarse question
     * "is there anything to sweep at all", and it must now answer it from the
     * links.
     */
    fun trackableHabits(habits: List<Habit>): List<Habit> =
        HabitTrackerLinks.of(habits).linkedHabits(habits).map { it.first }

    /**
     * The current state of all three providers.
     *
     * Every fact here comes from the same source the connect flow itself reads
     * (`canAttemptConnect`, the Health Connect SDK status, the stored tokens), so
     * the row can never claim a provider is ready and then fail to open it.
     *
     * ## Per-habit links
     *
     * Each row also carries which habit its provider is linked to, so a row can
     * say "Linked to Walk" instead of offering to reconnect a provider that is
     * already pointed at another habit.
     */
    fun statuses(
        context: Context,
        settings: AppSettings,
        tier: SubscriptionTier,
        /**
         * The habits whose bindings decide `linkedToThisHabit` / `linkedElsewhereLabel`.
         *
         * Defaults to empty so a caller that only wants the account-level view
         * (Settings) is unaffected — and, importantly, so an omitted list yields
         * `linkedElsewhereLabel == null` rather than a wrong habit name.
         */
        habits: List<Habit> = emptyList(),
        /** The habit whose dialog is rendering, for the per-habit link flags. */
        forHabitId: String? = null,
    ): List<TrackerStatus> {
        // One snapshot per read, so all three rows resolve the same bindings and
        // the dialog cannot show two providers disagreeing about one habit.
        val links = LinkContext(HabitTrackerLinks.of(habits), habits, forHabitId)
        return listOf(
            healthConnectStatus(context, settings).withLink(links),
            polarStatus(settings).withLink(links),
            stravaStatus(settings, tier).withLink(links),
        )
    }

    /**
     * Everything needed to resolve one provider's per-habit link flags.
     *
     * Bundled rather than passed as three parameters to each status function so
     * the three cannot be given inconsistent values — the failure that would let
     * one row claim a link the others deny.
     */
    private data class LinkContext(
        val index: HabitTrackerLinks.Index,
        val habits: List<Habit>,
        val forHabitId: String?,
    )

    /**
     * Applies the binding to an account-level status.
     *
     * Kept as one function so all three providers resolve their link flags
     * identically: three copies of "who holds this provider" is how one row ends
     * up claiming a link the others deny.
     */
    private fun TrackerStatus.withLink(links: LinkContext): TrackerStatus {
        // No holder means the provider is authorised but serving no habit — left
        // unlinked rather than defaulting to "this habit", which is the guess
        // that attributed one account-wide report to every sport habit.
        val holder = links.index.habitFor(provider) ?: return this
        return copy(
            linkedHabitId = holder,
            linkedElsewhereLabel = links.habits.firstOrNull { it.id == holder }
                ?.takeIf { holder != links.forHabitId }
                ?.name,
            linkedToThisHabit = holder == links.forHabitId,
        )
    }

    /**
     * The same account-level statuses, re-scoped to one habit.
     *
     * The account-level [statuses] answer "has the user authorised this service",
     * which is a fact about the *account*. A dialog needs the other half: "is
     * this service serving THIS habit?" Re-scoping from the already-resolved base
     * list rather than recomputing means the dialog cannot disagree with the
     * screen that opened it about whether a provider is connected — only about
     * which habit it serves, which is exactly the question being asked.
     */
    fun forHabit(
        base: List<TrackerStatus>,
        habits: List<Habit>,
        forHabitId: String?,
    ): List<TrackerStatus> {
        val links = LinkContext(HabitTrackerLinks.of(habits), habits, forHabitId)
        return base.map { it.withLink(links) }
    }

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
