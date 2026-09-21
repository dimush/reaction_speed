package org.softosaurus.reactionspeed.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawing math of [GameView] — the one part of the renderer that can be checked without a
 * screen.
 */
class GameLayoutTest {

    private val density = 3f // xxhdpi
    private val textAscent = 45f // ~15sp bold ascent at density 3

    // --- fairness --------------------------------------------------------------------------------

    @Test
    fun `the target is already unmistakable on its first frame`() {
        // The whole point of POP_START_SCALE: the reaction time must not include the pop.
        assertTrue(GameLayout.popScale(0L) >= 0.7f)
        assertEquals(GameLayout.POP_START_SCALE, GameLayout.popScale(0L), 1e-4f)
    }

    @Test
    fun `the pop finishes at full size and never overshoots`() {
        assertEquals(1f, GameLayout.popScale(80L), 1e-4f)
        assertEquals(1f, GameLayout.popScale(5_000L), 1e-4f)
        var previous = 0f
        for (ms in 0L..80L) {
            val scale = GameLayout.popScale(ms)
            assertTrue("pop must not shrink", scale >= previous)
            assertTrue("pop must not overshoot", scale <= 1f)
            previous = scale
        }
    }

    @Test
    fun `progress is clamped on both ends`() {
        assertEquals(0f, GameLayout.progress(-10L, 100f), 1e-4f)
        assertEquals(0.5f, GameLayout.progress(50L, 100f), 1e-4f)
        assertEquals(1f, GameLayout.progress(500L, 100f), 1e-4f)
        assertEquals(1f, GameLayout.progress(0L, 0f), 1e-4f)
    }

    @Test
    fun `fade out holds then reaches zero`() {
        assertEquals(255, GameLayout.fadeOutAlpha(0f, fadeFrom = 0.5f))
        assertEquals(255, GameLayout.fadeOutAlpha(0.5f, fadeFrom = 0.5f))
        assertEquals(0, GameLayout.fadeOutAlpha(1f, fadeFrom = 0.5f))
        assertTrue(GameLayout.fadeOutAlpha(0.75f, fadeFrom = 0.5f) in 100..160)
    }

    @Test
    fun `the hit ring grows outwards from the target`() {
        val r = 60f
        assertEquals(r, GameLayout.hitRingRadius(r, 0f), 1e-3f)
        assertEquals(r * GameLayout.HIT_RING_MAX_FACTOR, GameLayout.hitRingRadius(r, 1f), 1e-3f)
        assertTrue(GameLayout.hitRingRadius(r, 0.5f) > r)
    }

    @Test
    fun `the hit text floats upwards`() {
        assertEquals(0f, GameLayout.hitTextRise(density, 0f), 1e-3f)
        assertTrue(GameLayout.hitTextRise(density, 1f) > GameLayout.hitTextRise(density, 0.3f))
    }

    // --- HUD --------------------------------------------------------------------------------------

    @Test
    fun `hud dots are centred and stay inside the safe area`() {
        val hud = GameLayout.hud(0f, 96f, 1080f, density, dotCount = 10, textAscentPx = textAscent)

        assertEquals(540f, hud.centerX, 1e-3f)
        val lastDotX = hud.firstDotX + 9 * hud.dotSpacing
        assertEquals("the row must be symmetric", 540f - hud.firstDotX, lastDotX - 540f, 1e-3f)
        assertTrue("left edge inside", hud.firstDotX - hud.dotRadius >= 0f)
        assertTrue("right edge inside", lastDotX + hud.dotRadius <= 1080f)
        assertTrue("dots must not overlap", hud.dotSpacing > 2 * hud.dotRadius)
    }

    @Test
    fun `hud stays below the top inset and inside its own reserved height`() {
        val safeTop = 96f
        val hud = GameLayout.hud(0f, safeTop, 1080f, density, dotCount = 10, textAscentPx = textAscent)

        assertTrue("text must not climb into the status bar", hud.textBaselineY - textAscent >= safeTop)
        assertTrue(hud.dotCenterY > hud.textBaselineY)
        // The reserved height is what GameView adds to the engine's top inset, so everything the
        // HUD draws must fit inside it or a target could end up under the HUD.
        assertTrue(
            "dots must fit into the reserved strip",
            hud.dotCenterY + hud.dotRadius <= safeTop + hud.height,
        )
    }

    @Test
    fun `a narrow field squeezes the dots instead of overflowing`() {
        val hud = GameLayout.hud(0f, 0f, 160f, density, dotCount = 10, textAscentPx = textAscent)

        val lastDotX = hud.firstDotX + 9 * hud.dotSpacing
        assertTrue(hud.firstDotX - hud.dotRadius >= 0f)
        assertTrue(lastDotX + hud.dotRadius <= 160f)
        assertTrue(hud.dotRadius > 0f)
    }

    @Test
    fun `side insets shift the hud with them`() {
        val hud = GameLayout.hud(120f, 0f, 960f, density, dotCount = 10, textAscentPx = textAscent)

        assertEquals(540f, hud.centerX, 1e-3f)
        assertTrue(hud.firstDotX - hud.dotRadius >= 120f)
        assertTrue(hud.firstDotX + 9 * hud.dotSpacing + hud.dotRadius <= 960f)
    }

    @Test
    fun `a single dot is still laid out sanely`() {
        val hud = GameLayout.hud(0f, 0f, 1080f, density, dotCount = 1, textAscentPx = textAscent)

        assertEquals(540f, hud.firstDotX, 1e-3f)
        assertTrue(hud.dotRadius > 0f)
    }

    // --- the fairness invariants of the animated target -----------------------------------------

    @Test
    fun `the target is never smaller than the pop-in floor`() {
        // The whole promise of the flourish and the idle wobble: they may enlarge or tilt the face,
        // never shrink it below the size at which it must already be unmistakable.
        for (ms in 0L..10_000L) {
            val scale = GameLayout.targetScale(ms)
            assertTrue(
                "targetScale($ms) = $scale is below POP_START_SCALE",
                scale >= GameLayout.POP_START_SCALE - 1e-4f,
            )
        }
    }

    @Test
    fun `the first frame shows at least seventy percent`() {
        assertEquals(GameLayout.POP_START_SCALE, GameLayout.targetScale(0L), 1e-4f)
        assertTrue(GameLayout.POP_START_SCALE >= 0.7f)
    }

    @Test
    fun `the pop-in itself is untouched by the garnish`() {
        // Inside the pop window the decorated curve must be exactly the old one: the flourish and
        // the wobble both start at zero amplitude when the pop finishes.
        var ms = 0L
        while (ms <= GameLayout.POP_DURATION_MS.toLong()) {
            assertEquals(GameLayout.popScale(ms), GameLayout.targetScale(ms), 1e-4f)
            ms += 1L
        }
    }

    @Test
    fun `the idle wobble stays subtle and the tilt is silent during the pop`() {
        var ms = 0L
        while (ms <= GameLayout.POP_DURATION_MS.toLong()) {
            assertEquals(0f, GameLayout.targetTiltDegrees(ms), 1e-4f)
            ms += 1L
        }
        for (later in 100L..8_000L step 7L) {
            val tilt = GameLayout.targetTiltDegrees(later)
            assertTrue("tilt $tilt too large", kotlin.math.abs(tilt) <= GameLayout.IDLE_TILT_DEGREES + 1e-4f)
            val scale = GameLayout.targetScale(later)
            // Never more than the flourish peak plus the breath above the nominal size either.
            assertTrue("scale $scale too large", scale <= 1.2f)
        }
    }

    @Test
    fun `the struck face shrinks to nothing inside the hit effect`() {
        assertTrue(GameLayout.hitScaleX(0f) > GameLayout.hitScaleY(0f)) // squashed wide on impact
        assertEquals(0f, GameLayout.hitScaleX(1f), 1e-4f)
        assertEquals(0f, GameLayout.hitScaleY(1f), 1e-4f)
        assertEquals(0f, GameLayout.hitSpinDegrees(0f), 1e-4f)
        assertEquals(GameLayout.HIT_SPIN_DEGREES, GameLayout.hitSpinDegrees(1f), 1e-3f)
    }

    @Test
    fun `particles die before the earliest possible next target`() {
        // A leftover spark must never be on screen when the next stimulus appears.
        assertTrue(GameLayout.PARTICLE_LIFETIME_MS <= GameConfig().minDelayMs.toFloat())
        assertTrue(GameLayout.HIT_EFFECT_DURATION_MS <= GameConfig().minDelayMs.toFloat())
    }
}
