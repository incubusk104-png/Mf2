package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.ui.SyncUiState
import kotlinx.coroutines.delay

/**
 * Global sync status surface pinned above the bottom navigation bar.
 *
 * - While any backup runs (manual or automatic) it shows a compact pill
 *   with a spinner, so background syncs are never invisible.
 * - When a backup fails it becomes a persistent alert with the error
 *   message and a Retry action — failures can no longer pass silently.
 * - Info/success messages appear briefly and clear themselves.
 *
 * The host hides it on the Settings tab (which has its own inline account
 * banners) and while the sign-in sheet is open (which shows its own status).
 */
@Composable
fun SyncStatusBanner(
    syncState: SyncUiState,
    visible: Boolean,
    reducedMotion: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showBusy = syncState.busy
    val showError = !showBusy && syncState.isError && syncState.message != null
    val showInfo = !showBusy && !syncState.isError && syncState.message != null
    val show = visible && (showBusy || showError || showInfo)

    // Keep the last message around so the exit animation doesn't flash empty.
    var lastMessage by remember { mutableStateOf("") }
    syncState.message?.let { lastMessage = it }

    // Info/success toasts clear themselves; errors stay until acted on.
    LaunchedEffect(syncState.message, syncState.isError, syncState.busy, visible) {
        if (visible && showInfo) {
            delay(3_500)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = show,
        enter = if (reducedMotion) EnterTransition.None
        else slideInVertically(
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            initialOffsetY = { it },
        ) + fadeIn(tween(200)),
        exit = if (reducedMotion) ExitTransition.None
        else slideOutVertically(
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                showError -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                when {
                    showBusy -> {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Backing up your data…",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                    }
                    showError -> {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = lastMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                        TextButton(onClick = onRetry) {
                            Text(
                                text = "Retry",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Dismiss",
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = lastMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
