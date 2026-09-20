package org.softosaurus.reactionspeed.games

import org.softosaurus.reactionspeed.game.SeriesResult

/**
 * Resource names of the Play Games achievements, spelled exactly as in
 * `docs/PLAY_GAMES_SETUP.md` and in the `games-ids.xml` export from Play Console.
 *
 * These are *resource names*, not Play Games ids: [PlayGamesManager] maps them to
 * `R.string.<name>` and skips the ones whose string is still empty. Keeping the mapping out
 * of this file is what allows [AchievementRules] to stay free of any Android dependency.
 */
object AchievementIds {
    const val FIRST_SERIES = "achievement_first_series"
    const val UNDER_350 = "achievement_under_350"
    const val UNDER_300 = "achievement_under_300"
    const val UNDER_250 = "achievement_under_250"
    const val UNDER_220 = "achievement_under_220"
    const val SERIES_10 = "achievement_series_10"
    const val SERIES_50 = "achievement_series_50"
    const val SERIES_200 = "achievement_series_200"
    const val STEADY_HAND = "achievement_steady_hand"
    const val FLAWLESS = "achievement_flawless"
}

/**
 * What a finished series earns.
 *
 * @param unlock standard achievements to unlock (unlocking is idempotent server-side)
 * @param increment incremental achievements to advance by exactly one step
 */
data class AchievementOutcome(
    val unlock: List<String> = emptyList(),
    val increment: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = unlock.isEmpty() && increment.isEmpty()

    companion object {
        val NONE = AchievementOutcome()
    }
}

/**
 * Pure (Android-free) rules that turn a finished series into achievement work.
 *
 * Decisions taken here, so that they are testable and reviewable in one place:
 *
 * * **Anti-cheat gate.** Everything except [AchievementIds.FIRST_SERIES] requires
 *   [SeriesResult.isPlausible]. A series containing a sub-100 ms "reaction" is stored locally
 *   but must not earn anything. `first_series` is deliberately exempt: it only says
 *   "you have played once", and withholding it would look like a bug to an honest player whose
 *   very first series happened to be mis-measured.
 * * **`first_series` triggers on any `seriesCount >= 1`**, not on `seriesCount == 1`. Unlocking
 *   is idempotent, and a user migrated from 3.x arrives with a non-zero count — an
 *   `== 1` test would mean they could never earn it.
 * * **`steady_hand` uses [SeriesResult.stdDev] as given**, i.e. the population standard deviation
 *   over *all ten* attempts, outliers included. [SeriesResult] exposes no std-dev of the accepted
 *   subset, and recomputing one here would invent a statistic the game core does not define.
 *   Using the raw figure makes the achievement strictly harder, so it can never unlock by
 *   accident.
 * * **The `under_*` thresholds are cumulative.** A 210 ms mean unlocks 350, 300, 250 *and* 220.
 * * **The incremental achievements advance by one per qualifying series**, as opposed to being
 *   re-set from `seriesCount`. `seriesCount` counts every *stored* series, including implausible
 *   ones, so it is not the same quantity as "plausible series played" and must not be used to
 *   `setSteps`. It is used only to decide that at least one series exists.
 */
object AchievementRules {

    /** Std-dev (ms, over all attempts) that [AchievementIds.STEADY_HAND] requires beating. */
    const val STEADY_HAND_MAX_STD_DEV_MS = 25.0

    /**
     * `filteredMean` thresholds, strictly-less-than, in descending order.
     * Mirrors the "Quick / Fast / Lightning / Superhuman" tiers of `docs/PLAY_GAMES_SETUP.md`.
     */
    val MEAN_THRESHOLDS: List<Pair<Int, String>> = listOf(
        350 to AchievementIds.UNDER_350,
        300 to AchievementIds.UNDER_300,
        250 to AchievementIds.UNDER_250,
        220 to AchievementIds.UNDER_220,
    )

    /** Incremental achievements; every qualifying series advances each of them by one step. */
    val SERIES_COUNTERS: List<String> = listOf(
        AchievementIds.SERIES_10,
        AchievementIds.SERIES_50,
        AchievementIds.SERIES_200,
    )

    /**
     * @param result the series that just finished
     * @param seriesCount lifetime number of finished series **after** [result] has been recorded
     */
    fun evaluate(result: SeriesResult, seriesCount: Int): AchievementOutcome {
        if (seriesCount < 1) return AchievementOutcome.NONE

        val unlock = mutableListOf(AchievementIds.FIRST_SERIES)
        if (!result.isPlausible) return AchievementOutcome(unlock = unlock)

        for ((thresholdMs, id) in MEAN_THRESHOLDS) {
            if (result.filteredMean < thresholdMs) unlock += id
        }
        if (result.stdDev < STEADY_HAND_MAX_STD_DEV_MS) unlock += AchievementIds.STEADY_HAND
        if (result.allAccepted && result.falseStarts == 0 && result.misses == 0) {
            unlock += AchievementIds.FLAWLESS
        }
        return AchievementOutcome(unlock = unlock, increment = SERIES_COUNTERS)
    }
}
