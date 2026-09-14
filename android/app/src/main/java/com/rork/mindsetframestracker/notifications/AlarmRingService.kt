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
 * ## Why the service can no longer take the process down
 *
 * This service only ever exists while an alarm is ringing, so every one of its
 * lifecycle callbacks runs at the worst possible moment. A throw escaping
 * `onCreate` or `onStartCommand` is not a caught error \u2014 for a *started*
 * Service the system turns it into a **process kill**, which is precisely the
 * "keeps stopping" dialog that appears while the alarm rings.
 *
 * Three things here were capable of throwing:
 *
 *  - `startForeground(...)` with a **missing or invalid small icon**. All four
 *    alarm-path notifiers used `R.drawable.splash_icon`, a `<layer-list>` whose
 *    `<bitmap>` layer points at `@mipmap/ic_launcher` \u2014 an `anydpi-v26`
 *    **adaptive icon**, which has no bitmap to decode. `BitmapDrawable` layer
 *    inflation on that is an undefined cast at best and a throw at worst, and
 *    it happened inside `onCreate`, on the ring path. The icon is now the flat
 *    alpha-only vector [R.drawable.ic_notification], and the promotion is
 *    guarded so even a hypothetical failure there costs the ongoing
 *    notification rather than the process. (The other four notifiers in the app
 *    \u2014 `CheckInNotifier`, `StreakAlertNotifier`, `WeeklyRecapNotifier`,
 *    `CompanionNotifier` \u2014 already used a plain vector; the alarm path was the
 *    odd one out.)
 *  - Building the ongoing notification *before* promoting to foreground, which
 *    re-reads state and can throw before the ~5-second
 *    `startForegroundService()` deadline is met \u2014 the classic
 *    `ForegroundServiceDidNotStartInTimeException`, which is also fatal.
 *  - `createChannel()` on the notification-manager service, unguarded.
 *
 * Failing to ring is a bad outcome; killing the app while the user's alarm is
 * supposed to be ringing is a worse one. The audio path is therefore
 * explicitly best-effort and every step is guarded.
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
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelfSafely() }

    /** True once [startForeground] has actually succeeded. */
    private var foregroundActive = false

    /**
     * Set the instant the ring is torn down, BEFORE the player reference is
     * dropped.
     *
     * `@Volatile` because MediaPlayer delivers its prepared/error callbacks on
     * its own thread while [stopRinging] runs on the main thread. Without this
     * latch, a callback already queued when the user pressed Stop could still
     * call `start()` on a released player — an `IllegalStateException` on the
     * main thread, i.e. a crash at the exact moment the alarm is ringing.
     */
    @Volatile
    private var released = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        // A throw here kills the process, so every step is best-effort: the
        // worst acceptable outcome is an alarm that only vibrates, never a crash.
        super.onCreate()
        // Publish the live instance so a stop that arrives from a BROADCAST
        // receiver can tear the ring down directly, without needing a service
        // start of its own. See [stopFromBroadcast] for why that matters.
        instance = this
        runCatching { createChannel() }
            .onFailure { Log.w(TAG, "Could not create the ring channel", it) }

        // Promote to foreground immediately. Android kills a service started
        // with startForegroundService() that doesn't post its notification
        // within ~5 seconds \u2014 and this service only ever exists while an alarm
        // is ringing, so there is no earlier moment to do it. The notification
        // is built inside the guard so a failure while building it neither
        // escapes this callback nor blows the deadline.
        foregroundActive = runCatching {
            startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
            true
        }.onFailure {
            Log.w(TAG, "Could not promote the ring service to foreground", it)
        }.getOrDefault(false)
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

        // If the promotion in onCreate failed, we are still a started service
        // that owes the system a foreground notification; retry once here, which
        // is the last chance to satisfy that without being killed.
        if (!foregroundActive) {
            foregroundActive = runCatching {
                startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
                true
            }.onFailure { Log.w(TAG, "Retry of the foreground promotion failed", it) }
                .getOrDefault(false)
        }

        // START_STICKY would restart this with a null Intent and no idea which
        // alarm it belonged to, which is worse than not ringing: the alarm's own
        // re-arm path (HabitAlarmScheduler / TimerAlarmScheduler) already covers
        // recovery.
        return START_NOT_STICKY
    }

    private fun startRinging() {
        // Reset the teardown latch for THIS ring.
        //
        // It was never cleared, and that is a real bug: [released] is set on the
        // first stop and stayed set for the life of the process, so on the
        // SECOND alarm this service ever hosted, `onPrepared` saw a stale
        // "already torn down" and released the freshly-prepared player instead
        // of starting it. The result: the first alarm of a session rang, and
        // every alarm after it was silent with nothing logged. Clearing it here
        // (before the new player is prepared) makes each ring independent.
        released = false
        runCatching {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            // ── The ordering here is load-bearing, and it was wrong ────────────
            //
            // `setDataSource(context, uri)` resolves the ringtone through the
            // content resolver and calls prepare() internally, and
            // `prepareAsync()` can complete synchronously when the source
            // resolves instantly. So `onPrepared` is able to fire DURING this
            // block. With the listener registered *after* `setDataSource` (as it
            // was), the callback ran while the listener was still null: `start()`
            // was never called, the alarm made no sound, and nothing was thrown
            // or logged — which is why it presented as an alarm that simply
            // never rings.
            //
            // Registering the listener FIRST, and publishing the field BEFORE
            // preparing, removes both that missed start and the opposite failure
            // where the callback arrives after the ring was already torn down.
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.isLooping = true
            player.setOnPreparedListener { prepared ->
                // Declines instead of throwing when the ring was stopped — or the
                // 3-minute auto-stop fired — while the ringtone was still
                // decoding.
                if (released) {
                    runCatching { prepared.release() }
                } else {
                    runCatching { prepared.start() }
                        .onFailure { Log.w(TAG, "Could not start the prepared ringtone", it) }
                }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Ringtone playback error (what=$what extra=$extra)")
                true
            }
            player.setDataSource(this, alarmUri)
            // prepareAsync(), NOT prepare(): this is a service but still the main
            // thread of the process, and a blocking decode here delays the ring
            // itself. The sound simply starts when the ringtone is decoded.
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
        runCatching { handler.removeCallbacks(autoStop) }
        stopRinging()
        runCatching {
            if (foregroundActive) stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { Log.w(TAG, "stopForeground failed", it) }
        foregroundActive = false
        runCatching { stopSelf() }
    }

    private fun stopRinging() {
        // Set BEFORE the references are dropped, so a prepared/error callback
        // already in flight sees the teardown and declines rather than touching a
        // released player.
        released = true
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    override fun onDestroy() {
        // Drop the shared handle FIRST, so a stop arriving during teardown
        // cannot re-enter a service that is already going away.
        instance = null
        // A throw out of onDestroy is another process-killing path, and this one
        // runs while the ring is being torn down.
        runCatching { stopRinging() }
            .onFailure { Log.w(TAG, "Failed to release the ring cleanly", it) }
        // Remove the ongoing "Alarm ringing" notification.
        //
        // This was missing, and it is why an alarm could keep *looking* like it
        // was still ringing after every stop path had run: `stopSelf()` does not
        // clear a foreground notification, so the "Alarm ringing / Tap to open"
        // entry stayed in the shade until the process happened to be killed.
        // Best-effort — a failure here costs a stale notification, never the
        // teardown itself.
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .cancel(ONGOING_NOTIFICATION_ID)
        }.onFailure { Log.w(TAG, "Could not clear the ring notification", it) }
        super.onDestroy()
    }

    /**
     * Silent, low-importance ongoing notification. It exists only because a
     * foreground service must show one; the *audible* alert is the MediaPlayer
     * on the alarm stream, and the visible alert is the alarm notification
     * posted by [HabitCheckInNotifier] / [TimerNotifier] (plus the full-screen
     * ringing screen when that is granted). Importance LOW keeps this from
     * adding a second sound or a second heads-up on top of them.
     *
     * The small icon is deliberately [R.drawable.ic_notification] \u2014 a flat,
     * alpha-only vector. It is NOT `splash_icon` (a `<layer-list>` wrapping an
     * adaptive icon, which `BitmapDrawable` cannot inflate) and NOT
     * `@mipmap/ic_launcher` (a full-colour adaptive icon, wrong for a slot the
     * system renders as a silhouette).
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Alarm ringing")
            .setContentText("Tap to open")
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
        // ── The manual "Stop alarm" action ──────────────────────────────────
        // Reachable straight from the shade, so a ringing alarm can always be
        // silenced without unlocking the phone and without the ringing screen
        // being launchable at all (Android 14+ revokes USE_FULL_SCREEN_INTENT by
        // default, so that screen frequently never appears).
        //
        // GIVEN ITS OWN REQUEST CODE, distinct from ONGOING_NOTIFICATION_ID
        // and from the content intent above. Sharing a code with another
        // PendingIntent that has the same action + component makes
        // FLAG_UPDATE_CURRENT hand back the SAME object, so the stop button
        // could be silently retargeted at a different alarm's intent.
        AlarmStopReceiver.stopPendingIntent(
            context = this,
            requestCode = ONGOING_NOTIFICATION_ID + 1000,
        )?.let { stopIntent -> builder.addAction(0, "Stop alarm", stopIntent) }
        return builder.build()
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

        /**
         * The live service instance, while a ring is in progress.
         *
         * `@Volatile` because it is written on the main thread (onCreate /
         * onDestroy) and read from wherever a stop is handled. Null whenever no
         * ring is up.
         */
        @Volatile
        private var instance: AlarmRingService? = null

        /**
         * Delivers the stop action to a running service in this process.
         *
         * Guarded because `startService` from a background context is refused
         * on Android 8+, and that refusal is an `IllegalStateException` on some
         * builds and a silent no-op on others — which is precisely how the old
         * "Stop alarm" button ended up doing nothing at all.
         *
         * @return true when the action was accepted for delivery.
         */
        fun requestStopFromContext(context: Context): Boolean = runCatching {
            context.startService(
                Intent(context, AlarmRingService::class.java).apply { action = ACTION_STOP },
            )
            true
        }.onFailure {
            Log.w(TAG, "Could not deliver the stop action to the ring service", it)
        }.getOrDefault(false)

        /**
         * Tears the ring down **from the calling process, without starting or
         * stopping a service**.
         *
         * This is the rung that makes "Stop alarm" reliable. Every other way of
         * silencing the service depends on the system accepting a service
         * interaction, and that acceptance is exactly what is denied on the
         * devices and OS versions where the button was reported dead:
         *
         *  - `startService(ACTION_STOP)` needs a background start, refused on
         *    Android 8+ unless the app holds an exemption.
         *  - `stopService(...)` is always permitted, but it only reaches
         *    `onDestroy` — and `stopSelfSafely()`'s explicit `stopForeground` +
         *    notification cancel are skipped, so the shade can keep showing
         *    "Alarm ringing" after an ostensibly successful stop.
         *
         * Holding the instance removes the dependency entirely: the service
         * lives in this same process, so a broadcast receiver on the main thread
         * can call straight into it and run the real, complete teardown — the
         * same one the in-app Stop button runs. `onReceive` is delivered on the
         * main thread, which is the thread MediaPlayer requires, so this is a
         * legal call from here.
         *
         * @return true when a live ring was found and torn down.
         */
        fun stopFromBroadcast(context: Context): Boolean = runCatching {
            val live = instance ?: return@runCatching false
            live.stopSelfSafely()
            true
        }.onFailure {
            Log.w(TAG, "In-process ring teardown failed", it)
        }.getOrDefault(false)

        /**
         * Silences the ring from **any** context, including a broadcast receiver
         * that is not allowed to start a service.
         *
         * This is the fix for the "Stop alarm" button doing nothing. The old
         * implementation `startService`d an `ACTION_STOP` intent and fell back
         * to `stopService`. Both are unreliable here for the same root reason: a
         * background start is refused by the Android 12+ background-start
         * restrictions on some OEM builds, and `stopService` on its own never
         * runs the explicit `stopForeground` + notification-cancel part of the
         * teardown.
         *
         * The escalation is ordered so the most authoritative mechanism runs
         * first, and every rung is independently sufficient on the devices where
         * it works. All three are idempotent, so running the later ones after a
         * successful earlier one is harmless — and running them when it was
         * refused is the whole point. This deliberately errs toward doing more,
         * never less: a surviving player is the exact failure being fixed.
         */
        fun stop(context: Context) {
            // 1. The complete in-process teardown. Cannot be refused.
            stopFromBroadcast(context)
            // 2. The action path, which also covers a service running in a
            //    different process configuration than we can see.
            requestStopFromContext(context)
            // 3. The hard stop, so nothing can outlive the user's decision even
            //    if both paths above were refused.
            runCatching {
                context.stopService(Intent(context, AlarmRingService::class.java))
            }.onFailure { Log.w(TAG, "stopService failed while stopping the ring", it) }
            // 4. Clear the ongoing notification directly. `stopSelfSafely`
            //    normally handles this, but if the service was already gone the
            //    shade entry would otherwise stay until the process died — and
            //    a lingering "Alarm ringing" row reads as a failed stop.
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(context)
                    .cancel(ONGOING_NOTIFICATION_ID)
            }.onFailure { Log.w(TAG, "Could not clear the ring notification", it) }
        }
    }
}