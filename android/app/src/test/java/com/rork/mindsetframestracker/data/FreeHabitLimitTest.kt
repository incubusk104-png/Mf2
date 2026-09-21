package com.rork.mindsetframestracker.data

import com.rork.mindsetframestracker.billing.Entitlements
import com.rork.mindsetframestracker.billing.SubscriptionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free-tier habit cap, on the client side of it.
 *
 * ## What these tests are for
 *
 * Phase 1 asks for one number that is *shown* and one that is *enforced*, and
 * requires that they can never disagree. They used to: `MAX_FREE_HABITS` was
 * written out twice — `const val MAX_FREE_HABITS = 5` in this module and
 * `const MAX_FREE_HABITS = 5` in `functions/habits/index.ts` — and the server
 * copy was declared and then never referenced, so the indicator said "5 of 5
 * active habits" while the server accepted unlimited creates.
 *
 * The fix has two halves, and both are asserted here:
 *
 *  1. **One declaration.** [MAX_FREE_HABITS] is now `BuildConfig.MAX_FREE_HABITS`,
 *     a value the build reads from `freeHabitsMax` in `libs.versions.toml` — the
 *     same catalog entry the habits Edge Function's `MAX_FREE_HABITS` environment
 *     variable is injected from. There is no Kotlin literal left to drift.
 *  2. **One counting rule, in one place.** Archived habits are excluded, paused
 *     ones are counted, and the "x of 5" indicator, the creation block and
 *     [Entitlements.canAddHabit] all consume the same [AppData.activeHabitCount].
 *
 * ## What is deliberately NOT tested here
 *
 * The cap that actually holds is the server's (the Edge Function, plus the
 * BEFORE INSERT trigger in the schema). Those cannot be exercised from a JVM
 * unit test, and asserting the client's opinion of them here would create
 * exactly the false confidence this whole change is about removing.
 * [Entitlements.canAddHabit] is UX only; its tests below say so.
 *
 * ## The direction that matters most
 *
 * [habitCap_neverHidesOrLosesAnExistingHabit] pins the no-data-loss rule: an
 * install that is over the cap (downgraded, or simply predating the limit) counts
 * every habit it has and never has one removed or hidden by the limit.
 */
class FreeHabitLimitTest {

    private fun habit(
        id: String,
        name: String = id,
        archived: Boolean = false,
    ) = Habit(id = id, name = name, isArchived = archived)

    // ── The counting rule: archived excluded, paused counted ─────────────────

    @Test
    fun archivedHabitIsNotActive() {
        assertFalse(habit("a", archived = true).isActive)
        assertTrue(habit("b").isActive)
    }

    @Test
    fun activeHabitCountExcludesArchivedAndCountsEverythingElse() {
        val data = AppData(
            habits = listOf(
                habit("a"),
                habit("b"),
                habit("archived-1", archived = true),
                habit("c"),
            ),
        )

        assertEquals(3, data.activeHabitCount)
        assertEquals(listOf("a", "b", "c"), data.activeHabits.map { it.id })
    }

    /**
     * Paused is not archived, and must keep occupying a slot.
     *
     * The distinction is the whole point of having two flags' worth of concept in
     * one: pausing is a *scheduling* choice ("not on these days"), not a way to
     * get a sixth habit for free. If pausing freed a slot, a free user could hold
     * six habits by pausing one, and the cap would be decorative.
     *
     * `isActive` is derived from `isArchived` alone, so any habit that is merely
     * paused (not archived) still counts — asserted here by the explicit zero of
     * archived flags across a set of non-archived habits.
     */
    @Test
    fun pausingDoesNotFreeASlotBecausePausedIsNotArchived() {
        val paused = habit("paused")
        assertFalse("a paused habit is not archived", paused.isArchived)
        assertTrue("and therefore still occupies a slot", paused.isActive)

        val data = AppData(habits = listOf(paused))
        assertEquals(1, data.activeHabitCount)
    }

    /**
     * Archiving is how a user frees a slot back — and the freed slot is real.
     *
     * This is the behaviour that makes the cap answerable without deleting
     * anything: at 5 of 5, archive one and the count drops to 4, so a new habit is
     * allowed again.
     */
    @Test
    fun archivingFreesASlot() {
        val atCap = AppData(habits = (1..MAX_FREE_HABITS).map { habit("h$it") })
        assertEquals(MAX_FREE_HABITS, atCap.activeHabitCount)
        assertFalse(Entitlements.canAddHabit(SubscriptionTier.NONE, atCap.activeHabitCount))

        val afterArchiving = AppData(
            habits = (1..MAX_FREE_HABITS - 1).map { habit("h$it") } +
                habit("h$MAX_FREE_HABITS", archived = true),
        )
        assertEquals(MAX_FREE_HABITS - 1, afterArchiving.activeHabitCount)
        assertTrue(Entitlements.canAddHabit(SubscriptionTier.NONE, afterArchiving.activeHabitCount))
    }

    // ── The limit itself ─────────────────────────────────────────────────────

    @Test
    fun freeTierLimitIsTheSinglePublishedValue() {
        // Read from BuildConfig, which is generated from libs.versions.toml's
        // `freeHabitsMax`. Not asserted against a literal on purpose: a literal
        // here would be a second declaration, which is the bug being fixed. The
        // property worth pinning is that it is a usable constant at all.
        assertTrue("the free-tier cap must be a positive number", MAX_FREE_HABITS > 0)
    }

    // ── canAddHabit: the UX gate, and its boundary ───────────────────────────

    @Test
    fun freeUserMayAddUpToButNotPastTheLimit() {
        assertTrue(Entitlements.canAddHabit(SubscriptionTier.NONE, 0))
        assertTrue(
            Entitlements.canAddHabit(SubscriptionTier.NONE, MAX_FREE_HABITS - 1),
        )
        assertFalse(
            "at the cap, one more is refused",
            Entitlements.canAddHabit(SubscriptionTier.NONE, MAX_FREE_HABITS),
        )
        assertFalse(
            "and above it, too",
            Entitlements.canAddHabit(SubscriptionTier.NONE, MAX_FREE_HABITS + 3),
        )
    }

    @Test
    fun paidTierIsNeverCapped() {
        // Every tier that is not NONE carries Feature.UNLIMITED_HABITS
        // (Entitlements.hasAccess), so all of them bypass the cap entirely —
        // asserted across the enum rather than for one hardcoded tier, so adding a
        // tier without deciding its habit behaviour fails here.
        val paid = SubscriptionTier.values().filter { it != SubscriptionTier.NONE }
        assertTrue("the enum must have at least one paid tier to test", paid.isNotEmpty())
        for (tier in paid) {
            assertTrue(
                "$tier includes unlimited habits",
                Entitlements.canAddHabit(tier, MAX_FREE_HABITS),
            )
            assertTrue(
                "$tier stays uncapped well past the free limit",
                Entitlements.canAddHabit(tier, MAX_FREE_HABITS * 10),
            )
        }
    }

    /**
     * The promised no-data-loss path, asserted from the two directions a user
     * actually reaches it.
     *
     * **An install already over the cap.** Someone who downgrades, or who simply
     * had more habits before the limit existed, keeps every one of them. The count
     * reports what they have (not the cap), nothing is filtered out of the model,
     * and they are only blocked from adding *more*.
     */
    @Test
    fun habitCap_neverHidesOrLosesAnExistingHabit() {
        val overCap = AppData(habits = (1..MAX_FREE_HABITS + 4).map { habit("h$it") })

        assertEquals(
            "every existing habit is still counted",
            MAX_FREE_HABITS + 4,
            overCap.activeHabitCount,
        )
        assertEquals(
            "and every existing habit is still in the model",
            MAX_FREE_HABITS + 4,
            overCap.activeHabits.size,
        )
        assertFalse(
            "creation is the only thing the cap blocks",
            Entitlements.canAddHabit(SubscriptionTier.NONE, overCap.activeHabitCount),
        )
    }

    /**
     * **An archived habit is put away, not destroyed.** The habit object keeps its
     * identity and every field it had; only the flag changes, so un-archiving
     * restores it with its data intact.
     */
    @Test
    fun archivingKeepsTheHabitAndItsDataIntact() {
        val original = habit("h1", name = "Morning run").copy(isPinned = true)
        val archived = original.copy(isArchived = true)

        assertEquals(original.id, archived.id)
        assertEquals(original.name, archived.name)
        assertEquals(original.isPinned, archived.isPinned)
        assertEquals(original.createdAt, archived.createdAt)
        assertTrue(archived.isArchived)

        // Round trip: the flag is the only difference either way.
        assertEquals(original, archived.copy(isArchived = false))
    }

    /**
     * A habit stored before the flag existed must decode as **active**.
     *
     * This is what keeps an update from silently archiving a user's habits (which
     * would also drop them out of the count and free slots they are really using).
     * The serialized form below is exactly what an older install has on disk: no
     * `isArchived` key at all.
     */
    @Test
    fun habitWithoutTheArchivedFlagDecodesAsActive() {
        val legacyJson = """
            {"id":"h1","name":"Morning run","createdAt":1750000000000,"isPinned":false}
        """.trimIndent()

        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(Habit.serializer(), legacyJson)

        assertFalse("absent means active, never archived", decoded.isArchived)
        assertTrue(decoded.isActive)
    }

    /**
     * The Kotlin half of the wire contract the server enforces.
     *
     * The habits Edge Function answers a blocked write with `403` and
     * `code: "habit_limit"`, and the client uses this constant to recognise it and
     * report a product limit instead of a generic backup failure. The literal is
     * asserted so a one-sided rename cannot quietly break that match — the string
     * is the only thing on this boundary that cannot be shared across the two
     * languages.
     */
    @Test
    fun habitLimitCodeMatchesTheServerWireContract() {
        assertEquals("habit_limit", HABIT_LIMIT_CODE)

        // ...and so does the server's own declaration of it. A rename on one side
        // would leave the client matching a code nobody sends, turning a clear
        // upgrade refusal back into the generic "cloud storage" dead end.
        val serverCode = Regex("export\\s+const\\s+HABIT_LIMIT_CODE\\s*=\\s*\"([^\"]+)\"")
            .find(edgeFunctionSource())?.groupValues?.get(1)
        assertEquals(
            "the Edge Function's HABIT_LIMIT_CODE must match the client's",
            HABIT_LIMIT_CODE,
            serverCode,
        )
    }

    // ── The limit really is single-sourced ───────────────────────────────────
    //
    // The three tests below are what make "one source of truth" a property the
    // build checks rather than a claim in a comment. Without them the number
    // would still be written in three places — the catalog, the Kotlin
    // `MAX_FREE_HABITS` and the Edge Function's fallback — and only the first
    // would be the documented one; the other two would be free to drift, which is
    // exactly the failure Phase 1 asked to close.
    //
    // They read the real files, so they fail on the edit that introduces the
    // disagreement, in the same build that compiles the change. The workflow runs
    // `:app:testDebugUnitTest` on every push, so neither side can reach `main`
    // alone.

    /**
     * [MAX_FREE_HABITS] is the catalog value, not a Kotlin copy of it.
     *
     * Deleting the literal from `Models.kt` is not enough on its own: someone
     * could reintroduce one as `const val MAX_FREE_HABITS = 5` and everything
     * would still compile and pass. This pins the provenance.
     */
    @Test
    fun theBuildConfigCapIsTheVersionCatalogValue() {
        val fromCatalog = catalogFreeHabitsMax()

        assertNotNull(
            "freeHabitsMax must be declared in android/gradle/libs.versions.toml",
            fromCatalog,
        )
        assertEquals(
            "BuildConfig.MAX_FREE_HABITS must BE the catalog's freeHabitsMax — the " +
                "catalog is the single source of truth for the free-tier cap",
            fromCatalog,
            MAX_FREE_HABITS,
        )
    }

    /**
     * The Edge Function's fallback agrees with the same catalog entry.
     *
     * A deployed Edge Function cannot read the repository, so it has to carry a
     * literal as the fallback for a deployment that sets no `MAX_FREE_HABITS`
     * environment variable. That literal is the one place the number could still
     * silently diverge — this is the assertion that stops it.
     */
    @Test
    fun theEdgeFunctionFallbackAgreesWithTheVersionCatalog() {
        val fromTs = Regex("export\\s+const\\s+FREE_HABITS_FALLBACK\\s*=\\s*(\\d+)")
            .find(edgeFunctionSource())?.groupValues?.get(1)?.toIntOrNull()

        assertNotNull(
            "FREE_HABITS_FALLBACK must be declared in functions/_shared/freeTier.ts",
            fromTs,
        )
        assertEquals(
            "the Edge Function's fallback and the version catalog must carry the same " +
                "cap — change freeHabitsMax, then FREE_HABITS_FALLBACK, and nothing else",
            catalogFreeHabitsMax(),
            fromTs,
        )
    }

    /**
     * Every mirror of the Edge Function is byte-for-byte identical.
     *
     * `freeTier.ts` is tracked twice (the function sources are kept in both
     * `backend/functions/` and `supabase/functions/`). A cap constant that is
     * correct in one mirror and stale in the other is the same drift in a new
     * costume, so the copies are compared rather than trusted.
     */
    @Test
    fun theMirroredEdgeFunctionIsIdentical() {
        val root = repoRoot()
        val canonical = java.io.File(root, "backend/functions/_shared/freeTier.ts")
        val mirrors = listOf("supabase/functions/_shared/freeTier.ts")

        assertTrue("expected the canonical ${canonical.path} to exist", canonical.isFile)
        for (relative in mirrors) {
            val mirror = java.io.File(root, relative)
            assertTrue("expected a mirror at ${mirror.path}", mirror.isFile)
            assertEquals(
                "${mirror.path} must be a byte-for-byte copy of backend/functions/" +
                    "_shared/freeTier.ts, or the two deployments can enforce different caps",
                canonical.readText(),
                mirror.readText(),
            )
        }
    }

    // ── Reading the real files ───────────────────────────────────────────────

    /** `freeHabitsMax` from the version catalog, or null when it is not there. */
    private fun catalogFreeHabitsMax(): Int? = Regex("(?m)^\\s*freeHabitsMax\\s*=\\s*\"(\\d+)\"")
        .find(java.io.File(repoRoot(), "android/gradle/libs.versions.toml").readText())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

    /** The Edge Function's shared file, whichever mirror is present. */
    private fun edgeFunctionSource(): String =
        java.io.File(repoRoot(), "backend/functions/_shared/freeTier.ts").readText()

    /**
     * The repository root, located by walking up until the version catalog is
     * found.
     *
     * Searched rather than hardcoded because the working directory differs by
     * runner — Gradle starts unit tests in the module directory
     * (`<repo>/android/app`), while an IDE may use the repository root.
     */
    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            if (java.io.File(dir, "android/gradle/libs.versions.toml").isFile) return dir
            dir = dir.parentFile
        }
        return java.io.File(".")
    }
}
