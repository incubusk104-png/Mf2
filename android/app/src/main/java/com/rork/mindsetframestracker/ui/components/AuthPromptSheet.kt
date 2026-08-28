package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.ui.MIN_PASSWORD_LENGTH
import com.rork.mindsetframestracker.ui.SyncUiState
import com.rork.mindsetframestracker.ui.screens.PrivacyPolicyDialog
import kotlinx.coroutines.delay

/**
 * Automatic "save your progress" popup — slides up on the Today screen ONCE
 * ever, on the first arrival after onboarding (persisted flag). It never
 * re-appears on later launches; an explicit sign-out is the only thing
 * that re-arms it. Offers Huawei ID sign-in and classic email sign-up/sign-in
 * so habits, check-ins, and moods are backed up to the cloud. Signing in also
 * restores a returning user's cloud data. Fully skippable — the app keeps
 * working locally.
 *
 * **Privacy consent gate**: Before any login trigger fires, the user must
 * read and accept the Privacy Policy. This is required by Huawei AppGallery
 * guidelines. The consent checkbox is shown at the top of the sheet and
 * gates both the Huawei ID button and the email sign-in form.
 *
 * This sheet is the single surface where account creation/sign-in/restore
 * happens; onboarding carries no account UI. Settings offers a "Back up &
 * restore" entry that re-opens this sheet for anyone who dismissed the
 * one-time popup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthPromptSheet(
    syncState: SyncUiState,
    privacyConsentAccepted: Boolean,
    onAcceptPrivacyConsent: () -> Unit,
    onHuaweiSignIn: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (email: String, password: String) -> Unit,
    onForgotPassword: (email: String) -> Unit,
    onConsumeSuggestSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val signedIn = syncState.email != null

    LaunchedEffect(signedIn) {
        if (signedIn) {
            delay(2200)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!syncState.busy) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            if (signedIn) {
                SignedInContent(email = syncState.email.orEmpty())
            } else {
                SignedOutContent(
                    syncState = syncState,
                    privacyConsentAccepted = privacyConsentAccepted,
                    onAcceptPrivacyConsent = onAcceptPrivacyConsent,
                    onHuaweiSignIn = onHuaweiSignIn,
                    onSignIn = onSignIn,
                    onSignUp = onSignUp,
                    onForgotPassword = onForgotPassword,
                    onConsumeSuggestSignIn = onConsumeSuggestSignIn,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/** Brief success state shown right after the account connects. */
@Composable
private fun SignedInContent(email: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
    }
    Text(
        text = "You're all set!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        text = "Backed up as $email. Your progress now follows you to any device.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = "Manage backup or sign out anytime in Settings.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
    )
}

@Composable
private fun SignedOutContent(
    syncState: SyncUiState,
    privacyConsentAccepted: Boolean,
    onAcceptPrivacyConsent: () -> Unit,
    onHuaweiSignIn: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (email: String, password: String) -> Unit,
    onForgotPassword: (email: String) -> Unit,
    onConsumeSuggestSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    var emailMode by rememberSaveable { mutableStateOf(false) }
    var isSignUp by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPolicy by rememberSaveable { mutableStateOf(false) }
    var showForgotPassword by rememberSaveable { mutableStateOf(false) }

    if (showPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPolicy = false })
    }

    if (showForgotPassword) {
        ForgotPasswordDialog(
            initialEmail = email,
            busy = syncState.busy,
            onSend = { resetEmail ->
                onForgotPassword(resetEmail)
                showForgotPassword = false
            },
            onDismiss = { showForgotPassword = false },
        )
    }

    // Backend flags "email already registered" on sign-up — auto-drop the
    // user into Sign In mode so they aren't stuck resubmitting the same form.
    LaunchedEffect(syncState.suggestSignIn) {
        if (syncState.suggestSignIn) {
            emailMode = true
            isSignUp = false
            onConsumeSuggestSignIn()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(32.dp),
        )
    }
    Text(
        text = "Don't lose your progress",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        text = "Your habits, check-ins, and moods live only on this phone right now. " +
                "Connect an account and they're backed up automatically — and if " +
                "you've used Mindset Frames before, signing in brings your progress " +
                "right back.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )

    // ── Privacy Policy consent gate ──────────────────────────────
    // Required by Huawei AppGallery: the user must read and agree to the
    // Privacy Policy before any login trigger is initialized. The checkbox
    // gates both the Huawei ID button and the email form below.
    Spacer(modifier = Modifier.height(16.dp))
    PrivacyConsentRow(
        accepted = privacyConsentAccepted,
        onToggle = onAcceptPrivacyConsent,
        onReadPolicy = { showPolicy = true },
    )

    Spacer(modifier = Modifier.height(20.dp))

    // Huawei ID is the primary sign-in method. Disabled until the user
    // accepts the privacy policy.
    HuaweiSignInButton(
        onClick = onHuaweiSignIn,
        enabled = privacyConsentAccepted && !syncState.busy,
        busy = syncState.busy && !emailMode,
        modifier = Modifier.fillMaxWidth(),
    )

    OrDivider(modifier = Modifier.padding(vertical = 14.dp))

    if (!emailMode) {
        OutlinedButton(
            onClick = { emailMode = true },
            enabled = privacyConsentAccepted && !syncState.busy,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Continue with email",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    } else {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !syncState.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )

        // Eye-icon show/hide toggle lives inside PasswordField.
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = if (isSignUp) "Password (min $MIN_PASSWORD_LENGTH characters)" else "Password",
            enabled = !syncState.busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        if (!isSignUp) {
            TextButton(
                onClick = { showForgotPassword = true },
                enabled = !syncState.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            ) {
                Text(
                    text = "Forgot password?",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(
            onClick = {
                if (isSignUp) onSignUp(email, password) else onSignIn(email, password)
            },
            enabled = !syncState.busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .defaultMinSize(minHeight = 52.dp),
        ) {
            if (syncState.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (isSignUp) "Create free account" else "Sign in")
            }
        }

        TextButton(
            onClick = { isSignUp = !isSignUp },
            enabled = !syncState.busy,
        ) {
            Text(
                text = if (isSignUp) "Already have an account? Sign in" else "New here? Create an account",
            )
        }
    }

    syncState.message?.let { message ->
        AuthMessageBanner(
            message = message,
            isError = syncState.isError,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    TextButton(
        onClick = onDismiss,
        enabled = !syncState.busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .defaultMinSize(minHeight = 48.dp),
    ) {
        Text(
            text = "Not now — keep my data on this device only",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        text = "Free · No spam · Sign out anytime",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

/**
 * "Forgot Password?" dialog — collects an email and fires the reset-email
 * request. Always shows the same neutral confirmation regardless of whether
 * the address is registered, so the flow can't be used to probe which
 * emails have accounts.
 */
@Composable
private fun ForgotPasswordDialog(
    initialEmail: String,
    busy: Boolean,
    onSend: (email: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Reset your password") },
        text = {
            Column {
                Text(
                    text = "Enter your account email — we'll send a link to reset your password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(email) },
                enabled = !busy && email.isNotBlank(),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Send reset link")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Privacy Policy consent row — checkbox plus an in-sheet "Read" action
 * that opens the full policy, so users can actually read what they are
 * agreeing to without leaving the sheet. The checkbox must be ticked
 * before any login button activates, per Huawei AppGallery's
 * consent-before-login-trigger rule.
 */
@Composable
private fun PrivacyConsentRow(
    accepted: Boolean,
    onToggle: () -> Unit,
    onReadPolicy: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Checkbox(
            checked = accepted,
            onCheckedChange = { onToggle() },
        )
        Text(
            text = "I have read and agree to the Privacy Policy",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onReadPolicy,
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text("Read")
        }
    }
}

@Composable
private fun OrDivider(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "or",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
