package org.softosaurus.reactionspeed.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parity tests for the legacy outlier rule (see [SeriesScorer]). */
class SeriesScorerTest {

    /**
     * Boundary case, built from values whose mean and variance are exactly representable so that
     * the test pins the `>=` semantics rather than floating-point luck: five 280s and five 320s
     * give mean 300 and population std-dev exactly 20, so every 320 sits at `mean + stdDev` and
     * must be **discarded** (legacy keeps only `rt - mean < stdDev`).
     */
    @Test
    fun `sample exactly at mean plus stdDev is discarded`() {
        val values = List(5) { 280 } + List(5) { 320 }

        val result = SeriesScorer.score(values)

        assertEquals(300.0, result.rawMean, 0.0)
        assertEquals(20.0, result.stdDev, 0.0)
        assertEquals(listOf(true, true, true, true, true, false, false, false, false, false),
            result.attempts.map { it.accepted })
        assertEquals(280, result.filteredMean)
        assertFalse(result.allAccepted)
    }

    /** Legacy arithmetic on a single slow outlier: exact mean 310, exact std-dev 30. */
    @Test
    fun `single slow outlier is dropped`() {
        val values = List(9) { 300 } + listOf(400)

        val result = SeriesScorer.score(values)

        assertEquals(310.0, result.rawMean, 0.0)
        assertEquals(30.0, result.stdDev, 0.0)
        assertEquals(9, result.attempts.count { it.accepted })
        assertEquals(300, result.filteredMean)
        assertEquals(300, result.bestSingle)
    }

    /** Fast samples are never dropped: the rule is one-sided. */
    @Test
    fun `fast outlier is kept`() {
        val values = listOf(120) + List(9) { 300 }

        val result = SeriesScorer.score(values)

        assertTrue(result.attempts.all { it.accepted })
        assertTrue(result.allAccepted)
        assertEquals(282, result.filteredMean) // 2820 / 10 = 282
    }

    /** Legacy truncated the final mean with a C-style `(int)` cast. */
    @Test
    fun `filtered mean is truncated not rounded`() {
        val result = SeriesScorer.score(listOf(100, 101, 101))

        assertTrue(result.attempts.all { it.accepted })
        assertEquals(100, result.filteredMean) // 100.666... -> 100
    }

    /**
     * All-identical samples make std-dev zero, so the legacy rule would reject everything and the
     * old code restarted the series. The engine instead accepts everything; documented deviation.
     */
    @Test
    fun `identical samples fall back to accepting everything`() {
        val result = SeriesScorer.score(List(10) { 250 })

        assertEquals(0.0, result.stdDev, 0.0)
        assertTrue(result.allAccepted)
        assertEquals(250, result.filteredMean)
    }

    @Test
    fun `plausibility rejects impossible single reaction`() {
        val withCheat = SeriesScorer.score(listOf(20) + List(9) { 300 })
        assertFalse(withCheat.isPlausible)

        val honest = SeriesScorer.score(List(10) { 300 })
        assertTrue(honest.isPlausible)
    }

    @Test
    fun `plausibility rejects impossible mean`() {
        val result = SeriesScorer.score(List(10) { 99 })

        assertEquals(99, result.filteredMean)
        assertFalse(result.isPlausible)
    }

    @Test
    fun `false starts and misses are carried through`() {
        val result = SeriesScorer.score(List(10) { 300 }, falseStarts = 3, misses = 2)

        assertEquals(3, result.falseStarts)
        assertEquals(2, result.misses)
    }
}
