package com.rork.mindsetframestracker.notifications

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rork.mindsetframestracker.ui.screens.HabitTimerOptionsSheet
import com.rork.mindsetframestracker.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * True when this ring is a habit reminder — i.e. when the timer/stopwatch
     * choice belongs to this screen. Habit reminders only: a timer completion
     * has nothing to choose, and must never offer to start a second run.
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

            // The habit's own alarm is ringing, so this screen owns the
            // timer/stopwatch choice and shows it automatically once the ring
            // has started — no tap required.
            timerOptionsReady = true

            // ── The one-shot timer/stopwatch request ────────────────────────
            // Written here from the Intent extras, before anything else touches
            // the ring, so the choice is already waiting when the popup opens.
            // A plain SharedPreferences write: deliberately NO repository read
            // and no blocking on this path, because decoding the whole app blob
            // on the main thread inside onCreate is what used to delay — and
            // occasionally kill — the ring itself. (It was wrapped in
            // runBlocking(Dispatchers.IO) here before.) The newest ring wins,
            // which is what HabitTimerRequests.request documents.
            if (habitId.isNotEmpty()) {
                HabitTimerRequests.request(
                    context = this,
                    habitId = habitId,
                    habitName = habitName,
                    iconId = null,
                )
            }
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
                        onDismiss = { finishRinging() },
                        onSnooze = { snoozeAndFinish() },
                        // A fallback for reachability only: the popup below
                        // appears on its own the moment the ring starts. This
                        // button routes to the HABITS tab (not the timer
                        // screen) so the habit's icon — the thing the choice
                        // belongs to — is what the user lands on.
                        onTimerOptions = {
                            NavRequests.request(NavRequests.ROUTE_HABITS)
                            finishRinging()
                        },
                    )

                    // ── The timer / stopwatch popup ─────────────────────
                    // Shown automatically the instant this alarm rings — the
                    // same moment its notification appears — so the user picks
                    // timer or stopwatch without tapping anything. It is
                    // anchored to the ringing habit's own icon.
                    if (timerOptionsReady) {
                        val context = LocalContext.current
                        val request = remember { HabitTimerRequests.peek(context) }
                        var dismissed by remember { mutableStateOf(false) }
                        if (request != null && !dismissed) {
                            HabitTimerOptionsSheet(
                                habitId = request.habitId,
                                habitName = request.habitName.ifBlank { habitName },
                                habitIconId = request.iconId ?: resolvedIconId,
                                onOpenTimerScreen = {
                                    // TimerController.start already opened the
                                    // run; route the app at the timer screen
                                    // so the user lands on it as the ring ends.
                                    NavRequests.request(NavRequests.ROUTE_TIMER)
                                    finishRinging()
                                },
                                onDismiss = { dismissed = true },
                            )
                            // Clear the on-disk flag the moment the choice is
                            // shown, so it can never come back: a
                            // recomposition, a resume, a navigation or a
                            // reboot all find the request already consumed.
                            // The sheet stays on screen from the value already
                            // read, so this ring still shows it exactly once.
                            LaunchedEffect(request.habitId) {
                                HabitTimerRequests.consume(context)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A second alarm arriving while this screen is already up is delivered HERE
     * rather than as a new Activity, because this Activity is declared
     * `singleInstance` \u2014 the OS reuses the running instance.
     *
     * Without this override the screen kept showing the FIRST alarm's habit and
     * name while a *different* alarm was ringing, and \u2014 because the
     * timer/stopwatch request is written from the Intent \u2014 the new ring's
     * timer/stopwatch popup was never requested at all. Recreating re-runs
     * onCreate against the new Intent, so the screen, the ring and the one-shot
     * request all describe the alarm that is actually ringing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
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