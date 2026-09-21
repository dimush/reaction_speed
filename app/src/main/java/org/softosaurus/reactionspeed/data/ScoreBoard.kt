package org.softosaurus.reactionspeed.data

/**
 * Pure, Android-free snapshot of everything the app persists about results.
 *
 * Keeping the insertion/sorting rules here (instead of inside the SharedPreferences repository)
 * makes them unit-testable without Robolectric.
 *
 * @param top10 the ten best series scores, ascending (fastest first)
 * @param history every series score in chronological order, oldest first
 * @param bestSingleMs fastest single reaction ever recorded, or `null` if unknown.
 *   Legacy 3.x stored only series means, so this stays `null` after migration until the player
 *   records a tap at least as fast as the migrated best average (see [withCredibleBestSingle]).
 * @param seriesCount lifetime number of finished series; used for incremental achievements and
 *   deliberately **not** reset by [cleared]
 */
data class ScoreBoard(
    val top10: List<Int> = emptyList(),
    val history: List<Int> = emptyList(),
    val bestSingleMs: Int? = null,
    val seriesCount: Int = 0,
) {

    /**
     * Adds one finished series.
     *
     * A degenerate series (every attempt measured as 0 ms, which a pathological clock or a
     * synthetic event stream can produce) must not take the app down on the way to the result
     * screen, so the score is clamped to 1 ms rather than rejected.
     *
     * @param scoreMs the official score of the series (filtered mean), clamped to at least 1
     * @param bestSingleMs fastest single attempt of that series, or `null` if not available
     */
    fun withSeries(scoreMs: Int, bestSingleMs: Int? = null): ScoreBoard {
        val score = scoreMs.coerceAtLeast(1)
        val newHistory = (history + score).takeLast(MAX_HISTORY)
        val newBestSingle = listOfNotNull(this.bestSingleMs, bestSingleMs?.takeIf { it > 0 }).minOrNull()
        return copy(
            top10 = topOf(top10 + score),
            history = newHistory,
            bestSingleMs = newBestSingle,
            seriesCount = seriesCount + 1,
        ).withCredibleBestSingle()
    }

    /**
     * Drops a [bestSingleMs] that is slower than the best series average.
     *
     * The fastest tap of a series is never slower than that series' mean, so a best single above
     * `top10.first()` can only mean the real record was set in legacy 3.x, which never stored single
     * taps. Showing "unknown" is honest; showing a number the player has provably beaten is not.
     */
    fun withCredibleBestSingle(): ScoreBoard {
        val bestAverage = top10.firstOrNull() ?: return this
        return if (bestSingleMs != null && bestSingleMs > bestAverage) copy(bestSingleMs = null) else this
    }

    /**
     * Removes the most recent series (e.g. someone else played on the phone): drops it from
     * [history], takes one matching entry out of [top10], and forgets [bestSingleMs], which may have
     * come from that series and cannot be recomputed. No-op on an empty history.
     */
    fun withoutLastSeries(): ScoreBoard {
        val last = history.lastOrNull() ?: return this
        return copy(
            top10 = top10 - last,
            history = history.dropLast(1),
            bestSingleMs = null,
            seriesCount = (seriesCount - 1).coerceAtLeast(0),
        )
    }

    /**
     * Clears the visible statistics (top-10, history and the best single reaction), the way the
     * legacy "clean history" menu item did. [seriesCount] survives, because it feeds lifetime
     * achievements.
     */
    fun cleared(): ScoreBoard = copy(top10 = emptyList(), history = emptyList(), bestSingleMs = null)

    companion object {
        /** Size of the leaderboard kept on the device. */
        const val TOP_SIZE = 10

        /** Upper bound on the stored history; older entries are dropped. */
        const val MAX_HISTORY = 2000

        /**
         * The legacy insertion loop (shift-down into a fixed array of ten, inserting before the
         * first slot that is `>=` the new value or empty) is multiset-equivalent to simply sorting
         * and taking the ten smallest — including ties and the "board full and new value worse"
         * case.
         */
        fun topOf(values: List<Int>): List<Int> =
            values.filter { it > 0 }.sorted().take(TOP_SIZE)
    }
}

/**
 * Serialisation of an int list as one preference string. Pure, so it is covered by plain JVM tests.
 * Decoding silently drops anything that is not a positive integer, which makes a corrupt or
 * partially written value degrade instead of crashing.
 */
object IntListCodec {

    fun encode(values: List<Int>): String = values.joinToString(",")

    fun decode(raw: String?): List<Int> =
        raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 } ?: emptyList()
}

/**
 * Sound and haptics preferences.
 *
 * @param soundEffects target pops, hits, misses, jingles and the monster gibberish
 * @param music the two background loops
 * @param voice the localised spoken lines
 * @param vibration the pulse when a target appears
 */
data class GameSettings(
    val soundEffects: Boolean = true,
    val music: Boolean = true,
    val voice: Boolean = true,
    val vibration: Boolean = true,
)

/**
 * Reads [GameSettings] out of a raw preferences map, migrating anything an older version wrote.
 *
 * Pure and Android-free on purpose: the same code has to cope with three different shapes and each
 * of them deserves a unit test rather than a device.
 *
 * 1. **4.1 and later** — [KEY_SOUND_EFFECTS], [KEY_MUSIC], [KEY_VOICE], [KEY_VIBRATION].
 * 2. **4.0** (versionCode 11/12, which is what the owner's phone runs) — the new preferences file
 *    but with the two old sound switches. Forgetting this case would silently reset the sound
 *    preference of every existing player, which is why it is handled here and not only in
 *    [LegacyPrefs].
 * 3. **3.x** — the legacy file, same two sound switches plus `use_vibrator`.
 *
 * The rule for the merge is `use_target_sounds || use_stone_sounds → soundEffects`: a player who
 * silenced only one of the two halves of the old sound design wanted *some* sound, so they keep it.
 * A key that was never written counts as its old default (`true`), so only someone who deliberately
 * turned both off arrives with effects disabled. Music and voice are new and default to on.
 */
object SettingsCodec {

    const val KEY_SOUND_EFFECTS = "sound_effects"
    const val KEY_MUSIC = "music"
    const val KEY_VOICE = "voice"
    const val KEY_VIBRATION = "use_vibration"

    /** 3.x / 4.0 keys, read only. */
    const val KEY_LEGACY_TARGET_SOUNDS = "use_target_sounds"
    const val KEY_LEGACY_STONE_SOUNDS = "use_stone_sounds"

    /** The 3.x spelling of [KEY_VIBRATION]. */
    const val KEY_LEGACY_VIBRATOR = "use_vibrator"

    fun read(raw: Map<String, *>): GameSettings = GameSettings(
        soundEffects = boolOrNull(raw[KEY_SOUND_EFFECTS])
            ?: ((boolOrNull(raw[KEY_LEGACY_TARGET_SOUNDS]) ?: true) ||
                (boolOrNull(raw[KEY_LEGACY_STONE_SOUNDS]) ?: true)),
        music = boolOrNull(raw[KEY_MUSIC]) ?: true,
        voice = boolOrNull(raw[KEY_VOICE]) ?: true,
        vibration = boolOrNull(raw[KEY_VIBRATION])
            ?: boolOrNull(raw[KEY_LEGACY_VIBRATOR])
            ?: true,
    )

    private fun boolOrNull(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}
