package com.rork.mindsetframestracker.data

import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerState
import com.rork.mindsetframestracker.integrations.TrackerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the alarm's dialog is allowed to offer, and what it is allowed to say.
 *
 * ## The request these tests pin
 *
 * *"Remove the 'connect fitness tracker' step from the alarm flow. Instead, when
 * the alarm goes off, show the user a dialog ... offering to connect Strava,
 * Google Health, etc. ... If the habit does not involve a fitness app, do NOT
 * show the connect option — just the alarm. Also add personal dialogs based on
 * each habit's tools."*
 *
 * Three separate claims, and each one is easy to regress in a way no compiler
 * catches:
 *
 *  1. **The connect option is absent for a non-fitness habit.** Not disabled,
 *     not explained — absent. A `||` slipped into a condition, or a fallback
 *     provider list, silently puts Strava on a journal entry's alarm and every
 *     screenshot still looks plausible.
 *  2. **The offer is made once.** The record is per habit, so a habit that rings
 *     three times a day asks once — and, just as importantly, a habit that was
 *     *never* asked is not suppressed because some *other* habit was.
 *  3. **The wording is the habit's own.** A walk's dialog must talk about a walk
 *     and its trackers; a journal's must not mention a fitness app at all. The
 *     failure mode here is a shared generic string, which reads fine in review
 *     and is exactly the "form rather than coach" feeling being fixed.
 *
 * ## Why these are plain JVM tests
 *
 * The project has no `androidTest` source set, so a Compose assertion cannot run
 * in CI here. Both subjects are therefore deliberately pure functions of their
 * arguments — [AlarmRingGate.shouldOfferConnect] and [AlarmDialogs.forHabit] —
 * which is what makes the user-visible decisions assertable at all.
 */
class AlarmDialogPolicyTest {

    private val strava = TrackerProvider.STRAVA

    private fun status(provider: TrackerProvider, connected: Boolean) = TrackerStatus(
        provider = provider,
        state = if (connected) TrackerState.CONNECTED else TrackerState.READY,
        isConnected = connected,
        autoSync = connected,
        detail = "",
    )

    // ── 1. Non-fitness habits get no connect option ────────────────────────────

    @Test
    fun `a habit no fitness app can supply is never offered a connection`() {
        // `sourcesFor` returns empty for a journal, a vitamin, a water glass.
        assertFalse(
            "A habit with no fitness source must not be offered a connect option — " +
                "the request is explicit that such a habit shows just the alarm.",
            AlarmRingGate.shouldOfferConnect(
                sources = emptyList(),
                statuses = listOf(status(strava, connected = false)),
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `the non-fitness dialog offers no connect wording for any mode`() {
        // Every mode, including the ones a fitness app might plausibly be thought
        // to cover. `offersConnect` is the single flag the dialog branches on, so
        // if it is false there is no heading and no body to render.
        val journal = AlarmDialogs.forHabit(
            habitName = "Gratitude",
            iconId = "gratitude",
            mode = HabitTrackingMode.JOURNAL,
            sourceNames = emptyList(),
        )
        val medicine = AlarmDialogs.forHabit(
            habitName = "Vitamin D",
            iconId = "medicine",
            mode = HabitTrackingMode.CHECK,
            sourceNames = emptyList(),
        )

        assertFalse("A journal entry must not offer to connect a fitness app.", journal.offersConnect)
        assertFalse("A vitamin must not offer to connect a fitness app.", medicine.offersConnect)
        assertTrue("The connect heading must be blank, not a disabled label.", journal.connectHeading.isBlank())
        assertTrue("The connect body must be blank, not explanatory filler.", journal.connectBody.isBlank())
        // And the copy must not merely blank the block — it must not *mention* a
        // fitness app in the tool copy either, or the user is told about a
        // feature the dialog does not have.
        assertFalse(
            "A journal's own copy must not mention a fitness app",
            journal.subtitle.contains("Strava") || journal.subtitle.contains("Connect"),
        )
    }

    // ── 2. Fitness habits ARE offered, exactly once ────────────────────────────

    @Test
    fun `a walk with nothing connected is offered its real sources`() {
        assertTrue(
            "A walk's alarm should offer the fitness apps that can supply it.",
            AlarmRingGate.shouldOfferConnect(
                sources = listOf(strava),
                statuses = listOf(status(strava, connected = false)),
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `an already connected source suppresses the offer`() {
        // Nothing left to ask: the activity is already being captured, so showing
        // the offer would read as the app forgetting what the user did.
        assertFalse(
            "A habit whose source is connected must not be asked to connect it again.",
            AlarmRingGate.shouldOfferConnect(
                sources = listOf(strava),
                statuses = listOf(status(strava, connected = true)),
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `an unrelated connection does not suppress a different habit's offer`() {
        // Health Connect is connected for another habit. This habit's source is
        // Strava, which is NOT connected — so the offer still stands. This is the
        // case a naive "is anything connected?" check gets wrong, and it silently
        // removes the offer from every habit after the user's first connection.
        assertTrue(
            "Connected-ness must be read for this habit's own sources only.",
            AlarmRingGate.shouldOfferConnect(
                sources = listOf(strava),
                statuses = listOf(
                    status(TrackerProvider.HEALTH_CONNECT, connected = true),
                    status(strava, connected = false),
                ),
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `a habit already asked once is not asked again`() {
        assertFalse(
            "The connect offer is a one-time choice — re-asking is what makes it a nag.",
            AlarmRingGate.shouldOfferConnect(
                sources = listOf(strava),
                statuses = listOf(status(strava, connected = false)),
                alreadyAsked = true,
            ),
        )
    }

    @Test
    fun `only the sources that are actually connected are reported for capture`() {
        // The capture path must import from the provider that just connected —
        // and nothing else — or a ring would queue a sync for a tracker the user
        // never authorised.
        val connected = AlarmRingGate.connectedSources(
            sources = listOf(strava, TrackerProvider.POLAR),
            statuses = listOf(
                status(strava, connected = false),
                status(TrackerProvider.POLAR, connected = true),
            ),
        )
        assertEquals(
            "Exactly the connected source should be reported for capture.",
            listOf(TrackerProvider.POLAR),
            connected,
        )
    }

    // ── 3. The copy is the habit's own ────────────────────────────────────────

    @Test
    fun `each mode gets its own tool wording`() {
        // The personalisation lives in the subtitle rather than the heading: the
        // heading is named after the habit ("Time for your walk") because that is
        // what the user calls it, while the subtitle is what the habit's own tool
        // is about to ask for. A `when` that collapsed into one branch — the
        // genericness this whole change is about — shows up here as a repeated
        // subtitle, and nowhere else.
        val subtitles = HabitTrackingMode.entries.map { mode ->
            AlarmDialogs.forHabit(
                habitName = "Habit",
                iconId = null,
                mode = mode,
                sourceNames = emptyList(),
            ).subtitle
        }
        assertEquals(
            "Each of the five tracking modes must describe its own tool, not share one line.",
            HabitTrackingMode.entries.size,
            subtitles.distinct().size,
        )
        assertTrue(
            "No mode may render an empty subtitle — that is what a missing `when` branch looks like.",
            subtitles.none { it.isBlank() },
        )
    }

    @Test
    fun `a walk is addressed as a walk and names its own tracker`() {
        val copy = AlarmDialogs.forHabit(
            habitName = "Walk",
            iconId = "walking",
            mode = HabitTrackingMode.STOPWATCH,
            sourceNames = listOf("Strava", "Google Health Connect"),
        )

        // The habit's own noun, not its typed name and not a generic one.
        assertTrue(
            "A walking habit should be addressed in its own words: got '${copy.title}'",
            copy.title.contains("walk"),
        )
        assertTrue("A trackable habit must carry the offer.", copy.offersConnect)
        // Both names must be present, joined the way a person would say them.
        assertTrue(
            "The offer must name the real apps that can supply this habit: got '${copy.connectBody}'",
            copy.connectBody.contains("Strava") &&
                copy.connectBody.contains("Google Health Connect"),
        )
        assertTrue(
            "Multiple providers should read as a spoken list: got '${copy.connectBody}'",
            copy.connectBody.contains(" or "),
        )
    }

    @Test
    fun `a count habit talks about its own unit`() {
        val copy = AlarmDialogs.forHabit(
            habitName = "Water",
            iconId = "water",
            mode = HabitTrackingMode.COUNT,
            unit = "glasses",
            sourceNames = emptyList(),
        )
        assertTrue(
            "A count habit's copy should ask in its own unit: got '${copy.subtitle}'",
            copy.subtitle.contains("glasses"),
        )
        assertFalse("Water is not a fitness-app habit.", copy.offersConnect)
    }

    @Test
    fun `a strava sport icon is named in prose rather than by its raw id`() {
        // Strava's own imports arrive as `strava_weight_training`. Feeding that id
        // straight into a sentence produces "Time for your strava_weight_training".
        val copy = AlarmDialogs.forHabit(
            habitName = "",
            iconId = "strava_weight_training",
            mode = HabitTrackingMode.TIMER,
            sourceNames = emptyList(),
        )
        assertFalse(
            "A Strava-derived icon must not leak its raw id into the copy: got '${copy.title}'",
            copy.title.contains("strava_"),
        )
        assertTrue(
            "It should read as the sport it is: got '${copy.title}'",
            copy.title.contains("weight training"),
        )
    }
}
