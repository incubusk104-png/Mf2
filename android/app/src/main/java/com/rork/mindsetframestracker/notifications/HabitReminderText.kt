package com.rork.mindsetframestracker.notifications

import android.content.Context
import android.content.Intent
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.HabitStore
import com.rork.mindsetframestracker.data.MotivationalMessages
import com.rork.mindsetframestracker.data.alarmClockLabel
import com.rork.mindsetframestracker.data.alarmMinutes

/**
 * Resolves the text a habit reminder actually shows, at the moment it rings.
 *
 * ## Why this exists as its own object
 *
 * A reminder fires from [HabitReminderReceiver] — a manifest `BroadcastReceiver`
 * with no Activity, no ViewModel and, on a cold start, no running app process at
 * all. The motivational line therefore has to be answerable from a bare
 * [Context] and nothing else, twice over:
 *
 *  1. **Durably** — [messageFor] reads the habit's own saved `alarmMessage`
 *     straight out of the shared-preferences blob, so a custom line survives the
 *     process being killed between scheduling and firing. Nothing about the
 *     message is carried in memory.
 *  2. **Freshly** — the scheduler arms an alarm up to a day ahead (see
 *     [HabitAlarmScheduler]). Editing the message in between must take effect on
 *     the *already-armed* alarm. This is why the text is deliberately **not**
 *     packaged into the alarm's `PendingIntent` extras: it is resolved here, at
 *     ring time, so there is exactly one source of truth (the habit's saved
 *     value) and no way for a pending alarm to deliver a line the user has since
 *     changed or deleted.
 *
 * ## Why not `Json.decodeFromString<AppData>`
 *
 * The same reason [NotificationStrings] reads `settings.language` by hand: this
 * code runs inside an alarm receiver, where a deserialization exception escaping
 * would kill the process at the exact moment the user's alarm is supposed to
 * ring. A hand-walked, throw-proof lookup (see [HabitStore]) cannot fail for any
 * reason other than the blob being absent, every failure is swallowed into the
 * curated default — never into "no notification" — and an absent blob is read
 * exactly the same way by the reboot re-arm path.
 *
 * ## Degrading to the curated line, never to nothing
 *
 * If the habit cannot be found (deleted between arming and firing), or the
 * message is blank, or the blob is unreadable, this returns the curated line for
 * the icon id that travelled on the intent. The one outcome this deliberately
 * does not produce is an empty string: a reminder with a blank body is worse
 * than one with a generic encouraging line.
 */
object HabitReminderText {

    private const val TAG = "HabitReminderText"

    /**
     * The user's saved motivational line for [habitId], or `""` when they have
     * not written one (in which case the caller should use [lineFor]).
     *
     * Pure read. Never throws: any parse or storage failure is logged and
     * reported as "no custom message", which degrades to the curated pack rather
     * than breaking the ring.
     */
    fun messageFor(context: Context, habitId: String): String {
        if (habitId.isBlank()) return ""
        // Reading now lives in HabitStore — the ONE place that knows the
        // serialized field names, shared with the reboot re-arm path — and the
        // message comes back already sanitised (see that object for why the
        // lookup is hand-walked rather than deserialized into AppData).
        return HabitStore.find(context, habitId)?.alarmMessage.orEmpty()
    }

    /**
     * The full line to display: the user's own message when they wrote one,
     * otherwise the curated line for the habit.
     *
     * [iconId] is a parameter rather than something read from storage because the
     * alarm already carries the habit's icon (`HabitReminderReceiver` passes it
     * through), and that copy is by definition the one that was true when the
     * alarm was armed — so this is still correct for a habit that was deleted or
     * re-iconed while the alarm was pending.
     */
    fun lineFor(
        context: Context,
        habitId: String,
        iconId: String?,
        alarmMinutes: Int?,
    ): String = MotivationalMessages.lineFor(
        habitId = habitId,
        iconId = iconId,
        dayKey = Dates.todayKey(),
        customMessage = messageFor(context, habitId),
        alarmMinutes = alarmMinutes,
        // Which of the habit's alarms this is, so a 07:00/12:00/18:00 habit says
        // three different things across the day instead of the same sentence
        // three times. See MotivationalMessages.lineFor for why the position, and
        // not the clock time, has to drive the choice.
        alarmIndex = alarmIndexFor(context, habitId, alarmMinutes),
    )

    /**
     * Where [alarmMinutes] sits in the habit's own schedule: 0 for the first time
     * of the day, 1 for the second, and so on.
     *
     * Degrades to 0 whenever anything cannot be resolved — a habit deleted since
     * the alarm was armed, or an intent written by a build that predates
     * multi-time alarms. That falls back to the un-rotated line rather than to no
     * line at all, which is the safe direction: the reminder still says something
     * warm and specific.
     */
    fun alarmIndexFor(context: Context, habitId: String, alarmMinutes: Int?): Int {
        if (habitId.isBlank() || alarmMinutes == null) return 0
        val schedule = HabitStore.find(context, habitId)?.alarmMinutes ?: return 0
        return MotivationalMessages.alarmIndexFor(alarmMinutes, schedule)
    }

    /**
     * The line to display for an alarm [intent] — the form every receiver and
     * notifier in the reminder path uses.
     *
     * Reads the identity and icon off the intent's extras so a caller does not
     * have to re-derive them, and falls back to the habit name when even the
     * icon is missing (an intent written by an older build).
     */
    fun lineFor(context: Context, intent: Intent): String {
        val habitId = intent.getStringExtra(HabitReminderReceiver.EXTRA_HABIT_ID).orEmpty()
        val habitName = intent.getStringExtra(HabitReminderReceiver.EXTRA_HABIT_NAME)
        val alarmMinutes = intent
            .getIntExtra(HabitReminderReceiver.EXTRA_ALARM_MINUTES, HabitReminderReceiver.NO_ALARM_MINUTES)
            .takeIf { it != HabitReminderReceiver.NO_ALARM_MINUTES }

        val line = lineFor(
            context = context,
            habitId = habitId,
            iconId = iconIdFor(context, habitId),
            alarmMinutes = alarmMinutes,
        )
        // A habit id the store no longer knows resolves to the general pack,
        // which is still a complete, encouraging sentence — this guard only
        // covers the pathological case of an intent with no id at all.
        return line.ifBlank { habitName ?: "" }
    }

    /**
     * The saved habit's catalog icon id, or null when it cannot be resolved.
     *
     * Its own read rather than a second pass over the same blob: a habit deleted
     * between arming and firing resolves to null here, and the curated general
     * pack is then the honest answer.
     */
    fun iconIdFor(context: Context, habitId: String): String? {
        if (habitId.isBlank()) return null
        return HabitStore.find(context, habitId)?.iconId
    }

    /**
     * The alarm times currently configured for [habitId], for the notification's
     * "07:00 · Drink water" subtitle.
     *
     * Read from storage rather than from the alarm's own pending intent because
     * the times may have been edited since the alarm was armed, and showing the
     * schedule the user has now is the only non-confusing answer.
     */
    fun alarmTimesFor(context: Context, habitId: String): List<Int> {
        if (habitId.isBlank()) return emptyList()
        // `alarmMinutes` already applies the shared legacy fallback and returns
        // the list ascending, deduped and bounded — the same value the scheduler
        // arms from, so the "2 of 3 today" subtitle can never disagree with the
        // number of alarms that actually exist.
        return HabitStore.find(context, habitId)?.alarmMinutes.orEmpty()
    }

    /**
     * The subtitle under the motivational line: the occurrence's own time when it
     * is known, e.g. `"07:00 · 2 of 3 today"`, otherwise the habit's whole
     * schedule.
     *
     * The occurrence marker matters for a habit that rings several times a day —
     * without it, three identical-looking reminders read as one alarm firing
     * three times by mistake.
     */
    fun subtitleFor(context: Context, habitId: String, alarmMinutes: Int?): String {
        val times = alarmTimesFor(context, habitId)
        if (alarmMinutes == null) return formatTimes(times)
        val position = times.indexOf(alarmMinutes)
        val clock = formatTime(alarmMinutes)
        return if (times.size > 1 && position >= 0) {
            "$clock · ${position + 1} of ${times.size} today"
        } else {
            clock
        }
    }

    /** Always 24-hour: an alarm time is an appointment, not a locale timestamp. */
    fun formatTime(minutes: Int): String = alarmClockLabel(minutes)

    private fun formatTimes(times: List<Int>): String =
        if (times.isEmpty()) "" else times.joinToString(", ") { formatTime(it) }
}
