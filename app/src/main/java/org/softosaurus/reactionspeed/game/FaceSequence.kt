package org.softosaurus.reactionspeed.game

import kotlin.random.Random

/**
 * Picks which monster face the next target wears.
 *
 * The only rule is that a face never appears twice in a row: ten targets drawn uniformly at random
 * would show a repeat in about a third of all series, and a repeat reads as "the target did not
 * change" — which is exactly the wrong impression in a game about noticing that something appeared.
 * Everything else stays uniform, so no face is rarer than another and none of them can become a
 * cue the player learns to anticipate.
 *
 * Android-free and seedable, hence unit-testable. Not thread-safe: the renderer drives it from the
 * render thread only.
 *
 * @param faceCount how many faces exist; 1 degenerates to always returning 0
 */
class FaceSequence(
    private val faceCount: Int,
    private val random: Random = Random.Default,
) {
    init {
        require(faceCount > 0) { "faceCount must be positive" }
    }

    /** The face returned last, or -1 before the first call. */
    var lastIndex: Int = -1
        private set

    /**
     * The next face index in `0 until faceCount`, never equal to the previous one.
     *
     * Implemented by drawing from the `faceCount - 1` faces that are *not* the previous one and
     * folding the result back into the full range, so it is a single draw with no retry loop and
     * the distribution over the allowed faces is exactly uniform.
     */
    fun next(): Int {
        if (faceCount == 1) return 0.also { lastIndex = it }
        val previous = lastIndex
        val index = if (previous < 0) {
            random.nextInt(faceCount)
        } else {
            val drawn = random.nextInt(faceCount - 1)
            if (drawn >= previous) drawn + 1 else drawn
        }
        lastIndex = index
        return index
    }

    /** Forgets the previous face, e.g. when a new series starts. */
    fun reset() {
        lastIndex = -1
    }
}
