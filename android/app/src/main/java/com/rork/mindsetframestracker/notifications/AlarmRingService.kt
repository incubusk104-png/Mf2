package com.rork.mindsetframestracker.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rork.mindsetframestracker.MainActivity
import com.rork.mindsetframestracker.R

/**
 * The **single source of alarm audio** for the whole app.
 *
 * ## Why the ring had to move out of [AlarmRingingActivity]
 *
 * The ringing screen used to own the ring itself: it created a [MediaPlayer]
 * and called the *synchronous* `prepare()` in `onCreate`, on the main thread.
 * That was wrong in three distinct ways, and together they are why "the alarm
 * never actually rings" survived several rounds of scheduling fixes:
 *
 * 1. **The ring depended on that Activity launching at all.** From Android 14
 *    (API 34) `USE_FULL_SCREEN_INTENT` is *not* auto-granted to apps whose
 *    primary purpose isn't alarms/calls \u2014 it is revoked by default and must be
 *    granted under Settings \u2192 Special app access, which is a *different*
 *    screen from the Permissions list. [HabitCheckInNotifier] correctly checks
 *    `canUseFullScreenIntent()` and skips attaching the full-screen intent when
 *    it is missing, so the notification still posted \u2014 but with no ringing
 *    screen and, because the audio lived inside that screen, **no sound at all**.
 *    That is precisely "I granted every permission and the notification shows
 *    up, but it never rings". The alarm audio now starts here, straight off the
 *    notification path, so a missing special-access grant can cost the
 *    full-screen UI and never the sound.
 *
 * 2. **Synchronous `prepare()` on the main thread inside `onCreate`.** Decoding
 *    a ringtone from a `content://` provider is real I/O. Doing it synchronously
 *    during Activity startup \u2014 at the exact moment the phone is trying to wake
 *    the screen and show the alarm \u2014 blocks the main thread and is an ANR /
 *    force-stop waiting to happen. Here the player is built with
 *    `prepareAsync()` on a service, so nothing on the ring path blocks a frame.
 *
 * 3. **The audio died with the Activity.** If the launch was blocked, delayed or
 *    dismissed, the sound stopped with it. A foreground service keeps ringing
 *    until the user actually acts, and survives the screen being recreated.
 *
 * The Activity is now purely the *view* of a ring: it draws the screen and
 * calls [stop] when the user acts.
 *
 * ## Sound and vibration
 *
 * Plays on [AudioAttributes.USAGE_ALARM] \u2014 the phone's *alarm* volume, which
 * silent mode and Do Not Disturb leave untouched \u2014 and loops until
 * [AUTO_STOP_MILLIS] elapses, the same as any alarm clock. Starts on a
 * foreground service because Android will not let an ordinary background
 * service keep an audio stream alive. Being started from an exact
 * `AlarmManager` alarm is what makes this legal under the Android 12+ background
 * foreground-service-start restrictions: an exact alarm grants the app a
 * short exemption window.
 */
class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /**
     * True once [stopRinging] has released the player.
     *
     * Read by the prepared-listener below. The listener fires late (on the main
     * thread, after the ringtone has been decoded), by which time the auto-stop
     * or a "Dismiss" tap may already have released the player — and calling
     * `start()` on a released MediaPlayer throws.
     */
    @Volatile private var released = false
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelfSafely() }

    /** The habit (or event) currently ringing, so a stop can clear its notification. */
    private var habitId: String? = null
    private var eventId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Promote to foreground immediately. Android kills a service that was
        // started with startForegroundService() but doesn't post its
        // notification within ~5 seconds \u2014 and this service only ever exists
        // while an alarm is ringing, so there is no earlier moment to do it.
        createChannel()
        // Guarded: an exception thrown out of a service lifecycle callback is
        // exactly what the OS turns into its "app keeps stopping" process kill.
        // buildOngoingNotification() is the only non-trivial work here, so if it
        // ever fails the worthwhile thing to lose is a silent ongoing
        // notification — never the audible ring or the alarm notification that
        // HabitCheckInNotifier / TimerNotifier have already posted.
        runCatching {
            startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
        }.onFailure { Log.w(TAG, "Could not promote the ring service to foreground", it) }
    }

    /**
     * Android 15+ (API 35) calls this when the `mediaPlayback` foreground-service
     * timeout elapses. Ringing is a legitimate user-visible reason to keep
     * running, but the safe answer is to stop cleanly rather than let the OS
     * kill the process — a kill here is indistinguishable to the user from
     * "the app crashed while the alarm was ringing".
     */
    override fun onTimeout(startId: Int) {
        stopSelfSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // A second ring while one is already playing (a habit alarm and a timer
        // completing within the same minute, or a redelivered alarm) must restart
        // the ring window rather than stack a second player \u2014 this service is
        // deliberately the only audio source in the app.
        if (mediaPlayer == null) startRinging()

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MILLIS)

        // START_STICKY would restart this with a null Intent and no idea which
        // alarm it belonged to, which is worse than not ringing: the alarm's own
        // re-arm path (HabitAlarmScheduler / TimerAlarmScheduler) already covers
        // recovery.
        return START_NOT_STICKY
    }

    private fun startRinging() {
        // A fresh ring on a reused service instance must be able to start audio
        // again, so clear the released latch before preparing.
        released = false
        runCatching {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.isLooping = true
            // The listener is registered BEFORE setDataSource/prepareAsync, and
            // deliberately on the player object itself rather than inside an
            // `apply { }` block that ends with prepareAsync(). That ordering IS
            // the fix:
            //
            // `MediaPlayer.setDataSource(context, uri)` reaches the ringtone
            // through a content resolver and calls `prepare()` internally, and
            // `prepareAsync()` can likewise complete synchronously when the
            // source resolves instantly. In both cases onPrepared fires during
            // the `apply { }` block — at which point the listener registered at
            // its end is still null. The callback never ran, `start()` was never
            // called, and the player was assigned to the field one line later
            // than the moment it mattered. The result was an alarm that rang
            // silently while every log line claimed success.
            player.setOnPreparedListener { mp ->
                // The ring window may have expired (auto-stop) or the user may
                // have dismissed the alarm while the ringtone was still being
                // decoded. `start()` on a released MediaPlayer throws
                // IllegalStateException on the main thread — an immediate crash
                // while the alarm is ringing. Re-check and decline.
                if (released || mediaPlayer == null) {
                    runCatching { mp.release() }
                    return@setOnPreparedListener
                }
                runCatching { mp.start() }
                    .onFailure { Log.w(TAG, "Could not start the decoded ringtone", it) }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Ringtone playback error (what=$what extra=$extra)")
                true
            }
            player.setDataSource(this@AlarmRingService, alarmUri)
            // Publish the field BEFORE preparing: if onPrepared fires
            // synchronously, the guard above must already see a non-null field.
            mediaPlayer = player
            player.prepareAsync()
        }.onFailure { Log.w(TAG, "Could not start ringtone", it) }

        runCatching {
            val pattern = longArrayOf(0, 500, 500)
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // `repeat = 0` loops the waveform until cancelled \u2014 a one-shot
                // buzz is what let a vibrating alarm go unnoticed.
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }.onFailure { Log.w(TAG, "Could not start vibration", it) }
    }

    private fun stopSelfSafely() {
        handler.removeCallbacks(autoStop)
        stopRinging()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun stopRinging() {
        // Latched BEFORE the reference is dropped: a prepared-listener callback
        // already queued on the main thread must be able to see that this player
        // is being torn down and decline to start it.
        released = true
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    /**
     * Silent, low-importance ongoing notification. It exists only because a
     * foreground service must show one; the *audible* alert is the MediaPlayer
     * on the alarm stream, and the visible alert is the alarm notification
     * posted by [HabitCheckInNotifier] / [TimerNotifier] (plus the full-screen
     * ringing screen when that is granted). Importance LOW keeps this from
     * adding a second sound or a second heads-up on top of them.
     */
    private fun buildOngoingNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            ONGOING_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Alarm ringing")
            .setContentText("Tap to open")
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Alarm ringing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while an alarm is ringing. The alarm's own sound plays separately."
                setShowBadge(false)
                // No sound on the channel: the alarm audio is the MediaPlayer on
                // the ALARM stream above, and a channel sound would double it.
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        private const val TAG = "AlarmRingService"
        private const val ACTION_START = "com.rork.mindsetframestracker.action.RING_START"
        private const val ACTION_STOP = "com.rork.mindsetframestracker.action.RING_STOP"
        private const val EXTRA_HABIT_ID = "habitId"
        private const val EXTRA_EVENT_ID = "eventId"
        private const val CHANNEL_ID = "alarm_ringing"
        private const val ONGOING_NOTIFICATION_ID = 7100

        /** Stop ringing on its own after this long, same as most alarm clocks. */
        const val AUTO_STOP_MILLIS = 3L * 60L * 1000L

        /**
         * Starts (or restarts) the ring. Safe to call from a receiver, a service
         * or a notification path: a second call while already ringing just
         * extends the ring window instead of stacking a second player.
         */
        fun start(
            context: Context,
            habitId: String? = null,
            eventId: String? = null,
        ) {
            runCatching {
                val intent = Intent(context, AlarmRingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_HABIT_ID, habitId)
                    putExtra(EXTRA_EVENT_ID, eventId)
                }
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                // Android 12+ can refuse a background foreground-service start.
                // The alarm notification has already been posted by the caller,
                // so the ring degrades to "notification only" rather than
                // throwing into the receiver that is mid-alarm.
                Log.w(TAG, "Could not start the ring service", it)
            }
        }

        /** Silences the ring. Called when the user dismisses or snoozes. */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AlarmRingService::class.java).apply { action = ACTION_STOP },
                )
            }.onFailure {
                // Falling back to a hard stop is fine: the only thing to lose is
                // the notification, which the caller clears anyway.
                runCatching { context.stopService(Intent(context, AlarmRingService::class.java)) }
            }
        }
    }
}
