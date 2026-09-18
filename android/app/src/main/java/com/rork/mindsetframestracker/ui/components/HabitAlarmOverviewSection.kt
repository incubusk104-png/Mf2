package com.rork.mindsetframestracker.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.AlarmDaySlot
import com.rork.mindsetframestracker.data.AlarmSlotState
import com.rork.mindsetframestracker.data.HabitAlarmSetup
import com.rork.mindsetframestracker.data.alarmTimeOfDayLabel
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.appStrings

/**
 * The shared "what is this habit's alarm doing today?" overview.
 *
 * ## Why one implementation, used twice
 *
 * This section is rendered by **two** different surfaces:
 *
 *  * the alarm-time picker (`AlarmPickerDialog`), where it sits under the
 *    schedule editor so the user can see the day they are editing into; and
 *  * the habit-today sheet (`HabitTrackingSheet`), which is what the user opens
 *    when they tap a habit to complete it — the "habit today" dialog.
 *
 * A second implementation for the second surface is exactly how the two would
 * drift: the picker would say "2 of 3 answered" while the habit-today sheet said
 * something subtly different about the same three alarms, and the user would be
 * right to distrust both. One composable, two call sites.
 *
 * ## What it shows, and why every time appears
 *
 * One row per **scheduled time** — not one per habit, and not only the alarms
 * that left a trace. A habit set for 07:00/12:00/18:00 always renders three
 * rows, which is the whole point: the user asked to be able to see, at a
 * glance, which of the day's alarms fired, which were answered, and which never
 * happened. Deriving rows from the schedule (rather than from recorded events)
 * is what makes a silent 12:00 visible as [AlarmSlotState.MISSED] instead of
 * simply absent.
 *
 * [com.rork.mindsetframestracker.data.HabitAlarmHistory.daySlots] also includes
 * any time that fired but is no longer in the schedule, so editing a habit does
 * not erase an alarm that really rang this morning.
 *
 * ## Dumb by design
 *
 * All ordering and state derivation happens in
 * [com.rork.mindsetframestracker.data.HabitAlarmHistory.daySlots]; this only
 * labels what it is given. That keeps the "07:00 done, 12:00 missed" logic
 * testable without Compose, and means the timeline cannot disagree with the
 * notification subtitle about what an occurrence was.
 */
@Composable
internal fun HabitAlarmOverviewSection(
    /**
     * Header for the timeline. Passed in rather than hardcoded so the picker and
     * the habit-today sheet can title the same list in their own words.
     */
    title: String,
    /** Today's occurrences for this habit, ascending. One row each. */
    slots: List<AlarmDaySlot>,
    /**
     * What the habit has set up — schedule, repeat rule, and the line its alarm
     * delivers. Null for a habit that does not exist yet, in which case only the
     * timeline is shown: there is no setup to describe.
     */
    plan: HabitAlarmSetup?,
    /** Providers able to track this habit, for the "what's inside" summary. */
    trackerProviders: List<TrackerProvider> = emptyList(),
    onSelect: (AlarmDaySlot) -> Unit,
) {
    val s = appStrings()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
        )
        val answered = slots.count { it.isAnswered }
        if (slots.isNotEmpty()) {
            Text(
                text = s.habitsAlarmHistorySummary.format(answered, slots.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        when {
            // Nothing configured at all — distinct from "configured but silent",
            // which is the informative case below.
            slots.isEmpty() -> Text(
                text = s.habitsAlarmHistoryEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Configured, but not one of them has rung yet today (every time is
            // still ahead of the clock). Saying "nothing yet" is more useful than
            // a list of identical "Later today" rows.
            slots.none { it.state != AlarmSlotState.PENDING } -> Text(
                text = s.habitsAlarmHistoryNoneYet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                slots.forEach { slot ->
                    AlarmOverviewRow(slot = slot, onClick = { onSelect(slot) })
                }
            }
        }
        if (slots.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.habitsAlarmDetailHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── What is set up inside the habit ─────────────────────────────────
        // Shown for an EXISTING habit even when it has no alarm, because "this
        // habit has no alarm" is itself something the user needs to see plainly
        // rather than infer from an absence. This is the "what is set up inside
        // the habit" half of the at-a-glance view: the timeline above says what
        // happened today, this says what the habit is configured to do.
        if (plan != null) {
            Spacer(Modifier.height(12.dp))
            HabitSetupSummary(plan = plan, trackerProviders = trackerProviders)
        }
    }
}

/**
 * What the habit contains: its schedule, how often it repeats, and the line its
 * alarm will say.
 *
 * Rendered as label/value pairs rather than a sentence so the three facts can be
 * scanned independently — a user checking "did my weekday setting stick?" should
 * not have to parse a paragraph.
 */
@Composable
private fun HabitSetupSummary(
    plan: HabitAlarmSetup,
    trackerProviders: List<TrackerProvider>,
) {
    val s = appStrings()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = s.habitSetupTitle,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(6.dp))

        // The schedule itself. For a habit with no alarm this is the one line
        // that tells the user nothing will ring — said outright rather than left
        // as a blank, which would be indistinguishable from a rendering fault.
        OverviewFact(
            label = s.habitSetupSchedule,
            value = if (plan.hasAlarm) plan.scheduleLabel else s.habitSetupNoAlarm,
        )
        if (plan.hasAlarm) {
            OverviewFact(label = s.habitSetupRepeat, value = plan.repeatLabel)
        }
        // The line the alarm will deliver. Marked as the user's own when they
        // wrote it, because "this is the app's default" and "this is what I
        // typed" are different facts about the same string.
        OverviewFact(
            label = s.habitSetupMessage,
            value = plan.effectiveMessage,
        )
        if (plan.hasCustomMessage) {
            Text(
                text = s.habitSetupMessageCustom,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only shown when a tracker can actually supply this habit, so the sheet
        // never advertises an integration that cannot apply to what is on screen.
        if (trackerProviders.isNotEmpty()) {
            OverviewFact(
                label = s.habitSetupTracker,
                value = trackerProviders.joinToString(", ") { it.label },
            )
        }
    }
}

/** A label/value pair in the habit-today overview. */
@Composable
private fun OverviewFact(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One time in the timeline: the clock, its state, and the line it delivered.
 *
 * The whole row is the tap target and carries a chevron, so "there is more here"
 * is visible rather than something the hint text has to teach. The state label
 * is also what a screen reader announces, because a row whose only text is
 * "07:00" tells a TalkBack user nothing about whether that alarm was answered.
 */
@Composable
private fun AlarmOverviewRow(
    slot: AlarmDaySlot,
    onClick: () -> Unit,
) {
    val s = appStrings()
    val stateLabel = alarmStateLabel(s, slot.state)
    // Answered occurrences get the muted treatment; an outstanding or missed one
    // keeps the accent so it is what the eye lands on in the list.
    val stateColor = when (slot.state) {
        AlarmSlotState.ACKNOWLEDGED -> MaterialTheme.colorScheme.primary
        AlarmSlotState.MISSED -> MaterialTheme.colorScheme.error
        AlarmSlotState.FIRED, AlarmSlotState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        AlarmSlotState.DISMISSED, AlarmSlotState.SNOOZED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(
            text = slot.clockLabel,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = stateColor,
            )
            // The line the alarm actually said. Omitted entirely when none was
            // recorded rather than showing a placeholder, so the row stays one
            // line for a missed alarm that never delivered anything.
            slot.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The detail of one occurrence: what it was scheduled for, what it actually did,
 * what it said, and when the user answered it.
 *
 * A read-only view of what was recorded — the alarm cannot be re-armed from
 * here, because the schedule editor is the one place that owns alarm times and a
 * second editing surface would be a second source of truth for them.
 *
 * Shared by both surfaces for the same reason as the section above: an
 * occurrence must be described identically wherever it is tapped.
 */
@Composable
internal fun AlarmOccurrenceDetailDialog(
    slot: AlarmDaySlot,
    onDismiss: () -> Unit,
) {
    val s = appStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${s.habitsAlarmDetailTitle} · ${slot.clockLabel}")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OverviewFact(label = s.habitsAlarmDetailTime, value = slot.clockLabel)
                OverviewFact(label = s.habitsAlarmDetailState, value = alarmStateLabel(s, slot.state))
                OverviewFact(
                    label = s.habitsAlarmDetailMessage,
                    value = slot.message?.takeIf { it.isNotBlank() }
                        ?: s.habitsAlarmDetailNoMessage,
                )
                // Ring and answer times are separate rows because they answer
                // different questions, and an alarm that was never answered has no
                // answer time at all — which is exactly what "Not recorded" says.
                val firedAt = slot.events.mapNotNull { it.firedAtEpochMs.takeIf { ms -> ms > 0L } }
                    .minOrNull()
                val answeredAt = slot.latest?.respondedAtEpochMs
                OverviewFact(
                    label = s.habitsAlarmDetailFiredAt,
                    value = alarmTimeOfDayLabel(firedAt ?: 0L)
                        .ifBlank { s.habitsAlarmDetailNotRecorded },
                )
                if (slot.isAnswered) {
                    OverviewFact(
                        label = s.habitsAlarmDetailAnsweredAt,
                        value = alarmTimeOfDayLabel(answeredAt ?: 0L)
                            .ifBlank { s.habitsAlarmDetailNotRecorded },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.habitsDone) }
        },
    )
}

/**
 * The display label for an occurrence's state.
 *
 * One mapping for the whole file — and now for both surfaces — so the timeline
 * row and the detail dialog can never describe the same state with two different
 * words, and so a new state added to the data layer fails to compile here rather
 * than rendering blank.
 */
private fun alarmStateLabel(s: AppStrings, state: AlarmSlotState): String = when (state) {
    AlarmSlotState.PENDING -> s.habitsAlarmStatePending
    AlarmSlotState.FIRED -> s.habitsAlarmStateFired
    AlarmSlotState.ACKNOWLEDGED -> s.habitsAlarmStateDone
    AlarmSlotState.DISMISSED -> s.habitsAlarmStateDismissed
    AlarmSlotState.SNOOZED -> s.habitsAlarmStateSnoozed
    AlarmSlotState.MISSED -> s.habitsAlarmStateMissed
}
