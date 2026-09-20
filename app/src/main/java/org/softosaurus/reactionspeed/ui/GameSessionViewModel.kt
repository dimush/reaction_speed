package org.softosaurus.reactionspeed.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.softosaurus.reactionspeed.data.ResultsRepository
import org.softosaurus.reactionspeed.game.SeriesResult
import org.softosaurus.reactionspeed.games.PlayGamesLocator
import org.softosaurus.reactionspeed.games.PlayGamesManager
import org.softosaurus.reactionspeed.resultsRepository

/** What the result screen shows, captured the moment the series ended. */
data class FinishedSeries(
    val result: SeriesResult,
    /** True when this score beat every stored one — evaluated *before* it was recorded. */
    val isNewPersonalBest: Boolean,
)

/**
 * Hand-over between the game screen and the result screen, scoped to the activity.
 *
 * The result cannot travel as a navigation argument, and it must be recorded **exactly once**:
 * [consume] is guarded by a flag that survives recomposition and configuration changes, so
 * neither a rotation nor a re-entry of the result screen can double-count a series.
 *
 * After process death the flow is simply empty; the result screen then returns Home instead of
 * rendering a blank page.
 */
class GameSessionViewModel(
    private val results: ResultsRepository,
    private val playGames: PlayGamesManager?,
) : ViewModel() {

    private val _finished = MutableStateFlow<FinishedSeries?>(null)
    val finished: StateFlow<FinishedSeries?> = _finished.asStateFlow()

    private var consumed = false

    /** Call before navigating to the game screen. */
    fun prepareNewSeries() {
        _finished.value = null
        consumed = false
    }

    /** Called from the game screen's listener when a series completed normally. */
    fun onSeriesFinished(result: SeriesResult) {
        if (_finished.value != null) return
        val previousBest = results.top10.value.firstOrNull()
        _finished.value = FinishedSeries(
            result = result,
            isNewPersonalBest = previousBest == null || result.filteredMean < previousBest,
        )
    }

    /** Stores the series and hands it to Play Games. Idempotent. */
    fun consume(activity: Activity) {
        val finished = _finished.value ?: return
        if (consumed) return
        consumed = true
        results.addResult(finished.result)
        playGames?.submitResult(activity, finished.result, results.seriesCount.value)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                GameSessionViewModel(app.resultsRepository, PlayGamesLocator.managerOrNull())
            }
        }
    }
}
