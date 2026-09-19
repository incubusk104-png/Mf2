package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergeHabitsPreferringLocal] — the id-conflict rule for a habit merge (D5).
 *
 * ## The bug behind these tests
 *
 * Restore built its habit list as `snapshot.habits + data.habits` and collapsed
 * it with `distinctBy { it.id }`, which keeps the **first** occurrence. Cloud
 * came first, so the cloud copy won every conflict — the exact opposite of the
 * rest of the same function, where check-ins, logs and activity all let the
 * local entry win. Nothing in the code said "cloud wins"; it was an accident of
 * `+`, which is why the tests below assert the *rule* rather than an outcome.
 *
 * The user-visible symptom was that an unrelated background restore could
 * silently revert a habit the user had just edited, with a clean merge reported.
 *
 * Every test here is written against the **reverting** direction, because the
 * happy path (no conflict) passed under the old code too — that is precisely why
 * the defect survived review.
 */
class HabitMergeTest {

    private fun habit(
        id: String,
        name: String = id,
        alarmTimes: List<Int> = emptyList(),
        repeatDaysMask: Int = REPEAT_DAILY,
        message: String? = null,
    ) = Habit(
        id = id,
        name = name,
        alarmTimes = alarmTimes,
        repeatDaysMask = repeatDaysMask,
        alarmMessage = message,
    )

    // ── The conflict itself ───────────────────────────────────────────────────

    @Test
    fun `a local edit is never reverted by an older incoming copy`() {
        // THE destructive case. The local habit carries the user's newest edit;
        // the incoming one is stale. The stale copy must lose.
        val local = habit("h1", name = "Walk", alarmTimes = listOf(7 * 60, 18 * 60))
        val stale = habit("h1", name = "Walk", alarmTimes = listOf(7 * 60))

        val merged = mergeHabitsPreferringLocal(listOf(local), listOf(stale)).habits

        assertEquals(1, merged.size)
        assertEquals("the local (newer) schedule must survive", listOf(7 * 60, 18 * 60), merged.single().alarmTimes)
    }

    @Test
    fun `the local argument wins regardless of what it contains`() {
        // The rule is "the LOCAL side wins", not "the one that looks newer wins"
        // — so naming a different list as `local` must change the outcome. That is
        // the property the old `snapshot.habits + data.habits` ordering silently
        // broke, and asserting it in both directions pins the tie-break to the
        // parameter rather than to the list order.
        val mine = habit("h1", name = "Renamed locally")
        val theirs = habit("h1", name = "Old name")

        assertEquals("Renamed locally", mergeHabitsPreferringLocal(listOf(mine), listOf(theirs)).habits.single().name)
        assertEquals("Old name", mergeHabitsPreferringLocal(listOf(theirs), listOf(mine)).habits.single().name)
    }

    @Test
    fun `the order of the incoming list does not change the outcome`() {
        // Order within a side is irrelevant: only the side matters.
        val mine = habit("h1", name = "Mine")
        val a = habit("h1", name = "A")
        val b = habit("h2", name = "B")

        val first = mergeHabitsPreferringLocal(listOf(mine), listOf(a, b)).habits.map { it.name }
        val second = mergeHabitsPreferringLocal(listOf(mine), listOf(b, a)).habits.map { it.name }
        // `a` shares h1 with `mine`, so it is a CONFLICT — it is not appended,
        // `mine` is kept — and only `b` (h2) is new. Both orderings must therefore
        // give the same two names: the local one, then the adopted habit.
        assertEquals(listOf("Mine", "B"), first)
        assertEquals(listOf("Mine", "B"), second)
    }

    @Test
    fun `a removed alarm is not brought back by an incoming copy that still has it`() {
        // Deleting an alarm is the edit most likely to be lost, because the local
        // state it produces (empty) is the same shape as "never configured".
        val local = habit("h1", alarmTimes = emptyList())
        val incoming = habit("h1", alarmTimes = listOf(21 * 60))

        assertEquals(emptyList<Int>(), mergeHabitsPreferringLocal(listOf(local), listOf(incoming)).habits.single().alarmTimes)
    }

    @Test
    fun `a cleared repeat mask is not restored by an incoming copy`() {
        val local = habit("h1", repeatDaysMask = 0)
        val incoming = habit("h1", repeatDaysMask = REPEAT_DAILY)
        assertEquals(0, mergeHabitsPreferringLocal(listOf(local), listOf(incoming)).habits.single().repeatDaysMask)
    }

    @Test
    fun `a cleared alarm message is not restored by an incoming copy`() {
        val local = habit("h1", message = null)
        val incoming = habit("h1", message = "You've got this!")
        // assertNull rather than assertEquals(null, ...): the latter is ambiguous
        // against JUnit 4's (long, long) and (Object, Object) overloads in Kotlin.
        assertNull(mergeHabitsPreferringLocal(listOf(local), listOf(incoming)).habits.single().alarmMessage)
    }

    // ── Union behaviour ───────────────────────────────────────────────────────

    @Test
    fun `habits present on only one side all survive`() {
        // The merge is a union, so nothing may be dropped by either side.
        val merged = mergeHabitsPreferringLocal(
            local = listOf(habit("h1"), habit("h2")),
            incoming = listOf(habit("h3")),
        ).habits

        assertEquals(listOf("h1", "h2", "h3"), merged.map { it.id })
    }

    @Test
    fun `the user's habit order is preserved, with new habits appended`() {
        // A habit list is something the user arranged; a background merge is not
        // a reason to reshuffle it.
        val merged = mergeHabitsPreferringLocal(
            local = listOf(habit("h2"), habit("h1")),
            incoming = listOf(habit("h9")),
        ).habits
        assertEquals(listOf("h2", "h1", "h9"), merged.map { it.id })
    }

    @Test
    fun `no two rows exist for one habit`() {
        val merged = mergeHabitsPreferringLocal(
            local = listOf(habit("h1"), habit("h1", name = "dup")),
            incoming = listOf(habit("h1", name = "also dup")),
        ).habits
        assertEquals(1, merged.size)
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Test
    fun `conflicts are reported rather than resolved in silence`() {
        // The old code could not report a conflict because it did not know it had
        // one. Naming them is what lets the caller surface "we kept your copy".
        val result = mergeHabitsPreferringLocal(
            local = listOf(habit("h1"), habit("h2")),
            incoming = listOf(habit("h1"), habit("h3")),
        )

        assertEquals(listOf("h1"), result.keptLocal)
        assertEquals(listOf("h3"), result.addedFromIncoming)
        assertFalse(result.isNoOp)
    }

    @Test
    fun `merging an identical set is a reported no-op`() {
        val habits = listOf(habit("h1"), habit("h2"))
        val result = mergeHabitsPreferringLocal(habits, habits)
        assertEquals(habits, result.habits)
        assertEquals(listOf("h1", "h2"), result.keptLocal)
        assertTrue(result.isNoOp)
    }

    @Test
    fun `merging empty inputs in any combination is safe`() {
        assertEquals(emptyList<Habit>(), mergeHabitsPreferringLocal(emptyList(), emptyList()).habits)
        assertEquals(listOf("h1"), mergeHabitsPreferringLocal(emptyList(), listOf(habit("h1"))).habits.map { it.id })
        assertEquals(listOf("h1"), mergeHabitsPreferringLocal(listOf(habit("h1")), emptyList()).habits.map { it.id })
    }

    @Test
    fun `a large merge keeps every habit exactly once`() {
        // Large-data edge case: 500 local + 500 overlapping incoming.
        val local = (0 until 500).map { habit("h$it", name = "local-$it") }
        val incoming = (0 until 500).map { habit("h$it", name = "incoming-$it") }

        val merged = mergeHabitsPreferringLocal(local, incoming).habits

        assertEquals(500, merged.size)
        assertEquals(500, merged.map { it.id }.toSet().size)
        assertEquals(0, merged.count { it.name.startsWith("incoming-") })
    }

    @Test
    fun `emoji and special characters survive a merge unchanged`() {
        val name = "🚶‍♂️ walk — “daily” & <b>bold</b> \uD83C\uDF1E"
        val merged = mergeHabitsPreferringLocal(
            listOf(habit("h1", name = name)),
            listOf(habit("h1", name = "stale")),
        ).habits
        assertEquals(name, merged.single().name)
    }
}
