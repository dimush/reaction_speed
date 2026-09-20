package org.softosaurus.reactionspeed.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The race [GameView] can actually produce: a touch is queued on the main thread while the render
 * thread is drawing, and `markTargetPresented` then moves the baseline forward past that touch.
 */
class GameEnginePresentationTest {

    private fun engine() = GameEngine(Random(7)).apply { setField(1080f, 1920f) }

    private fun GameEngine.showTarget(fromMs: Long): Long {
        val waiting = phase as GamePhase.Waiting
        val showAt = waiting.startedAtMs + waiting.delayMs
        tick(showAt)
        return showAt
    }

    @Test
    fun `a tap that predates the presented frame is a false start, not a zero ms hit`() {
        val engine = engine()
        engine.start(0L)
        val shownAt = engine.showTarget(0L)
        val target = engine.visibleTarget!!
        // The frame reached the display one vsync after the tick that spawned the target.
        engine.markTargetPresented(0, shownAt + 16)

        val events = engine.onTouch(target.x, target.y, shownAt + 4)

        assertTrue("must not be scored as a hit", events.first() is GameEvent.FalseStart)
        assertEquals(1, engine.falseStarts)
        assertTrue(engine.measuredReactionTimes.isEmpty())
        assertTrue("the attempt must be replayed", engine.phase is GamePhase.Waiting)
        assertEquals(0, (engine.phase as GamePhase.Waiting).attemptIndex)
    }

    @Test
    fun `a tap exactly at the presentation time still counts as a zero ms hit`() {
        val engine = engine()
        engine.start(0L)
        val shownAt = engine.showTarget(0L)
        val target = engine.visibleTarget!!
        engine.markTargetPresented(0, shownAt + 16)

        val events = engine.onTouch(target.x, target.y, shownAt + 16)

        assertEquals(0, (events.first() as GameEvent.Hit).reactionTimeMs)
    }

    @Test
    fun `an off-target tap that predates the frame is a false start too`() {
        val engine = engine()
        engine.start(0L)
        val shownAt = engine.showTarget(0L)
        engine.markTargetPresented(0, shownAt + 16)

        val events = engine.onTouch(1f, 1f, shownAt)

        assertTrue(events.first() is GameEvent.FalseStart)
        assertEquals(0, engine.misses)
    }
}
