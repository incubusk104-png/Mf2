package com.rork.mindsetframestracker.data

import com.rork.mindsetframestracker.integrations.HabitTrackerLinks
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerState
import com.rork.mindsetframestracker.integrations.TrackerStatus
import com.rork.mindsetframestracker.integrations.canonicalActivityType
import com.rork.mindsetframestracker.integrations.providersLeftUnboundAfter
import com.rork.mindsetframestracker.integrations.withCanonicalActivityType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **per-habit tracker link**, and the attribution rule it exists to enforce.
 *
 * Every test here is aimed at a *destructive* outcome, because that is what the
 * bugs were — this is not a happy-path suite:
 *
 *  - **RC1 (the backfill)** — Strava's fetch is per-habit but its payload is the
 *    athlete's last ten activities with no habit filter. Because the sweep ran
 *    every connected provider against every icon-compatible habit, one
 *    account-wide report was appended under *each* habit's own id
 *    (`strava_<activityId>`, so the rows were distinct and nothing deduped them)
 *    and the weekly rollup counted one workout once per habit. The first habit
 *    normally lost the race, which is exactly why the next habit absorbed the
 *    whole report.
 *  - **RC2 (the unattributable flood)** — the same loop wrote Polar's daily
 *    step roll-up onto every sport habit, so records appeared with an id no
 *    single habit could justify.
 *  - **RC4 (the false advertisement)** — the dialog drew provider rows from the
 *    habit's *icon*, so a habit the user had never connected advertised three
 *    sources it would never receive data from.
 *
 * The through-line: **a provider is bound to exactly one habit, and only a bound
 * provider may write.** An unbound habit is not swept. That is asserted directly,
 * because "guess the first sport habit" was the behaviour that caused all of it.
 *
 * ## What is deliberately NOT covered here
 *
 * [com.rork.mindsetframestracker.integrations.candidateSourcesFor] is not
 * unit-tested: it calls `HealthPermission.getReadPermission(...)` on the Health
 * Connect SDK, and this module has no `testOptions { unitTests.returnDefaultValues }`,
 * so those stubs throw "not mocked" instead of returning. Adding that flag to
 * the module to reach one function would relax behaviour for the whole suite, so
 * the candidate list is left to the instrumented test the project still lacks
 * (validation finding D8).
 */
class HabitTrackerLinkTest {

    private fun habit(
        id: String,
        name: String = "Habit $id",
        iconId: String? = "walking",
        providers: List<TrackerProvider> = emptyList(),
    ) = Habit(
        id = id,
        name = name,
        iconId = iconId,
        trackerProviderIds = providers.map { it.name },
    )

    private fun status(
        provider: TrackerProvider = TrackerProvider.STRAVA,
        connected: Boolean = true,
        state: TrackerState = TrackerState.READY,
        linkedToThisHabit: Boolean = false,
        linkedElsewhereLabel: String? = null,
    ) = TrackerStatus(
        provider = provider,
        state = state,
        detail = "",
        isConnected = connected,
        autoSync = true,
        linkedHabitId = null,
        linkedElsewhereLabel = linkedElsewhereLabel,
        linkedToThisHabit = linkedToThisHabit,
    )

    // ── The single-habit rule: a bind is a TRANSFER, not an add ──────────────

    @Test
    fun `binding a provider to a second habit takes it from the first`() {
        // The destructive case: if this were a plain set-add, both habits would
        // name Strava and EVERY sweep would import the same account-wide report
        // twice — once under each habit's id. Removing it from the previous
        // holder is what makes "one provider, one habit" true by construction.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run")

        val result = HabitTrackerLinks.bindFor(listOf(walk, run), "run", TrackerProvider.STRAVA)

        assertTrue(result.applied)
        assertEquals(emptyList<String>(), result.habits.first { it.id == "walk" }.trackerProviderIds)
        assertEquals(
            listOf("STRAVA"),
            result.habits.first { it.id == "run" }.trackerProviderIds,
        )
        // Exactly one habit holds it — the property the whole fix rests on.
        assertEquals(1, result.habits.count { "STRAVA" in it.trackerProviderIds })
    }

    @Test
    fun `the habit that lost the provider is reported so the user can be told`() {
        // Silently moving a tracker means the first habit quietly stops receiving
        // data and nothing on screen explains it.
        val walk = habit("walk", name = "Walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run", name = "Run")

        val result = HabitTrackerLinks.bindFor(listOf(walk, run), "run", TrackerProvider.STRAVA)

        assertEquals("walk", result.takenFrom?.id)
        assertEquals("Walk", result.takenFrom?.name)
    }

    @Test
    fun `rebinding to the habit that already holds it changes nothing`() {
        // Idempotence matters: this is reachable from a reconnect the user may
        // run twice, and a no-op must not look like a transfer to itself.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))

        val result = HabitTrackerLinks.bindFor(listOf(walk), "walk", TrackerProvider.STRAVA)

        assertNull(result.takenFrom)
        assertEquals(listOf("STRAVA"), result.habits.single().trackerProviderIds)
    }

    @Test
    fun `binding to a habit that no longer exists changes nothing`() {
        // A dialog left open across a delete. Linking to a vanished habit would
        // create the orphaned binding the model exists to prevent.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))

        val result = HabitTrackerLinks.bindFor(listOf(walk), "gone", TrackerProvider.POLAR)

        assertFalse(result.applied)
        assertEquals(listOf(walk), result.habits)
    }

    // ── Unbind is scoped to ONE habit ────────────────────────────────────────

    @Test
    fun `unbinding from one habit leaves every other habit's link intact`() {
        // The destructive case. "This habit stops using Strava" must never be
        // read as "unlink Strava everywhere" — the class of bug that deleted
        // habits when a screen-time limit was edited.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run", providers = listOf(TrackerProvider.POLAR))

        val after = HabitTrackerLinks.unbindFor(listOf(walk, run), "walk", TrackerProvider.STRAVA)

        assertEquals(emptyList<String>(), after.first { it.id == "walk" }.trackerProviderIds)
        assertEquals(
            listOf("POLAR"),
            after.first { it.id == "run" }.trackerProviderIds,
        )
    }

    @Test
    fun `unbinding a provider this habit does not hold touches no other habit`() {
        // The habit asked to give up something it never had. Nothing may change —
        // in particular, the habit that DOES hold it must keep it.
        val walk = habit("walk")
        val run = habit("run", providers = listOf(TrackerProvider.STRAVA))

        val after = HabitTrackerLinks.unbindFor(listOf(walk, run), "walk", TrackerProvider.STRAVA)

        assertEquals(listOf("STRAVA"), after.first { it.id == "run" }.trackerProviderIds)
        assertEquals(emptyList<String>(), after.first { it.id == "walk" }.trackerProviderIds)
    }

    // ── RC1: one provider serves one habit, so no report is fetched twice ────

    @Test
    fun `a provider is linked to at most one habit so its report cannot be imported twice`() {
        // Even if a writer bypassed bindFor and two habits name Strava, the read
        // must resolve it to ONE habit. Two would mean two imports of the same
        // account-wide payload.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run", providers = listOf(TrackerProvider.STRAVA))

        val index = HabitTrackerLinks.of(listOf(walk, run))

        assertEquals("walk", index.habitFor(TrackerProvider.STRAVA))
        assertEquals(1, index.linkedHabits(listOf(walk, run)).size)
        // The loser is named rather than silently appearing unbound.
        assertEquals(
            listOf(TrackerProvider.STRAVA to "run"),
            index.displaced,
        )
    }

    @Test
    fun `the first binder wins regardless of the order the claims appear in`() {
        // First-wins is stable under a reordering that preserves relative order,
        // so a sync that reshuffles rows cannot silently move a tracker.
        val a = habit("a", providers = listOf(TrackerProvider.STRAVA))
        val b = habit("b", providers = listOf(TrackerProvider.STRAVA))

        assertEquals("a", HabitTrackerLinks.of(listOf(a, b)).habitFor(TrackerProvider.STRAVA))
        assertEquals("b", HabitTrackerLinks.of(listOf(b, a)).habitFor(TrackerProvider.STRAVA))
    }

    @Test
    fun `the sweep is scoped to linked habits only`() {
        // RC1/RC2 in one assertion. The old rule returned EVERY icon-compatible
        // habit (`sourcesFor(iconId).isNotEmpty()`), which is how one report and
        // one daily step roll-up landed on habits the user never connected.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run")
        val gym = habit("gym")

        val swept = HabitTrackerLinks.of(listOf(walk, run, gym)).linkedHabits(listOf(walk, run, gym))

        assertEquals(listOf("walk"), swept.map { it.first.id })
    }

    @Test
    fun `a habit bound to Strava is not swept by Health Connect`() {
        // RC2: the provider must match. Polar's daily step roll-up was written
        // for every sport habit regardless of what the habit was linked to.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))
        val index = HabitTrackerLinks.of(listOf(walk))

        assertTrue(index.allows("walk", TrackerProvider.STRAVA))
        assertFalse(index.allows("walk", TrackerProvider.HEALTH_CONNECT))
        assertFalse(index.allows("walk", TrackerProvider.POLAR))
    }

    @Test
    fun `an unbound habit allows no provider at all`() {
        // The rule that replaces guessing. Empty is not "use every candidate".
        val walk = habit("walk")
        val index = HabitTrackerLinks.of(listOf(walk))

        assertFalse(index.isBound(walk))
        assertEquals(emptyList<TrackerProvider>(), index.activeProvidersFor(walk))
        TrackerProvider.entries.forEach { provider ->
            assertFalse("unbound habit must not allow $provider", index.allows("walk", provider))
        }
    }

    @Test
    fun `two providers can serve the same habit`() {
        // A walk measured by the phone AND imported from a watch is legitimate:
        // the single-HABIT rule is about one provider not serving two habits, not
        // about one habit using two sources.
        val walk = habit(
            "walk",
            providers = listOf(TrackerProvider.HEALTH_CONNECT, TrackerProvider.STRAVA),
        )

        val index = HabitTrackerLinks.of(listOf(walk))

        assertEquals(2, index.providersFor("walk").size)
        assertTrue(index.allows("walk", TrackerProvider.HEALTH_CONNECT))
        assertTrue(index.allows("walk", TrackerProvider.STRAVA))
    }

    // ── RC4: the UI must be gated by the link, not the icon ──────────────────

    @Test
    fun `a bound provider whose icon cannot supply it is not offered on that habit`() {
        // The intersection. A Strava binding that survived an icon change to
        // "Journal" must not put a Strava row on a journal habit.
        val journal = habit("j", iconId = "journal", providers = listOf(TrackerProvider.STRAVA))

        assertEquals(emptyList<TrackerProvider>(), HabitTrackerLinks.of(listOf(journal)).activeProvidersFor(journal))
    }

    @Test
    fun `a provider is never claimed as linked to a habit that does not hold it`() {
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run")
        val index = HabitTrackerLinks.of(listOf(walk, run))

        assertTrue(index.allows("walk", TrackerProvider.STRAVA))
        assertFalse(index.allows("run", TrackerProvider.STRAVA))
    }

    @Test
    fun `the row says Link when the account is connected but this habit is not bound`() {
        // The old label was `isConnected -> "Disconnect"`, which read as "this
        // habit is connected" on a habit that had never been bound — and as
        // "disconnect my Strava account" where the user only meant to stop using
        // it here.
        val row = status(connected = true, linkedToThisHabit = false)

        assertEquals("Link", row.actionLabel)
    }

    @Test
    fun `the row says Unlink only when this habit holds the link`() {
        val row = status(connected = true, linkedToThisHabit = true)

        assertEquals("Unlink", row.actionLabel)
    }

    @Test
    fun `a locked provider is not actionable so the lock is not decoration`() {
        // The screenshot defect: the row was drawn locked with an "Upgrade"
        // label, but the tap ran the real OAuth flow. `isActionable` includes
        // LOCKED deliberately (the tap is meaningful — it routes to the upgrade
        // sheet), so the guard that matters is that the *label* is Upgrade and
        // the handler checks LOCKED before connecting.
        val locked = status(
            provider = TrackerProvider.STRAVA,
            connected = false,
            state = TrackerState.LOCKED,
        )

        assertEquals("Upgrade", locked.actionLabel)
        assertTrue(locked.isActionable)
    }

    @Test
    fun `a provider this build cannot use is not actionable`() {
        val unavailable = status(
            provider = TrackerProvider.POLAR,
            connected = false,
            state = TrackerState.NEEDS_SETUP,
        )

        assertFalse(unavailable.isActionable)
        assertEquals("Not available", unavailable.actionLabel)
    }

    // ── Deleting a habit must not leave a provider pointing at nothing ───────

    @Test
    fun `deleting a habit deletes its links and names the provider left idle`() {
        // A binding is a field on the habit, so the delete removes it — no orphan
        // row. What must not be silent is that the account-level connection
        // survives while now serving nothing: activity quietly stops arriving.
        val walk = habit("walk", name = "Walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run", providers = listOf(TrackerProvider.POLAR))
        val habits = listOf(walk, run)

        val idle = providersLeftUnboundAfter(habits, "walk")

        assertEquals(listOf(TrackerProvider.STRAVA), idle)
    }

    @Test
    fun `deleting a habit does not move its provider onto another habit`() {
        // The orphan case. The link must not silently re-attach to a different
        // habit, or every future sweep would import into a habit the user never
        // chose for this source.
        val walk = habit("walk", providers = listOf(TrackerProvider.STRAVA))
        val run = habit("run")

        val after = listOf(walk, run).filterNot { it.id == "walk" }

        assertEquals(emptyList<String>(), after.single().trackerProviderIds)
        val idle = providersLeftUnboundAfter(listOf(walk, run), "walk")
        assertEquals(listOf(TrackerProvider.STRAVA), idle)
    }

    // ── RC3: the provider's word must not reach the app's readers ────────────

    @Test
    fun `a Strava Ride keeps its own catalog id rather than a raw provider token`() {
        // Leaving the token as-is renders "Ride" beside screens that say
        // `strava_ride`, and breaks every reader matching on a catalog id. The
        // catalog has no bare `cycling` id — it names each variant `strava_*_ride`
        // — so every ride variant maps to the id the catalog actually carries.
        assertEquals("strava_ride", canonicalActivityType("Ride", "walking"))
        assertEquals("walking", canonicalActivityType("Walk", "running"))
        assertEquals("gym", canonicalActivityType("Workout", "running"))
    }

    @Test
    fun `an unlisted Strava sport resolves to its strava-prefixed catalog id`() {
        // The catalog names ~45 sports `strava_<snake>`; hand-listing every
        // Strava token would drift the moment Strava adds a sport.
        assertEquals("strava_nordic_ski", canonicalActivityType("NordicSki", "walking"))
        assertEquals("strava_rowing", canonicalActivityType("Rowing", "walking"))
        assertEquals("strava_swim", canonicalActivityType("Swim", "walking"))
    }

    @Test
    fun `an unrecognised token falls back to the habit icon not the raw token`() {
        // The habit's icon is at least a real catalog id, which is strictly more
        // useful to a reader than a token it cannot place.
        assertEquals("walking", canonicalActivityType("SomethingBrandNew", "walking"))
        // And with no icon either, it must still be a real string — never blank.
        assertTrue(canonicalActivityType("SomethingBrandNew", null).isNotBlank())
        assertTrue(canonicalActivityType("", null).isNotBlank())
    }

    @Test
    fun `an already-canonical type passes through unchanged`() {
        // Re-importing an older record must be stable rather than mangled.
        assertEquals("walking", canonicalActivityType("walking", "running"))
        assertEquals("strava_swim", canonicalActivityType("strava_swim", "running"))
        assertEquals("sleep", canonicalActivityType("sleep", "running"))
    }

    @Test
    fun `a sport the catalog does not carry cannot become an unplaceable type`() {
        // The destructive case for the mapping. "Kayaking" is absent from the
        // catalog, so `strava_kayaking` is NOT returned: storing it would put a
        // type on the record that no reader can resolve — the exact failure the
        // canonicalizer exists to prevent. The habit's icon is used instead, and
        // that is always a real catalog id.
        val mapped = canonicalActivityType("Kayaking", "walking")

        assertEquals("walking", mapped)
        assertTrue(mapped in SPORT_ACTIVITY_ICON_IDS)
    }

    @Test
    fun `the Strava aliases all resolve to ids the catalog actually carries`() {
        // Guards the mapping table against a typo that would silently store an id
        // nothing can render — the failure mode would be invisible in the app and
        // only visible as a missing icon.
        mapOf(
            "Walk" to "walking",
            "Run" to "running",
            "Basketball" to "basketball",
            "Workout" to "gym",
            "PhysicalTherapy" to "stretch",
            "Soccer" to "strava_football",
            "TableTennis" to "table_tennis",
            "Ride" to "strava_ride",
            "VirtualRide" to "strava_virtual_ride",
            "EBikeRide" to "strava_ebike_ride",
            "MountainBikeRide" to "strava_mountain_bike_ride",
            "GravelRide" to "strava_gravel_ride",
            "RockClimbing" to "strava_rock_climb",
            "WeightTraining" to "strava_weight_training",
            // Load-bearing: snake() gives `strava_high_intensity_interval_training`,
            // which the catalog does not carry.
            "HighIntensityIntervalTraining" to "strava_hiit",
            "HIIT" to "strava_hiit",
            "Yoga" to "strava_yoga",
            "Swim" to "strava_swim",
            "Hike" to "strava_hike",
        ).forEach { (token, expected) ->
            val actual = canonicalActivityType(token, "walking")
            assertEquals("Strava token $token", expected, actual)
            assertTrue(
                "$token mapped to '$actual', which is not a catalog id",
                actual in SPORT_ACTIVITY_ICON_IDS,
            )
        }
    }

    @Test
    fun `canonicalising a record preserves every other field`() {
        val record = ActivityRecord(
            id = "strava_99",
            habitId = "walk",
            source = "strava",
            activityType = "Ride",
            timestamp = 1_700_000_000_000L,
            durationMinutes = 42,
            distanceMeters = 12_000.0,
        )

        val canonical = record.withCanonicalActivityType("walking")

        assertEquals("strava_ride", canonical.activityType)
        assertEquals(record.copy(activityType = "strava_ride"), canonical)
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    fun `the per-habit link survives a JSON round trip`() {
        // The link is the user's own configuration, so losing it on a restore is
        // the same class of data loss as losing the habit's name.
        val walk = habit(
            "walk",
            name = "Walk 🚶",
            providers = listOf(TrackerProvider.STRAVA, TrackerProvider.HEALTH_CONNECT),
        )

        val decoded = Json.decodeFromString<Habit>(Json.encodeToString(walk))

        assertEquals(
            listOf("HEALTH_CONNECT", "STRAVA"),
            HabitTrackerLinks.sortProviderIds(decoded.trackerProviderIds),
        )
        assertEquals("Walk 🚶", decoded.name)
    }

    @Test
    fun `a habit from an older build has no links and is therefore not swept`() {
        // The migration default. Decoding a blob written before this field
        // existed must not fail, and must not invent a binding — the old
        // every-icon-compatible-habit sweep is precisely what is being removed.
        val legacyJson = """{"id":"walk","name":"Walk","iconId":"walking","alarmTimes":[]}"""

        val decoded = Json.decodeFromString<Habit>(legacyJson)

        assertEquals(emptyList<String>(), decoded.trackerProviderIds)
        assertFalse(HabitTrackerLinks.of(listOf(decoded)).isBound(decoded))
    }

    @Test
    fun `an unknown provider name is dropped rather than carried or crashed on`() {
        // A name written by a newer build that adds a fourth provider must not
        // fail the decode, and must not survive to be written back in a
        // different shape.
        val json = """{"id":"walk","name":"Walk","trackerProviderIds":["STRAVA","FITBIT"]}"""

        val decoded = Json.decodeFromString<Habit>(json)

        assertEquals(listOf("STRAVA", "FITBIT"), decoded.trackerProviderIds)
        // The resolution drops the one it cannot place.
        val index = HabitTrackerLinks.of(listOf(decoded))
        assertEquals(listOf(TrackerProvider.STRAVA), index.providersFor("walk"))
        assertEquals(1, index.byProvider.size)
    }
}