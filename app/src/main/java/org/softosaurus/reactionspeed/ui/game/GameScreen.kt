package org.softosaurus.reactionspeed.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.game.GameView
import org.softosaurus.reactionspeed.game.SeriesResult
import org.softosaurus.reactionspeed.ui.GameSessionViewModel
import org.softosaurus.reactionspeed.ui.common.rememberResults

/**
 * Full-screen playfield. No ad banner, no chrome — the series starts the moment the screen is up
 * (the engine's own random 0.5–3.5 s pause is the only countdown the game needs).
 *
 * Back during a series aborts it and returns Home. An abort that the *system* caused — a pause, a
 * phone call, the surface going away — is not a navigation event: the series is simply restarted
 * when the screen comes back.
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
                if (!flow.leaving) flow.restartPending = true
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
    val bottom = safeInsets.getBottom(density)

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
                Lifecycle.Event.ON_RESUME -> {
                    gameView.onResume()
                    if (flow.restartPending && !flow.leaving) {
                        flow.restartPending = false
                        gameView.startSeries()
                    }
                }

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
    AndroidView(
        factory = { gameView },
        update = { it.settings = settings },
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description },
    )
}

/**
 * Mutable, non-observable flags of one visit to the game screen. Deliberately not Compose state:
 * they are read and written from callbacks, never rendered, and a recomposition on every change
 * would be pure waste.
 */
private class GameFlow {
    /** The user is on their way out; further callbacks must not navigate again. */
    var leaving = false

    /** A system-caused abort is waiting for the screen to come back. */
    var restartPending = false
}
