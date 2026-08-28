package com.rork.mindsetframestracker.data

import com.rork.mindsetframestracker.util.LocalizationManager
import java.time.LocalDate

/** Static, mode-toned copy used across the app. Same layout, different voice.
 *
 * NOTE: This class is intentionally NOT `@Serializable`. It is parsed manually
 * by [LocalizationManager] to avoid the R8-generated DEX verifier issue. */
data class ModeCopy(
    val label: String,
    val tagline: String,
    val promptHeader: String,
    val habitsHeader: String,
    val habitsSub: String,
    val allDone: String,
    val emptyHabits: String,
)

/**
 * Content pack that loads mood-themed copy, prompts, and quotes from
 * JSON asset files via [LocalizationManager]. Only the active language's
 * content is loaded into memory; any missing mood/quote falls back to
 * English automatically.
 */
object ContentPack {

    private fun moodKey(mode: MoodMode): String = mode.name

    /** English fallback copy — used when no LanguageContent is loaded. */
    private val fallbackCopy: Map<MoodMode, ModeCopy> = mapOf(
        MoodMode.CALM to ModeCopy(
            label = "Calm",
            tagline = "Ease into the day, one gentle step at a time.",
            promptHeader = "A moment to reflect",
            habitsHeader = "Today's rhythm",
            habitsSub = "Move through these at your own pace.",
            allDone = "Everything done. Let the day settle softly.",
            emptyHabits = "No habits yet. Add one small ritual when you're ready.",
        ),
        MoodMode.FOCUSED to ModeCopy(
            label = "Focused",
            tagline = "Clear plan. Clean execution.",
            promptHeader = "Today's directive",
            habitsHeader = "Checklist",
            habitsSub = "Work the list. Top to bottom.",
            allDone = "List cleared. Well executed.",
            emptyHabits = "No habits defined. Add your first target.",
        ),
        MoodMode.MOTIVATED to ModeCopy(
            label = "Motivated",
            tagline = "Momentum is yours today — use it!",
            promptHeader = "Fuel for today",
            habitsHeader = "Let's stack some wins",
            habitsSub = "Every check builds the streak. Go get them!",
            allDone = "Full sweep! You crushed every single one today.",
            emptyHabits = "Blank slate, big energy. Add your first habit and go!",
        ),
        MoodMode.OVERWHELMED to ModeCopy(
            label = "Overwhelmed",
            tagline = "You don't have to do it all. You're doing fine.",
            promptHeader = "Just one tiny thing",
            habitsHeader = "Only if you have space",
            habitsSub = "One is enough today. Truly.",
            allDone = "Look at that — you showed up anyway. That matters.",
            emptyHabits = "Nothing here, and that's okay. Rest counts too.",
        ),
    )

    private val fallbackPrompts: Map<MoodMode, List<String>> = mapOf(
        MoodMode.CALM to listOf(
            "What is one thing you can let unfold slowly today, without rushing it?",
            "Take a slow breath. What already feels okay, right now, as it is?",
            "What would today look like if you gave yourself permission to go gently?",
            "Notice one small comfort around you. How can you return to it later today?",
            "What is something you can release your grip on, just for today?",
            "If today had a texture, what would you like it to feel like?",
            "What quiet moment yesterday deserves a little gratitude this morning?",
            "Where can you build in one unhurried pause before evening?",
            "What conversation today could you enter with softness instead of urgency?",
            "Choose one task to do at half speed today. Notice how it changes the doing.",
        ),
        MoodMode.FOCUSED to listOf(
            "Name your single highest-leverage task. Do it first.",
            "What one distraction will you eliminate before starting?",
            "Define done for today in one sentence.",
            "Which task have you been avoiding? Schedule it for the next hour.",
            "What deserves 45 minutes of deep, uninterrupted work today?",
            "Pick one thing to finish — not start. Finish it.",
            "What can you say no to today to protect your focus?",
            "Set your top three. Cross out the bottom two. Begin.",
            "Close every tab and app you don't need for the next hour. Then start.",
            "What's the smallest measurable outcome that would make today a win?",
        ),
        MoodMode.MOTIVATED to listOf(
            "You've got fire today — which goal gets the biggest push?",
            "What bold step have you been waiting for 'the right moment' to take? It's now.",
            "Channel this energy: what would future-you high-five you for doing today?",
            "Which habit can you level up today — a little longer, a little stronger?",
            "What's one thing you can do today that your streak will thank you for?",
            "Ride the wave: what's the very next action on your biggest ambition?",
            "Today's energy is a resource. Where will you invest it for the best return?",
            "What win can you lock in before noon?",
            "Who could you encourage today? Momentum shared is momentum doubled.",
            "Pick the scariest item on your list and take one concrete step toward it now.",
        ),
        MoodMode.OVERWHELMED to listOf(
            "Drink a glass of water. That's the whole task.",
            "Take three slow breaths. Nothing else is required right now.",
            "Pick the tiniest item on your list. Just that one. The rest can wait.",
            "Step outside or open a window for one minute of fresh air.",
            "Put one thing back in its place. One is plenty.",
            "Unclench your jaw, drop your shoulders. That counts as progress.",
            "Send yourself one kind sentence, the way a friend would.",
            "Set a timer for five minutes of rest. Rest is productive too.",
            "Write down everything on your mind for two minutes, then close the notebook.",
            "Cancel or postpone one thing today. Space is allowed.",
        ),
    )

    private val fallbackPremiumPrompts: Map<MoodMode, List<String>> = mapOf(
        MoodMode.CALM to listOf(
            "Recall a place where you feel completely at ease. Carry its stillness into one task today.",
            "What sound, scent, or light helps you settle? Invite it into your space this evening.",
            "Write one sentence to yourself beginning with: 'It is enough that I…'",
        ),
        MoodMode.FOCUSED to listOf(
            "Time-box your inbox: ten minutes, once. What will you do with the time saved?",
            "Identify the bottleneck task — the one blocking three others. Clear it first.",
            "What would this week look like if today were your most disciplined day?",
        ),
        MoodMode.MOTIVATED to listOf(
            "Write down the 90-day version of this goal. What does today contribute to it?",
            "Who benefits when you follow through today? Picture them. Now move.",
            "Double one habit today — two pages become four, ten minutes become twenty.",
        ),
        MoodMode.OVERWHELMED to listOf(
            "Name what's heaviest right now in three words. Naming it shrinks it.",
            "Give yourself permission to do one thing badly today. Done beats perfect.",
            "Text or tell one person how you're doing. Connection lightens the load.",
        ),
    )

    private val fallbackQuotes: Map<MoodMode, List<String>> = mapOf(
        MoodMode.CALM to listOf(
            "\u201CNature does not hurry, yet everything is accomplished.\u201D \u2014 Lao Tzu",
            "\u201CWithin you, there is a stillness and a sanctuary to which you can retreat at any time.\u201D \u2014 Hermann Hesse",
            "\u201CAlmost everything will work again if you unplug it for a few minutes, including you.\u201D \u2014 Anne Lamott",
            "\u201CPeace is the result of retraining your mind to process life as it is.\u201D \u2014 Wayne Dyer",
            "\u201CSlow down and everything you are chasing will come around and catch you.\u201D \u2014 John De Paola",
            "\u201CCalm mind brings inner strength and self-confidence.\u201D \u2014 Dalai Lama",
            "\u201CThere is more to life than increasing its speed.\u201D \u2014 Mahatma Gandhi",
            "\u201CTension is who you think you should be. Relaxation is who you are.\u201D \u2014 Chinese proverb",
            "\u201CSmile, breathe and go slowly.\u201D \u2014 Thich Nhat Hanh",
            "\u201CIn the midst of movement and chaos, keep stillness inside of you.\u201D \u2014 Deepak Chopra",
        ),
        MoodMode.FOCUSED to listOf(
            "\u201CConcentrate all your thoughts upon the work in hand.\u201D \u2014 Alexander Graham Bell",
            "\u201CIt is not enough to be busy. The question is: what are we busy about?\u201D \u2014 Henry David Thoreau",
            "\u201CThe successful warrior is the average man, with laser-like focus.\u201D \u2014 Bruce Lee",
            "\u201CYou can do two things at once, but you can't focus effectively on two things at once.\u201D \u2014 Gary Keller",
            "\u201CWhere focus goes, energy flows.\u201D \u2014 Tony Robbins",
            "\u201CDiscipline is choosing between what you want now and what you want most.\u201D \u2014 Abraham Lincoln (attr.)",
            "\u201CSimplicity boils down to two steps: identify the essential, eliminate the rest.\u201D \u2014 Leo Babauta",
            "\u201CWhat you stay focused on will grow.\u201D \u2014 Roy T. Bennett",
            "\u201CThe main thing is to keep the main thing the main thing.\u201D \u2014 Stephen Covey",
            "\u201CStarve your distractions, feed your focus.\u201D \u2014 Unknown",
        ),
        MoodMode.MOTIVATED to listOf(
            "\u201CThe way to get started is to quit talking and begin doing.\u201D \u2014 Walt Disney",
            "\u201CEnergy and persistence conquer all things.\u201D \u2014 Benjamin Franklin",
            "\u201CYou miss 100% of the shots you don't take.\u201D \u2014 Wayne Gretzky",
            "\u201CSuccess is the sum of small efforts, repeated day in and day out.\u201D \u2014 Robert Collier",
            "\u201CDo the hard jobs first. The easy jobs will take care of themselves.\u201D \u2014 Dale Carnegie",
            "\u201CIt always seems impossible until it's done.\u201D \u2014 Nelson Mandela",
            "\u201CAction is the foundational key to all success.\u201D \u2014 Pablo Picasso",
            "\u201CGreat things are done by a series of small things brought together.\u201D \u2014 Vincent van Gogh",
            "\u201CThe secret of getting ahead is getting started.\u201D \u2014 Mark Twain (attr.)",
            "\u201CDon't watch the clock; do what it does. Keep going.\u201D \u2014 Sam Levenson",
        ),
        MoodMode.OVERWHELMED to listOf(
            "\u201CYou don't have to see the whole staircase, just take the first step.\u201D \u2014 Martin Luther King Jr.",
            "\u201CNothing diminishes anxiety faster than action.\u201D \u2014 Walter Anderson",
            "\u201CBe gentle with yourself. You are doing the best you can.\u201D \u2014 Unknown",
            "\u201CHow do you eat an elephant? One bite at a time.\u201D \u2014 Proverb",
            "\u201CRest is not idleness.\u201D \u2014 John Lubbock",
            "\u201CStart where you are. Use what you have. Do what you can.\u201D \u2014 Arthur Ashe",
            "\u201CYou are allowed to be both a masterpiece and a work in progress.\u201D \u2014 Sophia Bush",
            "\u201CSometimes the most important thing in a whole day is the rest we take between two deep breaths.\u201D \u2014 Etty Hillesum",
            "\u201COne day at a time \u2014 this is enough.\u201D \u2014 Unknown",
            "\u201CIf you get tired, learn to rest, not to quit.\u201D \u2014 Banksy",
        ),
    )

    private val fallbackPremiumQuotes: Map<MoodMode, List<String>> = mapOf(
        MoodMode.CALM to listOf(
            "\u201CAdopt the pace of nature: her secret is patience.\u201D \u2014 Ralph Waldo Emerson",
            "\u201CQuiet the mind, and the soul will speak.\u201D \u2014 Ma Jaya Sati Bhagavati",
            "\u201CHe who is contented is rich.\u201D \u2014 Lao Tzu",
        ),
        MoodMode.FOCUSED to listOf(
            "\u201CEfficiency is doing things right; effectiveness is doing the right things.\u201D \u2014 Peter Drucker",
            "\u201CLack of direction, not lack of time, is the problem. We all have twenty-four hour days.\u201D \u2014 Zig Ziglar",
            "\u201CThe shorter way to do many things is to do only one thing at a time.\u201D \u2014 Mozart (attr.)",
        ),
        MoodMode.MOTIVATED to listOf(
            "\u201CWhether you think you can or you think you can't, you're right.\u201D \u2014 Henry Ford",
            "\u201CA year from now you may wish you had started today.\u201D \u2014 Karen Lamb",
            "\u201CSmall deeds done are better than great deeds planned.\u201D \u2014 Peter Marshall",
        ),
        MoodMode.OVERWHELMED to listOf(
            "\u201CIt's not the load that breaks you down, it's the way you carry it.\u201D \u2014 Lou Holtz",
            "\u201CFeelings are just visitors. Let them come and go.\u201D \u2014 Mooji",
            "\u201CYou can't calm the storm, so stop trying. What you can do is calm yourself. The storm will pass.\u201D \u2014 Timber Hawkeye",
        ),
    )

    private val fallbackStarterHabits: List<String> = listOf(
        "Drink a glass of water",
        "5 minutes of journaling",
        "Read 10 pages",
        "Morning stretch",
        "Evening walk",
    )

    // ── Public API (unchanged signatures) ─────────────────────

    /** Language-aware mood copy. Loads from JSON with English fallback. */
    fun copyFor(mode: MoodMode, language: AppLanguage): ModeCopy {
        val content = LocalizationManager.contentFor(language)
        val fromJson = content.modeCopy[moodKey(mode)]
        return fromJson ?: fallbackCopy.getValue(mode)
    }

    /** Language-aware daily prompt. */
    fun promptFor(mode: MoodMode, hasAccess: Boolean, language: AppLanguage = AppLanguage.ENGLISH): String {
        val content = LocalizationManager.contentFor(language)
        val basePrompts = content.prompts[moodKey(mode)]?.ifEmpty { fallbackPrompts.getValue(mode) } ?: fallbackPrompts.getValue(mode)
        val premiumPromptPool = content.premiumPrompts[moodKey(mode)]?.ifEmpty { fallbackPremiumPrompts.getValue(mode) } ?: fallbackPremiumPrompts.getValue(mode)
        val pool = basePrompts + if (hasAccess) premiumPromptPool else emptyList()
        val index = (LocalDate.now().toEpochDay() % pool.size).toInt()
        return pool[index]
    }

    /** Returns a single premium prompt for [mode], used as a greyed-out teaser on the Home screen. */
    fun premiumPromptPreview(mode: MoodMode, language: AppLanguage = AppLanguage.ENGLISH): String {
        val content = LocalizationManager.contentFor(language)
        val pool = content.premiumPrompts[moodKey(mode)]?.ifEmpty { fallbackPremiumPrompts.getValue(mode) } ?: fallbackPremiumPrompts.getValue(mode)
        return pool.firstOrNull() ?: ""
    }

    /** Number of premium prompts available for [mode]. */
    fun premiumPromptCount(mode: MoodMode): Int =
        fallbackPremiumPrompts.getValue(mode).size

    /**
     * Daily quote for [mode]. Premium users draw from the base pool plus the
     * exclusive premium library; free users see only the base pool.
     */
    fun quoteFor(mode: MoodMode, hasAccess: Boolean, language: AppLanguage = AppLanguage.ENGLISH): String {
        val content = LocalizationManager.contentFor(language)
        val baseQuoteObjects = content.quotes[moodKey(mode)]
        val baseQuotes = baseQuoteObjects?.map { it.text }?.ifEmpty { fallbackQuotes.getValue(mode) } ?: fallbackQuotes.getValue(mode)
        val premiumQuoteObjects = content.premiumQuotes[moodKey(mode)]
        val premiumQuotes = premiumQuoteObjects?.map { it.text }?.ifEmpty { fallbackPremiumQuotes.getValue(mode) } ?: fallbackPremiumQuotes.getValue(mode)
        val pool = baseQuotes + if (hasAccess) premiumQuotes else emptyList()
        val index = (LocalDate.now().toEpochDay() % pool.size).toInt()
        return pool[index]
    }

    /** Number of premium-exclusive quotes available for [mode]. */
    fun premiumQuoteCount(mode: MoodMode): Int =
        fallbackPremiumQuotes.getValue(mode).size

    /** Language-aware starter habits. */
    fun starterHabitsFor(language: AppLanguage): List<String> {
        val content = LocalizationManager.contentFor(language)
        return content.starterHabits.ifEmpty { fallbackStarterHabits }
    }
}
