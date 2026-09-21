package org.softosaurus.reactionspeed

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.softosaurus.reactionspeed.audio.AppAudio
import org.softosaurus.reactionspeed.data.ResultsRepository
import org.softosaurus.reactionspeed.data.SharedPreferencesResultsRepository
import org.softosaurus.reactionspeed.games.PlayGamesLocator

/**
 * Process-wide object graph. There is no DI framework: the long-lived collaborators are created
 * here and reached from the UI through [resultsRepository], [appAudio] and [PlayGamesLocator].
 */
class ReactionSpeedApp : Application() {

    /**
     * Created eagerly because its constructor performs the one-time migration of the legacy 3.x
     * preferences, and every screen needs it immediately anyway.
     */
    lateinit var results: ResultsRepository
        private set

    /**
     * The one sound pool and the one music player of the process.
     *
     * Owned by the application rather than by a screen because the playfield, the home mascot and
     * every button share them: a second `SoundPool` would double the memory and split the stream
     * budget, and a music player tied to a composable would restart the loop on every rotation.
     */
    lateinit var audio: AppAudio
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        results = SharedPreferencesResultsRepository(this)
        audio = AppAudio(this)
        // One subscription for the whole process: every screen that flips a switch reaches the
        // sound layer through the repository, so nothing has to remember to notify it.
        scope.launch {
            results.settings.collect { audio.applySettings(it) }
        }
        audio.playMenuMusic()
        PlayGamesLocator.install(this, results).initialize(this)
    }
}

/** The app-wide results store, for `ViewModel`s constructed with an `AndroidViewModel`-style context. */
val Application.resultsRepository: ResultsRepository
    get() = (this as ReactionSpeedApp).results

/** The app-wide sound layer. */
val Application.appAudio: AppAudio
    get() = (this as ReactionSpeedApp).audio
