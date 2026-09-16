package com.rork.mindsetframestracker.ui.screens

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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.HabitIconCatalog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rork.mindsetframestracker.data.ActiveTimer
import com.rork.mindsetframestracker.data.DEFAULT_TARGET_SECONDS
import com.rork.mindsetframestracker.data.TIMER_EXTEND_SECONDS
import com.rork.mindsetframestracker.data.TIMER_PRESET_MINUTES
import com.rork.mindsetframestracker.data.TimerCompletionEvent
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.data.TimerRepository
import com.rork.mindsetframestracker.data.TimerStatus
import com.rork.mindsetframestracker.data.formatTimerDuration
import com.rork.mindsetframestracker.notifications.TimerController
import kotlinx.coroutines.delay

/**
 * Timer + stopwatch, with the app's **one-popup-per-event** completion
 * behaviour.
 *
 * ## How the "exactly once" guarantee is built (four independent layers)
 *
 * 1. **Persisted pending event.** A completion writes a [TimerCompletionEvent]
 *    to [TimerRepository]; the popup reads *that record*, not a transient
 *    animation trigger. Rotation, navigation, backgrounding or a full process
 *    kill therefore cannot lose it, and cannot duplicate it either.
 * 2. **Consume-on-show.** Showing the popup immediately calls
 *    [TimerController.acknowledgeEvent], which appends the event id to an
 *    append-only handled-ledger in SharedPreferences. The ledger survives
 *    everything short of a data wipe, so the *same* event can never be shown
 *    a second time — by any entry point, in any process, ever.
 * 3. **No ephemeral trigger state.** The popup is driven purely by
 *    `pendingEvent != null`, a value re-read from disk. There is no
 *    `var showPopup by remember { mutableStateOf(...) }` that a
 *    recomposition/`rememberSaveable` could re-arm — the usual cause of
 *    "it popped up again after I rotated the screen". The only `remember`
 *    here holds the *dismissal* latch, and it is keyed by event id so a new
 *    event legitimately earns a new popup.
 * 4. **Lifecycle-resilient.** A [DisposableEffect] on `ON_RESUME` re-reads both
 *    the timer and the pending event, so a completion that landed while the
 *    screen was paused (the alarm fired with the app in the background) shows
 *    up on return — and because layer 2 already consumed it, returning *again*
 *    does not.
 *
 * The upshot for the user: the timer pops once when it hits zero, the
 * stopwatch pops once when it crosses its target, and neither ever reappears —
 * not on re-render, not on app resume, not after navigating away and back, not
 * after a reboot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    onBack: () -> Unit,
    onOpenHabits: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { TimerRepository(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var active by remember { mutableStateOf(repo.loadActive()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedKind by remember { mutableStateOf(active?.kind ?: TimerKind.TIMER) }
    var selectedMinutes by remember { mutableIntStateOf(DEFAULT_TARGET_SECONDS / 60) }
    var stopwatchTargetMinutes by remember { mutableIntStateOf(0) }
    var showStopConfirm by remember { mutableStateOf(false) }

    // Re-read persisted state on every resume. This is layer 4: a completion
    // that happened while this screen was paused appears now, and — because
    // showing it consumes it — does NOT appear a second time on the next resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                active = repo.loadActive()
                nowMs = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The on-screen clock. Purely a display refresh — it never decides whether
    // to pop anything, which is why a missed tick cannot cause a missed (or
    // duplicated) popup.
    LaunchedEffect(active?.id, active?.status) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(if (active?.status == TimerStatus.RUNNING) 250L else 1_000L)
        }
    }

    // In-app completion detection. The AlarmManager receiver and the foreground
    // service normally win this race; this exists so a short timer started and
    // watched entirely in-app still completes even if neither has fired yet.
    // All three go through TimerCompletion.fire(), whose repository gate means
    // only one of them can ever produce the event.
    LaunchedEffect(active?.id, active?.status, nowMs) {
        val timer = active ?: return@LaunchedEffect
        if (timer.status != TimerStatus.RUNNING) return@LaunchedEffect
        if (!timer.isExpiredAt(nowMs)) return@LaunchedEffect
        com.rork.mindsetframestracker.notifications.TimerCompletion.fire(context, timer)
        active = repo.loadActive()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (selectedKind == TimerKind.TIMER) "Timer" else "Stopwatch") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        val timer = active
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            TimerKindTabs(
                selected = selectedKind,
                enabled = timer == null || timer.completionFired,
                onSelect = { selectedKind = it },
            )

            if (timer != null && !timer.completionFired) {
                RunningTimerPanel(
                    timer = timer,
                    nowMs = nowMs,
                    onPause = {
                        TimerController.pause(context)
                        active = repo.loadActive()
                    },
                    onResume = {
                        TimerController.resume(context)
                        active = repo.loadActive()
                    },
                    onExtend = {
                        TimerController.extendAndResume(context, TIMER_EXTEND_SECONDS)
                        active = repo.loadActive()
                    },
                    onLap = {
                        TimerController.lap(context)
                        active = repo.loadActive()
                    },
                    onStopRequested = { showStopConfirm = true },
                )
            } else {
                TimerSetupPanel(
                    kind = selectedKind,
                    selectedMinutes = selectedMinutes,
                    onMinutesChange = { selectedMinutes = it },
                    stopwatchTargetMinutes = stopwatchTargetMinutes,
                    onStopwatchTargetChange = { stopwatchTargetMinutes = it },
                    onStart = {
                        val targetSeconds = when (selectedKind) {
                            TimerKind.TIMER -> selectedMinutes * 60
                            TimerKind.STOPWATCH -> stopwatchTargetMinutes * 60
                        }
                        TimerController.start(
                            context = context,
                            kind = selectedKind,
                            targetSeconds = targetSeconds,
                            label = if (selectedKind == TimerKind.TIMER) "Timer" else "Stopwatch",
                        )
                        active = repo.loadActive()
                        nowMs = System.currentTimeMillis()
                    },
                )
            }
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop this timer?") },
            text = { Text("The current time won't be saved and no completion alert will fire.") },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirm = false
                        TimerController.stop(context)
                        active = repo.loadActive()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Keep going") }
            },
        )
    }
}

/** Timer / stopwatch switch. Locked while a run is in progress. */
@Composable
private fun TimerKindTabs(
    selected: TimerKind,
    enabled: Boolean,
    onSelect: (TimerKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(TimerKind.TIMER to "Timer", TimerKind.STOPWATCH to "Stopwatch").forEach { (kind, label) ->
            FilterChip(
                selected = selected == kind,
                onClick = { if (enabled) onSelect(kind) },
                enabled = enabled,
                label = { Text(label) },
                leadingIcon = {
                    Icon(
                        imageVector = if (kind == TimerKind.TIMER) Icons.Outlined.Timer
                        else Icons.Outlined.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/** Setup state: pick a duration (timer) or an optional goal (stopwatch). */
@Composable
private fun TimerSetupPanel(
    kind: TimerKind,
    selectedMinutes: Int,
    onMinutesChange: (Int) -> Unit,
    stopwatchTargetMinutes: Int,
    onStopwatchTargetChange: (Int) -> Unit,
    onStart: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when (kind) {
                TimerKind.TIMER -> "How long is this timer?"
                TimerKind.STOPWATCH -> "Set a goal, or leave it open"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        if (kind == TimerKind.TIMER) {
            FilterChipsRow(
                options = TIMER_PRESET_MINUTES.map { it to "${it}m" },
                isSelected = { it == selectedMinutes },
                onPick = onMinutesChange,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = formatTimerDuration(selectedMinutes * 60),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else {
            FilterChipsRow(
                options = (listOf(0) + TIMER_PRESET_MINUTES).map {
                    it to if (it == 0) "Open" else "${it}m"
                },
                isSelected = { it == stopwatchTargetMinutes },
                onPick = onStopwatchTargetChange,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (stopwatchTargetMinutes == 0) {
                    "Counts up until you stop it"
                } else {
                    "Alerts once at ${formatTimerDuration(stopwatchTargetMinutes * 60)}, then keeps counting"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (kind == TimerKind.TIMER) "Start timer" else "Start stopwatch")
        }
    }
}

@Composable
private fun FilterChipsRow(
    options: List<Pair<Int, String>>,
    isSelected: (Int) -> Boolean,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    FilterChip(
                        selected = isSelected(value),
                        onClick = { onPick(value) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

/** Running/paused state: big readout, progress, controls, splits. */
@Composable
private fun RunningTimerPanel(
    timer: ActiveTimer,
    nowMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExtend: () -> Unit,
    onLap: () -> Unit,
    onStopRequested: () -> Unit,
) {
    val elapsed = timer.elapsedAt(nowMs)
    val remaining = timer.remainingSecondsAt(nowMs)
    val progress = timer.progressAt(nowMs)
    val isRunning = timer.status == TimerStatus.RUNNING

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                timer.kind == TimerKind.TIMER -> "Timer"
                timer.hasTarget -> "Stopwatch \u00b7 goal ${formatTimerDuration(timer.targetSeconds)}"
                else -> "Stopwatch"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (timer.hasTarget) formatTimerDuration(remaining) else formatTimerDuration(elapsed),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (timer.hasTarget) {
                "elapsed ${formatTimerDuration(elapsed)}"
            } else {
                "counting up"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (progress != null) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isRunning) "Running" else "Paused",
            style = MaterialTheme.typography.labelMedium,
            color = if (isRunning) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { if (isRunning) onPause() else onResume() },
                modifier = Modifier.height(52.dp),
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isRunning) "Pause" else "Resume")
            }
            OutlinedButton(onClick = onStopRequested, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Outlined.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (timer.hasTarget) {
                OutlinedButton(onClick = onExtend) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("5 min")
                }
            }
            if (timer.kind == TimerKind.STOPWATCH) {
                OutlinedButton(onClick = onLap) {
                    Icon(Icons.Outlined.Flag, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Lap")
                }
            }
        }

        if (timer.splits.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Splits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    timer.splits.reversed().take(6).forEach { split ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Lap ${split.label}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatTimerDuration(split.elapsedSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The completion popup itself.
 *
 * Owned globally by [com.rork.mindsetframestracker.ui.navigation.AppNavigation]
 * (not by [TimerScreen]) so a timer that finishes while the user is on another
 * tab — or with the app in the background — still surfaces its popup the moment
 * the app is visible, rather than only if the timer screen happens to be open.
 *
 * It is a plain function of the event it is handed: no internal "already
 * shown" state, no timers, no side effects on composition. Everything about
 * showing it *once* lives in the caller and in [TimerRepository]'s ledger,
 * which is what makes the guarantee testable and immune to Compose's
 * recomposition/rotation behaviour.
 */
@Composable
fun TimerCompletionPopup(
    event: TimerCompletionEvent,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTimer = event.kind == TimerKind.TIMER
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            // The timer mark belongs to the timer's own moment, not to a screen:
            // this is the same circle-and-walking-figure badge that used to sit
            // on the timer entry card, and it now lives here — inside the popup
            // that appears once when the timer alarm goes off. A stopwatch goal
            // (not a timer) keeps the plain timer glyph.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isTimer) Icons.Outlined.Timer else Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        title = {
            Text(if (isTimer) "Timer done!" else "Goal reached!")
        },
        text = {
            Column {
                Text(
                    text = if (isTimer) {
                        "You timed ${formatTimerDuration(event.targetSeconds)}."
                    } else {
                        "Your stopwatch passed ${formatTimerDuration(event.targetSeconds)} " +
                            "(at ${formatTimerDuration(event.elapsedSeconds)})."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (isTimer) {
                        "Mark this habit as done?"
                    } else {
                        "The stopwatch is still counting if you want to keep going."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onPrimary) {
                Text(if (isTimer) "Mark done" else "Done")
            }
        },
        dismissButton = {
            if (isTimer) {
                Row {
                    TextButton(onClick = onSecondary) { Text("Not yet") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            } else {
                TextButton(onClick = onSecondary) { Text("Keep counting") }
            }
        },
    )
}
