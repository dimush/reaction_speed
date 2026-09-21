package org.softosaurus.reactionspeed.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.softosaurus.reactionspeed.R

/**
 * The two background loops, played gaplessly.
 *
 * ### Why `AudioTrack` and not `MediaPlayer`
 * `tool/audio/README.md` is emphatic about this: both loops are written into a buffer of exactly
 * `bars × 4 × 60/BPM × sr` samples with wrap-around note placement, so the loop point is click-free
 * *by construction*. `MediaPlayer.setLooping(true)` is not gapless on Android and would insert an
 * audible glitch at exactly that boundary, throwing the sample-accurate construction away.
 * `AudioTrack` in [AudioTrack.MODE_STATIC] with `setLoopPoints(0, frameCount, -1)` loops in
 * hardware, sample-accurately. The decoded buffers are only ~1.3 MB each.
 *
 * Music also gets its own attributes — `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`, not the
 * `USAGE_GAME`/`CONTENT_TYPE_SONIFICATION` of [SoundBank] — so the system treats it as music and
 * the player's own effect cues stay in the game stream.
 *
 * ### Threading
 * Every public method is main-thread only and returns immediately; decoding and the volume ramp run
 * on a private worker thread. Nothing here blocks the caller.
 *
 * ### Volume
 * The audible level is `base × fade × duck × focus`:
 * * **fade** ramps over [CROSSFADE_MS] when [request] switches loops, so the menu theme melts into
 *   the game theme instead of cutting;
 * * **duck** dips the music while a voice line speaks (see [duck]);
 * * **focus** drops to silence when another app takes audio focus.
 */
class MusicPlayer(context: Context) {

    /** The two loops. */
    enum class Track(val resId: Int) {
        MENU(R.raw.music_menu),
        GAME(R.raw.music_game),
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val workerThread = HandlerThread("ReactionSpeed-Music").apply { start() }
    private val worker = Handler(workerThread.looper)

    private val tracks = HashMap<Track, AudioTrack?>()

    /** Per-track fade level, 0..1. Worker thread only. */
    private val levels = HashMap<Track, Float>()

    /** The loop that should be audible, or `null` for silence. Worker thread only. */
    private var desired: Track? = null

    private var started = false
    private var enabled = true
    private var focusLost = false
    private var released = false

    /** `uptimeMillis` until which the music stays ducked; 0 = not ducked. Worker thread only. */
    private var duckUntilMs = 0L

    private var ramping = false

    private var focusRequest: Any? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Delivered on the main thread; hand the decision to the worker like everything else.
        worker.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> focusLost = true

                AudioManager.AUDIOFOCUS_GAIN -> focusLost = false
                else -> Unit
            }
            applyState()
        }
    }

    // --- public API (main thread) ---------------------------------------------------------------

    /** Mirrors the "Music" preference. Turning it off stops the loops and frees audio focus. */
    fun setEnabled(enabled: Boolean) = worker.post {
        if (this.enabled == enabled) return@post
        this.enabled = enabled
        applyState()
    }

    /** Asks for [track] to be the audible loop; the switch is a [CROSSFADE_MS] crossfade. */
    fun request(track: Track) = worker.post {
        if (desired == track) return@post
        desired = track
        applyState()
    }

    /** Call from the host's `ON_START`. */
    fun onStart() = worker.post {
        started = true
        applyState()
    }

    /** Call from the host's `ON_STOP`: the loops pause where they are and audio focus is given up. */
    fun onStop() = worker.post {
        started = false
        applyState()
    }

    /**
     * Dips the music for [durationMs] so a voice line can be heard over it. Overlapping calls
     * extend the dip rather than shortening it.
     */
    fun duck(durationMs: Long) = worker.post {
        if (durationMs <= 0L) return@post
        val until = SystemClock.uptimeMillis() + durationMs + DUCK_RELEASE_MS
        if (until > duckUntilMs) duckUntilMs = until
        ensureRamping()
    }

    /** Stops everything and frees the native tracks. */
    fun release() {
        worker.post {
            released = true
            abandonFocus()
            for (track in tracks.values) {
                try {
                    track?.pause()
                    track?.flush()
                    track?.release()
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "track already gone", e)
                }
            }
            tracks.clear()
            workerThread.quitSafely()
        }
    }

    // --- worker thread --------------------------------------------------------------------------

    /** Brings every track in line with the desired state and starts the ramp if anything moves. */
    private fun applyState() {
        if (released) return
        val target = if (enabled && started && !focusLost) desired else null
        if (target != null) {
            requestFocus()
            ensureLoaded(target)
        } else if (!started || !enabled) {
            abandonFocus()
        }
        ensureRamping()
        // Run one step at once so an ON_STOP silences the music without waiting for a tick.
        rampStep()
    }

    private fun ensureRamping() {
        if (ramping || released) return
        ramping = true
        worker.postDelayed(rampTick, RAMP_INTERVAL_MS)
    }

    private val rampTick = object : Runnable {
        override fun run() {
            ramping = false
            if (released) return
            if (rampStep()) ensureRamping()
        }
    }

    /**
     * Moves every track one step towards its target level and applies the current duck.
     *
     * @return true while something is still moving and another tick is needed
     */
    private fun rampStep(): Boolean {
        val audible = if (enabled && started && !focusLost) desired else null
        val now = SystemClock.uptimeMillis()
        val ducked = duckUntilMs > now
        if (!ducked) duckUntilMs = 0L

        var busy = ducked
        val step = RAMP_INTERVAL_MS.toFloat() / CROSSFADE_MS
        for ((track, audioTrack) in tracks) {
            if (audioTrack == null) continue
            val goal = if (track == audible) 1f else 0f
            var level = levels[track] ?: 0f
            if (level != goal) {
                level = if (level < goal) minOf(goal, level + step) else maxOf(goal, level - step)
                levels[track] = level
                busy = true
            }
            applyVolume(track, audioTrack, level, ducked)
        }
        return busy
    }

    private fun applyVolume(track: Track, audioTrack: AudioTrack, level: Float, ducked: Boolean) {
        val volume = BASE_VOLUME * level * (if (ducked) DUCK_FACTOR else 1f)
        try {
            audioTrack.setVolume(volume.coerceIn(0f, 1f))
            if (level <= 0f) {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
            } else if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot update $track", e)
        }
    }

    /**
     * Decodes [track] into a static [AudioTrack] and arms the hardware loop. Called on the worker,
     * so the ~1.3 MB read never touches the main thread.
     */
    private fun ensureLoaded(track: Track) {
        if (tracks.containsKey(track)) return
        tracks[track] = try {
            buildTrack(track)
        } catch (e: Exception) {
            Log.w(TAG, "cannot prepare $track", e)
            null
        }
        levels.putIfAbsent(track, 0f)
    }

    private fun buildTrack(track: Track): AudioTrack? {
        val (format, pcm) = appContext.resources.openRawResource(track.resId).buffered().use {
            Wav.readPcm(it)
        } ?: run {
            Log.w(TAG, "${track.name} is not a PCM WAV")
            return null
        }
        if (format.bitsPerSample != 16 || format.frameCount <= 0) {
            Log.w(TAG, "${track.name} is not 16-bit PCM")
            return null
        }

        val channelMask =
            if (format.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        val written = audioTrack.write(pcm, 0, pcm.size)
        if (written < pcm.size) {
            Log.w(TAG, "${track.name}: wrote $written of ${pcm.size} bytes")
            audioTrack.release()
            return null
        }
        audioTrack.setVolume(0f)
        // The whole buffer, forever: the wrap point is click-free by construction, so no fade.
        val result = audioTrack.setLoopPoints(0, format.frameCount, -1)
        if (result != AudioTrack.SUCCESS) Log.w(TAG, "${track.name}: setLoopPoints -> $result")
        return audioTrack
    }

    // --- audio focus ------------------------------------------------------------------------------

    private fun requestFocus() {
        if (focusRequest != null) return
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                manager.requestAudioFocus(request)
                focusRequest = request
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
                focusRequest = focusListener
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot take audio focus", e)
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        focusRequest = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                request is android.media.AudioFocusRequest
            ) {
                manager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot give up audio focus", e)
        }
    }

    private companion object {
        const val TAG = "MusicPlayer"

        /** Length of the menu↔game crossfade, ms. */
        const val CROSSFADE_MS = 400f

        /** How often the fade is stepped; 25 ms is inaudibly smooth and costs nothing. */
        const val RAMP_INTERVAL_MS = 25L

        /** Music never competes with the effects: this is its ceiling. */
        const val BASE_VOLUME = 0.55f

        /** How far the music dips under a spoken line. */
        const val DUCK_FACTOR = 0.3f

        /** Extra time the dip is held after the line ends, so the music does not jump back in. */
        const val DUCK_RELEASE_MS = 250L
    }
}
