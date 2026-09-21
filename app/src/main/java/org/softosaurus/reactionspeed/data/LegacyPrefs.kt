package org.softosaurus.reactionspeed.data

/**
 * Everything that could be recovered from the legacy 3.x preferences.
 *
 * @param top10 legacy `best_res0..9`, cleaned and sorted ascending
 * @param history legacy `res0..N` in chronological order
 * @param settings legacy `use_*` flags, defaults kept when absent or corrupt
 * @param hasData true when at least one result was recovered
 */
data class LegacyData(
    val top10: List<Int> = emptyList(),
    val history: List<Int> = emptyList(),
    val settings: GameSettings = GameSettings(),
) {
    val hasData: Boolean get() = top10.isNotEmpty() || history.isNotEmpty()
}

/**
 * Reader for the preferences written by the legacy `ReactionSpeedActivity`.
 *
 * The legacy code used `Activity.getPreferences(0)`, i.e. a prefs file named after the activity's
 * local class name — [FILE_NAME]. Because the activity will be renamed in 4.0, the new code must
 * open that file **explicitly by name**:
 * `context.getSharedPreferences(LegacyPrefs.FILE_NAME, Context.MODE_PRIVATE)`.
 *
 * The parsing works over a plain `Map<String, *>` (what `SharedPreferences.getAll()` returns), so
 * it is unit-testable on a plain JVM without Robolectric, and is tolerant to every kind of garbage
 * a decade of app versions may have left behind.
 */
object LegacyPrefs {

    /** Name of the legacy SharedPreferences file. */
    const val FILE_NAME = "ReactionSpeedActivity"

    const val KEY_BEST_SIZE = "best_res_size"
    const val KEY_BEST_PREFIX = "best_res"
    const val KEY_RES_SIZE = "res_size"
    const val KEY_RES_PREFIX = "res"
    const val KEY_STONE_SOUNDS = SettingsCodec.KEY_LEGACY_STONE_SOUNDS
    const val KEY_TARGET_SOUNDS = SettingsCodec.KEY_LEGACY_TARGET_SOUNDS
    const val KEY_VIBRATOR = SettingsCodec.KEY_LEGACY_VIBRATOR

    /** Sanity cap on the declared history length (legacy history grew one entry per series). */
    private const val MAX_DECLARED_HISTORY = 100_000

    /**
     * Parses a raw preferences map.
     *
     * Rules, all deliberate:
     * * the declared sizes are authoritative — the legacy "clean history" wrote `res_size = 0`
     *   without removing the stale `resN` keys, so scanning beyond the declared size would
     *   resurrect data the user deleted;
     * * a missing, negative or non-numeric size means "no data";
     * * `best_res_size` is clamped to ten;
     * * entries that are missing, non-numeric or `<= 0` are skipped (legacy wrote zeros into unused
     *   slots);
     * * values below the 100 ms plausibility threshold are **kept** — this is the user's own
     *   history; plausibility only gates leaderboard submission, never local storage.
     */
    fun read(raw: Map<String, *>): LegacyData {
        val bestSize = intOrNull(raw[KEY_BEST_SIZE])?.coerceIn(0, ScoreBoard.TOP_SIZE) ?: 0
        val top10 = (0 until bestSize)
            .mapNotNull { intOrNull(raw["$KEY_BEST_PREFIX$it"]) }
            .filter { it > 0 }
            .sorted()

        val historySize = intOrNull(raw[KEY_RES_SIZE])?.coerceIn(0, MAX_DECLARED_HISTORY) ?: 0
        val history = (0 until historySize)
            .mapNotNull { intOrNull(raw["$KEY_RES_PREFIX$it"]) }
            .filter { it > 0 }

        return LegacyData(
            top10 = top10,
            history = history,
            // The sound/haptics keys are shared with the 4.x file, so one codec reads both.
            settings = SettingsCodec.read(raw),
        )
    }

    /**
     * Merges legacy data into an existing (normally empty) [ScoreBoard].
     *
     * Idempotency is guaranteed by the caller's one-shot `migrated_legacy_v1` flag — the merge
     * itself does not deduplicate, because two series may legitimately score the same. The legacy
     * top-10 is merged with the current one rather than replacing it, and `seriesCount` is seeded
     * from the larger of the legacy history length and the legacy top-10 size.
     */
    fun mergeInto(board: ScoreBoard, legacy: LegacyData): ScoreBoard {
        if (!legacy.hasData) return board
        val history = (legacy.history + board.history).takeLast(ScoreBoard.MAX_HISTORY)
        return board.copy(
            top10 = ScoreBoard.topOf(legacy.top10 + board.top10),
            history = history,
            // bestSingleMs intentionally untouched: legacy stored series means only, never a
            // single reaction, so there is nothing to seed it with.
            seriesCount = maxOf(board.seriesCount, legacy.history.size, legacy.top10.size),
        )
    }

    private fun intOrNull(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

}
