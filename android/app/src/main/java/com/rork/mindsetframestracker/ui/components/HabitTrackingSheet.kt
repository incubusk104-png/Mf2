package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.AlarmDaySlot
import com.rork.mindsetframestracker.data.HabitAlarmSetup
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.data.HabitLogEntry
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.TimerKind
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
 * ## The inputs themselves live in HabitToolInputs
 *
 * The five per-mode controls ([CheckInput], [TimerInput], [StopwatchInput],
 * [JournalInput], [CountInput]) are declared in `HabitToolInputs.kt` and shared
 * with the dialog the alarm raises, so "log how many glasses" cannot come to
 * mean one thing on the ring and another in this sheet. They are in the same
 * package and keep their names, so the call sites below resolve unchanged.
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

            // ── What this habit's alarms are doing today ─────────────────
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

            // ── Connect the fitness apps that can record this habit ──────
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
            // ── Leave, or come back later ────────────────────────────────
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
