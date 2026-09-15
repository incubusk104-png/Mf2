package com.rork.mindsetframestracker.integrations

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
 *
 * ## Why usage is measured from events, not from `queryUsageStats`
 *
 * The obvious implementation sums [android.app.usage.UsageStats.getTotalTimeInForeground]
 * over `queryUsageStats(INTERVAL_DAILY, start, end)`. That is what this file
 * used to do, and it is wrong in three separate ways:
 *
 *  1. `INTERVAL_DAILY` does **not** mean "the window you asked for". It means
 *     "one bucket per calendar day", and the bucket is clipped to the query
 *     window. A query from 00:00 to *now* therefore returns only today's
 *     partial bucket, while a query spanning yesterday..today returns a
 *     *single* bucket for today and one for yesterday — but the values are
 *     per-bucket totals that cannot be attributed to the requested window
 *     once the window and the bucket disagree.
 *  2. On many OEMs (EMUI/MIUI/ColorOS) the daily bucket is written lazily, so
 *     it lags real time by hours and silently reports `0` for an app the user
 *     is actively using right now.
 *  3. It reports foreground time per *app*, not per day, so "how much did I
 *     use this yesterday" and "how much today" cannot be separated reliably.
 *
 * [UsageEvents] has none of those problems: it is a timestamped stream of
 * `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` transitions, so a session is
 * `paused - resumed` and the total for any window is simply the sum of the
 * sessions clipped to it. That is what this file now does, per day, which is
 * also what lets [dailyUsageMinutes] answer for all seven days of the week in
 * a single pass rather than seven queries.
 *
 * Whether usage data actually **exists** is a separate question from whether
 * the permission is granted — see [hasUsageData].
 */
object ScreenTimeMonitor {

    private const val TAG = "ScreenTimeMonitor"

    /** How far back a query may reach. Usage events are not retained forever. */
    private const val MAX_LOOKBACK_DAYS = 30

    /** A launchable app the user can pick to monitor. */
    data class MonitorableApp(
        val packageName: String,
        val label: String,
        /** Real launcher icon, or null when it could not be loaded. */
        val icon: Drawable? = null,
        /** True when the user has actually used this app in the last 30 days. */
        val usedRecently: Boolean = false,
        /** Today's usage in minutes, or null when unavailable. */
        val usedMinutesToday: Long? = null,
    )

    /** Today's usage snapshot for a monitored package. */
    data class UsageSnapshot(
        val packageName: String,
        val usedMinutesToday: Long,
    )

    /** One day of measured usage. */
    data class DayUsage(
        /** Local midnight of this day. */
        val dayStartMs: Long,
        val minutes: Long,
    )

    /** Today's usage for one package, plus the summary the UI shows against the limit. */
    data class LimitStatus(
        val packageName: String,
        val limitMinutes: Int,
        val usedMinutesToday: Long?,
        /** dayStartMs -> minutes, oldest first, one entry per day (7 for a week). */
        val week: List<DayUsage>,
    ) {
        /** Minutes still available today; null when usage is unknown. Negative once over. */
        val remainingMinutes: Long? get() = usedMinutesToday?.let { limitMinutes - it }

        /** True when measured usage is at or under the user's limit. */
        val isUnderLimit: Boolean?
            get() = usedMinutesToday?.let { it <= limitMinutes }

        /** True when usage could not be measured at all. */
        val isUnknown: Boolean get() = usedMinutesToday == null

        /** Fraction of the budget consumed, clamped to 0f..1f for progress rendering. */
        val progress: Float
            get() {
                val used = usedMinutesToday ?: return 0f
                if (limitMinutes <= 0) return 0f
                return (used.toFloat() / limitMinutes.toFloat()).coerceIn(0f, 1f)
            }
    }

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

    /**
     * True when the permission is granted **and** the platform actually returns
     * usage events.
     *
     * Permission and data are not the same thing. A freshly-reset device, a
     * device whose usage history was cleared, or an OEM that withholds events
     * all report `MODE_ALLOWED` while `queryEvents` returns nothing. Without
     * this check the picker looks broken — an empty list under a permission
     * the user already granted. The UI uses this to say "no usage data yet"
     * instead of "you need to grant access", which are different problems.
     */
    fun hasUsageData(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 3L * 24L * 60L * 60L * 1000L
            val events = usm.queryEvents(start, end) ?: return false
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.getPackageName() != null) return true
            }
            false
        }.getOrDefault(false)
    }

    /** Deep link to Settings > Special access > Usage access for this app. */
    fun buildSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            // Some OEMs (incl. Huawei/Honor EMUI) accept a package Uri to land
            // directly on this app's row; stock Android ignores it gracefully.
            data = android.net.Uri.parse("package:${context.packageName}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // App enumeration
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Every app the user can actually open (excluding this one), sorted by label.
     *
     * Enumerates with **both** mechanisms and unions the results, because each
     * one alone is incomplete:
     *
     *  - `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` is the
     *    canonical "app drawer" set. It needs no extra permission at all
     *    (the `<queries>` element already declares the launcher intent), and it
     *    is the set that matches what the user sees on their home screen.
     *  - `getInstalledApplications` additionally catches apps that are
     *    installed and used in the background but expose no launcher entry —
     *    without it, an app that only ever opens from a notification or a share
     *    sheet would be missing from the picker even though its usage is
     *    being measured.
     *
     * Union rather than either/or is deliberate: the launcher query is the
     * trustworthy core, and the installed-apps pass only *adds*. Apps are then
     * labelled with the real [PackageManager.getApplicationLabel] and carry
     * their real icon, so the picker shows what the user recognises instead of
     * package names and a generic placeholder glyph.
     *
     * Apps are ordered by recent usage (most-used first) when usage data is
     * available, and alphabetically otherwise — a list of 150+ apps is only
     * usable if the ones that matter float to the top.
     */
    fun listMonitorableApps(context: Context): List<MonitorableApp> = runCatching {
        val pm = context.packageManager
        val self = context.packageName

        // Packages we must never offer: this app, and the launchers themselves
        // (monitoring your own home screen is not a meaningful limit).
        val excluded = mutableSetOf(self)
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(homeIntent, 0)
                .forEach { it.activityInfo?.packageName?.let(excluded::add) }
        }

        val packages = linkedSetOf<String>()

        // 1. Launchable apps (no permission needed — covered by <queries>).
        runCatching {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
                .forEach { activity ->
                    activity.activityInfo?.packageName?.let { packages.add(it) }
                }
        }.onFailure { Log.w(TAG, "launcher scan failed: ${it.message}") }

        // 2. Everything installed — adds background-only apps the scan above misses.
        runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .forEach { info: ApplicationInfo ->
                    if (!isHiddenOrFramework(info)) packages.add(info.packageName)
                }
        }.onFailure { Log.w(TAG, "installed-app scan failed: ${it.message}") }

        // Per-package usage for today + "used in the last 30 days", so the list
        // can be ranked and so the caller can show usage inline.
        val todayStart = startOfDayMillis(0)
        val usage = if (hasPermission(context)) {
            usageByPackage(context, todayStart, System.currentTimeMillis())
        } else {
            emptyMap()
        }
        val active = if (hasPermission(context)) {
            usedPackagesSince(context, MAX_LOOKBACK_DAYS)
        } else {
            emptySet()
        }

        packages
            .asSequence()
            .filter { it !in excluded }
            .distinct()
            .mapNotNull { pkg ->
                runCatching {
                    @Suppress("DEPRECATION")
                    val info = pm.getApplicationInfo(pkg, 0)
                    MonitorableApp(
                        packageName = pkg,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(pkg),
                        icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                        usedRecently = pkg in active,
                        usedMinutesToday = usage[pkg],
                    )
                }.getOrNull()
            }
            .sortedWith(
                // Most-used first, then alphabetically. A stable secondary key
                // is what keeps two zero-usage apps from swapping places
                // between recompositions.
                compareByDescending<MonitorableApp> { it.usedMinutesToday ?: -1L }
                    .thenBy { it.label.lowercase() }
            )
            .toList()
    }.onFailure {
        Log.w(TAG, "listMonitorableApps failed: ${it.message}")
    }.getOrDefault(emptyList())

    /**
     * True for packages that exist on the system but are not user-facing apps
     * (system/framework components, our own pre-installed helpers, packages
     * hidden from the launcher).
     */
    private fun isHiddenOrFramework(info: ApplicationInfo): Boolean {
        val isSystemFlagSet = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val hasLaunchIntent = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        // Keep system apps that have been updated by the user (Play-updated
        // system apps are ordinary apps in every meaningful sense), drop the
        // rest of the framework.
        if (isSystemFlagSet && !hasLaunchIntent) {
            // A few system apps ARE user-facing (Chrome, YouTube on some ROMs
            // ship as system). Those are already in the launcher set, so
            // dropping them here only loses the background-only extras.
            return true
        }
        return false
    }

    /** Human-readable label for a package, falling back to the package name. */
    fun labelFor(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** Real launcher icon for a package, or null when unavailable. */
    fun iconFor(context: Context, packageName: String): Drawable? = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationIcon(pm.getApplicationInfo(packageName, 0))
    }.getOrNull()

    /** True when [packageName] is still installed. */
    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    // ─────────────────────────────────────────────────────────────────────
    // Usage measurement
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Foreground minutes for [packageName] between [startMs] and [endMs],
     * derived from resume/pause events.
     * Returns null when the permission is missing or the query fails.
     */
    fun usedMinutes(context: Context, packageName: String, startMs: Long, endMs: Long): Long? {
        if (!hasPermission(context)) return null
        if (endMs <= startMs) return 0L
        return runCatching {
            val totals = sessionTotals(context, listOf(packageName), startMs, endMs)
            totals[packageName] ?: 0L
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

    /**
     * Today's usage plus a per-day breakdown for the last [days] days,
     * oldest first — everything the UI needs to show "today vs limit" and a
     * weekly bar chart, in one query.
     *
     * [days] is clamped to a sane range; asking for 7 gives a week ending today.
     */
    fun limitStatus(
        context: Context,
        packageName: String,
        limitMinutes: Int,
        days: Int = 7,
    ): LimitStatus {
        val span = days.coerceIn(1, MAX_LOOKBACK_DAYS)
        if (!hasPermission(context)) {
            return LimitStatus(packageName, limitMinutes, null, emptyList())
        }
        return runCatching {
            val todayStart = startOfDayMillis(0)
            val windowStart = startOfDayMillis(-(span - 1))
            val end = System.currentTimeMillis()

            val perDay = dailyUsageMinutes(context, listOf(packageName), windowStart, end)
                ?: return@runCatching LimitStatus(packageName, limitMinutes, null, emptyList())

            val week = (0 until span).map { index ->
                val offset = index - (span - 1) // -(span-1) .. 0
                val dayStart = startOfDayMillis(offset)
                DayUsage(dayStartMs = dayStart, minutes = perDay[dayStart] ?: 0L)
            }
            LimitStatus(
                packageName = packageName,
                limitMinutes = limitMinutes,
                usedMinutesToday = perDay[todayStart] ?: 0L,
                week = week,
            )
        }.onFailure {
            Log.w(TAG, "limitStatus failed for $packageName: ${it.message}")
        }.getOrDefault(LimitStatus(packageName, limitMinutes, null, emptyList()))
    }

    /**
     * Usage for several packages at once, split by local day.
     *
     * Returns `key` -> (dayStartMs -> minutes), or null when permission is
     * missing. Batched deliberately: the habit list needs today's number for
     * every monitored package, and one event scan answers for all of them.
     */
    fun dailyUsageMinutes(
        context: Context,
        packageNames: Collection<String>,
        startMs: Long,
        endMs: Long,
    ): Map<String, Map<Long, Long>>? {
        if (!hasPermission(context)) return null
        if (packageNames.isEmpty() || endMs <= startMs) return emptyMap()
        return runCatching {
            val wanted = packageNames.toSet()
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(startMs, endMs) ?: return emptyMap()

            // openSession[pkg] = (resumeMs, resumeDayStartMs)
            val openSession = HashMap<String, Pair<Long, Long>>()
            val totals = HashMap<String, MutableMap<Long, Long>>()

            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.getPackageName() ?: continue
                if (pkg !in wanted) continue
                val ts = event.getTimeStamp()

                when (event.getEventType()) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    -> {
                        // Keep the EARLIEST resume of an open pair so a
                        // resume/resume sequence without a pause doesn't
                        // discard the time already accumulated.
                        val existing = openSession[pkg]
                        if (existing == null) {
                            openSession[pkg] = ts to startOfDayMillisFor(ts)
                        }
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    -> {
                        val open = openSession.remove(pkg) ?: continue
                        addSession(totals, pkg, open.first, ts, open.second)
                    }
                }
            }

            // Anything still open is a session in progress — count it up to
            // `endMs`, otherwise an app the user has open right now would show
            // as zero usage until they leave it.
            for ((pkg, open) in openSession) {
                addSession(totals, pkg, open.first, endMs, open.second)
            }

            totals.mapValues { (_, byDay) -> byDay.toMap() }
        }.onFailure {
            Log.w(TAG, "dailyUsageMinutes failed: ${it.message}")
        }.getOrNull()
    }

    /** Today's usage per package for every package with any usage today. */
    private fun usageByPackage(
        context: Context,
        startMs: Long,
        endMs: Long,
    ): Map<String, Long> = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(startMs, endMs) ?: return emptyMap()
        val openSession = HashMap<String, Long>()
        val totals = HashMap<String, Long>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.getPackageName() ?: continue
            when (event.getEventType()) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                -> if (pkg !in openSession) openSession[pkg] = event.getTimeStamp()

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                -> openSession.remove(pkg)?.let { started ->
                    totals[pkg] = (totals[pkg] ?: 0L) + (event.getTimeStamp() - started).coerceAtLeast(0L)
                }
            }
        }
        for ((pkg, started) in openSession) {
            totals[pkg] = (totals[pkg] ?: 0L) + (endMs - started).coerceAtLeast(0L)
        }
        totals.mapValues { (_, ms) -> ms / 60_000L }
    }.onFailure { Log.w(TAG, "usageByPackage failed: ${it.message}") }.getOrDefault(emptyMap())

    /**
     * Packages with at least one foreground session in the last [days] days.
     * Used to mark "recently used" apps and to keep the picker honest about
     * apps that are installed but never opened.
     */
    private fun usedPackagesSince(context: Context, days: Int): Set<String> = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = startOfDayMillis(-(days.coerceIn(1, MAX_LOOKBACK_DAYS) - 1))
        val events = usm.queryEvents(start, end) ?: return emptySet()
        val seen = HashSet<String>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            event.getPackageName()?.let(seen::add)
        }
        seen
    }.onFailure { Log.w(TAG, "usedPackagesSince failed: ${it.message}") }.getOrDefault(emptySet())

    /** Per-package foreground minutes for a window (session totals, not per-day). */
    private fun sessionTotals(
        context: Context,
        packageNames: Collection<String>,
        startMs: Long,
        endMs: Long,
    ): Map<String, Long> = runCatching {
        val wanted = packageNames.toSet()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(startMs, endMs) ?: return emptyMap()
        val openSession = HashMap<String, Long>()
        val totals = HashMap<String, Long>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.getPackageName() ?: continue
            if (pkg !in wanted) continue
            when (event.getEventType()) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                -> if (pkg !in openSession) openSession[pkg] = event.getTimeStamp()

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                -> openSession.remove(pkg)?.let { started ->
                    totals[pkg] = (totals[pkg] ?: 0L) +
                        (event.getTimeStamp() - started).coerceAtLeast(0L)
                }
            }
        }
        for ((pkg, started) in openSession) {
            totals[pkg] = (totals[pkg] ?: 0L) + (endMs - started).coerceAtLeast(0L)
        }
        totals.mapValues { (_, ms) -> ms / 60_000L }
    }.onFailure { Log.w(TAG, "sessionTotals failed: ${it.message}") }.getOrDefault(emptyMap())

    /**
     * Attribute a session to local days. A session that crosses midnight is
     * split across both days, so "last night 23:40–00:30" contributes 20
     * minutes to yesterday and 30 to today rather than all of it to whichever
     * day it happened to start on.
     */
    private fun addSession(
        totals: MutableMap<String, MutableMap<Long, Long>>,
        pkg: String,
        startMs: Long,
        endMs: Long,
        startDayMs: Long,
    ) {
        if (endMs <= startMs) return
        val byDay = totals.getOrPut(pkg) { HashMap() }

        var cursor = startMs
        var dayStart = startDayMs
        // Bounded so a pathological timestamp can never spin forever; 400 days
        // is far beyond any real session and any realistic query window.
        var guard = 0
        while (cursor < endMs && guard++ < 400) {
            val nextDayStart = dayStart + 24L * 60L * 60L * 1000L
            val sliceEnd = minOf(endMs, nextDayStart)
            val minutes = (sliceEnd - cursor) / 60_000L
            if (minutes > 0) byDay[dayStart] = (byDay[dayStart] ?: 0L) + minutes
            cursor = sliceEnd
            dayStart = nextDayStart
        }
    }

    /** Local midnight of the day containing [tsMs]. */
    private fun startOfDayMillisFor(tsMs: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = tsMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Local midnight [dayOffset] days from today (0 = today, -1 = yesterday). */
    private fun startOfDayMillis(dayOffset: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Public counterpart of [startOfDayMillis], so callers can key the maps
     * returned by [dailyUsageMinutes] by day without reimplementing local
     * midnight (and getting it subtly wrong across DST).
     */
    fun dayStartMillis(dayOffset: Int): Long = startOfDayMillis(dayOffset)
}
