package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MotivationalMessages.sanitize] and `lineFor` — the safety boundary on text
 * that reaches the notification shade.
 *
 * ## Why sanitizing is not cosmetic
 *
 * The message is placed onto a **Notification** by a `BroadcastReceiver` that
 * runs with no Activity and no user present. That makes every property below a
 * correctness requirement rather than a formatting preference:
 *
 *  - An embedded newline renders as a broken, half-height notification row and is
 *    a known way to make a notification look like it came from another app.
 *  - An unbounded string is posted on *every* ring, and a pasted essay pushes the
 *    habit name and every action button off the notification entirely.
 *  - A whitespace-only value that counted as "set" would silently suppress the
 *    curated line pack, leaving an empty notification title.
 *
 * It is also the value written to the database and read back by a build that
 * predates the writer-side sanitizer, so it is applied on both ends.
 */
class MotivationalMessagesTest {

    // ── Length ───────────────────────────────────────────────────────────────

    @Test
    fun `a message over the limit is truncated to exactly 120 characters`() {
        val long = "a".repeat(400)
        val result = MotivationalMessages.sanitize(long)

        assertEquals(MotivationalMessages.MAX_MESSAGE_LENGTH, result.length)
        assertEquals(120, MotivationalMessages.MAX_MESSAGE_LENGTH)
        assertEquals("a".repeat(120), result)
    }

    @Test
    fun `a message of exactly the limit is preserved intact`() {
        val exact = "b".repeat(MotivationalMessages.MAX_MESSAGE_LENGTH)
        assertEquals(exact, MotivationalMessages.sanitize(exact))
    }

    @Test
    fun `a message one over the limit loses exactly one character`() {
        val over = "c".repeat(MotivationalMessages.MAX_MESSAGE_LENGTH + 1)
        assertEquals(
            "c".repeat(MotivationalMessages.MAX_MESSAGE_LENGTH),
            MotivationalMessages.sanitize(over),
        )
    }

    @Test
    fun `trimming happens before the length bound`() {
        // Otherwise a message of 118 characters padded with 10 spaces would be
        // truncated to 120 including the padding — and then trimmed back to 118
        // only by luck. Bounding the trimmed value is the correct order.
        val padded = "  " + "d".repeat(118) + "      "
        val result = MotivationalMessages.sanitize(padded)
        assertEquals("d".repeat(118), result)
        assertEquals(118, result.length)
    }

    // ── Control characters and whitespace ────────────────────────────────────

    @Test
    fun `control characters are removed and can never join two words`() {
        // The regression this locks down: the original implementation filtered
        // control characters BEFORE collapsing whitespace, so the newline became
        // nothing and "a\nwalk" turned into the non-word "awalk".
        val dirty = "Go for a\nwalk\u0000please\tnow"
        val result = MotivationalMessages.sanitize(dirty)

        assertEquals("Go for a walk please now", result)
        assertTrue("result must be single-line", !result.contains('\n'))
        assertTrue("result must contain no control chars", result.none { it.isISOControl() })
        assertTrue("words must not be fused", !result.contains("awalk"))
    }

    @Test
    fun `a control character inside a word never fuses it into a non-word`() {
        // Corrupt data puts NULs where high bytes were, i.e. mid-word. The point
        // is only that the result stays *readable*: never a control character, and
        // never two words glued into one.
        //
        // The escape is written as a char code because Kotlin has no `\0` escape —
        // an earlier revision used one and the test source failed to compile
        // ("Unsupported escape sequence"), which is what the CI diagnostics caught.
        val nul = 0.toChar()
        val result = MotivationalMessages.sanitize("Wa${nul}ter")

        assertTrue("must contain no control character", result.none { it.isISOControl() })
        assertTrue(
            "must stay readable: either 'Water' or 'Wa ter', got '$result'",
            result == "Water" || result == "Wa ter",
        )
    }

    @Test
    fun `runs of whitespace collapse to a single space`() {
        val result = MotivationalMessages.sanitize("Walk    every     morning")
        assertEquals("Walk every morning", result)
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("Walk", MotivationalMessages.sanitize("   Walk   "))
    }

    @Test
    fun `blank and whitespace-only input becomes empty`() {
        assertEquals("", MotivationalMessages.sanitize(null))
        assertEquals("", MotivationalMessages.sanitize(""))
        assertEquals("", MotivationalMessages.sanitize("   "))
        assertEquals("", MotivationalMessages.sanitize("\n\t\r"))
        assertEquals("", MotivationalMessages.sanitize("\u0000\u0001"))
    }

    @Test
    fun `emoji and non-latin text survive sanitizing`() {
        // The truncation is by Kotlin Char, and an emoji is a surrogate pair —
        // this asserts the common case round-trips untouched rather than that a
        // split surrogate is handled, which `take()` cannot do.
        val message = "Hydrate up! \uD83D\uDCA7 Drink water"
        assertEquals(message, MotivationalMessages.sanitize(message))
    }

    // ── lineFor: custom vs curated ───────────────────────────────────────────

    @Test
    fun `a blank custom message falls back to the habit's curated pack line`() {
        val fromBlank = MotivationalMessages.lineFor(
            habitId = "h1",
            iconId = "water",
            dayKey = "2026-09-17",
            customMessage = "   ",
        )
        val fromNull = MotivationalMessages.lineFor(
            habitId = "h1",
            iconId = "water",
            dayKey = "2026-09-17",
            customMessage = null,
        )

        assertTrue("a blank custom message must not become an empty title", fromBlank.isNotEmpty())
        assertEquals("blank and null must resolve identically", fromNull, fromBlank)
        assertEquals(
            "must be a line from the habit's own pack",
            true,
            MotivationalMessages.packFor("water").lines.contains(fromBlank),
        )
    }

    @Test
    fun `a custom message wins over the curated pack`() {
        val custom = "Water 2 litres"
        val result = MotivationalMessages.lineFor(
            habitId = "h1",
            iconId = "water",
            dayKey = "2026-09-17",
            customMessage = custom,
        )
        assertEquals(custom, result)
    }

    @Test
    fun `a custom message is sanitized on the way out too`() {
        // Read-side sanitizing: a value restored from the cloud or written by an
        // older build never passed through the editor.
        val result = MotivationalMessages.lineFor(
            habitId = "h1",
            iconId = "water",
            dayKey = "2026-09-17",
            customMessage = "  Water\n2   litres  ",
        )
        assertEquals("Water 2 litres", result)
    }

    @Test
    fun `an over-long custom message is bounded before it reaches the notification`() {
        val result = MotivationalMessages.lineFor(
            habitId = "h1",
            iconId = "water",
            dayKey = "2026-09-17",
            customMessage = "x".repeat(500),
        )
        assertEquals(MotivationalMessages.MAX_MESSAGE_LENGTH, result.length)
    }

    @Test
    fun `the three alarm times of one habit each get a different line`() {
        // A habit ringing at 07:00, 12:00 and 18:00 must not read as the same nag
        // three times.
        //
        // ## This test exists because it FAILED
        //
        // The first implementation rotated the line by mixing `alarmMinutes`
        // into the seed. That looks correct and is not: the packs are only 5–8
        // lines, and 07:00/12:00/18:00 differ by 300 and 360 minutes, which are
        // both ≡ 0 modulo 5 — so **12 of the 13 packs** produced the identical
        // line for all three alarms. The intent was right and the arithmetic was
        // wrong, which is precisely the kind of bug a test has to catch rather
        // than a reader.
        //
        // The fix rotates by the alarm's *position* in the schedule, which makes
        // distinctness exact instead of probabilistic. Every pack is asserted
        // below, not just water, because "water happens to work" was the shape of
        // the original mistake.
        val packs = listOf("water", "medicine", "movement", "sleep", "reading", "general", "not-a-real-icon")
        val schedule = listOf(7 * 60, 12 * 60, 18 * 60)

        packs.forEach { iconId ->
            val lines = schedule.mapIndexed { index, _ ->
                MotivationalMessages.lineFor(
                    habitId = "h1",
                    iconId = iconId,
                    dayKey = "2026-09-17",
                    alarmMinutes = schedule[index],
                    alarmIndex = index,
                )
            }
            assertEquals(
                "pack '$iconId' must give three distinct lines across the day",
                3,
                lines.distinct().size,
            )
        }
    }

    @Test
    fun `distinct lines hold across many habits and days, not just one lucky pair`() {
        // Guards against a formula that is distinct for 'h1' on one date and not
        // in general — the failure mode of tuning a hash until a single test
        // passes.
        val schedule = listOf(7 * 60, 12 * 60, 18 * 60)
        val packs = listOf("water", "medicine", "movement", "sleep", "general")

        var collisions = 0
        (0 until 40).forEach { h ->
            (1..28).forEach { day ->
                packs.forEach { iconId ->
                    val lines = schedule.mapIndexed { index, _ ->
                        MotivationalMessages.lineFor(
                            habitId = "habit$h",
                            iconId = iconId,
                            dayKey = "2026-09-${day.toString().padStart(2, '0')}",
                            alarmIndex = index,
                        )
                    }
                    if (lines.distinct().size != 3) collisions++
                }
            }
        }
        assertEquals("no (habit, day, pack) combination may collide", 0, collisions)
    }

    @Test
    fun `alarmIndexFor maps a time to its position in the schedule`() {
        val schedule = listOf(7 * 60, 12 * 60, 18 * 60)
        assertEquals(0, MotivationalMessages.alarmIndexFor(7 * 60, schedule))
        assertEquals(1, MotivationalMessages.alarmIndexFor(12 * 60, schedule))
        assertEquals(2, MotivationalMessages.alarmIndexFor(18 * 60, schedule))
        // A time no longer in the schedule (the user edited it away), or no time
        // at all (an intent from an older build): fall back to the first line
        // rather than to no line.
        assertEquals(0, MotivationalMessages.alarmIndexFor(9 * 60, schedule))
        assertEquals(0, MotivationalMessages.alarmIndexFor(null, schedule))
        assertEquals(0, MotivationalMessages.alarmIndexFor(7 * 60, emptyList()))
    }

    @Test
    fun `the same habit, day and time always yields the same line`() {
        // Stability matters: the notification and the dialog must agree, and a
        // line that changed on every read would look like a glitch.
        val first = MotivationalMessages.lineFor("h1", "water", "2026-09-17", alarmMinutes = 7 * 60)
        val second = MotivationalMessages.lineFor("h1", "water", "2026-09-17", alarmMinutes = 7 * 60)
        assertEquals(first, second)
    }

    @Test
    fun `different habits on the same day do not all get the same line`() {
        val a = MotivationalMessages.lineFor("habit-a", "water", "2026-09-17")
        val b = MotivationalMessages.lineFor("habit-b", "water", "2026-09-17")
        assertNotEquals(a, b)
    }

    @Test
    fun `an unknown icon still produces a usable line`() {
        val result = MotivationalMessages.lineFor("h1", "not-a-real-icon", "2026-09-17")
        assertTrue("must fall back rather than return empty", result.isNotEmpty())
    }

    @Test
    fun `a habit carrying no icon still produces a usable line`() {
        val result = MotivationalMessages.lineFor("h1", null, "2026-09-17")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `every sanitized line is notification-safe`() {
        // A property check across the packs actually shipped, rather than only
        // the values this test file happens to name.
        val iconIds = listOf("water", "walking", "running", "meditation", "reading", "")
        iconIds.forEach { iconId ->
            val line = MotivationalMessages.lineFor("h1", iconId, "2026-09-17", alarmMinutes = 7 * 60)
            assertEquals("single line for '$iconId'", -1, line.indexOf('\n'))
            assertTrue("bounded for '$iconId'", line.length <= MotivationalMessages.MAX_MESSAGE_LENGTH)
            assertTrue("non-blank for '$iconId'", line.isNotBlank())
            assertEquals("already-sanitized for '$iconId'", line, MotivationalMessages.sanitize(line))
        }
    }
}
