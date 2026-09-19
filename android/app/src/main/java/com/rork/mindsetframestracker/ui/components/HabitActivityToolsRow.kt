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
    /**
     * Starts the tool (stopwatch / timer) for this habit.
     *
     * Defaulted to null so the *link*-only host (the per-habit tracker section)
     * does not have to pass a no-op that would look like a real handler. A null
     * handler hides the tool row entirely rather than rendering a button that
     * does nothing.
     */
    onStartTool: (() -> Unit)? = null,
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
     * Links/unlinks a provider for **this habit** — the per-habit connect the
     * request asks for.
     *
     * Separate from [onOpenTracker] because they are different actions on
     * different scopes. [onOpenTracker] opens the account-level sheet, which is
     * only the right destination when the service is not authorised at all;
     * once it is authorised, the remaining question is "should *this* habit use
     * it?" — and that answer lives here, on the habit, not in a global sheet.
     *
     * Null means this surface cannot host the link, in which case each row falls
     * back to [onOpenTracker].
     */
    onLinkTracker: ((TrackerProvider) -> Unit)? = null,
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
                // A null handler would render a button that silently does
                // nothing, so it is degraded to a no-op rather than smuggled
                // through a non-null parameter type.
                onClick = onStartTool ?: {},
            )
        }

        // One row per provider that can actually supply this habit, so a
        // connected-but-irrelevant tracker is never advertised as if it could.
        trackerProviders.forEach { provider ->
            TrackerToolRow(
                provider = provider,
                status = statusByProvider[provider],
                onOpen = onOpenTracker,
                onLink = onLinkTracker,
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
internal fun TrackerToolRow(
    provider: TrackerProvider,
    status: TrackerStatus?,
    onOpen: (() -> Unit)?,
    onLink: ((TrackerProvider) -> Unit)? = null,
) {
    val connected = status?.isConnected == true
    val linkedHere = status?.linkedToThisHabit == true
    val linkedElsewhere = status?.linkedElsewhereLabel
    val unavailable = status != null && !status.isActionable
    val locked = status?.state == TrackerState.LOCKED

    val detail = when {
        status == null -> "Connect to import this habit automatically"
        locked -> "Included with Premium"
        linkedHere -> "Linked to this habit — activity imports automatically"
        // Names the habit that holds it. Without this the row simply offered
        // "Link" and the user had no way to know their tracker was already
        // serving a different habit — nor that linking here would move it.
        linkedElsewhere != null -> "Linked to $linkedElsewhere"
        connected -> "Connected — tap to link this habit"
        unavailable -> status.detail
        else -> "Ready to import your activity"
    }

    val actionLabel = when {
        locked || unavailable -> null
        linkedHere -> "Unlink"
        // Authorised at the account level: one tap links it here, with no OAuth
        // round trip, because the grant already exists.
        connected -> "Link"
        // Not authorised yet: the account-level sheet is the right destination,
        // and completing it links this habit (the host holds the pending target).
        else -> "Connect"
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
        actionLabel = actionLabel,
        // A provider this build cannot use stays non-tappable: a button that
        // cannot succeed is worse than no button, because it reads as a bug.
        enabled = when {
            locked || unavailable -> false
            linkedHere -> onLink != null
            else -> onLink != null || onOpen != null
        },
        onClick = {
            when {
                // In the habit's own dialog the link is the whole point: the
                // account is already authorised, so this tap decides whether
                // THIS habit uses the tracker.
                linkedHere -> onLink?.invoke(provider)
                connected -> onLink?.invoke(provider) ?: onOpen?.invoke()
                else -> onOpen?.invoke()
            }
        },
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
    /**
     * The row's action, named on the row itself.
     *
     * Link and unlink are not the same tap: a user who taps a row reading "Link"
     * and has it instead *unlink* a tracker has lost their automatic tracking
     * with no idea why. Spelling the action out is what makes it impossible to
     * mistake which one this tap will do. Null (the tool row's case) shows no
     * label, because starting a stopwatch carries no such ambiguity.
     */
    actionLabel: String? = null,
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
        if (actionLabel != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
        }
    }
}