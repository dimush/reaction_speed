package org.softosaurus.reactionspeed.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.softosaurus.reactionspeed.game.Attempt
import org.softosaurus.reactionspeed.game.SeriesResult

class AchievementRulesTest {

    private fun series(
        meanMs: Int = 400,
        stdDev: Double = 40.0,
        bestSingle: Int = 300,
        falseStarts: Int = 0,
        misses: Int = 0,
        allAccepted: Boolean = true,
        fastestAttemptMs: Int = 300,
    ): SeriesResult {
        val attempts = buildList {
            add(Attempt(reactionTimeMs = fastestAttemptMs, accepted = true))
            repeat(8) { add(Attempt(reactionTimeMs = meanMs, accepted = true)) }
            add(Attempt(reactionTimeMs = meanMs, accepted = allAccepted))
        }
        return SeriesResult(
            attempts = attempts,
            rawMean = meanMs.toDouble(),
            stdDev = stdDev,
            filteredMean = meanMs,
            bestSingle = bestSingle,
            falseStarts = falseStarts,
            misses = misses,
        )
    }

    @Test
    fun `no series means nothing`() {
        assertEquals(AchievementOutcome.NONE, AchievementRules.evaluate(series(), seriesCount = 0))
    }

    @Test
    fun `first series is unlocked for any positive count`() {
        val migrated = AchievementRules.evaluate(series(), seriesCount = 42)
        assertTrue(AchievementIds.FIRST_SERIES in migrated.unlock)
        val fresh = AchievementRules.evaluate(series(), seriesCount = 1)
        assertTrue(AchievementIds.FIRST_SERIES in fresh.unlock)
    }

    @Test
    fun `implausible series earns only first series`() {
        // A sub-100 ms attempt makes the whole series implausible.
        val cheated = series(meanMs = 200, stdDev = 5.0, fastestAttemptMs = 40)
        assertFalse(cheated.isPlausible)

        val outcome = AchievementRules.evaluate(cheated, seriesCount = 3)

        assertEquals(listOf(AchievementIds.FIRST_SERIES), outcome.unlock)
        assertEquals(emptyList<String>(), outcome.increment)
    }

    @Test
    fun `mean thresholds are cumulative`() {
        val outcome = AchievementRules.evaluate(series(meanMs = 210, stdDev = 40.0), seriesCount = 5)

        assertTrue(AchievementIds.UNDER_350 in outcome.unlock)
        assertTrue(AchievementIds.UNDER_300 in outcome.unlock)
        assertTrue(AchievementIds.UNDER_250 in outcome.unlock)
        assertTrue(AchievementIds.UNDER_220 in outcome.unlock)
    }

    @Test
    fun `mean thresholds are strictly less than`() {
        val exactly350 = AchievementRules.evaluate(series(meanMs = 350), seriesCount = 1)
        assertFalse(AchievementIds.UNDER_350 in exactly350.unlock)

        val justUnder = AchievementRules.evaluate(series(meanMs = 349), seriesCount = 1)
        assertTrue(AchievementIds.UNDER_350 in justUnder.unlock)
        assertFalse(AchievementIds.UNDER_300 in justUnder.unlock)
    }

    @Test
    fun `steady hand needs std dev strictly under 25 ms`() {
        assertFalse(
            AchievementIds.STEADY_HAND in
                AchievementRules.evaluate(series(stdDev = 25.0), seriesCount = 1).unlock
        )
        assertTrue(
            AchievementIds.STEADY_HAND in
                AchievementRules.evaluate(series(stdDev = 24.9), seriesCount = 1).unlock
        )
    }

    @Test
    fun `flawless needs every attempt accepted and no false starts or misses`() {
        assertTrue(
            AchievementIds.FLAWLESS in
                AchievementRules.evaluate(series(), seriesCount = 1).unlock
        )
        assertFalse(
            AchievementIds.FLAWLESS in
                AchievementRules.evaluate(series(allAccepted = false), seriesCount = 1).unlock
        )
        assertFalse(
            AchievementIds.FLAWLESS in
                AchievementRules.evaluate(series(falseStarts = 1), seriesCount = 1).unlock
        )
        assertFalse(
            AchievementIds.FLAWLESS in
                AchievementRules.evaluate(series(misses = 2), seriesCount = 1).unlock
        )
    }

    @Test
    fun `every plausible series advances all three counters by one step`() {
        val outcome = AchievementRules.evaluate(series(), seriesCount = 7)

        assertEquals(
            listOf(
                AchievementIds.SERIES_10,
                AchievementIds.SERIES_50,
                AchievementIds.SERIES_200,
            ),
            outcome.increment,
        )
    }

    @Test
    fun `a plain slow but clean series earns first series flawless and the counters`() {
        val outcome = AchievementRules.evaluate(series(meanMs = 500, stdDev = 80.0), seriesCount = 2)

        assertEquals(
            listOf(AchievementIds.FIRST_SERIES, AchievementIds.FLAWLESS),
            outcome.unlock,
        )
        assertEquals(3, outcome.increment.size)
    }

    @Test
    fun `outcome is empty only when nothing is earned`() {
        assertTrue(AchievementOutcome.NONE.isEmpty)
        assertFalse(AchievementRules.evaluate(series(), seriesCount = 1).isEmpty)
    }
}
