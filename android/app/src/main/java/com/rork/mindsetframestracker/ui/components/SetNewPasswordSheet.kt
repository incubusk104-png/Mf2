package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.ui.MIN_PASSWORD_LENGTH
import com.rork.mindsetframestracker.ui.SyncUiState

/**
 * Dedicated recovery sheet shown when arriving via a password reset link.
 * Asks ONLY for a new password and confirmation — zero old/current password required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetNewPasswordSheet(
    syncState: SyncUiState,
    onUpdatePassword: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
    val armed = newPassword.isNotBlank() && newPassword == confirmPassword && newPassword.length >= MIN_PASSWORD_LENGTH

    ModalBottomSheet(
        onDismissRequest = { /* Locked out until resolved */ },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 32.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = "Set a new password",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )

            Text(
                text = "Enter a secure new password for your account below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )

            PasswordField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = "New password (min $MIN_PASSWORD_LENGTH characters)",
                enabled = !syncState.busy,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            PasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = "Confirm new password",
                enabled = !syncState.busy,
                isError = mismatch,
                supportingText = { if (mismatch) Text("Passwords don't match") },
                modifier = Modifier.fillMaxWidth(),
            )

            syncState.message?.let { message ->
                AuthMessageBanner(
                    message = message,
                    isError = syncState.isError,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Button(
                onClick = { onUpdatePassword(newPassword) },
                enabled = armed && !syncState.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                if (syncState.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text("Update password")
                }
            }
        }
    }
}
