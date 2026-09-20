package org.softosaurus.reactionspeed.games

/**
 * The best not-yet-submitted scores of a signed-out player, and the rules for merging them.
 *
 * Pure Kotlin on purpose: this is the only part of the pending-submission feature with real
 * decisions in it, so it is the part that carries unit tests. The tiny `SharedPreferences` file
 * that persists it lives in [PlayGamesManager] and is deliberately *not* abstracted behind an
 * interface — it stores two ints and a boolean.
 *
 * Smaller is better on both leaderboards, so "merging" always means taking the minimum.
 */
data class PendingScores(
    val bestAverageMs: Int? = null,
    val bestSingleMs: Int? = null,
) {

    val isEmpty: Boolean get() = bestAverageMs == null && bestSingleMs == null

    /** Folds a freshly finished (already plausibility-checked) series into the pending set. */
    fun withSeries(averageMs: Int?, singleMs: Int?): PendingScores = PendingScores(
        bestAverageMs = best(bestAverageMs, averageMs),
        bestSingleMs = best(bestSingleMs, singleMs),
    )

    companion object {
        val EMPTY = PendingScores()

        /**
         * Sanity floor for values that cannot be verified after the fact — i.e. the locally
         * stored bests of a user migrated from 3.x, which were produced by a build that had no
         * anti-cheat gate at all. A sub-100 ms reaction is physiologically impossible, so such a
         * legacy record is dropped rather than pushed onto a public board.
         *
         * Note this is a *different* check from [org.softosaurus.reactionspeed.game.SeriesResult.isPlausible]:
         * that one gates live results (where every attempt is known), this one gates a single
         * opaque number.
         */
        const val MIN_CREDIBLE_MS = 100

        /** Returns [ms] if it is a credible legacy record, otherwise `null`. */
        fun credible(ms: Int?): Int? = ms?.takeIf { it >= MIN_CREDIBLE_MS }

        /**
         * Builds the one-off "bootstrap" submission for a migrated user from their locally
         * stored bests. Both values are passed through [credible].
         */
        fun fromLocalBests(bestAverageMs: Int?, bestSingleMs: Int?): PendingScores =
            PendingScores(credible(bestAverageMs), credible(bestSingleMs))

        private fun best(current: Int?, candidate: Int?): Int? = when {
            candidate == null -> current
            current == null -> candidate
            else -> minOf(current, candidate)
        }
    }
}
