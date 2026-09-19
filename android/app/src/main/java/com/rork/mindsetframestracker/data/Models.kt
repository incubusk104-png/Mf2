package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The four mood modes that drive the adaptive theme. Exactly these four. */
@Serializable
enum class MoodMode { CALM, FOCUSED, MOTIVATED, OVERWHELMED }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Habit(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    /** Pinned (favorite) habits always sort to the top of habit lists. */
    val isPinned: Boolean = false,
    /** Minutes from midnight for this habit's own reminder. Null = no individual alarm.
     *
     * ## Why this still exists alongside [alarmTimes]
     *
     * This is the habit's **first/primary** alarm time, and it is kept as the
     * single source of truth for everything that only ever understood one time:
     * the `reminder_minutes` column, the picker grid's "has an alarm" badge, the
     * legacy re-arm path. [alarmTimes] is the full set, and the two are
     * maintained together by [Habit.alarmMinutes] / [Habit.withAlarmTimes] so
     * they can never disagree.
     *
     * Null means "no alarm" — which is also why a user cannot set one of
     * several alarms to null: the list simply shrinks.
     */
    val reminderMinutes: Int? = null,
    /**
     * **Every** time this habit rings, in minutes from midnight, ascending.
     *
     * A habit like "Walk" is commonly wanted at 07:00, 12:00 and 18:00, and the
     * single [reminderMinutes] could express exactly one of those. Each entry
     * here is armed as its **own** AlarmManager alarm with its own request code,
     * so the three fire independently and each produces its own occurrence.
     *
     * `repeatDaysMask` is deliberately shared by the whole list rather than
     * stored per time: the user's mental model is "this habit rings at these
     * times on these days", and a per-time mask would mean editing the repeat
     * row silently applied only to whichever time happened to be selected.
     *
     * Empty means the habit has no alarm at all. When non-empty, [reminderMinutes]
     * is always the first entry — see [Habit.alarmMinutes].
     */
    val alarmTimes: List<Int> = emptyList(),
    /** For timed habits (meditation, workout). Null = simple checkbox habit. */
    val durationSeconds: Int? = null,
    /** Links to HabitIconCatalog.HabitIcon.id for visual picker display. */
    val iconId: String? = null,
    /**
     * Alarm repeat schedule as a 7-bit mask, bit 0 = Monday … bit 6 = Sunday
     * (mirrors the system Clock app's "Repeat: Once / Daily / Custom" row).
     *   127 (default) = every day
     *   0             = once — the alarm fires a single time then disarms
     *   0b0011111(31) = weekdays, 0b1100000(96) = weekends, any other = custom
     */
    val repeatDaysMask: Int = REPEAT_DAILY,
    /**
     * Screen-time habit: the package name of the phone app being monitored
     * (e.g. "com.facebook.katana"). Null for every other habit type.
     */
    val monitoredPackage: String? = null,
    /**
     * Screen-time habit: the daily usage budget in minutes (e.g. 120 = keep
     * Facebook under 2 hours). The habit auto-completes for a day when the
     * measured foreground time stayed at or under this limit.
     */
    val screenTimeLimitMinutes: Int? = null,
    /** Human-readable label of the monitored app, for display. */
    val monitoredAppLabel: String? = null,
    /**
     * Which tracking tool this habit uses \u2014 see [HabitTrackingMode].
     *
     * Null means "not configured by the user", and the habit resolves its mode
     * from its own [iconId] instead (see [Habit.trackingModeOrDefault]). That
     * indirection is what gives every habit that already exists on an install
     * the right tool per habit without a migration: the catalog knows a walk is
     * timed and a journal is written, so an existing habit picks the correct
     * input up the moment this ships. Setting it here is the user's override,
     * and an override is never replaced by a default.
     */
    val trackingMode: HabitTrackingMode? = null,
    /**
     * Target for a timed habit: the count-down length of a [HabitTrackingMode.TIMER],
     * or the optional goal of a [HabitTrackingMode.STOPWATCH]. Null = inherit
     * from the icon, and an open-ended stopwatch when the icon has none.
     */
    val trackingTargetSeconds: Int? = null,
    /** Goal amount for a [HabitTrackingMode.COUNT] habit (8 glasses, 3 servings). */
    val trackingTargetCount: Int? = null,
    /** What [trackingTargetCount] counts, for display ("glasses"). */
    val trackingUnit: String? = null,
    /**
     * The user's own motivational line for this habit's reminders — the one
     * thing that makes the alarm an encouragement instead of an alert.
     *
     * Null or blank means "use the curated default":
     * [MotivationalMessages.lineFor] falls back to the line pack that matches
     * this habit's [iconId] (a water habit says "It's time to water up! 💧 …"),
     * so a habit whose owner never wrote anything still gets a warm, specific
     * reminder rather than the bare habit name.
     *
     * ## Always read it through the sanitizer
     *
     * The value is placed on a Notification by a BroadcastReceiver that runs
     * with no user present, so it is normalised by
     * [MotivationalMessages.sanitize] — trimmed, single-line, control characters
     * stripped, and bounded to [MotivationalMessages.MAX_MESSAGE_LENGTH] — at
     * both ends: on write in the editor, and again on read at ring time, because
     * a restored cloud row or an older build can bypass the editor entirely.
     *
     * ## Why it is stored on the Habit and not in a settings map
     *
     * It belongs to the habit the same way its alarm times do: it is delivered
     * by that habit's alarm, deleted with that habit, and restored with it. A
     * side table keyed by habit id would need its own lifecycle, its own sync,
     * and its own orphan cleanup — for one nullable string.
     */
    val alarmMessage: String? = null,
    /**
     * **Which fitness trackers this habit is linked to** \u2014 the per-habit half of
     * the tracker integration, stored as [com.rork.mindsetframestracker.
     * integrations.TrackerProvider] names (`"STRAVA"`, `"POLAR"`,
     * `"HEALTH_CONNECT"`).
     *
     * ## Why the link is per habit and not a global switch
     *
     * The user's own words: *"the tracker should not be a separate/global thing
     * sitting in its own position \u2014 each habit should carry the features related
     * to it."* Walking exists in Google Health, Polar and Strava, so the connect
     * action belongs on the **Walk** habit, and the activity it imports belongs to
     * that habit's record.
     *
     * The distinction that matters is between *connectivity* and *attribution*.
     * The OAuth grant is genuinely account-wide \u2014 the user authorises Strava
     * once, not once per habit \u2014 so the tokens stay in [AppSettings]. What is
     * per-habit is **which habit the data lands on**. Before this field existed
     * there was no way to express that, so every sync call site guessed from the
     * icon and every sport habit imported the same account-wide report, each
     * under its own id, double-counting one workout against two habits.
     *
     * ## Why a `List<String>` and not a single id
     *
     * A habit may legitimately draw on more than one source \u2014 a walk measured by
     * the phone's own Health Connect *and* imported from Strava when the user
     * wears a watch. The single-*habit* rule (one provider, one habit) is enforced
     * where it belongs, in
     * [com.rork.mindsetframestracker.integrations.HabitTrackerLinks.bindFor],
     * rather than by narrowing the type: a `String?` could not express the
     * legitimate case, and would have to be widened later by a migration.
     *
     * ## Why strings rather than the enum
     *
     * So a value written by a newer build that adds a fourth provider is ignored
     * by an older one instead of failing to decode the whole blob \u2014 the same
     * forward-compatibility rule `trackingUnit` and `accentPack` follow. An
     * unrecognised id is dropped on read by
     * [com.rork.mindsetframestracker.integrations.HabitTrackerLinks.providerOf].
     *
     * ## Empty means unbound, and unbound means no import
     *
     * Empty is the default and the state of every habit that existed before this
     * shipped \u2014 deliberately, because the pre-existing behaviour (every sport
     * habit swept by every connected provider) is the defect being removed.
     * Empty does **not** mean "use all candidate sources": an unbound provider
     * resolves to no habit and writes nothing, so a user who has not chosen a
     * tracker for a habit gets no mysteriously-attributed activity. The user
     * chooses, from inside the habit.
     */
    val trackerProviderIds: List<String> = emptyList(),
)

/** [Habit.repeatDaysMask] value meaning "every day". */
const val REPEAT_DAILY = 0b1111111

/** [Habit.repeatDaysMask] value meaning "fire once, then disarm". */
const val REPEAT_ONCE = 0

/** [Habit.repeatDaysMask] for Monday–Friday. */
const val REPEAT_WEEKDAYS = 0b0011111

/** [Habit.repeatDaysMask] for Saturday + Sunday. */
const val REPEAT_WEEKENDS = 0b1100000

/** True when this habit tracks phone screen time instead of a manual check-in. */
val Habit.isScreenTimeHabit: Boolean
    get() = monitoredPackage != null && screenTimeLimitMinutes != null

/**
 * Every alarm time this habit rings at, in minutes from midnight, ascending
 * and free of duplicates.
 *
 * Reads [Habit.alarmTimes] when it has been populated and falls back to the
 * legacy [Habit.reminderMinutes] otherwise, so every habit created before
 * multiple alarms existed keeps ringing exactly where it did. Returning a
 * sorted, deduped list rather than the raw field is what lets the scheduler
 * (and the UI) treat one-time and many-time habits through the same path
 * without a per-call-site special case.
 */
val Habit.alarmMinutes: List<Int>
    get() = legacyAlarmTimes(alarmTimes, reminderMinutes)

/** True when this habit rings at more than one time of day. */
val Habit.hasMultipleAlarms: Boolean
    get() = alarmMinutes.size > 1

/**
 * The alarm time today that is most likely the one waiting on the user, given
 * the current local time in minutes.
 *
 * Used to attribute a completion that happens *after* the ring — a stopwatch the
 * user started from the alarm sheet and finished 40 minutes later. The record
 * belongs on the occurrence that prompted it, not on "the habit" generically, or
 * the 07:00 and 18:00 walks collapse into one entry and the user cannot tell
 * what they did at each time.
 *
 * The **latest** already-due time is the right answer: alarms fire in ascending
 * order, so if 07:00 and 12:00 have both passed and the user is finishing a walk
 * now, 12:00 is the occurrence in progress.
 *
 * Falls back to the habit's last time of day when nothing is due yet (a
 * completion arriving before the first alarm, e.g. from a manually started
 * timer). Null only when the habit has no alarms at all, in which case the
 * record is written day-scoped exactly as before.
 */
fun Habit.pendingOccurrenceMinutes(nowMinutes: Int): Int? {
    val times = alarmMinutes
    if (times.isEmpty()) return null
    return times.filter { it <= nowMinutes }.lastOrNull() ?: times.last()
}

/**
 * The habit with its alarm times replaced by [times].
 *
 * The **only** supported way to change a habit's alarms, because it is the one
 * place that keeps [Habit.alarmTimes] and [Habit.reminderMinutes] consistent.
 * Writing either field directly is what would let them drift — a habit whose
 * list says 07:00/18:00 but whose `reminderMinutes` says 09:00 would ring at
 * the wrong times depending on which reader looked at it.
 *
 * An empty [times] clears the alarm entirely and nulls [Habit.reminderMinutes],
 * which is what the "no alarm for this habit" choice means.
 */
fun Habit.withAlarmTimes(times: List<Int>): Habit {
    val clean = times.filter { it in 0..1439 }.distinct().sorted()
    return copy(
        alarmTimes = clean,
        reminderMinutes = clean.firstOrNull(),
    )
}

/**
 * The habit with its motivational reminder line replaced by [message].
 *
 * The only supported way to change [Habit.alarmMessage], for the same reason
 * [withAlarmTimes] exists: it routes the value through
 * [MotivationalMessages.sanitize] and stores `null` rather than `""` for a blank
 * or whitespace-only entry. Without that normalisation the two "no custom
 * message" spellings would both be representable, and every reader would have to
 * test for both — the kind of ambiguity that eventually ships as a habit whose
 * reminder is an empty notification title.
 */
fun Habit.withAlarmMessage(message: String?): Habit =
    copy(alarmMessage = MotivationalMessages.sanitize(message).takeIf { it.isNotEmpty() })

/**
 * "07:00, 12:00, 18:00" — the alarm times as a readable list.
 *
 * Written here rather than in the UI so the schedule is described identically
 * wherever it is shown (snackbar, habit row, settings), and so the
 * always-24-hour clock is consistent: an alarm time is an appointment, not a
 * locale-formatted timestamp, and mixing 12- and 24-hour renderings of the
 * same schedule is how a user ends up misreading their own alarms.
 */
fun formatAlarmTimes(times: List<Int>): String =
    times.sorted().joinToString(", ") { minutes -> alarmClockLabel(minutes) }

/**
 * "07:00" for a time-of-day in minutes from midnight — the single clock label
 * every alarm surface uses.
 *
 * Extracted so the habit dialog, the notification subtitle
 * ([com.rork.mindsetframestracker.notifications.HabitReminderText]) and the new
 * alarm-history view cannot drift apart: three private implementations of the
 * same `String.format` is how one screen ends up showing "7:00" next to another
 * showing "07:00". Always 24-hour and always zero-padded, for the reason above —
 * an alarm time is an appointment, not a locale-formatted timestamp.
 */
fun alarmClockLabel(minutes: Int): String =
    String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

/**
 * One app's screen-time limit as chosen in the limits manager.
 *
 * This is the transport between the picker UI and [AppViewModel]'s
 * reconciliation, deliberately a plain value rather than a [Habit]: the sheet
 * knows which apps the user wants limited and by how much, but it does not own
 * habit identity, creation order or ids. Letting the ViewModel mint the
 * [Habit]s keeps ids and the create/update/remove decision in one place.
 */
data class ScreenTimeLimitInput(
    val packageName: String,
    val appLabel: String,
    /** Daily budget in minutes. */
    val limitMinutes: Int,
)

/**
 * Human label for a screen-time habit, e.g. "Facebook under 2h".
 *
 * Falls back to the package name when the stored label is missing (habits
 * created before the label column existed), so the title is never blank.
 */
fun Habit.screenTimeSummary(): String {
    val label = monitoredAppLabel ?: monitoredPackage ?: return name
    val limit = screenTimeLimitMinutes ?: return label
    val limitText = when {
        limit % 60 == 0 && limit >= 60 -> "${limit / 60}h"
        limit > 60 -> "${limit / 60}h ${limit % 60}m"
        else -> "${limit}m"
    }
    return "$label under $limitText"
}

@Serializable
data class ActivityRecord(
    val id: String,
    val habitId: String,
    val source: String,          // "polar" | "health_connect" | "health_connect_device" | "strava"
    val activityType: String,    // "walking", "running", "cycling", etc.
    /**
     * Start of the activity, epoch millis.
     *
     * NOT the moment it was imported. Strava returns each activity's own
     * `start_date`, and a sync run today can import a run from three days ago;
     * stamping the import time would file that run under today and make the
     * Weekly and Insight day buckets wrong. Sources that genuinely have no
     * start time of their own (Polar's daily step roll-up) leave this as the
     * read moment, which is the only truthful value available.
     */
    val timestamp: Long,
    val durationMinutes: Int? = null,
    val distanceMeters: Double? = null,
    val steps: Long? = null,
    val heartRateAvg: Int? = null,
    /** Peak heart rate, when the source recorded samples. */
    val heartRateMax: Int? = null,
    val calories: Int? = null,
    /**
     * End of the activity, epoch millis — null when the source gave only a
     * duration or nothing at all. Persisted rather than derived so a future
     * reader never has to guess whether `timestamp + duration` is exact.
     */
    val endedAtMs: Long? = null,
    /** Provider-supplied name ("Morning Run"), when there is one. */
    val activityName: String? = null,
    /** Cumulative climb in metres, when the provider reports it. */
    val elevationGainMeters: Double? = null,
    /**
     * Sleep duration in minutes, for sleep records only.
     *
     * Sleep has no distance, steps or pace, so it could not ride on any
     * existing field without lying about what the number meant. Null on every
     * non-sleep record.
     */
    val sleepMinutes: Int? = null,
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reducedMotion: Boolean = false,
    /** Daily reminder time as minutes from midnight. */
    val notificationMinutes: Int = 8 * 60,
    /**
     * Streak-protection alert: fires once a day at [streakAlertMinutes] but
     * ONLY when today's habits are not all completed yet. Stays silent when
     * the day is already done.
     */
    val streakAlertEnabled: Boolean = true,
    /** Streak alert time as minutes from midnight (default 8:00 PM). */
    val streakAlertMinutes: Int = 20 * 60,
    /**
     * Weekly recap: one Sunday-evening notification summarizing the week
     * ("You checked in 5/7 days"). Strong weeks get celebrated, quiet weeks
     * get a fresh-start nudge — never a guilt trip.
     */
    val weeklyRecapEnabled: Boolean = true,
    /** Accent pack id: "classic" (free). */
    val accentPack: String = "classic",
    /** Custom default times for the quick-pick presets ("morning"/"midday"/"evening" -> minutes from midnight). */
    val presetTimes: Map<String, Int> = emptyMap(),
    val onboardingDone: Boolean = false,
    /**
     * One-time sign-in popup guard: true once the save-your-progress sheet
     * has been offered. It never re-appears on later launches — the ONLY
     * thing that re-arms it is an explicit sign-out (the popup is the sole
     * sign-in surface, so the user needs a way back in).
     */
    val authPromptDone: Boolean = false,
    /**
     * One-time guard for whether the user has agreed to the Privacy Policy
     * before any login trigger is initialized. Required by Huawei AppGallery.
     */
    val privacyConsentAccepted: Boolean = false,
    /**
     * Premium entitlement. Free tier keeps the core tracker; Premium unlocks
     * extended prompts/quotes, advanced insights, exclusive themes, all
     * languages, unlimited habits, and PDF reports. Upgrade is offered
     * through the Mindset Frames listing on Huawei AppGallery.
     */
    val isPremium: Boolean = false,
    /**
     * The active Huawei IAP subscription product id (e.g.
     * "mindset_premium_monthly"). Set by SubscriptionBilling on a verified
     * purchase or restore, cleared when the store reports no active sub.
     * Drives the SubscriptionTier used by Entitlements (Strava is
     * REGULAR-tier only). Null while [isPremium] is false or when premium
     * was granted through a legacy path.
     */
    val subscriptionProductId: String? = null,
    /** Polar AccessLink access token — held only on-device. */
    val polarAccessToken: String? = null,
    /**
     * Numeric Polar user id (x_user_id from the token exchange). Required by
     * every AccessLink transaction endpoint (/users/{id}/activity-transactions)
     * — without it no activity data can be pulled. 0 = unknown (legacy
     * connection made before this field existed; user must reconnect).
     */
    val polarUserId: Long = 0,
    /** Epoch millis of the last successful Polar sync (0 = never). */
    val polarLastSyncMs: Long = 0,
    /** Auto-sync Polar data when opening the app. Defaults to false —
     *  the user must explicitly opt in after authenticating their account. */
    val polarAutoSync: Boolean = false,
    /** Strava OAuth tokens — held only on-device; exchange/refresh happens
     *  through the strava-token-exchange Edge Function (secret never ships). */
    val stravaAccessToken: String? = null,
    val stravaRefreshToken: String? = null,
    /** Strava access-token expiry, epoch SECONDS (Strava's own unit). */
    val stravaExpiresAt: Long = 0,
    /** Epoch millis of the last successful Strava sync (0 = never). */
    val stravaLastSyncMs: Long = 0,
    /** Auto-sync Strava activities when opening the app. Defaults to false —
     *  the user must explicitly opt in after authenticating their account. */
    val stravaAutoSync: Boolean = false,
    /** Android Health Connect (Google) connected state. */
    val healthConnectConnected: Boolean = false,
    /** Epoch millis of last Health Connect sync. */
    val healthConnectLastSyncMs: Long = 0,
    /** Auto-sync Health Connect data when opening the app. Defaults to false —
     *  the user must explicitly opt in after authenticating their account. */
    val healthConnectAutoSync: Boolean = false,
    /**
     * App display language. English (US/UK) is free everywhere, one regional
     * language ([freeRegionalLanguage]) is free for this install; all other
     * languages are Premium.
     */
    val language: AppLanguage = DEFAULT_LANGUAGE,
    /**
     * The ONE regional language this install unlocked for free, resolved
     * from the device locale on first launch (e.g. Simplified Chinese in
     * China, Tagalog in the Philippines). Null when the locale maps to no
     * supported language (plain English regions). Persisted locally and
     * synced to Supabase inside the settings payload so the unlock follows
     * the user across restores.
     */
    val freeRegionalLanguage: AppLanguage? = null,
    /**
     * Achievement badges earned by completing all habits for consecutive
     * days. Once earned, a badge is permanent — it stays even if the streak
     * later breaks. See [BadgeTier] for the tier thresholds.
     */
    val earnedBadges: Set<BadgeTier> = emptySet(),
    /**
     * The user's companion avatar. Customization is free; background frames
     * unlock through streak achievements (see AvatarCatalog).
     */
    val avatar: AvatarConfig = AvatarConfig(),
    /**
     * Permanently earned Companion Studio exclusives (outfit/expression/pet
     * ids). An item lands here the first time its CompanionTask requirement
     * is met and never leaves — a later streak break can't re-lock it.
     */
    val companionUnlocks: Set<String> = emptySet(),
)

/** Root of everything persisted locally on device. No PII, no cloud. */
@Serializable
data class AppData(
    val habits: List<Habit> = emptyList(),
    /** habitId -> list of ISO day keys ("2026-07-23") the habit was checked. */
    val checkIns: Map<String, List<String>> = emptyMap(),
    /** ISO day key -> mood selected that day (used for weekly view). */
    val moodHistory: Map<String, MoodMode> = emptyMap(),
    /** ISO day key -> one-line grounding micro-journal entry for that day. */
    val reflections: Map<String, String> = emptyMap(),
    val activityRecords: List<ActivityRecord> = emptyList(),
    /**
     * Detailed records of what was actually done, one per completion, newest
     * last \u2014 see [HabitLogEntry]. Complements [checkIns] rather than
     * replacing it: [checkIns] is the cheap boolean that streaks, badges and
     * the heatmap read, this is the payload (duration, journal text, amount)
     * that the habit's own tracking tool produced.
     *
     * Defaults to empty and is append-only, so every existing install decodes
     * unchanged (`ignoreUnknownKeys` + a default is what keeps the single
     * SharedPreferences blob forward- and backward-compatible).
     */
    val habitLogs: List<HabitLogEntry> = emptyList(),
    /**
     * Every habit alarm occurrence the app has seen, newest state per event —
     * see [HabitAlarmEvent] and [HabitAlarmHistory].
     *
     * ## Why this is a separate list from [habitLogs]
     *
     * They record different things. [habitLogs] is what the *user* supplied (a
     * duration, a count, a journal entry) and is only written when there is real
     * content. This is what the *alarm* did: it fired at a scheduled time, and the
     * user either answered it or did not. A 07:00/12:00/18:00 habit produces three
     * entries here a day regardless of whether any of them was completed, which is
     * what makes a whole-day alarm history possible at all — and it is the
     * evidence a MISSED alarm leaves, which no [HabitLogEntry] can represent.
     *
     * ## Identity, not just ownership
     *
     * Each event is keyed by `(habitId, dayKey, scheduledMinutes)`. Nothing in
     * this list may be collapsed to one row per habit per day: that is precisely
     * the shape that makes a habit's three daily alarms indistinguishable from
     * one, and it is the bug this list was added to fix.
     *
     * ## Bounded and compat-safe
     *
     * Append-mostly, newest-last, capped at [MAX_ALARM_EVENTS] by
     * [HabitAlarmHistory.record] so the single SharedPreferences blob cannot grow
     * without limit. Defaults to empty, so every existing install decodes
     * unchanged.
     */
    val alarmEvents: List<HabitAlarmEvent> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

object Dates {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun todayKey(): String = LocalDate.now().format(formatter)

    /**
     * Minutes since local midnight, 0..1439.
     *
     * Used to decide which of a habit's alarm times is the one currently in
     * progress, so a record made *after* a ring (a stopwatch the user started
     * from the alarm sheet and stopped 40 minutes later) is attributed to the
     * occurrence that prompted it rather than to the habit generically. Local
     * rather than UTC on purpose: the user's "today" is their own midnight.
     */
    fun nowMinutes(): Int {
        val now = java.time.LocalTime.now()
        return now.hour * 60 + now.minute
    }

    fun key(date: LocalDate): String = date.format(formatter)

    /** Last [n] days, oldest first, ending today. */
    fun lastDays(n: Int): List<LocalDate> =
        (n - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
}

/**
 * Habits for display: pinned favorites first, otherwise preserving the
 * user's original order (stable sort).
 */
fun AppData.sortedHabits(): List<Habit> = habits.sortedByDescending { it.isPinned }

fun AppData.isCheckedOn(habitId: String, dayKey: String): Boolean =
    checkIns[habitId]?.contains(dayKey) == true

fun AppData.isCheckedToday(habitId: String): Boolean = isCheckedOn(habitId, Dates.todayKey())

/**
 * Consecutive-day streak for a habit. Counts back from today; if today is not
 * yet checked the streak is preserved from yesterday.
 */
fun AppData.streakFor(habitId: String): Int {
    val days = checkIns[habitId]?.toSet() ?: return 0
    var cursor = LocalDate.now()
    if (!days.contains(Dates.key(cursor))) cursor = cursor.minusDays(1)
    var streak = 0
    while (days.contains(Dates.key(cursor))) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/**
 * Overall consecutive daily check-in streak. A day counts when at least one
 * habit was checked in on it. Counts back from today; if today has no
 * check-in yet, the streak carries over from yesterday.
 */
fun AppData.dailyCheckInStreak(): Int {
    val days: Set<String> = checkIns.values.flatten().toSet()
    if (days.isEmpty()) return 0
    var cursor = LocalDate.now()
    if (!days.contains(Dates.key(cursor))) cursor = cursor.minusDays(1)
    var streak = 0
    while (days.contains(Dates.key(cursor))) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/** Mood for today, falling back to the most recent recorded mood, then Calm. */
fun AppData.currentMood(): MoodMode =
    moodHistory[Dates.todayKey()]
        ?: moodHistory.entries.maxByOrNull { it.key }?.value
        ?: MoodMode.CALM

fun AppData.completedCountOn(dayKey: String): Int =
    habits.count { isCheckedOn(it.id, dayKey) }

/**
 * True when every current habit was checked in on the given day. A day with
 * zero habits is never considered complete.
 */
fun AppData.isFullyCompleted(dayKey: String): Boolean =
    habits.isNotEmpty() && completedCountOn(dayKey) == habits.size

/**
 * Consecutive-day streak of completing ALL habits. Counts back from today;
 * if today isn't fully done yet, the streak carries from yesterday so the
 * count isn't lost mid-day. Used for badge tier qualification.
 */
fun AppData.fullCompletionStreak(): Int {
    if (habits.isEmpty()) return 0
    var cursor = LocalDate.now()
    if (!isFullyCompleted(Dates.key(cursor))) cursor = cursor.minusDays(1)
    var streak = 0
    while (isFullyCompleted(Dates.key(cursor))) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/** Max number of habits on the free tier. Premium removes the cap. */
const val MAX_FREE_HABITS = 5

/**
 * Premium-level content access — gates extended prompt packs, the exclusive
 * quote library, advanced weekly insights, premium accent themes, premium
 * languages, unlimited habits, and PDF reports.
 */
fun AppSettings.hasFeatureAccess(): Boolean = isPremium

/**
 * The billing tier this install is entitled to, derived from the active
 * subscription product. Legacy premium grants without a stored product id
 * map to REGULAR so no existing premium user loses features on update.
 */
fun AppSettings.subscriptionTier(): com.rork.mindsetframestracker.billing.SubscriptionTier {
    if (!isPremium) return com.rork.mindsetframestracker.billing.SubscriptionTier.NONE
    val fromProduct = subscriptionProductId?.let {
        com.rork.mindsetframestracker.billing.Entitlements.tierForProductId(it)
    }
    return if (fromProduct != null && fromProduct != com.rork.mindsetframestracker.billing.SubscriptionTier.NONE) {
        fromProduct
    } else {
        com.rork.mindsetframestracker.billing.SubscriptionTier.REGULAR
    }
}
