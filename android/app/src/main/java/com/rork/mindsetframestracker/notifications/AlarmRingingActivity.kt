package com.rork.mindsetframestracker.notifications

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rork.mindsetframestracker.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shown via a notification's full-screen intent so a habit reminder behaves
 * like an actual alarm clock, not a heads-up notification that a phone in
 * silent/Do-Not-Disturb/Bedtime mode can swallow without a sound.
 *
 * The ring's **sound and vibration are owned by [AlarmRingService]**, not by
 * this screen — see that class for why. This Activity is only the view of an
 * alarm that is already ringing, which is what keeps the ring alive when this
 * screen cannot launch (Android 14+ revokes USE_FULL_SCREEN_INTENT by default).
 */
class AlarmRingingActivity : ComponentActivity() {

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

    /**
     * True when this ring is a habit reminder — i.e. when this screen should
     * resolve the habit's own artwork for it. Habit reminders only: a timer
     * completion has no habit to draw.
     */
    private var timerOptionsReady = false

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

            // This is a habit's own alarm ringing, so this screen resolves that
            // habit's artwork below.
            timerOptionsReady = true
        }

        showOverLockScreen()
        // The ring is owned by AlarmRingService, NOT by this screen. The audio
        // used to be created here — and only existed if this Activity was
        // actually allowed to launch, which on Android 14+ silently degrades to
        // "notification shows, nothing rings" whenever USE_FULL_SCREEN_INTENT
        // isn't granted. Starting it here as well is harmless (the service is
        // idempotent) and keeps the ring alive if the screen is recreated.
        AlarmRingService.start(
            this,
            habitId = habitId.ifEmpty { null },
            eventId = ringingEvent?.eventId,
        )
        autoStopHandler.postDelayed(autoStopRunnable, AUTO_STOP_MILLIS)

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Resolve the ringing habit's own catalog artwork AFTER the
                    // first frame, on IO. This is exactly the repository read
                    // that used to sit in onCreate as runBlocking(Dispatchers.IO)
                    // — on the main thread, before the alarm was even allowed to
                    // ring. Moved here it can neither delay nor kill the ring;
                    // the habit's own icon simply appears a beat later, and the
                    // generic glyph covers the gap.
                    var resolvedIconId by remember { mutableStateOf<String?>(null) }
                    if (timerOptionsReady) {
                        LaunchedEffect(habitId) {
                            resolvedIconId = withContext(Dispatchers.IO) {
                                runCatching {
                                    MindsetRepository(this@AlarmRingingActivity)
                                        .load()
                                        .habits
                                        .firstOrNull { it.id == habitId }
                                        ?.iconId
                                }.getOrNull()
                            }
                        }
                    }
                    AlarmRingingScreen(
                        habitName = habitName,
                        subtitle = ringingSubtitle,
                        habitIconRes = habitIconRes
                            ?: resolvedIconId?.let { HabitIconCatalog.byId(it)?.drawableRes },
                        // Snoozing a timer is meaningless (there is no
                        // "later" for a completed timer), so it is a habit-only
                        // affordance.
                        showSnooze = ringingEvent == null,
                        onStop = { finishRinging() },
                        onSnooze = { snoozeAndFinish() },
                    )

                    // NOTE: the timer/stopwatch popup is deliberately NOT here.
                    // It is raised automatically at the app root by
                    // HabitTimerOptionsHost in AppNavigation, from a one-shot
                    // request written the instant the alarm reaches the user
                    // (HabitReminderReceiver). Hosting it on this screen made it
                    // depend on a grant the user had no reason to have given:
                    // Android 14+ revokes USE_FULL_SCREEN_INTENT by default, so
                    // the notification posts with no full-screen intent and this
                    // Activity never launches at all — the alarm rang and the
                    // choice never appeared.
                }
            }
        }
    }

    /**
     * A second alarm arriving while this screen is already up is delivered HERE
     * rather than as a new Activity, because this Activity is declared
     * `singleInstance` — the OS reuses the running instance.
     *
     * Without this override the screen kept showing the FIRST alarm's habit and
     * name while a *different* alarm was ringing, and — because the
     * timer/stopwatch request is written from the Intent — the new ring's
     * timer/stopwatch popup was never requested at all. Recreating re-runs
     * onCreate against the new Intent, so the screen and the ring both describe
     * the alarm that is actually ringing.
     */
    override fun onNewIntent(intent: Intent) {
        runCatching {
            super.onNewIntent(intent)
            setIntent(intent)
            recreate()
        }.onFailure { Log.w(TAG, "Could not refresh the ringing screen for a new alarm", it) }
    }

    /**
     * Ensures the alarm UI appears even from a locked screen with the display off.
     *
     * Guarded as a whole: this runs at the very top of `onCreate`, on an
     * alarm-triggered cold start, and `requestDismissKeyguard` in particular is
     * allowed to throw when the keyguard is in a state it cannot service. None
     * of it is worth the process.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                keyguardManager?.requestDismissKeyguard(this, null)
            }.onFailure { Log.w(TAG, "Could not take over the lock screen", it) }
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

    // NOTE: the audio (MediaPlayer on the ALARM stream) and the looping
    // vibration used to live here as startRinging(). They moved to
    // AlarmRingService so the ring no longer depends on this Activity being
    // launched — and so the synchronous prepare() of the ringtone stops blocking
    // the main thread during an alarm-triggered cold start.

    private fun stopRinging() {
        autoStopHandler.removeCallbacks(autoStopRunnable)
        // Silence the service that owns the audio.
        AlarmRingService.stop(this)
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
        // Guarded: onDestroy is a lifecycle callback, so a throw here is another
        // process-killing path — and it runs while the ring is being torn down.
        runCatching { stopRinging() }
            .onFailure { Log.w(TAG, "Failed to stop ringing cleanly", it) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingingActivity"

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
    onStop: () -> Unit,
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
            // ── The manual "Stop alarm" button ──────────────────────────────────
            // Deliberately the primary action, and deliberately NOT a plain
            // screen close: the ring audio is owned by AlarmRingService, so
            // merely finishing this Activity would leave the alarm playing in the
            // background. This stops the MediaPlayer, cancels the repeating
            // vibration, tears the service down and clears both notifications.
            // Every step is idempotent, so a double tap, or a race with the
            // 3-minute auto-stop, is harmless.
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop alarm") }

            if (showSnooze) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Snooze 5 min") }
            }

            // NOTE: there is deliberately no "Timer / stopwatch" button here.
            // The choice belongs to the habit's own icon, not to this screen,
            // and it must arrive on its own without the user tapping anything:
            // it is raised automatically at the app root by
            // HabitTimerOptionsHost the moment the ring's request is written
            // (see HabitReminderReceiver). A button here would also be
            // unreachable in the very case that matters most — this Activity is
            // launched through the notification's full-screen intent, which
            // Android 14+ does not grant by default, so it often never appears
            // at all.
        }
    }
}
