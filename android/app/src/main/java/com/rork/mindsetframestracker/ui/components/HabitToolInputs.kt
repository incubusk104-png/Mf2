package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.HabitLogEntry
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.TIMER_PRESET_MINUTES
import com.rork.mindsetframestracker.data.formatTimerDuration

/**
 * The five per-mode habit tools, as **one implementation with two surfaces**.
 *
 * ## Why these live here rather than inside a sheet
 *
 * A habit's own tool now has to appear in two places:
 *
 *  * the habit sheet the user opens by tapping a habit (the tap-to-track path,
 *    `HabitTrackingSheet`); and
 *  * the dialog that appears on its own the moment the alarm goes off
 *    ([AlarmActionDialog]).
 *
 * These were `private` inside `HabitTrackingSheet`, which meant the alarm
 * dialog's only options were to duplicate them or to render the wrong control.
 * Duplication is the real hazard: a "log how many glasses" stepper fixed in one
 * copy and not the other is how the ring and the sheet come to disagree about
 * what logging water even means — and the disagreement is invisible, because each
 * copy looks right on its own.
 *
 * So the inputs are hoisted here, `internal`, and both surfaces compose the same
 * functions. Each input is still a pure function of its parameters, which is what
 * makes a second call site safe rather than merely convenient.
 *
 * ## What each mode asks for
 *
 * | mode        | what the user gets                                     |
 * |-------------|--------------------------------------------------------|
 * | [CHECK]     | a single "Done" — did you do it?                      |
 * | [TIMER]     | a length to commit to, then a count-down that rings     |
 * | [STOPWATCH] | one button — count up, stop whenever you are finished  |
 * | [JOURNAL]   | a title and a note                                     |
 * | [COUNT]     | a stepper against a goal ("6 of 8 glasses")            |
 */

/** The one-tap mode: nothing to measure, so nothing to ask. */
@Composable
internal fun CheckInput(label: String, onConfirm: () -> Unit) {
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
internal fun TimerInput(targetSeconds: Int, onStart: (Int) -> Unit) {
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
internal fun StopwatchInput(onStart: () -> Unit) {
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
internal fun JournalInput(onSave: (title: String?, note: String?) -> Unit) {
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
internal fun CountInput(targetCount: Int, unit: String, onSave: (Int) -> Unit) {
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

/** Icon that represents what each mode asks for. */
internal fun iconFor(mode: HabitTrackingMode): ImageVector = when (mode) {
    HabitTrackingMode.CHECK -> Icons.Outlined.Check
    HabitTrackingMode.TIMER -> Icons.Outlined.Timer
    HabitTrackingMode.STOPWATCH -> Icons.Outlined.Flag
    HabitTrackingMode.JOURNAL -> Icons.Outlined.Edit
    HabitTrackingMode.COUNT -> Icons.Outlined.Add
}

/** The one-line explanation under the habit's name, per mode. */
internal fun subtitleFor(mode: HabitTrackingMode): String = when (mode) {
    HabitTrackingMode.CHECK -> "Tick it off when it's done"
    HabitTrackingMode.TIMER -> "Set the time, and it rings when you're done"
    HabitTrackingMode.STOPWATCH -> "Time it and stop whenever you're finished"
    HabitTrackingMode.JOURNAL -> "Write down how it went"
    HabitTrackingMode.COUNT -> "Log how many you did"
}

/** "1 glass" / "3 glasses" — a naive plural that reads correctly for our units. */
internal fun valueLabel(amount: Int, unit: String): String {
    val singular = unit.trimEnd('s')
    return if (amount == 1) "1 $singular" else "$amount $unit"
}

/** Stable key for the timer preset state, so it resets per habit not per frame. */
internal fun habitKeyFor(minutes: Int): Int = minutes

/** One-line summary of a recorded entry, in whatever its mode produced. */
internal fun describeLog(entry: HabitLogEntry): String {
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
