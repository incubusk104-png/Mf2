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

    /** True only when every permission that affects alarm delivery is granted. */
    fun allGranted(context: Context): Boolean =
        hasNotificationPermission(context) &&
            hasExactAlarmPermission(context) &&
            isIgnoringBatteryOptimizations(context)
}

/**
 * Auto-triggered dialog — shown right after the user sets an alarm, only
 * if something is actually missing. Walks them through fixing it on the
 * spot instead of leaving it to be discovered (or not) in Settings later.
 */
@Composable
fun AlarmPermissionPromptDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    var notifGranted by remember { mutableStateOf(AlarmPermissions.hasNotificationPermission(context)) }
    var exactAlarmGranted by remember { mutableStateOf(AlarmPermissions.hasExactAlarmPermission(context)) }
    var batteryExempt by remember { mutableStateOf(AlarmPermissions.isIgnoringBatteryOptimizations(context)) }

    // Re-check whenever the user comes back from a system Settings page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = AlarmPermissions.hasNotificationPermission(context)
                exactAlarmGranted = AlarmPermissions.hasExactAlarmPermission(context)
                batteryExempt = AlarmPermissions.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }

    val allGood = notifGranted && exactAlarmGranted && batteryExempt

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allGood) "You're all set" else "Make this alarm actually ring") },
        text = {
            Column {
                Text(
                    text = if (allGood)
                        "Notifications, exact timing, and battery optimization are all set up — this and future alarms will fire on time."
                    else
                        "Your phone can silently block scheduled alarms unless a few things are allowed. Takes a few seconds:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                if (!allGood) {
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
                        detail = "Xiaomi, Samsung, Oppo, Honor and similar phones can kill scheduled alarms unless this app is exempted.",
                        granted = batteryExempt,
                        actionLabel = "Fix",
                        onFix = { requestBatteryOptimizationExemption(context) },
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
                                "Allow notifications above first, then try again.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val posted = HabitCheckInNotifier.show(
                                context = context,
                                habitId = "diagnostic_test",
                                habitName = "Test reminder",
                                reschedule = false,
                            )
                            if (!posted) {
                                Toast.makeText(
                                    context,
                                    "Couldn't send the test reminder — check notification settings.",
                                    Toast.LENGTH_SHORT,
                                ).show()
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
            Button(onClick = onDismiss) { Text(if (allGood) "Done" else "Not now") }
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
