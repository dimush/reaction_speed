package org.softosaurus.reactionspeed.game

import kotlin.math.max
import kotlin.math.min

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
