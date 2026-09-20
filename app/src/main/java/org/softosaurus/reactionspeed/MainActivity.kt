package org.softosaurus.reactionspeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.softosaurus.reactionspeed.ads.AdsManager
import org.softosaurus.reactionspeed.games.PlayGamesLocator
import org.softosaurus.reactionspeed.ui.ReactionSpeedNavHost
import org.softosaurus.reactionspeed.ui.theme.ReactionSpeedTheme

/**
 * The single activity. It owns nothing but the window: all state lives in the repository and in
 * the `ViewModel`s scoped to this activity.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // UMP consent first, Mobile Ads init once consent allows it. Idempotent per process.
        AdsManager.start(this)

        setContent {
            ReactionSpeedTheme {
                ReactionSpeedNavHost()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Idempotent: a no-op unless a previous consent attempt genuinely failed, in which case
        // this is the retry that gets the process its ads back.
        AdsManager.start(this)
        PlayGamesLocator.managerOrNull()?.onActivityStart(this)
    }

    override fun onStop() {
        PlayGamesLocator.managerOrNull()?.onActivityStop(this)
        super.onStop()
    }
}
