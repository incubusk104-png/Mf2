package com.rork.mindsetframestracker.integrations

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * On-device screen-time monitoring for "digital wellbeing" habits —
 * e.g. "keep Facebook under 2 hours a day".
 *
 * Built on [UsageStatsManager], which requires the special
 * PACKAGE_USAGE_STATS app-op (already declared in the manifest). Android
 * never auto-grants it: the user must flip the switch in
 * Settings > Apps > Special access > Usage access. [hasPermission] checks
 * the grant and [buildSettingsIntent] deep-links to that screen.
 *
 * Everything is measured locally on the phone. No usage data ever leaves
 * the device — only the habit's daily pass/fail check-in syncs to the
 * cloud like any other habit.
 */
object ScreenTimeMonitor {

    private const val TAG = "ScreenTimeMonitor"

    /** A launchable app the user can pick to monitor. */
    data class MonitorableApp(
        val packageName: String,
        val label: String,
    )

    /** Today's usage snapshot for a monitored package. */
    data class UsageSnapshot(
        val packageName: String,
        val usedMinutesToday: Long,
    )

    /** True when the Usage Access special permission has been granted. */
    fun hasPermission(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Deep link to Settings > Special access > Usage access for this app. */
    fun buildSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            // Some OEMs (incl. Huawei/Honor EMUI) accept a package Uri to land
            // directly on this app's row; stock Android ignores it gracefully.
            data = android.net.Uri.parse("package:${context.packageName}")
        }

    /**
     * All launchable, user-facing apps on this phone (excluding this app),
     * sorted by label. Used by the app picker in the screen-time habit sheet.
     */
    fun listMonitorableApps(context: Context): List<MonitorableApp> = runCatching {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        resolved
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info: ApplicationInfo ->
                MonitorableApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
    }.onFailure {
        Log.w(TAG, "listMonitorableApps failed: ${it.message}")
    }.getOrDefault(emptyList())

    /** Human-readable label for a package, falling back to the package name. */
    fun labelFor(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** True when [packageName] is still installed. */
    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * Foreground minutes for [packageName] between [startMs] and [endMs].
     * Returns null when the permission is missing or the query fails.
     */
    fun usedMinutes(context: Context, packageName: String, startMs: Long, endMs: Long): Long? {
        if (!hasPermission(context)) return null
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            // INTERVAL_DAILY buckets can span midnight on some OEMs; summing
            // totalTimeInForeground across the queried window and clamping to
            // the window length is the practical cross-device approach.
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs)
                .orEmpty()
                .filter { it.packageName == packageName }
            val totalMs = stats.sumOf { it.totalTimeInForeground }
                .coerceAtMost(endMs - startMs)
            totalMs / 60_000L
        }.onFailure {
            Log.w(TAG, "usedMinutes failed for $packageName: ${it.message}")
        }.getOrNull()
    }

    /** Foreground minutes for [packageName] so far today. */
    fun usedMinutesToday(context: Context, packageName: String): Long? {
        val start = startOfDayMillis(0)
        return usedMinutes(context, packageName, start, System.currentTimeMillis())
    }

    /** Foreground minutes for [packageName] across the whole of yesterday. */
    fun usedMinutesYesterday(context: Context, packageName: String): Long? {
        val startYesterday = startOfDayMillis(-1)
        val startToday = startOfDayMillis(0)
        return usedMinutes(context, packageName, startYesterday, startToday)
    }

    private fun startOfDayMillis(dayOffset: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
