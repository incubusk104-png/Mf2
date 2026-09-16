package com.rork.mindsetframestracker.data

/**
 * The motivational copy that turns a habit reminder from a plain alert into an
 * encouragement.
 *
 * ## Why a reminder needs this at all
 *
 * A reminder that only says the habit's name ("Drink Water") tells the user
 * *what* is due and nothing else — and the moment the phone buzzes is exactly
 * the moment the user is least willing to spend attention on it. Habit apps
 * lose people at that specific instant, not at the planning stage. A single
 * warm, specific line at the ring ("It's time to water up! 💧 Stay hydrated,
 * you've got this!") is the whole difference between an alarm that gets
 * dismissed from muscle memory and one that gets acted on.
 *
 * ## What this object owns
 *
 *  - [MessagePack]s of curated, habit-specific lines — the water pack is the
 *    example the product brief leads with.
 *  - The **preset picker** ([itemsFor] / [presetFor]) that the alarm editor
 *    shows, so the user can pick a line without typing one.
 *  - [lineFor] — pure rotation over [packFor], used when a habit has no custom
 *    message of its own.
 *  - [sanitize] — the single place a user-supplied message is trimmed and
 *    bounded before it is stored or put on a Notification. See its own doc for
 *    why that is a safety requirement, not a nicety.
 *
 * ## Why rotation is a pure function of a stable key
 *
 * [lineFor] derives its index from `(habitId, dayKey)` rather than from a
 * counter or from `Random`. Two consequences, both deliberate:
 *
 *  - **The same habit shows the same line all day.** A habit that rings at
 *    07:00, 12:00 and 18:00 would otherwise show three unrelated messages and
 *    read as three different apps.
 *  - **A re-armed or re-delivered alarm is stable.** Nothing here writes state,
 *    so it is safe to call from a BroadcastReceiver at ring time *and* from the
 *    alarm editor's preview, and both will agree.
 *
 * It also means the only cross-run monotonic value is the day itself, so a user
 * gets a genuinely different line tomorrow without anything being persisted.
 */
object MotivationalMessages {

    /**
     * A named set of lines for one kind of habit.
     *
     * [id] is persisted nowhere — it is only an in-memory handle for the picker,
     * so a pack can be renamed or its lines reordered without any migration.
     */
    data class MessagePack(
        val id: String,
        val label: String,
        val emoji: String,
        val lines: List<String>,
    )

    /** Gmail's/LinkedIn's practical ceiling for a preview line; keeps the shade tidy. */
    const val MAX_MESSAGE_LENGTH = 120

    // ── Water — the brief's own example ──────────────────────────────────────
    /**
     * The habits the brief names first, and the one whose copy is quoted
     * verbatim in the product request, so it is written out in full rather than
     * left to the generic defaults.
     */
    private val WATER = MessagePack(
        id = "water",
        label = "Drink water",
        emoji = "💧",
        lines = listOf(
            "It's time to water up! 💧 Stay hydrated, you've got this!",
            "Water break! 💧 Your future self says thanks.",
            "One glass now, one better hour later. 💧",
            "Hydration o'clock! 💧 Small sip, big difference.",
            "Refill and reset. 💧 You're doing great.",
            "Your body runs on water, not willpower. 💧 Drink up!",
            "Cheers to you! 💧 One glass closer to feeling sharp.",
            "Thirsty brain? 💧 Water first, everything else after.",
        ),
    )

    private val MEDICINE = MessagePack(
        id = "medicine",
        label = "Take medicine / vitamins",
        emoji = "💊",
        lines = listOf(
            "Time for your vitamins! 💊 Future you is grateful.",
            "Small pill, big care. 💊 Take it now while you remember.",
            "Health first! 💊 One quick swallow and you're set.",
            "Don't skip it — your body keeps score. 💊 Take your dose.",
            "Two seconds now, better weeks later. 💊 Grab your vitamins.",
        ),
    )

    private val MOVEMENT = MessagePack(
        id = "movement",
        label = "Move your body",
        emoji = "🏃",
        lines = listOf(
            "Time to move! 🏃 Ten minutes counts. Start there.",
            "Your body is ready — your excuse isn't invited. 🏃",
            "Movement is the mood-lifter. 🏃 Go get some.",
            "Lace up, show up. 🏃 That's the whole plan.",
            "A short walk beats a perfect workout you skipped. 🏃",
            "You never regret the movement you did. 🏃 Let's go!",
            "Get the blood flowing! 🏃 Momentum starts with one step.",
        ),
    )

    private val STRETCH = MessagePack(
        id = "stretch",
        label = "Stretch / mobility",
        emoji = "🧘",
        lines = listOf(
            "Unclench, unfold, breathe. 🧘 Time to stretch.",
            "Your shoulders have been up by your ears all day. 🧘 Let them down.",
            "Sixty seconds of stretching is a real win. 🧘 Do it now.",
            "Loose body, clearer head. 🧘 Time for your stretch.",
            "Move slowly on purpose. 🧘 Your body will thank you.",
        ),
    )

    private val SLEEP = MessagePack(
        id = "sleep",
        label = "Wind down / sleep",
        emoji = "🌙",
        lines = listOf(
            "Time to wind down. 🌙 Rest is how you win tomorrow.",
            "Screens off, lights low. 🌙 Your best work needs sleep.",
            "You've done enough today. 🌙 Let yourself rest.",
            "Sleep is training, not laziness. 🌙 Go get some.",
            "Close the day gently. 🌙 Tomorrow-you is counting on tonight-you.",
        ),
    )

    private val MIND = MessagePack(
        id = "mind",
        label = "Journal / reflect",
        emoji = "📝",
        lines = listOf(
            "Two minutes, one honest line. 📝 That's all this needs.",
            "Write it down and let it go. 📝 Time to journal.",
            "Your thoughts deserve a page. 📝 Start anywhere.",
            "No perfect entry exists — just write. 📝 You're here, that's the win.",
            "Empty the loop in your head. 📝 One sentence is enough.",
        ),
    )

    private val GRATITUDE = MessagePack(
        id = "gratitude",
        label = "Gratitude",
        emoji = "🙏",
        lines = listOf(
            "Name one good thing. 🙏 Watch it change your day.",
            "Gratitude is a muscle. 🙏 Time to flex it.",
            "What went right today? 🙏 Take ten seconds to notice.",
            "One small thank-you, out loud or on paper. 🙏 Go.",
            "Good things are already here. 🙏 Let yourself see them.",
        ),
    )

    private val READING = MessagePack(
        id = "reading",
        label = "Read",
        emoji = "📚",
        lines = listOf(
            "A page is a win. 📚 Pick up the book.",
            "Ten pages beat zero pages. 📚 Go.",
            "Feed your head. 📚 Reading time!",
            "The book has been waiting patiently. 📚 Reward it.",
            "Trade scroll for page. 📚 Your focus will thank you.",
        ),
    )

    private val FOCUS = MessagePack(
        id = "focus",
        label = "Deep work / no phone",
        emoji = "🎯",
        lines = listOf(
            "One task, full attention. 🎯 You've got this.",
            "Phone down, world out. 🎯 Focus time.",
            "Protect the next hour — it belongs to you. 🎯",
            "Deep work starts with a single decision. 🎯 Make it now.",
            "Distraction is a choice you get to decline. 🎯 Go focus.",
        ),
    )

    private val ROUTINE = MessagePack(
        id = "routine",
        label = "Tidy / organise",
        emoji = "🧹",
        lines = listOf(
            "Five minutes of tidying resets everything. 🧹 Go.",
            "Clear space, clear head. 🧹 Start with one surface.",
            "Small tidy, big relief. 🧹 You know the spot.",
            "Future-you is begging you to do the dishes. 🧹",
            "One thing back in its place. 🧹 That's the whole ask.",
        ),
    )

    private val MONEY = MessagePack(
        id = "money",
        label = "Budget / money check",
        emoji = "💰",
        lines = listOf(
            "Two minutes with your numbers today saves a week of worry. 💰",
            "Money loves attention. 💰 Give it a look.",
            "Log it now, forget it later. 💰 Future-you approves.",
            "Small check-ins, big peace of mind. 💰 Go.",
            "Know your numbers, own your choices. 💰 Quick look now.",
        ),
    )

    private val SOCIAL = MessagePack(
        id = "social",
        label = "Reach out to someone",
        emoji = "💬",
        lines = listOf(
            "Send the message. 💬 They'll be glad you did.",
            "One kind word, right now. 💬 That's the habit.",
            "Connection is a practice. 💬 Reach out today.",
            "You've been meaning to text them. 💬 Do it now.",
            "Someone out there would love to hear from you. 💬 Go.",
        ),
    )

    /** Generic fallback, used for any habit with no more specific pack. */
    val GENERAL = MessagePack(
        id = "general",
        label = "Encouragement",
        emoji = "✨",
        lines = listOf(
            "You've got this. ✨ One small step, right now.",
            "Showing up is the whole trick. ✨ Let's go.",
            "Progress, not perfection. ✨ Take the next step.",
            "You started this for a reason. ✨ Remember why.",
            "Tiny action now, big compound later. ✨ Go.",
            "Future-you is cheering for present-you. ✨",
            "Momentum is built, not found. ✨ Start here.",
            "You don't need motivation — you need one move. ✨",
        ),
    )

    /** Every pack, in the order the picker shows them. */
    private val ALL_PACKS: List<MessagePack> = listOf(
        WATER, MEDICINE, MOVEMENT, STRETCH, SLEEP, MIND, GRATITUDE,
        READING, FOCUS, ROUTINE, MONEY, SOCIAL, GENERAL,
    )

    /**
     * The packs offered for a habit, given its catalog icon id.
     *
     * Returns the habit's own pack **first**, so the most relevant lines are
     * what the user sees without scrolling, with [GENERAL] always last as the
     * always-applicable option. Deliberately never returns an empty list: a
     * picker with nothing in it is a dead end, and every habit can at least take
     * an encouraging line.
     */
    fun itemsFor(iconId: String?): List<MessagePack> {
        val primary = packFor(iconId)
        return (listOf(primary) + ALL_PACKS.filterNot { it.id == primary.id || it.id == GENERAL.id } + GENERAL)
            .distinctBy { it.id }
    }

    /** Just the first suggestion for an icon — used to seed a new habit. */
    fun presetFor(iconId: String?): String = packFor(iconId).lines.first()

    /**
     * The pack that best fits a habit, chosen from its catalog icon id.
     *
     * Matching is on the icon id because that is the only classification the app
     * already carries for every habit, including ones created before this
     * feature existed (see `Habit.trackingModeOrDefault` for the same
     * reasoning). Anything unrecognised — including a habit the user typed in
     * themselves, which gets the `todoList` icon — falls through to [GENERAL].
     */
    fun packFor(iconId: String?): MessagePack {
        // Normalised once so every branch below can be a plain comparison and no
        // branch has to reason about nullability.
        val id = iconId.orEmpty()
        return when {
            id == "water" -> WATER
            id in MEDICINE_ICONS -> MEDICINE
            // Every Strava sport icon (`strava_yoga`, `strava_hike`, …) is a
            // movement habit, so they are matched by prefix rather than listed.
            id.startsWith("strava_") -> MOVEMENT
            id in MOVEMENT_ICONS -> MOVEMENT
            id in STRETCH_ICONS -> STRETCH
            id == "sleep" -> SLEEP
            id == "journal" -> MIND
            id in GRATITUDE_ICONS -> GRATITUDE
            id == "read" -> READING
            id in FOCUS_ICONS -> FOCUS
            id == "tidy" -> ROUTINE
            id in MONEY_ICONS -> MONEY
            id in SOCIAL_ICONS -> SOCIAL
            else -> GENERAL
        }
    }

    private val MEDICINE_ICONS = setOf("medicine", "biotin", "protein", "cholesterol")
    private val MOVEMENT_ICONS = setOf("walking", "running", "gym", "basketball", "dance", "table_tennis")
    private val STRETCH_ICONS = setOf("stretch", "yoga", "pilates")
    private val GRATITUDE_ICONS = setOf("gratitude", "compliment")
    private val FOCUS_ICONS = setOf("noPhone", "screenTime", "plan", "inbox")
    private val MONEY_ICONS = setOf("spend", "noSpend")
    private val SOCIAL_ICONS = setOf("message", "meeting")

    /**
     * The line to show for a habit, honouring the user's own words when they
     * wrote some.
     *
     * [customMessage] wins outright — the user overrode the defaults, and
     * rotating their line away from them would be the app arguing with the
     * person using it. It is sanitized here rather than at the call site so that
     * **every** producer of the message text (the notification, the ringing
     * screen, the editor's preview) sees the same, safe value.
     */
    fun lineFor(
        habitId: String,
        iconId: String?,
        dayKey: String,
        customMessage: String? = null,
        alarmMinutes: Int? = null,
    ): String {
        val custom = sanitize(customMessage)
        if (custom.isNotEmpty()) return custom

        val lines = packFor(iconId).lines
        if (lines.isEmpty()) return GENERAL.lines.first()

        // Stable per (habit, day, alarm time): a habit that rings three times a
        // day still tells one consistent story, while its three alarms each get
        // a different line so the day doesn't read as a single repeated nag.
        val seed = habitId.hashCode().toLong() * 31L + dayKey.hashCode().toLong() * 17L +
            (alarmMinutes?.toLong() ?: 0L)
        val index = ((seed % lines.size) + lines.size) % lines.size
        return lines[index.toInt()]
    }

    /**
     * The line to show for a habit, reading the habit's own stored message.
     *
     * The convenience form for callers that already hold a [Habit]; the
     * id/icon form above exists for the pure-JVM callers (tests, previews) that
     * should not have to construct one.
     */
    fun lineFor(habit: Habit, dayKey: String, alarmMinutes: Int? = null): String =
        lineFor(
            habitId = habit.id,
            iconId = habit.iconId,
            dayKey = dayKey,
            customMessage = habit.alarmMessage,
            alarmMinutes = alarmMinutes,
        )

    /**
     * Trims and bounds a user-supplied message, returning `""` for anything that
     * should be treated as "not set".
     *
     * This is a **safety boundary**, not formatting. The result is placed into a
     * Notification by a BroadcastReceiver that runs with no Activity and no user
     * present (see `HabitCheckInNotifier`), so it must be:
     *
     *  - **single-line** — a newline smuggled into a notification title renders
     *    as a broken, half-height row and is a known way to make a notification
     *    look like it came from another app;
     *  - **length-bounded** — an unbounded string is posted on every ring, and a
     *    pasted essay would push the habit name and every action button off the
     *    notification entirely;
     *  - **non-blank after trimming** — otherwise " " would count as a custom
     *    message and silently suppress the curated pack.
     *
     * Control characters are stripped for the same reason as the newline: they
     * are invisible in the editor and only surface in the shade.
     */
    fun sanitize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val flattened = raw
            .filter { ch -> !ch.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
        return flattened.take(MAX_MESSAGE_LENGTH)
    }
}
