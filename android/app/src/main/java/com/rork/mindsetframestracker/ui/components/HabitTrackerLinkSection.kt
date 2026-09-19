package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.integrations.HabitTrackerLinks
import com.rork.mindsetframestracker.integrations.TrackerConnections
import com.rork.mindsetframestracker.integrations.TrackerStatus
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.candidateSourcesFor

/**
 * **The tracker links for one habit**, rendered inside that habit's dialog.
 *
 * ## Why this is a section and not a screen
 *
 * The user's framing: *"the tracker should not be a separate/global thing
 * sitting in its own position. Instead, each habit should carry the features
 * related to it — for example when you click walk, since walking exists in
 * Google Health, Polar and Strava, the connect functionality for those fitness
 * apps should live inside that habit's dialog."*
 *
 * A separate screen cannot satisfy that even if its content is right, because it
 * has no habit in scope — it can only describe *account-level* state, which is
 * exactly what it used to show. The fix is a change of scope, not a change of
 * layout: the tracker rows have to be rendered with a habit in hand.
 *
 * ## Why the rows are the SAME composable as the tools row's
 *
 * [TrackerToolRow] is reused rather than reimplemented. Three hosts render this
 * section (the Habits screen's edit sheet, the ring host, and the Home screen's
 * tracking sheet) and the request is explicit that the post-alarm dialog must
 * offer the same action. A second implementation would drift, and the drift
 * would be invisible: a user who links from the ring path and then cannot see
 * the link from the Habits screen has no way to tell which surface is wrong.
 *
 * @param habit the habit whose dialog this is. Null renders nothing: without a
 *   habit there is nothing to link the tracker *to*, and the account-level view
 *   belongs in Settings, not here.
 * @param allStatuses account-level statuses (from
 *   [TrackerConnections.statuses]); re-scoped to [habit] internally.
 * @param habits the full habit list, needed to name the habit that holds a
 *   tracker when it is not this one.
 * @param onLink links/unlinks [TrackerProvider] for **this habit**. Receives the
 *   provider, never the habit id — the habit is this section's own parameter, so
 *   a caller cannot mis-target it.
 * @param onOpenAccountSheet opens the account-level flow, for a service not
 *   authorised at all yet. Null on the ring path, deliberately: pushing the user
 *   into an OAuth browser round trip from a ringing alarm would abandon the
 *   habit they were recording. The row then reads as a status line.
 */
@Composable
fun HabitTrackerLinkSection(
    habit: Habit?,
    allStatuses: List<TrackerStatus>,
    habits: List<Habit>,
    onLink: (TrackerProvider) -> Unit,
    onOpenAccountSheet: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (habit == null) return

    // Only providers this habit's icon can be supplied by. A Strava link that
    // survived an icon change to "Journal" must not put a Strava row on a
    // journal habit — this intersection is what keeps the list honest.
    val candidates = candidateSourcesFor(habit.iconId)
    if (candidates.isEmpty()) return

    val statusByProvider = TrackerConnections.forHabit(
        base = allStatuses,
        habits = habits,
        forHabitId = habit.id,
    ).associateBy { it.provider }
    val bound = HabitTrackerLinks.of(habits).activeProvidersFor(habit)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Trackers for this habit",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            // The count is stated because "linked" is otherwise a claim the user
            // has to verify row by row. A habit with nothing linked says so.
            Text(
                text = if (bound.isEmpty()) "None linked" else "${bound.size} linked",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Link the app you use for this habit — its activity is recorded " +
                "against this habit only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEach { provider ->
                TrackerToolRow(
                    provider = provider,
                    status = statusByProvider[provider],
                    onOpen = onOpenAccountSheet,
                    onLink = onLink,
                )
            }
        }
    }
}
