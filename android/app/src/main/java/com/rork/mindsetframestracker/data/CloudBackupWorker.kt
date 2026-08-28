package com.rork.mindsetframestracker.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Automated daily cloud backup. WorkManager runs this roughly once every
 * 24 hours — even when the app hasn't been opened — so a signed-in user's
 * habits, check-ins, and moods are never more than a day behind in the
 * cloud, regardless of whether the in-app queued sync got a chance to run.
 *
 * Constraints mirror the in-app sync rules: requires a network connection
 * and skips while the battery is low. Transient failures retry with
 * exponential backoff inside the same period; a run that still fails leaves
 * [SupabaseSync.hasPendingPush] set so the next app launch retries
 * immediately. The worker persists across reboots automatically.
 */
class CloudBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = runCatching { SupabaseSync(applicationContext) }.getOrNull()
            ?: return Result.success()
        // Signed out or unconfigured — nothing to back up.
        if (!sync.isConfigured || !sync.isSignedIn) return Result.success()
        val data = runCatching { MindsetRepository(applicationContext).load() }.getOrNull()
            ?: return Result.success()
        val error = runCatching { sync.pushSnapshot(data) }.getOrElse { it.message }
        return when {
            error == null -> {
                sync.hasPendingPush = false
                Log.i(TAG, "Daily cloud backup completed")
                Result.success()
            }
            runAttemptCount < MAX_RETRIES -> {
                Log.w(TAG, "Daily cloud backup failed, will retry: $error")
                Result.retry()
            }
            else -> {
                // Give up until the next period — the launch-time retry and
                // the in-app queued sync still cover the gap.
                sync.hasPendingPush = true
                Log.w(TAG, "Daily cloud backup failed: $error")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "CloudBackupWorker"
        private const val UNIQUE_NAME = "daily_cloud_backup"
        private const val MAX_RETRIES = 3

        /** Enqueues (or keeps) the daily backup. Call whenever a session starts. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Stops the daily backup — call on sign-out or account deletion. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
