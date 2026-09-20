package org.softosaurus.reactionspeed.games

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.games.AchievementsClient
import com.google.android.gms.games.LeaderboardsClient
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.PlayGamesSdk
import com.google.android.gms.games.leaderboard.LeaderboardScore
import com.google.android.gms.games.leaderboard.LeaderboardVariant
import java.lang.ref.WeakReference
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.data.ResultsRepository
import org.softosaurus.reactionspeed.game.SeriesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sign-in state of Play Games, as the UI needs to see it. */
sealed interface PlayGamesState {

    /** No `game_services_project_id` in `games-ids.xml` — every call is a no-op. */
    data object NotConfigured : PlayGamesState

    /** Configured, but the player is not (or not yet) authenticated. */
    data object SignedOut : PlayGamesState

    /** A sign-in attempt is in flight. */
    data object SigningIn : PlayGamesState

    /** Authenticated. [playerName] is `null` until the player profile has loaded. */
    data class SignedIn(val playerName: String? = null) : PlayGamesState
}

/** The two boards of this game. */
enum class LeaderboardId {
    /** `leaderboard_best_average` — best mean of a ten-tap series. */
    BEST_AVERAGE,

    /** `leaderboard_best_single` — fastest single reaction. */
    BEST_SINGLE,
}

/** One row of a leaderboard, flattened so that no GMS data buffer escapes the manager. */
data class LeaderboardEntry(
    val rank: Long,
    val displayName: String?,
    val scoreMs: Long,
)

/**
 * All Play Games Services v2 usage of the app.
 *
 * ### Graceful-off
 * [isConfigured] is false while `R.string.game_services_project_id` is blank (the checked-in
 * placeholder). In that state [state] is [PlayGamesState.NotConfigured] forever,
 * `PlayGamesSdk.initialize()` is never called, no `PlayGames.get*Client()` is ever touched and
 * every method below returns immediately. Individual leaderboard/achievement ids are checked the
 * same way, so a partially filled export degrades to "submit what is known".
 *
 * ### Lifecycle and activity references
 * The manager holds the *application* context strongly and never holds an Activity strongly:
 * every call that needs one takes it as a parameter, and every GMS listener that has to reach one
 * later captures a [WeakReference] and re-checks `isFinishing`/`isDestroyed` before using it — a
 * GMS task can easily outlive the screen that started it. [onActivityStart] additionally parks a
 * [WeakReference] so the two `suspend` loaders can be called from a ViewModel without threading
 * an Activity through the UI layer; it is cleared in [onActivityStop].
 *
 * ### Showing the Play Games UI
 * The three `show*` methods call [Activity.startActivityForResult] with [RC_PLAY_GAMES_UI]. This
 * is the least awkward option for a single-activity Compose host: the returned result carries
 * nothing the app needs, so an `ActivityResultLauncher` would only force every caller to hoist a
 * launcher and thread it down through composables for no benefit. The deprecation on
 * `startActivityForResult` is irrelevant here because the result is genuinely unused.
 *
 * ### Failure policy
 * Fire-and-forget. `submitScore` / `unlock` / `increment` are the queuing, `void` overloads of
 * the GMS clients — they retry by themselves once connectivity returns. Everything that does
 * return a `Task` is logged on failure and never rethrown; nothing in here may take the game down.
 *
 * Instantiate once from `Application.onCreate()` and reach it through [PlayGamesLocator].
 */
class PlayGamesManager(
    context: Context,
    /**
     * Local results, used once per install to seed the boards of a user migrated from 3.x.
     * May be `null`, in which case that bootstrap is skipped.
     */
    private val results: ResultsRepository? = null,
) {

    private val appContext: Context = context.applicationContext

    private val projectId: String = appContext.getString(R.string.game_services_project_id)

    /** True when `games-ids.xml` carries a real Play Console app id. */
    val isConfigured: Boolean = projectId.isNotBlank()

    private val _state = MutableStateFlow<PlayGamesState>(
        if (isConfigured) PlayGamesState.SignedOut else PlayGamesState.NotConfigured
    )
    val state: StateFlow<PlayGamesState> = _state.asStateFlow()

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var currentActivity: WeakReference<Activity> = WeakReference(null)

    private val isSignedIn: Boolean get() = _state.value is PlayGamesState.SignedIn

    // --- lifecycle ---------------------------------------------------------------------------

    /**
     * Call once from `Application.onCreate()`.
     *
     * PGS v2 also auto-initialises through the `PlayGamesInitProvider` declared by the library
     * manifest, but the documented contract is to call this, and calling it twice is harmless.
     */
    fun initialize(app: Application) {
        if (!isConfigured) return
        runCatching { PlayGamesSdk.initialize(app) }
            .onFailure { Log.w(TAG, "PlayGamesSdk.initialize failed", it) }
    }

    /**
     * Call from the host activity's `onStart()`. PGS v2 signs the player in automatically, so
     * this only *observes* the result; [signIn] is for the explicit button.
     */
    fun onActivityStart(activity: Activity) {
        if (!isConfigured) return
        currentActivity = WeakReference(activity)
        // GMS keeps the completion listener alive until the task resolves, which can outlast the
        // activity; a strong capture would pin a whole destroyed Activity (and its window) until
        // then. The reference is weak and re-checked before any follow-up work touches it.
        val host = WeakReference(activity)
        runCatching {
            PlayGames.getGamesSignInClient(activity).isAuthenticated()
                .addOnCompleteListener { task ->
                    val authenticated = task.isSuccessful && task.result?.isAuthenticated == true
                    if (!task.isSuccessful) {
                        Log.w(TAG, "isAuthenticated failed", task.exception)
                    }
                    resolveOn(host, authenticated)
                }
        }.onFailure { Log.w(TAG, "isAuthenticated threw", it) }
    }

    /**
     * Runs [onAuthenticationResolved] against [host] if it is still a usable Activity. When it is
     * not, the visible state is still kept honest — only the work that genuinely needs an Activity
     * (the player profile, flushing queued scores) is skipped, and the next `onStart` redoes it.
     */
    private fun resolveOn(host: WeakReference<Activity>, authenticated: Boolean) {
        val live = host.live()
        if (live == null) {
            when {
                !authenticated -> _state.value = PlayGamesState.SignedOut
                // Don't clobber a name that a previous resolution already loaded.
                !isSignedIn -> _state.value = PlayGamesState.SignedIn()
            }
            return
        }
        onAuthenticationResolved(live, authenticated)
    }

    private fun WeakReference<Activity>.live(): Activity? =
        get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** Call from the host activity's `onStop()`. */
    fun onActivityStop(activity: Activity) {
        if (currentActivity.get() === activity) currentActivity = WeakReference(null)
    }

    /** Explicit "Sign in" button. Safe to call when already signed in. */
    fun signIn(activity: Activity) {
        signIn(activity, onSuccess = null)
    }

    private fun signIn(activity: Activity, onSuccess: ((Activity) -> Unit)?) {
        if (!isConfigured) return
        if (isSignedIn) {
            onSuccess?.invoke(activity)
            return
        }
        _state.value = PlayGamesState.SigningIn
        // Weak for the same reason as in onActivityStart: the sign-in task can outlive the screen
        // that started it (the user rotates, or backs out while the GMS dialog is up).
        val host = WeakReference(activity)
        runCatching {
            PlayGames.getGamesSignInClient(activity).signIn()
                .addOnCompleteListener { task ->
                    val authenticated = task.isSuccessful && task.result?.isAuthenticated == true
                    if (!task.isSuccessful) Log.w(TAG, "signIn failed", task.exception)
                    resolveOn(host, authenticated)
                    if (authenticated) host.live()?.let { onSuccess?.invoke(it) }
                }
        }.onFailure {
            Log.w(TAG, "signIn threw", it)
            _state.value = PlayGamesState.SignedOut
        }
    }

    private fun onAuthenticationResolved(activity: Activity, authenticated: Boolean) {
        if (!authenticated) {
            _state.value = PlayGamesState.SignedOut
            return
        }
        val wasSignedIn = isSignedIn
        if (!wasSignedIn) _state.value = PlayGamesState.SignedIn()
        loadPlayerName(activity)
        if (!wasSignedIn) flushPending(activity)
    }

    private fun loadPlayerName(activity: Activity) {
        runCatching {
            PlayGames.getPlayersClient(activity).currentPlayer
                .addOnSuccessListener { player ->
                    val current = _state.value
                    if (current is PlayGamesState.SignedIn) {
                        _state.value = PlayGamesState.SignedIn(player?.displayName)
                    }
                }
                .addOnFailureListener { Log.w(TAG, "getCurrentPlayer failed", it) }
        }.onFailure { Log.w(TAG, "getCurrentPlayer threw", it) }
    }

    // --- submitting --------------------------------------------------------------------------

    /**
     * Records a finished series with Play Games.
     *
     * * Scores go up only when [SeriesResult.isPlausible]; when the player is signed out the
     *   plausible values are remembered (best-so-far) and flushed after the next sign-in.
     * * Achievements are evaluated by [AchievementRules], which applies its own anti-cheat gate,
     *   and are only applied while signed in — there is nothing sensible to queue for them.
     *
     * @param seriesCount lifetime series count *after* the result has been stored, i.e.
     *   `repository.seriesCount.value`.
     */
    fun submitResult(activity: Activity, result: SeriesResult, seriesCount: Int) {
        if (!isConfigured) return

        if (!isSignedIn) {
            if (result.isPlausible) {
                writePending(
                    readPending().withSeries(result.filteredMean, result.bestSingle)
                )
            }
            return
        }

        if (result.isPlausible) {
            submitScores(activity, PendingScores(result.filteredMean, result.bestSingle))
        }
        applyAchievements(activity, AchievementRules.evaluate(result, seriesCount))
    }

    /**
     * @return true when at least one score was actually handed to the client. False means the
     *   values are still unsent (no client, or the board ids are missing from `games-ids.xml`)
     *   and the caller must keep them queued.
     */
    private fun submitScores(activity: Activity, scores: PendingScores): Boolean {
        if (scores.isEmpty) return false
        val client = leaderboardsClient(activity) ?: return false
        var submitted = false
        runCatching {
            scores.bestAverageMs?.let { ms ->
                leaderboardId(LeaderboardId.BEST_AVERAGE)?.let {
                    client.submitScore(it, ms.toLong())
                    submitted = true
                }
            }
            scores.bestSingleMs?.let { ms ->
                leaderboardId(LeaderboardId.BEST_SINGLE)?.let {
                    client.submitScore(it, ms.toLong())
                    submitted = true
                }
            }
        }.onFailure { Log.w(TAG, "submitScore failed", it) }
        return submitted
    }

    private fun applyAchievements(activity: Activity, outcome: AchievementOutcome) {
        if (outcome.isEmpty) return
        val client = achievementsClient(activity) ?: return
        runCatching {
            outcome.unlock.forEach { name -> achievementId(name)?.let(client::unlock) }
            outcome.increment.forEach { name ->
                achievementId(name)?.let { client.increment(it, 1) }
            }
        }.onFailure { Log.w(TAG, "achievement update failed", it) }
    }

    /**
     * After the first successful sign-in of a session: push whatever was recorded while signed
     * out, plus — once per install — the locally stored bests, so that a user migrated from 3.x
     * shows up on the boards at all.
     */
    private fun flushPending(activity: Activity) {
        val bootstrapDue = !prefs.getBoolean(KEY_LOCAL_BESTS_SENT, false)
        val local = if (bootstrapDue && results != null) {
            PendingScores.fromLocalBests(
                bestAverageMs = results.top10.value.firstOrNull(),
                bestSingleMs = results.bestSingleMs.value,
            )
        } else {
            PendingScores.EMPTY
        }
        val pending = readPending().withSeries(local.bestAverageMs, local.bestSingleMs)

        if (pending.isEmpty) {
            // Nothing to send. The bootstrap is consumed only if the repository was actually
            // consulted and had nothing credible to offer — otherwise a manager built without a
            // repository would burn the one-shot flag and a migrated user would never be seeded.
            if (bootstrapDue && results != null) markBootstrapDone()
            return
        }
        // Keep everything queued when the submission could not be issued at all (no client, or
        // an export that does not carry the board ids yet).
        if (!submitScores(activity, pending)) return
        writePending(PendingScores.EMPTY)
        if (bootstrapDue && results != null) markBootstrapDone()
    }

    private fun markBootstrapDone() {
        prefs.edit { putBoolean(KEY_LOCAL_BESTS_SENT, true) }
    }

    private fun readPending() = PendingScores(
        bestAverageMs = prefs.getInt(KEY_PENDING_AVERAGE, 0).takeIf { it > 0 },
        bestSingleMs = prefs.getInt(KEY_PENDING_SINGLE, 0).takeIf { it > 0 },
    )

    private fun writePending(scores: PendingScores) {
        prefs.edit {
            putInt(KEY_PENDING_AVERAGE, scores.bestAverageMs ?: 0)
            putInt(KEY_PENDING_SINGLE, scores.bestSingleMs ?: 0)
        }
    }

    // --- native UI ---------------------------------------------------------------------------

    /** Opens the native "all leaderboards" screen, signing in first if necessary. */
    fun showLeaderboards(activity: Activity) = withSignIn(activity) { host ->
        leaderboardsClient(host)?.allLeaderboardsIntent?.startWhenReady(host, "allLeaderboards")
    }

    /** Opens one native leaderboard screen, signing in first if necessary. */
    fun showLeaderboard(activity: Activity, which: LeaderboardId) = withSignIn(activity) { host ->
        val id = leaderboardId(which) ?: return@withSignIn
        leaderboardsClient(host)?.getLeaderboardIntent(id)?.startWhenReady(host, "leaderboard")
    }

    /** Opens the native achievements screen, signing in first if necessary. */
    fun showAchievements(activity: Activity) = withSignIn(activity) { host ->
        achievementsClient(host)?.achievementsIntent?.startWhenReady(host, "achievements")
    }

    private inline fun withSignIn(activity: Activity, crossinline block: (Activity) -> Unit) {
        if (!isConfigured) return
        if (isSignedIn) block(activity) else signIn(activity) { host -> block(host) }
    }

    private fun com.google.android.gms.tasks.Task<android.content.Intent>.startWhenReady(
        activity: Activity,
        what: String,
    ) {
        addOnSuccessListener { intent ->
            if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
            runCatching { activity.startActivityForResult(intent, RC_PLAY_GAMES_UI) }
                .onFailure { Log.w(TAG, "cannot start $what UI", it) }
        }
        addOnFailureListener { Log.w(TAG, "cannot obtain $what intent", it) }
    }

    // --- reading -----------------------------------------------------------------------------

    /**
     * The signed-in player's own row on `leaderboard_best_average` (all time, public), for the
     * home screen. Returns `null` when not configured, signed out, without a score yet, or on any
     * error — this is decoration, not a feature that may fail loudly.
     *
     * Uses the Activity parked by [onActivityStart]; call it while the host activity is started.
     */
    suspend fun loadMyBestAverage(): LeaderboardEntry? {
        val activity = readyActivity() ?: return null
        val client = leaderboardsClient(activity) ?: return null
        val id = leaderboardId(LeaderboardId.BEST_AVERAGE) ?: return null
        return runCatching {
            client.loadCurrentPlayerLeaderboardScore(
                id,
                LeaderboardVariant.TIME_SPAN_ALL_TIME,
                LeaderboardVariant.COLLECTION_PUBLIC,
            ).awaitResult().get()?.toEntry()
        }.getOrElse {
            Log.w(TAG, "loadCurrentPlayerLeaderboardScore failed", it)
            null
        }
    }

    /**
     * Top rows of `leaderboard_best_average` (all time, public) so the app can render its own
     * "World top" list. Returns an empty list on any error.
     *
     * @param limit 1..25, as required by the GMS API.
     */
    suspend fun loadTopScores(limit: Int = 10): List<LeaderboardEntry> {
        val activity = readyActivity() ?: return emptyList()
        val client = leaderboardsClient(activity) ?: return emptyList()
        val id = leaderboardId(LeaderboardId.BEST_AVERAGE) ?: return emptyList()
        return runCatching {
            val scores = client.loadTopScores(
                id,
                LeaderboardVariant.TIME_SPAN_ALL_TIME,
                LeaderboardVariant.COLLECTION_PUBLIC,
                limit.coerceIn(1, MAX_SCORES_PER_PAGE),
            ).awaitResult().get() ?: return emptyList()
            // The buffer is backed by a cursor: copy out, then release. Never let it escape.
            try {
                scores.scores.map { it.toEntry() }
            } finally {
                scores.release()
            }
        }.getOrElse {
            Log.w(TAG, "loadTopScores failed", it)
            emptyList()
        }
    }

    private fun readyActivity(): Activity? {
        if (!isConfigured || !isSignedIn) return null
        return currentActivity.live()
    }

    private fun LeaderboardScore.toEntry() = LeaderboardEntry(
        rank = rank,
        displayName = scoreHolderDisplayName,
        scoreMs = rawScore,
    )

    // --- id plumbing -------------------------------------------------------------------------

    private fun leaderboardsClient(activity: Activity): LeaderboardsClient? =
        runCatching { PlayGames.getLeaderboardsClient(activity) }
            .onFailure { Log.w(TAG, "no leaderboards client", it) }
            .getOrNull()

    private fun achievementsClient(activity: Activity): AchievementsClient? =
        runCatching { PlayGames.getAchievementsClient(activity) }
            .onFailure { Log.w(TAG, "no achievements client", it) }
            .getOrNull()

    private fun leaderboardId(which: LeaderboardId): String? = when (which) {
        LeaderboardId.BEST_AVERAGE -> appContext.getString(R.string.leaderboard_best_average)
        LeaderboardId.BEST_SINGLE -> appContext.getString(R.string.leaderboard_best_single)
    }.takeIf { it.isNotBlank() }

    /**
     * Maps an [AchievementIds] resource *name* to the id exported by Play Console. An explicit
     * `when` rather than `Resources.getIdentifier()`: reflection-by-name does not survive
     * resource shrinking and hides typos until runtime.
     */
    private fun achievementId(resourceName: String): String? = when (resourceName) {
        AchievementIds.FIRST_SERIES -> appContext.getString(R.string.achievement_first_series)
        AchievementIds.UNDER_350 -> appContext.getString(R.string.achievement_under_350)
        AchievementIds.UNDER_300 -> appContext.getString(R.string.achievement_under_300)
        AchievementIds.UNDER_250 -> appContext.getString(R.string.achievement_under_250)
        AchievementIds.UNDER_220 -> appContext.getString(R.string.achievement_under_220)
        AchievementIds.SERIES_10 -> appContext.getString(R.string.achievement_series_10)
        AchievementIds.SERIES_50 -> appContext.getString(R.string.achievement_series_50)
        AchievementIds.SERIES_200 -> appContext.getString(R.string.achievement_series_200)
        AchievementIds.STEADY_HAND -> appContext.getString(R.string.achievement_steady_hand)
        AchievementIds.FLAWLESS -> appContext.getString(R.string.achievement_flawless)
        else -> null
    }?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "PlayGames"

        /** Arbitrary; the result of the native Play Games screens is never used. */
        const val RC_PLAY_GAMES_UI = 0x9001

        /** GMS caps one page of leaderboard scores at 25. */
        const val MAX_SCORES_PER_PAGE = 25

        /** Tiny private file, unrelated to the game's own preferences. */
        const val PREFS_NAME = "play_games"
        const val KEY_PENDING_AVERAGE = "pending_best_average_ms"
        const val KEY_PENDING_SINGLE = "pending_best_single_ms"
        const val KEY_LOCAL_BESTS_SENT = "local_bests_sent"
    }
}
