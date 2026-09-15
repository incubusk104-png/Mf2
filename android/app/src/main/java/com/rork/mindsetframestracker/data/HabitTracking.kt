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

/**
 * **Every** entry for [habitId] on [dayKey], newest first.
 *
 * The single-entry [habitLogOn] above answers "was anything logged"; this
 * answers "what was logged". They are different questions for a COUNT habit:
 * "Drink water" tapped five times in a day is five entries, and anything that
 * wants to show or total them must not stop at the first.
 */
fun AppData.habitLogsOn(habitId: String, dayKey: String): List<HabitLogEntry> =
    habitLogsFor(habitId).filter { it.dayKey == dayKey }

/** Sum of every [HabitLogEntry.count] recorded for [habitId] on [dayKey]. */
fun AppData.habitCountOn(habitId: String, dayKey: String): Int =
    habitLogsOn(habitId, dayKey).sumOf { it.count ?: 0 }

/**
 * Glasses of water so far today.
 *
 * This is the summing counterpart to [habitLogOn]'s single-entry semantics,
 * and it exists because the two are genuinely different: for a TIMER habit
 * "today's entry" is the right thing to show, but for a COUNT habit it is
 * actively wrong — a user who has had five glasses must not be shown
 * "1 glass". Callers showing progress against a COUNT goal use this.
 */
fun AppData.todayCount(habitId: String): Int = habitCountOn(habitId, Dates.todayKey())

/** Seconds recorded for [habitId] today, summed across every entry. */
fun AppData.todaySeconds(habitId: String): Int =
    habitLogsOn(habitId, Dates.todayKey()).sumOf { it.durationSeconds ?: 0 }

/** One habit's totals over a set of days — the shape both Weekly and the
 *  dialog's "this week" strip read. */
data class HabitTotals(
    val sessions: Int,
    val totalCount: Int,
    val totalSeconds: Int,
    val notes: Int,
) {
    val isEmpty: Boolean get() = sessions == 0

    companion object {
        val EMPTY = HabitTotals(sessions = 0, totalCount = 0, totalSeconds = 0, notes = 0)
    }
}

/** Totals for [habitId] across [dayKeys] (a week, a month, any window). */
fun AppData.habitTotalsOver(habitId: String, dayKeys: Collection<String>): HabitTotals {
    val days = dayKeys.toSet()
    val entries = habitLogs.filter { it.habitId == habitId && it.dayKey in days }
    if (entries.isEmpty()) return HabitTotals.EMPTY
    return HabitTotals(
        sessions = entries.size,
        totalCount = entries.sumOf { it.count ?: 0 },
        totalSeconds = entries.sumOf { it.durationSeconds ?: 0 },
        notes = entries.count { !it.note.isNullOrBlank() || !it.title.isNullOrBlank() },
    )
}

/** Every habit that has at least one log entry in [dayKeys], in habit order. */
fun AppData.habitsLoggedOver(dayKeys: Collection<String>): List<Habit> {
    val days = dayKeys.toSet()
    val logged = habitLogs.filter { it.dayKey in days }.map { it.habitId }.toSet()
    return habits.filter { it.id in logged }
}

/**
 * True when [habitId] was completed on [dayKey] by **either** route — the
 * check-in boolean or a recorded log entry.
 *
 * Why both: the check-in map is what streaks, badges and the heatmap are built
 * on, and every write path in the app sets it. But a log entry is itself
 * evidence the user did the thing, and it is written by paths (a synced
 * activity, a restored backup from a build that logged without checking in)
 * that may legitimately have no check-in behind them. Treating a real record
 * as "not done" would make the Weekly and Insight views disagree with the
 * record the user just created — which is exactly the disconnect this exists
 * to remove. [InsightsScreen] already merged synced activity into "done" this
 * way; this is that same rule, stated once and reused.
 */
fun AppData.isHabitDoneOn(habitId: String, dayKey: String): Boolean =
    isCheckedOn(habitId, dayKey) ||
        habitLogs.any { it.habitId == habitId && it.dayKey == dayKey }

/** Habits completed on [dayKey], counting a log entry as completion. */
fun AppData.completedCountOnIncludingLogs(dayKey: String): Int =
    habits.count { isHabitDoneOn(it.id, dayKey) }

// ─────────────────────────────────────────────────────────────────────────────
// Alarm behaviour — what the RING does, as opposed to what the input needs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * What a habit's **alarm** does the moment it rings.
 *
 * This is a different question from [HabitTrackingMode] and it exists because
 * routing the ring through the tracking mode alone gets two cases wrong:
 *
 * A habit's mode describes the *input* it needs. The ring has to decide
 * something else: does the user need to be asked anything at all right now, or
 * does the very act of dismissing the alarm already answer the question?
 *
 * For "Take a vitamin" it does — the tap on Stop *is* the record, and raising
 * a dialog to confirm what the user just told you by dismissing is pure
 * friction. For "Journal" it does not: no tap can invent a sentence, so the
 * note has to be asked for. For "Walk 30 minutes" neither a tap nor a sentence
 * works — the habit is a measurement, so the ring has to hand over the
 * measuring tool.
 *
 * ## Why this is derived from the mode rather than from a sport/non-sport test
 *
 * The obvious-sounding rule ("sport habits get a dialog, everything else is
 * one tap") silently breaks habits that are not sport but are still measured:
 * `read`, `tidy`, `plan`, `noPhone`, `meeting` and `stretch` all default to
 * [HabitTrackingMode.TIMER]. Deciding the ring by "is this a sport?" would
 * delete six working timers. Deriving from the mode keeps every habit's own
 * tool intact and still gives the user exactly the behaviour they asked for on
 * the examples that matter (water, vitamins → tap; walking, running → the
 * stopwatch; journal → the note field).
 *
 * Sport is a separate, *additive* dimension — see [Habit.isSportActivity] —
 * that additionally offers the Strava / Health Connect / Polar sources. It
 * never decides whether a dialog appears.
 */
enum class HabitAlarmBehavior {
    /** The dismissal IS the record. No dialog, nothing to ask. */
    ONE_TAP,

    /**
     * A dialog, but a tiny one — the record needs one value a tap cannot
     * imply: a count ("how many glasses?") or a note ("what did you write?").
     */
    MINIMAL_INPUT,

    /** The habit is a measurement: hand over its own timer/stopwatch. */
    TOOL,
}

/**
 * The ring behaviour for this habit, derived from [Habit.trackingModeOrDefault]
 * so an explicit user choice always decides it — the same precedence every
 * other part of the tracking model uses.
 */
val Habit.alarmBehavior: HabitAlarmBehavior
    get() = when (trackingModeOrDefault) {
        HabitTrackingMode.CHECK -> HabitAlarmBehavior.ONE_TAP
        HabitTrackingMode.COUNT, HabitTrackingMode.JOURNAL -> HabitAlarmBehavior.MINIMAL_INPUT
        HabitTrackingMode.TIMER, HabitTrackingMode.STOPWATCH -> HabitAlarmBehavior.TOOL
    }

/**
 * The physical-movement activities that a step/heart-rate source can actually
 * supply data for.
 *
 * **One list, not two.** This set previously existed verbatim in both
 * [com.rork.mindsetframestracker.integrations.MindsetHealthConnectClient] and
 * [com.rork.mindsetframestracker.integrations.PolarClient] — the same 46 ids,
 * copy-pasted. Two copies of "which activities count as movement" is how a
 * habit ends up supported by the Health Connect row and silently missing from
 * the Polar one, or vice versa; the failure is invisible because both lists
 * look right in isolation. They are now one list, and the source clients read
 * it from here.
 */
val SPORT_ACTIVITY_ICON_IDS: Set<String> = setOf(
    "walking", "running", "basketball", "gym", "stretch",
    "strava_badminton", "strava_crossfit", "strava_dance",
    "strava_elliptical", "strava_football", "strava_hiit",
    "strava_hike", "strava_inline_skate", "strava_pilates",
    "strava_racquetball", "strava_ride", "strava_rock_climb",
    "strava_rowing", "strava_squash", "strava_stair_stepper",
    "strava_swim", "strava_tennis", "strava_trail_run",
    "strava_volleyball", "strava_weight_training", "strava_workout",
    "strava_yoga", "strava_mountain_bike_ride", "strava_gravel_ride",
    "strava_ebike_ride", "strava_emtb_ride", "strava_virtual_ride",
    "strava_virtual_run", "strava_virtual_rowing", "strava_pickleball",
    "strava_padel", "strava_cricket", "strava_skateboarding",
    "strava_ice_skate", "strava_snowboard", "strava_snowshoe",
    "strava_alpine_ski", "strava_backcountry_ski", "strava_nordic_ski",
    "strava_roller_ski", "table_tennis",
)

/** True when [iconId] names an activity a movement data source can supply. */
fun isSportActivityIcon(iconId: String?): Boolean =
    iconId != null && iconId in SPORT_ACTIVITY_ICON_IDS

/**
 * True when this habit is a physical-movement activity.
 *
 * Additive only: it decides whether the ring dialog *also* offers the Strava /
 * Health Connect / Polar sources, never whether a dialog appears at all (that
 * is [Habit.alarmBehavior], so a non-sport TIMER habit keeps its timer).
 */
val Habit.isSportActivity: Boolean
    get() = isSportActivityIcon(iconId)
