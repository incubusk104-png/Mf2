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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.integrations.TrackerProvider
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
    modifier: Modifier = Modifier,
) {
    val s = appStrings()
    if (trackingMode == HabitTrackingMode.CHECK && trackerProviders.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = s.habitToolsTitle, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))

        if (trackingMode != HabitTrackingMode.CHECK) {
            CompactToolRow(
                title = s.habitToolsOpen,
                detail = toolDetailLabel(targetSeconds),
                onClick = onStartTool,
            )
        }

        // One row per provider that can actually supply this habit, so a
        // connected-but-irrelevant tracker is never advertised as if it could.
        trackerProviders.forEach { provider ->
            CompactToolRow(
                title = provider.label,
                detail = s.habitToolsTrackerReady,
                onClick = onStartTool,
            )
        }
    }
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
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
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
