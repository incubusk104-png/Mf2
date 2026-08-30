package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.integrations.ScreenTimeMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screen-time habit creation dialog: pick a phone app to monitor and a
 * daily minutes budget ("keep Facebook under 2 hours"). The habit
 * auto-completes each day the measured foreground time stays at or under
 * the budget (see ScreenTimeMonitor + AppViewModel.evaluateScreenTimeHabits).
 */
@Composable
fun ScreenTimeHabitSheet(
    onDismiss: () -> Unit,
    onConfirm: (packageName: String, appLabel: String, limitMinutes: Int) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<ScreenTimeMonitor.MonitorableApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var selectedLabel by remember { mutableStateOf("") }
    var limitMinutes by remember { mutableStateOf(120) }
    var customLimitText by remember { mutableStateOf("") }
    var useCustomLimit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { ScreenTimeMonitor.listMonitorableApps(context) }
    }

    val presetLimits = listOf(30, 60, 120, 180)
    val effectiveLimit = if (useCustomLimit) {
        customLimitText.toIntOrNull()?.coerceIn(5, 1440)
    } else {
        limitMinutes
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.HourglassBottom,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Screen time limit") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Pick an app and a daily budget. The habit completes " +
                        "automatically on days you stay under the limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                val list = apps
                if (list == null) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    ) { CircularProgressIndicator(modifier = Modifier.height(28.dp)) }
                } else {
                    val filtered = remember(list, query) {
                        if (query.isBlank()) list
                        else list.filter { it.label.contains(query.trim(), ignoreCase = true) }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(
                                    selected = selectedPackage == app.packageName,
                                    onClick = {
                                        selectedPackage = app.packageName
                                        selectedLabel = app.label
                                    },
                                )
                                Icon(
                                    imageVector = Icons.Outlined.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Daily limit",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetLimits.forEach { preset ->
                        FilterChip(
                            selected = !useCustomLimit && limitMinutes == preset,
                            onClick = {
                                useCustomLimit = false
                                limitMinutes = preset
                            },
                            label = { Text(formatLimit(preset)) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = useCustomLimit,
                        onClick = { useCustomLimit = true },
                        label = { Text("Custom") },
                    )
                    if (useCustomLimit) {
                        OutlinedTextField(
                            value = customLimitText,
                            onValueChange = { customLimitText = it.filter(Char::isDigit).take(4) },
                            placeholder = { Text("minutes") },
                            singleLine = true,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .weight(1f),
                        )
                    }
                }

                if (!ScreenTimeMonitor.hasPermission(context)) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Heads-up: Android's Usage Access permission is needed to " +
                            "measure app time. You'll be taken to the system settings " +
                            "switch after adding this habit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pkg = selectedPackage ?: return@Button
                    val limit = effectiveLimit ?: return@Button
                    onConfirm(pkg, selectedLabel, limit)
                },
                enabled = selectedPackage != null && effectiveLimit != null,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Add habit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatLimit(minutes: Int): String = when {
    minutes % 60 == 0 -> "${minutes / 60}h"
    minutes > 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}
