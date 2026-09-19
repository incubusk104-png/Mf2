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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerState
import com.rork.mindsetframestracker.integrations.TrackerStatus
import com.rork.mindsetframestracker.ui.appStrings

/**
 * "What this habit's activity tool is, and how to use it" — the habit dialog's
 * answer to *"I have a walking habit, where is the stopwatch?"*.
 *
 * ## Why the tool has to be named here rather than left to the timer screen
 *
 * The tracking mode already existed (`HabitTrackingMode`, resolved per icon so a
 * walking habit is a `STOPWATCH` out of the box) and the timer/stopwatch screen
 * already existed. What did **not** exist was any place in the habit's own dialog
 * that said so. A user opening their walk — the exact gesture the request
 * describes — saw a check-in and no sign that a stopwatch was available, so the
 * feature may as well not have been there. This names the tool, its target, and
 * the single action that starts it.
 *
 * ## Why tracker rows no longer share the tool's action
 *
 * Every provider row used to be handed the **same `onClick` as the stopwatch
 * row**, so tapping "Strava — ready to import your activity" started a count-up
 * clock: nothing connected, and no way to connect from where the user was. The
 * two are different actions on different objects — one starts a local clock, the
 * other opens a connection flow — and collapsing them into one callback made the
 * tracker line a lie about what it does. [onOpenTracker] is separate for exactly
 * that reason, which is what makes the row's wording honest.
 *
 * A provider that *cannot* be connected is no longer drawn as though it could
 * be: a tier-locked Strava shows a padlock and routes to the upgrade path rather
 * than launching an OAuth flow the user is not entitled to, and a provider this
 * build has no credentials for shows a warning instead of a dead button.
 *
 * ## Why it takes plain values instead of a `Habit`
 *
 * Two callers need this row and they hold different things: the alarm dialog
 * holds a whole `Habit`, while the tracking sheet holds the habit's *parts*
 * (`trackingMode`, `targetSeconds`) because that is all it was ever given. A row
 * that demanded a `Habit` would force the sheet to fabricate one — inventing an
 * id and a creation time just to read two ints — which is how a display
 * component ends up able to disagree with the thing it is describing.
 *
 * ## Why it takes the trackers
 *
 * The tool and the tracker are the same question asked twice: *how will this
 * habit get its number?* A walk can be measured by the stopwatch or imported
 * from Strava / Health Connect, so when a provider can supply the habit the two
 * are shown under one heading — which is what makes "the stopwatch and the
 * tracker work together" visible rather than something the user must infer.
 *
 * Renders nothing when the habit has no activity tool and no provider, so a
 * plain checkbox habit's dialog is not padded with an empty section.
 */
@Composable
internal fun HabitActivityToolsRow(
    trackingMode: HabitTrackingMode,
    targetSeconds: Int,
    trackerProviders: List<TrackerProvider>,
    onStartTool: () -> Unit,
    /**
     * Opens the connect/disconnect flow.
     *
     * Null (the default) means this surface cannot host the flow, in which case
     * the provider rows are rendered as plain, non-tappable status lines rather
     * than as buttons that would silently do nothing — the failure mode the old
     * shared callback produced.
     *
     * Takes no provider argument: the connect pop-up lists every provider and is
     * not opened "for" one of them.
     */
    onOpenTracker: (() -> Unit)? = null,
    /**
     * Each provider's real state, so a row can honestly say "Not available" or
     * show a lock instead of offering a connect that cannot succeed.
     *
     * Empty means the caller has no status to hand (it has not resolved them
     * yet); the rows then name the provider without claiming a state, which is
     * honest and still tells the user the option exists.
     */
    trackerStatuses: List<TrackerStatus> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val s = appStrings()
    if (trackingMode == HabitTrackingMode.CHECK && trackerProviders.isEmpty()) return

    val statusByProvider = trackerStatuses.associateBy { it.provider }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = s.habitToolsTitle, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))

        if (trackingMode != HabitTrackingMode.CHECK) {
            CompactToolRow(
                icon = Icons.Outlined.PlayArrow,
                title = s.habitToolsOpen,
                detail = toolDetailLabel(targetSeconds),
                onClick = onStartTool,
            )
        }

        // One row per provider that can actually supply this habit, so a
        // connected-but-irrelevant tracker is never advertised as if it could.
        // Rendered through the same composable the tap-to-track sheet uses, so
        // the two surfaces cannot describe one provider differently.
        trackerProviders.forEach { provider ->
            TrackerToolRow(
                provider = provider,
                status = statusByProvider[provider],
                onOpen = onOpenTracker,
            )
        }
    }
}

/**
 * The tracker connections for one habit, as a titled group.
 *
 * ## Why this exists separately from [HabitActivityToolsRow]
 *
 * Two habit dialogs show a habit's trackers and they hold different things. The
 * alarm editor holds a whole `Habit`; the tap-to-track and alarm-ring sheet only
 * ever had the habit's *parts*. [HabitActivityToolsRow] needs a tracking mode to
 * name the tool alongside the trackers, which the alarm editor has but the ring
 * sheet would have to fabricate — and inventing a mode to satisfy a display
 * component is how a row ends up describing a tool that will not run.
 *
 * Showing nothing was the alternative, and it is what made the user's request
 * impossible: after the alarm rang, the dialog they landed on offered no way to
 * connect the fitness app that could record the walk. This is the piece that
 * makes "the connect action is available in the dialog the alarm leads to"
 * true rather than aspirational.
 *
 * ## Why it is not called "connect button"
 *
 * It renders one row per provider that can actually supply this habit, with each
 * provider's real state, so a connected-but-irrelevant tracker is never
 * advertised as if it could record the habit in front of the user. The rows come
 * from [TrackerConnections.sourcesFor], the same source the habits overview and
 * the connect sweep use, so "this habit can be tracked by Strava" cannot be true
 * on one surface and false on another.
 *
 * Renders nothing when no provider can supply the habit, so a plain checkbox
 * habit's dialog is not padded with an empty heading.
 */
@Composable
internal fun SectionTrackerRows(
    trackerProviders: List<TrackerProvider>,
    /**
     * Opens the connect/disconnect flow. Null renders the rows as plain, honest
     * status lines rather than buttons that would silently do nothing.
     */
    onOpenTracker: (() -> Unit)? = null,
    /** Each provider's real state, so a row never offers a connect that cannot succeed. */
    trackerStatuses: List<TrackerStatus> = emptyList(),
) {
    if (trackerProviders.isEmpty()) return
    val s = appStrings()
    val statusByProvider = trackerStatuses.associateBy { it.provider }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = s.habitToolsTitle, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        trackerProviders.forEach { provider ->
            TrackerToolRow(
                provider = provider,
                status = statusByProvider[provider],
                onOpen = onOpenTracker,
            )
        }
    }
}

/**
 * One provider's row inside a habit dialog.
 *
 * The state drives every visual choice, so the row cannot claim more than the
 * connect flow will deliver: connected providers read as connected and route to
 * the manage sheet, locked ones show a padlock, and those this build cannot use
 * at all show a warning icon instead of a call to action.
 */
@Composable
private fun TrackerToolRow(
    provider: TrackerProvider,
    status: TrackerStatus?,
    onOpen: (() -> Unit)?,
) {
    val connected = status?.isConnected == true
    val unavailable = status != null && !status.isActionable
    val locked = status?.state == TrackerState.LOCKED

    val detail = when {
        status == null -> "Connect to import this habit automatically"
        connected -> "Connected \u2014 activity imports automatically"
        locked -> "Included with Premium"
        unavailable -> status.detail
        else -> "Ready to import your activity"
    }

    CompactToolRow(
        // The provider's own mark rather than the shared play arrow, so a tracker
        // row no longer looks identical to the stopwatch row above it.
        icon = when {
            locked -> Icons.Outlined.Lock
            unavailable -> Icons.Outlined.ErrorOutline
            else -> provider.icon()
        },
        title = provider.label,
        detail = detail,
        // A provider this build cannot use stays non-tappable: a button that
        // cannot succeed is worse than no button, because it reads as a bug.
        enabled = onOpen != null && status?.isActionable != false,
        onClick = onOpen ?: {},
    )
}

/**
 * The tool's detail line: the count-down target for a timer, the goal for a
 * stopwatch, or "open ended" when neither applies.
 *
 * Takes the seconds the caller already holds rather than re-deriving them from
 * an icon, so the label describes the tool that will actually run — a dialog
 * reading "20 min" while the timer ran open-ended is exactly the
 * two-sources-of-truth bug this avoids.
 */
@Composable
private fun toolDetailLabel(targetSeconds: Int): String {
    val s = appStrings()
    if (targetSeconds <= 0) return s.habitToolsOpenEnded
    val minutes = targetSeconds / 60
    return if (minutes >= 1) "$minutes min" else "${targetSeconds}s"
}

/** One tappable line: an icon, a title, a muted detail and a start affordance. */
@Composable
private fun CompactToolRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
