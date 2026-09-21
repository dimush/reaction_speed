package org.softosaurus.reactionspeed.game

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure (Android-free) layout and animation math of [GameView].
 *
 * Everything that decides *where* something is drawn or *how far* an animation has progressed lives
 * here so that it can be verified by plain JVM unit tests — the renderer itself cannot be seen from
 * a test, but its arithmetic can.
 *
 * All sizes are in px; `density` is `DisplayMetrics.density` (px per dp).
 */
object GameLayout {

    /** Duration of the target "pop" scale-in, ms. */
    const val POP_DURATION_MS = 80f

    /**
     * Scale of the target on its very first frame.
     *
     * Deliberately large: the target must be unmistakable the instant it is posted, otherwise the
     * measured reaction time would include the time the player spends waiting for the pop to
     * finish. The pop is a garnish, never a reveal.
     */
    const val POP_START_SCALE = 0.7f

    /**
     * Duration of the hit effect (expanding ring + floating "231 ms"), ms.
     *
     * Kept at [GameConfig.minDelayMs] so the effect has always faded out by the time the earliest
     * possible next target appears — it must never compete with the thing being measured.
     */
    const val HIT_EFFECT_DURATION_MS = 500f

    /** Duration of the "Too early!" toast, ms. */
    const val FALSE_START_DURATION_MS = 900f

    /** Outer radius of the hit ring at the end of the effect, as a factor of the target radius. */
    const val HIT_RING_MAX_FACTOR = 2.6f

    /** How far the hit text floats upwards over the whole effect, dp. */
    const val HIT_TEXT_RISE_DP = 34f

    // --- timing ---------------------------------------------------------------------------------

    /** Linear 0..1 progress of an animation of [durationMs] that started [elapsedMs] ago. */
    fun progress(elapsedMs: Long, durationMs: Float): Float =
        if (durationMs <= 0f) 1f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Standard decelerating ease. */
    fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    /** Scale of the target [elapsedMs] after it was shown; never below [POP_START_SCALE]. */
    fun popScale(elapsedMs: Long): Float =
        POP_START_SCALE + (1f - POP_START_SCALE) * easeOutCubic(progress(elapsedMs, POP_DURATION_MS))

    // --- target flourish and idle life ----------------------------------------------------------

    /**
     * Duration of the "out of the grass" overshoot that follows the pop-in, ms.
     *
     * It begins only once [popScale] has reached 1, so the reveal itself is untouched.
     */
    const val FLOURISH_DURATION_MS = 260f

    /** Peak of the overshoot, as a factor of the nominal radius. */
    const val FLOURISH_AMPLITUDE = 0.12f

    /** How long the idle wobble takes to reach full amplitude once the flourish is under way, ms. */
    const val IDLE_RAMP_MS = 320f

    /** Period of the breathing scale oscillation, ms. */
    const val IDLE_BREATH_PERIOD_MS = 2_100f

    /** Amplitude of the breathing, as a factor of the nominal radius. */
    const val IDLE_BREATH_AMPLITUDE = 0.025f

    /** Period of the idle tilt, ms — deliberately not a multiple of the breath, so it never loops. */
    const val IDLE_TILT_PERIOD_MS = 1_630f

    /** Amplitude of the idle tilt, degrees. */
    const val IDLE_TILT_DEGREES = 3.2f

    /**
     * Total scale of a visible target: the pop-in, the overshoot flourish and the idle breathing.
     *
     * **This is the fairness-critical curve.** Two invariants hold for every `elapsedMs >= 0` and
     * are asserted by `GameLayoutTest`:
     * * the value never drops below [POP_START_SCALE], so the face is at least 70 % of its final
     *   size on the very first frame and never shrinks below that afterwards;
     * * the first [POP_DURATION_MS] are *exactly* [popScale] — the flourish and the wobble both
     *   start at zero amplitude, so nothing about the reveal has changed.
     *
     * The garnish can only ever make the target bigger or wobble it a couple of percent once it is
     * unmistakably there. It can never delay or weaken the appearance, which is the moment being
     * measured.
     */
    fun targetScale(elapsedMs: Long): Float =
        popScale(elapsedMs) * (1f + flourish(elapsedMs)) * (1f + breath(elapsedMs))

    /** Idle tilt of a visible target in degrees; zero until the pop-in is over. */
    fun targetTiltDegrees(elapsedMs: Long): Float {
        val ramp = idleRamp(elapsedMs)
        if (ramp <= 0f) return 0f
        return IDLE_TILT_DEGREES * ramp * sin(TWO_PI * elapsedMs / IDLE_TILT_PERIOD_MS).toFloat()
    }

    /** Extra scale of the overshoot bump, 0 outside its window. */
    private fun flourish(elapsedMs: Long): Float {
        val start = POP_DURATION_MS.toLong()
        if (elapsedMs <= start) return 0f
        val p = progress(elapsedMs - start, FLOURISH_DURATION_MS)
        if (p >= 1f) return 0f
        // One clean half-sine: up to the peak and back to nothing, no discontinuity at either end.
        return FLOURISH_AMPLITUDE * sin(PI * p).toFloat()
    }

    /** Breathing component of the scale, 0 until the idle animation has ramped in. */
    private fun breath(elapsedMs: Long): Float {
        val ramp = idleRamp(elapsedMs)
        if (ramp <= 0f) return 0f
        return IDLE_BREATH_AMPLITUDE * ramp * sin(TWO_PI * elapsedMs / IDLE_BREATH_PERIOD_MS).toFloat()
    }

    /** 0..1 fade-in of the idle animation, which starts only after the pop-in has finished. */
    private fun idleRamp(elapsedMs: Long): Float {
        val start = POP_DURATION_MS.toLong()
        if (elapsedMs <= start) return 0f
        return progress(elapsedMs - start, IDLE_RAMP_MS)
    }

    // --- hit effect -------------------------------------------------------------------------------

    /** How far the face spins as it flies away, degrees over the whole effect. */
    const val HIT_SPIN_DEGREES = 420f

    /** Peak squash of the face at the moment of impact, as a factor. */
    const val HIT_SQUASH = 0.42f

    /** How far the struck face drifts upward over the effect, dp. */
    const val HIT_DRIFT_DP = 26f

    /** Horizontal scale of the struck face: squashed wide on impact, then shrinking away. */
    fun hitScaleX(progress: Float): Float = hitDecay(progress) * (1f + HIT_SQUASH * hitSquash(progress))

    /** Vertical scale of the struck face: flattened on impact, then shrinking away. */
    fun hitScaleY(progress: Float): Float = hitDecay(progress) * (1f - HIT_SQUASH * hitSquash(progress))

    /** Rotation of the struck face, degrees. */
    fun hitSpinDegrees(progress: Float): Float = HIT_SPIN_DEGREES * easeOutCubic(progress)

    /** Upwards drift (px, positive = up) of the struck face. */
    fun hitDrift(density: Float, progress: Float): Float =
        HIT_DRIFT_DP * density * easeOutCubic(progress)

    /** 1 → 0 shrink of the struck face. */
    private fun hitDecay(progress: Float): Float =
        (1f - easeOutCubic(progress.coerceIn(0f, 1f))).coerceAtLeast(0f)

    /** 1 → 0 squash envelope; front-loaded so the flattening is over in the first ~100 ms. */
    private fun hitSquash(progress: Float): Float {
        val inv = 1f - progress.coerceIn(0f, 1f)
        val sq = inv * inv
        return sq * sq
    }

    // --- particles ---------------------------------------------------------------------------------

    /** Lifetime of one grass-dust or star particle, ms. Inside [HIT_EFFECT_DURATION_MS] by design. */
    const val PARTICLE_LIFETIME_MS = 460f

    /** Downward acceleration applied to particles, in dp per second squared. */
    const val PARTICLE_GRAVITY_DP = 2_600f

    private const val TWO_PI = 2.0 * PI

    /** Radius of the expanding hit ring. */
    fun hitRingRadius(targetRadiusPx: Float, progress: Float): Float =
        targetRadiusPx * (1f + (HIT_RING_MAX_FACTOR - 1f) * easeOutCubic(progress))

    /** Upwards offset (px, positive = up) of the floating hit text. */
    fun hitTextRise(density: Float, progress: Float): Float =
        HIT_TEXT_RISE_DP * density * easeOutCubic(progress)

    /** Alpha 0..255 of something that fades out over the last [fadeFrom]..1 of its progress. */
    fun fadeOutAlpha(progress: Float, fadeFrom: Float = 0.5f): Int {
        val p = progress.coerceIn(0f, 1f)
        if (p <= fadeFrom) return 255
        val t = (p - fadeFrom) / (1f - fadeFrom)
        return ((1f - t) * 255f).toInt().coerceIn(0, 255)
    }

    // --- HUD ------------------------------------------------------------------------------------

    /** Padding around the HUD block, dp. */
    private const val HUD_PAD_DP = 8f

    /** Nominal dot radius, dp. */
    private const val HUD_DOT_RADIUS_DP = 3.5f

    /** Nominal gap between two dots, dp. */
    private const val HUD_DOT_GAP_DP = 7f

    /** Vertical distance from the progress text baseline to the row of dots, dp. */
    private const val HUD_DOT_OFFSET_DP = 11f

    /**
     * Where the HUD pieces go.
     *
     * @param centerX horizontal centre of the safe area (the progress text is centred on it)
     * @param textBaselineY baseline of the "3 / 10" text
     * @param dotCenterY centre line of the dot row
     * @param firstDotX centre of the leftmost dot
     * @param dotSpacing distance between two neighbouring dot centres (0 for a single dot)
     * @param dotRadius radius of one dot
     * @param height total height of the HUD, measured down from the top of the safe area
     */
    data class HudLayout(
        val centerX: Float,
        val textBaselineY: Float,
        val dotCenterY: Float,
        val firstDotX: Float,
        val dotSpacing: Float,
        val dotRadius: Float,
        val height: Float,
    )

    /**
     * Lays the HUD out at the top of the safe area.
     *
     * @param safeLeft left edge of the safe area (px)
     * @param safeTop top edge of the safe area (px)
     * @param safeRight right edge of the safe area (px)
     * @param density px per dp
     * @param dotCount number of attempts in the series
     * @param textAscentPx `-Paint.FontMetrics.ascent` of the progress text
     */
    fun hud(
        safeLeft: Float,
        safeTop: Float,
        safeRight: Float,
        density: Float,
        dotCount: Int,
        textAscentPx: Float,
    ): HudLayout {
        val pad = HUD_PAD_DP * density
        val textBaselineY = safeTop + pad + textAscentPx
        val dotOffset = HUD_DOT_OFFSET_DP * density
        val dotCenterY = textBaselineY + dotOffset

        val nominalRadius = HUD_DOT_RADIUS_DP * density
        val nominalSpacing = 2f * nominalRadius + HUD_DOT_GAP_DP * density
        val available = max(0f, (safeRight - safeLeft) - 2f * pad)
        val spacing = if (dotCount > 1) min(nominalSpacing, available / dotCount) else 0f
        // Shrink the dots too when the row had to be squeezed, so they never touch each other.
        val radius = if (dotCount > 1) min(nominalRadius, spacing * 0.38f) else nominalRadius

        val centerX = (safeLeft + safeRight) / 2f
        val firstDotX = centerX - (dotCount - 1) * spacing / 2f
        val height = pad + textAscentPx + dotOffset + radius + pad

        return HudLayout(
            centerX = centerX,
            textBaselineY = textBaselineY,
            dotCenterY = dotCenterY,
            firstDotX = firstDotX,
            dotSpacing = spacing,
            dotRadius = radius,
            height = height,
        )
    }
}
