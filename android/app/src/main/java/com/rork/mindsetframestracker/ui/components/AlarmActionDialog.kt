package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rork.mindsetframestracker.data.AlarmDaySlot
import com.rork.mindsetframestracker.data.AlarmDialogCopy
import com.rork.mindsetframestracker.data.AlarmRingGate
import com.rork.mindsetframestracker.data.HabitAlarmSetup
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.data.HabitLogEntry
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.TimerKind
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.integrations.TrackerStatus

/**
 * The dialog that appears **on its own** when one of a habit's alarms goes off.
 *
 * ## Why this is a dialog and not a destination
 *
 * The user's report was that the connect-a-tracker step sat *in the alarm flow*
 * as a step to get through — a separate surface the ring handed them off to, so
 * the alarm became a sequence (ring → dismiss → find the tracker row → connect)
 * instead of a moment of help. The fix is the one fitness apps use for a walk
 * habit: one dialog, raised by the alarm itself, which asks what it needs and
 * offers the connection **in place**. Nothing is pushed and nothing is looked
 * for; the offer is simply there, in the same card as the habit's own tool.
 *
 * ## Personal, because a habit is not generic
 *
 * A single "Time for your habit" heading over a single form is what makes a
 * tracker feel like a form rather than a coach. Every string here comes from
 * [AlarmDialogCopy], which is a pure function of the habit: a walk gets its
 * stopwatch and "Time for your walk", water gets its stepper, a journal entry
 * gets its note field and never sees a Strava row, because no fitness app can
 * record a sentence. The dialog cannot invent copy, so the heading and the
 * input underneath it always describe the same tool.
 *
 * ## The connect option is conditional, and its absence is deliberate
 *
 * [connectOffer] is decided by the caller (see `AlarmRingGate.shouldOfferConnect`)
 * and is false for a habit no fitness app can supply, for a habit whose source is
 * already connected, and for a habit already offered this once. When it is false
 * the block is not merely disabled — it is absent, so a non-fitness habit's
 * alarm is just the alarm and its own tool. That is the request, and it also
 * stops a one-time offer from becoming a daily nag.
 *
 * ## Recording
 *
 * The mode decides the payload, exactly as in the habit's own sheet, because
 * these are the same controls: a CHECK confirms with nothing, a COUNT sends its
 * amount, a JOURNAL sends text, and the two timed modes hand off to
 * [TimerController] so the clock survives this dialog closing. Nothing here
 * writes state itself — [onRecord] and [onStartTimed] do, so the record's shape
 * is defined in one place.
 */
@Composable
fun AlarmActionDialog(
    /** The habit's name, as the user typed it. */
    habitName: String,
    /** The habit's catalog icon id, for its own artwork. */
    habitIconId: String?,
    /** The habit's own tool, which decides what is rendered below the heading. */
    trackingMode: HabitTrackingMode,
    /** The habit's own title/subtitle/connect wording. */
    copy: AlarmDialogCopy,
    targetSeconds: Int = 0,
    targetCount: Int = 0,
    unit: String = "",
    /**
     * Whether to offer connecting a fitness app on this ring.
     *
     * False for a habit with no fitness source, one already connected, or one
     * already offered — see `AlarmRingGate.shouldOfferConnect`. Kept as an
     * input rather than computed here so the decision is testable without
     * Compose, and so the caller that knows the habit can be the one to record
     * that the offer was made.
     */
    connectOffer: Boolean = false,
    /** Providers that can supply this habit; empty means no fitness source. */
    trackerProviders: List<TrackerProvider> = emptyList(),
    trackerStatuses: List<TrackerStatus> = emptyList(),
    recentLogs: List<HabitLogEntry> = emptyList(),
    alarmSlots: List<AlarmDaySlot> = emptyList(),
    alarmSetup: HabitAlarmSetup? = null,
    /** Opens the full connect flow. Null leaves the rows as honest status lines. */
    onOpenConnect: (() -> Unit)? = null,
    /**
     * Called when a fitness app has just become connected from this dialog, so
     * the caller can pull today's activity into the habit.
     *
     * This is what makes the offer worth accepting at the moment it is made: the
     * user connected Strava *because* the walk alarm just told them to walk, so
     * the walk they are about to take should land on the habit without them
     * remembering to sync. The dialog only reports the transition — the caller
     * owns the sync, because that is where the habit id and the request queue are.
     */
    onActivityCaptured: ((List<TrackerProvider>) -> Unit)? = null,
    onRecord: (
        title: String?,
        note: String?,
        durationSeconds: Int?,
        count: Int?,
    ) -> Unit,
    onStartTimed: (kind: TimerKind, targetSeconds: Int) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Minimize instead of abandoning — the "take it anytime" path.
     *
     * Null leaves the button out entirely rather than rendering one that does
     * nothing. The ring host passes a real handler, because a ring can arrive
     * while the user is mid-something-else and a walk they started should
     * survive them putting the phone away.
     */
    onMinimize: (() -> Unit)? = null,
) {
    val label = habitName.ifBlank { "Habit" }
    val iconRes = habitIconId?.let { HabitIconCatalog.byId(it)?.drawableRes }

    Dialog(
        onDismissRequest = onDismiss,
        // A ring is an alarm, not a casual pop-up: it may arrive over the lock
        // screen with the display just woken, and the user is likely half-awake.
        // Dismissing on an outside tap would let a mis-aimed thumb swallow the
        // reminder silently, so the dialog is dismissed deliberately — by
        // Minimize or Not now — and the system back gesture still works.
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Scrollable because the content is genuinely variable: a
                    // timer's preset chips plus a connect block plus the day's
                    // alarm timeline can exceed a small phone in landscape, and
                    // a fixed card would clip the habit's own tool — the one
                    // thing the user must be able to reach.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                // ── The habit's own artwork and its personal copy ──────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (iconRes != null) {
                            Image(
                                painter = painterResource(id = iconRes),
                                contentDescription = label,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(30.dp),
                            )
                        } else {
                            // The mode's own glyph stands in when the catalog has
                            // no artwork for this icon, so the dialog still shows
                            // what kind of input is coming.
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
                            text = copy.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = copy.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ── The habit's OWN tool, chosen by its mode ───────────────────
                // The same composables the habit's own sheet renders, so "log how
                // many glasses" cannot mean one thing here and another there.
                when (trackingMode) {
                    HabitTrackingMode.CHECK -> CheckInput(
                        label = label,
                        onConfirm = { onRecord(null, null, null, null) },
                    )

                    HabitTrackingMode.TIMER -> TimerInput(
                        targetSeconds = targetSeconds,
                        onStart = { seconds -> onStartTimed(TimerKind.TIMER, seconds) },
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

                // ── The connect offer, when — and only when — it applies ───────
                // Placed after the habit's own tool on purpose: the tool is how
                // the user records the habit now, the trackers are how the habit
                // will record itself from now on, and leading with the tracker
                // would make the alarm read as an upsell rather than a reminder.
                if (connectOffer && trackerProviders.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    RingConnectOffer(
                        copy = copy,
                        providers = trackerProviders,
                        statuses = trackerStatuses,
                        onOpenConnect = onOpenConnect,
                    )
                    // Reports the transition for the caller to act on. Keyed on
                    // the connected-set so it fires exactly once, when a provider
                    // actually goes from not-connected to connected — not on
                    // every recomposition, which would queue a sync per frame.
                    val connectedNow = AlarmRingGate.connectedSources(
                        sources = trackerProviders,
                        statuses = trackerStatuses,
                    )
                    LaunchedEffect(connectedNow) {
                        if (connectedNow.isNotEmpty()) onActivityCaptured?.invoke(connectedNow)
                    }
                }

                // ── What this habit's alarms are doing today ──────────────────
                // The same shared section the habit's own dialog and the alarm
                // editor render, so a habit is never described one way here and
                // another way there. It answers the question the ring interrupted:
                // which of today's alarms fired, and which did I answer.
                if (alarmSlots.isNotEmpty() || alarmSetup != null) {
                    Spacer(Modifier.height(18.dp))
                    HabitAlarmOverviewSection(
                        title = "This habit",
                        slots = alarmSlots,
                        plan = alarmSetup,
                        trackerProviders = trackerProviders,
                        onSelect = {},
                    )
                }

                if (recentLogs.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    RingRecentLogs(logs = recentLogs)
                }

                Spacer(Modifier.height(10.dp))
                // ── Leave, or come back later ─────────────────────────────────
                // Named actions rather than an unlabelled close, so neither reads
                // as the same thing: Minimize keeps the habit reachable under a
                // running chip, Not now writes nothing.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (onMinimize != null) {
                        TextButton(onClick = onMinimize, modifier = Modifier.weight(1f)) {
                            Text("Minimize")
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Not now")
                    }
                }
            }
        }
    }
}

/**
 * The habit's recent results, so "recorded inside the app" is visible rather
 * than merely claimed. Kept to two entries and one line each — this is
 * confirmation that the record landed, not a history view.
 *
 * Named distinctly from the habit sheet's own section because the two differ on
 * purpose: the sheet's version is a dividered block inside a longer page, while
 * this one is a compact tail on a dialog the user is trying to dismiss. Sharing
 * one name would invite sharing one implementation, and the dialog does not have
 * the room for the sheet's.
 */
@Composable
private fun RingRecentLogs(logs: List<HabitLogEntry>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
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
