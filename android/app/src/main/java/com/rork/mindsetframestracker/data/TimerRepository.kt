package com.rork.mindsetframestracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Persistence for the live timers, deliberately kept in its **own**
 * SharedPreferences file rather than inside [AppData].
 *
 * Why not [MindsetRepository]'s blob? Two reasons:
 *
 * 1. **Write frequency.** A timer writes its state on every start / pause /
 *    resume / stop / extend. Rolling a copy of the whole app blob (habits,
 *    check-ins, mood history, reflections, activity records) on each of those
 *    writes is pure waste, and would repeatedly rewrite the \"last known good\"
 *    backup slot [MindsetRepository] keeps.
 * 2. **Blast radius.** The timer is written from a foreground service and from
 *    AlarmManager broadcast receivers that run in a **fresh, UI-less process**.
 *    Keeping it separate means a bug in timer persistence can never corrupt
 *    habit history, and vice versa.
 *
 * Everything here is idempotent and safe to call from any process: the alarm
 * receiver, the service, and the UI all go through the same functions, which
 * is what makes \"the popup appears exactly once\" hold even when three
 * different entry points race for the same event.
 */
class TimerRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // \u2500\u2500 Active timer \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /** The current run, or null when idle. Never throws \u2014 a corrupt blob reads as idle. */
    fun loadActive(): ActiveTimer? {
        val raw = prefs.getString(KEY_ACTIVE, null) ?: return null
        return runCatching { json.decodeFromString<ActiveTimer>(raw) }
            .onFailure { Log.w(TAG, "Failed to decode active timer \u2014 treating as idle", it) }
            .getOrNull()
    }

    fun saveActive(timer: ActiveTimer?) {
        runCatching {
            val editor = prefs.edit()
            if (timer == null) editor.remove(KEY_ACTIVE)
            else editor.putString(KEY_ACTIVE, json.encodeToString(ActiveTimer.serializer(), timer))
            editor.apply()
        }.onFailure { Log.w(TAG, "Failed to persist active timer", it) }
    }

    fun clearActive() = saveActive(null)

    // \u2500\u2500 Completion events (the \"show exactly once\" ledger) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /**
     * Records the completion [event] **if this is the first caller for its
     * [TimerCompletionEvent.eventId]**, and reports whether this caller was
     * that first one.
     *
     * This single atomic-style read-modify-write in SharedPreferences is the
     * heart of the one-time guarantee: the AlarmManager receiver, the
     * foreground service and the in-app tick can all decide independently
     * that the timer is due, but only the first one to get here wins \u2014 the
     * rest see `Recorded(existing = true)` and post nothing, pop nothing.
     */
    @Synchronized
    fun recordCompletion(event: TimerCompletionEvent): RecordOutcome {
        if (!wasRunStarted(event.runId)) {
            // No started run behind this event: it cannot be a real completion,
            // so it must never reach the popup or the alert.
            return RecordOutcome.Rejected
        }
        val existing = loadPendingEvent()
        if (existing != null && existing.eventId == event.eventId) {
            return RecordOutcome.AlreadyRecorded(existing)
        }
        if (isEventHandled(event.eventId)) {
            return RecordOutcome.AlreadyRecorded(existing ?: event)
        }
        writeEvent(event, KEY_PENDING_EVENT)
        return RecordOutcome.Recorded(event)
    }

    /** The completion event that has fired but has not been shown/consumed yet, if any. */
    fun loadPendingEvent(): TimerCompletionEvent? {
        val raw = prefs.getString(KEY_PENDING_EVENT, null) ?: return null
        return runCatching { json.decodeFromString<TimerCompletionEvent>(raw) }
            .onFailure { Log.w(TAG, "Failed to decode pending timer event", it) }
            .getOrNull()
    }

    /** Append-only ledger of every event id already shown \u2014 the durable \"once\" guard. */
    fun handledEventIds(): List<String> {
        val raw = prefs.getString(KEY_HANDLED_EVENTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    fun isEventHandled(eventId: String): Boolean = handledEventIds().contains(eventId)

    /**
     * Marks [eventId] as shown and clears it from the pending slot. Returns
     * true only for the very first call, so a caller can safely treat a
     * `false` result as \"somebody else already showed this popup\".
     */
    @Synchronized
    fun consumeEvent(eventId: String): Boolean {
        val pending = loadPendingEvent()
        if (pending?.eventId == eventId) {
            prefs.edit().remove(KEY_PENDING_EVENT).apply()
        }
        if (isEventHandled(eventId)) return false
        val ledger = (handledEventIds() + eventId).takeLast(MAX_HANDLED_EVENTS)
        runCatching {
            prefs.edit().putString(KEY_HANDLED_EVENTS, json.encodeToString(ledger)).apply()
        }.onFailure { Log.w(TAG, "Failed to persist handled timer event", it) }
        return true
    }

    /**
     * One-shot guard for the completion *re-notification* (the reminder that
     * re-alerts if the user dismissed the ringing screen without acting).
     * Separate from [consumeEvent] because a reminder may fire while the
     * popup itself is still legitimately pending.
     */
    @Synchronized
    fun markReminderSent(eventId: String): Boolean {
        val sentKey = "$KEY_REMINDER_PREFIX$eventId"
        if (prefs.getBoolean(sentKey, false)) return false
        prefs.edit().putBoolean(sentKey, true).apply()
        return true
    }

    /** Used by the Settings \"Reset timer state\" action and by tests. */
    fun clearAll() {
        runCatching {
            val editor = prefs.edit().clear()
            editor.apply()
        }.onFailure { Log.w(TAG, "Failed to clear timer state", it) }
    }

    // \u2500\u2500 Started-run ledger (session provenance) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /**
     * Remembers that [runId] was genuinely started **by the user** on this
     * device.
     *
     * This is what ties a completion popup to an actual timer session. A
     * completion carries the id of the run that produced it, and the UI only
     * ever shows the popup for a run that appears here. Without this ledger any
     * orphaned record in the pending slot \u2014 a leftover from an older build,
     * or a half-written event \u2014 would render a "timer complete" popup with no
     * timer behind it, which is precisely the "why is this on my Home screen?"
     * bug this guard exists to prevent.
     */
    @Synchronized
    fun recordRunStarted(runId: String) {
        if (runId.isBlank()) return
        val runs = (startedRunIds() + runId).takeLast(MAX_STARTED_RUNS)
        runCatching {
            prefs.edit().putString(KEY_STARTED_RUNS, json.encodeToString(runs)).apply()
        }.onFailure { Log.w(TAG, "Failed to persist started timer run", it) }
    }

    /** Every run id started on this device, oldest first (bounded). */
    fun startedRunIds(): List<String> {
        val raw = prefs.getString(KEY_STARTED_RUNS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    /** True when [runId] is a run the user actually started. */
    fun wasRunStarted(runId: String): Boolean =
        runId.isNotBlank() && startedRunIds().contains(runId)

    /**
     * The pending completion that may be shown as a **popup**, or null.
     *
     * Stricter than [loadPendingEvent] on purpose: a popup interrupts whatever
     * the user is doing, so it must prove three things first.
     *
     * 1. **Provenance** \u2014 the run that produced it was actually started here
     *    ([wasRunStarted]). No started run, no popup.
     * 2. **Freshness** \u2014 it fired within [TIMER_POPUP_GRACE_MILLIS].
     * 3. **Not already shown** \u2014 the handled-ledger still gets the final say.
     *
     * Anything failing those checks is *consumed*, not merely hidden, so it can
     * never resurface on a later launch or resume.
     */
    @Synchronized
    fun loadPopupEvent(nowMs: Long = System.currentTimeMillis()): TimerCompletionEvent? {
        val pending = loadPendingEvent() ?: return null
        val orphaned = !wasRunStarted(pending.runId)
        val expired = pending.firedAtEpochMs <= 0L ||
            nowMs - pending.firedAtEpochMs > TIMER_POPUP_GRACE_MILLIS
        if (orphaned || expired || isEventHandled(pending.eventId)) {
            consumeEvent(pending.eventId)
            Log.d(
                TAG,
                "Discarding pending timer event ${pending.eventId} " +
                    "(orphaned=$orphaned expired=$expired)",
            )
            return null
        }
        return pending
    }

    private fun writeEvent(event: TimerCompletionEvent, key: String) {
        runCatching {
            prefs.edit().putString(key, json.encodeToString(TimerCompletionEvent.serializer(), event)).apply()
        }.onFailure { Log.w(TAG, "Failed to persist timer event", it) }
    }

    /** Outcome of [recordCompletion]. */
    sealed class RecordOutcome {
        /** This caller is the first to observe this event \u2014 it should now alert. */
        data class Recorded(val event: TimerCompletionEvent) : RecordOutcome()

        /** Somebody already recorded it \u2014 do not alert again. */
        data class AlreadyRecorded(val event: TimerCompletionEvent) : RecordOutcome()

        /**
         * The event names a run that was never started here \u2014 it is not a
         * completion of anything the user did, so it must never alert.
         */
        object Rejected : RecordOutcome()
    }

    private companion object {
        const val TAG = "TimerRepository"
        const val PREFS_NAME = "mindset_timers"
        const val KEY_ACTIVE = "active_timer"
        const val KEY_PENDING_EVENT = "pending_event"
        const val KEY_HANDLED_EVENTS = "handled_event_ids"
        const val KEY_STARTED_RUNS = "started_run_ids"
        const val KEY_REMINDER_PREFIX = "reminder_sent_"
        /** Bounded so the ledger can never grow without limit. */
        const val MAX_HANDLED_EVENTS = 100

        /** Same bound for the started-run ledger. */
        const val MAX_STARTED_RUNS = 100
    }
}
