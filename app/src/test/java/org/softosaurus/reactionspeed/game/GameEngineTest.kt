package org.softosaurus.reactionspeed.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    private val width = 1080f
    private val height = 1920f

    private fun engine(
        seed: Int = 42,
        config: GameConfig = GameConfig(),
        insets: Insets = Insets.NONE,
    ) = GameEngine(Random(seed), config).apply { setField(width, height, insets) }

    /** Advances the clock to the moment the target appears and returns that moment. */
    private fun GameEngine.advanceToTarget(fromMs: Long): Long {
        val waiting = phase as GamePhase.Waiting
        val showAt = waiting.startedAtMs + waiting.delayMs
        // One tick just before the deadline must do nothing.
        assertTrue(tick(showAt - 1).isEmpty())
        val events = tick(showAt)
        assertTrue(events.first() is GameEvent.TargetShown)
        return showAt
    }

    @Test
    fun `full series produces a scored result`() {
        val engine = engine()
        val collected = mutableListOf<GameEvent>()
        engine.onEvent = { collected += it }

        engine.start(0L)
        var now = 0L
        val reactionTimes = mutableListOf<Int>()
        repeat(10) { i ->
            now = engine.advanceToTarget(now)
            val target = engine.visibleTarget!!
            val rt = 240 + i * 7 // deterministic, distinct reaction times
            now += rt
            engine.onTouch(target.x, target.y, now)
            reactionTimes += rt
        }

        val finished = engine.phase as GamePhase.Finished
        assertEquals(SeriesScorer.score(reactionTimes), finished.result)
        assertEquals(10, finished.result.attempts.size)
        assertEquals(0, finished.result.falseStarts)
        assertEquals(0, finished.result.misses)
        assertEquals(10, collected.filterIsInstance<GameEvent.TargetShown>().size)
        assertEquals(10, collected.filterIsInstance<GameEvent.Hit>().size)
        assertEquals(1, collected.filterIsInstance<GameEvent.SeriesFinished>().size)
    }

    @Test
    fun `delays stay inside the legacy range`() {
        val engine = engine(seed = 7)
        engine.start(0L)
        var now = 0L
        repeat(10) {
            val waiting = engine.phase as GamePhase.Waiting
            assertTrue(
                "delay ${waiting.delayMs} out of range",
                waiting.delayMs >= 500L && waiting.delayMs < 3500L,
            )
            now = engine.advanceToTarget(now)
            val target = engine.visibleTarget!!
            now += 300
            engine.onTouch(target.x, target.y, now)
        }
    }

    @Test
    fun `reaction time is measured from presentation to event time`() {
        val engine = engine()
        engine.start(1_000L)
        val shownAt = engine.advanceToTarget(1_000L)
        val target = engine.visibleTarget!!

        // Several ticks after the target is up must not change anything.
        engine.tick(shownAt + 100)
        val events = engine.onTouch(target.x, target.y, shownAt + 217)

        assertEquals(217, (events.first() as GameEvent.Hit).reactionTimeMs)
    }

    @Test
    fun `markTargetPresented shifts the baseline and is guarded`() {
        val engine = engine()
        engine.start(0L)
        val shownAt = engine.advanceToTarget(0L)
        val target = engine.visibleTarget!!

        assertFalse("wrong attempt index must be ignored", engine.markTargetPresented(5, shownAt + 16))
        assertTrue(engine.markTargetPresented(0, shownAt + 16))

        val events = engine.onTouch(target.x, target.y, shownAt + 216)
        assertEquals(200, (events.first() as GameEvent.Hit).reactionTimeMs)

        // Late callback after the hit must not corrupt the next attempt.
        assertFalse(engine.markTargetPresented(0, shownAt + 500))
    }

    @Test
    fun `touch while waiting is a false start and re-randomises the delay`() {
        val engine = engine()
        engine.start(0L)
        val original = engine.phase as GamePhase.Waiting

        val events = engine.onTouch(10f, 10f, 100L)

        assertTrue(events.single() is GameEvent.FalseStart)
        assertEquals(1, engine.falseStarts)
        val restarted = engine.phase as GamePhase.Waiting
        assertEquals(0, restarted.attemptIndex)
        assertEquals(100L, restarted.startedAtMs)
        // The original deadline no longer shows a target.
        assertTrue(engine.tick(original.startedAtMs + original.delayMs).isEmpty())
        assertTrue(engine.phase is GamePhase.Waiting)
    }

    @Test
    fun `false starts are counted in the result`() {
        val engine = engine()
        engine.start(0L)
        var now = 0L
        repeat(10) {
            engine.onTouch(5f, 5f, now + 10)
            now += 10
            now = engine.advanceToTarget(now)
            val target = engine.visibleTarget!!
            now += 250
            engine.onTouch(target.x, target.y, now)
        }
        val result = (engine.phase as GamePhase.Finished).result
        assertEquals(10, result.falseStarts)
    }

    @Test
    fun `off-target touch is a miss and does not end the attempt`() {
        val engine = engine()
        engine.start(0L)
        var now = engine.advanceToTarget(0L)
        val target = engine.visibleTarget!!
        val far = if (target.x > width / 2) 1f else width - 1f

        val events = engine.onTouch(far, target.y, now + 100)

        assertTrue(events.single() is GameEvent.Miss)
        assertEquals(1, engine.misses)
        assertTrue(engine.phase is GamePhase.TargetVisible)

        now += 300
        engine.onTouch(target.x, target.y, now)
        assertEquals(1, engine.measuredReactionTimes.size)
    }

    @Test
    fun `hit radius is forgiving up to twice the target radius`() {
        val engine = engine()
        engine.start(0L)
        val now = engine.advanceToTarget(0L)
        val target = engine.visibleTarget!!
        val r = target.radiusPx

        assertTrue(engine.onTouch(target.x, target.y + 1.9f * r, now + 100).first() is GameEvent.Hit)

        engine.reset()
        engine.start(0L)
        val now2 = engine.advanceToTarget(0L)
        val target2 = engine.visibleTarget!!
        assertTrue(
            engine.onTouch(target2.x, target2.y + 2.5f * target2.radiusPx, now2 + 100).first()
                is GameEvent.Miss,
        )
    }

    @Test
    fun `targets stay inside the field with margins and away from the last touch`() {
        val insets = Insets(left = 20f, top = 60f, right = 20f, bottom = 140f)
        repeat(20) { seed ->
            val engine = engine(seed = seed, insets = insets)
            engine.start(0L)
            var now = 0L
            var lastTouchX: Float? = null
            var lastTouchY: Float? = null
            repeat(10) {
                now = engine.advanceToTarget(now)
                val t = engine.visibleTarget!!
                assertTrue("x=${t.x}", t.x - t.radiusPx >= insets.left - 1e-3f)
                assertTrue("x=${t.x}", t.x + t.radiusPx <= width - insets.right + 1e-3f)
                assertTrue("y=${t.y}", t.y - t.radiusPx >= insets.top - 1e-3f)
                assertTrue("y=${t.y}", t.y + t.radiusPx <= height - insets.bottom + 1e-3f)
                val px = lastTouchX
                val py = lastTouchY
                if (px != null && py != null) {
                    val separation = max(abs(t.x - px), abs(t.y - py))
                    assertTrue(
                        "target spawned too close to the previous touch: $separation",
                        separation > 4f * t.radiusPx,
                    )
                }
                now += 260
                engine.onTouch(t.x, t.y, now)
                lastTouchX = t.x
                lastTouchY = t.y
            }
        }
    }

    @Test
    fun `target radius follows the shorter field dimension`() {
        val engine = engine()
        assertEquals(width / 16f, engine.targetRadiusPx!!, 1e-4f)
    }

    /** The rejection loop must terminate even when no candidate can satisfy the exclusion zone. */
    @Test
    fun `impossible exclusion zone still spawns a valid target`() {
        val engine = engine(config = GameConfig(exclusionFactor = 1000f, maxSpawnAttempts = 20))
        engine.start(0L)
        var now = engine.advanceToTarget(0L)
        val first = engine.visibleTarget!!
        now += 250
        engine.onTouch(first.x, first.y, now)

        now = engine.advanceToTarget(now)
        val second = engine.visibleTarget!!
        assertNotNull(second)
        assertTrue(second.x - second.radiusPx >= -1e-3f && second.x + second.radiusPx <= width + 1e-3f)
        assertTrue(second.y - second.radiusPx >= -1e-3f && second.y + second.radiusPx <= height + 1e-3f)
    }

    /** Insets larger than the field must not throw; the target falls back to the centre. */
    @Test
    fun `degenerate safe area falls back to the centre`() {
        val engine = engine(insets = Insets(left = 600f, right = 600f, top = 1200f, bottom = 1200f))
        engine.start(0L)
        engine.advanceToTarget(0L)
        val target = engine.visibleTarget!!
        assertEquals(width / 2f, target.x, 1f)
        assertEquals(height / 2f, target.y, 1f)
    }

    @Test
    fun `touches in idle and finished phases are ignored`() {
        val engine = engine()
        assertTrue(engine.onTouch(10f, 10f, 0L).isEmpty())
        assertTrue(engine.tick(10_000L).isEmpty())

        engine.start(0L)
        var now = 0L
        repeat(10) {
            now = engine.advanceToTarget(now)
            val t = engine.visibleTarget!!
            now += 200
            engine.onTouch(t.x, t.y, now)
        }
        assertTrue(engine.phase is GamePhase.Finished)
        assertTrue(engine.onTouch(10f, 10f, now + 10).isEmpty())
        assertEquals(0, engine.misses)
        assertEquals(0, engine.falseStarts)
    }

    @Test
    fun `start resets counters`() {
        val engine = engine()
        engine.start(0L)
        engine.onTouch(1f, 1f, 5L)
        assertEquals(1, engine.falseStarts)

        engine.start(100L)
        assertEquals(0, engine.falseStarts)
        assertEquals(0, engine.misses)
        assertTrue(engine.measuredReactionTimes.isEmpty())
    }

    @Test
    fun `seeded runs are reproducible`() {
        fun run(): List<Pair<Float, Float>> {
            val engine = engine(seed = 99)
            engine.start(0L)
            var now = 0L
            return (0 until 10).map {
                now = engine.advanceToTarget(now)
                val t = engine.visibleTarget!!
                now += 250
                engine.onTouch(t.x, t.y, now)
                t.x to t.y
            }
        }
        assertEquals(run(), run())
    }
}
