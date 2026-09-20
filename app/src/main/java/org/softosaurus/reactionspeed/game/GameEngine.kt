package org.softosaurus.reactionspeed.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Deterministic, Android-free core of the reaction-speed game.
 *
 * The engine never reads a clock and never touches `android.*`: every timestamp arrives from the
 * caller, and randomness comes from the injected [random]. That makes a whole series reproducible
 * in a plain JVM unit test.
 *
 * ### Clock contract (important for the renderer)
 * `nowMs`, `eventTimeMs` and [markTargetPresented] must all come from the **same monotonic base**,
 * which on Android is `SystemClock.uptimeMillis()` — the base of `MotionEvent.eventTime`. Pass
 * `event.eventTime` (not `uptimeMillis()` read in the handler) so that input-queue latency is not
 * charged to the player.
 *
 * ### Typical usage
 * ```
 * engine.setField(width, height, insets)
 * engine.start(SystemClock.uptimeMillis())
 * // render loop:
 * engine.tick(SystemClock.uptimeMillis())     // may emit TargetShown
 * // after the frame carrying the target is actually on screen:
 * engine.markTargetPresented(attemptIndex, presentationTimeMs)
 * // touch handler:
 * engine.onTouch(event.x, event.y, event.eventTime)
 * ```
 *
 * Every input returns the list of [GameEvent]s it produced; the same events are also delivered to
 * [onEvent] if a listener is installed. The class is not thread-safe: drive it from one thread
 * (typically the render thread), forwarding touches to it.
 *
 * @param random source of delays and target positions
 * @param config tunables; defaults reproduce legacy 3.x behaviour
 */
class GameEngine(
    private val random: Random = Random.Default,
    val config: GameConfig = GameConfig(),
) {

    /** Optional push-style listener; receives exactly what the input methods return. */
    var onEvent: ((GameEvent) -> Unit)? = null

    /** Current phase of the series. */
    var phase: GamePhase = GamePhase.Idle
        private set

    /** The playfield, or `null` until [setField] has been called. */
    var playfield: Field? = null
        private set

    private val reactionTimes = mutableListOf<Int>()
    private var falseStartCount = 0
    private var missCount = 0
    private var lastTouchX: Float? = null
    private var lastTouchY: Float? = null

    /** Number of taps made while the target was hidden, in the current series. */
    val falseStarts: Int get() = falseStartCount

    /** Number of off-target taps made while the target was visible, in the current series. */
    val misses: Int get() = missCount

    /** Reaction times measured so far in the current series, ms. */
    val measuredReactionTimes: List<Int> get() = reactionTimes.toList()

    /** Index of the attempt in flight (0-based), or the attempt count when finished. */
    val attemptIndex: Int
        get() = when (val p = phase) {
            is GamePhase.Waiting -> p.attemptIndex
            is GamePhase.TargetVisible -> p.attemptIndex
            is GamePhase.Finished -> p.result.attempts.size
            GamePhase.Idle -> 0
        }

    /** The target currently on screen, or `null`. */
    val visibleTarget: Target?
        get() = (phase as? GamePhase.TargetVisible)?.target

    /** Target radius in px for the current playfield, or `null` if no playfield is set. */
    val targetRadiusPx: Float? get() = playfield?.let { targetRadius(it) }

    /**
     * Sets or updates the playfield.
     *
     * A target already on screen is **clamped into the new safe area** (and its radius recomputed
     * for the new field): a resize mid-series — split-screen, an unfold, the ad band appearing —
     * would otherwise leave the target at coordinates that are now off-surface or behind a system
     * bar, and the series could never be finished because the target could never be tapped.
     *
     * A hidden target needs no such care: [GamePhase.Waiting] carries no geometry and
     * [spawnTarget] reads the playfield fresh when the delay expires.
     */
    fun setField(width: Float, height: Float, insets: Insets = Insets.NONE) {
        require(width > 0f && height > 0f) { "playfield must have a positive size" }
        val field = Field(width, height, insets)
        playfield = field
        val visible = phase as? GamePhase.TargetVisible ?: return
        phase = visible.copy(target = clampIntoField(visible.target, field))
    }

    /**
     * Starts (or restarts) a series: counters are reset and the first wait begins at [nowMs].
     * Requires a playfield to have been set.
     */
    fun start(nowMs: Long): List<GameEvent> {
        checkNotNull(playfield) { "setField() must be called before start()" }
        reactionTimes.clear()
        falseStartCount = 0
        missCount = 0
        lastTouchX = null
        lastTouchY = null
        phase = beginWait(attemptIndex = 0, nowMs = nowMs)
        return emit(emptyList())
    }

    /** Resets the engine to [GamePhase.Idle] without producing a result. */
    fun reset() {
        reactionTimes.clear()
        falseStartCount = 0
        missCount = 0
        lastTouchX = null
        lastTouchY = null
        phase = GamePhase.Idle
    }

    /**
     * Advances time. The only thing that can happen here is the hidden target becoming visible,
     * which yields a [GameEvent.TargetShown].
     */
    fun tick(nowMs: Long): List<GameEvent> {
        val waiting = phase as? GamePhase.Waiting ?: return emptyList()
        if (nowMs - waiting.startedAtMs < waiting.delayMs) return emptyList()

        val target = spawnTarget()
        phase = GamePhase.TargetVisible(waiting.attemptIndex, target, nowMs)
        return emit(listOf(GameEvent.TargetShown(waiting.attemptIndex, target, nowMs)))
    }

    /**
     * Refines the reaction-time baseline for the attempt [attemptIndex] once the frame that
     * actually contains the target has been presented (e.g. from a `Choreographer` frame callback
     * or `SurfaceHolder` post time). Without this call the baseline stays at the `nowMs` of the
     * [tick] that showed the target, which is typically one frame optimistic.
     *
     * No-op (returns `false`) if the target for that attempt is no longer visible — a late callback
     * after a hit or after the series ended cannot corrupt the measurement.
     */
    fun markTargetPresented(attemptIndex: Int, presentedAtMs: Long): Boolean {
        val visible = phase as? GamePhase.TargetVisible ?: return false
        if (visible.attemptIndex != attemptIndex) return false
        phase = visible.copy(presentedAtMs = presentedAtMs)
        return true
    }

    /**
     * Feeds a touch-down at ([x], [y]) that happened at [eventTimeMs].
     *
     * * [GamePhase.Waiting] — false start: the counter grows and the pending delay is re-randomised
     *   from [eventTimeMs], so the player cannot spam the screen into a fast time.
     * * [GamePhase.TargetVisible] — a hit within the forgiving radius records the reaction time and
     *   moves on; anything else is counted as a miss and otherwise ignored. A touch whose
     *   [eventTimeMs] predates the target's presentation time is a false start, not a 0 ms hit.
     * * [GamePhase.Idle] / [GamePhase.Finished] — ignored entirely (no miss, no false start).
     */
    fun onTouch(x: Float, y: Float, eventTimeMs: Long): List<GameEvent> {
        // Legacy parity: the last touch point is remembered for every touch, hit or not, and is
        // what the next target must not spawn under.
        lastTouchX = x
        lastTouchY = y

        return when (val p = phase) {
            GamePhase.Idle, is GamePhase.Finished -> emptyList()

            is GamePhase.Waiting -> {
                falseStartCount++
                phase = beginWait(p.attemptIndex, eventTimeMs)
                emit(listOf(GameEvent.FalseStart(p.attemptIndex, falseStartCount)))
            }

            is GamePhase.TargetVisible -> {
                if (eventTimeMs < p.presentedAtMs) {
                    // The tap physically happened before the frame carrying the target was posted
                    // (it was sitting in the input queue while the renderer drew, or
                    // markTargetPresented moved the baseline forward afterwards). Scoring it would
                    // hand out a ~0 ms "reaction"; it is a false start, exactly as it would have
                    // been one millisecond earlier.
                    falseStartCount++
                    phase = beginWait(p.attemptIndex, eventTimeMs)
                    return emit(listOf(GameEvent.FalseStart(p.attemptIndex, falseStartCount)))
                }
                if (!isHit(p.target, x, y)) {
                    missCount++
                    return emit(listOf(GameEvent.Miss(p.attemptIndex, missCount)))
                }
                val reactionTimeMs = (eventTimeMs - p.presentedAtMs).coerceAtLeast(0L).toInt()
                reactionTimes += reactionTimeMs
                val events = mutableListOf<GameEvent>(
                    GameEvent.Hit(p.attemptIndex, reactionTimeMs),
                )
                if (reactionTimes.size < config.attemptsPerSeries) {
                    phase = beginWait(p.attemptIndex + 1, eventTimeMs)
                } else {
                    val result = SeriesScorer.score(
                        reactionTimesMs = reactionTimes.toList(),
                        falseStarts = falseStartCount,
                        misses = missCount,
                        minPlausibleMs = config.minPlausibleMs,
                    )
                    phase = GamePhase.Finished(result)
                    events += GameEvent.SeriesFinished(result)
                }
                emit(events)
            }
        }
    }

    // --- internals ----------------------------------------------------------------------------

    private fun beginWait(attemptIndex: Int, nowMs: Long): GamePhase.Waiting {
        val span = config.maxDelayMs - config.minDelayMs
        val delay = config.minDelayMs + (random.nextDouble() * span).toLong()
        return GamePhase.Waiting(attemptIndex, nowMs, delay)
    }

    private fun targetRadius(f: Field): Float =
        min(f.width, f.height) * config.targetRadiusFactor

    /** Circular hit test with the legacy-comparable forgiving radius. */
    private fun isHit(target: Target, x: Float, y: Float): Boolean {
        val r = target.radiusPx * config.hitRadiusFactor
        val dx = x - target.x
        val dy = y - target.y
        return dx * dx + dy * dy <= r * r
    }

    /**
     * Rejection-samples a position inside the safe rectangle that is farther than
     * `exclusionFactor * radius` (Chebyshev) from the last touch point. Bounded by
     * [GameConfig.maxSpawnAttempts]; if no candidate qualifies, the farthest one is used.
     */
    private fun spawnTarget(): Target {
        val f = checkNotNull(playfield) { "no playfield" }
        val r = targetRadius(f)
        val bounds = safeBounds(f, r) ?: return degenerateCentre(f, r)
        val (minX, minY, maxX, maxY) = bounds

        val exclusion = r * config.exclusionFactor
        var bestX = 0f
        var bestY = 0f
        var bestSeparation = -1f
        repeat(config.maxSpawnAttempts) {
            val x = minX + (random.nextDouble() * (maxX - minX)).toFloat()
            val y = minY + (random.nextDouble() * (maxY - minY)).toFloat()
            val separation = separationFromLastTouch(x, y)
            if (separation > exclusion) return Target(x, y, r)
            if (separation > bestSeparation) {
                bestSeparation = separation
                bestX = x
                bestY = y
            }
        }
        return Target(bestX, bestY, r)
    }

    /**
     * The rectangle of legal target *centres* for [f] and radius [r], or `null` when the insets
     * eat the whole field.
     */
    private fun safeBounds(f: Field, r: Float): Bounds? {
        val minX = f.insets.left + r
        val maxX = f.width - f.insets.right - r
        val minY = f.insets.top + r
        val maxY = f.height - f.insets.bottom - r
        return if (maxX <= minX || maxY <= minY) null else Bounds(minX, minY, maxX, maxY)
    }

    /** Degenerate playfield (insets eat everything): fall back to the centre of the safe area. */
    private fun degenerateCentre(f: Field, r: Float) = Target(
        x = (f.insets.left + f.width - f.insets.right) / 2f,
        y = (f.insets.top + f.height - f.insets.bottom) / 2f,
        radiusPx = r,
    )

    /** Moves [target] into the safe area of [f] and gives it the radius that field implies. */
    private fun clampIntoField(target: Target, f: Field): Target {
        val r = targetRadius(f)
        val b = safeBounds(f, r) ?: return degenerateCentre(f, r)
        return Target(
            x = target.x.coerceIn(b.minX, b.maxX),
            y = target.y.coerceIn(b.minY, b.maxY),
            radiusPx = r,
        )
    }

    private data class Bounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    )

    /** Chebyshev distance to the last touch point, or +inf when there was none. */
    private fun separationFromLastTouch(x: Float, y: Float): Float {
        val tx = lastTouchX ?: return Float.MAX_VALUE
        val ty = lastTouchY ?: return Float.MAX_VALUE
        return max(abs(x - tx), abs(y - ty))
    }

    private fun emit(events: List<GameEvent>): List<GameEvent> {
        val listener = onEvent
        if (listener != null) events.forEach(listener)
        return events
    }
}
