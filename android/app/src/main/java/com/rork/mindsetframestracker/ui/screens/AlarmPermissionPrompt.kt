package com.rork.mindsetframestracker.ui.screens

/*
 * ── WHAT CHANGED IN THIS VERSION ─────────────────────────────────────────
 * The three MIUI rows (Autostart / Battery saver / "Display pop-up windows")
 * used to be hardcoded `granted = false` forever, because Android exposes no
 * public API to read those toggles' real state. That meant the warning
 * triangle NEVER cleared no matter what the user actually set in Settings —
 * that was the reported bug ("I set everything and it still shows warnings").
 *
 * Fix: since we can't verify these three automatically, we let the user
 * confirm them once (after they come back from the Settings screen we send
 * them to) and persist that confirmation in SharedPreferences. Once
 * confirmed, the row shows a green check like everything else. A small
 * "Reset" action is included in case the alarm still doesn't ring and the
 * user wants to re-check their settings.
 *
 * Nothing else about wiring changes — see the original header comment below
 * for how to hook this dialog up.
 *
 * ── HOW TO WIRE IT UP ────────────────────────────────────────────────────
 * 1. Drop this file next to HabitsScreen.kt (replacing the old one).
 * 2. In HabitsScreen.kt, wherever you currently call
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
        // Catches the app-level "Allow notifications" switch being off —
        // this exists on every Android version (it's the ONLY notification
        // gate before API 33) and, separately from the runtime permission,
        // can still be toggled off by the user (or an OEM default) even on
        // API 33+. NotificationManager.notify() no-ops completely and
        // silently when this is off — no exception, nothing in the shade,
        // nothing logged — which is exactly the "I cleared everything but
        // there's nothing in my notification shade at all" symptom.
        val notificationsEnabledAtOsLevel =
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!notificationsEnabledAtOsLevel) return false

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
     * Starting with Android 14 (API 34), `NotificationCompat.Builder
     * .setFullScreenIntent()` is no longer enough on its own. The OS also
     * requires a separate, user-granted "Full screen intent" permission
     * (Settings > Apps > this app > Full screen intent) — off by default for
     * ordinary apps. If it isn't granted, Android doesn't error or fall back
     * loudly; it just silently downgrades the notification to a normal
     * heads-up one, so [AlarmRingingActivity] never launches.
     */
    fun hasFullScreenIntentPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true // permission didn't exist before API 34
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    /** True only when every *verifiable* permission that affects alarm delivery is granted. */
    fun allGranted(context: Context): Boolean =
        hasNotificationPermission(context) &&
            hasExactAlarmPermission(context) &&
            isIgnoringBatteryOptimizations(context) &&
            hasFullScreenIntentPermission(context)

    /**
     * True when the alarm-permission dialog actually has something left to
     * show — either a plain Android permission is missing, OR (critically)
     * this is a non-Pixel OEM skin whose Autostart/Battery-saver/Pop-up
     * steps haven't been confirmed yet. [allGranted] alone used to gate the
     * auto-popup, which meant a MIUI/ColorOS/etc. phone with all the plain
     * Android permissions already granted would never see the prompt at
     * all — exactly the "the permission dialog stopped showing up" bug,
     * even though the OEM-specific steps (the most common real reason a
     * MIUI alarm goes silent) were still pending.
     */
    fun needsAttention(context: Context): Boolean =
        !allGranted(context) || (needsOemConfirmation() && !OemPermissionState.allConfirmed(context))

    fun isMiuiDevice(): Boolean = oemFamily() == OemFamily.MIUI

    /**
     * Which "aggressive background management" family this device belongs
     * to. Android exposes no public API to read any of these OEM toggles,
     * so [OemPermissionState] tracks the user's own confirmation instead —
     * see that object for why.
     */
    fun oemFamily(): OemFamily {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        fun oneOf(vararg names: String) =
            names.any { manufacturer.equals(it, ignoreCase = true) || brand.equals(it, ignoreCase = true) }
        return when {
            oneOf("Xiaomi", "Redmi", "POCO") -> OemFamily.MIUI
            oneOf("OPPO", "Realme", "OnePlus") -> OemFamily.COLOROS
            oneOf("vivo", "iQOO") -> OemFamily.FUNTOUCH
            oneOf("HUAWEI", "Honor") -> OemFamily.HUAWEI_HONOR
            oneOf("samsung") -> OemFamily.SAMSUNG
            oneOf("google") -> OemFamily.STOCK // Pixel — no extra background killing on top of AOSP
            manufacturer.isBlank() || manufacturer.contains("unknown", ignoreCase = true) -> OemFamily.STOCK
            else -> OemFamily.OTHER // Asus, Lenovo, Motorola, Sony, ZTE, Nokia/HMD, etc.
        }
    }

    /**
     * True for every OEM skin known (or reasonably assumed) to layer its own
     * autostart/background-battery restrictions on top of stock Android —
     * i.e. everyone except Pixel/stock. This — not a hardcoded MIUI check —
     * is what decides whether the manual-confirmation rows are shown.
     */
    fun needsOemConfirmation(): Boolean = oemFamily() != OemFamily.STOCK

    /** Short label for the manual-confirmation section's copy. */
    fun oemSkinName(): String = when (oemFamily()) {
        OemFamily.MIUI -> "MIUI/HyperOS"
        OemFamily.COLOROS -> "ColorOS"
        OemFamily.FUNTOUCH -> "Funtouch/OriginOS"
        OemFamily.HUAWEI_HONOR -> "EMUI/MagicOS"
        OemFamily.SAMSUNG -> "One UI"
        OemFamily.OTHER -> "your phone's"
        OemFamily.STOCK -> "Android"
    }
}

enum class OemFamily { MIUI, COLOROS, FUNTOUCH, HUAWEI_HONOR, SAMSUNG, OTHER, STOCK }

/**
 * The three OEM-specific background-management toggles (autostart, battery
 * saver, background pop-up) have no public "is it on?" API on ANY skin, so
 * we track the user's own confirmation instead. This is what makes the
 * warning triangles actually disappear once the user has gone through
 * Settings and turned each one on — previously they were hardcoded to
 * always show, and were only ever asked for on Xiaomi devices.
 */
object OemPermissionState {
    // Prefs file/key names kept as-is (originally MIUI-only) so anyone
    // upgrading from an older build doesn't lose confirmations they already
    // made.
    private const val PREFS = "miui_permission_state"
    private const val KEY_AUTOSTART = "autostart_confirmed"
    private const val KEY_BATTERY_SAVER = "battery_saver_confirmed"
    private const val KEY_POPUP = "popup_confirmed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutostartConfirmed(context: Context) = prefs(context).getBoolean(KEY_AUTOSTART, false)
    fun isBatterySaverConfirmed(context: Context) = prefs(context).getBoolean(KEY_BATTERY_SAVER, false)
    fun isPopupConfirmed(context: Context) = prefs(context).getBoolean(KEY_POPUP, false)

    fun setAutostartConfirmed(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTOSTART, value).apply()

    fun setBatterySaverConfirmed(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BATTERY_SAVER, value).apply()

    fun setPopupConfirmed(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_POPUP, value).apply()

    fun allConfirmed(context: Context) =
        isAutostartConfirmed(context) && isBatterySaverConfirmed(context) && isPopupConfirmed(context)

    /** Lets the user start over if the alarm still doesn't ring after confirming. */
    fun resetAll(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_AUTOSTART, false)
            .putBoolean(KEY_BATTERY_SAVER, false)
            .putBoolean(KEY_POPUP, false)
            .apply()
    }
}

/** Tries each (package, class) candidate in turn; true if any launched. */
private fun tryComponents(context: Context, candidates: List<Pair<String, String>>, extras: Intent.() -> Unit = {}): Boolean =
    candidates.any { (pkg, cls) ->
        runCatching {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                extras()
            }
            context.startActivity(intent)
        }.isSuccess
    }

/** Opens this device's autostart/background-launch manager, whatever OEM it is. */
private fun openOemAutostartSettings(context: Context) {
    val opened = when (AlarmPermissions.oemFamily()) {
        OemFamily.MIUI -> tryComponents(
            context,
            listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.securitycenter.permission.AutoStartManagementActivity",
            ),
        ) { putExtra("extra_pkgname", context.packageName) }
        OemFamily.COLOROS -> tryComponents(
            context,
            listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        )
        OemFamily.FUNTOUCH -> tryComponents(
            context,
            listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        )
        OemFamily.HUAWEI_HONOR -> tryComponents(
            context,
            listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
        )
        else -> false // Samsung/other: no separate autostart manager, battery page below covers it
    }
    if (!opened) openAppDetailsSettings(context)
}

/** Opens this device's "unrestricted battery / no restrictions" page, whatever OEM it is. */
private fun openOemBatterySettings(context: Context) {
    val opened = when (AlarmPermissions.oemFamily()) {
        OemFamily.MIUI -> tryComponents(
            context,
            listOf(
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
            ),
        ) {
            putExtra("package_name", context.packageName)
            putExtra("package_label", "Mindset Frames")
        }
        OemFamily.COLOROS -> tryComponents(
            context,
            listOf(
                "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgauge.PowerConsumptionActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.powerbattery.PowerSaverModeActivity",
            ),
        )
        OemFamily.FUNTOUCH -> tryComponents(
            context,
            listOf(
                "com.vivo.abe" to "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
            ),
        )
        OemFamily.HUAWEI_HONOR -> tryComponents(
            context,
            listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )
        else -> false
    }
    // Every OEM (including Samsung "Sleeping apps" and anything unlisted
    // above) still has the standard battery-optimization exemption request
    // as a fallback — it's the one thing guaranteed to exist everywhere.
    if (!opened) requestBatteryOptimizationExemption(context)
}

/** Opens this device's "display pop-up / background permission" page, whatever OEM it is. */
private fun openOemPopupSettings(context: Context) {
    val opened = when (AlarmPermissions.oemFamily()) {
        OemFamily.MIUI -> tryComponents(
            context,
            listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.permissions.PermissionsEditorActivity",
                "com.miui.securitycenter" to "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
            ),
        ) { putExtra("extra_pkgname", context.packageName) }
        OemFamily.COLOROS -> tryComponents(
            context,
            listOf("com.coloros.safecenter" to "com.coloros.privacypermissionsentry.PermissionTopActivity"),
        )
        OemFamily.HUAWEI_HONOR -> tryComponents(
            context,
            listOf("com.huawei.systemmanager" to "com.huawei.permissionmanager.ui.MainActivity"),
        )
        else -> false // vivo/Samsung/other: this toggle usually lives in the same
        // permission manager as autostart, or doesn't exist as a separate
        // setting — app details is the safest generic landing spot.
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
    val prefs = remember {
        context.getSharedPreferences("alarm_permission_prompt", Context.MODE_PRIVATE)
    }

    var notifGranted by remember { mutableStateOf(AlarmPermissions.hasNotificationPermission(context)) }
    var exactAlarmGranted by remember { mutableStateOf(AlarmPermissions.hasExactAlarmPermission(context)) }
    var batteryExempt by remember { mutableStateOf(AlarmPermissions.isIgnoringBatteryOptimizations(context)) }
    var fullScreenGranted by remember { mutableStateOf(AlarmPermissions.hasFullScreenIntentPermission(context)) }

    // NEW: user-confirmed MIUI toggle state — this is what makes the
    // warnings actually clear once the user has been through Settings.
    var autostartConfirmed by remember { mutableStateOf(OemPermissionState.isAutostartConfirmed(context)) }
    var batterySaverConfirmed by remember { mutableStateOf(OemPermissionState.isBatterySaverConfirmed(context)) }
    var popupConfirmed by remember { mutableStateOf(OemPermissionState.isPopupConfirmed(context)) }

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
    // For the MIUI rows we can't verify the real state, so instead we treat
    // "the user just came back from that Settings screen" as an implicit
    // confirmation prompt: the row switches from "Check" to a small
    // "Mark as done" affordance rather than guessing.
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
    val needsOem = AlarmPermissions.needsOemConfirmation()
    val oemName = AlarmPermissions.oemSkinName()

    // Only truly "all set" once the verifiable Android permissions AND the
    // user's own OEM-skin confirmations (Xiaomi, Oppo, Vivo, Honor/Huawei,
    // Samsung, or any other non-Pixel skin) are both done.
    val allGoodOverall = allGood && (!needsOem || OemPermissionState.allConfirmed(context))

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
                            "Standard Android permissions are all granted — but $oemName has its own extra toggles Android can't check for us. Confirm each one below once you've turned it on:"
                        else ->
                            "Your phone can silently block scheduled alarms unless a few things are allowed. Takes a few seconds:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                if (!allGood) {
                    val notifNeedsSettingsPage = notifPermanentlyDenied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    PermissionRow(
                        label = "Notifications",
                        detail = if (notifNeedsSettingsPage)
                            "Off at the system level — this silently blocks every reminder with no error. Turn it on in Settings."
                        else
                            "Required to show any reminder at all.",
                        granted = notifGranted,
                        actionLabel = if (notifNeedsSettingsPage) "Open Settings" else "Allow",
                        onFix = {
                            if (notifNeedsSettingsPage) {
                                openAppNotificationSettings(context)
                            } else {
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

                if (needsOem) {
                    // These three now behave like real pass/fail rows again —
                    // "Check" opens the OEM Settings page, and once you're
                    // back, tap "Mark as done" to confirm it and clear the
                    // warning for good (persisted, so it stays cleared).
                    ConfirmablePermissionRow(
                        label = "$oemName Autostart",
                        detail = "This phone needs auto-start/background-launch enabled separately, or scheduled alarms can be killed even with everything else allowed.",
                        confirmed = autostartConfirmed,
                        onOpenSettings = { openOemAutostartSettings(context) },
                        onConfirm = {
                            OemPermissionState.setAutostartConfirmed(context, true)
                            autostartConfirmed = true
                        },
                    )
                    ConfirmablePermissionRow(
                        label = "$oemName Battery saver",
                        detail = "A SEPARATE toggle from battery optimization above — set it to \"No restrictions\" / unrestricted, or the OS can still kill this alarm in the background.",
                        confirmed = batterySaverConfirmed,
                        onOpenSettings = { openOemBatterySettings(context) },
                        onConfirm = {
                            OemPermissionState.setBatterySaverConfirmed(context, true)
                            batterySaverConfirmed = true
                        },
                    )
                    ConfirmablePermissionRow(
                        label = "$oemName background pop-up permission",
                        detail = "THE MOST COMMON reason it still won't ring even with Autostart + Battery saver fixed: find \"Display pop-up windows while running in the background\" (or similarly-named) under this app's permissions and turn it on.",
                        confirmed = popupConfirmed,
                        onOpenSettings = { openOemPopupSettings(context) },
                        onConfirm = {
                            OemPermissionState.setPopupConfirmed(context, true)
                            popupConfirmed = true
                        },
                    )

                    if (autostartConfirmed || batterySaverConfirmed || popupConfirmed) {
                        TextButton(
                            onClick = {
                                OemPermissionState.resetAll(context)
                                autostartConfirmed = false
                                batterySaverConfirmed = false
                                popupConfirmed = false
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text("Alarm still not ringing? Reset these checks") }
                    }
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

/**
 * NEW: like [PermissionRow] but for the three unverifiable MIUI toggles.
 * Two actions instead of one: "Check" opens the OEM Settings page, and once
 * the row has been opened at least once, "Mark as done" appears so the user
 * can explicitly confirm it and clear the warning — persisted across app
 * restarts via [OemPermissionState].
 */
@Composable
private fun ConfirmablePermissionRow(
    label: String,
    detail: String,
    confirmed: Boolean,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
) {
    var hasOpenedSettings by remember { mutableStateOf(confirmed) }
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
                imageVector = if (confirmed) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (confirmed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (!confirmed) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!confirmed) {
            if (hasOpenedSettings) {
                TextButton(onClick = onConfirm) { Text("Mark as done") }
            } else {
                TextButton(onClick = {
                    onOpenSettings()
                    hasOpenedSettings = true
                }) { Text("Check") }
            }
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

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .apply { data = Uri.parse("package:${context.packageName}") }
        }
        context.startActivity(intent)
    }.onFailure { openAppDetailsSettings(context) }
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
