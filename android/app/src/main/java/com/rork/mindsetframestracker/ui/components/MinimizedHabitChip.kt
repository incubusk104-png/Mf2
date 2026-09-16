package com.rork.mindsetframestracker.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.TimerStatus
import com.rork.mindsetframestracker.data.formatTimerDuration
import com.rork.mindsetframestracker.notifications.HabitTimerRequests
import com.rork.mindsetframestracker.notifications.TimerController
import com.rork.mindsetframestracker.notifications.TimerRepository
import kotlinx.coroutines.delay

/**
 * The "minimized" strip: whatever the user put away is still reachable here.
 *
 * ## The problem it solves
 *
 * An alarm that rings mid-walk used to fire and forget. Dismissing the sheet
 * discarded the in-progress state, and a stopwatch the user had already started
 * could only be seen again by navigating back to the timer screen — so "start
 * the walk, put the phone away, log it when I get home" was not actually
 * possible, which is the whole point of an alarm for a physical activity.
 *
 * This chip is the way back. It shows the **real** elapsed time and restores the
 * full sheet on tap, so the user can take the habit up again at any time.
 *
 * ## Why the elapsed time here is never wrong
 *
 * Nothing on this chip counts in memory. The elapsed value is derived at render
 * time from the persisted run (`ActiveTimer.elapsedAt(now)`, built on
 * `startedAtEpochMs`), so it stays correct across navigation, backgrounding, a
 * reboot, and a process kill by an OEM battery manager. The one-second `tick` is
 * only a re-render trigger for that derivation — if it were to stop, the next
 * frame would still show the right number rather than a frozen one.
 *
 * ## Two things can be minimized, and they are merged when they coincide
 *
 *  1. a **running TIMER/STOPWATCH** — the measurement itself
 *  2. a **minimized habit sheet** — the input the habit still needs
 *
 * For a walk these are the same act, so when both concern the same habit this
 * renders ONE chip showing the running time (tapping it opens the timer, which
 * is what the user came back for). Rendering two stacked chips for one walk
 * would be exactly the "cannot tell which is which" confusion this is meant to
 * remove — hence the merge rather than two independent rows.
 */
@Composable
fun MinimizedSessionChip(
    context: Context,
    /** Restores the minimized ring sheet. */
    onResumeSheet: (HabitTimerRequests.Request) -> Unit,
    /** Opens the timer screen for a running run. */
    onOpenTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timerRepo = remember(context) { TimerRepository(context) }

    // Re-read on the same one-second beat the elapsed value needs, so a run
    // started or stopped anywhere in the app appears/disappears promptly.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    // Re-derived every tick rather than accumulated — see the class doc.
    val active = remember(tick) { runCatching { timerRepo.loadActive() }.getOrNull() }
    val minimizedSheet = remember(tick, active) {
        runCatching { HabitTimerRequests.peekMinimized(context) }.getOrNull()
    }

    // Habit lookup for names/icons, read once per tick together with everything
    // else rather than through a per-row `remember` inside a lambda — a
    // conditionally-evaluated `remember` is not a stable identity and would
    // recompute (or worse, be skipped) unpredictably across recompositions.
    val habits: List<Habit> = remember(tick) {
        runCatching { MindsetRepository(context).load().habits }.getOrDefault(emptyList())
    }

    // Nothing was put away — draw nothing at all rather than an empty host, so
    // this adds no layout to the common case.
    if (active == null && minimizedSheet == null) return

    val activeHabit = active?.habitId?.let { id -> habits.firstOrNull { it.id == id } }

    // Merge: a minimized sheet for the habit whose timer is running is one
    // session, so the timer row wins and the sheet stays reachable through it.
    val showTimerRow = active != null
    val showSheetRow = minimizedSheet != null &&
        (!showTimerRow || minimizedSheet.habitId != active?.habitId)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (active != null) {
                MinimizedRow(
                    iconId = activeHabit?.iconId,
                    title = activeHabit?.name?.takeIf { it.isNotBlank() }
                        ?: active.label.ifBlank { "Timer" },
                    detail = if (active.status != TimerStatus.RUNNING) {
                        "Paused · ${formatTimerDuration(active.elapsedAt(tick))} · tap to resume"
                    } else if (active.isOpenEnded) {
                        "Running · ${formatTimerDuration(active.elapsedAt(tick))} · tap to open"
                    } else {
                        "Running · ${formatTimerDuration(active.elapsedAt(tick))} of ${
                            formatTimerDuration(active.targetSeconds)
                        }"
                    },
                    onClick = onOpenTimer,
                    onStop = {
                        // Ends the run for real: the completion event is written
                        // and the popup offers to log it, so finishing from here
                        // can never lose the measurement.
                        runCatching { TimerController.stop(context) }
                    },
                )
                // Today's own count for this habit, so the chip answers "have I
                // done it yet" without the user having to open anything. Read
                // for the running habit only, and only when there is one.
                if (activeHabit != null) {
                    val todayCount = remember(activeHabit.id, tick) {
                        runCatching {
                            MindsetRepository(context).load()
                                .habitCountOn(activeHabit.id, Dates.todayKey())
                        }.getOrDefault(0)
                    }
                    if (todayCount > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "$todayCount recorded today",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showSheetRow && minimizedSheet != null) {
                if (active != null) Spacer(Modifier.height(6.dp))
                MinimizedRow(
                    iconId = minimizedSheet.iconId,
                    title = minimizedSheet.habitName.ifBlank { "Habit" },
                    detail = "Waiting for you · tap to continue",
                    onClick = { onResumeSheet(minimizedSheet) },
                    onStop = {
                        // Removing the chip means dismissing it outright.
                        // Nothing is recorded — the user never supplied the
                        // input — so the habit simply stays open.
                        runCatching {
                            HabitTimerRequests.clearMinimized(context)
                        }
                    },
                )
            }
        }
    }
}

/** One line of the minimized strip: icon, what it is, and its two actions. */
@Composable
private fun MinimizedRow(
    iconId: String?,
    title: String,
    detail: String,
    onClick: () -> Unit,
    onStop: () -> Unit,
) {
    val iconRes = remember(iconId) { iconId?.let { HabitIconCatalog.byId(it)?.drawableRes } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (iconRes != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Finish",
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Finish")
        }
    }
}
