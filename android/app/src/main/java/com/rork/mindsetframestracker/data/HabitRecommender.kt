package com.rork.mindsetframestracker.data

/** Category tag used to group and match suggested habits. */
enum class HabitCategory { HEALTH, MIND, PRODUCTIVITY, SOCIAL, FINANCE }

/** A single suggestable habit template. */
data class HabitSuggestion(
    val name: String,
    val category: HabitCategory,
    /** Short reason shown under the suggestion, e.g. "Pairs well with your morning routine". */
    val reason: String,
)

/**
 * Zero-cost, fully on-device "smart suggestions" engine. No LLM call, no
 * per-request bill — just curated templates matched against what the user
 * already tracks, same spirit as ContentPack's curated copy. Swap this out
 * for a real LLM-backed recommender later without changing the call site
 * (HabitRecommender.suggest(...) keeps the same signature).
 */
object HabitRecommender {

    private val library: List<HabitSuggestion> = listOf(
        HabitSuggestion("Drink a glass of water", HabitCategory.HEALTH, "A simple daily reset that's easy to keep up."),
        HabitSuggestion("10-minute walk", HabitCategory.HEALTH, "Low effort, works with almost any schedule."),
        HabitSuggestion("Stretch for 5 minutes", HabitCategory.HEALTH, "Pairs well with a morning or bedtime routine."),
        HabitSuggestion("Sleep by 11 PM", HabitCategory.HEALTH, "Most other habits get easier with consistent sleep."),
        HabitSuggestion("Meditate for 5 minutes", HabitCategory.MIND, "A short daily reset for focus and calm."),
        HabitSuggestion("Write 3 things you're grateful for", HabitCategory.MIND, "Quick, no equipment, strong mood impact."),
        HabitSuggestion("Journal for 5 minutes", HabitCategory.MIND, "Helps track patterns over time."),
        HabitSuggestion("No phone in the first 30 minutes", HabitCategory.MIND, "Common companion habit to a morning routine."),
        HabitSuggestion("Plan tomorrow before bed", HabitCategory.PRODUCTIVITY, "Reduces morning decision fatigue."),
        HabitSuggestion("Read 10 pages", HabitCategory.PRODUCTIVITY, "Small, consistent, compounds fast."),
        HabitSuggestion("Inbox zero for 10 minutes", HabitCategory.PRODUCTIVITY, "Keeps small tasks from piling up."),
        HabitSuggestion("Tidy one area for 5 minutes", HabitCategory.PRODUCTIVITY, "Pairs well with an evening wind-down."),
        HabitSuggestion("Message one friend or family member", HabitCategory.SOCIAL, "Easy to sustain, strong long-term payoff."),
        HabitSuggestion("Compliment someone", HabitCategory.SOCIAL, "Takes seconds, noticeable mood boost."),
        HabitSuggestion("Track today's spending", HabitCategory.FINANCE, "Awareness habit that pays for itself."),
        HabitSuggestion("No impulse purchases today", HabitCategory.FINANCE, "Works well as a companion to spend tracking."),
    )

    /**
     * Returns up to [limit] suggestions the user doesn't already track,
     * preferring categories they haven't touched yet so the list rounds out
     * their routine instead of piling onto one area.
     */
    fun suggest(existingHabitNames: List<String>, limit: Int = 5): List<HabitSuggestion> {
        val existingLower = existingHabitNames.map { it.trim().lowercase() }.toSet()
        val candidates = library.filterNot { it.name.lowercase() in existingLower }

        val coveredCategories = library
            .filter { it.name.lowercase() in existingLower }
            .map { it.category }
            .toSet()

        val (uncoveredFirst, alreadyCovered) = candidates.partition { it.category !in coveredCategories }
        return (uncoveredFirst + alreadyCovered).take(limit)
    }
}
