package org.softosaurus.reactionspeed

import android.app.Application
import org.softosaurus.reactionspeed.data.ResultsRepository
import org.softosaurus.reactionspeed.data.SharedPreferencesResultsRepository
import org.softosaurus.reactionspeed.games.PlayGamesLocator

/**
 * Process-wide object graph. There is no DI framework: the two long-lived collaborators are
 * created here and reached from the UI through [resultsRepository] and [PlayGamesLocator].
 */
class ReactionSpeedApp : Application() {

    /**
     * Created eagerly because its constructor performs the one-time migration of the legacy 3.x
     * preferences, and every screen needs it immediately anyway.
     */
    lateinit var results: ResultsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        results = SharedPreferencesResultsRepository(this)
        PlayGamesLocator.install(this, results).initialize(this)
    }
}

/** The app-wide results store, for `ViewModel`s constructed with an `AndroidViewModel`-style context. */
val Application.resultsRepository: ResultsRepository
    get() = (this as ReactionSpeedApp).results
