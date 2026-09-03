package com.rork.mindsetframestracker.ui.screens

/*
 * ── WHY THIS FILE EXISTS ─────────────────────────────────────────────────
 * Your HabitAlarmScheduler.kt already has exact -> windowed -> inexact
 * fallback logic, and its own doc comments name the real culprit for
 * "alarm didn't ring" reports: OEM battery optimization (MIUI, Samsung,
 * Oppo/Vivo, Honor) silently killing alarms even when SCHEDULE_EXACT_ALARM
 * is granted. Your AndroidManifest.xml already declares the
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission for exactly this reason.
 *
 * The one thing missing anywhere in the app: nothing ever actually shows
 * the user a screen asking them to grant it. This file is that screen —
 * a self-contained Settings card with live status + one-tap fixes for the
 * three things that make reminders unreliable on real devices:
 *   1. Notification permission (Android 13+)
 *   2. Exact alarm permission (Android 12+)
 *   3. Battery optimization exemption (all OEMs, especially the ones above)
 *
 * ── HOW TO ADD IT ────────────────────────────────────────────────────────
 * 1. Drop this file in: android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/
 * 2. In SettingsScreen.kt, inside your notifications section, add:
 *
 *        AlarmReliabilityCard()
 *
 *    Anywhere after your existing reminder-time settings is fine — it's
 *    fully self-contained, no props required.
 * 3. That's it. No manifest changes needed — every permission it asks for
 *    is already declared in your AndroidManifest.xml.
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Settings card that surfaces the three real-device permission gaps that
 * cause habit alarms to silently not fire, with a live status dot and a
 * one-tap fix for each. Re-checks status whenever the screen resumes
 * (e.g. after the user comes back from the system settings page).
 */
@Composable
fun AlarmReliabilityCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var exactAlarmGranted by remember { mutableStateOf(hasExactAlarmPermission(context)) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Re-check every time the user returns to this screen (e.g. after
    // toggling a permission in system Settings and pressing back).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableRefreshEffect(lifecycleOwner) {
        notifGranted = hasNotificationPermission(context)
        exactAlarmGranted = hasExactAlarmPermission(context)
        batteryExempt = isIgnoringBatteryOptimizations(context)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }

    val allGood = notifGranted && exactAlarmGranted && batteryExempt

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allGood)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = if (allGood) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Reminder reliability",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (allGood)
                    "All set — reminders will fire on time, even in the background."
                else
                    "Some phone settings can silently stop reminders from ringing. Fix these so your alarms are reliable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            PermissionRow(
                label = "Notifications",
                detail = "Required to show any reminder at all.",
                granted = notifGranted,
                actionLabel = "Allow",
                onFix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                detail = "Your phone maker (Xiaomi, Samsung, Oppo, Honor, etc.) can kill scheduled alarms unless this app is exempted.",
                granted = batteryExempt,
                actionLabel = "Fix",
                onFix = { requestBatteryOptimizationExemption(context) },
                isLast = true,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    onFix: () -> Unit,
    isLast: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
            Button(onClick = onFix) { Text(actionLabel) }
        }
    }
    if (!isLast) Spacer(Modifier.width(0.dp))
}

/** Re-runs [onResume] every time the host screen returns to RESUMED. */
@Composable
private fun DisposableRefreshEffect(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onResume: () -> Unit,
) {
    LaunchedEffect(lifecycleOwner) {
        // Initial check already happens via remember{} above; this observer
        // handles the "came back from system Settings" case.
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

// ── Permission check + fix helpers ──────────────────────────────────────

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun hasExactAlarmPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return runCatching {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true) // treat unsupported OEMs as "fine" rather than nagging forever
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
        // Some OEMs don't resolve the direct intent — fall back to app details.
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
        // Some OEMs (esp. MIUI) block the direct-request intent entirely —
        // fall back to the general battery optimization list so the user
        // can find and exempt the app manually.
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
