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
}
