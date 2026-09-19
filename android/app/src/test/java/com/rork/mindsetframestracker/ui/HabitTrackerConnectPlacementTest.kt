package com.rork.mindsetframestracker.ui

import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerState
import com.rork.mindsetframestracker.integrations.TrackerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the connect-fitness control lives, and what it is allowed to offer.
 *
 * ## The request these tests pin
 *
 * *"Just move connect fitness [into] the dialog ... inside activity habit."*
 * Connect fitness trackers used to be a global row above the habit grid — its own
 * position, reading as a separate feature. It now belongs to the habit's own
 * dialog, so opening a walk offers the connections that can supply a walk.
 *
 * A relocation is easy to undo by accident: one well-meaning "shortcut" back into
 * the header, and the control is global again. These tests fail if it moves back.
 *
 * ## Why they are plain JVM tests
 *
 * The project has no `androidTest` source set — every test is a JVM test — so a
 * Compose UI assertion is not available here and inventing an instrumented suite
 * to host one would produce a test that never runs in CI. What *is* checkable
 * without a device is the wiring itself: the source no longer declares the global
 * entry point, and the habit dialog declares the section it renders. That is what
 * is asserted below, against the real files on disk.
 *
 * ## Why the source is read from the repo root
 *
 * The module's working directory when Gradle runs unit tests is the `app`
 * directory, so the paths below are relative to that. If the files cannot be
 * found the test *fails* rather than skipping: a test that silently passes when
 * it cannot see its subject is worse than no test, because it reports coverage
 * it does not have.
 */
class HabitTrackerConnectPlacementTest {

    private fun moduleFile(relative: String): java.io.File {
        val candidates = listOf(
            java.io.File(relative),
            java.io.File("app/$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "Could not locate $relative from ${java.io.File(".").absolutePath}. " +
                    "These tests assert against the real source files, so a missing " +
                    "file is a failure, not a skip.",
            )
    }

    private val habitsScreen = { moduleFile("src/main/java/com/rork/mindsetframestracker/ui/screens/HabitsScreen.kt").readText() }
    private val trackingSheet = { moduleFile("src/main/java/com/rork/mindsetframestracker/ui/components/HabitTrackingSheet.kt").readText() }
    private val toolsRow = { moduleFile("src/main/java/com/rork/mindsetframestracker/ui/components/HabitActivityToolsRow.kt").readText() }
    private val connectSheet = { moduleFile("src/main/java/com/rork/mindsetframestracker/ui/components/TrackerConnectSheet.kt").readText() }

    // ── The move: gone from the global position ──────────────────────────────

    @Test
    fun `the habits screen no longer offers a global connect fitness entry point`() {
        // The literal thing the user objected to: a connect control sitting in
        // its own position above the habit grid. If this string is back in the
        // screen, the control has moved back out of the habit dialog.
        val source = habitsScreen()
        assertFalse(
            "HabitsScreen must not present a global \"Connect fitness trackers\" " +
                "control — it belongs inside the habit dialog.",
            source.contains("\"Connect fitness trackers\""),
        )
    }

    @Test
    fun `the habits screen header declares no tracker TextButton`() {
        // Stronger than the string check: the header used to open the sheet with
        // a bare `showTrackerSheet = true` from a header button. Nothing outside
        // a habit's dialog may do that any more.
        val source = habitsScreen()
        assertFalse(
            "No header control may open the tracker sheet directly; only a habit " +
                "dialog's onOpenTracker may.",
            Regex("""TextButton\(\s*onClick\s*=\s*\{\s*showTrackerSheet\s*=\s*true""")
                .containsMatchIn(source),
        )
    }

    // ── The move: present inside the habit dialog ────────────────────────────

    @Test
    fun `the habit dialog accepts a connect action and provider statuses`() {
        // This sheet is the dialog both the tap-to-track path and the alarm-ring
        // host open, so carrying the connect action here is what makes it
        // reachable "inside the activity habit" rather than only in the editor.
        val source = trackingSheet()
        assertTrue(
            "HabitTrackingSheet must declare onOpenTracker so the connect action " +
                "is reachable from the habit dialog.",
            source.contains("onOpenTracker: (() -> Unit)? = null"),
        )
        assertTrue(
            "HabitTrackingSheet must accept provider statuses so its rows can be honest.",
            source.contains("trackerStatuses: List<TrackerStatus> = emptyList()"),
        )
    }

    @Test
    fun `the habit dialog renders the tracker rows rather than only accepting them`() {
        // Declaring the parameter and never rendering it is the trap: the API
        // would look moved while the screen showed nothing. The section must
        // actually be composed, from the habit's own icon.
        val source = trackingSheet()
        assertTrue(
            "HabitTrackingSheet must render its tracker section.",
            source.contains("SectionTrackerRows("),
        )
        assertTrue(
            "The rows must come from the habit's own icon, so only providers " +
                "that can supply this habit are offered.",
            source.contains("TrackerConnections.sourcesFor(habitIconId)"),
        )
    }

    @Test
    fun `both habit dialog call sites pass the connect action through`() {
        // The two dialogs a user actually reaches: tap-to-track (Home) and the
        // dialog the alarm leads to (the ring host). If either omits the action
        // the section renders as inert text, which is the failure mode this
        // whole change exists to remove.
        val home = moduleFile("src/main/java/com/rork/mindsetframestracker/ui/screens/HomeScreen.kt").readText()
        val nav = moduleFile("src/main/java/com/rork/mindsetframestracker/ui/navigation/AppNavigation.kt").readText()
        listOf("HomeScreen" to home, "AppNavigation" to nav).forEach { (name, source) ->
            assertTrue(
                "$name must hand the connect action to HabitTrackingSheet.",
                source.contains("onOpenTracker = { showTrackerConnect = true },"),
            )
            assertTrue(
                "$name must hand the resolved provider statuses to HabitTrackingSheet.",
                source.contains("trackerStatuses = trackerStatuses,"),
            )
        }
    }

    // ── What the sheet must not do: offer a connection that cannot work ──────

    private fun status(
        provider: TrackerProvider,
        state: TrackerState,
        connected: Boolean = false,
    ) = TrackerStatus(
        provider = provider,
        state = state,
        detail = "d",
        isConnected = connected,
        autoSync = false,
    )

    @Test
    fun `a locked provider is actionable so the tap reaches the upgrade path`() {
        // If LOCKED were not actionable the padlock row would be dead, and the
        // user would have no route to the tier that unlocks it. It is actionable
        // *and* the host must intercept it — see the test below.
        val locked = status(TrackerProvider.STRAVA, TrackerState.LOCKED)
        assertTrue("a locked provider must still be tappable", locked.isActionable)
        assertEquals("Upgrade", locked.actionLabel)
    }

    @Test
    fun `the locked row is routed to the upgrade sheet and never to oauth`() {
        // The historical defect: the row was DRAWN as locked while `onConnect`
        // branched straight on the provider and launched the real authorisation
        // page. The lock was decoration. The host must check LOCKED *before* the
        // provider branch, so the label and the behaviour agree.
        val host = moduleFile(
            "src/main/java/com/rork/mindsetframestracker/ui/components/HabitTrackerConnectHost.kt",
        ).readText()
        val lockCheckAt = host.indexOf("state == TrackerState.LOCKED")
        val oauthAt = host.indexOf("viewModel.connectStrava()")
        assertTrue("the host must guard on the locked state", lockCheckAt >= 0)
        assertTrue("the host must still be able to run the Strava flow", oauthAt >= 0)
        assertTrue(
            "the LOCKED guard must come BEFORE the provider branch, otherwise a " +
                "locked row opens the authorisation page it is not entitled to.",
            lockCheckAt < oauthAt,
        )
    }

    @Test
    fun `the connect sheet labels itself for the habit it was opened from`() {
        // Scoping the title is what stops the sheet reading as a global setting
        // the user wandered into. Passing the label is optional, so a caller
        // with no habit in context still gets sensible wording.
        val source = connectSheet()
        assertTrue(
            "TrackerConnectSheet must accept the habit label.",
            source.contains("habitLabel: String? = null"),
        )
        assertTrue(
            "The label must actually reach the heading.",
            source.contains("\"Connect fitness trackers for \$habitLabel\""),
        )
    }

    @Test
    fun `the shared tracker section is one implementation used by the tools row`() {
        // Two dialogs show a habit's trackers. They must not each own a copy of
        // the row rendering, or one of them will drift and describe a provider
        // differently from the other.
        val source = toolsRow()
        assertTrue(
            "SectionTrackerRows must be declared once, in the shared component file.",
            source.contains("internal fun SectionTrackerRows("),
        )
        assertTrue(
            "HabitActivityToolsRow must render providers through that shared row.",
            source.contains("TrackerToolRow("),
        )
    }
}
