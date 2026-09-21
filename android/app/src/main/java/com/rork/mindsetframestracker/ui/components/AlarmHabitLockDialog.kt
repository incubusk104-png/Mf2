package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.ui.theme.Mf2Palette

/**
 * What the user can answer a ringing habit alarm with.
 *
 * These are the **three** answers the Phase 1 plan specifies — Done, Snooze and
 * Skip — kept as a closed enum rather than three separate callbacks so the host
 * has exactly one place to decide what an answer *means*. Two of the three are
 * deliberately not "record something": Snooze defers the occurrence and Skip
 * ends it without a log, which is why they must not collapse into one
 * "dismiss" path. A habit that the user genuinely did not do has to be
 * expressible, or their streak and history become fiction.
 *
 * [description] doubles as the accessible label: each action is reachable by a
 * screen reader and by the lock screen's own accessibility layer, where the
 * icon alone would read as nothing.
 */
enum class AlarmHabitLockAction(
    /** Short button label, matching the alarm flow's existing copy style. */
    val label: String,
    /** Spoken/screen-reader description of what choosing this does. */
    val description: String,
) {
    /** The habit is done: record it and complete today's check-in. */
    DONE("Done", "Mark this habit done for today"),

    /** Defer this occurrence; the ring comes back shortly. */
    SNOOZE("Snooze", "Snooze this alarm and ring again in a few minutes"),

    /** End the occurrence with no record — the honest "I did not do it". */
    SKIP("Skip", "Skip this alarm without recording anything"),
}

/**
 * Minimum height for every tappable row here. This screen is answered by a
 * half-awake thumb on a locked phone, so the WCAG 2.5.5 target size (44dp) is
 * treated as a floor rather than a target and lifted to 56dp.
 */
private val LockActionHeight = 56.dp

/**
 * Shared corner radius for the dialog's actions and cards. Large enough to read
 * as the same family as the app's extra-large habit cards, which is what makes
 * this look like *this app's* alarm rather than a system dialog.
 */
private val LockActionShape = RoundedCornerShape(18.dp)

/**
 * The per-habit alarm dialog that is raised **over the lock screen** by a
 * full-screen intent, offering Done / Snooze / Skip.
 *
 * ## Why this is a full-screen surface and not a notification action
 *
 * A notification can be swiped away, and its action buttons are small targets
 * on a shade the user has to unlock the phone to read. The plan's Change B asks
 * for a habit dialog that behaves like an alarm clock: it arrives on its own,
 * on top of the keyguard, with the three answers already named and big enough
 * to hit without looking. That is precisely what the full-screen intent in
 * [com.rork.mindsetframestracker.notifications.HabitCheckInNotifier] is for, and
 * it is why the buttons here are full-width rows rather than a compact row of
 * chips.
 *
 * ## Why the copy is a parameter and not computed here
 *
 * The heading is the habit's own name and the subtitle is whatever the caller
 * resolved (the habit's motivating line, or the neutral fallback). This
 * composable never invents a sentence about the habit, so it cannot describe a
 * different habit from the one that is ringing — the same discipline
 * [AlarmActionDialog] follows.
 *
 * ## Palette discipline
 *
 * Every colour comes from [Mf2Palette]. Because this screen may be the first
 * thing the user sees after waking, its contrast matters more than anywhere
 * else: the heading is the palette's ivory (12.2:1+) and the actions are the
 * accent pair (bright green fill with the palette's near-black on top) or the
 * hairline-outlined variant, all of which clear WCAG AA and, for the heading,
 * AAA.
 */
@Composable
fun AlarmHabitLockDialog(
    /** The habit's name, as the user typed it. */
    habitName: String,
    /** The line under the name — the habit's own motivating message. */
    subtitle: String,
    /** The habit's catalog icon id, for its own artwork. */
    habitIconId: String? = null,
    /** "07:00" for the occurrence that is ringing, when it is known. */
    alarmClockLabel: String? = null,
    /**
     * True when today's check-in for this habit is already complete.
     *
     * The host normally closes without showing anything in that case (the plan
     * asks for the dialog to be skipped when the habit is already done), so this
     * exists for the honest in-progress state: an alarm that fired twice for one
     * habit should say so rather than pretending the work is still outstanding.
     */
    alreadyDone: Boolean = false,
    /** How long Snooze defers the ring. Matches `HabitSnoozeReceiver`. */
    snoozeMinutes: Int = 5,
    /** Called with the answer the user chose. */
    onAction: (AlarmHabitLockAction) -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val label = habitName.ifBlank { "Habit" }
    val iconRes = habitIconId?.let { HabitIconCatalog.byId(it)?.drawableRes }

    val background = if (isDark) Mf2Palette.DarkBackground else Mf2Palette.LightBackground
    val headingColor = if (isDark) Mf2Palette.OnDarkHeading else Mf2Palette.OnLight
    val mutedColor = if (isDark) Mf2Palette.OnDarkMuted else Mf2Palette.OnLightMuted
    val accent = if (isDark) Mf2Palette.AccentBright else Mf2Palette.AccentOnLight
    val onAccent = if (isDark) Mf2Palette.OnAccent else Mf2Palette.LightSurface
    val iconWell = if (isDark) Mf2Palette.AccentContainerDark else Mf2Palette.AccentContainerLight
    val iconTint = if (isDark) Mf2Palette.HabitIconOnDark else Mf2Palette.HabitIconOnLight
    val outline = if (isDark) Mf2Palette.DarkOutline else Mf2Palette.LightOutline

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scrollable because this screen can be reached in landscape and
                // with the display's font scale raised — the answers must never
                // be the part that gets clipped off the bottom, since they are
                // the only way to silence the alarm.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // ── The habit's own artwork ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(iconWell),
                contentAlignment = Alignment.Center,
            ) {
                if (iconRes != null) {
                    Image(
                        painter = painterResource(id = iconRes),
                        // The name already carries the meaning for a screen
                        // reader, so the artwork is decorative here.
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Alarm,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Who is ringing, and why ─────────────────────────────────────
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = headingColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = mutedColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Which occurrence this is ────────────────────────────────────
            // A habit can ring at 07:00, 12:00 and 18:00, so the time is what
            // tells the user *which* alarm they are answering.
            if (!alarmClockLabel.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(iconWell)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Alarm,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = alarmClockLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isDark) Mf2Palette.OnAccentContainerDark else Mf2Palette.AccentOnLight,
                    )
                }
            }

            if (alreadyDone) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Already done today — this alarm is a reminder only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── Done — the filled, primary answer ────────────────────────────
            // First and filled on purpose: recording the habit is the outcome
            // the alarm exists to produce, so the most likely answer is also the
            // easiest one to hit without reading.
            Button(
                onClick = { onAction(AlarmHabitLockAction.DONE) },
                shape = LockActionShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = onAccent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = LockActionHeight),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = AlarmHabitLockAction.DONE.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Snooze — defer without judging ───────────────────────────────
            OutlinedButton(
                onClick = { onAction(AlarmHabitLockAction.SNOOZE) },
                shape = LockActionShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = headingColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = LockActionHeight),
            ) {
                Icon(
                    imageVector = Icons.Filled.Alarm,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${AlarmHabitLockAction.SNOOZE.label} $snoozeMinutes min",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(4.dp))

            // ── Skip — deliberately quiet, never hidden ──────────────────────
            // A quieter treatment than Snooze, because skipping is the honest
            // exception rather than a normal answer — but it IS present: if the
            // only ways out were "done" and "later", the user could not tell the
            // app they did not do it, and the history would fill with lies.
            TextButton(
                onClick = { onAction(AlarmHabitLockAction.SKIP) },
                colors = ButtonDefaults.textButtonColors(contentColor = mutedColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = LockActionHeight),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = AlarmHabitLockAction.SKIP.label,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(8.dp))

            // The hairline divider and this line exist to answer the question a
            // lock-screen alarm provokes — "what did I just do?" — at the moment
            // it is asked. It also states the Phase 1 free-tier rule in the one
            // place a user is guaranteed to look: nothing already added is ever
            // locked, removed, or lost.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(if (isDark) Mf2Palette.DarkOutlineVariant else Mf2Palette.LightOutlineVariant),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (alreadyDone) {
                    "Nothing left to record for this habit today."
                } else {
                    "Free plan: $MAX_FREE_HABITS active habits. " +
                        "Snoozing or skipping never removes a habit."
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tip: turn the phone screen off to silence the ring for now.",
                style = MaterialTheme.typography.labelSmall,
                color = outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
