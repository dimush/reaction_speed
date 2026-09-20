package org.softosaurus.reactionspeed.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.softosaurus.reactionspeed.ui.game.GameScreen
import org.softosaurus.reactionspeed.ui.home.HomeScreen
import org.softosaurus.reactionspeed.ui.result.ResultScreen
import org.softosaurus.reactionspeed.ui.settings.SettingsScreen
import org.softosaurus.reactionspeed.ui.stats.StatsScreen

private object Routes {
    const val HOME = "home"
    const val GAME = "game"
    const val RESULT = "result"
    const val STATS = "stats"
    const val SETTINGS = "settings"
}

/**
 * The whole navigation graph. Plain string routes: nothing is parameterised, and the one piece of
 * state that has to survive a hop — the finished series — lives in the activity-scoped
 * [GameSessionViewModel] instead of in the back stack.
 *
 * Predictive back is handled by navigation-compose itself; `android:enableOnBackInvokedCallback`
 * in the manifest enables the system side of it.
 */
@Composable
fun ReactionSpeedNavHost() {
    val navController = rememberNavController()
    val session: GameSessionViewModel = viewModel(factory = GameSessionViewModel.Factory)

    fun startGame() {
        session.prepareNewSeries()
        navController.navigate(Routes.GAME)
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStart = ::startGame,
                onOpenStats = { navController.navigate(Routes.STATS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.GAME) {
            GameScreen(
                session = session,
                onFinished = {
                    navController.navigate(Routes.RESULT) {
                        popUpTo(Routes.GAME) { inclusive = true }
                    }
                },
                onAbort = { navController.popBackStack() },
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(
                session = session,
                onAgain = {
                    session.prepareNewSeries()
                    navController.navigate(Routes.GAME) {
                        popUpTo(Routes.RESULT) { inclusive = true }
                    }
                },
                onHome = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.STATS) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
