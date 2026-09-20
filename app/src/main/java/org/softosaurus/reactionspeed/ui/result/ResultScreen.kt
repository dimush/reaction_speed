package org.softosaurus.reactionspeed.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.game.Attempt
import org.softosaurus.reactionspeed.game.SeriesResult
import org.softosaurus.reactionspeed.games.LeaderboardId
import org.softosaurus.reactionspeed.games.PlayGamesLocator
import org.softosaurus.reactionspeed.games.PlayGamesState
import org.softosaurus.reactionspeed.ui.FinishedSeries
import org.softosaurus.reactionspeed.ui.GameSessionViewModel
import org.softosaurus.reactionspeed.ui.common.BannerScaffold
import org.softosaurus.reactionspeed.ui.common.msWithDecimal
import org.softosaurus.reactionspeed.ui.common.rememberActivity

/**
 * The score of the series just played.
 *
 * The series was already stored when it finished; only the online submission happens here, in a
 * single guarded call into [GameSessionViewModel.consume] — re-entering the screen or rotating the
 * device cannot double-count a series. After process death there is no result to show and the
 * screen returns Home instead of rendering an empty page.
 */
@Composable
fun ResultScreen(
    session: GameSessionViewModel,
    onAgain: () -> Unit,
    onHome: () -> Unit,
) {
    val finished by session.finished.collectAsStateWithLifecycle()
    val activity = rememberActivity()
    val flow = remember { ResultFlow() }

    // "Again" clears the session's result *before* navigating, and this screen stays composed for
    // the whole exit transition — without the guard the effect would re-fire with a null result and
    // send the user Home instead. The same flag keeps a second tap on either button from navigating
    // twice.
    LaunchedEffect(finished, activity) {
        if (flow.leaving) return@LaunchedEffect
        if (finished == null) onHome() else if (activity != null) session.consume(activity)
    }

    // The last result stays on screen while the outgoing transition plays, so "Again" does not
    // flash an empty page on its way to the game.
    if (finished != null) flow.lastShown = finished
    val current = flow.lastShown ?: return

    val leave = { action: () -> Unit ->
        if (!flow.leaving) {
            flow.leaving = true
            action()
        }
    }
    val manager = PlayGamesLocator.managerOrNull()
    val stateFlow = remember(manager) {
        manager?.state ?: MutableStateFlow<PlayGamesState>(PlayGamesState.NotConfigured)
    }
    val playGamesState by stateFlow.collectAsStateWithLifecycle()
    val onlineAvailable = manager != null && manager.isConfigured && activity != null

    BannerScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val result = current.result

            Text(
                text = stringResource(R.string.result_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.ms_format, result.filteredMean),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(verdictOf(result.filteredMean)),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            if (current.isNewPersonalBest) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.result_new_best),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            AttemptsCard(result)

            Spacer(Modifier.height(12.dp))
            SummaryRow(result)

            if (onlineAvailable) {
                Spacer(Modifier.height(12.dp))
                OnlineNote(
                    isPlausible = result.isPlausible,
                    isSignedIn = playGamesState is PlayGamesState.SignedIn,
                    onSignIn = { manager?.signIn(activity!!) },
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { leave(onAgain) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.result_again))
                }
                OutlinedButton(
                    onClick = { leave(onHome) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.result_home))
                }
            }

            if (onlineAvailable) {
                TextButton(
                    onClick = { manager?.showLeaderboard(activity!!, LeaderboardId.BEST_AVERAGE) },
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.result_leaderboard))
                }
            }
        }
    }
}

/**
 * Mutable, non-observable flags of one visit to the result screen. Like the game screen's flow
 * object these are read and written from callbacks during navigation, never rendered, so making
 * them Compose state would only buy a recomposition nobody needs. [lastShown] is the exception in
 * spirit but not in effect: it is only ever assigned during composition, from a value that already
 * triggered one.
 */
private class ResultFlow {
    /** The user is on their way out; further effects must not navigate again. */
    var leaving = false

    /** The last non-null result, kept so the exit transition has something to draw. */
    var lastShown: FinishedSeries? = null
}

@Composable
private fun AttemptsCard(result: SeriesResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.result_attempts),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            result.attempts.forEachIndexed { index, attempt ->
                AttemptRow(index + 1, attempt)
            }
        }
    }
}

/** Discarded outliers are struck through and coloured, exactly as the legacy slab showed them. */
@Composable
private fun AttemptRow(number: Int, attempt: Attempt) {
    // A plain contentDescription would replace the Text's own semantics and swallow the
    // number, so the discarded rows carry the milliseconds in their spoken label too.
    val discardedLabel =
        stringResource(R.string.result_attempt_discarded_format, attempt.reactionTimeMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.result_attempt_number, number),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        Text(
            text = stringResource(R.string.ms_format, attempt.reactionTimeMs),
            style = MaterialTheme.typography.bodyLarge,
            color = if (attempt.accepted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
            textDecoration = if (attempt.accepted) null else TextDecoration.LineThrough,
            modifier = Modifier.semantics {
                if (!attempt.accepted) contentDescription = discardedLabel
            },
        )
    }
}

@Composable
private fun SummaryRow(result: SeriesResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SummaryItem(stringResource(R.string.result_std_dev), msWithDecimal(result.stdDev))
        SummaryItem(stringResource(R.string.result_false_starts), result.falseStarts.toString())
        SummaryItem(stringResource(R.string.result_misses), result.misses.toString())
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnlineNote(isPlausible: Boolean, isSignedIn: Boolean, onSignIn: () -> Unit) {
    when {
        !isPlausible -> Text(
            text = stringResource(R.string.result_implausible),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )

        isSignedIn -> Text(
            text = stringResource(R.string.result_submitted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        else -> TextButton(onClick = onSignIn, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.result_sign_in_cta))
        }
    }
}

private fun verdictOf(meanMs: Int): Int = when {
    meanMs < 220 -> R.string.result_verdict_superhuman
    meanMs < 250 -> R.string.result_verdict_lightning
    meanMs < 300 -> R.string.result_verdict_fast
    meanMs < 350 -> R.string.result_verdict_quick
    else -> R.string.result_verdict_practice
}
