package org.softosaurus.reactionspeed.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSequenceTest {

    @Test
    fun `never repeats the previous face`() {
        val sequence = FaceSequence(faceCount = 10, random = Random(1))
        var previous = -1
        repeat(5_000) {
            val index = sequence.next()
            assertTrue("index out of range: $index", index in 0..9)
            assertNotEquals("the same face twice in a row", previous, index)
            previous = index
        }
    }

    @Test
    fun `every face is reachable and roughly equally likely`() {
        val sequence = FaceSequence(faceCount = 10, random = Random(7))
        val counts = IntArray(10)
        repeat(100_000) { counts[sequence.next()] += 1 }

        // With the "not the previous one" constraint the stationary distribution is still uniform,
        // so a 25 % band around the mean is a generous but meaningful check.
        val expected = 10_000
        for ((face, count) in counts.withIndex()) {
            assertTrue("face $face appeared $count times", count > expected * 0.75)
            assertTrue("face $face appeared $count times", count < expected * 1.25)
        }
    }

    @Test
    fun `reset forgets the previous face`() {
        val sequence = FaceSequence(faceCount = 3, random = Random(3))
        val first = sequence.next()
        assertEquals(first, sequence.lastIndex)
        sequence.reset()
        assertEquals(-1, sequence.lastIndex)
        // After a reset the first draw may legitimately be the same face again, so only the
        // bookkeeping is asserted here.
        assertTrue(sequence.next() in 0..2)
    }

    @Test
    fun `a single face degenerates gracefully`() {
        val sequence = FaceSequence(faceCount = 1, random = Random(0))
        repeat(5) { assertEquals(0, sequence.next()) }
    }
}
