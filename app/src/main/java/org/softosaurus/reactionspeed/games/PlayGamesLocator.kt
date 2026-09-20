package org.softosaurus.reactionspeed.games

import android.app.Application
import org.softosaurus.reactionspeed.data.ResultsRepository

/**
 * The project has no DI framework, so the single [PlayGamesManager] is reached through this
 * holder. Created once in `Application.onCreate()`:
 *
 * ```kotlin
 * class ReactionSpeedApp : Application() {
 *     lateinit var results: ResultsRepository
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         results = SharedPreferencesResultsRepository(this)
 *         PlayGamesLocator.install(this, results).initialize(this)
 *     }
 * }
 * ```
 *
 * Read it anywhere with `PlayGamesLocator.manager`. The manager holds only the application
 * context, so a process-lifetime singleton leaks nothing.
 */
object PlayGamesLocator {

    @Volatile
    private var instance: PlayGamesManager? = null

    /**
     * Creates the manager (idempotent — a second call returns the existing one, which keeps
     * instrumentation tests and process restarts simple).
     */
    fun install(app: Application, results: ResultsRepository? = null): PlayGamesManager =
        instance ?: synchronized(this) {
            instance ?: PlayGamesManager(app, results).also { instance = it }
        }

    /** The installed manager. Throws if [install] has not run — that is a programming error. */
    val manager: PlayGamesManager
        get() = checkNotNull(instance) {
            "PlayGamesLocator.install() must be called from Application.onCreate()"
        }

    /** The installed manager, or `null` (e.g. in a unit test that never ran `Application`). */
    fun managerOrNull(): PlayGamesManager? = instance
}
