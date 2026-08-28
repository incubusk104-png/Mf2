package com.rork.mindsetframestracker.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Battery percentage below which background sync pauses to conserve energy. */
const val LOW_BATTERY_THRESHOLD = 20

/** Reads the low-power state from a sticky battery intent. */
private fun isLowPower(intent: Intent?): Boolean {
    if (intent == null) return false
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return false
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val percent = level * 100 / scale
    // While charging, energy conservation isn't needed — sync stays active.
    return !charging && percent < LOW_BATTERY_THRESHOLD
}

/**
 * True when the battery is below the low-power threshold and not charging.
 * Non-Compose entry point used by sync logic (e.g. from the ViewModel).
 */
fun isBatteryLow(context: Context): Boolean {
    val intent = context.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    )
    return isLowPower(intent)
}

/**
 * Observes the low-power state as Compose state. Emits immediately with the
 * current status, then updates live as the battery level or charger changes.
 */
@Composable
fun rememberIsBatteryLow(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = isBatteryLow(context), context) {
        val flow = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    trySend(isLowPower(intent))
                }
            }
            val sticky = context.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            trySend(isLowPower(sticky))
            awaitClose { context.unregisterReceiver(receiver) }
        }
        flow.collect { value = it }
    }
}
