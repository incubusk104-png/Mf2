package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The privacy data-consent copy for each connectable integration. Shown in
 * [IntegrationConsentDialog] BEFORE any OAuth/permission flow starts, so the
 * user always knows exactly what data is read and where it goes — required
 * by Huawei AppGallery privacy review, GDPR Art. 13, and Google Play's
 * User Data policy.
 */
enum class IntegrationConsent(
    val title: String,
    val dataRead: String,
    val whereItGoes: String,
    val revokeHint: String,
) {
    HEALTH_CONNECT(
        title = "Connect Google Health Connect",
        dataRead = "Steps and sleep sessions recorded by your phone and connected wearables.",
        whereItGoes = "Read on this device only, to auto-complete your activity habits. " +
            "Raw health data is never uploaded to our servers or shared with anyone.",
        revokeHint = "You can revoke access anytime in Settings > Activity sync > Disconnect, " +
            "or in the Health Connect app's permission manager.",
    ),
    POLAR(
        title = "Connect Polar",
        dataRead = "Daily activity summaries (step counts) from your Polar account via " +
            "Polar AccessLink, after you approve access on Polar's own consent page.",
        whereItGoes = "The access token is stored only on this device. Activity data is " +
            "used locally to complete your habits — it is never sold or shared.",
        revokeHint = "Revoke anytime in Settings > Activity sync > Disconnect, or at " +
            "flow.polar.com under your account's authorized apps.",
    ),
    STRAVA(
        title = "Connect Strava",
        dataRead = "Your recent activities (runs, rides, walks — type, duration, distance, " +
            "heart rate) after you approve access on Strava's own consent page.",
        whereItGoes = "Tokens are stored only on this device; the token exchange runs " +
            "through our secure server so no app secret ships in this app. Activity data " +
            "is used locally to complete your habits — never sold or shared.",
        revokeHint = "Revoke anytime in Settings > Activity sync > Disconnect, or at " +
            "strava.com > Settings > My Apps.",
    ),
    SCREEN_TIME(
        title = "Allow screen-time monitoring",
        dataRead = "How long you use the app you choose to limit (daily foreground time), " +
            "via Android's Usage Access — granted by you in system settings.",
        whereItGoes = "Measured entirely on this phone. Usage data never leaves the " +
            "device — only the habit's daily done/not-done state syncs with your backup.",
        revokeHint = "Revoke anytime in system Settings > Apps > Special access > " +
            "Usage access, or by removing the screen-time habit.",
    ),
}

/**
 * Privacy consent dialog shown before EVERY integration connect. The
 * connect flow (OAuth browser page / permission dialog / settings screen)
 * only launches after the user explicitly taps "Agree & connect".
 */
@Composable
fun IntegrationConsentDialog(
    consent: IntegrationConsent,
    onAgree: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(consent.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Before you connect, here's exactly what happens with your data:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                ConsentRow(
                    icon = Icons.Outlined.Visibility,
                    label = "What we read",
                    body = consent.dataRead,
                )
                ConsentRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    label = "Where it goes",
                    body = consent.whereItGoes,
                )
                ConsentRow(
                    icon = Icons.Outlined.Delete,
                    label = "Your control",
                    body = consent.revokeHint,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Full details are in our Privacy Policy (Settings > Privacy Policy).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Agree & connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

@Composable
private fun ConsentRow(icon: ImageVector, label: String, body: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
