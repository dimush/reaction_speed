package org.softosaurus.reactionspeed.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.ads.adaptiveBannerHeightDp
import org.softosaurus.reactionspeed.game.GameView
import org.softosaurus.reactionspeed.game.SeriesResult
import org.softosaurus.reactionspeed.ui.GameSessionViewModel
import org.softosaurus.reactionspeed.ui.common.rememberResults

/**
 * Full-screen playfield. No ad banner, no chrome — the series starts the moment the screen is up
 * (the engine's own random 0.5–3.5 s pause is the only countdown the game needs).
 *
 * Back during a series aborts it and returns Home. An abort that the *system* caused — a pause, a
 * phone call, the surface going away — puts a "tap to start" overlay on the playfield rather than
 * silently restarting: coming back to the app to find a series already counting down costs the
 * player their first reaction, and a restart nobody asked for is indistinguishable from a bug.
 *
 * Although this screen shows no banner, it still keeps the band where the banner sits on every
 * other screen free of targets, so that a tap aimed at the tenth target cannot carry over onto the
 * ad that the result screen puts in the same place.
 */
@Composable
fun GameScreen(
    session: GameSessionViewModel,
    onFinished: () -> Unit,
    onAbort: () -> Unit,
) {
    val context = LocalContext.current
    val results = rememberResults()
    val settings by results.settings.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val layoutDirection = LocalLayoutDirection.current
    val gameView = remember(context) { GameView(context) }

    // The listener is installed once and must never capture a stale navigation lambda.
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnAbort by rememberUpdatedState(onAbort)
    val flow = remember { GameFlow() }

    /** Set by a system-caused abort; the overlay is the only way back into a series after one. */
    var awaitingTap by remember { mutableStateOf(false) }

    DisposableEffect(gameView) {
        gameView.listener = object : GameView.Listener {
            override fun onSeriesFinished(result: SeriesResult) {
                if (flow.leaving) return
                flow.leaving = true
                session.onSeriesFinished(result)
                currentOnFinished()
            }

            override fun onSeriesAborted() {
                // Only the system can get here: a user abort navigates away by itself.
                if (!flow.leaving) awaitingTap = true
            }
        }
        onDispose { gameView.listener = null }
    }

    // GameView wants pixels; WindowInsets hands them over directly, unlike asPaddingValues().
    val density = LocalDensity.current
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val left = safeInsets.getLeft(density, layoutDirection)
    val top = safeInsets.getTop(density)
    val right = safeInsets.getRight(density, layoutDirection)
    // The navigation-bar inset plus the strip the ad banner occupies on the result screen: the
    // tenth target must never spawn where the banner is about to be drawn.
    val bannerHeightPx = with(density) { adaptiveBannerHeightDp(context).dp.roundToPx() }
    val bottom = safeInsets.getBottom(density) + bannerHeightPx

    LaunchedEffect(gameView, left, top, right, bottom) {
        gameView.setSafeInsets(left, top, right, bottom)
    }

    LaunchedEffect(gameView) {
        gameView.startSeries()
    }

    DisposableEffect(lifecycleOwner, gameView) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            gameView.onResume()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Deliberately no restart here: the overlay waits for the player instead.
                Lifecycle.Event.ON_RESUME -> gameView.onResume()
                Lifecycle.Event.ON_PAUSE -> gameView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gameView.onPause()
            gameView.release()
        }
    }

    BackHandler {
        flow.leaving = true
        gameView.abortSeries()
        currentOnAbort()
    }

    val description = stringResource(R.string.game_screen_description)
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { gameView },
            update = { it.settings = settings },
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = description },
        )
        if (awaitingTap) {
            TapToStartOverlay(
                onStart = {
                    awaitingTap = false
                    gameView.startSeries()
                },
            )
        }
    }
}

/**
 * Covers the playfield after a system-caused abort. It swallows the tap that dismisses it, so the
 * same gesture cannot also reach the game underneath and register as a false start.
 */
@Composable
private fun TapToStartOverlay(onStart: () -> Unit) {
    val label = stringResource(R.string.game_tap_to_start)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClickLabel = label, onClick = onStart)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Mutable, non-observable flags of one visit to the game screen. Deliberately not Compose state:
 * they are read and written from callbacks, never rendered, and a recomposition on every change
 * would be pure waste. (The "tap to start" flag *is* rendered, so it lives in Compose state.)
 */
private class GameFlow {
    /** The user is on their way out; further callbacks must not navigate again. */
    var leaving = false
}
