package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.billing.Entitlements
import com.rork.mindsetframestracker.billing.Feature
import com.rork.mindsetframestracker.billing.SubscriptionTier
import com.rork.mindsetframestracker.integrations.HuaweiHealthKitClient
import com.rork.mindsetframestracker.integrations.StravaAuthClient

enum class ActivitySource { HUAWEI_HEALTH, STRAVA }

data class ActivitySourceOption(
    val source: ActivitySource,
    val label: String,
    val isLocked: Boolean,
)

/**
 * Called when a habit-icon whose category is HEALTH and whose id matches
 * a known activity type (walking, running, gym, basketball) is tapped.
 * Shows only sources that (a) support this activity AND (b) are relevant —
 * Strava always listed but shown locked if not entitled, so the user sees
 * the upsell rather than the option silently vanishing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySourcePickerSheet(
    habitIconId: String,
    currentTier: SubscriptionTier,
    onSourceChosen: (ActivitySource) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    val options = buildList {
        if (HuaweiHealthKitClient.isActivitySupported(habitIconId)) {
            add(ActivitySourceOption(ActivitySource.HUAWEI_HEALTH, "Huawei Health", isLocked = false))
        }
        // Strava supports the same core activity set — always listed if the
        // icon is a Strava-trackable type, locked state driven by entitlement.
        if (habitIconId in setOf("walking", "running", "walk2")) {
            val locked = !Entitlements.hasAccess(currentTier, Feature.STRAVA)
            add(ActivitySourceOption(ActivitySource.STRAVA, "Strava", isLocked = locked))
        }
    }

    if (options.isEmpty()) {
        onDismiss()
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Track this with",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))

            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !option.isLocked) {
                            onSourceChosen(option.source)
                            onDismiss()
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(option.label)
                    if (option.isLocked) {
                        Icon(Icons.Filled.Lock, contentDescription = "Locked — upgrade to Regular plan")
                    }
                }
            }
        }
    }
}
