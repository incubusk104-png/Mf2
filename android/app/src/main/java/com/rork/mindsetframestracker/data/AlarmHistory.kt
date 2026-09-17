package com.rork.mindsetframestracker.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

/**
 * What happened to **one** habit alarm at **one** scheduled time on **one** day.
 *
 * ## Why this is separate from [HabitLogEntry]
 *
 * [HabitLogEntry] answers *"what did I actually do"* \u2014 and deliberately only
 * exists when the user supplied the content (a duration, a count, a journal
 * sentence). It is written by the tracking sheet and by the timer, and
 * `HabitAlarmRecords` refuses to invent one for a habit whose input only the
 * user can give.
 *
 * That leaves a hole this type fills: **the alarm itself.** A habit that rings
 * at 07:00, 12:00 and 18:00 produces three events a day whether or not the user
 * answers any of them, and before this type existed there was nowhere in the app
 * that recorded the ring at all. The dialog could say "done today" but not
 * *which of the three* went off, at what time, saying what \u2014 or that the 12:00
 * one was missed entirely, which is exactly the thing a user reviewing their day
 * wants to see.
 *
 * ## Every event is its own record
 *
 * The identity of an event is `(habitId, dayKey, scheduledMinutes)` \u2014 see
 * [alarmEventKey]. It is deliberately **not** keyed on `habitId` alone, and not
 * on `(habitId, dayKey)` either: collapsing any of those would make the day's
 * three alarms indistinguishable from one, which is the bug this feature exists
 * to fix. Two devices, or two readings of the same day, produce the same key, so
 * the "already recorded" and "refine this event" tests are a single string
 * comparison.
 *
 * ## One event, several outcomes
 *
 * An alarm fires and is *then* answered, and those are two facts about the same
 * occurrence rather than two occurrences. So the outcome is a field that gets
 * **refined in place** ([HabitAlarmHistory.record] upserts on the key):

 * | outcome        | meaning                                                     |
 * |----------------|-------------------------------------------------------------|
 * | [FIRED]        | it rang; nothing recorded about what the user did yet        |
 * | [ACKNOWLEDGED] | the user completed the habit from the ring (ONE_TAP dismiss)  |
 * | [DISMISSED]    | the user stopped the alarm without completing it              |
 * | [SNOOZED]      | the user snoozed it; it will ring again                       |
 *
 * A snooze that re-fires updates the **same** event rather than adding a second
 * row, because the user's model is "the 07:00 alarm" \u2014 not "the 07:00 alarm,
 * three times".
 */
@Serializable
enum class AlarmEventOutcome {
    FIRED,
    ACKNOWLEDGED,
    DISMISSED,
    SNOOZED;

    /** True once the user has done something about the ring. */
    val isAnswered: Boolean get() = this != FIRED
}

/**
 * One habit alarm occurrence: it fired for [habitId] at [scheduledMinutes] on
 * [dayKey], in [outcome].
 *
 * Defaults are chosen so a decoder reading a blob written by a previous build
 * (`ignoreUnknownKeys` + absent field) produces a usable value rather than a
 * crash \u2014 the same forward/backward-compatibility rule [AppData] relies on.
 */
@Serializable
data class HabitAlarmEvent(
    val id: String = UUID.randomUUID().toString(),
    val habitId: String,
    /** ISO day key the alarm was *scheduled* for ("2026-09-17"). */
    val dayKey: String,
    /** The scheduled time, minutes from local midnight (0..1439). */
    val scheduledMinutes: Int,
    val outcome: AlarmEventOutcome = AlarmEventOutcome.FIRED,
    /** When the alarm rang. 0 when the event was first seen as an answer. */
    val firedAtEpochMs: Long = 0L,
    /** When the user answered it, or null while still unanswered. */
    val respondedAtEpochMs: Long? = null,
    /**
     * The motivational line this occurrence actually delivered, snapshotted at
     * ring time.
     *
     * Snapshotted rather than re-resolved at display time on purpose: the user
     * can edit or clear their message later, and a history view that re-rendered
     * today's message against this morning's ring would be showing them
     * something the alarm never said.
     */
    val message: String? = null,
) {
    /** The occurrence key \u2014 see [alarmEventKey]. Not serialized. */
    val eventKey: String get() = alarmEventKey(dayKey, scheduledMinutes)

    /** "07:00" \u2014 always 24-hour, like every other alarm time in the app. */
    val clockLabel: String get() = alarmClockLabel(scheduledMinutes)
}

/**
 * The identity of one alarm occurrence: the habit, the day, **and the scheduled
 * time**. Including the time is what keeps a 07:00/12:00/18:00 habit's three
 * alarms as three distinct records instead of one collapsed entry.
 */
fun alarmEventKey(dayKey: String, scheduledMinutes: Int): String = "$dayKey@$scheduledMinutes"

/** How many events are retained. Oldest are trimmed once this is exceeded. */
const val MAX_ALARM_EVENTS = 500

/** Every alarm event for [habitId], oldest first. */
fun AppData.alarmEventsFor(habitId: String): List<HabitAlarmEvent> =
    alarmEvents.filter { it.habitId == habitId }.sortedBy { it.firedAtEpochMs }

/** Every alarm event for [habitId] on [dayKey], oldest first. */
fun AppData.alarmEventsOn(habitId: String, dayKey: String): List<HabitAlarmEvent> =
    alarmEventsFor(habitId).filter { it.dayKey == dayKey }

/** The event for exactly this occurrence, or null \u2014 the uniqueness test. */
fun AppData.alarmEventFor(habitId: String, dayKey: String, scheduledMinutes: Int): HabitAlarmEvent? {
    val key = alarmEventKey(dayKey, scheduledMinutes)
    return alarmEvents.firstOrNull { it.habitId == habitId && it.eventKey == key }
}

/**
 * The per-time state of one of a habit's alarms on a given day.
 *
 * [MISSED] and [PENDING] are *derived*, not stored: an alarm that never fired
 * has no event, so its state comes from comparing its scheduled time with the
 * day being viewed and the current clock. That is why this is a separate enum
 * from [AlarmEventOutcome] \u2014 the outcome is a fact that was recorded, the slot
 * state is what the UI should say right now.
 */
enum class AlarmSlotState {
    /** Scheduled later today; has not fired yet. */
    PENDING,
    /** Rang, with no answer recorded. */
    FIRED,
    /** The user completed the habit from the ring. */
    ACKNOWLEDGED,
    /** The user stopped the alarm without completing it. */
    DISMISSED,
    /** The user snoozed it. */
    SNOOZED,
    /** Its time passed on this day with no event recorded at all. */
    MISSED,
}

/**
 * One row of the day's alarm history: the time, what state it is in, and the
 * events behind it.
 *
 * A slot exists for **every** time in the habit's schedule, whether or not it
 * fired \u2014 that is what lets the dialog show the honest whole day ("07:00 done,
 * 12:00 missed, 18:00 still to come") rather than only the alarms that happened
 * to leave a trace.
 */
data class AlarmDaySlot(
    val scheduledMinutes: Int,
    val state: AlarmSlotState,
    val events: List<HabitAlarmEvent> = emptyList(),
) {
    /** "07:00" \u2014 24-hour, matching every other alarm label in the app. */
    val clockLabel: String get() = alarmClockLabel(scheduledMinutes)

    /** The most recent event for this time, if any. */
    val latest: HabitAlarmEvent? get() = events.lastOrNull()

    /** The line the alarm actually delivered, when one was recorded. */
    val message: String? get() = events.lastOrNull { !it.message.isNullOrBlank() }?.message

    /** True when the user did something about this occurrence. */
    val isAnswered: Boolean get() =
        state == AlarmSlotState.ACKNOWLEDGED ||
            state == AlarmSlotState.DISMISSED ||
            state == AlarmSlotState.SNOOZED
}

/**
 * The recorder/reader for per-occurrence alarm history.
 *
 * Reads and writes the list on [AppData] directly \u2014 there is no separate store,
 * for the same reason [Habit.alarmMessage] lives on the habit: the history
 * belongs to the habit, is deleted with it, and travels with the single
 * persisted blob that [MindsetRepository] already owns.
 *
 * ## Why every write is a keyed upsert
 *
 * [record] replaces the event for `(habitId, dayKey, scheduledMinutes)` if one
 * exists. That single rule gives all three behaviours the feature needs, and
 * they would otherwise be three hand-written branches that can disagree:
 *
 *  * **A re-delivered intent is not a second ring.** The alarm firing twice for
 *    07:00 (a re-arm, a duplicate broadcast) updates one row.
 *  * **Answering refines, never duplicates.** The DISMISSED that follows a
 *    FIRED is the same occurrence being answered \u2014 not a second alarm.
 *  * **Different times are different rows.** An 18:00 ring is a different key
 *    from the 07:00 one, so it always writes its own event.
 *
 * ## Never throws
 *
 * Every entry point here is reachable from a `BroadcastReceiver` \u2014 the boot
 * re-arm, the ring notifier, the snooze and stop receivers \u2014 where an escaping
 * exception kills the process at the exact moment the user's alarm should be
 * dealing with them. Failures are logged and returned as null; the alarm itself
 * is never affected by a history write failing.
 */
object HabitAlarmHistory {

    private const val TAG = "HabitAlarmHistory"

    /**
     * Records the occurrence `(habitId, dayKey, scheduledMinutes)` in [outcome],
     * replacing any existing event for that exact occurrence.
     *
     * Returns the event as stored, or null when the arguments are unusable or
     * persistence failed.
     */
    fun record(
        context: Context,
        habitId: String,
        dayKey: String,
        scheduledMinutes: Int,
        outcome: AlarmEventOutcome,
        message: String? = null,
        atEpochMs: Long = System.currentTimeMillis(),
    ): HabitAlarmEvent? = runCatching {
        if (habitId.isBlank() || scheduledMinutes !in 0..1439 || dayKey.isBlank()) return null

        val repo = MindsetRepository(context)
        val data = repo.load()
        val key = alarmEventKey(dayKey, scheduledMinutes)
        val existing = data.alarmEvents.firstOrNull { it.habitId == habitId && it.eventKey == key }
        val cleanMessage = message?.trim()?.takeIf { it.isNotEmpty() }

        val event = if (existing == null) {
            HabitAlarmEvent(
                habitId = habitId,
                dayKey = dayKey,
                scheduledMinutes = scheduledMinutes,
                outcome = outcome,
                // An event first seen as an answer has no known ring time; the
                // answer's own clock is the only timestamp that exists.
                firedAtEpochMs = if (outcome == AlarmEventOutcome.FIRED) atEpochMs else 0L,
                respondedAtEpochMs = if (outcome.isAnswered) atEpochMs else null,
                message = cleanMessage,
            )
        } else {
            existing.copy(
                // A FIRED arriving for an event that already has a real outcome
                // must not un-answer it: the ring and the answer can be delivered
                // out of order, and the answer is the more informative fact.
                outcome = if (outcome == AlarmEventOutcome.FIRED) existing.outcome else outcome,
                firedAtEpochMs = existing.firedAtEpochMs.takeIf { it > 0L }
                    ?: atEpochMs.takeIf { outcome == AlarmEventOutcome.FIRED }
                    ?: 0L,
                respondedAtEpochMs = if (outcome.isAnswered) atEpochMs else existing.respondedAtEpochMs,
                // The first snapshot wins: it is the line the user actually heard.
                message = existing.message ?: cleanMessage,
            )
        }

        val others = data.alarmEvents.filterNot { it.habitId == habitId && it.eventKey == key }
        repo.save(data.copy(alarmEvents = prune(others + event)))
        event
    }.onFailure { Log.w(TAG, "Could not record the alarm event for $habitId@$scheduledMinutes", it) }
        .getOrNull()

    /**
     * Records that the alarm at [scheduledMinutes] was answered with [outcome],
     * without the caller needing to know which day it belonged to.
     *
     * The day is resolved by finding the newest **unanswered** [AlarmEventOutcome.FIRED]
     * event for this habit and time on today or yesterday, and refining that one.
     * The yesterday window is what keeps a late-night alarm answered just after
     * midnight attached to the occurrence it belongs to instead of creating a
     * stray 00:0x event for a time the user never set.
     *
     * Falls back to today when no outstanding ring is found (an older install, or
     * a stop that arrived before the ring event was written), so the answer is
     * never silently dropped.
     */
    fun markOutcome(
        context: Context,
        habitId: String,
        scheduledMinutes: Int?,
        outcome: AlarmEventOutcome,
        atEpochMs: Long = System.currentTimeMillis(),
    ): HabitAlarmEvent? = runCatching {
        if (habitId.isBlank() || scheduledMinutes == null || scheduledMinutes !in 0..1439) return null

        val today = Dates.todayKey()
        val yesterday = Dates.key(LocalDate.now().minusDays(1))
        val dayKey = MindsetRepository(context).load().alarmEvents
            .filter {
                it.habitId == habitId &&
                    it.scheduledMinutes == scheduledMinutes &&
                    it.outcome == AlarmEventOutcome.FIRED &&
                    (it.dayKey == today || it.dayKey == yesterday)
            }
            .maxByOrNull { it.firedAtEpochMs }
            ?.dayKey
            ?: today
        record(context, habitId, dayKey, scheduledMinutes, outcome, message = null, atEpochMs = atEpochMs)
    }.onFailure { Log.w(TAG, "Could not mark $outcome for $habitId@$scheduledMinutes", it) }
        .getOrNull()

    /**
     * The day's alarms for [habitId] in chronological order \u2014 **one slot per
     * scheduled time**, plus any time that fired but is no longer in the
     * schedule (an alarm edited away after it went off still really happened).
     *
     * [nowMinutes] is injectable so the PENDING/MISSED derivation is testable
     * without depending on the wall clock.
     */
    fun daySlots(
        data: AppData,
        habitId: String,
        dayKey: String = Dates.todayKey(),
        nowMinutes: Int = Dates.nowMinutes(),
    ): List<AlarmDaySlot> {
        val scheduled = data.habits.firstOrNull { it.id == habitId }?.alarmMinutes.orEmpty()
        val isToday = dayKey == Dates.todayKey()
        val byTime = data.alarmEventsOn(habitId, dayKey).groupBy { it.scheduledMinutes }

        val fromSchedule = scheduled.map { minutes ->
            val events = byTime[minutes].orEmpty().sortedBy { it.firedAtEpochMs }
            AlarmDaySlot(minutes, slotState(events, isToday, nowMinutes, minutes), events)
        }
        val offSchedule = byTime.keys
            .filterNot { it in scheduled }
            .sorted()
            .map { minutes ->
                val events = byTime.getValue(minutes).sortedBy { it.firedAtEpochMs }
                AlarmDaySlot(minutes, slotState(events, isToday, nowMinutes, minutes), events)
            }

        return (fromSchedule + offSchedule).sortedBy { it.scheduledMinutes }
    }

    /**
     * How many of [habitId]'s alarms on [dayKey] the user answered, and how many
     * the habit is scheduled to ring \u2014 the "2 of 3 answered today" summary.
     */
    fun answeredToday(
        data: AppData,
        habitId: String,
        dayKey: String = Dates.todayKey(),
        nowMinutes: Int = Dates.nowMinutes(),
    ): Pair<Int, Int> {
        val slots = daySlots(data, habitId, dayKey, nowMinutes)
        return slots.count { it.isAnswered } to slots.size
    }

    /**
     * The recorded state for one time: the newest event's outcome, or the
     * derived state when the alarm left no trace at all.
     *
     * A time in the future on *today* is [AlarmSlotState.PENDING]; the same time
     * on a past day, or one already elapsed today with no event, is
     * [AlarmSlotState.MISSED]. Both are honest: the app cannot distinguish "the
     * alarm was never armed" from "it was armed and the OS dropped it", and
     * claiming PENDING for a time that has passed would read as "still to come".
     */
    private fun slotState(
        events: List<HabitAlarmEvent>,
        isToday: Boolean,
        nowMinutes: Int,
        minutes: Int,
    ): AlarmSlotState {
        when (events.lastOrNull()?.outcome) {
            AlarmEventOutcome.FIRED -> return AlarmSlotState.FIRED
            AlarmEventOutcome.ACKNOWLEDGED -> return AlarmSlotState.ACKNOWLEDGED
            AlarmEventOutcome.DISMISSED -> return AlarmSlotState.DISMISSED
            AlarmEventOutcome.SNOOZED -> return AlarmSlotState.SNOOZED
            null -> Unit
        }
        return if (isToday && minutes > nowMinutes) AlarmSlotState.PENDING else AlarmSlotState.MISSED
    }

    /** Keeps the newest [MAX_ALARM_EVENTS] events so the blob stays bounded. */
    private fun prune(events: List<HabitAlarmEvent>): List<HabitAlarmEvent> =
        if (events.size <= MAX_ALARM_EVENTS) events
        else events.sortedByDescending { it.firedAtEpochMs }.take(MAX_ALARM_EVENTS)
}

/**
 * "07:00" for a stored epoch millis \u2014 the wall-clock label for a timestamp in
 * the alarm detail view.
 *
 * Local time on purpose (the alarm is a local appointment), 24-hour like every
 * other alarm label, and defensive about the 0 sentinel [HabitAlarmEvent] uses
 * for "this event was first seen as an answer": that renders as the empty
 * string so callers can fall back to the answered-at time instead of printing a
 * misleading 00:00.
 */
fun alarmTimeOfDayLabel(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val time = java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return String.format(java.util.Locale.US, "%02d:%02d", time.hour, time.minute)
}
