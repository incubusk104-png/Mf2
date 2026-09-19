package com.rork.mindsetframestracker.ui.components

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.AlarmDaySlot
import com.rork.mindsetframestracker.data.HabitAlarmSetup
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.data.HabitLogEntry
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.TIMER_PRESET_MINUTES
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.data.formatTimerDuration
import com.rork.mindsetframestracker.integrations.TrackerConnections
import com.rork.mindsetframestracker.integrations.TrackerStatus
import com.rork.mindsetframestracker.ui.appStrings

/**
 * The activity-completion dialog, **driven by the habit's own tracking mode**.
 *
 * ## The problem this replaces
 *
 * The app had one completion gesture for every habit: tap the row, it flips to
 * checked, today is recorded. That treats a 30-minute walk, a written gratitude
 * entry and eight glasses of water as the same act, and none of them are
 * something you can express with a tap. The result was a tracker that could say
 * *that* you did something but never *what* you did — and for a habit whose
 * whole point is the measurement, the tap recorded nothing worth keeping.
 *
 * There is no single correct form, so there is no single form here. The habit
 * decides: [HabitTrackingMode] is a property of the habit (see
 * [com.rork.mindsetframestracker.data.Habit.trackingModeOrDefault]), and this
 * sheet renders exactly the input that mode needs and nothing else.
 *
 * | mode        | what the user gets                                  |
 * |-------------|-----------------------------------------------------|
 * | [CHECK]     | a single "Done" — did you do it?                     |
 * | [TIMER]     | a length to commit to, then a count-down that rings  |
 * | [STOPWATCH] | one button — count up, stop whenever you are finished |
 * | [JOURNAL]   | a title and a note                                   |
 * | [COUNT]     | a stepper against a goal ("6 of 8 glasses")          |
 *
 * ## Why the timed modes hand off instead of counting here
 *
 * TIMER and STOPWATCH do not run a clock inside this sheet on purpose. A timer
 * has to survive the sheet closing, the app being backgrounded, the screen
 * locking and the process being killed — that is what
 * [com.rork.mindsetframestracker.notifications.TimerController] and its
 * AlarmManager + foreground-service machinery exist for, and it derives elapsed
 * time from the wall clock rather than from a ticking counter precisely so it
 * cannot drift. A countdown living in this composable would stop the moment it
 * left composition. So these two modes start a real run and open the timer
 * screen; the completion then arrives through the normal once-only funnel and is
 * attributed back to this habit by `habitId`.
 *
 * ## Recording
 *
 * Every mode funnels into [onRecord], which persists a
 * [com.rork.mindsetframestracker.data.HabitLogEntry] for the habit and marks the
 * day done. Nothing here writes state itself — this is presentation, so the
 * record's shape is defined in one place rather than per screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitTrackingSheet(
    habitName: String,
    habitIconId: String?,
    trackingMode: HabitTrackingMode,
    targetSeconds: Int,
    targetCount: Int,
    unit: String,
    recentLogs: List<HabitLogEntry> = emptyList(),
    onRecord: (
        title: String?,
        note: String?,
        durationSeconds: Int?,
        count: Int?,
    ) -> Unit,
    onStartTimed: (kind: TimerKind, targetSeconds: Int) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Minimize this sheet instead of abandoning it — the "take it anytime" path.
     *
     * Null (the default) means the sheet has no minimized home to go to, in which
     * case the button is simply not rendered rather than being shown and doing
     * nothing. The Habits screen passes null because there the sheet *is* the
     * destination; the root alarm host passes a real handler because a ring can
     * arrive while the user is mid-something-else.
     */
    onMinimize: (() -> Unit)? = null,
    /**
     * Every one of this habit's alarms for today, one entry per scheduled time.
     *
     * Supplied by the caller (which holds the loaded
     * [com.rork.mindsetframestracker.data.AppData]) rather than read here, so
     * this sheet stays a pure function of its inputs and — more importantly —
     * so the habit-today view and the alarm picker render the *same* list from
     * the *same* source. A second read site is how the two surfaces would come
     * to disagree about whether the 12:00 alarm was answered.
     */
    alarmSlots: List<AlarmDaySlot> = emptyList(),
    /**
     * What is set up inside the habit: schedule, repeat rule, and the line its
     * alarm will deliver.
     *
     * Null when the caller cannot resolve the habit (e.g. the habit was deleted
     * out from under an open sheet), in which case only the timeline is shown —
     * there is no setup left to describe.
     */
    alarmSetup: HabitAlarmSetup? = null,
    /**
     * Opens the connect-fitness flow for THIS habit, from inside this dialog.
     *
     * The connect control used to be a global row at the top of the habits list
     * — a separate position, which is what the request objected to. It now lives
     * here, in the habit's own dialog, so opening a habit ("walk") offers the
     * connections that can actually supply it.
     *
     * ## Why this sheet has to carry it, and not only the alarm editor
     *
     * This is the dialog the user lands on **after the alarm fires and notifies
     * them** — the ring host and the tap-to-track path both open it. If the
     * connect action existed only in the alarm editor, the one moment the user is
     * most likely to want it (the alarm just told them to walk) would be the one
     * moment it was missing. Carrying it here is what makes the alarm flow lead
     * into the connection rather than merely precede it.
     *
     * Null (the default) leaves the section out entirely, so a caller with no
     * tracker host renders nothing rather than buttons that do nothing.
     */
    onOpenTracker: (() -> Unit)? = null,
    /**
     * Each provider's resolved state, so a row can honestly say "Connected" or
     * show a padlock instead of offering a connect that cannot succeed.
     *
     * Empty means the caller has not resolved them; the rows then name the
     * providers without claiming a state.
     */
    trackerStatuses: List<TrackerStatus> = emptyList(),
) {
    val s = appStrings()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    /** Which occurrence's detail dialog is open, if any. */
    var detailSlot by remember { mutableStateOf<AlarmDaySlot?>(null) }
    val iconRes = remember(habitIconId) {
        habitIconId?.let { HabitIconCatalog.byId(it)?.drawableRes }
    }
    val label = habitName.ifBlank { "Habit" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // ── Header: the habit's own artwork, so the sheet is anchored ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconRes != null) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = iconRes),
                            contentDescription = label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(30.dp),
                        )
                    } else {
                        Icon(
                            imageVector = iconFor(trackingMode),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitleFor(trackingMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── The input, chosen by the habit's mode ──────────────────────
            when (trackingMode) {
                HabitTrackingMode.CHECK -> CheckInput(
                    label = label,
                    onConfirm = { onRecord(null, null, null, null) },
                )

                HabitTrackingMode.TIMER -> TimerInput(
                    targetSeconds = targetSeconds,
                    onStart = { seconds ->
                        onStartTimed(TimerKind.TIMER, seconds)
                    },
                )

                HabitTrackingMode.STOPWATCH -> StopwatchInput(
                    onStart = { onStartTimed(TimerKind.STOPWATCH, 0) },
                )

                HabitTrackingMode.JOURNAL -> JournalInput(
                    onSave = { title, note -> onRecord(title, note, null, null) },
                )

                HabitTrackingMode.COUNT -> CountInput(
                    targetCount = targetCount,
                    unit = unit,
                    onSave = { amount -> onRecord(null, null, null, amount) },
                )
            }

            // ── What this habit's alarms are doing today ────────────────
            // The same section the alarm picker renders (see
            // [HabitAlarmOverviewSection]), so a habit cannot be described one
            // way here and another way in the schedule editor. It answers the
            // question the ring interrupted: which of today's alarms fired,
            // which did I answer, which slipped past — and what is set up
            // inside this habit. Rendered even with no alarms configured, when
            // [alarmSetup] is non-null, because "this habit has no alarm" is
            // something the user should be able to see plainly.
            if (alarmSlots.isNotEmpty() || alarmSetup != null) {
                Spacer(Modifier.height(18.dp))
                HabitAlarmOverviewSection(
                    title = s.habitSetupTitle,
                    slots = alarmSlots,
                    plan = alarmSetup,
                    // The providers that can actually supply this habit's icon,
                    // so the sheet never advertises an integration that cannot
                    // apply to what is on screen.
                    trackerProviders = TrackerConnections.sourcesFor(habitIconId),
                    onSelect = { detailSlot = it },
                )
                // Layered over the sheet rather than replacing it: the user is
                // mid-way through completing the habit, and closing their form to
                // show a read-only detail would discard what they had typed.
                detailSlot?.let { slot ->
                    AlarmOccurrenceDetailDialog(slot = slot, onDismiss = { detailSlot = null })
                }
            }

            // ── Connect the fitness apps that can record this habit ───────────
            // Inside the habit's own dialog, and deliberately outside the block
            // above so it is shown even for a habit with no alarm configured —
            // "connect Strava so my walks count" is a thing to do whether or
            // not a reminder rings. Only rendered when a provider can actually
            // supply this habit, so a journal entry is not offered Strava.
            //
            // Placed here rather than beside the tracking controls on purpose:
            // the input above is how the user records the habit *now*, and the
            // trackers below are how the habit records *itself*. Keeping them
            // visually separate is what stops the connect row from reading as
            // one more way to complete the habit.
            val trackerProviders = TrackerConnections.sourcesFor(habitIconId)
            if (trackerProviders.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionTrackerRows(
                    trackerProviders = trackerProviders,
                    onOpenTracker = onOpenTracker,
                    trackerStatuses = trackerStatuses,
                )
            }

            if (recentLogs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                RecentLogsSection(logs = recentLogs)
            }

            Spacer(Modifier.height(6.dp))
            // ── Leave, or come back later ──────────────────────────────────
            // "Minimize" and "Cancel" are deliberately different actions and
            // both are named explicitly:
            //
            //  * **Minimize** keeps the sheet reachable under a running chip,
            //    so a walk the user started can be paused mid-way and the rest
            //    of the habit logged when they get back. Nothing is recorded
            //    yet either way — the chip is a way back, not a submission.
            //  * **Cancel** discards and writes nothing, exactly as before.
            //
            // Labelled "Minimize" rather than an icon-only chevron because an
            // unlabelled collapse arrow next to a Cancel button reads as "close"
            // — which would make the two buttons look like the same action and
            // lose the user's in-progress state.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onMinimize != null) {
                    TextButton(onClick = onMinimize, modifier = Modifier.weight(1f)) {
                        Text("Minimize")}
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
            }
        }
    }
}

/** The one-tap mode: nothing to measure, so nothing to ask. */
@Composable
private fun CheckInput(label: String, onConfirm: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Mark $label done for today?",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Done")
        }
    }
}

/**
 * Timer: commit to a length, and be told when it is up.
 *
 * The presets are the same quick-picks the timer screen offers, so the choice
 * made here is visible and adjustable in exactly the same terms afterwards.
 */
@Composable
private fun TimerInput(targetSeconds: Int, onStart: (Int) -> Unit) {
    // Default to the habit's own target (a 45-minute gym block, a 20-minute
    // read) rather than a generic value — the habit already knows what it is.
    val defaultMinutes = (targetSeconds / 60).takeIf { it > 0 } ?: 20
    var minutes by remember(habitKeyFor(defaultMinutes)) { mutableIntStateOf(defaultMinutes) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "How long?",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(10.dp))

        // Presets in a simple wrapping row of chips, with the habit's own
        // target guaranteed to be present even if it is not one of the presets.
        val options = (TIMER_PRESET_MINUTES + defaultMinutes).distinct().sorted()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.chunked(4).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEach { option ->
                        FilterChip(
                            selected = option == minutes,
                            onClick = { minutes = option },
                            label = { Text("${option}m") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = formatTimerDuration(minutes * 60),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onStart(minutes * 60) }, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Start timer")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "It rings when the time is up, even if the app is closed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Stopwatch: the mode for an activity whose length you find out by doing it.
 *
 * One button and no target — that absence is the point. Asking a walker to pick
 * a duration before they walk would be asking them to know the answer in
 * advance, which is exactly what a stopwatch avoids.
 */
@Composable
private fun StopwatchInput(onStart: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Outlined.Flag,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Start stopwatch")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Counts up until you stop it — no target, so nothing to set up front.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Journal: the output is text, so the input asks for text.
 *
 * Both fields are offered but only the note is required — a title alone is a
 * legitimate entry, and demanding a body would make a two-line gratitude note
 * feel like homework. The title is deliberately separate from the body rather
 * than a first line of it: it is what the user scans back through later.
 */
@Composable
private fun JournalInput(onSave: (title: String?, note: String?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            placeholder = { Text("Morning pages") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("What's on your mind?") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Button(
            // Enabled only once there is something to save, so an empty entry
            // can never be recorded as a completed journal habit.
            enabled = title.isNotBlank() || note.isNotBlank(),
            onClick = { onSave(title, note) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Save entry")
        }
    }
}

/**
 * Count: a quantity against a goal ("8 glasses"), so the input is a stepper.
 *
 * Starts at 1 rather than 0 — a tap that opens this sheet already means "I did
 * some of it", and making the user press + before the button is enabled would be
 * a pointless extra step.
 */
@Composable
private fun CountInput(targetCount: Int, unit: String, onSave: (Int) -> Unit) {
    var amount by remember { mutableIntStateOf(1) }
    val goal = targetCount.coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { if (amount > 1) amount-- }, enabled = amount > 1) {
                Icon(
                    imageVector = Icons.Outlined.Remove,
                    contentDescription = "Less",
                )
            }
            Text(
                text = "$amount",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            IconButton(onClick = { amount++ }) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "More",
                )
            }
        }

        Text(
            text = if (goal > 0) {
                // Names progress against the habit's own goal, which is the
                // reason the goal is stored per habit rather than globally.
                "${valueLabel(amount, unit)} · goal $goal $unit"
            } else {
                valueLabel(amount, unit)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(14.dp))
        Button(onClick = { onSave(amount) }, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (goal > 0 && amount >= goal) "Goal reached" else "Log it")
        }
    }
}

/**
 * The habit's recent results, shown so "record it against this habit" is
 * visible rather than merely claimed. Kept to two entries and one line each —
 * this is confirmation that the record landed, not a history view.
 */
@Composable
private fun RecentLogsSection(logs: List<HabitLogEntry>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Recent",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        logs.take(2).forEach { entry ->
            Text(
                text = "• ${describeLog(entry)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/** One-line summary of a recorded entry, in whatever its mode produced. */
private fun describeLog(entry: HabitLogEntry): String {
    val when_ = entry.dayKey
    return when (entry.mode) {
        HabitTrackingMode.JOURNAL -> {
            val title = entry.title?.takeIf { it.isNotBlank() }
            listOfNotNull(title, entry.note?.takeIf { it.isNotBlank() }).joinToString(" — ")
                .ifBlank { "Journal entry" } + " ($when_)"
        }
        HabitTrackingMode.COUNT -> {
            val amount = entry.count ?: 0
            val unit = entry.unit.orEmpty()
            "$amount $unit".trim() + " ($when_)"
        }
        else -> {
            val seconds = entry.durationSeconds ?: 0
            listOf(formatTimerDuration(seconds), when_).joinToString(" · ")
        }
    }
}

/** Icon that represents what each mode asks for. */
private fun iconFor(mode: HabitTrackingMode): ImageVector = when (mode) {
    HabitTrackingMode.CHECK -> Icons.Outlined.Check
    HabitTrackingMode.TIMER -> Icons.Outlined.Timer
    HabitTrackingMode.STOPWATCH -> Icons.Outlined.Flag
    HabitTrackingMode.JOURNAL -> Icons.Outlined.Edit
    HabitTrackingMode.COUNT -> Icons.Outlined.Add
}

/** The one-line explanation under the habit's name, per mode. */
private fun subtitleFor(mode: HabitTrackingMode): String = when (mode) {
    HabitTrackingMode.CHECK -> "Tick it off when it's done"
    HabitTrackingMode.TIMER -> "Set the time, and it rings when you're done"
    HabitTrackingMode.STOPWATCH -> "Time it and stop whenever you're finished"
    HabitTrackingMode.JOURNAL -> "Write down how it went"
    HabitTrackingMode.COUNT -> "Log how many you did"
}

/** "1 glass" / "3 glasses" — a naive plural that reads correctly for our units. */
private fun valueLabel(amount: Int, unit: String): String {
    val singular = unit.trimEnd('s')
    return if (amount == 1) "1 $singular" else "$amount $unit"
}

/** Stable key for the timer preset state, so it resets per habit not per frame. */
private fun habitKeyFor(minutes: Int): Int = minutes
