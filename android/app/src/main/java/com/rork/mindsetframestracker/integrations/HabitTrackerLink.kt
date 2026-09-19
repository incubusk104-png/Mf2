package com.rork.mindsetframestracker.integrations

import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.SPORT_ACTIVITY_ICON_IDS

/**
 * **Which tracker belongs to which habit** \u2014 the per-habit link, and the one
 * rule that stops imported activity from being filed against the wrong habit.
 *
 * ## The problem this exists to fix
 *
 * The tracker was modelled **globally**. Connectivity lived in `AppSettings`
 * (`stravaRefreshToken`, `polarAccessToken`, `healthConnectConnected`) and every
 * sync call site resolved its own habit from the icon:
 *
 * ```kotlin
 * TrackerConnections.trackableHabits(habits).forEach { habit ->
 *     if (stravaConnected) syncStravaActivities(habit.id, iconId)
 *     if (polarConnected)  syncPolarToHabit(habit.id, iconId)
 *     if (hcConnected)     syncHealthConnectToHabit(habit.id, iconId)
 * }
 * ```
 *
 * Three consequences followed from that, and all three are user-visible:
 *
 * ### 1. One account-wide report, backfilled onto every habit
 *
 * `fetchRecentActivities` asks Strava for `?per_page=10` **with no habit
 * filter** \u2014 the payload is the athlete's last ten activities, whatever they
 * were, whenever they happened. The `habitId` is whatever the *caller* passed.
 * So the moment the first habit's fetch is skipped (a blank `activityType`, an
 * expired refresh token, `PolarClient`'s unmapped-user check, `HealthConnect`
 * permissions revoked) the **next** eligible habit appends *the same*
 * activities under its own id. The ids are `strava_<activityId>`, so the rows
 * are distinct: nothing dedupes them, and the weekly rollup counts the workout
 * twice \u2014 against two different habits. A user with a Walk and a Run habit
 * therefore sees runs credited to walking and walks credited to running.
 *
 * ### 2. Unattributable provider data attributed anyway
 *
 * Polar's transaction endpoint is a **daily step roll-up** for one calendar
 * date. It is not a workout and no habit owns it, which is exactly why
 * [com.rork.mindsetframestracker.data.partitionByAttributableHabitId] has to
 * exist for the cloud path. But on the import path the same call was handed
 * every sport habit in turn, so the roll-up was written once per habit \u2014
 * inflating every activity habit's totals with the *same* daily steps, so the
 * profile filled up with records no single habit could claim.
 *
 * ### 3. A connection that claims to serve a habit it does not
 *
 * `AppSettings.stravaRefreshToken` says "this account is linked", not "this
 * habit is linked". A Strava connection made for a Run habit was advertised on
 * the Walk habit's dialog and on a checkbox habit's dialog too, because
 * [TrackerConnections.candidateSourcesFor] answers only "could this icon use
 * this provider?" \u2014 it has no idea what the user actually bound.
 *
 * ## The rule
 *
 * **A provider is bound to exactly one habit, and only a bound provider may
 * write a record.**
 *
 * - Connectivity stays where it is (one OAuth grant per *account* \u2014 the user
 *   authorises Strava once, not once per habit). What is per-habit is **which
 *   habit the data lands on**. That distinction is the whole fix: the user's
 *   complaint is about attribution and entry point, not about re-authorising
 *   three times.
 * - [habitFor] answers "which habit does this provider serve?" and returns
 *   `null` when the answer is not unambiguous. **`null` means no import** \u2014 not
 *   "pick the first sport habit", which is the behaviour that produced both
 *   defects above.
 * - [bindFor] makes binding a **transfer** when the provider is already bound
 *   elsewhere, and returns the habit that lost it, so the caller can tell the
 *   user instead of silently moving their data.
 *
 * ## Why the link lives on `Habit` and not in a side table
 *
 * Same reason `alarmMessage` does: it belongs to the habit it describes, it is
 * deleted with that habit, shared with that habit, and restored with it. A map
 * keyed by habit id would need its own lifecycle, its own sync and its own
 * orphan cleanup \u2014 and, worst of all, it could outlive the habit and re-attach
 * the link to a different habit that later reused the id.
 */

/**
 * The single-habit constraint, made checkable.
 *
 * A provider bound to two habits is not "more connectivity", it is the bug this
 * file exists to prevent: each habit would import the same account-wide report.
 * [HabitTrackerLinks.of] resolves that conflict deterministically by keeping the
 * **first** binder in habit order and dropping the rest, and reports nothing
 * silently \u2014 [HabitTrackerLinks.displaced] names what lost.
 */
object HabitTrackerLinks {

    /**
     * The provider ids a habit may carry, as stored strings.
     *
     * Stored as `name` strings rather than the enum so an unknown value written
     * by a newer build is ignored on read instead of crashing an older one \u2014 the
     * same forward-compatibility rule every other string-keyed field in this app
     * follows (`accentPack`, `trackingUnit`).
     */
    val PROVIDER_IDS: List<String> = TrackerProvider.entries.map { it.name }

    /** Parses a stored provider id, or null when it names nothing we know. */
    fun providerOf(id: String?): TrackerProvider? =
        TrackerProvider.entries.firstOrNull { it.name == id }

    /**
     * The resolved link set: provider \u2192 the habit it serves, plus the reverse.
     *
     * Built once per read rather than queried per lookup, so every decision in a
     * single sync sweep or dialog render sees the same snapshot. Recomputing per
     * lookup is how two providers in one sweep could disagree about the state of
     * the world halfway through a transfer.
     */
    data class Index(
        /** Provider \u2192 the habit that owns it. */
        val byProvider: Map<TrackerProvider, String>,
        /** Habit id \u2192 the providers it owns, in provider declaration order. */
        val byHabit: Map<String, List<TrackerProvider>>,
        /**
         * Bindings dropped because another habit already held them, as
         * (provider, habitId) \u2014 the habit that was denied.
         *
         * Kept so a caller can report a conflict rather than have the second
         * habit simply appear unbound with no explanation.
         */
        val displaced: List<Pair<TrackerProvider, String>> = emptyList(),
    ) {
        /** The habit this provider serves, or null when it serves none. */
        fun habitFor(provider: TrackerProvider): String? = byProvider[provider]

        /** The providers bound to [habitId]. Empty \u2014 not "all" \u2014 when unbound. */
        fun providersFor(habitId: String): List<TrackerProvider> =
            byHabit[habitId].orEmpty()

        /** True when [habitId] may receive records from [provider]. */
        fun allows(habitId: String, provider: TrackerProvider): Boolean =
            byProvider[provider] == habitId

        /**
         * Every provider this habit may import from, intersected with the
         * providers that can actually supply its icon.
         *
         * The intersection is what keeps a bound provider from being offered on
         * a habit whose icon it cannot serve \u2014 a Strava binding surviving an icon
         * change to "Journal" must not put a Connect Strava row on a journal
         * habit.
         */
        fun activeProvidersFor(habit: Habit): List<TrackerProvider> {
            val candidates = candidateSourcesFor(habit.iconId)
            val bound = providersFor(habit.id)
            return candidates.filter { it in bound }
        }

        /** True when this habit can import from at least one tracker. */
        fun isBound(habit: Habit): Boolean = activeProvidersFor(habit).isNotEmpty()

        /**
         * Every habit that should be swept, with the providers to sweep it from.
         *
         * The replacement for `trackableHabits`: that returned every sport habit
         * regardless of binding, which is what let one account-wide report land
         * on all of them. This returns only genuine links, so the sweep can only
         * write where the user actually pointed a tracker.
         */
        fun linkedHabits(habits: List<Habit>): List<Pair<Habit, List<TrackerProvider>>> =
            habits.mapNotNull { habit ->
                val providers = activeProvidersFor(habit)
                if (providers.isEmpty()) null else habit to providers
            }

        companion object {
            val EMPTY = Index(byProvider = emptyMap(), byHabit = emptyMap())
        }
    }

    /**
     * Resolves the link set from [habits], first binder in habit order winning.
     *
     * ## Why first-wins rather than last-wins
     *
     * "Last wins" would make the outcome depend on list order *and* on edit
     * order, so reordering habits (or a sync that reorders rows) would silently
     * move a tracker from one habit to another. First-wins is stable under any
     * reordering that preserves the relative order of the two claimants, and the
     * losing habit is reported rather than silently dropped.
     *
     * The duplicate can only exist if a writer bypassed [bindFor] \u2014 a cloud row
     * from a build with a different field order, or a hand-edited share code \u2014
     * so this is a guard against data this app does not produce, and it is
     * deliberately not destructive: nothing is rewritten, the conflict is only
     * resolved for *this* read.
     */
    fun of(habits: List<Habit>): Index {
        val byProvider = LinkedHashMap<TrackerProvider, String>()
        val byHabit = LinkedHashMap<String, MutableList<TrackerProvider>>()
        val displaced = mutableListOf<Pair<TrackerProvider, String>>()

        habits.forEach { habit ->
            habit.trackerProviderIds.forEach { raw ->
                val provider = providerOf(raw) ?: return@forEach
                // A habit asking for the same provider twice is harmless; the
                // second mention is not a second binding.
                if (byProvider[provider] == habit.id) return@forEach
                val holder = byProvider[provider]
                if (holder != null) {
                    displaced += provider to habit.id
                    return@forEach
                }
                byProvider[provider] = habit.id
                byHabit.getOrPut(habit.id) { mutableListOf() } += provider
            }
        }
        // Sorted into the canonical provider order so every render of the same
        // link set lists the providers identically \u2014 a dialog that reordered its
        // rows between recompositions is the kind of instability that reads as a
        // rendering fault.
        val ordered = byHabit.mapValues { (_, list) ->
            list.distinct().sortedBy { it.ordinal }
        }
        return Index(byProvider = byProvider, byHabit = ordered, displaced = displaced)
    }

    /**
     * The result of binding [provider] to [habitId].
     *
     * [habits] is the **complete** new habit list, with the transfer already
     * applied \u2014 callers persist this rather than splicing the field in
     * themselves, so the single-habit constraint cannot be violated by a caller
     * that only edits the habit it was asked about.
     */
    data class BindResult(
        val habits: List<Habit>,
        /** The habit that lost the provider in a transfer, if any. */
        val takenFrom: Habit? = null,
        /** False when [habitId] does not exist \u2014 nothing was changed. */
        val applied: Boolean = true,
    )

    /**
     * Binds [provider] to [habitId], taking it from whichever habit held it.
     *
     * ## Why this is a transfer and not a set-add
     *
     * Because the alternative is the defect. If the bind were purely additive,
     * two habits could name Strava and every sync would import the same
     * account-wide report twice. Removing it from the previous holder is what
     * makes "one provider, one habit" true by construction rather than by
     * convention \u2014 and the previous holder is returned so the UI can say which
     * habit gave it up, instead of the user discovering later that walk
     * statistics stopped arriving.
     *
     * Rebinding a provider to the habit that already holds it is a no-op rather
     * than an error: idempotence matters because this is reachable from a
     * reconnect flow the user may run twice.
     */
    fun bindFor(
        habits: List<Habit>,
        habitId: String,
        provider: TrackerProvider,
    ): BindResult {
        if (habits.none { it.id == habitId }) return BindResult(habits, applied = false)
        val index = of(habits)
        val holder = index.habitFor(provider)

        val updated = habits.map { habit ->
            val shouldHold = habit.id == habitId
            val holdsNow = provider.name in habit.trackerProviderIds
            when {
                // Give it up: it is being transferred away to another habit.
                !shouldHold && holdsNow && holder == habit.id ->
                    habit.copy(trackerProviderIds = habit.trackerProviderIds - provider.name)
                // Take it up, preserving the canonical order.
                shouldHold && !holdsNow ->
                    habit.copy(
                        trackerProviderIds = sortProviderIds(habit.trackerProviderIds + provider.name),
                    )
                else -> habit
            }
        }
        return BindResult(
            habits = updated,
            takenFrom = holder?.takeIf { it != habitId }?.let { id -> habits.firstOrNull { it.id == id } },
        )
    }

    /**
     * Unbinds [provider] from [habitId] only.
     *
     * Scoped to the habit on purpose, for the reason the screen-time limit bug
     * taught: "this habit no longer uses Strava" and "the account is no longer
     * linked to Strava" are different intentions, and treating the first as the
     * second deletes a working connection. Disconnecting the *account* is a
     * separate action (`AppViewModel.disconnectStrava`), and it clears the
     * tokens rather than the bindings.
     *
     * Unbinding a provider this habit does not hold changes nothing \u2014 it must
     * never touch another habit's binding.
     */
    fun unbindFor(habits: List<Habit>, habitId: String, provider: TrackerProvider): List<Habit> =
        habits.map { habit ->
            if (habit.id != habitId || provider.name !in habit.trackerProviderIds) {
                habit
            } else {
                habit.copy(trackerProviderIds = habit.trackerProviderIds - provider.name)
            }
        }

    /** Stored ids sorted into [TrackerProvider] declaration order, deduped. */
    fun sortProviderIds(ids: List<String>): List<String> = ids
        .mapNotNull { providerOf(it) }
        .distinct()
        .sortedBy { it.ordinal }
        .map { it.name }
}

/**
 * The providers left with no habit after [removedHabitId] was deleted.
 *
 * ## Why a binding cannot outlive its habit \u2014 and why this is still needed
 *
 * A binding is a field *on* the habit, so deleting the habit does delete its
 * bindings; there is no orphaned-record problem to clean up here, which is
 * precisely why the link lives on `Habit` rather than in a side table.
 *
 * What remains is a **reporting** concern. The account-level connection
 * (`stravaRefreshToken` etc.) is still live, so the provider is connected but
 * now serves nothing \u2014 no habit will ever be swept from it again. That is
 * invisible unless someone says it: the user's Strava is still "Connected" in
 * Settings, and their runs quietly stop arriving. Returning the name lets the
 * caller say which provider was left idle, so the state is explainable instead
 * of merely true.
 */
fun providersLeftUnboundAfter(habits: List<Habit>, removedHabitId: String): List<TrackerProvider> {
    val removed = habits.firstOrNull { it.id == removedHabitId } ?: return emptyList()
    val remaining = HabitTrackerLinks.of(habits.filterNot { it.id == removedHabitId })
    return removed.trackerProviderIds
        .mapNotNull { HabitTrackerLinks.providerOf(it) }
        .filter { remaining.habitFor(it) == null }
}

/**
 * Providers that *could* serve [iconId] \u2014 the candidate set, not the binding.
 *
 * Renamed from `sourcesFor` deliberately: the old name read as "the sources this
 * habit uses", and every call site treated it that way. It only ever answered
 * "what could this icon use", so the dialog advertised three providers for a
 * habit the user had never connected anything to, and `runAutoSync` swept every
 * sport habit whether or not a tracker was pointed at it. The binding lives in
 * [HabitTrackerLinks]; this answers the narrower question and its name now says
 * so.
 *
 * Strava last on purpose: it is the only tier-gated one, so the two free options
 * appear first and a user on the free tier never has to read past a lock to find
 * something they can use.
 */
fun candidateSourcesFor(iconId: String?): List<TrackerProvider> {
    if (iconId.isNullOrBlank()) return emptyList()
    return buildList {
        if (MindsetHealthConnectClient.isActivitySupported(iconId)) {
            add(TrackerProvider.HEALTH_CONNECT)
        }
        if (PolarClient.isActivitySupported(iconId)) {
            add(TrackerProvider.POLAR)
        }
        // Strava records every sport, so it is offered for any sport icon rather
        // than only the ids in one hand-maintained list.
        if (iconId in SPORT_ACTIVITY_ICON_IDS) add(TrackerProvider.STRAVA)
    }
}

/**
 * The **app's** vocabulary for an imported activity, from the provider's own.
 *
 * ## Why the record cannot keep the provider's word
 *
 * `activityType` used to be the habit's `iconId` \u2014 the caller passed it in, and
 * it was already an app-catalog id. Once the type genuinely describes the
 * *activity* rather than the habit it was filed under, the value arriving is the
 * provider's own token: `stravaActivityTypeFor` returns Strava's spelling
 * (`Walk`, `Ride`, `NordicSki`, `VirtualRide`), and `?per_page=10` returns a
 * different sport from the one the habit is for. Storing that verbatim breaks
 * every reader that speaks the catalog's language: the activity sheet renders
 * "Ride" where the rest of the app says cycling, and anything matching on a
 * catalog id stops matching at all.
 *
 * Canonicalizing **at the record builder** rather than at each reader is the
 * point: a reader that has to know both vocabularies is a reader that will
 * eventually be written by someone who only knows one.
 *
 * Falls back to the habit's own icon when the token is unrecognised \u2014 the habit
 * is at least a real catalog id, which is strictly more useful to a reader than
 * a provider token it cannot place. **The returned value is therefore always
 * either an id the catalog carries or `activity`; never a raw provider token,
 * and never blank.**
 */
fun canonicalActivityType(providerType: String, habitIconId: String?): String {
    val token = providerType.trim()
    if (token.isEmpty()) return habitIconId?.takeIf { it.isNotBlank() } ?: UNNAMED_ACTIVITY_FALLBACK

    STRAVA_TOKEN_TO_CANONICAL[token]?.let { return it }

    // Already canonical: a catalog id ("walking", "strava_swim") or one of the
    // non-sport types Health Connect reports ("sleep", "activity"). Passing it
    // through unchanged is what keeps a re-import of an old record stable.
    if (token in SPORT_ACTIVITY_ICON_IDS || token in NON_SPORT_ACTIVITY_TYPES) return token

    // A sport the catalog names `strava_<snake>` but which has no explicit alias
    // above \u2014 the catalog lists ~150 of them, and hand-listing every Strava
    // token would drift the moment Strava adds a sport.
    // Accepted only when the catalog actually carries it. Returning the candidate
    // unconditionally stored a type no reader can resolve ("Kayaking" ->
    // `strava_kayaking`), which reintroduced the unplaceable-type failure this
    // function exists to prevent. It also made the function non-idempotent, so an
    // unrelated re-save of an older record rewrote its type.
    val prefixed = "strava_${token.toSnakeCase()}"
    if (prefixed in SPORT_ACTIVITY_ICON_IDS) return prefixed

    return habitIconId?.takeIf { it.isNotBlank() } ?: UNNAMED_ACTIVITY_FALLBACK
}

/** `NordicSki` \u2192 `nordic_ski`, `EBikeRide` \u2192 `e_bike_ride`. */
private fun String.toSnakeCase(): String = buildString {
    this@toSnakeCase.forEachIndexed { i, c ->
        when {
            c.isUpperCase() && i > 0 -> {
                append('_')
                append(c.lowercaseChar())
            }
            else -> append(c.lowercaseChar())
        }
    }
}

/** Used when neither the token nor the habit can name the activity. */
private const val UNNAMED_ACTIVITY_FALLBACK = "activity"

/**
 * Types that are legitimately not a catalog sport id, and so are passed through
 * rather than being forced into the `strava_` namespace.
 */
private val NON_SPORT_ACTIVITY_TYPES: Set<String> = setOf(
    "sleep",
    "activity",
    "steps",
)

/**
 * Strava's `type` vocabulary mapped onto the app's catalog ids.
 *
 * Only the tokens whose canonical id is **not** simply `strava_` + snake case are
 * listed: `Walk` is the catalog's `walking` (not `strava_walk`), `Ride` is the
 * catalog's `strava_ride` (there is no bare `cycling` id), and `Workout` is
 * `gym`. Everything else resolves through the `strava_<snake>` fallback in
 * [canonicalActivityType], so this table stays a list of genuine exceptions
 * instead of a second copy of the catalog.
 */
private val STRAVA_TOKEN_TO_CANONICAL: Map<String, String> = mapOf(
    "Walk" to "walking",
    "Run" to "running",
    "Basketball" to "basketball",
    "Workout" to "gym",
    "WeightTraining" to "strava_weight_training",
    // The cycling family. The catalog names every variant `strava_*_ride` and has
    // no bare `cycling` id at all -- an earlier draft mapped the whole family to
    // `cycling`, which the catalog does not carry, and its own idempotence test
    // caught it: re-canonicalising "cycling" fell through to the habit icon, so a
    // plain re-save would have rewritten the type of every ride.
    "Ride" to "strava_ride",
    "VirtualRide" to "strava_virtual_ride",
    "EBikeRide" to "strava_ebike_ride",
    "EMountainBikeRide" to "strava_emtb_ride",
    "MountainBikeRide" to "strava_mountain_bike_ride",
    "GravelRide" to "strava_gravel_ride",
    // Strava's token and the catalog's id disagree on the last word.
    "RockClimbing" to "strava_rock_climb",
    // The catalog carries this one UNPREFIXED, so `strava_table_tennis` is not a
    // real id and the snake-case fallback would miss it — the alias is the only
    // thing that resolves this token. Caught by the alias-table test, which
    // asserts every mapped value is an id the catalog actually carries.
    "TableTennis" to "table_tennis",
    "Yoga" to "strava_yoga",
    "PhysicalTherapy" to "stretch",
    "Soccer" to "strava_football",
    // `snake()` would give `strava_high_intensity_interval_training`, which the
    // catalog does not carry, so this alias is load-bearing rather than
    // cosmetic. Both spellings are listed because Strava has used both.
    "HighIntensityIntervalTraining" to "strava_hiit",
    "HIIT" to "strava_hiit",
)

/**
 * [ActivityRecord] with `activityType` replaced by its canonical form.
 *
 * Applied at the record builders in the three provider clients, so no reader
 * ever sees a provider token. A record that must be re-canonicalized (an older
 * one restored from the cloud, written before this existed) is still readable,
 * which is why the canonicalizer is a pure function of the token rather than a
 * write-time-only transform.
 */
fun ActivityRecord.withCanonicalActivityType(habitIconId: String?): ActivityRecord {
    val canonical = canonicalActivityType(activityType, habitIconId)
    return if (canonical == activityType) this else copy(activityType = canonical)
}
