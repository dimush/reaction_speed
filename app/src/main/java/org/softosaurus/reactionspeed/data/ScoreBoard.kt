package org.softosaurus.reactionspeed.data

/**
 * Pure, Android-free snapshot of everything the app persists about results.
 *
 * Keeping the insertion/sorting rules here (instead of inside the SharedPreferences repository)
 * makes them unit-testable without Robolectric.
 *
 * @param top10 the ten best series scores, ascending (fastest first)
 * @param history every series score in chronological order, oldest first
 * @param bestSingleMs fastest single reaction ever recorded, or `null` if unknown.
 *   Legacy 3.x stored only series means, so this stays `null` after migration until the player
 *   finishes a series in 4.0.
 * @param seriesCount lifetime number of finished series; used for incremental achievements and
 *   deliberately **not** reset by [cleared]
 */
data class ScoreBoard(
    val top10: List<Int> = emptyList(),
    val history: List<Int> = emptyList(),
    val bestSingleMs: Int? = null,
    val seriesCount: Int = 0,
) {

    /**
     * Adds one finished series.
     *
     * A degenerate series (every attempt measured as 0 ms, which a pathological clock or a
     * synthetic event stream can produce) must not take the app down on the way to the result
     * screen, so the score is clamped to 1 ms rather than rejected.
     *
     * @param scoreMs the official score of the series (filtered mean), clamped to at least 1
     * @param bestSingleMs fastest single attempt of that series, or `null` if not available
     */
    fun withSeries(scoreMs: Int, bestSingleMs: Int? = null): ScoreBoard {
        val score = scoreMs.coerceAtLeast(1)
        val newHistory = (history + score).takeLast(MAX_HISTORY)
        val newBestSingle = listOfNotNull(this.bestSingleMs, bestSingleMs?.takeIf { it > 0 }).minOrNull()
        return copy(
            top10 = topOf(top10 + score),
            history = newHistory,
            bestSingleMs = newBestSingle,
            seriesCount = seriesCount + 1,
        )
    }

    /**
     * Clears the visible statistics (top-10, history and the best single reaction), the way the
     * legacy "clean history" menu item did. [seriesCount] survives, because it feeds lifetime
     * achievements.
     */
    fun cleared(): ScoreBoard = copy(top10 = emptyList(), history = emptyList(), bestSingleMs = null)

    companion object {
        /** Size of the leaderboard kept on the device. */
        const val TOP_SIZE = 10

        /** Upper bound on the stored history; older entries are dropped. */
        const val MAX_HISTORY = 2000

        /**
         * The legacy insertion loop (shift-down into a fixed array of ten, inserting before the
         * first slot that is `>=` the new value or empty) is multiset-equivalent to simply sorting
         * and taking the ten smallest — including ties and the "board full and new value worse"
         * case.
         */
        fun topOf(values: List<Int>): List<Int> =
            values.filter { it > 0 }.sorted().take(TOP_SIZE)
    }
}

/**
 * Serialisation of an int list as one preference string. Pure, so it is covered by plain JVM tests.
 * Decoding silently drops anything that is not a positive integer, which makes a corrupt or
 * partially written value degrade instead of crashing.
 */
object IntListCodec {

    fun encode(values: List<Int>): String = values.joinToString(",")

    fun decode(raw: String?): List<Int> =
        raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 } ?: emptyList()
}

/** Sound and haptics preferences (legacy `use_*` keys). */
data class GameSettings(
    val targetSounds: Boolean = true,
    val stoneSounds: Boolean = true,
    val vibration: Boolean = true,
)
