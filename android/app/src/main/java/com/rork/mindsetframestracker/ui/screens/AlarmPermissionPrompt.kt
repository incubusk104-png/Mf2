package com.rork.mindsetframestracker.ui.screens

/*
 * ── WHAT CHANGED FROM THE OLD AlarmReliabilityCard.kt ───────────────────
 * Previously this was a persistent card the user had to go find in
 * Settings themselves. Per request: it's now automatic — call
 * [maybeShowAlarmPermissionPrompt] right after HabitAlarmScheduler.schedule()
 * fires (i.e. the moment the user actually sets an alarm), and this dialog
 * pops up on its own if anything is missing. Nothing to visit in Settings
 * anymore; delete any `AlarmReliabilityCard()` call there.
 *
 * ── HOW TO WIRE IT UP ────────────────────────────────────────────────────
 * 1. Drop this file next to HabitsScreen.kt.
 * 2. Delete the old AlarmReliabilityCard.kt file and any
 *    `AlarmReliabilityCard()` call in SettingsScreen.kt.
 * 3. In HabitsScreen.kt, wherever you currently call
 *    `HabitAlarmScheduler.schedule(context, habit)`, add right after it:
 *
 *        if (!AlarmPermissions.allGranted(context)) showAlarmPermissionPrompt = true
 *
 *    (with `var showAlarmPermissionPrompt by remember { mutableStateOf(false) }`
 *    declared once near your other dialog state, and this composable shown
 *    when it's true: `if (showAlarmPermissionPrompt) AlarmPermissionPromptDialog(
 *    onDismiss = { showAlarmPermissionPrompt = false } )`).
 *
 * No manifest changes needed — every permission requested here is already
 * declared in AndroidManifest.xml.
 */

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.widget.Toast
import com.rork.mindsetframestracker.notifications.HabitCheckInNotifier

/**
 * Public so the screen that just set an alarm can check "should I show the
 * prompt?" without any UI overhead — no permission dialog is ever shown
 * unless something is actually missing.
 */
object AlarmPermissions {

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true) // treat unsupported OEMs as "fine" rather than nagging forever
    }

    /**
     * BUG FIX (the actual "alarm doesn't ring" root cause on modern phones):
     * starting with Android 14 (API 34), `NotificationCompat.Builder
     * .setFullScreenIntent()` is no longer enough on its own. The OS also
     * requires a separate, user-granted "Full screen intent" permission
     * (Settings > Apps > this app > Full screen intent) — off by default for
     * ordinary apps. If it isn't granted, Android doesn't error or fall back
     * loudly; it just silently downgrades the notification to a normal
     * heads-up one, so [AlarmRingingActivity] — the screen that actually
     * plays the alarm sound and vibration — never launches. Everything else
     * (exact alarm, battery exemption, notification permission) can be
     * perfectly granted and the "alarm" will still never ring on API 34+
     * until this one is granted too.
     */
    fun hasFullScreenIntentPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true // permission didn't exist before API 34
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    /** True only when every permission that affects alarm delivery is granted. */
    fun allGranted(context: Context): Boolean =
        hasNotificationPermission(context) &&
            hasExactAlarmPermission(context) &&
            isIgnoringBatteryOptimizations(context) &&
            hasFullScreenIntentPermission(context)

    /**
     * MIUI (Xiaomi/Redmi/POCO) kills scheduled work in the background unless
     * the app is also allowed under its own proprietary "Autostart" toggle —
     * a setting with no public Android API, entirely separate from (and in
     * addition to) the standard battery-optimization exemption above. This
     * is consistently the single biggest cause of "I set an alarm and it
     * just never rang" reports specifically on Xiaomi/Redmi/POCO devices;
     * [isIgnoringBatteryOptimizations] can return true while Autostart is
     * still off and the alarm still won't fire.
     */
    fun isMiuiDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("POCO", ignoreCase = true)
}

/** Opens MIUI's Autostart management screen. No public API exists to check
 * or request this — it's a manufacturer-specific settings page reached only
 * via this hardcoded component, with a couple of known variants across MIUI
 * versions. Falls back to the app's own details page if none resolve. */
private fun openMiuiAutostartSettings(context: Context) {
    val candidates = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.miui.securitycenter" to "com.miui.securitycenter.permission.AutoStartManagementActivity",
    )
    val opened = candidates.any { (pkg, cls) ->
        runCatching {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                putExtra("extra_pkgname", context.packageName)
            }
            context.startActivity(intent)
        }.isSuccess
    }
    if (!opened) openAppDetailsSettings(context)
}

/**
 * Opens MIUI's per-app "Battery saver" page (Settings → Battery & performance
 * → App battery saver → this app), where it needs to be set to "No
 * restrictions". This is SEPARATE from both the standard Android
 * isIgnoringBatteryOptimizations() exemption above AND from Autostart —
 * MIUI can (and very often does) kill a scheduled alarm's receiver even when
 * both of those are already granted, if this third toggle is left on
 * "Save battery" (the default for every newly installed app). No public API
 * exists to read its current state, same as Autostart — these are the two
 * known component paths across MIUI versions, with a details-page fallback.
 */
private fun openMiuiBatterySaverSettings(context: Context) {
    val candidates = listOf(
        "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
    )
    val opened = candidates.any { (pkg, cls) ->
        runCatching {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                putExtra("package_name", context.packageName)
                putExtra("package_label", "Mindset Frames")
            }
            context.startActivity(intent)
        }.isSuccess
    }
    if (!opened) openAppDetailsSettings(context)
}

/**
 * THE MOST COMMON REAL CAUSE of "Autostart is on, Battery saver is 'No
 * restrictions', and it STILL doesn't ring" on Xiaomi/Redmi/POCO: MIUI has a
 * fourth, separate toggle — "Display pop-up windows while running in the
 * background" (sometimes labelled "Display pop-up windows" / "Show on lock
 * screen") under Settings → Apps → this app → Other permissions. Full-screen
 * intents (which is how [AlarmRingingActivity] launches) are exactly the
 * "pop-up window while backgrounded" case MIUI locks down by default,
 * independently of Autostart, Battery saver, and even the standard Android
 * "Use full screen intent" permission. Every one of those can be granted and
 * the alarm screen still won't appear until this MIUI-specific toggle is
 * also flipped on.
 */
private fun openMiuiPopupPermissionSettings(context: Context) {
    val candidates = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.permissions.PermissionsEditorActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
    )
    val opened = candidates.any { (pkg, cls) ->
        runCatching {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                putExtra("extra_pkgname", context.packageName)
            }
            context.startActivity(intent)
        }.isSuccess
    }
    if (!opened) openAppDetailsSettings(context)
}

/**
 * Auto-triggered dialog — shown right after the user sets an alarm, only
 * if something is actually missing. Walks them through fixing it on the
 * spot instead of leaving it to be discovered (or not) in Settings later.
 */
@Composable
fun AlarmPermissionPromptDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    // Persists across dialog re-opens AND app restarts — needed because
    // shouldShowRequestPermissionRationale() alone can't tell "never asked
    // yet" apart from "asked once and permanently denied"; both return
    // false. We only trust that signal once we know for certain we asked.
    val prefs = remember {
        context.getSharedPreferences("alarm_permission_prompt", Context.MODE_PRIVATE)
    }

    var notifGranted by remember { mutableStateOf(AlarmPermissions.hasNotificationPermission(context)) }
    var exactAlarmGranted by remember { mutableStateOf(AlarmPermissions.hasExactAlarmPermission(context)) }
    var batteryExempt by remember { mutableStateOf(AlarmPermissions.isIgnoringBatteryOptimizations(context)) }
    var fullScreenGranted by remember { mutableStateOf(AlarmPermissions.hasFullScreenIntentPermission(context)) }

    // BUG FIX: if the user denied the notification permission on an earlier
    // run (very likely after a few rounds of testing), Android permanently
    // stops showing its own permission dialog — requestPermissionLauncher.launch()
    // then silently does NOTHING: no dialog, no callback change, no error.
    // The "Allow" button just looked broken. We now detect that state and
    // swap the button to "Open Settings" instead, which is the only way to
    // re-grant a permanently-denied permission.
    var notifPermanentlyDenied by remember {
        mutableStateOf(
            !notifGranted &&
                prefs.getBoolean("notif_requested_before", false) &&
                activity != null &&
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ),
        )
    }

    // Re-check whenever the user comes back from a system Settings page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = AlarmPermissions.hasNotificationPermission(context)
                exactAlarmGranted = AlarmPermissions.hasExactAlarmPermission(context)
                batteryExempt = AlarmPermissions.isIgnoringBatteryOptimizations(context)
                fullScreenGranted = AlarmPermissions.hasFullScreenIntentPermission(context)
                if (notifGranted) notifPermanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        if (!granted && activity != null) {
            notifPermanentlyDenied = !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    val allGood = notifGranted && exactAlarmGranted && batteryExempt && fullScreenGranted

    // BUG FIX: on MIUI, "allGood" (every standard-Android permission granted)
    // used to produce a flat "You're all set" — but MIUI's own Autostart /
    // Battery saver / pop-up-window toggles sit entirely outside the
    // permissions Android lets an app query, so "all good" on stock Android
    // checks can still mean the alarm never rings. Never claim full success
    // on a MIUI device; always show the three unverifiable MIUI rows instead
    // of a falsely reassuring "all set" message.
    val allGoodOverall = allGood && !AlarmPermissions.isMiuiDevice()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allGoodOverall) "You're all set" else "Make this alarm actually ring") },
        text = {
            Column {
                Text(
                    text = when {
                        allGoodOverall ->
                            "Notifications, exact timing, and battery optimization are all set up — this and future alarms will fire on time."
                        allGood ->
                            "Standard Android permissions are all granted — but MIUI has its own extra toggles Android can't check for us. Please confirm these three below are actually on:"
                        else ->
                            "Your phone can silently block scheduled alarms unless a few things are allowed. Takes a few seconds:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                if (!allGood) {
                    PermissionRow(
                        label = "Notifications",
                        detail = if (notifPermanentlyDenied)
                            "Blocked earlier — Android won't ask again automatically. Turn it on in Settings."
                        else
                            "Required to show any reminder at all.",
                        granted = notifGranted,
                        actionLabel = if (notifPermanentlyDenied) "Open Settings" else "Allow",
                        onFix = {
                            if (notifPermanentlyDenied) {
                                openAppDetailsSettings(context)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                prefs.edit().putBoolean("notif_requested_before", true).apply()
                                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    PermissionRow(
                        label = "Exact alarm timing",
                        detail = "Without this, reminders can arrive up to 15 minutes late.",
                        granted = exactAlarmGranted,
                        actionLabel = "Fix",
                        onFix = { openExactAlarmSettings(context) },
                    )
                    PermissionRow(
                        label = "Battery optimization",
                        detail = "Xiaomi, Samsung, Oppo, Honor and similar phones can kill scheduled alarms unless this app is exempted.",
                        granted = batteryExempt,
                        actionLabel = "Fix",
                        onFix = { requestBatteryOptimizationExemption(context) },
                    )
                    if (Build.VERSION.SDK_INT >= 34) {
                        PermissionRow(
                            label = "Full screen alarm",
                            detail = "Without this (Android 14+), the reminder shows as a quiet notification instead of an actual ringing alarm screen.",
                            granted = fullScreenGranted,
                            actionLabel = "Fix",
                            onFix = { openFullScreenIntentSettings(context) },
                        )
                    }
                }

                if (AlarmPermissions.isMiuiDevice()) {
                    // Autostart and Battery saver have no public API to check
                    // their current state — shown as standing reminders
                    // rather than pass/fail rows, since MIUI can silently
                    // kill alarms even when every standard Android
                    // permission above is granted.
                    PermissionRow(
                        label = "MIUI Autostart",
                        detail = "Xiaomi/Redmi/POCO phones need this enabled separately, or scheduled alarms can be killed even with everything else allowed.",
                        granted = false,
                        actionLabel = "Check",
                        onFix = { openMiuiAutostartSettings(context) },
                    )
                    PermissionRow(
                        label = "MIUI Battery saver",
                        detail = "A SEPARATE toggle from battery optimization above — set it to \"No restrictions\", or MIUI can still kill this alarm in the background.",
                        granted = false,
                        actionLabel = "Check",
                        onFix = { openMiuiBatterySaverSettings(context) },
                    )
                    PermissionRow(
                        label = "MIUI \"Display pop-up windows\"",
                        detail = "THE MOST COMMON reason it still won't ring even with Autostart + Battery saver fixed: under Other permissions, turn on \"Display pop-up windows while running in the background\".",
                        granted = false,
                        actionLabel = "Check",
                        onFix = { openMiuiPopupPermissionSettings(context) },
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (!notifGranted) {
                            // Previously this button silently did nothing when
                            // notification permission was missing — notify()
                            // is a no-op without it. Tell the user why instead
                            // of letting it look broken.
                            Toast.makeText(
                                context,
                                if (notifPermanentlyDenied)
                                    "Turn on Notifications for this app in Settings first, then try again."
                                else
                                    "Allow notifications above first, then try again.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            // BUG FIX: this used to call show() and only
                            // toast on a false result — meaning SUCCESS
                            // produced no feedback at all. If posting the
                            // notification silently threw (bad icon, a null
                            // Uri, anything), the old show() had no
                            // try/catch around its body, so the exception
                            // could vanish with zero visible symptom other
                            // than "I tapped it and nothing happened."
                            // showResult() now wraps that body in
                            // runCatching and reports exactly which of the
                            // three things occurred, and every branch below
                            // shows a toast — there is no more silent case.
                            when (val result = HabitCheckInNotifier.showResult(
                                context = context,
                                habitId = "diagnostic_test",
                                habitName = "Test reminder",
                                reschedule = false,
                            )) {
                                is HabitCheckInNotifier.NotifyResult.Posted -> {
                                    Toast.makeText(
                                        context,
                                        "Test reminder sent — check your notification shade now.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                is HabitCheckInNotifier.NotifyResult.PermissionMissing -> {
                                    Toast.makeText(
                                        context,
                                        "Notification permission isn't actually granted — try the Allow button above again.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                is HabitCheckInNotifier.NotifyResult.Failed -> {
                                    // Surfaces the real exception so it can be
                                    // reported instead of just "it's broken."
                                    Toast.makeText(
                                        context,
                                        "Test reminder failed: ${result.error}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send a test reminder now")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(if (allGoodOverall) "Done" else "Not now") }
        },
    )
}

@Composable
private fun PermissionRow(
    label: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    onFix: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (!granted) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!granted) {
            TextButton(onClick = onFix) { Text(actionLabel) }
        }
    }
}

/** Opens the system "Alarms & reminders" page for this app (API 31+). */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }.onFailure {
        openAppDetailsSettings(context)
    }
}

/**
 * Requests the "ignore battery optimizations" exemption directly. This is
 * the single most impactful fix for "alarm just didn't ring" on Xiaomi,
 * Samsung, Oppo/Vivo, and Honor devices.
 */
private fun requestBatteryOptimizationExemption(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            openAppDetailsSettings(context)
        }
    }
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

/** Opens the system "Full screen intent" toggle for this app (API 34+ only). */
private fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 34) return
    runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }.onFailure {
        openAppDetailsSettings(context)
    }
}
