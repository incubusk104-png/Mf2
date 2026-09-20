package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerState
import com.rork.mindsetframestracker.integrations.TrackerStatus

/**
 * The "Connect fitness trackers" pop-up: all three providers in one place, each
 * with its real state, so the user can see what is connected and what still needs
 * doing without hunting through Settings.
 *
 * ## What this deliberately does NOT do
 *
 * It does not claim a provider is connected when it is not, and it does not
 * present a working-looking button for a provider this build cannot use. Each
 * row's wording comes from [TrackerStatus], which is built from the same checks
 * the connect flow itself runs — so a row reading "Connect" opens a real OAuth
 * page or permission dialog, and a row reading "Not available" says which piece
 * of configuration is missing instead of failing silently when tapped.
 *
 * ## The auto-track switch
 *
 * When at least one provider is connected, the sheet offers a single
 * "Automatically track my activity habits" switch. It is the aggregate of the
 * three per-provider auto-sync flags: on means every connected provider sweeps
 * into the user's activity habits on app open, off means none do. Presented as
 * one decision because that is how the user thinks about it — they connected a
 * tracker *so that* their walking habit keeps itself up to date, not so that
 * "Polar auto-sync" is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerConnectSheet(
    statuses: List<TrackerStatus>,
    /**
     * The habit this connection is being set up for, so the sheet names it.
     *
     * Required, not optional: a tracker link is meaningless except *relative to
     * a habit*, and the sheet is reachable only from a habit's own dialog. Making
     * it non-null is what removes the last way for this sheet to render as a
     * standalone "Connect fitness trackers" surface.
     */
    habitLabel: String,
    /** Whether new activity from a connected provider auto-checks the habit off. */
    autoTrack: Boolean,
    /** The provider currently mid-connect, so its row can show progress. */
    busyProvider: TrackerProvider? = null,
    /** Shown at the top, e.g. the result of the last connect attempt. */
    message: String? = null,
    onAutoTrackChange: (Boolean) -> Unit,
    onConnect: (TrackerProvider) -> Unit,
    onDisconnect: (TrackerProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    val anyConnected = statuses.any { it.isConnected }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                // Scoped to the habit whose dialog opened this. There is
                // deliberately NO generic fallback: the sheet is only ever
                // reachable from a habit's own dialog, so a label-less render is
                // a caller bug, not a state to design for. Keeping a generic
                // "Connect fitness trackers" branch is exactly how the global
                // control grew back last time.
                text = "Connect fitness trackers for " + habitLabel,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Link an app you already use and your activity habits \u2014 walking, running, " +
                    "gym \u2014 can be tracked from it instead of by hand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(16.dp))

            statuses.forEachIndexed { index, status ->
                TrackerRow(
                    status = status,
                    busy = busyProvider == status.provider,
                    onConnect = { onConnect(status.provider) },
                    onDisconnect = { onDisconnect(status.provider) },
                )
                if (index != statuses.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            if (anyConnected) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Track my activity habits automatically",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Your connected trackers keep walking and running habits up to " +
                                "date when you open the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = autoTrack, onCheckedChange = onAutoTrackChange)
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nothing is connected yet. Link a tracker above and your activity habits " +
                        "can be checked off from your real workouts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

/**
 * One provider row.
 *
 * The whole row is the action target, except when the provider is connected —
 * there the tap disconnects, which is why the action label is spelled out on the
 * row rather than implied. A destructive tap should never be the one the user
 * makes by accident while exploring.
 */
@Composable
private fun TrackerRow(
    status: TrackerStatus,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val unavailable = !status.isActionable

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = status.isActionable && !busy) {
                if (status.isConnected) onDisconnect() else onConnect()
            }
            .padding(14.dp),
    ) {
        Icon(
            imageVector = status.provider.icon(),
            contentDescription = null,
            tint = when {
                unavailable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                status.isConnected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.provider.label,
                    fontWeight = FontWeight.SemiBold,
                    color = if (unavailable) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (status.isConnected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Connected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (unavailable) 0.7f else 0.9f,
                ),
            )
            if (status.isConnected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (status.autoSync) "Auto-track on" else "Auto-track off",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            // Locked and unavailable states get an icon rather than a button:
            // a disabled button invites tapping, and the row's detail text is
            // where the real explanation lives.
            status.state == TrackerState.LOCKED -> Icon(
                Icons.Outlined.Lock,
                contentDescription = "Requires Premium",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            unavailable -> Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = "Not available in this build",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            else -> Text(
                status.actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The icon that identifies each provider, shared by every surface that lists them.
 *
 * ## Why this is `internal` rather than `private`
 *
 * It was `private` here, and `TrackerConnectSheet` was the only caller. The habit
 * dialog now lists the same three providers, and a private helper cannot be
 * reached from another file — so it would have re-declared the mapping, and two
 * declarations of "Strava is the running icon" is how two screens come to show
 * *different* icons for one service (and how a fourth provider gets added to one
 * list and silently renders a fallback in the other).
 *
 * ## Why the provider's own icon is worth having in the habit dialog
 *
 * Every tracker row there used to draw the same generic play arrow as the
 * stopwatch row, so the two were visually identical — a large part of why tapping
 * "Strava" and getting a stopwatch felt like a mis-tap rather than a bug.
 */
internal fun TrackerProvider.icon(): ImageVector = when (this) {
    TrackerProvider.HEALTH_CONNECT -> Icons.Outlined.MonitorHeart
    TrackerProvider.STRAVA -> Icons.AutoMirrored.Outlined.DirectionsRun
    TrackerProvider.POLAR -> Icons.Outlined.FavoriteBorder
}
