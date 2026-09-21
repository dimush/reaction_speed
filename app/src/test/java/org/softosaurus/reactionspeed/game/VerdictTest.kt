package org.softosaurus.reactionspeed.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {

    @Test
    fun `maps the documented thresholds`() {
        assertEquals(5, Verdict.tierOf(0))
        assertEquals(5, Verdict.tierOf(219))
        assertEquals(4, Verdict.tierOf(220))
        assertEquals(4, Verdict.tierOf(249))
        assertEquals(3, Verdict.tierOf(250))
        assertEquals(3, Verdict.tierOf(299))
        assertEquals(2, Verdict.tierOf(300))
        assertEquals(2, Verdict.tierOf(349))
        assertEquals(1, Verdict.tierOf(350))
        assertEquals(1, Verdict.tierOf(10_000))
    }

    @Test
    fun `is monotonic and always in range`() {
        var previous = Verdict.TIER_COUNT
        for (ms in 0..2_000) {
            val tier = Verdict.tierOf(ms)
            assertTrue("tier $tier out of range at $ms ms", tier in 1..Verdict.TIER_COUNT)
            assertTrue("tier went up at $ms ms", tier <= previous)
            previous = tier
        }
    }
}
