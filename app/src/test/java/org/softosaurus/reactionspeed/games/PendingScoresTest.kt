package org.softosaurus.reactionspeed.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingScoresTest {

    @Test
    fun `empty by default`() {
        assertTrue(PendingScores.EMPTY.isEmpty)
        assertFalse(PendingScores(bestAverageMs = 300).isEmpty)
        assertFalse(PendingScores(bestSingleMs = 300).isEmpty)
    }

    @Test
    fun `first series fills both slots`() {
        val pending = PendingScores.EMPTY.withSeries(averageMs = 320, singleMs = 280)

        assertEquals(320, pending.bestAverageMs)
        assertEquals(280, pending.bestSingleMs)
    }

    @Test
    fun `smaller is better so merging keeps the minimum`() {
        val pending = PendingScores(bestAverageMs = 320, bestSingleMs = 280)
            .withSeries(averageMs = 300, singleMs = 290)

        assertEquals(300, pending.bestAverageMs)
        assertEquals(280, pending.bestSingleMs)
    }

    @Test
    fun `a worse series does not overwrite`() {
        val pending = PendingScores(bestAverageMs = 300, bestSingleMs = 250)
            .withSeries(averageMs = 400, singleMs = 350)

        assertEquals(300, pending.bestAverageMs)
        assertEquals(250, pending.bestSingleMs)
    }

    @Test
    fun `nulls are ignored`() {
        val pending = PendingScores(bestAverageMs = 300, bestSingleMs = 250)
            .withSeries(averageMs = null, singleMs = null)

        assertEquals(PendingScores(300, 250), pending)
        assertNull(PendingScores.EMPTY.withSeries(null, null).bestAverageMs)
    }

    @Test
    fun `legacy bests below the credibility floor are dropped`() {
        assertNull(PendingScores.credible(99))
        assertEquals(100, PendingScores.credible(100))
        assertNull(PendingScores.credible(null))

        val bootstrap = PendingScores.fromLocalBests(bestAverageMs = 180, bestSingleMs = 40)
        assertEquals(180, bootstrap.bestAverageMs)
        assertNull("an unverifiable 40 ms legacy record must not reach the board", bootstrap.bestSingleMs)
    }

    @Test
    fun `bootstrap of a user with no local results is empty`() {
        assertTrue(PendingScores.fromLocalBests(null, null).isEmpty)
    }
}
