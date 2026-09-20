package org.softosaurus.reactionspeed.game

/**
 * Pure (Android-free) value types of the reaction-speed game core.
 *
 * All timestamps in this package are plain `Long` milliseconds taken from a **monotonic** clock.
 * On Android that clock must be `android.os.SystemClock.uptimeMillis()`, because
 * `MotionEvent.eventTime` is expressed in exactly that base. Mixing in `System.nanoTime()` or
 * `System.currentTimeMillis()` produces nonsense reaction times.
 */

/** Safe-area insets (px) that the target must not overlap, e.g. system bars or an ad banner. */
data class Insets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        val NONE = Insets()
    }
}

/**
 * The playfield the engine spawns targets in.
 *
 * @param width field width in px
 * @param height field height in px
 * @param insets area at the edges that targets must stay clear of
 */
data class Field(
    val width: Float,
    val height: Float,
    val insets: Insets = Insets.NONE,
)

/** A red target: centre and radius in px. */
data class Target(
    val x: Float,
    val y: Float,
    val radiusPx: Float,
)

/**
 * Tunables of the game. Defaults reproduce the legacy 3.x behaviour.
 *
 * Legacy geometry recap (`MySurfaceView`): the canvas was rotated, `h = canvasHeight / 2`, and
 * `target_r = h / 8`, i.e. one sixteenth of the shorter screen dimension on a portrait phone —
 * hence [targetRadiusFactor] is applied to `min(width, height)`.
 *
 * @param attemptsPerSeries number of targets in one series (legacy: 10)
 * @param minDelayMs lower bound of the random hide delay (legacy: 500)
 * @param maxDelayMs upper bound, exclusive (legacy: 500 + random * 3000)
 * @param targetRadiusFactor target radius as a fraction of `min(width, height)`
 * @param hitRadiusFactor hit radius = target radius * this. Legacy used a *square* test with
 *   half-side `target_r + finger_r == 2 * target_r`; a circle of radius `2 * target_r` is
 *   inscribed in that square (~79% of its area) — comparable forgiveness, no corner bias.
 * @param exclusionFactor a new target must be farther than `exclusionFactor * target radius` from
 *   the last touch point, measured as a **Chebyshev** distance `max(|dx|, |dy|)`. Legacy accepted a
 *   candidate when `|dx| > 2*(target_r+finger_r) || |dy| > 2*(target_r+finger_r)`, which is exactly
 *   `max(|dx|, |dy|) > 4 * target_r`. Do not replace this with a Euclidean check.
 * @param maxSpawnAttempts safety bound for the rejection sampling loop; after that the farthest
 *   candidate seen is used (legacy retried forever, which can hang on a very short field).
 * @param minPlausibleMs anti-cheat threshold, see [SeriesResult.isPlausible]
 */
data class GameConfig(
    val attemptsPerSeries: Int = 10,
    val minDelayMs: Long = 500L,
    val maxDelayMs: Long = 3500L,
    val targetRadiusFactor: Float = 1f / 16f,
    val hitRadiusFactor: Float = 2f,
    val exclusionFactor: Float = 4f,
    val maxSpawnAttempts: Int = 50,
    val minPlausibleMs: Int = 100,
) {
    init {
        require(attemptsPerSeries > 0) { "attemptsPerSeries must be positive" }
        require(maxDelayMs > minDelayMs) { "maxDelayMs must exceed minDelayMs" }
        require(maxSpawnAttempts > 0) { "maxSpawnAttempts must be positive" }
    }
}

/** Explicit phases of a series. */
sealed interface GamePhase {

    /** Nothing is running; [GameEngine.start] is the only meaningful input. */
    data object Idle : GamePhase

    /**
     * The target is hidden and will appear after [delayMs] have elapsed since [startedAtMs].
     * A touch in this phase is a false start and re-randomises the delay.
     */
    data class Waiting(
        val attemptIndex: Int,
        val startedAtMs: Long,
        val delayMs: Long,
    ) : GamePhase

    /**
     * The target is on screen. [presentedAtMs] is the baseline for the reaction time and may be
     * refined by [GameEngine.markTargetPresented] once the frame is actually on screen.
     */
    data class TargetVisible(
        val attemptIndex: Int,
        val target: Target,
        val presentedAtMs: Long,
    ) : GamePhase

    /** The series is over; [result] is final. */
    data class Finished(val result: SeriesResult) : GamePhase
}

/** One measured reaction, with the outlier verdict of [SeriesScorer]. */
data class Attempt(
    val reactionTimeMs: Int,
    val accepted: Boolean,
)

/**
 * Outcome of a full series.
 *
 * @param attempts the ten measurements in chronological order
 * @param rawMean arithmetic mean of all attempts, ms
 * @param stdDev population standard deviation over all attempts, ms
 * @param filteredMean the official score: mean of the accepted attempts, truncated to Int ms
 * @param bestSingle fastest single attempt, ms
 * @param falseStarts touches made while the target was hidden
 * @param misses touches that were made while the target was visible but landed off-target
 * @param minPlausibleMs anti-cheat threshold used by [isPlausible]
 */
data class SeriesResult(
    val attempts: List<Attempt>,
    val rawMean: Double,
    val stdDev: Double,
    val filteredMean: Int,
    val bestSingle: Int,
    val falseStarts: Int,
    val misses: Int,
    val minPlausibleMs: Int = 100,
) {
    /** True when the outlier filter discarded nothing (legacy "no outliers" achievement). */
    val allAccepted: Boolean get() = attempts.all { it.accepted }

    /**
     * Anti-cheat gate: a single reaction under [minPlausibleMs] (100 ms by default) is
     * physiologically impossible, so neither it nor the resulting mean may be submitted to a
     * leaderboard. Implausible results are still stored locally — this only gates submission.
     */
    val isPlausible: Boolean
        get() = filteredMean >= minPlausibleMs && attempts.none { it.reactionTimeMs < minPlausibleMs }
}

/** Things the renderer may want to react to with sound or vibration. */
sealed interface GameEvent {

    /** The target just became visible; play the "metal" cue and vibrate. */
    data class TargetShown(
        val attemptIndex: Int,
        val target: Target,
        val shownAtMs: Long,
    ) : GameEvent

    /** The target was tapped. */
    data class Hit(
        val attemptIndex: Int,
        val reactionTimeMs: Int,
    ) : GameEvent

    /** A tap while the target was visible but off-target. */
    data class Miss(
        val attemptIndex: Int,
        val totalMisses: Int,
    ) : GameEvent

    /** A tap while the target was hidden; the pending delay has been re-randomised. */
    data class FalseStart(
        val attemptIndex: Int,
        val totalFalseStarts: Int,
    ) : GameEvent

    /** The last attempt was scored. */
    data class SeriesFinished(val result: SeriesResult) : GameEvent
}
