package com.rork.mindsetframestracker.integrations

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rork.mindsetframestracker.data.ActivityRecord
import java.time.Instant
import java.time.temporal.ChronoUnit

sealed interface HealthConnectStatus {
    data object NotInstalled : HealthConnectStatus
    /** HC is installed but needs a provider update before it can be used. */
    data object UpdateRequired : HealthConnectStatus
    data object PermissionsNeeded : HealthConnectStatus
    data object Ready : HealthConnectStatus
}

object MindsetHealthConnectClient {

    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        // The real-activity set. These are what let a sports habit record what the
        // user actually did rather than just that they tapped it.
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    fun checkStatus(context: Context): HealthConnectStatus {
        // The connect-client artifact's own AndroidManifest declares minSdk 26 (we
        // override that merge conflict in AndroidManifest.xml so the app can stay
        // at minSdk 24 for everything else) — so on API 24/25 devices we must
        // never actually call into the SDK, only report it as unavailable.
        if (Build.VERSION.SDK_INT < 26) return HealthConnectStatus.NotInstalled

        // getSdkStatus() can throw on certain OEM firmware (e.g. when the
        // PackageManager queries fail) — catch everything so the caller
        // never crashes.
        val availability = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (e: Exception) {
            android.util.Log.w("MindsetHC", "getSdkStatus threw: ${e.message}")
            return HealthConnectStatus.NotInstalled
        }

        return when (availability) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.PermissionsNeeded
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectStatus.UpdateRequired
            else -> HealthConnectStatus.NotInstalled
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
    val supportedActivityIconIds: Set<String> =
        com.rork.mindsetframestracker.data.SPORT_ACTIVITY_ICON_IDS

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
     * Reads everything Health Connect has for **today** and books it onto
     * [habitId] as one [ActivityRecord]. Returns true when a record was written.
     *
     * This used to persist steps and nothing else — it called [todaySteps] and
     * built a record with `steps` as the only populated field, discarding the
     * distance, calories and heart-rate that the same permission grant already
     * allowed. Those fields arrived on the *other* Health Connect path
     * ([com.rork.mindsetframestracker.integrations.ActivityMonitor], used by the
     * timer/alarm flow) which reads a session window; a user who synced from
     * the habit sheet got a steps-only row, a user whose habit rang got the
     * full metrics, and the two disagreed about the same workout.
     *
     * Both paths now go through `ActivityMonitor.captureBlocking`, so there is
     * one reader, one honesty gate and one record shape — and this one is
     * scoped from midnight to now, which is what "today" means here.
     */
    suspend fun syncTodayToHabit(context: Context, habitId: String, activityType: String): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        if (!hasAllPermissions(context)) return false
        val now = Instant.now()
        // Minutes since local midnight — the window "today's activity" spans.
        // Coerced to at least one minute so a 00:00:30 read still looks back
        // a real interval rather than a zero-width one.
        val minutesToday = ChronoUnit.MINUTES
            .between(now.truncatedTo(ChronoUnit.DAYS), now)
            .coerceAtLeast(1L)
        return ActivityMonitor.captureBlocking(
            context = context,
            habitId = habitId,
            activityType = activityType,
            windowMinutes = minutesToday,
        ) != null
    }

    /**
     * Reads last night's sleep as a single total, in minutes.
     *
     * Sums the sessions that overlap the last 24 hours rather than assuming one
     * session: Health Connect writes a separately-sourced row per tracker (a
     * watch and a phone both record), and taking only the newest would report
     * half the night.
     */
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
        val total = response.records.sumOf {
            ChronoUnit.MINUTES.between(it.startTime, it.endTime)
        }
        // A zero would create a record claiming "0 minutes of sleep", which is
        // worse than no record — it reads as a real measurement of no sleep.
        return total.takeIf { it > 0 }
    }
}
