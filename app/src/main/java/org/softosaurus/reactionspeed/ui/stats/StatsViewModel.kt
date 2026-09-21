package org.softosaurus.reactionspeed.ui.stats

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.softosaurus.reactionspeed.data.ResultsRepository
import org.softosaurus.reactionspeed.resultsRepository

/** Where the recent results are heading compared with the ten before them. */
enum class Trend { IMPROVING, STEADY, SLOWER }

data class StatsUiState(
    val history: List<Int> = emptyList(),
    val top10: List<Int> = emptyList(),
    val seriesCount: Int = 0,
    val allTimeBestMs: Int? = null,
    val lastTenAverageMs: Int? = null,
    val trend: Trend? = null,
) {
    val isEmpty: Boolean get() = history.isEmpty() && top10.isEmpty()
}

class StatsViewModel(private val results: ResultsRepository) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = combine(
        results.history,
        results.top10,
        results.seriesCount,
    ) { history, top10, seriesCount ->
        val lastTen = history.takeLast(WINDOW)
        StatsUiState(
            history = history,
            top10 = top10,
            seriesCount = seriesCount,
            allTimeBestMs = top10.firstOrNull(),
            lastTenAverageMs = lastTen.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            trend = trendOf(history),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatsUiState())

    fun removeLastResult() = results.removeLastResult()

    fun clearHistory() = results.clearHistory()

    companion object {
        private const val WINDOW = 10
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Half the window has to differ by more than this to count as a real change. */
        private const val TREND_THRESHOLD_MS = 5.0

        /**
         * Compares the most recent ten results with the ten before them. Fewer than
         * [WINDOW] + 1 results means "not enough evidence" and yields `null`.
         */
        internal fun trendOf(history: List<Int>): Trend? {
            if (history.size <= WINDOW) return null
            val recent = history.takeLast(WINDOW).average()
            val previous = history.dropLast(WINDOW).takeLast(WINDOW).average()
            val delta = recent - previous
            return when {
                delta < -TREND_THRESHOLD_MS -> Trend.IMPROVING
                delta > TREND_THRESHOLD_MS -> Trend.SLOWER
                else -> Trend.STEADY
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                StatsViewModel(app.resultsRepository)
            }
        }
    }
}
