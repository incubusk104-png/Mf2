package com.rork.mindsetframestracker.notifications

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.ui.theme.AppTheme

/**
 * Shown via a notification's full-screen intent so a habit reminder behaves
 * like an actual alarm clock, not a heads-up notification that a phone in
 * silent/Do-Not-Disturb/Bedtime mode can swallow without a sound.
 *
 * Rings on the ALARM audio stream (separate from the notification/ringer
 * stream most silence toggles mute) and vibrates in a loop until the user
 * dismisses or snoozes it, or [AUTO_STOP_MILLIS] elapses.
 */
class AlarmRingingActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val autoStopHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { finishRinging() }

    private lateinit var habitId: String
    private lateinit var habitName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        habitId = intent.getStringExtra("habitId") ?: run { finish(); return }
        habitName = intent.getStringExtra("habitName") ?: "Habit"

        showOverLockScreen()
        startRinging()
        autoStopHandler.postDelayed(autoStopRunnable, AUTO_STOP_MILLIS)

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlarmRingingScreen(
                        habitName = habitName,
                        onDismiss = { finishRinging() },
                        onSnooze = { snoozeAndFinish() },
                    )
                }
            }
        }
    }

    /** Ensures the alarm UI appears even from a locked screen with the display off. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun startRinging() {
        runCatching {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmRingingActivity, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        }

        runCatching {
            val pattern = longArrayOf(0, 500, 500)
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRinging() {
        autoStopHandler.removeCallbacks(autoStopRunnable)
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(HabitCheckInNotifier.notificationId(habitId))
    }

    private fun finishRinging() {
        stopRinging()
        finish()
    }

    private fun snoozeAndFinish() {
        stopRinging()
        val snoozeIntent = Intent(this, HabitSnoozeReceiver::class.java).apply {
            putExtra("habitId", habitId)
            putExtra("habitName", habitName)
        }
        sendBroadcast(snoozeIntent)
        finish()
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    companion object {
        /** Stop ringing on its own after this long, same as most alarm clocks. */
        private const val AUTO_STOP_MILLIS = 3L * 60L * 1000L
    }
}

@Composable
private fun AlarmRingingScreen(
    habitName: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(PaddingValues(24.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Alarm,
            contentDescription = null,
            modifier = Modifier.height(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = habitName,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Text(
            text = "Time for your habit",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("Dismiss") }

            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Snooze 5 min") }
        }
    }
}
