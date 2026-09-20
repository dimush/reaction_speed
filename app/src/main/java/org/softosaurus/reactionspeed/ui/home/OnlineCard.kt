package org.softosaurus.reactionspeed.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.games.LeaderboardEntry
import org.softosaurus.reactionspeed.games.PlayGamesLocator
import org.softosaurus.reactionspeed.games.PlayGamesState
import org.softosaurus.reactionspeed.ui.common.rememberActivity

/**
 * The Play Games section of the home screen.
 *
 * Only composed when `games-ids.xml` carries a real project id — with the checked-in placeholder
 * the whole section is absent rather than disabled, so the app never advertises a feature the
 * build cannot deliver.
 */
@Composable
fun OnlineCard(viewModel: HomeViewModel) {
    val activity = rememberActivity() ?: return
    val manager = PlayGamesLocator.managerOrNull() ?: return
    val state by viewModel.playGamesState.collectAsStateWithLifecycle()
    val worldTop by viewModel.worldTop.collectAsStateWithLifecycle()
    val myRank by viewModel.myRank.collectAsStateWithLifecycle()

    // The leaderboard loaders need a started activity, which is exactly the resumed window.
    LifecycleResumeEffect(state) {
        if (state is PlayGamesState.SignedIn) viewModel.refreshOnline()
        onPauseOrDispose { }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.online_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))

            when (val current = state) {
                is PlayGamesState.SignedIn -> {
                    current.playerName?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        text = myRank?.let { stringResource(R.string.online_your_rank, it) }
                            ?: stringResource(R.string.online_no_rank),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                PlayGamesState.SigningIn -> Text(
                    text = stringResource(R.string.online_signing_in),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    Text(
                        text = stringResource(R.string.online_signed_out),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { manager.signIn(activity) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.online_sign_in))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { manager.showLeaderboards(activity) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.online_leaderboards))
                }
                OutlinedButton(
                    onClick = { manager.showAchievements(activity) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.online_achievements))
                }
            }

            if (state is PlayGamesState.SignedIn) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.online_top_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                WorldTopList(worldTop)
            }
        }
    }
}

@Composable
private fun WorldTopList(worldTop: WorldTop) {
    when (worldTop) {
        WorldTop.Idle -> Unit

        WorldTop.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.online_top_loading),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        WorldTop.Empty -> Text(
            text = stringResource(R.string.online_top_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is WorldTop.Content -> Column {
            worldTop.entries.forEach { WorldTopRow(it) }
        }
    }
}

@Composable
private fun WorldTopRow(entry: LeaderboardEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.online_rank_format, entry.rank.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 32.dp, height = 20.dp),
        )
        Text(
            text = entry.displayName ?: stringResource(R.string.online_unknown_player),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.ms_format, entry.scoreMs.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
