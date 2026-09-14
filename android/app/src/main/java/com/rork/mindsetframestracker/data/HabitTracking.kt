package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * **How** a habit is tracked when the user completes it.
 *
 * The app used to have exactly one completion gesture \u2014 tap the habit row,
 * it flips to checked, today is recorded \u2014 no matter what the habit was.
 * That is wrong for most real habits: "Walk 30 minutes" is not something you
 * *assert* with a tap, it is something you measure; "Journal" produces text,
 * not a duration; "Drink water" is a count. A meditation session and a
 * gratitude note need different inputs, so the input has to be a property of
 * the habit rather than of the screen.
 *
 * Each mode answers one question, and [com.rork.mindsetframestracker.ui.components.HabitTrackingSheet]
 * renders exactly the input that question needs:
 *
 * | mode        | question it answers            | input                | record            |
 * |-------------|--------------------------------|----------------------|-------------------|
 * | [CHECK]     | did you do it?                 | a tap                | check-in          |
 * | [STOPWATCH] | how long did it take?          | count-up, open-ended | duration          |
 * | [TIMER]     | did you do the full duration?  | count-down to target | duration          |
 * | [JOURNAL]   | what did you write?            | title + note         | title + note      |
 * | [COUNT]     | how many?                      | a stepper            | amount + unit     |
 *
 * ## STOPWATCH vs TIMER \u2014 why both exist, and when each fits
 *
 * They are not two spellings of one thing, and picking the right one per
 * habit is the point of this enum:
 *
 *  - **STOPWATCH** is *recording* something whose length you do not know in
 *    advance \u2014 a walk, a run, a swim. You start it, you do the thing, you
 *    stop it; the elapsed time is the result. A stopwatch that stopped
 *    itself would not be a stopwatch, so it never "finishes" on its own.
 *  - **TIMER** is *committing* to a length ahead of time and being told when
 *    you have served it \u2014 a 20-minute meditation, a 45-minute gym block, a
 *    15-minute tidy. You set the target and the alarm rings when it is up.
 *
 * Collapsing these into one control is what makes a tracker feel generic; the
 * distinction is exactly what [HabitTrackingMode] preserves.
 */
@Serializable
enum class HabitTrackingMode { CHECK, STOPWATCH, TIMER, JOURNAL, COUNT }

/**
 * The tracking configuration a habit inherits when the user has not chosen
 * one explicitly, derived from its catalog icon.
 *
 * @param mode the input this habit gets out of the box.
 * @param targetSeconds count-down target for [HabitTrackingMode.TIMER], or an
 *   optional goal for [HabitTrackingMode.STOPWATCH]. Null = open-ended.
 * @param targetCount amount to aim for in [HabitTrackingMode.COUNT].
 * @param unit what the count counts ("glasses", "pages"), for display.
 */
data class HabitTrackingDefaults(
    val mode: HabitTrackingMode,
    val targetSeconds: Int? = null,
    val targetCount: Int? = null,
    val unit: String? = null,
)

/**
 * Per-icon tracking defaults.
 *
 * Keyed by [HabitIcon.id]. Deliberately a lookup rather than a field on every
 * catalog row: the catalog carries ~200 entries, and a table here keeps the
 * defaults readable, reviewable and changeable in one place \u2014 and lets
 * [defaultsFor] fall back sensibly for any icon (including the long
 * `strava_*` block) that is not listed.
 *
 * The assignments follow the two rules above: movement/endurance \u2192
 * STOPWATCH, a fixed sitting/commitment \u2192 TIMER, writing/reflection \u2192
 * JOURNAL, quantities \u2192 COUNT, and anything genuinely binary \u2192 CHECK.
 */
private val HABIT_TRACKING_DEFAULTS: Map<String, HabitTrackingDefaults> = mapOf(
    // \u2500\u2500 Movement: measured, unknown length \u2192 stopwatch \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    "walking" to HabitTrackingDefaults(HabitTrackingMode.STOPWATCH),
    "running" to HabitTrackingDefaults(HabitTrackingMode.STOPWATCH),
    "basketball" to HabitTrackingDefaults(HabitTrackingMode.STOPWATCH),

    // \u2500\u2500 Fixed commitments: a length you set ahead of time \u2192 timer \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    "gym" to HabitTrackingDefaults(HabitTrackingMode.TIMER, targetSeconds = 45 * 60),
    "read" to HabitTrackingDefaults(HabitTrackingMode.TIMER, targetSeconds = 20 * 60),
    "stretch" to HabitTrackingDefaults(HabitTrackingMode.TIMER, targetSeconds = 10 * 60),
    "tidy" to HabitTrackingDefaults(HabitTrackingMode.TIMER, targetSeconds = 15 * 60),
    "plan" to HabitTrackingDefaults(HabitTrackingMode.TIMER, targetSeconds = 10 * 60),
    "noPhone" to HabitTrackingDefaults(HabitTrackingMode.TIMER, targetSeconds = 30 * 60),
    "meeting" to HabitTrackingDefaults(HabitTrackingMode.TIMER, targetSeconds = 15 * 60),

    // \u2500\u2500 Writing and reflection: the output is text \u2192 journal \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    "journal" to HabitTrackingDefaults(HabitTrackingMode.JOURNAL),
    "gratitude" to HabitTrackingDefaults(HabitTrackingMode.JOURNAL),

    // \u2500\u2500 Quantities \u2192 count \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    "water" to HabitTrackingDefaults(HabitTrackingMode.COUNT, targetCount = 8, unit = "glasses"),
    "protein" to HabitTrackingDefaults(HabitTrackingMode.COUNT, targetCount = 3, unit = "servings"),

    // \u2500\u2500 Genuinely binary: done or not \u2192 check \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    "medicine" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "biotin" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "cholesterol" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "sleep" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "todoList" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "inbox" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "message" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "compliment" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "noSpend" to HabitTrackingDefaults(HabitTrackingMode.CHECK),
    "spend" to HabitTrackingDefaults(HabitTrackingMode.COUNT, targetCount = 1, unit = "entries"),
)

/**
 * The tracking configuration for [iconId], falling back by exercise type.
 *
 * The fallback matters: the catalog lists ~150 `strava_*` sport icons that
 * have no explicit entry above, and defaulting all of them to a bare checkbox
 * would reintroduce exactly the problem this model exists to fix. Anything
 * sport-shaped therefore defaults to a stopwatch \u2014 the one tracking mode
 * that is correct for *any* activity whose length you find out by doing it.
 */
fun defaultsFor(iconId: String?): HabitTrackingDefaults = when {
    iconId == null -> HabitTrackingDefaults(HabitTrackingMode.CHECK)
    HABIT_TRACKING_DEFAULTS.containsKey(iconId) ->
        HABIT_TRACKING_DEFAULTS.getValue(iconId)
    // Every Strava sport is an activity you time rather than assert.
    iconId.startsWith("strava_") -> HabitTrackingDefaults(HabitTrackingMode.STOPWATCH)
    else -> HabitTrackingDefaults(HabitTrackingMode.CHECK)
}

/**
 * The mode this habit is actually tracked with.
 *
 * An explicit [Habit.trackingMode] always wins \u2014 the user's choice is never
 * second-guessed by a default. Null means "never configured", which is the
 * case for every habit created before this feature existed, and those resolve
 * from their icon so an existing install instantly gets the right tool per
 * habit instead of staying on the generic form.
 */
val Habit.trackingModeOrDefault: HabitTrackingMode
    get() = trackingMode ?: defaultsFor(iconId).mode

/** Count-down target (TIMER) or goal (STOPWATCH) in seconds; 0 = open-ended. */
val Habit.trackingTargetSecondsOrDefault: Int
    get() = trackingTargetSeconds ?: defaultsFor(iconId).targetSeconds ?: 0

/** COUNT goal; 0 = no goal, just record what was done. */
val Habit.trackingTargetCountOrDefault: Int
    get() = trackingTargetCount ?: defaultsFor(iconId).targetCount ?: 0

/** Unit label for a COUNT habit ("glasses"), falling back to a neutral word. */
val Habit.trackingUnitOrDefault: String
    get() = trackingUnit?.takeIf { it.isNotBlank() }
        ?: defaultsFor(iconId).unit
        ?: "times"

/**
 * One recorded completion of a habit, carrying whatever the habit's tracking
 * mode actually produced.
 *
 * The check-in map in [AppData] already answers *"was this habit done today"*
 * \u2014 it is the thing streaks, badges and the heatmap are built on, and it is
 * deliberately a plain set of day keys so those stay cheap. This is the
 * complementary record: *what was actually done*. A timed habit stores its
 * duration, a journal habit stores its title and text, a count habit stores
 * its amount. They are separate on purpose \u2014 a journal entry that also
 * counted as a check-in should not force the streak maths to parse text, and
 * a duration should not be lost just because the check-in is a boolean.
 *
 * Entries are append-only: a later completion the same day adds a second
 * entry rather than overwriting the first ("I walked twice today" is real
 * information, not a correction).
 */
@Serializable
data class HabitLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val habitId: String,
    /** ISO day key the entry belongs to ("2026-09-15"). */
    val dayKey: String,
    /** Which tool produced this entry. */
    val mode: HabitTrackingMode,
    /** Journal headline, when the mode asks for one. */
    val title: String? = null,
    /** Journal body text. */
    val note: String? = null,
    /** Measured session length in seconds (STOPWATCH / TIMER). */
    val durationSeconds: Int? = null,
    /** Recorded amount (COUNT). */
    val count: Int? = null,
    /** What [count] counts, snapshot at record time. */
    val unit: String? = null,
    /** When it was recorded \u2014 also the tie-breaker for "most recent". */
    val recordedAtEpochMs: Long = 0L,
)

/** All entries for [habitId], newest first. */
fun AppData.habitLogsFor(habitId: String): List<HabitLogEntry> =
    habitLogs.filter { it.habitId == habitId }.sortedByDescending { it.recordedAtEpochMs }

/** The most recent entry for [habitId], or null when nothing was recorded yet. */
fun AppData.latestHabitLog(habitId: String): HabitLogEntry? = habitLogsFor(habitId).firstOrNull()

/** Entry for [habitId] on one day, if any \u2014 used for "you already logged this". */
fun AppData.habitLogOn(habitId: String, dayKey: String): HabitLogEntry? =
    habitLogsFor(habitId).firstOrNull { it.dayKey == dayKey }
