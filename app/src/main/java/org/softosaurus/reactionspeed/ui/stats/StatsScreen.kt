package org.softosaurus.reactionspeed.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.ui.common.BannerScaffold
import org.softosaurus.reactionspeed.ui.common.BackTopBar
import org.softosaurus.reactionspeed.ui.common.msOrDash

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    BannerScaffold {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(stringResource(R.string.stats_title), onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp),
            ) {
                if (state.isEmpty) {
                    Text(
                        text = stringResource(R.string.stats_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                    )
                    return@Column
                }

                SectionTitle(stringResource(R.string.stats_history))
                Card(Modifier.fillMaxWidth()) {
                    HistoryChart(
                        values = state.history,
                        contentDescription = stringResource(R.string.stats_chart_description),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(12.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.stats_totals))
                TotalsCard(state)

                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.stats_top10))
                TopTenCard(state.top10)

                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.stats_clear))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stats_clear_title)) },
            text = { Text(stringResource(R.string.stats_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) {
                    Text(stringResource(R.string.stats_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun TotalsCard(state: StatsUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = pluralStringResource(
                    R.plurals.stats_series_count,
                    state.seriesCount,
                    state.seriesCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            TotalsRow(stringResource(R.string.stats_all_time_best), msOrDash(state.allTimeBestMs))
            TotalsRow(
                stringResource(R.string.stats_last10_average),
                msOrDash(state.lastTenAverageMs),
            )
            state.trend?.let {
                TotalsRow(stringResource(R.string.stats_trend), stringResource(it.labelRes()))
            }
        }
    }
}

@Composable
private fun TotalsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun TopTenCard(top10: List<Int>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            top10.forEachIndexed { index, score ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.online_rank_format, index + 1),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        text = stringResource(R.string.ms_format, score),
                        style = if (index == 0) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (index == 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

private fun Trend.labelRes(): Int = when (this) {
    Trend.IMPROVING -> R.string.stats_trend_improving
    Trend.STEADY -> R.string.stats_trend_steady
    Trend.SLOWER -> R.string.stats_trend_slower
}
