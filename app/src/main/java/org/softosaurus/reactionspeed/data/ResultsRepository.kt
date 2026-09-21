package org.softosaurus.reactionspeed.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.softosaurus.reactionspeed.game.SeriesResult

/**
 * Persistent store of results and settings.
 *
 * All flows are hot [StateFlow]s that always carry the current value, so the UI can collect them
 * directly. Writes are cheap and synchronous in memory; the backing store is updated with
 * `SharedPreferences.apply()`.
 */
interface ResultsRepository {

    /** Ten best series scores, ascending (fastest first). */
    val top10: StateFlow<List<Int>>

    /** All stored series scores in chronological order (oldest first). */
    val history: StateFlow<List<Int>>

    /** Fastest single reaction ever recorded, or `null` if unknown (e.g. right after migration). */
    val bestSingleMs: StateFlow<Int?>

    /** Lifetime number of finished series; not reset by [clearHistory]. */
    val seriesCount: StateFlow<Int>

    /** Sound and haptics preferences. */
    val settings: StateFlow<GameSettings>

    /**
     * Records a finished series. [SeriesResult.filteredMean] becomes the stored score and
     * [SeriesResult.bestSingle] may improve [bestSingleMs].
     *
     * Implausible results (see [SeriesResult.isPlausible]) are still stored locally — the caller
     * decides separately whether to submit them to a leaderboard.
     */
    fun addResult(result: SeriesResult)

    /** Removes the most recent series; see [ScoreBoard.withoutLastSeries]. */
    fun removeLastResult()

    /** Clears top-10, history and the best single reaction. */
    fun clearHistory()

    fun setSoundEffects(enabled: Boolean)

    fun setMusic(enabled: Boolean)

    fun setVoice(enabled: Boolean)

    fun setVibration(enabled: Boolean)
}

/**
 * [SharedPreferences]-backed implementation.
 *
 * Data lives in the file [PREFS_NAME]; on first construction it performs a one-time, idempotent
 * migration of the legacy 3.x preferences (see [LegacyPrefs]). The legacy file is only read, never
 * written or cleared, so downgrading to 3.x keeps working.
 */
class SharedPreferencesResultsRepository(
    context: Context,
) : ResultsRepository {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _top10 = MutableStateFlow<List<Int>>(emptyList())
    private val _history = MutableStateFlow<List<Int>>(emptyList())
    private val _bestSingleMs = MutableStateFlow<Int?>(null)
    private val _seriesCount = MutableStateFlow(0)
    private val _settings = MutableStateFlow(GameSettings())

    override val top10: StateFlow<List<Int>> = _top10.asStateFlow()
    override val history: StateFlow<List<Int>> = _history.asStateFlow()
    override val bestSingleMs: StateFlow<Int?> = _bestSingleMs.asStateFlow()
    override val seriesCount: StateFlow<Int> = _seriesCount.asStateFlow()
    override val settings: StateFlow<GameSettings> = _settings.asStateFlow()

    init {
        var board = loadBoard()
        var settings = loadSettings()
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val legacyRaw: Map<String, Any?> = try {
                appContext.getSharedPreferences(LegacyPrefs.FILE_NAME, Context.MODE_PRIVATE).all
            } catch (e: Exception) {
                // A missing or unreadable legacy file must never keep the app from starting.
                emptyMap()
            }
            val legacy = LegacyPrefs.read(legacyRaw)
            board = LegacyPrefs.mergeInto(board, legacy)
            if (legacyRaw.isNotEmpty()) settings = legacy.settings
            persist(board, settings, markMigrated = true)
        }
        publish(board, settings)
    }

    override fun addResult(result: SeriesResult) {
        val board = currentBoard().withSeries(
            scoreMs = result.filteredMean,
            bestSingleMs = result.bestSingle,
        )
        persist(board, _settings.value, markMigrated = false)
        publish(board, _settings.value)
    }

    override fun removeLastResult() {
        val board = currentBoard().withoutLastSeries()
        persist(board, _settings.value, markMigrated = false)
        publish(board, _settings.value)
    }

    override fun clearHistory() {
        val board = currentBoard().cleared()
        persist(board, _settings.value, markMigrated = false)
        publish(board, _settings.value)
    }

    override fun setSoundEffects(enabled: Boolean) = updateSettings { it.copy(soundEffects = enabled) }

    override fun setMusic(enabled: Boolean) = updateSettings { it.copy(music = enabled) }

    override fun setVoice(enabled: Boolean) = updateSettings { it.copy(voice = enabled) }

    override fun setVibration(enabled: Boolean) = updateSettings { it.copy(vibration = enabled) }

    // --- internals ----------------------------------------------------------------------------

    private fun updateSettings(transform: (GameSettings) -> GameSettings) {
        val settings = transform(_settings.value)
        prefs.edit { writeSettings(settings) }
        _settings.value = settings
    }

    private fun currentBoard() = ScoreBoard(
        top10 = _top10.value,
        history = _history.value,
        bestSingleMs = _bestSingleMs.value,
        seriesCount = _seriesCount.value,
    )

    private fun publish(board: ScoreBoard, settings: GameSettings) {
        _top10.value = board.top10
        _history.value = board.history
        _bestSingleMs.value = board.bestSingleMs
        _seriesCount.value = board.seriesCount
        _settings.value = settings
    }

    private fun loadBoard() = ScoreBoard(
        top10 = IntListCodec.decode(prefs.getString(KEY_TOP10, null)),
        history = IntListCodec.decode(prefs.getString(KEY_HISTORY, null)),
        bestSingleMs = prefs.getInt(KEY_BEST_SINGLE, 0).takeIf { it > 0 },
        seriesCount = prefs.getInt(KEY_SERIES_COUNT, 0).coerceAtLeast(0),
    ).withCredibleBestSingle()

    /**
     * Reads the preferences through [SettingsCodec], so a file written by 4.0 — which had the two
     * old sound switches and no music/voice keys — migrates in place instead of resetting.
     */
    private fun loadSettings(): GameSettings = SettingsCodec.read(
        try {
            prefs.all
        } catch (e: Exception) {
            emptyMap<String, Any?>()
        },
    )

    private fun persist(board: ScoreBoard, settings: GameSettings, markMigrated: Boolean) {
        prefs.edit {
            putString(KEY_TOP10, IntListCodec.encode(board.top10))
            putString(KEY_HISTORY, IntListCodec.encode(board.history))
            putInt(KEY_BEST_SINGLE, board.bestSingleMs ?: 0)
            putInt(KEY_SERIES_COUNT, board.seriesCount)
            writeSettings(settings)
            if (markMigrated) putBoolean(KEY_MIGRATED, true)
        }
    }

    /**
     * Only the current keys are written. The 3.x/4.0 spellings stay in the file untouched: they are
     * the fallback [SettingsCodec] reads when the new keys are missing, and rewriting them would
     * make a downgrade lie about what the player chose.
     */
    private fun SharedPreferences.Editor.writeSettings(settings: GameSettings) {
        putBoolean(SettingsCodec.KEY_SOUND_EFFECTS, settings.soundEffects)
        putBoolean(SettingsCodec.KEY_MUSIC, settings.music)
        putBoolean(SettingsCodec.KEY_VOICE, settings.voice)
        putBoolean(SettingsCodec.KEY_VIBRATION, settings.vibration)
    }

    companion object {
        /** Name of the 4.x preferences file. */
        const val PREFS_NAME = "reaction_speed"

        const val KEY_TOP10 = "top10"
        const val KEY_HISTORY = "history"
        const val KEY_BEST_SINGLE = "best_single"
        const val KEY_SERIES_COUNT = "series_count"

        /** One-shot flag guarding the legacy import. */
        const val KEY_MIGRATED = "migrated_legacy_v1"
    }
}
