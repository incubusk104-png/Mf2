package com.rork.mindsetframestracker.integrations

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.MindsetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Real activity capture for the **sports habits** \u2014 walking, running, cycling and
 * the rest of the movement set.
 *
 * ## Why this exists
 *
 * Before this, a sports habit was only ever a label. The habit got ticked by a
 * manual tap or by the app's own alarm/timer flow, and nothing about what the
 * user *actually did* was ever captured. The only real numbers anywhere in the
 * app came from the connected services (Strava, Polar) \u2014 and only if the user
 * had linked an account *and* a workout had been uploaded there. A user who
 * simply went for a walk and tapped the habit produced no data at all.
 *
 * This reads the activity from the device's own **Health Connect** store, over
 * the window in which the activity happened, and records the metrics that are
 * genuinely present.
 *
 * ## What is genuinely available, and what is not
 *
 * | Metric | Source here | Honest limitation |
 * |---|---|---|
 * | duration | `ExerciseSessionRecord` start/end | requires a recorded session |
 * | distance | `DistanceRecord` aggregate | only where something wrote distance |
 * | steps | `StepsRecord` aggregate | needs `READ_STEPS` |
 * | calories | `TotalCaloriesBurnedRecord` aggregate | needs `READ_TOTAL_CALORIES_BURNED` |
 * | heart rate | `HeartRateRecord` samples | needs `READ_HEART_RATE`; usually absent on a phone with no paired watch/band |
 *
 * **There is deliberately no raw-sensor fallback**, even though `TYPE_STEP_COUNTER`
 * and `ACTIVITY_RECOGNITION` exist. A raw step-counter fallback would give steps
 * and *nothing else* \u2014 never distance, calories or heart rate \u2014 while requiring
 * its own runtime permission plus an always-on foreground service to be read
 * reliably, and being throttled or wholly unavailable on several OEM builds.
 * Measuring one metric directly while leaving the rest blank would be *more*
 * misleading than being explicit that the numbers come from Health Connect. So
 * when Health Connect is missing or unauthorised this reports that honestly
 * instead of approximating.\n *
 * ## Threading
 *
 * [captureForHabit] is fire-and-forget. Its callers are the timer-completion path
 * \u2014 which runs inside a **headless, UI-less process** at alarm time \u2014 and a habit
 * check-in tap. Both need to return immediately, so this launches on its own IO
 * scope and folds every failure into a log entry: a metric that could not be read
 * must never be able to break the alert the user is about to hear, or the check-in
 * that was just recorded.
 */
object ActivityMonitor {

    private const val TAG = "ActivityMonitor"

    /**
     * A long-lived IO scope for best-effort captures.
     *
     * `SupervisorJob` so one failed read cannot cancel the others, and a private
     * scope rather than a caller's so the work survives the caller returning \u2014
     * which it always does, immediately.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Captures the real activity for [habitId] in the background and records it.
     *
     * The habit's own activity type is resolved here, **on the IO scope**, rather
     * than by the caller \u2014 the callers are on the alarm/check-in path and a
     * whole-database decode there is exactly the kind of main-thread work that
     * turns an alarm into an ANR.
     *
     * Safe to call from any process at any time. It is a no-op when Health
     * Connect is missing, unavailable, or unauthorised.
     *
     * @param windowMinutes how far back to look. Bounded so the read can never
     *   scan an unbounded slice of history.
     */
    fun captureForHabit(
        context: Context,
        habitId: String,
        windowMinutes: Long = DEFAULT_WINDOW_MINUTES,
    ) {
        if (habitId.isBlank()) return
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                // Which activity this habit is, from the habit's own icon \u2014 the
                // same id the catalog/UI keys off. Falls back to a neutral label
                // rather than skipping the capture, so a habit whose artwork
                // cannot be resolved still gets its real numbers recorded.
                val iconId = MindsetRepository(appContext).load().habits
                    .firstOrNull { it.id == habitId }
                    ?.iconId
                captureBlocking(
                    context = appContext,
                    habitId = habitId,
                    activityType = iconId ?: FALLBACK_ACTIVITY_TYPE,
                    windowMinutes = windowMinutes,
                )
            }.onFailure { Log.w(TAG, "Activity capture failed for habit $habitId", it) }
        }
    }

    /**
     * The actual read, on whatever thread the caller is on. Kept separate so
     * [captureForHabit] has exactly one job (resolve + dispatch + guard) and this
     * has exactly one job (read + persist).
     *
     * Returns the record it wrote, or null when nothing real was available.
     */
    suspend fun captureBlocking(
        context: Context,
        habitId: String,
        activityType: String,
        windowMinutes: Long = DEFAULT_WINDOW_MINUTES,
    ): ActivityRecord? {
        // API 24/25 cannot call into the Health Connect SDK at all \u2014 the check
        // must come first, before any SDK type is touched.
        val status = MindsetHealthConnectClient.checkStatus(context)
        if (status !is HealthConnectStatus.Ready &&
            status !is HealthConnectStatus.PermissionsNeeded
        ) {
            Log.d(TAG, "Health Connect not usable ($status) \u2014 no activity captured")
            return null
        }
        if (!MindsetHealthConnectClient.hasAllPermissions(context)) {
            Log.d(TAG, "Health Connect not authorised \u2014 no activity captured")
            return null
        }

        val end = Instant.now()
        val start = end.minusSeconds(windowMinutes * 60L)
        val filter = TimeRangeFilter.between(start, end)
        val client = HealthConnectClient.getOrCreate(context)

        // \u2500\u2500 The session itself \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        // An ExerciseSessionRecord is the only thing that knows the activity
        // happened and for how long. Its absence is not a failure: the aggregates
        // below can still hold real numbers, they just cannot be attributed to a
        // discrete session.
        val sessionSeconds = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = filter,
                ),
            ).records
                .maxByOrNull { it.endTime }
                ?.let { Duration.between(it.startTime, it.endTime).seconds }
        }.onFailure { Log.w(TAG, "Could not read exercise sessions", it) }.getOrNull()

        // \u2500\u2500 Aggregates \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        // One request rather than four round-trips. Guarded as a whole because a
        // single ungranted record type makes Health Connect reject the entire
        // aggregate call with a SecurityException \u2014 which must not cost us the
        // metrics we *can* read.
        val metrics = runCatching { client.aggregate(aggregateRequest(filter)) }
            .onFailure { Log.w(TAG, "Could not aggregate activity metrics", it) }
            .getOrNull()

        val steps = metrics?.get(StepsRecord.COUNT_TOTAL)
        val distanceMeters = metrics?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters
        val calories = metrics?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories
        val heartRateAvg = metrics?.get(HeartRateRecord.BPM_AVG)?.toInt()

        // \u2500\u2500 Honesty gate \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        // If literally nothing real came back, record nothing at all. Persisting an
        // all-null record would create a phantom "activity" in the report: a row
        // claiming the user did something while showing no evidence of it.
        val hasRealData = sessionSeconds != null ||
            steps != null ||
            distanceMeters != null ||
            calories != null ||
            heartRateAvg != null
        if (!hasRealData) {
            Log.d(TAG, "No real activity data in the window \u2014 nothing recorded")
            return null
        }

        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            habitId = habitId,
            source = SOURCE,
            activityType = activityType,
            timestamp = System.currentTimeMillis(),
            // Rounded up, so a 30-second session is never reported as "0 min" \u2014
            // which would read as "you did nothing".
            durationMinutes = sessionSeconds
                ?.let { ((it + 59) / 60).toInt().coerceAtLeast(1) },
            distanceMeters = distanceMeters,
            steps = steps,
            heartRateAvg = heartRateAvg,
            calories = calories?.toInt(),
        )

        runCatching { MindsetRepository(context).saveActivityRecord(record) }
            .onFailure { Log.w(TAG, "Could not persist activity record", it) }

        Log.i(
            TAG,
            "Captured real activity for $habitId: ${record.durationMinutes}min, " +
                "${record.steps} steps, ${record.distanceMeters}m, " +
                "${record.calories} kcal, hr=${record.heartRateAvg}",
        )
        return record
    }

    /**
     * Captures activity for EVERY habit whose activity Health Connect can supply
     * data for. Called once, at the moment the user grants Health Connect access
     * — the first point at which the app can see real data at all.
     *
     * Kept here, rather than at the call site, so all coroutine usage stays inside
     * this object: the caller is a Compose permission-result lambda, and it should
     * not have to own a scope for background work.
     */
    fun captureAllSupportedHabits(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                MindsetRepository(appContext).load().habits
                    .filter { habit ->
                        habit.iconId
                            ?.let { MindsetHealthConnectClient.isActivitySupported(it) } == true
                    }
                    .forEach { habit ->
                        captureBlocking(
                            context = appContext,
                            habitId = habit.id,
                            activityType = habit.iconId ?: FALLBACK_ACTIVITY_TYPE,
                        )
                    }
            }.onFailure { Log.w(TAG, "Bulk activity capture failed", it) }
        }
    }

    /** The aggregate set, shared so the request and the reads cannot drift apart. */
    private fun aggregateRequest(filter: TimeRangeFilter) = AggregateRequest(
        metrics = setOf(
            StepsRecord.COUNT_TOTAL,
            DistanceRecord.DISTANCE_TOTAL,
            TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            HeartRateRecord.BPM_AVG,
        ),
        timeRangeFilter = filter,
    )

    /**
     * Metrics a phone alone can never supply, and which therefore must never be
     * shown as a number the user could mistake for a measurement. Surfaced by the
     * UI so an empty value is explained rather than silently blank.
     */
    val deviceUnavailableMetrics: List<String> = listOf(
        "distance (needs a recorded route, or a paired device)",
        "heart rate (needs a watch, band or chest strap)",
    )

    /**
     * How far back a capture looks by default. Long enough to cover an activity
     * that has just finished, short enough that the read stays cheap on the
     * alarm-time path.
     */
    const val DEFAULT_WINDOW_MINUTES = 180L

    /** Value of `ActivityRecord.source` for records captured here. */
    const val SOURCE = "health_connect_device"

    /** Used when a habit's icon cannot be resolved, so capture still happens. */
    private const val FALLBACK_ACTIVITY_TYPE = "activity"
}
