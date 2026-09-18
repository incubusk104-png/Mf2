package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the screen-time save.
 *
 * The reported failure was that **editing a limit deleted habits**. These assert
 * the rule that prevents it: a screen-time save may only ever touch the habits it
 * is actually about, and only a package the sheet was seeded with — one the user
 * was genuinely shown — can be removed.
 *
 * Pure JVM tests, no Compose: the bug was in the reconciliation *rule*, so
 * asserting the rule directly is both cheaper and a stronger guarantee than
 * driving the sheet.
 */
class ScreenTimeLimitsTest {

    private fun habit(
        id: String,
        name: String = id,
        iconId: String? = "meditate",
        pkg: String? = null,
        limit: Int? = null,
        label: String? = null,
    ) = Habit(
        id = id,
        name = name,
        createdAt = 1_700_000_000_000L,
        iconId = iconId,
        monitoredPackage = pkg,
        screenTimeLimitMinutes = limit,
        monitoredAppLabel = label,
    )

    private fun screenHabit(id: String, pkg: String, limit: Int, label: String = pkg) =
        habit(id = id, name = "", iconId = "screenTime", pkg = pkg, limit = limit, label = label)

    private fun data(habits: List<Habit>, checkIns: Map<String, List<String>> = emptyMap()) =
        AppData(habits = habits, checkIns = checkIns)

    // ── The bug: editing a limit must not delete anything ─────────────────────

    @Test
    fun `editing one limit leaves every other habit intact`() {
        val current = data(
            habits = listOf(
                habit("meditate", "Meditate"),
                habit("walk", "Walk"),
                screenHabit("fb", "com.facebook.katana", 120, "Facebook"),
            ),
        )

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.facebook.katana", "Facebook", 90)),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals("no habit may be removed by an edit", emptyList<Habit>(), plan.removed)
        assertEquals(3, plan.habits.size)
        assertTrue("Meditate must survive verbatim", plan.habits.contains(habit("meditate", "Meditate")))
        assertTrue("Walk must survive verbatim", plan.habits.contains(habit("walk", "Walk")))
        assertEquals(
            "the edited limit must take its new value",
            90,
            plan.habits.first { it.id == "fb" }.screenTimeLimitMinutes,
        )
    }

    @Test
    fun `non screen-time habits are never filtered out of the result`() {
        // The old implementation rebuilt the list from a filtered view of it,
        // so any habit the filter did not re-derive simply vanished.
        val habits = (1..12).map { habit("h$it", "Habit $it") }
        val current = data(habits = habits + screenHabit("fb", "com.facebook.katana", 60, "Facebook"))

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.facebook.katana", "Facebook", 60)),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals(13, plan.habits.size)
        habits.forEach { assertTrue("$it must survive", plan.habits.contains(it)) }
    }

    @Test
    fun `a limit whose stored row is missing its minutes is not read as removable`() {
        // `isScreenTimeHabit` needs BOTH the package and the limit. A habit with
        // a package but no limit is *not* a screen-time habit, so it cannot be
        // matched as one — and must therefore never be classified as removed.
        val damaged = habit("fb", "Facebook", iconId = "screenTime", pkg = "com.facebook.katana", limit = null)
        val current = data(habits = listOf(habit("meditate", "Meditate"), damaged))

        val plan = planScreenTimeLimits(
            current = current,
            limits = emptyList(),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals("nothing may be removed", emptyList<Habit>(), plan.removed)
        assertEquals(listOf("meditate", "fb"), plan.habits.map { it.id })
    }

    // ── Only a seeded package can be removed ─────────────────────────────────

    @Test
    fun `a package the sheet was not seeded with is never removed`() {
        // A limit that arrived from a cloud pull while the sheet was open was
        // never shown to the user, so its absence from the result cannot mean
        // "the user cleared it".
        val current = data(
            habits = listOf(screenHabit("come-later", "com.newapp", 45, "New App")),
        )

        val plan = planScreenTimeLimits(
            current = current,
            limits = emptyList(),
            removablePackages = emptySet(),
        )

        assertEquals(emptyList<Habit>(), plan.removed)
        assertEquals(1, plan.habits.size)
    }

    @Test
    fun `clearing a seeded limit does remove it`() {
        val current = data(
            habits = listOf(
                habit("meditate", "Meditate"),
                screenHabit("fb", "com.facebook.katana", 120, "Facebook"),
            ),
            checkIns = mapOf("fb" to listOf("2026-09-18")),
        )

        val plan = planScreenTimeLimits(
            current = current,
            limits = emptyList(),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals(listOf("fb"), plan.removed.map { it.id })
        assertEquals(listOf("meditate"), plan.habits.map { it.id })
        assertEquals("the removed limit's history goes with it", emptyMap<String, List<String>>(), plan.checkIns)
    }

    @Test
    fun `removing one limit keeps the other limit and its history`() {
        val current = data(
            habits = listOf(
                screenHabit("fb", "com.facebook.katana", 120, "Facebook"),
                screenHabit("ig", "com.instagram.android", 60, "Instagram"),
            ),
            checkIns = mapOf(
                "fb" to listOf("2026-09-18"),
                "ig" to listOf("2026-09-17", "2026-09-18"),
            ),
        )

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.instagram.android", "Instagram", 60)),
            removablePackages = setOf("com.facebook.katana", "com.instagram.android"),
        )

        assertEquals(listOf("fb"), plan.removed.map { it.id })
        assertEquals(listOf("ig"), plan.habits.map { it.id })
        assertEquals(mapOf("ig" to listOf("2026-09-17", "2026-09-18")), plan.checkIns)
    }

    // ── Adding ───────────────────────────────────────────────────────────────

    @Test
    fun `a new limit is added as its own habit with a generated id`() {
        val current = data(habits = listOf(habit("meditate", "Meditate")))

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.tiktok", "TikTok", 30)),
            removablePackages = emptySet(),
        )

        assertEquals(1, plan.added.size)
        val added = plan.added.single()
        assertEquals("com.tiktok", added.monitoredPackage)
        assertEquals(30, added.screenTimeLimitMinutes)
        assertTrue("the added habit's id must be its own", added.id != "meditate")
        assertEquals("TikTok under 30m", added.name)
        assertEquals(2, plan.habits.size)
    }

    @Test
    fun `adding a limit does not disturb existing habits or their history`() {
        val current = data(
            habits = listOf(
                habit("meditate", "Meditate"),
                screenHabit("fb", "com.facebook.katana", 120, "Facebook"),
            ),
            checkIns = mapOf("meditate" to listOf("2026-09-18")),
        )

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(
                ScreenTimeLimitInput("com.facebook.katana", "Facebook", 120),
                ScreenTimeLimitInput("com.tiktok", "TikTok", 30),
            ),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals(mapOf("meditate" to listOf("2026-09-18")), plan.checkIns)
        assertTrue(plan.habits.contains(habit("meditate", "Meditate")))
        assertEquals(3, plan.habits.size)
        assertEquals(1, plan.added.size)
        assertEquals(emptyList<Habit>(), plan.removed)
    }

    @Test
    fun `re-saving the same limits is a no-op`() {
        val fb = screenHabit("fb", "com.facebook.katana", 120, "Facebook")
        val current = data(habits = listOf(habit("meditate", "Meditate"), fb))

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.facebook.katana", "Facebook", 120)),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals(emptyList<Habit>(), plan.updated)
        assertEquals(emptyList<Habit>(), plan.added)
        assertEquals(emptyList<Habit>(), plan.removed)
        assertEquals(current.habits, plan.habits)
    }

    @Test
    fun `a renamed app label updates the limit and its title`() {
        val current = data(habits = listOf(screenHabit("fb", "com.facebook.katana", 120, "Facebook")))
        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.facebook.katana", "Facebook Lite", 120)),
            removablePackages = setOf("com.facebook.katana"),
        )

        val updated = plan.updated.single()
        assertEquals("Facebook Lite", updated.monitoredAppLabel)
        assertEquals("Facebook Lite under 2h", updated.name)
    }

    // ── Order, duplicates, counts ────────────────────────────────────────────

    @Test
    fun `a limit only ever updates its own package`() {
        val current = data(
            habits = listOf(
                screenHabit("fb", "com.facebook.katana", 120, "Facebook"),
                screenHabit("ig", "com.instagram.android", 120, "Instagram"),
            ),
        )

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(
                ScreenTimeLimitInput("com.facebook.katana", "Facebook", 15),
                ScreenTimeLimitInput("com.instagram.android", "Instagram", 120),
            ),
            removablePackages = setOf("com.facebook.katana", "com.instagram.android"),
        )

        assertEquals(15, plan.habits.first { it.id == "fb" }.screenTimeLimitMinutes)
        assertEquals(120, plan.habits.first { it.id == "ig" }.screenTimeLimitMinutes)
        assertEquals(1, plan.updated.size)
    }

    @Test
    fun `a duplicate stored package is updated rather than silently dropped`() {
        // Two habits for one package should not exist, but the path that must
        // never delete cannot be allowed to "fix" it by dropping one either.
        val current = data(
            habits = listOf(
                screenHabit("fb1", "com.facebook.katana", 120, "Facebook"),
                screenHabit("fb2", "com.facebook.katana", 120, "Facebook"),
            ),
        )

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.facebook.katana", "Facebook", 45)),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals(emptyList<Habit>(), plan.removed)
        assertEquals(2, plan.habits.size)
        assertTrue(
            "both rows must carry the chosen limit",
            plan.habits.all { it.screenTimeLimitMinutes == 45 },
        )
    }

    @Test
    fun `user ordering of non screen-time habits is preserved`() {
        val habits = listOf(
            habit("a", "A"),
            screenHabit("fb", "com.facebook.katana", 60, "Facebook"),
            habit("b", "B"),
            habit("c", "C"),
        )
        val plan = planScreenTimeLimits(
            current = data(habits = habits),
            limits = listOf(ScreenTimeLimitInput("com.facebook.katana", "Facebook", 90)),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals(listOf("a", "fb", "b", "c"), plan.habits.map { it.id })
    }

    @Test
    fun `limitedCount reports the resulting limit count`() {
        val current = data(
            habits = listOf(
                habit("meditate", "Meditate"),
                screenHabit("fb", "com.facebook.katana", 120, "Facebook"),
            ),
        )
        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(
                ScreenTimeLimitInput("com.facebook.katana", "Facebook", 120),
                ScreenTimeLimitInput("com.tiktok", "TikTok", 30),
            ),
            removablePackages = setOf("com.facebook.katana"),
        )

        assertEquals(2, plan.limitedCount)
    }

    @Test
    fun `an empty install can add its first limit`() {
        val plan = planScreenTimeLimits(
            current = AppData(),
            limits = listOf(ScreenTimeLimitInput("com.tiktok", "TikTok", 30)),
            removablePackages = emptySet(),
        )

        assertEquals(1, plan.habits.size)
        assertEquals(1, plan.added.size)
        assertTrue(plan.checkIns.isEmpty())
    }
}
