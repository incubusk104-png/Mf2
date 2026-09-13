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
    }

    private companion object {
        const val TAG = "TimerRepository"
        const val PREFS_NAME = "mindset_timers"
        const val KEY_ACTIVE = "active_timer"
        const val KEY_PENDING_EVENT = "pending_event"
        const val KEY_HANDLED_EVENTS = "handled_event_ids"
        const val KEY_REMINDER_PREFIX = "reminder_sent_"
        /** Bounded so the ledger can never grow without limit. */
        const val MAX_HANDLED_EVENTS = 100
    }
}
