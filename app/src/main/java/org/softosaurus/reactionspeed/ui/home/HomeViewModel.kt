package org.softosaurus.reactionspeed.ui.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.softosaurus.reactionspeed.data.ResultsRepository
import org.softosaurus.reactionspeed.games.LeaderboardEntry
import org.softosaurus.reactionspeed.games.PlayGamesLocator
import org.softosaurus.reactionspeed.games.PlayGamesManager
import org.softosaurus.reactionspeed.games.PlayGamesState
import org.softosaurus.reactionspeed.resultsRepository

/** Everything the home screen shows about the player's own history. */
data class HomeUiState(
    val bestAverageMs: Int? = null,
    val bestSingleMs: Int? = null,
    val seriesCount: Int = 0,
    val lastResultMs: Int? = null,
    /** Most recent scores, oldest first, for the sparkline. */
    val recent: List<Int> = emptyList(),
) {
    val isFirstTime: Boolean get() = seriesCount == 0
}

/**
 * The world top-10 list.
 *
 * There is no failure state on purpose: [PlayGamesManager.loadTopScores] swallows every error and
 * returns an empty list, so the UI genuinely cannot tell "no scores yet" from "the call failed".
 * Inventing an error branch here would only produce dead code.
 */
sealed interface WorldTop {
    data object Idle : WorldTop
    data object Loading : WorldTop
    data class Content(val entries: List<LeaderboardEntry>) : WorldTop
    data object Empty : WorldTop
}

class HomeViewModel(
    results: ResultsRepository,
    private val playGames: PlayGamesManager?,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        results.top10,
        results.bestSingleMs,
        results.seriesCount,
        results.history,
    ) { top10, bestSingle, seriesCount, history ->
        HomeUiState(
            bestAverageMs = top10.firstOrNull(),
            bestSingleMs = bestSingle,
            seriesCount = seriesCount,
            lastResultMs = history.lastOrNull(),
            recent = history.takeLast(SPARKLINE_POINTS),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    val playGamesState: StateFlow<PlayGamesState> =
        playGames?.state ?: MutableStateFlow(PlayGamesState.NotConfigured).asStateFlow()

    /** True when the online card may be shown at all. */
    val isOnlineAvailable: Boolean = playGames?.isConfigured == true

    private val _worldTop = MutableStateFlow<WorldTop>(WorldTop.Idle)
    val worldTop: StateFlow<WorldTop> = _worldTop.asStateFlow()

    private val _myRank = MutableStateFlow<Long?>(null)
    val myRank: StateFlow<Long?> = _myRank.asStateFlow()

    /**
     * Loads the online decoration. Called whenever the home screen becomes visible while signed
     * in; the loaders need a started activity, which is exactly when that holds.
     */
    fun refreshOnline() {
        val manager = playGames ?: return
        if (!manager.isConfigured) return
        if (_worldTop.value == WorldTop.Loading) return
        _worldTop.value = WorldTop.Loading
        viewModelScope.launch {
            _myRank.value = manager.loadMyBestAverage()?.rank
            val entries = manager.loadTopScores(TOP_SCORES)
            _worldTop.value = if (entries.isEmpty()) WorldTop.Empty else WorldTop.Content(entries)
        }
    }

    companion object {
        private const val SPARKLINE_POINTS = 20
        private const val TOP_SCORES = 10
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                HomeViewModel(app.resultsRepository, PlayGamesLocator.managerOrNull())
            }
        }
    }
}
