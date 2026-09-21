package org.softosaurus.reactionspeed.game

/**
 * The speed tiers the game grades a time against, 1 (slowest) to [TIER_COUNT] (fastest).
 *
 * The thresholds are the ones the result screen has always used for its verdict text; they are
 * pulled out here because three other things now depend on the same ladder — the tier mascot, the
 * tier jingle and spoken line, and the colour of the "231 ms" that floats up from a hit — and a
 * second copy of the numbers would drift.
 *
 * Android-free so the mapping can be unit-tested directly.
 */
object Verdict {

    const val TIER_COUNT = 5

    /** Upper bounds (exclusive, ms) of tiers 5, 4, 3 and 2; anything slower is tier 1. */
    private val UPPER_BOUNDS_MS = intArrayOf(220, 250, 300, 350)

    /**
     * Tier of a time in milliseconds: 5 below 220 ms, 4 below 250, 3 below 300, 2 below 350,
     * 1 otherwise.
     *
     * Applied to the series score on the result screen and to a single reaction for the floating
     * hit text — deliberately the same ladder, so a tap that would earn "Lightning" as an average
     * is coloured as "Lightning" the moment it is made.
     */
    fun tierOf(timeMs: Int): Int {
        for ((index, bound) in UPPER_BOUNDS_MS.withIndex()) {
            if (timeMs < bound) return TIER_COUNT - index
        }
        return 1
    }
}
