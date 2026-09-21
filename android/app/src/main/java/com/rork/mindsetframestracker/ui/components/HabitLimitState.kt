package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.ui.theme.Mf2Palette

/**
 * The caption for the free-tier habit counter — **"x of 5 active habits"**.
 *
 * ## Why this is a pure function and not inline UI copy
 *
 * The "x of 5" indicator appears in more than one place in the plan: the Habits
 * header and the limit-reached state. If each spelled the sentence out itself
 * they would eventually disagree — one saying "3 of 5", the other "3 / 5" — and
 * the limit is exactly the kind of number users compare across screens. One
 * function, one phrasing.
 *
 * ## Which habits the count represents
 *
 * The Phase 1 rule counts **active** habits: archived ones are excluded and
 * paused ones are counted (a paused habit still occupies a slot, which is what
 * makes "pause" a scheduling choice rather than a way to get a sixth habit for
 * free). Archiving is the model layer's `isArchived` flag, added as part of the
 * same phase — this function takes the already-computed number rather than
 * re-deriving it, so the UI can never disagree with the rule
 * `Entitlements.canAddHabit` enforces.
 *
 * When the user is entitled, no fraction is shown at all: "12 of 5 active
 * habits" would be nonsense, and a counter that reads as a warning would make
 * Premium feel like a limitation.
 */
fun habitLimitCaption(
    activeCount: Int,
    limit: Int = MAX_FREE_HABITS,
    unlimited: Boolean = false,
): String = if (unlimited) {
    if (activeCount == 1) "1 active habit" else "$activeCount active habits"
} else {
    "$activeCount of $limit active habits"
}

/** True once the free tier is full — the state the paywall answers. */
fun habitLimitReached(activeCount: Int, limit: Int = MAX_FREE_HABITS): Boolean =
    activeCount >= limit

/**
 * The compact **"x of 5"** pill for the Habits header.
 *
 * It is a passive readout, not a warning: it reports the remaining free slots
 * (the same number the plan asks for) and only turns into an invitation to
 * upgrade once the tier is actually full. Shown in the accent container colours
 * so it reads as information on every one of the six habit-card hues, and sized
 * to be tappable the moment it becomes an upgrade affordance.
 *
 * When [unlocked] is true the fraction is dropped entirely (see
 * [habitLimitCaption]) — a paying user is never shown a cap.
 */
@Composable
fun ActiveHabitIndicator(
    activeCount: Int,
    limit: Int = MAX_FREE_HABITS,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    /** Non-null makes the full-tier pill open the upgrade sheet. */
    onUpgrade: (() -> Unit)? = null,
) {
    val isDark = isSystemInDarkTheme()
    val reached = !unlocked && habitLimitReached(activeCount, limit)
    val clickable = reached && onUpgrade != null

    val container = when {
        isDark && reached -> Mf2Palette.Attention.copy(alpha = 0.16f)
        isDark -> Mf2Palette.AccentContainerDark
        reached -> Mf2Palette.Warning.copy(alpha = 0.18f)
        else -> Mf2Palette.AccentContainerLight
    }
    val content = when {
        isDark && reached -> Mf2Palette.Attention
        isDark -> Mf2Palette.OnAccentContainerDark
        reached -> Mf2Palette.OnLight
        else -> Mf2Palette.AccentOnLight
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .then(if (clickable) Modifier.clickable(onClick = onUpgrade!!) else Modifier)
            .defaultMinSize(minHeight = 28.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(
            imageVector = if (reached) Icons.Outlined.Block else Icons.Filled.WorkspacePremium,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = habitLimitCaption(activeCount = activeCount, limit = limit, unlimited = unlocked),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
        if (clickable) {
            Text(
                text = "Upgrade",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
        }
    }
}

/**
 * The **limit-reached** state: a full dialog raised when a user tries to create
 * a sixth active habit on the free plan.
 *
 * ## What this state must and must not do
 *
 * It **blocks the creation** and offers the upgrade — that is Change A. What it
 * must not do is scare the user about the habits they already have, because the
 * same change says existing habits are never deleted or locked when the cap is
 * reached or when a subscription lapses. That promise is therefore stated in the
 * dialog itself, in the user's own terms, rather than being left implicit: a
 * paywall that looks like a threat is what makes people delete their own data
 * before unsubscribing.
 *
 * It also names the thing being created when the caller knows it ([habitName]),
 * so the message is about *this* habit instead of a generic refusal.
 *
 * ## Accessibility
 *
 * Not dismissible by tapping outside: like the alarm dialog, this is a decision
 * with two named answers, and a mis-aimed tap should not read as a choice made.
 * The system back gesture still works, and "Not now" is always present.
 */
@Composable
fun LimitReachedPaywallState(
    activeCount: Int,
    limit: Int = MAX_FREE_HABITS,
    /** The habit the user was trying to create, when it is already named. */
    habitName: String? = null,
    /** Opens the existing premium sheet. */
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val headingColor = if (isDark) Mf2Palette.OnDarkHeading else Mf2Palette.OnLight
    val mutedColor = if (isDark) Mf2Palette.OnDarkMuted else Mf2Palette.OnLightMuted
    val accent = if (isDark) Mf2Palette.AccentBright else Mf2Palette.AccentOnLight
    val onAccent = if (isDark) Mf2Palette.OnAccent else Mf2Palette.LightSurface
    val surface = if (isDark) Mf2Palette.DarkSurface else Mf2Palette.LightSurface

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isDark) Mf2Palette.Attention.copy(alpha = 0.16f)
                            else Mf2Palette.Warning.copy(alpha = 0.18f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null,
                        tint = if (isDark) Mf2Palette.Attention else Mf2Palette.Warning,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "You've reached your $limit-habit limit",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = headingColor,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = if (habitName.isNullOrBlank()) {
                        "The free plan includes $limit active habits. " +
                            "Premium removes the limit so you can track as many as you like."
                    } else {
                        "\"$habitName\" would be habit number ${activeCount + 1} on the free plan, " +
                            "which includes $limit active habits. Premium removes the limit so you " +
                            "can track as many as you like."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedColor,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(14.dp))

                // The same "x of 5" readout the header shows, so the number the
                // user just saw on the Habits screen is the number that explains
                // the refusal.
                ActiveHabitIndicator(
                    activeCount = activeCount,
                    limit = limit,
                    unlocked = false,
                )

                Spacer(Modifier.height(16.dp))

                // The no-data-loss promise. Stated plainly and in the same
                // dialog as the ask, because this is the moment the user would
                // otherwise wonder whether hitting the cap endangers their data.
                Text(
                    text = "Your existing habits stay exactly as they are — " +
                        "nothing is deleted, paused, or locked when you reach the limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onUpgrade,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = onAccent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.WorkspacePremium,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Go Premium",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(4.dp))

                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = mutedColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Not now", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
