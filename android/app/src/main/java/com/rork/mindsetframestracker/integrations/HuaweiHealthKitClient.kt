package com.rork.mindsetframestracker.integrations

import android.content.Context
import android.util.Log
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.MindsetRepository
import java.util.UUID

/**
 * Huawei Health Kit — free for every user, no subscription check
 * (see Entitlements.HUAWEI_HEALTH_KIT = true, unconditional).
 *
 * BLOCKER: real step-reading requires (1) com.huawei.hms:health gradle
 * dependency confirmed present, (2) Health Kit API enabled in AppGallery
 * Connect > Manage APIs for this app, (3) DataController scope-authorized
 * sign-in completed first. readTodaySteps() stays a stub until all three
 * are confirmed — do not ship this returning null silently without a
 * visible "Health Kit not connected" state in the UI.
 */
object HuaweiHealthKitClient {

    private const val TAG = "HuaweiHealthKitClient"
    val supportedActivityIconIds = setOf("walking", "running", "walk2", "basketball", "gym")

    fun isActivitySupported(iconId: String): Boolean = iconId in supportedActivityIconIds

    suspend fun syncTodayToHabit(context: Context, habitId: String, activityType: String): Boolean {
        val steps = readTodaySteps(context)
        if (steps == null) {
            Log.w(TAG, "syncTodayToHabit: no data available, Health Kit not connected or auth pending")
            return false
        }
        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            habitId = habitId,
            source = "huawei_health",
            activityType = activityType,
            timestamp = System.currentTimeMillis(),
            steps = steps,
        )
        MindsetRepository(context).saveActivityRecord(record)
        return true
    }

    private suspend fun readTodaySteps(context: Context): Long? {
        // TODO: real DataReadRequest via HuaweiHiHealth.getDataController(context),
        // scoped to DT_CONTINUOUS_STEPS_DELTA for today's time range, once
        // gradle dependency + AGC Health Kit scope are both confirmed active.
        return null
    }
}
