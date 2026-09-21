package org.softosaurus.reactionspeed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPrefsTest {

    private fun legacyMap(
        best: List<Int>,
        history: List<Int>,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = buildMap {
        put(LegacyPrefs.KEY_BEST_SIZE, best.size)
        best.forEachIndexed { i, v -> put("${LegacyPrefs.KEY_BEST_PREFIX}$i", v) }
        put(LegacyPrefs.KEY_RES_SIZE, history.size)
        history.forEachIndexed { i, v -> put("${LegacyPrefs.KEY_RES_PREFIX}$i", v) }
        putAll(extra)
    }

    @Test
    fun `reads a normal legacy file`() {
        val raw = legacyMap(
            best = listOf(271, 288, 300),
            history = listOf(340, 300, 288, 271),
            extra = mapOf(
                LegacyPrefs.KEY_STONE_SOUNDS to false,
                LegacyPrefs.KEY_TARGET_SOUNDS to true,
                LegacyPrefs.KEY_VIBRATOR to false,
            ),
        )

        val data = LegacyPrefs.read(raw)

        assertEquals(listOf(271, 288, 300), data.top10)
        assertEquals(listOf(340, 300, 288, 271), data.history)
        // One of the two legacy sound switches was still on, so effects survive the migration;
     // music and voice are new and default on. See SettingsCodecTest for the full rule set.
        assertEquals(
            GameSettings(soundEffects = true, music = true, voice = true, vibration = false),
            data.settings,
        )
        assertTrue(data.hasData)
    }

    @Test
    fun `empty map yields defaults and no data`() {
        val data = LegacyPrefs.read(emptyMap<String, Any?>())

        assertFalse(data.hasData)
        assertEquals(GameSettings(), data.settings)
    }

    @Test
    fun `zero and negative entries are dropped`() {
        val raw = legacyMap(best = listOf(0, 250, -3, 0), history = listOf(0, 0, 310))

        val data = LegacyPrefs.read(raw)

        assertEquals(listOf(250), data.top10)
        assertEquals(listOf(310), data.history)
    }

    @Test
    fun `declared size larger than the stored keys is tolerated`() {
        val raw = mapOf<String, Any?>(
            LegacyPrefs.KEY_BEST_SIZE to 10,
            "best_res0" to 260,
            "best_res1" to 275,
            LegacyPrefs.KEY_RES_SIZE to 7,
            "res0" to 300,
        )

        val data = LegacyPrefs.read(raw)

        assertEquals(listOf(260, 275), data.top10)
        assertEquals(listOf(300), data.history)
    }

    @Test
    fun `declared size larger than ten is clamped for the top list`() {
        val raw = buildMap<String, Any?> {
            put(LegacyPrefs.KEY_BEST_SIZE, 40)
            repeat(40) { put("best_res$it", 500 - it) }
            put(LegacyPrefs.KEY_RES_SIZE, 0)
        }

        val data = LegacyPrefs.read(raw)

        assertEquals(10, data.top10.size)
        assertEquals(listOf(491, 492, 493, 494, 495, 496, 497, 498, 499, 500), data.top10)
    }

    @Test
    fun `stale keys left behind by the legacy clean-history are not resurrected`() {
        // Legacy wrote res_size = 0 on clear but never removed the res* keys.
        val raw = mapOf<String, Any?>(
            LegacyPrefs.KEY_BEST_SIZE to 0,
            "best_res0" to 260,
            LegacyPrefs.KEY_RES_SIZE to 0,
            "res0" to 300,
            "res1" to 310,
        )

        val data = LegacyPrefs.read(raw)

        assertFalse(data.hasData)
        assertEquals(emptyList<Int>(), data.top10)
        assertEquals(emptyList<Int>(), data.history)
    }

    @Test
    fun `corrupt types and negative sizes do not crash`() {
        val raw = mapOf<String, Any?>(
            LegacyPrefs.KEY_BEST_SIZE to "nonsense",
            "best_res0" to 260,
            LegacyPrefs.KEY_RES_SIZE to -17,
            "res0" to "garbage",
            LegacyPrefs.KEY_VIBRATOR to "not a boolean",
            LegacyPrefs.KEY_STONE_SOUNDS to 42,
        )

        val data = LegacyPrefs.read(raw)

        assertFalse(data.hasData)
        assertTrue(data.settings.vibration)
        assertTrue(data.settings.soundEffects)
    }

    @Test
    fun `numeric values stored as strings or longs are accepted`() {
        val raw = mapOf<String, Any?>(
            LegacyPrefs.KEY_BEST_SIZE to 2L,
            "best_res0" to "265",
            "best_res1" to 280L,
            LegacyPrefs.KEY_RES_SIZE to 1,
            "res0" to 265,
        )

        val data = LegacyPrefs.read(raw)

        assertEquals(listOf(265, 280), data.top10)
        assertEquals(listOf(265), data.history)
    }

    @Test
    fun `implausibly fast legacy entries are kept as user data`() {
        val raw = legacyMap(best = listOf(42), history = listOf(42))

        val data = LegacyPrefs.read(raw)

        assertEquals(listOf(42), data.top10)
        assertEquals(listOf(42), data.history)
    }

    @Test
    fun `merge seeds an empty board and leaves best single unknown`() {
        val legacy = LegacyPrefs.read(
            legacyMap(best = listOf(270, 300), history = listOf(300, 270, 320)),
        )

        val board = LegacyPrefs.mergeInto(ScoreBoard(), legacy)

        assertEquals(listOf(270, 300), board.top10)
        assertEquals(listOf(300, 270, 320), board.history)
        assertEquals(3, board.seriesCount)
        assertNull(board.bestSingleMs)
    }

    @Test
    fun `merge does not deduplicate, so the one-shot flag is what makes migration idempotent`() {
        val legacy = LegacyPrefs.read(legacyMap(best = listOf(270), history = listOf(270)))
        val once = LegacyPrefs.mergeInto(ScoreBoard(), legacy)

        val twice = LegacyPrefs.mergeInto(once, legacy)

        // Documented behaviour: equal scores are legitimate, so re-running duplicates them.
        // SharedPreferencesResultsRepository must therefore gate the import on KEY_MIGRATED.
        assertEquals(listOf(270, 270), twice.top10)
    }

    @Test
    fun `merge prepends legacy history before newer entries`() {
        val legacy = LegacyPrefs.read(legacyMap(best = listOf(290), history = listOf(290, 310)))
        val existing = ScoreBoard(top10 = listOf(250), history = listOf(250), seriesCount = 1)

        val board = LegacyPrefs.mergeInto(existing, legacy)

        assertEquals(listOf(290, 310, 250), board.history)
        assertEquals(listOf(250, 290), board.top10)
        assertEquals(2, board.seriesCount)
    }

    @Test
    fun `merging empty legacy data is a no-op`() {
        val board = ScoreBoard(top10 = listOf(250), history = listOf(250), seriesCount = 1)

        assertEquals(board, LegacyPrefs.mergeInto(board, LegacyPrefs.read(emptyMap<String, Any?>())))
    }
}
