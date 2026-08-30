package com.rork.mindsetframestracker.integrations

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.MindsetRepository
import java.time.Instant
import java.util.UUID
import java.time.temporal.ChronoUnit

sealed interface HealthConnectStatus {
    data object NotInstalled : HealthConnectStatus
    data object PermissionsNeeded : HealthConnectStatus
    data object Ready : HealthConnectStatus
}

object MindsetHealthConnectClient {

    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun checkStatus(context: Context): HealthConnectStatus {
        // The connect-client artifact's own AndroidManifest declares minSdk 26 (we
        // override that merge conflict in AndroidManifest.xml so the app can stay
        // at minSdk 24 for everything else) — so on API 24/25 devices we must
        // never actually call into the SDK, only report it as unavailable.
        if (Build.VERSION.SDK_INT < 26) return HealthConnectStatus.NotInstalled
        val availability = HealthConnectClient.getSdkStatus(context)
        return if (availability != HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectStatus.NotInstalled
        } else {
            HealthConnectStatus.PermissionsNeeded
        }
    }

    /**
     * Checks whether all required Health Connect permissions have been granted.
     * Returns true only when the user has explicitly authorised step + sleep
     * read access through the Health Connect permission dialog.
     */
    suspend fun hasAllPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            requiredPermissions.all { it in granted }
        }.getOrDefault(false)
    }

    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    /**
     * Set of icon IDs whose activity data can be tracked via Health Connect.
     * Shares the same physical-movement set as Polar.
     */
    val supportedActivityIconIds = setOf(
        "walking", "running", "basketball", "gym", "stretch",
        "strava_badminton", "strava_crossfit", "strava_dance",
        "strava_elliptical", "strava_football", "strava_hiit",
        "strava_hike", "strava_inline_skate", "strava_pilates",
        "strava_racquetball", "strava_ride", "strava_rock_climb",
        "strava_rowing", "strava_squash", "strava_stair_stepper",
        "strava_swim", "strava_tennis", "strava_trail_run",
        "strava_volleyball", "strava_weight_training", "strava_workout",
        "strava_yoga", "strava_mountain_bike_ride", "strava_gravel_ride",
        "strava_ebike_ride", "strava_emtb_ride", "strava_virtual_ride",
        "strava_virtual_run", "strava_virtual_rowing", "strava_pickleball",
        "strava_padel", "strava_cricket", "strava_skateboarding",
        "strava_ice_skate", "strava_snowboard", "strava_snowshoe",
        "strava_alpine_ski", "strava_backcountry_ski", "strava_nordic_ski",
        "strava_roller_ski", "table_tennis",
    )

    fun isActivitySupported(iconId: String): Boolean = iconId in supportedActivityIconIds

    /** Today's step total — free for everyone, no tier check. */
    suspend fun todaySteps(context: Context): Long? {
        if (Build.VERSION.SDK_INT < 26) return null
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val startOfDay = now.truncatedTo(ChronoUnit.DAYS)

        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            ),
        )
        return response.records.sumOf { it.count }
    }

    /**
     * Reads today's steps and books them onto [habitId] as an ActivityRecord.
     */
    suspend fun syncTodayToHabit(context: Context, habitId: String, activityType: String): Boolean {
        val steps = todaySteps(context)
        if (steps == null) return false
        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            habitId = habitId,
            source = "health_connect",
            activityType = activityType,
            timestamp = System.currentTimeMillis(),
            steps = steps,
        )
        MindsetRepository(context).saveActivityRecord(record)
        return true
    }

    suspend fun lastNightSleepMinutes(context: Context): Long? {
        if (Build.VERSION.SDK_INT < 26) return null
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val yesterday = now.minus(1, ChronoUnit.DAYS)

        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(yesterday, now),
            ),
        )
        return response.records.sumOf {
            ChronoUnit.MINUTES.between(it.startTime, it.endTime)
        }
    }
}
