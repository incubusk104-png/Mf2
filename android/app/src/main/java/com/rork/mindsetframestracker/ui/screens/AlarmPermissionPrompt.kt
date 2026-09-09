package com.rork.mindsetframestracker.ui.screens

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
        }.getOrDefault(true)
    }

    fun hasFullScreenIntentPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    fun allGranted(context: Context): Boolean =
        hasNotificationPermission(context) &&
            hasExactAlarmPermission(context) &&
            isIgnoringBatteryOptimizations(context) &&
            hasFullScreenIntentPermission(context)

    fun isMiuiDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("POCO", ignoreCase = true)
}

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

@Composable
fun AlarmPermissionPromptDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val prefs = remember {
        context.getSharedPreferences("alarm_permission_prompt", Context.MODE_PRIVATE)
    }

    var notifGranted by remember { mutableStateOf(AlarmPermissions.hasNotificationPermission(context)) }
    var exactAlarmGranted by remember { mutableStateOf(AlarmPermissions.hasExactAlarmPermission(context)) }
    var batteryExempt by remember { mutableStateOf(AlarmPermissions.isIgnoringBatteryOptimizations(context)) }
    var fullScreenGranted by remember { mutableStateOf(AlarmPermissions.hasFullScreenIntentPermission(context)) }

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
                            Toast.makeText(
                                context,
                                if (notifPermanentlyDenied)
                                    "Turn on Notifications for this app in Settings first, then try again."
                                else
                                    "Allow notifications above first, then try again.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
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
