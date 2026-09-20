package org.softosaurus.reactionspeed.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.softosaurus.reactionspeed.R

/** The three cues the playfield makes. */
enum class GameSound {
    /** The target just appeared — the legacy "metal" tick, played very quietly. */
    TARGET,

    /** The target was hit — "drip". */
    HIT,

    /** The series is over — a stone slab sliding into place. */
    FINISHED,
}

/**
 * Small [SoundPool] wrapper for the playfield.
 *
 * `SoundPool.load` is asynchronous: an id is returned immediately but playing it before the sample
 * is decoded is a silent no-op (and logs an error), which is why loaded ids are tracked and unloaded
 * ones are skipped.
 *
 * [play] may be called from the render thread; [release] and [ensureLoaded] from the main thread.
 */
class GameAudio(context: Context) {

    private val appContext = context.applicationContext
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private var pool: SoundPool? = null
    private val sampleIds = HashMap<GameSound, Int>()

    init {
        ensureLoaded()
    }

    /** Recreates the pool after a [release] (e.g. when the view is re-attached to a window). */
    fun ensureLoaded() {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attributes)
            .build()
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId) else Log.w(TAG, "sample $sampleId failed to load")
        }
        sampleIds.clear()
        loaded.clear()
        sampleIds[GameSound.TARGET] = sp.load(appContext, R.raw.metal, 1)
        sampleIds[GameSound.HIT] = sp.load(appContext, R.raw.drip, 1)
        sampleIds[GameSound.FINISHED] = sp.load(appContext, R.raw.stone2, 1)
        pool = sp
    }

    /**
     * Plays [sound] at [volume] (0..1). Does nothing when the pool is released or the sample is
     * still decoding.
     */
    fun play(sound: GameSound, volume: Float) {
        val sp = pool ?: return
        val id = sampleIds[sound] ?: return
        if (id == 0 || !loaded.contains(id)) return
        try {
            sp.play(id, volume, volume, 1, 0, 1f)
        } catch (e: IllegalStateException) {
            // The pool was released concurrently by the main thread; nothing to do.
            Log.d(TAG, "play() on a released pool", e)
        }
    }

    /** Frees the native pool. Safe to call more than once. */
    fun release() {
        pool?.release()
        pool = null
        loaded.clear()
        sampleIds.clear()
    }

    companion object {
        private const val TAG = "GameAudio"
        private const val MAX_STREAMS = 4

        /** Legacy volume of the target tick: deliberately quiet, it is a cue and not an event. */
        const val VOLUME_TARGET = 0.1f

        /** Legacy volume of the hit and finish samples. */
        const val VOLUME_LOUD = 0.7f
    }
}
