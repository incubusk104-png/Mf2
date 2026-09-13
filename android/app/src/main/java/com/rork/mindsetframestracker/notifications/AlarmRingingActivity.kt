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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.HabitIconCatalog
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.TimerCompletionEvent
import com.rork.mindsetframestracker.ui.navigation.NavRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    /**
     * The timer event being rung, or null when this is a habit reminder.
     *
     * `habitId` / `habitName` are deliberately NOT `lateinit`: a timer has no
     * habit, and a `lateinit` they were is exactly what turned the early
     * [finish] below into an `UninitializedPropertyAccessException` during
     * teardown. Empty strings keep every downstream path safe.
     */
    private var ringingEvent: TimerCompletionEvent? = null
    private var habitId: String = ""
    private var habitName: String = ""
    private var ringingSubtitle: String = "Time for your habit"
    /**
     * The ringing habit's own catalog artwork, or null for a timer
     * completion.
     *
     * The alarm screen used to draw one generic alarm glyph for
     * everything. Showing the habit's own icon is what makes this read as
     * *that habit's* alarm rather than an anonymous system alarm — and it
     * is the same artwork the timer/stopwatch options sheet draws, so the
     * ring and the choice that follows it look like one continuous flow.
     */
    private var habitIconRes: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Identify WHAT is ringing before anything else. A timer-driven launch
        // carries the event extras (see TimerNotifier.applyEventExtras), not the
        // habit ones; requiring a habitId here made every timer alarm bail out
        // of onCreate immediately, so it never rang.
        val event = TimerNotifier.eventFromIntent(intent)
        if (event != null) {
            ringingEvent = event
            habitId = event.habitId.orEmpty()
            habitName = TimerNotifier.ringTitle(event)
            ringingSubtitle = TimerNotifier.ringSubtitle(event)
        } else {
            habitId = intent.getStringExtra("habitId") ?: run { finish(); return }
            habitName = intent.getStringExtra("habitName") ?: "Habit"

            // Resolve this habit's own icon from the saved data. Done on IO
            // because the repository reads DataStore; failure just falls back
            // to the generic alarm glyph rather than blocking the ring.
            habitIconRes = runCatching {
                runBlocking(Dispatchers.IO) {
                    MindsetRepository(this@AlarmRingingActivity)
                        .load()
                        .habits
                        .firstOrNull { it.id == habitId }
                        ?.iconId
                        ?.let { HabitIconCatalog.byId(it)?.drawableRes }
                }
            }.getOrNull()

            // ── Arm the one-shot timer/stopwatch options ───────────────
            // The user asked for the choice to appear "once the alarm was
            // ringing and notified", anchored to the habit icon. The alarm
            // rang in a process that may have no UI and this screen is
            // dismissible, so the request is left on disk for the Habits
            // screen to pick up and consume exactly once — it survives this
            // activity being closed, the app being backgrounded, and a
            // reboot. Writing it here (rather than only in the notifier)
            // covers the ringing screen being launched straight from the
            // full-screen intent.
            HabitTimerRequests.request(
                context = this,
                habitId = habitId,
                habitName = habitName,
                iconId = runCatching {
                    runBlocking(Dispatchers.IO) {
                        MindsetRepository(this@AlarmRingingActivity)
                            .load()
                            .habits
                            .firstOrNull { it.id == habitId }
                            ?.iconId
                    }
                }.getOrNull(),
            )
        }

        showOverLockScreen()
        startRinging()
        autoStopHandler.postDelayed(autoStopRunnable, AUTO_STOP_MILLIS)

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlarmRingingScreen(
                        habitName = habitName,
                        subtitle = ringingSubtitle,
                        habitIconRes = habitIconRes,
                        // Snoozing a timer is meaningless (there is no
                        // "later" for a completed timer), so it is a habit-only
                        // affordance.
                        showSnooze = ringingEvent == null,
                        onDismiss = { finishRinging() },
                        onSnooze = { snoozeAndFinish() },
                        // A ringing habit is the moment to start timing it.
                        // Routed to the HABITS tab, not the timer screen: the
                        // timer/stopwatch options live inside the habit's own
                        // icon, where this ring's one-shot request is already
                        // waiting to be consumed.
                        onTimerOptions = {
                            NavRequests.request(NavRequests.ROUTE_HABITS)
                            finishRinging()
                        },
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
        // Cancel the notification that raised this ring: the timer's own event id
        // for a timer, the habit's id for a reminder. A timer has no habit, so
        // guard rather than compute a meaningless id from an empty string.
        val event = ringingEvent
        if (event != null) {
            manager.cancel(TimerNotifier.notificationId(event.eventId))
        } else if (habitId.isNotEmpty()) {
            manager.cancel(HabitCheckInNotifier.notificationId(habitId))
        }
    }

    private fun finishRinging() {
        stopRinging()
        finish()
    }

    private fun snoozeAndFinish() {
        // Snooze is a habit-reminder affordance and its button is hidden for a
        // timer; this guard keeps a stray call from snoozing an empty id.
        if (ringingEvent != null || habitId.isEmpty()) {
            finishRinging()
            return
        }
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
    subtitle: String,
    habitIconRes: Int?,
    showSnooze: Boolean,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onTimerOptions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(PaddingValues(24.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The habit's own artwork when we know it, otherwise the generic
        // alarm glyph — a timer completion has no habit to draw.
        if (habitIconRes != null) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = habitIconRes),
                    contentDescription = habitName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(58.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Alarm,
                contentDescription = null,
                modifier = Modifier.height(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = habitName,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("Dismiss") }

            if (showSnooze) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Snooze 5 min") }

                // Habit alarms only: the timers belong to the habit, so the
                // choice is offered here, from the habit's own alarm.
                OutlinedButton(
                    onClick = onTimerOptions,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Timer / stopwatch") }
            }
        }
    }
}
