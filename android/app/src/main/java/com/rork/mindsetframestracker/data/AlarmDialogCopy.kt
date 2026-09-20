package com.rork.mindsetframestracker.data

/**
 * The words the dialog that appears when a habit's alarm goes off says.
 *
 * ## Why the copy is data rather than string literals in the dialog
 *
 * The dialog is raised for *every* habit, and a habit is not generic: "Take a
 * vitamin" finished with a tap, "Drink water" needs a number, "Walk" wants a
 * stopwatch, "Journal" needs a sentence. A single "Time for your habit" heading
 * over a single form is what makes a tracker feel like a form rather than a
 * coach — and it is exactly the genericness the request "add personal dialogs
 * based on each habit's tools" names.
 *
 * Keeping the copy here, as a pure function of the habit, has three
 * consequences that are worth more than the tidiness:
 *
 *  * **It is testable without a device.** The project has no `androidTest`
 *    source set, so a Compose assertion is not available; a pure function of
 *    `(habit, sources)` is assertable in a plain JVM test, which is the only
 *    kind that runs in CI.
 *  * **Every mode's wording can be reviewed in one place**, instead of being
 *    scattered through a `when` inside a composable where a missing branch
 *    renders as an empty string rather than as a compile error.
 *  * **The dialog cannot invent copy.** It renders what this returns, so the
 *    heading and the input below it are always describing the same tool.
 *
 * ## Why it takes source *labels* and not `TrackerProvider`
 *
 * `TrackerProvider` lives in the `integrations` package, which already depends
 * on `data`. Taking it here would invert that layering and make this file — a
 * pure copy table — impossible to test without dragging the integration clients
 * (and their `BuildConfig` reads) into the test classpath. The caller, which
 * already holds the resolved statuses for its own rows, passes the display names
 * through. An empty list means "this habit has no fitness source", which is the
 * signal to write the honest non-tracker wording rather than promising an
 * automatic record that nothing can deliver.
 */
data class AlarmDialogCopy(
    /**
     * The dialog's heading: the habit plus what is being asked of the user.
     *
     * "Time for your walk", not "Habit reminder" — the notification and the ring
     * already announce the habit, so the dialog's job is to say what happens
     * next, in the user's own words for the thing they chose.
     */
    val title: String,
    /**
     * One line under the heading, in the second person, naming the tool this
     * habit actually has. This is the sentence that makes the dialog feel
     * written for the habit rather than generated.
     */
    val subtitle: String,
    /**
     * Heading for the connect block, shown **only** when [connectBody] is
     * non-blank. Blank means the habit has no fitness source, so the dialog
     * shows the alarm and its own tool and nothing else — the connect option
     * must not appear at all for a habit no fitness app can record.
     */
    val connectHeading: String,
    /**
     * The line under [connectHeading], naming the apps that can supply this
     * habit. Blank for a non-fitness habit — see [connectHeading].
     */
    val connectBody: String,
) {
    /**
     * True when this copy carries a connect offer.
     *
     * The single flag the dialog branches on, so "should the connect option be
     * shown?" cannot be answered one way by the heading and another by the body.
     */
    val offersConnect: Boolean get() = connectBody.isNotBlank()
}

object AlarmDialogs {

    /**
     * The copy for a whole habit. The convenience form for callers that hold one
     * (the ring host resolves the habit before it renders anything).
     */
    fun forHabit(
        habit: Habit,
        /** Display names of the providers that can supply this habit, if any. */
        sourceNames: List<String> = emptyList(),
    ): AlarmDialogCopy = forHabit(
        habitName = habit.name,
        iconId = habit.iconId,
        mode = habit.trackingModeOrDefault,
        unit = habit.trackingUnitOrDefault,
        sourceNames = sourceNames,
    )

    /**
     * The copy for a habit's *parts*.
     *
     * Exists alongside the [Habit] form for the same reason
     * [com.rork.mindsetframestracker.notifications.HabitTimerRequests.Request]
     * carries the mode and the icon rather than a whole habit: the ring path can
     * legitimately have the request but not the habit — a habit deleted between
     * the alarm being armed and it ringing — and fabricating a `Habit` just to
     * read four fields is how a false "already recorded" state gets invented.
     */
    fun forHabit(
        habitName: String,
        iconId: String?,
        mode: HabitTrackingMode,
        unit: String = "",
        /** Display names of the providers that can supply this habit, if any. */
        sourceNames: List<String> = emptyList(),
    ): AlarmDialogCopy {
        val label = habitLabelFor(iconId, habitName)
        val sources = sourceNames.filter { it.isNotBlank() }.distinct()
        // Whether an automatic record is even possible for this habit. When it
        // is not, every mode's subtitle stays about the manual tool and the
        // connect block is left blank — which is what keeps Strava off a
        // journal entry's dialog.
        val trackable = sources.isNotEmpty()
        val sourceList = joinNames(sources)

        val title = when (mode) {
            HabitTrackingMode.CHECK -> "Time for your $label"
            HabitTrackingMode.COUNT -> "Time for your $label"
            HabitTrackingMode.JOURNAL -> "Time to write"
            HabitTrackingMode.TIMER -> "Time for your $label"
            HabitTrackingMode.STOPWATCH -> "Time for your $label"
        }

        val subtitle = when (mode) {
            HabitTrackingMode.CHECK -> if (trackable) {
                "One tap is enough — or let $sourceList keep the record for you."
            } else {
                "Nothing to measure. Tap done and it's on the board."
            }

            HabitTrackingMode.COUNT -> if (unit.isNotBlank()) {
                "Log how many $unit you've done so far."
            } else {
                "Log how much you've done so far."
            }

            HabitTrackingMode.JOURNAL -> if (trackable) {
                "Get the words down while they're fresh."
            } else {
                "A few lines is plenty — no one else reads it."
            }

            HabitTrackingMode.TIMER -> "Set the length and it rings when you're done."

            HabitTrackingMode.STOPWATCH -> if (trackable) {
                "No target to set: start it when you head out, stop it when you're back."
            } else {
                "No target to set: start it and stop it whenever you're finished."
            }
        }

        return AlarmDialogCopy(
            title = title,
            subtitle = subtitle,
            connectHeading = if (trackable) CONNECT_HEADING else "",
            connectBody = if (trackable) {
                "Connect $sourceList and this $label records itself — no timer to remember."
            } else {
                ""
            },
        )
    }

    /** The section heading above the provider rows. */
    const val CONNECT_HEADING: String = "Connect a fitness app"

    /**
     * A human label for the habit in prose.
     *
     * Prefers the catalog icon's own id because that is the thing the user
     * actually picked from the grid, and reads better in a sentence than the
     * name they may have typed. Falls back to their typed name, and only then to
     * a generic word — so a custom habit is still addressed by name.
     */
    private fun habitLabelFor(iconId: String?, habitName: String): String {
        HABIT_PROSE_LABELS[iconId]?.let { return it }
        // A Strava sport id is `strava_weight_training`; "weight training" reads
        // naturally in the sentences above, where the raw id does not.
        iconId?.takeIf { it.startsWith("strava_") }
            ?.removePrefix("strava_")
            ?.replace('_', ' ')
            ?.let { return it }
        val typed = habitName.trim()
        if (typed.isNotEmpty()) return typed
        // The subject of "Time for your ___". Neutral on purpose: a blank habit
        // name is a data state, not something to guess a noun for.
        return "habit"
    }

    /**
     * Joins provider names the way a person would say them: "Strava",
     * "Strava or Polar", "Strava, Polar or Google Health Connect". No Oxford
     * comma, because these are conversational sentences and not a list in a
     * contract.
     */
    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} or ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
    }

    /**
     * The nouns these habits are spoken about with.
     *
     * Deliberately not the same as the catalog's display labels, which are
     * title-cased list entries ("Drink Water", "Gym"). In a sentence the user
     * needs the lowercase common noun — "Time for your walk", not "Time for your
     * Walking". Ids absent here fall through to the typed habit name.
     */
    private val HABIT_PROSE_LABELS: Map<String, String> = mapOf(
        // Movement
        "walking" to "walk",
        "running" to "run",
        "basketball" to "game",
        "gym" to "workout",
        "stretch" to "stretch",
        "table_tennis" to "match",
        // Commitments
        "read" to "reading",
        "tidy" to "tidy-up",
        "plan" to "planning",
        "noPhone" to "phone break",
        "meeting" to "meeting",
        // Reflection
        "journal" to "journal",
        "gratitude" to "gratitude note",
        // Quantities
        "water" to "water",
        "protein" to "protein",
        // Binary
        "medicine" to "medication",
        "biotin" to "biotin",
        "cholesterol" to "cholesterol",
        "sleep" to "bedtime",
        "todoList" to "to-do list",
        "inbox" to "inbox",
        "message" to "message",
        "compliment" to "compliment",
        "noSpend" to "no-spend day",
        "spend" to "spending log",
    )
}
