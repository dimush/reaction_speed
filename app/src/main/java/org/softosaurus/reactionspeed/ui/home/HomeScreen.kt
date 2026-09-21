package org.softosaurus.reactionspeed.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.audio.Sound
import org.softosaurus.reactionspeed.ui.common.BannerScaffold
import org.softosaurus.reactionspeed.ui.common.PlayfulCornerShape
import org.softosaurus.reactionspeed.ui.common.Sparkline
import org.softosaurus.reactionspeed.ui.common.msOrDash
import org.softosaurus.reactionspeed.ui.common.rememberAppAudio
import org.softosaurus.reactionspeed.ui.common.rememberClickSound

@Composable
fun HomeScreen(
    onStart: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audio = rememberAppAudio()
    val click = rememberClickSound()

    BannerScaffold {
        // The content is centred in the window but still scrolls on short screens: the minimum
        // height pins the column to the viewport, so Arrangement.Center has something to work with.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MeadowHeader()

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        click()
                        // Non-blocking by construction: SoundPool.play() returns immediately and
                        // the navigation happens on this same frame whatever the sound does.
                        audio.sounds.playVoice(Sound.VOICE_READY)
                        onStart()
                    },
                    shape = PlayfulCornerShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_start),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                Spacer(Modifier.height(20.dp))

                if (state.isFirstTime) {
                    HowToPlayCard()
                } else {
                    PersonalBestCard(state)
                }

                if (viewModel.isOnlineAvailable) {
                    Spacer(Modifier.height(16.dp))
                    OnlineCard(viewModel)
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { click(); onOpenStats() },
                        shape = PlayfulCornerShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                    ) {
                        Text(stringResource(R.string.home_stats))
                    }
                    OutlinedButton(
                        onClick = { click(); onOpenSettings() },
                        shape = PlayfulCornerShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                    ) {
                        Text(stringResource(R.string.home_settings))
                    }
                }
            }
        }
    }
}

/**
 * Title, tagline and the mascot on a patch of the same meadow the playfield is made of.
 *
 * The grass is the real tile, repeated with an [ImageShader] rather than stretched, and then veiled
 * by a scrim in the theme's own background colour: at 78 % it reads as a texture in the light theme
 * and as a dark lawn at night, and the title keeps its contrast in both. The tile is deliberately
 * the low-contrast, toned-down one the art pipeline produced — it is a backdrop, and the thing that
 * must pop on this screen is the Start button.
 */
@Composable
private fun MeadowHeader() {
    val tile = ImageBitmap.imageResource(R.drawable.grass_tile)
    val brush = remember(tile) {
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
    val audio = rememberAppAudio()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(brush),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeMascot(audio = audio, modifier = Modifier.size(148.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HowToPlayCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = PlayfulCornerShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(
            text = stringResource(R.string.home_how_to_play),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun PersonalBestCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth(), shape = PlayfulCornerShape) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatColumn(
                    label = stringResource(R.string.home_best_average),
                    value = msOrDash(state.bestAverageMs),
                    emphasised = true,
                )
                StatColumn(
                    label = stringResource(R.string.home_best_single),
                    value = msOrDash(state.bestSingleMs),
                )
                StatColumn(
                    label = stringResource(R.string.home_series),
                    value = state.seriesCount.toString(),
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_last_result),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = msOrDash(state.lastResultMs),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Sparkline(
                    values = state.recent,
                    contentDescription = stringResource(R.string.home_history_sparkline),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                )
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, emphasised: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = if (emphasised) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            fontWeight = if (emphasised) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (emphasised) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
