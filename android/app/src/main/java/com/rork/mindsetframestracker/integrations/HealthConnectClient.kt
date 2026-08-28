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
import com.rork.mindsetframestracker.billing.Entitlements
import com.rork.mindsetframestracker.billing.Feature
import com.rork.mindsetframestracker.billing.SubscriptionTier
import java.time.Instant
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

    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun todaySteps(context: Context, tier: SubscriptionTier): Long? {
        if (Build.VERSION.SDK_INT < 26) return null
        if (!Entitlements.hasAccess(tier, Feature.HEALTH_CONNECT)) return null
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

    suspend fun lastNightSleepMinutes(context: Context, tier: SubscriptionTier): Long? {
        if (Build.VERSION.SDK_INT < 26) return null
        if (!Entitlements.hasAccess(tier, Feature.HEALTH_CONNECT)) return null
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
