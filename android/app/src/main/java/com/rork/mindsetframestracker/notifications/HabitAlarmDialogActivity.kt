package com.rork.mindsetframestracker.notifications

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.alarmClockLabel
import com.rork.mindsetframestracker.data.isHabitDoneOn
import com.rork.mindsetframestracker.ui.components.AlarmHabitLockAction
import com.rork.mindsetframestracker.ui.components.AlarmHabitLockDialog
import com.rork.mindsetframestracker.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hosts the per-habit **Done / Snooze / Skip** dialog and shows it over the lock
 * screen via the reminder notification's full-screen intent.
 *
 * ## Why this is a separate screen from [AlarmRingingActivity]
 *
 * [AlarmRingingActivity] is the alarm's *view* for the whole reminder system:
 * it also serves timer and stopwatch completions, it owns the 3-minute
 * auto-stop, and its Stop/Snooze pair is deliberately generic. The Phase 1
 * habit dialog is a narrower thing with a different job: it asks **what the user
 * did about this habit**, with three answers, one of which writes a result. Were
 * this dialog folded into that screen, the two surfaces would end up sharing one
 * set of buttons, and the habit's "Done" would have to be squeezed into a
 * control whose other owners mean something else by it.
 *
 * ## The dialog decides nothing; the activity decides nothing either
 *
 * Choosing an answer closes this screen and hands the decision to
 * [AlarmRingActionReceiver], which is the single place the three answers turn
 * into state. That is deliberate: this screen may be created on a locked,
 * half-asleep phone and is then torn down, so any write it performed itself
 * would be a write racing its own teardown. It records what the user chose, not
 * what the choice means — exactly the split [AlarmActionDialog] uses.
 *
 * ## Ringing is not owned here
 *
 * The sound and vibration belong to [AlarmRingService]. This screen starts it
 * the way [AlarmRingingActivity] does (harmless and idempotent) so a ring cannot
 * be silent merely because this Activity was the first thing the alarm launched,
 * and it stops it before finishing so the alarm never outlives the answer.
 *
 * ## Already-done habits
 *
 * The plan asks for the dialog to be skipped when the habit is already done. That
 * decision belongs to whoever raises the surface — the notifier/receiver path
 * decides whether to attach the full-screen intent at all, degrading to the
 * ordinary Stop/Snooze notification. This screen still handles the case, because
 * a done-today habit can become true *after* the intent was attached: it then
 * says so instead of asking the same question twice.
 */
class HabitAlarmDialogActivity : ComponentActivity() {

    /** The habit whose alarm is being answered. Empty means "nothing to show". */
    private var habitId: String = ""

    /** Shown before the habit's own resolved line arrives, and if it never does. */
    private var habitName: String = "Habit"

    /** Which of the habit's alarm times is ringing; null when unknown. */
    private var alarmMinutes: Int? = null

    /**
     * Closes this screen the moment the alarm is stopped from anywhere else.
     *
     * The answer buttons all leave through here, but the notification's own
     * "Stop alarm" action does not — and an alarm page left standing over a ring
     * that has already been silenced reads as the stop button half-working. This
     * mirrors [AlarmRingingActivity]'s receiver; it is registered in `onStart`
     * and removed in `onStop` so it can never outlive the visible screen.
     *
     * `RECEIVER_NOT_EXPORTED` is both required from API 33 for a dynamic
     * registration and correct here: only our own process sends this action.
     */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching { if (!isFinishing) finish() }
                .onFailure { Log.w(TAG, "Could not close the habit dialog after the alarm stopped", it) }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            ContextCompat.registerReceiver(
                this,
                stopReceiver,
                IntentFilter(AlarmStopReceiver.ACTION_ALARM_STOPPED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "Could not listen for the alarm-stop broadcast", it) }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stopReceiver) }
            .onFailure { Log.w(TAG, "Could not unregister the alarm-stop receiver", it) }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The habit identity travels on the full-screen intent. Both the
        // constant and its literal are accepted because the ringing intent is
        // built by the notifier (`HabitReminderReceiver.EXTRA_HABIT_ID` is the
        // literal `"habitId"`), and a habitId that failed to resolve here would
        // otherwise leave a blank screen that cannot be answered.
        // `Intent` can be null in the unlikely case of a null read, so the
        // extras are read defensively rather than from a non-null receiver.
        val source: Intent? = intent
        val id = source?.getStringExtra(HabitReminderReceiver.EXTRA_HABIT_ID)
            ?: source?.getStringExtra(LEGACY_EXTRA_HABIT_ID)
            ?: run {
                finish()
                return
            }
        habitId = id
        habitName = source.getStringExtra(HabitReminderReceiver.EXTRA_HABIT_NAME)
            ?: source.getStringExtra(LEGACY_EXTRA_HABIT_NAME)
            ?: DEFAULT_TITLE
        alarmMinutes = source
            .getIntExtra(
                HabitReminderReceiver.EXTRA_ALARM_MINUTES,
                HabitReminderReceiver.NO_ALARM_MINUTES,
            )
            .takeIf { it != HabitReminderReceiver.NO_ALARM_MINUTES }

        showOverLockScreen()
        // See the class note: the ring is owned by the service, and starting it
        // here as well is idempotent and keeps the alarm audible when this
        // screen is the entry point.
        runCatching { AlarmRingService.start(this, habitId = habitId.ifEmpty { null }) }
            .onFailure { Log.w(TAG, "Could not start the ring service for $habitId", it) }

        setContent {
            AppTheme {
                var subtitle by remember { mutableStateOf(DEFAULT_SUBTITLE) }
                var iconId by remember { mutableStateOf<String?>(null) }
                var doneToday by remember { mutableStateOf(false) }

                LaunchedEffect(habitId) {
                    // ONE IO pass resolves all three facts (the artwork, the
                    // habit's own motivating line, and whether today is already
                    // complete) from a single repository read, so the alarm's
                    // cold start gains no extra blocking work.
                    val resolved = withContext(Dispatchers.IO) {
                        runCatching {
                            val data = MindsetRepository(this@HabitAlarmDialogActivity).load()
                            val habit = data.habits.firstOrNull { it.id == habitId }
                            Triple(
                                habit?.iconId,
                                HabitReminderText.lineFor(
                                    context = this@HabitAlarmDialogActivity,
                                    habitId = habitId,
                                    iconId = habit?.iconId,
                                    alarmMinutes = alarmMinutes,
                                ),
                                data.isHabitDoneOn(habitId, Dates.todayKey()),
                            )
                        }.getOrNull()
                    }
                    iconId = resolved?.first
                    // A blank line is left alone so the neutral fallback stands:
                    // an empty sentence is worse than a generic one.
                    resolved?.second?.takeIf { it.isNotBlank() }?.let { subtitle = it }
                    doneToday = resolved?.third ?: false
                }

                AlarmHabitLockDialog(
                    habitName = habitName,
                    subtitle = subtitle,
                    habitIconId = iconId,
                    alarmClockLabel = alarmMinutes?.let { alarmClockLabel(it) },
                    alreadyDone = doneToday,
                    onAction = { answer -> answerAndFinish(answer) },
                )
            }
        }
    }

    /**
     * A second alarm arriving while this screen is already up is delivered here
     * rather than as a new Activity, because this Activity is declared
     * `singleInstance` — the OS reuses the running instance.
     *
     * Recreating re-runs `onCreate` against the new Intent, so the dialog
     * describes the alarm that is actually ringing instead of leaving the first
     * habit's name on screen for a different habit's alarm.
     */
    override fun onNewIntent(intent: Intent) {
        runCatching {
            super.onNewIntent(intent)
            setIntent(intent)
            recreate()
        }.onFailure { Log.w(TAG, "Could not refresh the habit dialog for a new alarm", it) }
    }

    /**
     * Ensures the dialog appears even from a locked screen with the display off.
     *
     * Guarded as a whole: this runs at the top of `onCreate` on an
     * alarm-triggered cold start, and `requestDismissKeyguard` is allowed to
     * throw when the keyguard is in a state it cannot service. None of it is
     * worth the process.
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
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
    }

    /**
     * Records the user's answer, silences the ring, and closes.
     *
     * The effect itself is posted to [AlarmRingActionReceiver] rather than
     * written here, for the reason in the class note: this Activity is a view
     * that is about to be destroyed, and a write racing its own teardown is how
     * an alarm answer gets lost. The in-process [AlarmRingService.stop] is kept
     * as a belt-and-braces silence for the case where the broadcast is refused
     * on an OEM build — the audio must never survive the user's answer.
     */
    private fun answerAndFinish(answer: AlarmHabitLockAction) {
        val action = when (answer) {
            AlarmHabitLockAction.DONE -> AlarmRingActionReceiver.ACTION_HABIT_DONE
            AlarmHabitLockAction.SNOOZE -> AlarmRingActionReceiver.ACTION_HABIT_SNOOZE
            AlarmHabitLockAction.SKIP -> AlarmRingActionReceiver.ACTION_HABIT_SKIP
        }
        val dispatch = Intent(this, AlarmRingActionReceiver::class.java).apply {
            this.action = action
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_NAME, habitName)
            alarmMinutes?.let { putExtra(HabitReminderReceiver.EXTRA_ALARM_MINUTES, it) }
        }
        runCatching { sendBroadcast(dispatch) }
            .onFailure { Log.w(TAG, "Could not dispatch $answer for $habitId", it) }
        runCatching { AlarmRingService.stop(this) }
            .onFailure { Log.w(TAG, "Could not stop the ring after $answer", it) }
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(HabitCheckInNotifier.notificationId(habitId))
        }.onFailure { Log.w(TAG, "Could not clear the reminder notification for $habitId", it) }
        finish()
    }

    companion object {
        private const val TAG = "HabitAlarmDialogActivity"

        /** Neutral heading when the intent carried no name. */
        private const val DEFAULT_TITLE = "Habit"

        /** Neutral subtitle until the habit's own line has been resolved. */
        private const val DEFAULT_SUBTITLE = "Time for your habit"

        /** Literal extra names, accepted for intents built without the constants. */
        private const val LEGACY_EXTRA_HABIT_ID = "habitId"
        private const val LEGACY_EXTRA_HABIT_NAME = "habitName"
    }
}
