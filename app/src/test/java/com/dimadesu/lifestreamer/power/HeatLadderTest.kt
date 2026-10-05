package com.dimadesu.lifestreamer.power

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatLadderTest {
    private val minute = 60_000L

    @Test
    fun `it climbs at once, straight to the rung the temperature reaches`() {
        val ladder = HeatLadder()
        assertEquals(0, ladder.update(43.9f, 0))
        assertEquals(1, ladder.update(44f, 1))
        assertEquals(3, ladder.update(50.5f, 2))
    }

    @Test
    fun `it comes down one rung at a time, after holding cool enough for long enough`() {
        val ladder = HeatLadder()
        ladder.update(48f, 0)
        assertEquals(2, ladder.rung)

        // Below the rung's threshold, but not by the margin: no change, however long
        assertEquals(2, ladder.update(45f, 1 * minute))
        assertEquals(2, ladder.update(45f, 10 * minute))

        // 3 °C below 47: starts the hold
        assertEquals(2, ladder.update(44f, 11 * minute))
        assertEquals(2, ladder.update(44f, 13 * minute))
        assertEquals(1, ladder.update(44f, 14 * minute))

        // The next rung down needs its own margin (41 °C) and its own hold
        assertEquals(1, ladder.update(41f, 15 * minute))
        assertEquals(0, ladder.update(41f, 18 * minute))
    }

    @Test
    fun `a warm spell during the hold starts it again`() {
        val ladder = HeatLadder()
        ladder.update(45f, 0)
        ladder.update(40f, 1 * minute)
        ladder.update(42f, 3 * minute) // above 41: the hold is lost
        assertEquals(1, ladder.update(40f, 4 * minute + 1))
        assertEquals(0, ladder.update(40f, 7 * minute + 1))
    }

    @Test
    fun `an unknown temperature changes nothing`() {
        val ladder = HeatLadder()
        ladder.update(48f, 0)
        assertEquals(2, ladder.update(null, 60 * minute))
    }
}
