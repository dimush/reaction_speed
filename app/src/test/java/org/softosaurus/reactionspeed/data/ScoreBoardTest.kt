package org.softosaurus.reactionspeed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreBoardTest {

    @Test
    fun `top10 is sorted ascending and capped at ten`() {
        var board = ScoreBoard()
        listOf(400, 310, 500, 280, 350, 290, 460, 270, 330, 300, 320).forEach {
            board = board.withSeries(it)
        }

        assertEquals(listOf(270, 280, 290, 300, 310, 320, 330, 350, 400, 460), board.top10)
        assertEquals(11, board.history.size)
        assertEquals(11, board.seriesCount)
    }

    @Test
    fun `a worse result does not enter a full board`() {
        var board = ScoreBoard()
        repeat(10) { board = board.withSeries(200 + it) }
        val before = board.top10

        board = board.withSeries(900)

        assertEquals(before, board.top10)
        assertEquals(11, board.history.size)
    }

    @Test
    fun `ties are kept, matching the legacy shift-down insertion`() {
        var board = ScoreBoard()
        repeat(3) { board = board.withSeries(250) }

        assertEquals(listOf(250, 250, 250), board.top10)
    }

    @Test
    fun `history is chronological and best single only improves`() {
        val board = ScoreBoard()
            .withSeries(320, bestSingleMs = 290)
            .withSeries(300, bestSingleMs = 255)
            .withSeries(310, bestSingleMs = 280)

        assertEquals(listOf(320, 300, 310), board.history)
        assertEquals(255, board.bestSingleMs)
    }

    @Test
    fun `history is capped at the maximum size`() {
        var board = ScoreBoard()
        repeat(ScoreBoard.MAX_HISTORY + 5) { board = board.withSeries(200 + (it % 100)) }

        assertEquals(ScoreBoard.MAX_HISTORY, board.history.size)
        assertEquals(ScoreBoard.MAX_HISTORY + 5, board.seriesCount)
    }

    @Test
    fun `clearing keeps the lifetime series counter`() {
        var board = ScoreBoard()
        repeat(4) { board = board.withSeries(300, bestSingleMs = 280) }

        val cleared = board.cleared()

        assertEquals(emptyList<Int>(), cleared.top10)
        assertEquals(emptyList<Int>(), cleared.history)
        assertNull(cleared.bestSingleMs)
        assertEquals(4, cleared.seriesCount)
    }

    @Test
    fun `non-positive values never reach the board`() {
        assertEquals(listOf(250), ScoreBoard.topOf(listOf(0, -5, 250)))
    }

    @Test
    fun `a degenerate zero-millisecond series is clamped, not rejected`() {
        // SeriesScorer can hand back a filteredMean of 0 for a pathological series (a clock that
        // did not move, a synthetic event stream). Storing it must not take the app down on the
        // way to the result screen.
        val board = ScoreBoard().withSeries(0, bestSingleMs = 0)

        assertEquals(listOf(1), board.top10)
        assertEquals(listOf(1), board.history)
        assertEquals(1, board.seriesCount)
        assertNull("a zero best-single is not a record", board.bestSingleMs)
    }

    @Test
    fun `a negative score is clamped too`() {
        val board = ScoreBoard().withSeries(-40)

        assertEquals(listOf(1), board.top10)
        assertEquals(listOf(1), board.history)
    }

    @Test
    fun `int list survives a storage round trip`() {
        val values = listOf(270, 288, 301)

        assertEquals(values, IntListCodec.decode(IntListCodec.encode(values)))
        assertEquals(emptyList<Int>(), IntListCodec.decode(null))
        assertEquals(emptyList<Int>(), IntListCodec.decode(""))
        assertEquals(listOf(270), IntListCodec.decode("270,,oops,-3,0"))
    }
}
