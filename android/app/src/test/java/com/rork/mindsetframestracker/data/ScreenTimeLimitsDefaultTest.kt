package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `removablePackages` default (D7).
 *
 * ## The risk behind these tests
 *
 * `emptySet()` made an omitted argument fail *closed* — nothing could be removed
 * — which does prevent the habit-deletion bug the parameter exists for. But it
 * does so by turning **every removal into a silent no-op**: a caller that forgot
 * the argument would get a picker whose limits could be added and never cleared,
 * with no error and no hint.
 *
 * A default that disables a whole user action is not a safe default. It now
 * resolves to every screen-time habit currently on the device, which is the
 * honest reading of "we were not told what was shown, so assume everything was".
 *
 * The tests below assert both halves: the omitted argument still **works**, and
 * an explicit narrow set still **protects** an unseen habit.
 */
class ScreenTimeLimitsDefaultTest {

    private fun screenTimeHabit(id: String, pkg: String, limit: Int? = 90) = Habit(
        id = id,
        name = id,
        monitoredPackage = pkg,
        screenTimeLimitMinutes = limit,
        monitoredAppLabel = pkg,
    )

    private fun plainHabit(id: String) = Habit(id = id, name = id, iconId = "meditate")

    @Test
    fun `omitting removablePackages still allows a removal`() {
        // THE regression: under the old default this returned 0 removed, so the
        // clear button in the picker did nothing and said nothing.
        val current = AppData(habits = listOf(screenTimeHabit("fb", "com.facebook.katana")))

        val plan = planScreenTimeLimits(current = current, limits = emptyList())

        assertEquals("the limit must be removable", 1, plan.removed.size)
        assertEquals(listOf("fb"), plan.removedIds)
    }

    @Test
    fun `omitting removablePackages can still update an existing limit`() {
        val current = AppData(habits = listOf(screenTimeHabit("fb", "com.facebook.katana", 90)))

        val plan = planScreenTimeLimits(
            current = current,
            limits = listOf(ScreenTimeLimitInput("com.facebook.katana", "Facebook", 30)),
        )

        assertEquals(1, plan.updated.size)
        // `!!` so this is Int-vs-Int: an Int? would be ambiguous between JUnit 4's
        // (long, long) and (Object, Object) overloads.
        assertEquals(30, plan.updated.single().screenTimeLimitMinutes!!)
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun `omitting removablePackages still adds a new limit`() {
        val plan = planScreenTimeLimits(
            current = AppData(habits = listOf(plainHabit("walk"))),
            limits = listOf(ScreenTimeLimitInput("com.tiktok", "TikTok", 30)),
        )
        assertEquals(1, plan.added.size)
        assertEquals("com.tiktok", plan.added.single().monitoredPackage)
    }

    @Test
    fun `omitting removablePackages never touches a non-screen-time habit`() {
        // The rule the parameter exists for, restated under the new default: the
        // default is "everything the picker could have shown", which is still only
        // screen-time habits — never the user's other habits.
        val current = AppData(
            habits = listOf(
                screenTimeHabit("fb", "com.facebook.katana"),
                plainHabit("walk"),
                plainHabit("read"),
                plainHabit("meditate"),
            ),
        )

        val plan = planScreenTimeLimits(current = current, limits = emptyList())

        assertEquals(listOf("fb"), plan.removedIds)
        assertEquals(
            "every non-screen-time habit must survive",
            setOf("walk", "read", "meditate"),
            plan.habits.map { it.id }.filter { it != "fb" }.toSet(),
        )
    }

    @Test
    fun `an explicit narrow seed still protects a limit the user never saw`() {
        // The protection must not have been weakened: with an explicit set that
        // does not contain the habit's package, the habit survives even though it
        // is absent from `limits`.
        val current = AppData(habits = listOf(screenTimeHabit("fb", "com.facebook.katana")))

        val plan = planScreenTimeLimits(
            current = current,
            limits = emptyList(),
            removablePackages = emptySet(),
        )

        assertTrue("an unseen limit must never be read as cleared", plan.removed.isEmpty())
        assertEquals(listOf("fb"), plan.habits.map { it.id })
    }

    @Test
    fun `an empty device with an omitted argument is a safe no-op`() {
        val plan = planScreenTimeLimits(current = AppData(), limits = emptyList())
        assertTrue(plan.removed.isEmpty())
        assertTrue(plan.added.isEmpty())
        assertTrue(plan.updated.isEmpty())
    }

    @Test
    fun `a screen-time habit with no package is not removable by default`() {
        // `isScreenTimeHabit` requires both a package and a limit, so a row that
        // somehow lost its package is not a candidate for removal — it must not be
        // deleted by a default that was meant to describe screen-time habits.
        val orphan = Habit(id = "orphan", name = "Orphan", monitoredPackage = null, screenTimeLimitMinutes = 90)
        val plan = planScreenTimeLimits(current = AppData(habits = listOf(orphan)), limits = emptyList())
        assertTrue(plan.removed.isEmpty())
    }
}
