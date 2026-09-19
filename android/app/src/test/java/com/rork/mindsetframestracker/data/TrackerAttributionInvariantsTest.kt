package com.rork.mindsetframestracker.data

import com.rork.mindsetframestracker.integrations.HabitTrackerLinks
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.canonicalActivityType
import com.rork.mindsetframestracker.integrations.withCanonicalActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **stability** of the per-habit tracker link and of activity-type
 * canonicalisation — the invariants that must hold when an operation runs more
 * than once.
 *
 * ## Why this is separate from [HabitTrackerLinkTest]
 *
 * That suite covers decisions; this one covers *repetition*. Every operation
 * here is reachable twice in normal use, and a transformation that is not
 * idempotent produces a different stored state on the second pass than on the
 * first — which shows up as data that silently changes while the user does
 * nothing:
 *
 *  - `MindsetRepository.saveActivityRecord` canonicalises **every** write, so a
 *    record restored from the cloud (written by a build with different rules) is
 *    re-normalised on the next save. If that were not idempotent, each save
 *    would shift the stored type.
 *  - `AppData` is serialised to the JSON blob and re-read constantly, so the
 *    link list must survive a round trip **unchanged in value**, or an
 *    otherwise-no-op save would look like an edit and be pushed forever.
 *  - A push writes the sorted provider list; a re-push of an unchanged link set
 *    must produce the identical value, which is what `sortProviderIds` is for.
 */
class TrackerAttributionInvariantsTest {

    private fun record(type: String) = ActivityRecord(
        id = "r1",
        habitId = "walk",
        source = "strava",
        activityType = type,
        timestamp = 1_700_000_000_000L,
    )

    // ── Canonicalisation is idempotent ───────────────────────────────────────

    @Test
    fun `canonicalising an already-canonical record changes nothing`() {
        // saveActivityRecord re-canonicalises on EVERY write, including a write
        // whose only change was a note. If this were not a fixed point, the
        // stored type would drift one step per save with the user doing nothing.
        val once = record("Ride").withCanonicalActivityType("walking")
        val twice = once.withCanonicalActivityType("walking")

        assertEquals(once, twice)
        assertEquals("strava_ride", twice.activityType)
    }

    @Test
    fun `canonicalisation is stable for every provider token shape`() {
        // A provider token, a catalog id, and a non-sport type all have to be
        // fixed points after one pass — the three shapes the writers produce.
        listOf("Ride", "NordicSki", "walking", "strava_swim", "sleep", "activity")
            .forEach { token ->
                val once = canonicalActivityType(token, "walking")
                assertEquals(
                    "$token was not a fixed point (first=$once)",
                    once,
                    canonicalActivityType(once, "walking"),
                )
            }
    }

    @Test
    fun `canonicalisation never yields a blank type`() {
        // A blank type would render as an empty label in the activity sheet and
        // match nothing. Every input shape must still produce a usable string.
        listOf("", "   ", "Ride", "???", "walking", "sleep").forEach { token ->
            val out = canonicalActivityType(token, "walking")
            assertTrue("blank output for input '$token'", out.isNotBlank())
        }
        // And with no habit icon to fall back to, still not blank.
        listOf("", "???", "Walk").forEach { token ->
            assertTrue(canonicalActivityType(token, null).isNotBlank())
        }
    }

    @Test
    fun `canonicalising preserves the record identity and the habit link`() {
        // The destructive case: a normalisation that changed the id or the habit
        // link would either duplicate the row (new id) or move it to another
        // habit — both silently. Only `activityType` may change.
        val original = record("Ride")
        val canonical = original.withCanonicalActivityType("running")

        assertEquals(original.id, canonical.id)
        assertEquals(original.habitId, canonical.habitId)
        assertEquals(original.timestamp, canonical.timestamp)
        assertEquals(original.source, canonical.source)
        assertEquals("strava_ride", canonical.activityType)
    }

    // ── The link list is stable across storage ───────────────────────────────

    @Test
    fun `the stored provider order is canonical regardless of input order`() {
        // The push writes this value. Two different orderings of the same link
        // set must not produce two different stored values, or an unchanged set
        // would look like a change on every push and re-write the row forever.
        val forward = HabitTrackerLinks.sortProviderIds(
            listOf("HEALTH_CONNECT", "POLAR", "STRAVA"),
        )
        val backward = HabitTrackerLinks.sortProviderIds(
            listOf("STRAVA", "POLAR", "HEALTH_CONNECT"),
        )

        assertEquals(forward, backward)
        // Canonical order is the enum's own declaration order.
        assertEquals(listOf("HEALTH_CONNECT", "STRAVA", "POLAR"), forward)
    }

    @Test
    fun `sorting the provider list is idempotent and drops duplicates`() {
        // A habit that listed STRAVA twice (only reachable from a hand-edited
        // share code) must collapse to one, and re-sorting must not change it.
        val sorted = HabitTrackerLinks.sortProviderIds(listOf("STRAVA", "STRAVA", "POLAR"))

        assertEquals(listOf("STRAVA", "POLAR"), sorted)
        assertEquals(sorted, HabitTrackerLinks.sortProviderIds(sorted))
    }

    @Test
    fun `sorting drops names this build does not know rather than keeping them`() {
        // A provider written by a newer build must not be carried through and
        // written back in a position this build cannot justify.
        val sorted = HabitTrackerLinks.sortProviderIds(listOf("FITBIT", "STRAVA"))

        assertEquals(listOf("STRAVA"), sorted)
    }

    // ── Names and edge cases ────────────────────────────────────────────────

    @Test
    fun `a habit name with emoji and punctuation survives the link rules untouched`() {
        // The link logic must not be sensitive to the habit's name — a name is
        // free text, and mangling it while binding a tracker would be a
        // spectacularly confusing bug.
        val habit = Habit(
            id = "walk",
            name = "Walk 🚶‍♂️ 30min — \"morning\" ☀️ / 日本語",
            iconId = "walking",
            trackerProviderIds = listOf("STRAVA"),
        )

        val bound = HabitTrackerLinks.bindFor(listOf(habit), "walk", TrackerProvider.STRAVA)

        assertEquals(
            "Walk 🚶‍♂️ 30min — \"morning\" ☀️ / 日本語",
            bound.habits.single().name,
        )
    }

    @Test
    fun `binding a second provider leaves the first in the list`() {
        // Two sources for one habit is legitimate; the second bind must add, not
        // replace. Replacement here would silently drop the user's first source.
        val habit = Habit(id = "walk", name = "Walk", iconId = "walking")

        val one = HabitTrackerLinks.bindFor(listOf(habit), "walk", TrackerProvider.STRAVA)
        val two = HabitTrackerLinks.bindFor(one.habits, "walk", TrackerProvider.HEALTH_CONNECT)

        assertEquals(listOf("HEALTH_CONNECT", "STRAVA"), two.habits.single().trackerProviderIds)
    }

    @Test
    fun `the index reports no conflict for a well-formed link set`() {
        // `displaced` being empty is the normal case, and the UI reads it to
        // decide nothing needs explaining. A false positive there would show a
        // scary message about a conflict that does not exist.
        val habits = listOf(
            Habit(id = "a", name = "A", iconId = "walking", trackerProviderIds = listOf("STRAVA")),
            Habit(id = "b", name = "B", iconId = "running", trackerProviderIds = listOf("POLAR")),
            Habit(id = "c", name = "C", iconId = "gym"),
        )

        assertTrue(HabitTrackerLinks.of(habits).displaced.isEmpty())
    }

    // ── The JSON blob is the storage format, so the link must round trip ─────

    @Test
    fun `a linked habit survives a full AppData JSON round trip`() {
        // AppData is the SharedPreferences blob. Losing the link here would mean
        // the user re-links after every restart, and the loss would look like the
        // app forgetting rather than a serialization gap.
        val data = AppData(
            habits = listOf(
                Habit(
                    id = "walk",
                    name = "Walk",
                    iconId = "walking",
                    trackerProviderIds = listOf("HEALTH_CONNECT", "STRAVA"),
                ),
            ),
        )

        val decoded = kotlinx.serialization.json.Json.decodeFromString<AppData>(
            kotlinx.serialization.json.Json.encodeToString(data),
        )

        assertEquals(
            listOf("HEALTH_CONNECT", "STRAVA"),
            decoded.habits.single().trackerProviderIds,
        )
        // And the resolved index agrees, so the sweep still knows where to write.
        assertEquals(
            "walk",
            HabitTrackerLinks.of(decoded.habits).habitFor(TrackerProvider.HEALTH_CONNECT),
        )
    }
}
