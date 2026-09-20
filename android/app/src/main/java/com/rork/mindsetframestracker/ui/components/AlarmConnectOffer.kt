package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.AlarmDialogCopy
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerStatus

/**
 * The "connect a fitness app" block **inside the dialog the alarm raises**.
 *
 * ## What this is, and what it deliberately is not
 *
 * This is the offer, not the flow. Tapping a provider row here hands off to
 * [HabitTrackerConnectHost], which already owns the whole connect sequence — the
 * privacy consent gate, the tier upgrade path for a locked provider, and the
 * in-flight state that must always resolve. Re-implementing any of that here
 * would give the app two ways to start a connection, and therefore two ways for
 * one of them to be wrong; the alarm ring is the worst possible place for that,
 * because it is the one surface the user did not choose to be on.
 *
 * ## Why it renders nothing at all for some habits
 *
 * The block is driven by [copy] and [providers], and both are empty for a habit
 * no fitness app can record — a journal entry, a vitamin. The request was
 * explicit: *if the habit does not involve a fitness app, do NOT show the
 * connect option — just the alarm.* So the absence is structural rather than a
 * conditional buried in the caller: there is no heading, no body and no row to
 * render, which is why this early-returns instead of drawing a disabled or
 * explanatory version of the offer. A greyed-out Strava row on a meditation
 * alarm would be the same defect wearing a different coat.
 *
 * ## Why the row copy comes from the shared section
 *
 * The provider rows are rendered by [SectionTrackerRows], the same composable the
 * habit's own dialog uses, so a provider cannot read "Connected" on one surface
 * and "Ready to import" on the other. The only thing this adds is the *framing* —
 * the heading and the one line explaining what connecting would buy the user for
 * this specific habit, taken from [AlarmDialogCopy] so the words match the tool
 * the dialog is showing underneath.
 */
@Composable
internal fun RingConnectOffer(
    /**
     * The dialog's per-habit copy. Supplies both the heading and the line naming
     * the apps that can supply this habit.
     */
    copy: AlarmDialogCopy,
    /** Providers that can actually supply this habit, from the caller's resolved set. */
    providers: List<TrackerProvider>,
    /** Each provider's real state, so a row never offers a connect that cannot succeed. */
    statuses: List<TrackerStatus>,
    /** Opens the connect flow. Null renders the rows as honest status lines. */
    onOpenConnect: (() -> Unit)?,
) {
    // The two facts that make this block exist at all. Both must hold: a copy
    // with no offer (no possible source) and an empty provider list are the same
    // answer — there is nothing here for this habit — and either alone is enough
    // to mean the block must not appear.
    if (!copy.offersConnect || providers.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        // The icon and the heading are one visual unit, so they are laid out
        // together here rather than at two call sites — that is how the icon ends
        // up stranded on the wrong line when the heading wraps.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = copy.connectHeading.ifBlank { "Connect a fitness app" },
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = copy.connectBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // The rows themselves — one implementation, shared with the habit's own
        // dialog, so the two surfaces can never describe a provider differently.
        SectionTrackerRows(
            trackerProviders = providers,
            onOpenTracker = onOpenConnect,
            trackerStatuses = statuses,
        )
    }
}
