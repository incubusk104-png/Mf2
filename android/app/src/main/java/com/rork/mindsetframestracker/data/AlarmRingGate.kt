package com.rork.mindsetframestracker.data

import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerStatus

/**
 * Whether a ringing alarm should offer to connect a fitness app, and why.
 *
 * ## The three conditions, and why each is necessary
 *
 * The request was precise about the first one and implied the other two, but all
 * three have to hold together or the offer misbehaves in a way the user notices:
 *
 *  1. **A fitness app can actually supply this habit.** [sources] must be
 *     non-empty. This is the user's own rule — *"if the habit does not involve a
 *     fitness app, do NOT show the connect option — just the alarm"* — and it is
 *     what keeps Strava off a journal entry's alarm. The list comes from
 *     [com.rork.mindsetframestracker.integrations.TrackerConnections.sourcesFor],
 *     so a habit is offered exactly the apps its own icon maps to, not a
 *     hardcoded pair.
 *  2. **Nothing is connected yet.** If a source for this habit is already
 *     connected, the activity is *already* being captured — the offer has nothing
 *     left to ask for, and showing it would read as the app forgetting what the
 *     user already did. Note this is checked against the providers *that can
 *     supply this habit*, not against "is anything connected": a user with Strava
 *     linked for their runs should still be offered Health Connect on their
 *     stretches if Health Connect is the free source for them.
 *  3. **This habit has not been offered before.** A one-time choice.
 *     [alreadyAsked] is per habit and persisted, so a habit that rings three
 *     times a day asks once — and a user who tapped "Not now" has answered and
 *     is not asked again. Re-asking is what turns a helpful prompt into a nag,
 *     and it is the specific behaviour this condition exists to prevent.
 *
 * ## Why this is a pure function rather than a conditional in the dialog
 *
 * The dialog is the ring path, which the project has no instrumented test
 * coverage for (there is no `androidTest` source set, so no Compose assertion can
 * run in CI). Putting the decision here makes every case assertable in a plain
 * JVM test — including the one that matters most, that a non-fitness habit
 * yields `false` and therefore renders no connect option at all.
 *
 * It also keeps the dialog honest about its inputs: the dialog renders the offer
 * it is told to render, so there is exactly one place where "should the connect
 * option be shown?" is answered.
 */
object AlarmRingGate {

    /**
     * True when this ring should make the connect offer.
     *
     * @param sources the providers that can supply this habit, from
     *   `TrackerConnections.sourcesFor(iconId)`. Empty means the habit has no
     *   fitness source, which is the non-fitness case — no offer.
     * @param statuses the caller's resolved provider states. Any source that is
     *   already connected suppresses the offer entirely.
     * @param alreadyAsked whether this habit has been offered before.
     */
    fun shouldOfferConnect(
        /** The providers that can supply this habit. */
        sources: List<TrackerProvider>,
        /** Each provider's resolved state, so "already connected" is knowable. */
        statuses: List<TrackerStatus>,
        /** True once this habit has been offered the connection (see [AlarmConnectPrompt]). */
        alreadyAsked: Boolean,
    ): Boolean {
        if (sources.isEmpty()) return false
        if (alreadyAsked) return false
        // Connected-ness is read only for the providers that can supply THIS
        // habit. Reading it for any provider would let an unrelated connection
        // suppress a perfectly good offer.
        val anySourceConnected = sources.any { provider ->
            statuses.firstOrNull { it.provider == provider }?.isConnected == true
        }
        return !anySourceConnected
    }

    /**
     * The sources that have just become connected, for the caller to sync.
     *
     * Extracted for the same reason as [shouldOfferConnect]: it is the piece of
     * the ring's capture path that decides *what* to import, and a plain function
     * of the statuses can be tested without a device. Returning the providers
     * rather than a boolean is what lets the caller pick the right sync call for
     * each — they are genuinely different requests, not one request with a
     * parameter.
     */
    fun connectedSources(
        sources: List<TrackerProvider>,
        statuses: List<TrackerStatus>,
    ): List<TrackerProvider> = sources.filter { provider ->
        statuses.firstOrNull { it.provider == provider }?.isConnected == true
    }
}
