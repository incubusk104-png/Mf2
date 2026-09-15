package com.rork.mindsetframestracker.ui.components

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.ScreenTimeLimitInput
import com.rork.mindsetframestracker.integrations.ScreenTimeMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Screen-time limits manager.
 *
 * Lets the user set **their own daily limit for any installed app** — 30 min
 * for one, 2 h for another — and shows the real measured usage against it.
 *
 * ## Three things this does that the previous sheet could not
 *
 *  1. **It lists every app, not a subset.** The app list comes from
 *     [ScreenTimeMonitor.listMonitorableApps], which unions the launcher set
 *     with the installed-app set. That needs **no** permission — the `<queries>`
 *     element already covers the launcher intent — so the picker is populated
 *     even before Usage Access is granted. Usage *numbers* need the permission;
 *     the app *list* does not.
 *  2. **Each app shows what it is.** Real launcher icon + real app label, via
 *     [ApplicationIcon]. The old sheet drew a generic phone glyph next to every
 *     name, which made a list of 150 apps unreadable.
 *  3. **The limit is per app and the usage is real.** Each row shows today's
 *     measured minutes against that app's own limit, with a seven-day strip so
 *     the user can see the pattern rather than a single number.
 *
 * ## Permission and data are different problems
 *
 * Missing Usage Access and "granted but no usage recorded yet" both produce
 * zero numbers, but they need different messages and different fixes. This
 * sheet distinguishes them — see [PermissionBanner] and the empty state.
 *
 * @param habits Existing screen-time habits, so their limits can be pre-loaded
 *   and edited rather than retyped.
 * @param onSave Called with the complete desired set of limits (see
 *   [ScreenTimeLimitInput]). The caller reconciles: create, update, remove.
 * @param onDismiss Dismissal without saving.
 */
@Composable
fun ScreenTimeHabitSheet(
    habits: List<Habit>,
    onSave: (List<ScreenTimeLimitInput>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Draft state: package -> limit minutes. Seeded from the habits that
    // already exist so editing is a change, not a re-entry.
    var draft by remember {
        mutableStateOf(
            habits.filter { it.isScreenTimeHabit }
                .mapNotNull { habit ->
                    val pkg = habit.monitoredPackage ?: return@mapNotNull null
                    val limit = habit.screenTimeLimitMinutes ?: return@mapNotNull null
                    pkg to limit
                }
                .toMap()
        )
    }
    var labels by remember {
        mutableStateOf(
            habits.filter { it.isScreenTimeHabit }
                .mapNotNull { habit ->
                    val pkg = habit.monitoredPackage ?: return@mapNotNull null
                    pkg to (habit.monitoredAppLabel ?: pkg)
                }
                .toMap()
        )
    }

    var apps by remember { mutableStateOf<List<ScreenTimeMonitor.MonitorableApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(ScreenTimeMonitor.hasPermission(context)) }
    var hasData by remember { mutableStateOf(false) }
    var permissionChecked by remember { mutableStateOf(false) }

    // Which app's limit is being chosen right now (null = browsing the list).
    var editingPackage by remember { mutableStateOf<String?>(null) }

    // Progress toward opening the Usage Access settings screen. `null` until
    // the user taps it, so we don't nag before they've asked.
    var awaitingPermission by remember { mutableStateOf(false) }

    // Re-check permission on every (re)entry: the user leaves to Settings and
    // comes back, which recomposes this screen but does not re-run a
    // `Unit`-keyed effect. `awaitingPermission` flipping back to false is the
    // signal that we returned, so key the check on it too.
    LaunchedEffect(awaitingPermission) {
        if (awaitingPermission) return@LaunchedEffect
        hasPermission = ScreenTimeMonitor.hasPermission(context)
        hasData = if (hasPermission) {
            withContext(Dispatchers.IO) { ScreenTimeMonitor.hasUsageData(context) }
        } else {
            false
        }
        apps = withContext(Dispatchers.IO) { ScreenTimeMonitor.listMonitorableApps(context) }
        permissionChecked = true
    }

    // A large list of apps is unusable while it is still loading — show a
    // spinner rather than an empty list that looks like "no apps found".
    val filtered = remember(apps, query) {
        val list = apps ?: return@remember emptyList<ScreenTimeMonitor.MonitorableApp>()
        if (query.isBlank()) list
        else list.filter {
            it.label.contains(query.trim(), ignoreCase = true) ||
                it.packageName.contains(query.trim(), ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // A picker over 150 apps needs the width; the platform default leaves
        // it a phone-narrow column.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 12.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Header ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HourglassBottom,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Screen time limits",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (draft.isEmpty()) {
                                "Set a daily limit for any app on your phone."
                            } else {
                                "${draft.size} ${if (draft.size == 1) "app" else "apps"} limited"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                val editing = editingPackage

                if (editing != null) {
                    // ── Limit chooser for one app ──
                    LimitChooser(
                        appLabel = labels[editing] ?: ScreenTimeMonitor.labelFor(context, editing),
                        packageName = editing,
                        currentLimit = draft[editing] ?: DEFAULT_LIMIT_MINUTES,
                        hasPermission = hasPermission,
                        modifier = Modifier.weight(1f),
                        onBack = { editingPackage = null },
                        onConfirm = { minutes ->
                            draft = draft + (editing to minutes)
                            labels = labels + (editing to ScreenTimeMonitor.labelFor(context, editing))
                            editingPackage = null
                        },
                        onClear = {
                            draft = draft - editing
                            editingPackage = null
                        },
                    )
                } else {
                    if (!hasPermission || !hasData) {
                        PermissionBanner(
                            hasPermission = hasPermission,
                            hasData = hasData,
                            onGrant = {
                                awaitingPermission = true
                                runCatching {
                                    context.startActivity(
                                        Intent(ScreenTimeMonitor.buildSettingsIntent(context))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }

                    // ── Search ──
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search all apps…") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(8.dp))

                    // ── App list ──
                    val list = apps
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            list == null || !permissionChecked -> {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(32.dp),
                                )
                            }

                            list.isEmpty() -> {
                                EmptyAppsState(modifier = Modifier.align(Alignment.Center))
                            }

                            filtered.isEmpty() -> {
                                Text(
                                    text = "No app matches “${query.trim()}”.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }

                            else -> {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(filtered, key = { it.packageName }) { app ->
                                        AppLimitRow(
                                            app = app,
                                            limitMinutes = draft[app.packageName],
                                            hasPermission = hasPermission,
                                            onClick = { editingPackage = app.packageName },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Footer ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "${draft.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                onSave(
                                    draft.map { (pkg, minutes) ->
                                        ScreenTimeLimitInput(
                                            packageName = pkg,
                                            appLabel = labels[pkg]
                                                ?: ScreenTimeMonitor.labelFor(context, pkg),
                                            limitMinutes = minutes,
                                        )
                                    }
                                )
                            },
                            enabled = draft.isNotEmpty(),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) { Text(if (draft.isEmpty()) "Pick an app" else "Save limits") }
                    }
                }
            }
        }
    }
}

/** Default daily budget offered for a newly picked app: 2 hours. */
private const val DEFAULT_LIMIT_MINUTES = 120

/** The preset limits offered as chips, in minutes. */
private val PRESET_LIMITS = listOf(15, 30, 60, 120, 180)

/**
 * The limit chooser for a single app: presets, a custom value, and a live
 * preview of today's real usage against whatever is being chosen.
 */
@Composable
private fun LimitChooser(
    appLabel: String,
    packageName: String,
    currentLimit: Int,
    hasPermission: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onConfirm: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var limit by remember { mutableStateOf(currentLimit) }
    var customText by remember { mutableStateOf("") }
    var useCustom by remember { mutableStateOf(currentLimit !in PRESET_LIMITS) }
    var status by remember { mutableStateOf<ScreenTimeMonitor.LimitStatus?>(null) }

    // Measured usage for this app — shown live so the user picks a limit with
    // their real behaviour in front of them instead of guessing.
    LaunchedEffect(packageName, hasPermission) {
        status = if (hasPermission) {
            withContext(Dispatchers.IO) {
                ScreenTimeMonitor.limitStatus(context, packageName, currentLimit)
            }
        } else {
            null
        }
    }
    // Re-read with the chosen limit so the "under/over" verdict tracks the
    // value being edited, not the one that was saved.
    LaunchedEffect(hasPermission, limit) {
        if (hasPermission) {
            status = withContext(Dispatchers.IO) {
                ScreenTimeMonitor.limitStatus(context, packageName, limit)
            }
        }
    }

    val effectiveLimit = if (useCustom) {
        customText.toIntOrNull()?.coerceIn(1, 1440)
    } else {
        limit
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TextButton(onClick = onBack) { Text("← All apps") }
        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            ApplicationIcon(packageName = packageName, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Measured usage today ──
        UsageTodayCard(status = status, limit = effectiveLimit, hasPermission = hasPermission)

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Daily limit",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Column {
            PRESET_LIMITS.chunked(3).forEach { rowPresets ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    rowPresets.forEach { preset ->
                        FilterChip(
                            selected = !useCustom && limit == preset,
                            onClick = {
                                useCustom = false
                                limit = preset
                            },
                            label = { Text(formatLimitLabel(preset)) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = useCustom,
                    onClick = { useCustom = true },
                    label = { Text("Custom") },
                )
                if (useCustom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it.filter(Char::isDigit).take(4) },
                        placeholder = { Text("minutes") },
                        singleLine = true,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .width(120.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "This week",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        WeeklyUsageStrip(status = status, hasPermission = hasPermission)

        Spacer(Modifier.height(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { effectiveLimit?.let(onConfirm) },
                enabled = effectiveLimit != null,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp),
            ) { Text("Set ${effectiveLimit?.let(::formatLimitLabel) ?: "limit"}") }
            if (currentLimit != DEFAULT_LIMIT_MINUTES || customText.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Today's measured usage for one app, versus the limit being chosen. */
@Composable
private fun UsageTodayCard(
    status: ScreenTimeMonitor.LimitStatus?,
    limit: Int?,
    hasPermission: Boolean,
) {
    val used = status?.usedMinutesToday
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when {
                !hasPermission -> Text(
                    text = "Grant Usage access to measure this app's screen time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                used == null -> Text(
                    text = "No usage recorded yet for this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    val over = limit != null && used > limit
                    Text(
                        text = "Today: ${formatMinutes(used)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (over) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (limit != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (over) {
                                "Over your ${formatLimitLabel(limit)} limit by " +
                                    formatMinutes(used - limit)
                            } else {
                                "${formatMinutes(limit - used)} left of your " +
                                    formatLimitLabel(limit) + " limit"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (over) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        // `status` is a delegated state property, so it cannot be
                        // smart-cast here — read the progress through the safe call.
                        UsageBar(
                            progress = status?.progress ?: 0f,
                            over = over,
                        )
                    }
                }
            }
        }
    }
}

/** A simple proportional bar — no Canvas needed, just a filled fraction. */
@Composable
private fun UsageBar(progress: Float, over: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (over) MaterialTheme.colorScheme.error
                    else Color(0xFF4CAF50),
                ),
        )
    }
}

/**
 * Seven-day usage strip: one bar per day, scaled to the busiest day so the
 * shape is readable, with bars over the limit marked.
 */
@Composable
private fun WeeklyUsageStrip(
    status: ScreenTimeMonitor.LimitStatus?,
    hasPermission: Boolean,
) {
    val week = status?.week.orEmpty()
    if (!hasPermission || week.isEmpty()) {
        Text(
            text = if (!hasPermission) {
                "Available once Usage access is granted."
            } else {
                "No history yet — check back after a day of use."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // Scale against the busiest day AND the limit, so a quiet week against a
    // large limit still shows the limit line at a sensible height rather than
    // pinning every bar to the top.
    val limit = status?.limitMinutes ?: 0
    val peak = maxOf(week.maxOfOrNull { it.minutes } ?: 0L, limit.toLong(), 1L)

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            week.forEach { day ->
                val fraction = (day.minutes.toFloat() / peak.toFloat()).coerceIn(0f, 1f)
                val over = limit > 0 && day.minutes > limit
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (over) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                ),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = dayLabel(day.dayStartMs),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Bar colour marks days over the limit" +
                if (limit > 0) " (${formatLimitLabel(limit)})" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One app in the picker: real icon, label, and its current limit if set. */
@Composable
private fun AppLimitRow(
    app: ScreenTimeMonitor.MonitorableApp,
    limitMinutes: Int?,
    hasPermission: Boolean,
    onClick: () -> Unit,
) {
    val selected = limitMinutes != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        ApplicationIcon(packageName = app.packageName, size = 36.dp, fallback = app.icon)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            // Real measured usage, so the user picks the app they actually
            // over-use rather than the one they remember over-using.
            val subtitle = when {
                selected -> "Limit ${formatLimitLabel(limitMinutes!!)}"
                !hasPermission -> null
                app.usedMinutesToday != null && app.usedMinutesToday > 0 ->
                    "${formatMinutes(app.usedMinutesToday)} today"
                app.usedRecently -> "Used recently"
                else -> null
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Limit set",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Text(
                text = "Set limit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The app's real launcher icon, drawn from the drawable the PackageManager
 * returns.
 *
 * The drawable is converted on a background dispatch and cached per package,
 * because `getApplicationIcon` decodes resources and doing that on the main
 * thread for 150 rows is exactly the kind of jank this screen does not need.
 * Falls back to a generic phone glyph when an app exposes no icon.
 */
@Composable
private fun ApplicationIcon(
    packageName: String,
    size: androidx.compose.ui.unit.Dp,
    fallback: android.graphics.drawable.Drawable? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = fallback ?: ScreenTimeMonitor.iconFor(context, packageName)
                if (drawable == null) {
                    null
                } else {
                    val px = (size.value * 2).toInt().coerceAtLeast(48)
                    drawable.toBitmap(px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            }.getOrNull()
        }
    }

    val icon = bitmap
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * Explains exactly what is missing.
 *
 * Two genuinely different states, deliberately worded differently:
 *  - no permission → "turn on Usage access", with the button that gets there;
 *  - permission but no data → nothing to grant, it simply hasn't been recorded
 *    yet, so offering a Settings button would send the user on a pointless trip.
 */
@Composable
private fun PermissionBanner(
    hasPermission: Boolean,
    hasData: Boolean,
    onGrant: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (hasPermission) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = if (!hasPermission) {
                    "Turn on Usage access to measure screen time"
                } else {
                    "No usage data recorded yet"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (!hasPermission) {
                    "Android keeps this switch under Special access, and it can't be " +
                        "granted from inside the app. You can still pick apps below — " +
                        "their time appears once access is on."
                } else {
                    "Access is granted, but the system hasn't recorded any app usage " +
                        "yet. Screen time usually appears after the apps have been used " +
                        "for a while."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasPermission) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onGrant) { Text("Open Usage access settings") }
            }
        }
    }
}

/** Shown when the app scan genuinely returned nothing. */
@Composable
private fun EmptyAppsState(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(24.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "No apps found",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "The app list came back empty. This usually means the launcher " +
                "query was blocked — reopening the app normally clears it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Formatting ────────────────────────────────────────────────────────────

/** "2h" / "1h 30m" / "45m" for a daily limit. */
internal fun formatLimitLabel(minutes: Int): String = when {
    minutes % 60 == 0 && minutes >= 60 -> "${minutes / 60}h"
    minutes > 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}

/** "38m" / "2h 5m" for measured usage. */
private fun formatMinutes(minutes: Long): String = when {
    minutes <= 0 -> "0m"
    minutes < 60 -> "${minutes}m"
    minutes % 60L == 0L -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** Single-letter weekday label for a day's local midnight. */
private fun dayLabel(dayStartMs: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = dayStartMs }
        .get(Calendar.DAY_OF_WEEK)
    return when (day) {
        Calendar.MONDAY -> "M"
        Calendar.TUESDAY -> "T"
        Calendar.WEDNESDAY -> "W"
        Calendar.THURSDAY -> "T"
        Calendar.FRIDAY -> "F"
        Calendar.SATURDAY -> "S"
        else -> "S"
    }
}
