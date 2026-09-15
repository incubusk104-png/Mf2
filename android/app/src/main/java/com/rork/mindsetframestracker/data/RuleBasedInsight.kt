package com.rork.mindsetframestracker.data

/**
 * Static, rule-based insight generator — zero external API cost, fully
 * on-device. Uses commonly cited general reference ranges, not medical
 * diagnosis. Always defers to a professional for anything outside typical
 * range.
 */
object RuleBasedInsight {

    fun forSteps(steps: Long): String = when {
        steps >= 10_000 -> "Great job — $steps steps is at or above the commonly cited daily target."
        steps >= 6_000 -> "$steps steps today — solid movement, a bit more gets you to the common 10,000 benchmark."
        steps > 0 -> "$steps steps logged. Any movement counts — try a short walk to build the habit."
        else -> "No steps recorded yet today."
    }

    fun forHeartRate(avgBpm: Int): String = when {
        avgBpm in 60..100 -> "Average heart rate of $avgBpm bpm falls within the commonly cited typical adult resting range (60-100 bpm)."
        avgBpm < 60 -> "$avgBpm bpm is below the commonly cited typical range — this can be normal for well-trained individuals, but if unexpected, worth mentioning to a doctor."
        else -> "$avgBpm bpm is above the commonly cited typical resting range. If this persists, checking with a healthcare professional is a good idea."
    }

    fun forSleep(minutes: Long): String {
        val hours = minutes / 60.0
        return when {
            hours >= 7 -> "%.1f hours of sleep — within the commonly recommended 7-9 hour range for adults.".format(hours)
            hours >= 5 -> "%.1f hours of sleep — a bit under the commonly recommended range. Worth prioritizing rest tonight.".format(hours)
            else -> "%.1f hours of sleep logged — noticeably below the typical recommended range.".format(hours)
        }
    }

    fun forStreak(currentStreak: Int, habitName: String): String = when {
        currentStreak >= 30 -> "$currentStreak days strong on \"$habitName\" — well into long-term habit territory."
        currentStreak >= 7 -> "$currentStreak day streak on \"$habitName\" — past the hardest first week."
        currentStreak >= 1 -> "$currentStreak day streak on \"$habitName\" — keep it going."
        else -> "Start today on \"$habitName\" — every streak begins at day one."
    }

    fun forActivity(record: ActivityRecord): String = when {
        record.steps != null -> forSteps(record.steps)
        record.heartRateAvg != null -> forHeartRate(record.heartRateAvg)
        record.durationMinutes != null -> "${record.durationMinutes} minute ${record.activityType} logged via ${record.source.replace("_", " ")}."
        else -> "${record.activityType} synced from ${record.source.replace("_", " ")}."
    }

    /**
     * Insight for a **counted** habit — glasses of water, servings of protein.
     *
     * Counts needed their own line because the existing helpers only speak
     * steps/heart-rate/sleep, so a habit like "Drink water" produced no insight
     * text at all no matter how much was logged. [target] is the habit's own
     * goal, so the wording can speak to that habit rather than to a generic
     * benchmark — and when there is no target, the sentence reports the total
     * instead of inventing one.
     */
    fun forCount(unit: String, total: Int, target: Int? = null, label: String = "this habit"): String {
        val noun = unit.trim().ifEmpty { "entries" }
        val plural = if (total == 1) noun else pluralise(noun)
        return when {
            total <= 0 -> "Nothing logged for $label yet \u2014 the first one is the hardest."
            target != null && target > 0 && total >= target ->
                "$total $plural logged \u2014 that's the $target-$noun goal for $label met. Nice work."
            target != null && target > 0 ->
                "$total of $target $plural logged for $label \u2014 ${target - total} to go."
            else -> "$total $plural logged for $label."
        }
    }

    /**
     * Insight for a **measured** habit — minutes walked, read or stretched.
     *
     * Reports the total and, when there is a target, how it compares. Deliberately
     * free of pace/performance language: this is a habit tracker, not a coaching
     * app, and the same range reads as a win for a beginner and a failure for an
     * athlete.
     */
    fun forDuration(label: String, totalSeconds: Int, targetSeconds: Int? = null): String {
        if (totalSeconds <= 0) return "No time logged for $label yet."
        val minutes = totalSeconds / 60
        val shown = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
        return when {
            targetSeconds != null && targetSeconds > 0 && totalSeconds >= targetSeconds ->
                "$shown logged for $label \u2014 past the ${targetSeconds / 60}m target."
            targetSeconds != null && targetSeconds > 0 ->
                "$shown logged for $label of a ${targetSeconds / 60}m target."
            else -> "$shown logged for $label."
        }
    }

    /** Naive English plural — enough for the unit nouns the catalog uses. */
    private fun pluralise(noun: String): String = when {
        noun.endsWith("s") -> noun
        noun.endsWith("y") && noun.length > 1 -> noun.dropLast(1) + "ies"
        else -> noun + "s"
    }
}
