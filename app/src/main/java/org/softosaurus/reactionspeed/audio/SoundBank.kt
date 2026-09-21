package org.softosaurus.reactionspeed.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import org.softosaurus.reactionspeed.R
import org.softosaurus.reactionspeed.data.GameSettings

/** Which preference switch a sound obeys, and which loudness reference it was mastered against. */
enum class SoundGroup {
    /** Game and UI effects, jingles. Gated by [GameSettings.soundEffects]. */
    SFX,

    /** Wordless monster gibberish. Also gated by [GameSettings.soundEffects] — it is a funny noise, not speech. */
    VOX,

    /** Spoken, localised lines. Gated by [GameSettings.voice] and ducks the music. */
    VOICE,
}

/**
 * Every short sample the app can play, with the playback gain measured by `tool/audio/analyze.py`.
 *
 * The gains are **not** guesses: `analyze.py` prints `10^((groupReference − weightedRMS)/20)`
 * clamped to 1.0 for each file, and those are the numbers below. A gain of 1.0 means the file is
 * already at (or below) its group reference.
 *
 * The three target pops deliberately all carry gain 1.0 and are played through the same
 * [SoundGroup.SFX] master volume: `tool/audio` normalises them with one *shared* gain precisely so
 * that the random variant cannot bias the measured reaction time. Never give them individual gains.
 */
enum class Sound(val resId: Int, val group: SoundGroup, val gain: Float = 1f) {
    TARGET_POP_1(R.raw.sfx_target_pop_1, SoundGroup.SFX),
    TARGET_POP_2(R.raw.sfx_target_pop_2, SoundGroup.SFX),
    TARGET_POP_3(R.raw.sfx_target_pop_3, SoundGroup.SFX),

    HIT_1(R.raw.sfx_hit_1, SoundGroup.SFX),
    HIT_2(R.raw.sfx_hit_2, SoundGroup.SFX),
    HIT_3(R.raw.sfx_hit_3, SoundGroup.SFX),
    HIT_4(R.raw.sfx_hit_4, SoundGroup.SFX),
    HIT_5(R.raw.sfx_hit_5, SoundGroup.SFX),

    MISS(R.raw.sfx_miss, SoundGroup.SFX),
    FALSE_START(R.raw.sfx_false_start, SoundGroup.SFX),
    UI_CLICK(R.raw.sfx_ui_click, SoundGroup.SFX),
    SERIES_FINISH(R.raw.sfx_series_finish, SoundGroup.SFX, gain = 0.95f),
    NEW_RECORD(R.raw.sfx_new_record, SoundGroup.SFX, gain = 0.85f),

    JINGLE_1(R.raw.jingle_tier_1, SoundGroup.SFX),
    JINGLE_2(R.raw.jingle_tier_2, SoundGroup.SFX),
    JINGLE_3(R.raw.jingle_tier_3, SoundGroup.SFX),
    JINGLE_4(R.raw.jingle_tier_4, SoundGroup.SFX),
    JINGLE_5(R.raw.jingle_tier_5, SoundGroup.SFX, gain = 0.94f),

    VOX_OUCH_1(R.raw.vox_ouch_1, SoundGroup.VOX),
    VOX_OUCH_2(R.raw.vox_ouch_2, SoundGroup.VOX),
    VOX_OUCH_3(R.raw.vox_ouch_3, SoundGroup.VOX),
    VOX_OUCH_4(R.raw.vox_ouch_4, SoundGroup.VOX),
    VOX_LAUGH_1(R.raw.vox_laugh_1, SoundGroup.VOX),
    VOX_LAUGH_2(R.raw.vox_laugh_2, SoundGroup.VOX),
    VOX_YAY(R.raw.vox_yay, SoundGroup.VOX),
    VOX_AWW(R.raw.vox_aww, SoundGroup.VOX),

    /**
     * Idle "hello" gibberish for the home mascot **only**.
     *
     * `tool/audio/README.md` is explicit: these takes are long and vowel-shaped, so their perceived
     * onset is fuzzy. Playing one when a target appears would add variance to the measurement, so
     * nothing on the playfield may reach them.
     */
    VOX_HELLO_1(R.raw.vox_pop_hello_1, SoundGroup.VOX),
    VOX_HELLO_2(R.raw.vox_pop_hello_2, SoundGroup.VOX),

    VOICE_READY(R.raw.voice_ready, SoundGroup.VOICE, gain = 0.78f),
    VOICE_TOO_EARLY(R.raw.voice_too_early, SoundGroup.VOICE),
    VOICE_TIER_1(R.raw.voice_tier_1, SoundGroup.VOICE),
    VOICE_TIER_2(R.raw.voice_tier_2, SoundGroup.VOICE),
    VOICE_TIER_3(R.raw.voice_tier_3, SoundGroup.VOICE),
    VOICE_TIER_4(R.raw.voice_tier_4, SoundGroup.VOICE),
    VOICE_TIER_5(R.raw.voice_tier_5, SoundGroup.VOICE),
    VOICE_NEW_RECORD(R.raw.voice_new_record, SoundGroup.VOICE),
    ;

    companion object {
        val TARGET_POPS = arrayOf(TARGET_POP_1, TARGET_POP_2, TARGET_POP_3)
        val HITS = arrayOf(HIT_1, HIT_2, HIT_3, HIT_4, HIT_5)
        val OUCHES = arrayOf(VOX_OUCH_1, VOX_OUCH_2, VOX_OUCH_3, VOX_OUCH_4)
        val LAUGHS = arrayOf(VOX_LAUGH_1, VOX_LAUGH_2)
        val HELLOS = arrayOf(VOX_HELLO_1, VOX_HELLO_2)

        private val JINGLES = arrayOf(JINGLE_1, JINGLE_2, JINGLE_3, JINGLE_4, JINGLE_5)
        private val TIER_VOICES =
            arrayOf(VOICE_TIER_1, VOICE_TIER_2, VOICE_TIER_3, VOICE_TIER_4, VOICE_TIER_5)

        /** Fanfare for a 1..5 verdict tier; out-of-range values are clamped. */
        fun jingleForTier(tier: Int): Sound = JINGLES[(tier - 1).coerceIn(0, JINGLES.lastIndex)]

        /** Spoken verdict for a 1..5 tier; out-of-range values are clamped. */
        fun voiceForTier(tier: Int): Sound = TIER_VOICES[(tier - 1).coerceIn(0, TIER_VOICES.lastIndex)]
    }
}

/**
 * The app's one [SoundPool], shared by the playfield's render thread and the Compose screens.
 *
 * ### Loading
 * Every sample is loaded on a private worker thread at startup, together with its duration, which
 * is read out of the WAV header by [Wav] rather than being hardcoded — the `voice_*` files are
 * regenerated by `tool/audio` and will be replaced by takes of different lengths. Playing a sample
 * that has not finished decoding is a **silent no-op**, exactly as `SoundPool.load()`'s asynchrony
 * demands.
 *
 * ### Threading
 * [play], [playVoice] and the `random*` helpers may be called from any thread, including the render
 * thread; nothing in them allocates or blocks. [release] is main-thread only.
 *
 * ### Voices never overlap
 * [playVoice] refuses to start a line while another one is still speaking, tracked with the
 * duration measured at load time. The same duration is handed to [ducker] so the music dips for
 * exactly as long as the line lasts.
 */
class SoundBank(context: Context) {

    private val appContext = context.applicationContext

    /** Sample ids by [Sound.ordinal]; 0 means "not loaded yet". */
    private val sampleIds = IntArray(Sound.entries.size)

    /** Durations in ms by [Sound.ordinal]; 0 until the header has been read. */
    private val durations = LongArray(Sound.entries.size)

    private val loadedIds = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var pool: SoundPool? = null

    private val loaderThread =
        HandlerThread("ReactionSpeed-SoundLoader", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val loader = android.os.Handler(loaderThread.looper)

    private val random = Random.Default

    /** Preference gate, kept in sync by the owner. Read on the render thread, hence volatile. */
    @Volatile
    var settings: GameSettings = GameSettings()

    /**
     * Called with the length of a voice line that just started, so the music can dip under it.
     * Installed by the owner; `null` simply means "no music to duck".
     */
    @Volatile
    var ducker: ((Long) -> Unit)? = null

    /** `uptimeMillis` at which the currently speaking line ends. */
    @Volatile
    private var voiceBusyUntilMs = 0L

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attributes)
            .build()
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId) else Log.w(TAG, "sample $sampleId failed to load")
        }
        pool = sp
        // Deferred, and at background priority. `SoundPool.load()` spins up a full MediaCodec per
        // sample; four dozen of them fired off inside Application.onCreate compete with the very
        // first frame for CPU and for the media stack, which on a cold emulator is enough to stall
        // the launch outright. Nothing is lost by waiting: an unloaded sample is a silent no-op,
        // and the earliest a cue can be wanted is a tap plus the engine's own half-second pause.
        loader.postDelayed({ loadAll(sp) }, LOAD_DELAY_MS)
    }

    /**
     * Plays [sound] if its group is enabled and it has finished decoding.
     *
     * @return the sample's length in ms, or 0 when nothing was played — which is exactly what a
     *   caller sequencing several cues wants to wait for.
     */
    fun play(sound: Sound): Long {
        if (!isGroupEnabled(sound.group)) return 0L
        return playUnconditionally(sound)
    }

    /** A random one of [pool], e.g. the five hit sounds. Empty arrays are ignored. */
    fun playRandom(pool: Array<Sound>): Long {
        if (pool.isEmpty()) return 0L
        return play(pool[random.nextInt(pool.size)])
    }

    /**
     * Plays a localised spoken line, unless the voice setting is off or another line is still
     * speaking. Two voices talking over each other is never wanted, so the later one is dropped
     * rather than queued — a stale line arriving seconds late is worse than no line.
     *
     * @return the line's length in ms, or 0 when nothing was played
     */
    fun playVoice(voice: Sound): Long {
        if (!settings.voice) return 0L
        val now = SystemClock.uptimeMillis()
        if (now < voiceBusyUntilMs) return 0L
        val duration = playUnconditionally(voice)
        if (duration <= 0L) return 0L
        voiceBusyUntilMs = now + duration
        ducker?.invoke(duration)
        return duration
    }

    /** True while a spoken line is still playing. */
    fun isVoiceBusy(): Boolean = SystemClock.uptimeMillis() < voiceBusyUntilMs

    /** Length of [sound] in ms, or 0 while its header has not been read yet. */
    fun durationMs(sound: Sound): Long = durations[sound.ordinal]

    /** Frees the native pool and stops the loader thread. Safe to call more than once. */
    fun release() {
        pool?.release()
        pool = null
        loadedIds.clear()
        loaderThread.quitSafely()
    }

    // --- internals ----------------------------------------------------------------------------

    private fun isGroupEnabled(group: SoundGroup): Boolean {
        val current = settings
        return when (group) {
            SoundGroup.SFX, SoundGroup.VOX -> current.soundEffects
            SoundGroup.VOICE -> current.voice
        }
    }

    private fun playUnconditionally(sound: Sound): Long {
        val sp = pool ?: return 0L
        val id = sampleIds[sound.ordinal]
        if (id == 0 || !loadedIds.contains(id)) return 0L
        val volume = (masterVolume(sound.group) * sound.gain).coerceIn(0f, 1f)
        return try {
            sp.play(id, volume, volume, PRIORITY, 0, 1f)
            durations[sound.ordinal]
        } catch (e: IllegalStateException) {
            // The pool was released concurrently by the main thread; nothing to do.
            Log.d(TAG, "play() on a released pool", e)
            0L
        }
    }

    /**
     * Loads every sample, most urgent first.
     *
     * The order matters on a slow device: whatever is still decoding when the player reaches for it
     * is simply silent, so the cues that a hurried player meets first — the click that starts the
     * game, the target pop, the hit — must not be queued behind eight spoken lines and five
     * jingles, which cannot be wanted until a series has actually been finished.
     */
    private fun loadAll(sp: SoundPool) {
        for (sound in LOAD_ORDER) {
            durations[sound.ordinal] = readDuration(sound)
            sampleIds[sound.ordinal] = try {
                sp.load(appContext, sound.resId, PRIORITY)
            } catch (e: Exception) {
                Log.w(TAG, "cannot load ${sound.name}", e)
                0
            }
        }
    }

    private fun readDuration(sound: Sound): Long = try {
        appContext.resources.openRawResource(sound.resId).use { Wav.readFormat(it)?.durationMs ?: 0L }
    } catch (e: Exception) {
        // A missing or exotic file must never keep the app from starting; it just gets no duration,
        // which only costs the sequencing niceties.
        Log.w(TAG, "cannot read the header of ${sound.name}", e)
        0L
    }

    private companion object {
        const val TAG = "SoundBank"

        /**
         * Enough for the busiest moment — a hit sound, an "ouch", the next target pop and the UI
         * click of a fast tap — without letting a stuck stream starve the pool.
         */
        const val MAX_STREAMS = 8

        const val PRIORITY = 1

        /** How long the load waits so the first frame gets the CPU first. */
        const val LOAD_DELAY_MS = 450L

        /**
         * Load order: the playfield's own cues, then the UI and the monster noises, then the things
         * that cannot possibly be needed until a series has been played to its end.
         */
        val LOAD_ORDER: List<Sound> = buildList {
            addAll(Sound.TARGET_POPS)
            addAll(Sound.HITS)
            add(Sound.MISS)
            add(Sound.FALSE_START)
            add(Sound.UI_CLICK)
            addAll(Sound.OUCHES)
            addAll(Sound.LAUGHS)
            add(Sound.VOICE_READY)
            add(Sound.VOICE_TOO_EARLY)
            addAll(Sound.entries.filterNot { it in this })
        }

        /**
         * Per-group master volume. Voices sit highest because they carry meaning; the monster
         * gibberish is a touch below the effects so it garnishes rather than covers them.
         */
        fun masterVolume(group: SoundGroup): Float = when (group) {
            SoundGroup.SFX -> 0.85f
            SoundGroup.VOX -> 0.75f
            SoundGroup.VOICE -> 0.95f
        }
    }
}
