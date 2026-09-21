package org.softosaurus.reactionspeed.audio

import android.content.Context
import kotlinx.coroutines.delay
import org.softosaurus.reactionspeed.ReactionSpeedApp
import org.softosaurus.reactionspeed.data.GameSettings

/**
 * The process-wide sound layer: one [SoundBank] and one [MusicPlayer], created by
 * [ReactionSpeedApp] and handed to everything that makes a noise.
 *
 * Having exactly one of each matters: the playfield used to own a private `SoundPool`, so a second
 * one would have appeared the moment the Compose screens started clicking. Both collaborators are
 * cheap to hold and expensive to duplicate.
 *
 * Lifecycle is driven by the activity ([onStart] / [onStop]), never by a composable: a rotation
 * recreates every composable but not the activity's start state, and restarting the loops there
 * would stutter the music on every configuration change.
 */
class AppAudio(context: Context) {

    val sounds = SoundBank(context)
    val music = MusicPlayer(context)

    init {
        // A spoken line dips the music for as long as it actually lasts — the duration is measured
        // from the WAV header at load time, never hardcoded.
        sounds.ducker = { durationMs -> music.duck(durationMs) }
    }

    /** Keeps both collaborators in step with the user's preferences. */
    fun applySettings(settings: GameSettings) {
        sounds.settings = settings
        music.setEnabled(settings.music)
    }

    fun onStart() = music.onStart()

    fun onStop() = music.onStop()

    /** Switches to the game loop; the menu loop fades out under it. */
    fun playGameMusic() = music.request(MusicPlayer.Track.GAME)

    /** Switches back to the menu loop, used by every screen that is not the playfield. */
    fun playMenuMusic() = music.request(MusicPlayer.Track.MENU)

    /**
     * The result-screen fanfare: tier jingle, then — on a personal best — the record sting and its
     * spoken line, then the spoken verdict.
     *
     * The waits come from the measured sample lengths, so nothing overlaps and nothing assumes a
     * duration that the next regeneration of `tool/audio` would invalidate. A disabled group
     * reports a length of 0, which simply collapses its step: with effects off and voice on, the
     * verdict line is spoken immediately.
     *
     * Must be called **once** per finished series; [org.softosaurus.reactionspeed.ui.GameSessionViewModel]
     * owns that guard, because a recomposition, a rotation or a return from the background must not
     * replay it.
     */
    suspend fun playResultFanfare(tier: Int, isNewPersonalBest: Boolean) {
        val jingle = sounds.play(Sound.jingleForTier(tier))
        if (jingle > 0L) delay(jingle - JINGLE_TAIL_OVERLAP_MS.coerceAtMost(jingle))

        if (isNewPersonalBest) {
            val sting = sounds.play(Sound.NEW_RECORD)
            if (sting > 0L) delay(sting)
            val recordLine = sounds.playVoice(Sound.VOICE_NEW_RECORD)
            if (recordLine > 0L) delay(recordLine + VOICE_GAP_MS)
        }

        sounds.playVoice(Sound.voiceForTier(tier))
    }

    fun release() {
        sounds.release()
        music.release()
    }

    private companion object {
        /**
         * The verdict may start slightly inside the jingle's reverb tail — it sounds like one
         * celebration instead of two events — but never inside its musical body.
         */
        const val JINGLE_TAIL_OVERLAP_MS = 180L

        /** Breath between two spoken lines. */
        const val VOICE_GAP_MS = 120L
    }
}
