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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    val subtitle: String,
    val isLocked: Boolean,
)

// ── Strava-trackable icon IDs ──────────────────────────────────────────
// Every icon in HabitIconCatalog whose activity data can be pulled from
// Strava. This includes the core walking/running/gym icons AND every
// strava_* prefixed icon (alpine_ski through yoga).
//
// If an icon id starts with "strava_" it is ALWAYS Strava-trackable.
// We also explicitly include the app's own fitness icons that map 1:1
// to Strava activity types.

private val STRAVA_TRACKABLE_IDS: Set<String> = setOf(
    // App's own fitness icons that map to Strava activity types
    "walking",
    "running",
    "walk2",
    "basketball",
    "gym",
    "stretch",
    // All strava_* catalog icons
    "strava_alpine_ski",
    "strava_backcountry_ski",
    "strava_badminton",
    "strava_canoe",
    "strava_cricket",
    "strava_crossfit",
    "strava_dance",
    "strava_ebike_ride",
    "strava_elliptical",
    "strava_emtb_ride",
    "strava_football",
    "strava_golf",
    "strava_gravel_ride",
    "strava_handcycle",
    "strava_hiit",
    "strava_hike",
    "strava_ice_skate",
    "strava_inline_skate",
    "strava_kayak",
    "strava_kitesurf",
    "strava_mountain_bike_ride",
    "strava_nordic_ski",
    "strava_padel",
    "strava_pickleball",
    "strava_pilates",
    "strava_racquetball",
    "strava_ride",
    "strava_rock_climb",
    "strava_roller_ski",
    "strava_rowing",
    "strava_sailing",
    "strava_skateboarding",
    "strava_snowboard",
    "strava_snowshoe",
    "strava_squash",
    "strava_stair_stepper",
    "strava_stand_up_paddling",
    "strava_surf",
    "strava_swim",
    "table_tennis",
    "strava_tennis",
    "strava_trail_run",
    "strava_velomobile",
    "strava_virtual_ride",
    "strava_virtual_rowing",
    "strava_virtual_run",
    "strava_volleyball",
    "strava_weight_training",
    "strava_wheelchair",
    "strava_windsurf",
    "strava_workout",
    "strava_yoga",
)

/**
 * Returns true when the given icon id represents an activity that can
 * be tracked via an external source (Strava or Huawei Health).
 * Used by HabitsScreen to decide whether to show the source picker
 * when the user taps an activity-type icon.
 */
fun isActivityTrackableIcon(iconId: String): Boolean =
    iconId in STRAVA_TRACKABLE_IDS || HuaweiHealthKitClient.isActivitySupported(iconId)

/**
 * Returns the Strava activity type string to use when fetching activities
 * from the Strava API for this icon. Maps internal icon IDs to Strava's
 * expected activity type names (PascalCase per Strava API docs).
 */
fun stravaActivityTypeFor(iconId: String): String = when (iconId) {
    "walking", "walk2" -> "Walk"
    "running" -> "Run"
    "basketball" -> "Basketball"
    "gym" -> "Workout"
    "stretch" -> "Yoga"
    "strava_alpine_ski" -> "AlpineSki"
    "strava_backcountry_ski" -> "BackcountrySki"
    "strava_badminton" -> "Badminton"
    "strava_canoe" -> "Canoeing"
    "strava_cricket" -> "Cricket"
    "strava_crossfit" -> "Crossfit"
    "strava_dance" -> "Dance"
    "strava_ebike_ride" -> "EBikeRide"
    "strava_elliptical" -> "Elliptical"
    "strava_emtb_ride" -> "EMountainBikeRide"
    "strava_football" -> "Soccer"
    "strava_golf" -> "Golf"
    "strava_gravel_ride" -> "GravelRide"
    "strava_handcycle" -> "Handcycle"
    "strava_hiit" -> "HighIntensityIntervalTraining"
    "strava_hike" -> "Hike"
    "strava_ice_skate" -> "IceSkate"
    "strava_inline_skate" -> "InlineSkate"
    "strava_kayak" -> "Kayaking"
    "strava_kitesurf" -> "Kitesurf"
    "strava_mountain_bike_ride" -> "MountainBikeRide"
    "strava_nordic_ski" -> "NordicSki"
    "strava_padel" -> "Padel"
    "strava_pickleball" -> "Pickleball"
    "strava_pilates" -> "Pilates"
    "strava_racquetball" -> "Racquetball"
    "strava_ride" -> "Ride"
    "strava_rock_climb" -> "RockClimbing"
    "strava_roller_ski" -> "RollerSki"
    "strava_rowing" -> "Rowing"
    "strava_sailing" -> "Sail"
    "strava_skateboarding" -> "Skateboard"
    "strava_snowboard" -> "Snowboard"
    "strava_snowshoe" -> "Snowshoe"
    "strava_squash" -> "Squash"
    "strava_stair_stepper" -> "StairStepper"
    "strava_stand_up_paddling" -> "StandUpPaddling"
    "strava_surf" -> "Surfing"
    "strava_swim" -> "Swim"
    "table_tennis" -> "TableTennis"
    "strava_tennis" -> "Tennis"
    "strava_trail_run" -> "TrailRun"
    "strava_velomobile" -> "Velomobile"
    "strava_virtual_ride" -> "VirtualRide"
    "strava_virtual_rowing" -> "VirtualRow"
    "strava_virtual_run" -> "VirtualRun"
    "strava_volleyball" -> "Volleyball"
    "strava_weight_training" -> "WeightTraining"
    "strava_wheelchair" -> "Wheelchair"
    "strava_windsurf" -> "Windsurf"
    "strava_workout" -> "Workout"
    "strava_yoga" -> "Yoga"
    else -> "Workout" // safe fallback
}

/**
 * Called when a habit icon whose id matches a known activity type is tapped.
 * Shows available tracking sources:
 *
 * - **Huawei Health** — free for all users, shown when the icon's activity
 *   is supported by Health Kit (steps-based activities).
 * - **Strava** — shown for ALL activity icons (Strava tracks every sport),
 *   locked when the user isn't on the REGULAR subscription tier so they see
 *   the upsell rather than the option silently vanishing.
 *
 * When only one source is available (e.g. a strava_alpine_ski icon with
 * no Huawei Health support), it's still shown so the user understands the
 * tracking flow. When no sources are available, the sheet auto-dismisses.
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
        // Huawei Health — free for everyone, but only for step-trackable activities
        if (HuaweiHealthKitClient.isActivitySupported(habitIconId)) {
            add(
                ActivitySourceOption(
                    source = ActivitySource.HUAWEI_HEALTH,
                    label = "Huawei Health",
                    subtitle = "Sync steps & activity data (free)",
                    isLocked = false,
                )
            )
        }

        // Strava — supports ALL activity types; locked state driven by tier
        if (habitIconId in STRAVA_TRACKABLE_IDS) {
            val locked = !Entitlements.hasAccess(currentTier, Feature.STRAVA)
            add(
                ActivitySourceOption(
                    source = ActivitySource.STRAVA,
                    label = "Strava",
                    subtitle = if (locked) "Upgrade to Regular plan to connect"
                    else "Import activities from Strava",
                    isLocked = locked,
                )
            )
        }
    }

    if (options.isEmpty()) {
        onDismiss()
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Track this activity with",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Connect an external source to automatically log this habit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Source icon
                    Icon(
                        imageVector = when (option.source) {
                            ActivitySource.HUAWEI_HEALTH -> Icons.Filled.FitnessCenter
                            ActivitySource.STRAVA -> Icons.Filled.DirectionsRun
                        },
                        contentDescription = null,
                        tint = if (option.isLocked) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            option.label,
                            fontWeight = FontWeight.SemiBold,
                            color = if (option.isLocked) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            option.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (option.isLocked) 0.5f else 0.8f,
                            ),
                        )
                    }

                    if (option.isLocked) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Locked - upgrade to Regular plan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
