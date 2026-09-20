package org.softosaurus.reactionspeed.game

import kotlin.math.sqrt

/**
 * Scoring of a finished series, reproducing the legacy arithmetic of `MySurfaceView` exactly.
 *
 * Legacy algorithm:
 * 1. `rt_av` = arithmetic mean of all 10 reaction times.
 * 2. `rt_sko` = sqrt(sum((rt - rt_av)^2) / 10) — **population** standard deviation over all ten
 *    samples (`rt_count` was still 10 at that point; it was reset to 0 only afterwards).
 * 3. A sample is kept when `(rt - rt_av) < rt_sko` — one-sided: only slow outliers are dropped,
 *    and the boundary case `rt - mean == stdDev` is **discarded**.
 * 4. The official score is the mean of the kept samples, truncated to Int.
 */
object SeriesScorer {

    /**
     * Scores [reactionTimesMs] (chronological order).
     *
     * @param reactionTimesMs one entry per attempt; must not be empty
     * @param falseStarts number of taps made while the target was hidden
     * @param misses number of off-target taps made while the target was visible
     * @param minPlausibleMs anti-cheat threshold carried into [SeriesResult.isPlausible]
     */
    fun score(
        reactionTimesMs: List<Int>,
        falseStarts: Int = 0,
        misses: Int = 0,
        minPlausibleMs: Int = 100,
    ): SeriesResult {
        require(reactionTimesMs.isNotEmpty()) { "a series needs at least one attempt" }

        val n = reactionTimesMs.size
        val rawMean = reactionTimesMs.sumOf { it.toDouble() } / n
        val variance = reactionTimesMs.sumOf { rt ->
            val d = rt - rawMean
            d * d
        } / n
        val stdDev = sqrt(variance)

        // Legacy keep-rule: (rt - mean) < stdDev. Discard on >=.
        var accepted = reactionTimesMs.map { (it - rawMean) < stdDev }
        if (accepted.none { it }) {
            // Only reachable when every sample is identical (stdDev == 0, rt - mean == 0), where
            // legacy simply restarted the series. Accepting everything keeps the engine total and
            // yields the same number the player saw for those ten taps.
            accepted = List(n) { true }
        }

        val keptSum = reactionTimesMs.filterIndexed { i, _ -> accepted[i] }.sumOf { it.toDouble() }
        val keptCount = accepted.count { it }
        val filteredMean = (keptSum / keptCount).toInt() // legacy truncates, (int)rt_av

        return SeriesResult(
            attempts = reactionTimesMs.mapIndexed { i, rt -> Attempt(rt, accepted[i]) },
            rawMean = rawMean,
            stdDev = stdDev,
            filteredMean = filteredMean,
            bestSingle = reactionTimesMs.min(),
            falseStarts = falseStarts,
            misses = misses,
            minPlausibleMs = minPlausibleMs,
        )
    }
}
