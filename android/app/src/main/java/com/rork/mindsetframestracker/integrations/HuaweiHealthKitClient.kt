package com.rork.mindsetframestracker.integrations

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.huawei.hms.hihealth.HuaweiHiHealth
import com.huawei.hms.hihealth.data.DataType
import com.huawei.hms.hihealth.data.Field
import com.huawei.hms.hihealth.data.SamplePoint
import com.huawei.hms.hihealth.data.Scopes
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.MindsetRepository
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Huawei Health Kit — free for every user, no subscription check
 * (see Entitlements.HUAWEI_HEALTH_KIT = true, unconditional).
 *
 * Real implementation (v1.1.0):
 *  - [requestAuthorization] launches the Health Kit consent screen for the
 *    step-read scope; the result lands in MainActivity.onActivityResult with
 *    [HEALTH_AUTH_REQUEST_CODE] — call [parseAuthResult] there.
 *  - [readTodaySteps] runs a DataController.readTodaySummation over
 *    DT_CONTINUOUS_STEPS_DELTA and sums the step deltas.
 *
 * Prerequisites (already in place for this app):
 *  1. com.huawei.hms:health gradle dependency (app/build.gradle.kts).
 *  2. Health Kit API enabled in AppGallery Connect > Manage APIs.
 *  3. The user granted the step-read scope via [requestAuthorization].
 * When any of these is missing every call fails soft (returns null/false)
 * and the Settings row keeps showing "Connect" instead of crashing.
 */
object HuaweiHealthKitClient {

    private const val TAG = "HuaweiHealthKitClient"

    /** Request code for the Health Kit authorization intent — handled in MainActivity.onActivityResult. */
    const val HEALTH_AUTH_REQUEST_CODE = 8891

    val supportedActivityIconIds = setOf("walking", "running", "walk2", "basketball", "gym")

    fun isActivitySupported(iconId: String): Boolean = iconId in supportedActivityIconIds

    /**
     * Opens Huawei's Health Kit authorization screen scoped to step reading.
     * Returns false when the intent could not be created (HMS missing,
     * Health Kit API not enabled) so the caller can show a friendly message.
     */
    fun requestAuthorization(activity: Activity): Boolean = runCatching {
        val settingController = HuaweiHiHealth.getSettingController(activity)
        val intent = settingController.requestAuthorizationIntent(
            arrayOf(Scopes.HEALTHKIT_STEP_READ),
            /* enableHealthAuth = */ true,
        )
        activity.startActivityForResult(intent, HEALTH_AUTH_REQUEST_CODE)
        true
    }.onFailure {
        Log.w(TAG, "requestAuthorization failed: ${it.message}")
    }.getOrDefault(false)

    /**
     * Parses the authorization result delivered to MainActivity.onActivityResult.
     * Returns true when the user granted the step-read scope.
     */
    fun parseAuthResult(context: Context, data: Intent?): Boolean = runCatching {
        val settingController = HuaweiHiHealth.getSettingController(context)
        val result = settingController.parseHealthKitAuthResultFromIntent(data)
        result != null && result.isSuccess
    }.onFailure {
        Log.w(TAG, "parseAuthResult failed: ${it.message}")
    }.getOrDefault(false)

    /**
     * Reads today's steps and books them onto [habitId] as an ActivityRecord.
     * Returns true when data was read and saved.
     */
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

    /**
     * Today's step total via Health Kit, or null when unavailable (no HMS,
     * no authorization, or the read failed). Never throws.
     */
    suspend fun readTodaySteps(context: Context): Long? = suspendCancellableCoroutine { cont ->
        runCatching {
            val dataController = HuaweiHiHealth.getDataController(context)
            dataController.readTodaySummation(DataType.DT_CONTINUOUS_STEPS_DELTA)
                .addOnSuccessListener { sampleSet ->
                    val total = runCatching {
                        var sum = 0L
                        for (point: SamplePoint in sampleSet.samplePoints) {
                            sum += point.getFieldValue(Field.FIELD_STEPS_DELTA).asIntValue().toLong()
                        }
                        sum
                    }.getOrNull()
                    if (cont.isActive) cont.resume(total)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "readTodaySummation failed: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }.onFailure {
            Log.w(TAG, "readTodaySteps unavailable: ${it.message}")
            if (cont.isActive) cont.resume(null)
        }
    }
}
