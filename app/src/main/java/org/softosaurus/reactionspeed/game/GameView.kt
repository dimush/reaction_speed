package org.softosaurus.reactionspeed.game

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.softosaurus.reactionspeed.audio.Sound
import org.softosaurus.reactionspeed.audio.SoundBank
import org.softosaurus.reactionspeed.data.GameSettings

/**
 * The playfield: a self-contained [SurfaceView] that drives a [GameEngine] and draws the result.
 *
 * It shows **only** the game — grass, target, hit effect, a small progress HUD — and nothing else.
 * Menus, the top-10 slab, the statistics graph and the instruction boulder of the legacy view live
 * in the Compose shell now; this view is meant to be embedded with `AndroidView { GameView(it) }`.
 *
 * ### Threading
 * * The engine, the renderer and all game state are touched **only by the render thread**.
 * * The public API is main-thread-only. Every call turns into a command that is queued under
 *   [lock] and executed by the render thread; when no render thread exists (no surface yet, or the
 *   host is paused) the command is executed inline, which is safe precisely because nothing else
 *   runs then.
 * * Listener callbacks are always delivered on the main thread.
 *
 * ### Timing
 * Reaction times are measured between the moment the frame carrying the target was posted
 * (`SystemClock.uptimeMillis()` right after `unlockCanvasAndPost`, handed to
 * [GameEngine.markTargetPresented]) and `MotionEvent.eventTime` of the tap — the same monotonic
 * base, and free of input-queue latency.
 *
 * The sound and the vibration fire at that same moment, i.e. when the frame is **queued**, which is
 * one or two vsyncs (~16–33 ms) before it is actually scanned out. So a cue can in principle reach
 * the player slightly before the pixels do. That is deliberate and harmless in this direction: the
 * baseline is the queue time, which is earlier than the true presentation time, so every measured
 * reaction comes out a touch *slower* than reality and no legitimate tap is ever mistaken for a
 * false start. Moving the baseline later (e.g. to a real presentation timestamp) would make times
 * flatter but would start rejecting honest fast taps, which is the worse trade.
 *
 * ### Host contract
 * ```
 * val view = GameView(context)
 * view.listener = ...
 * view.settings = repository.settings.value
 * view.setSafeInsets(l, t, r, b)   // status bar / cutout / ad banner, px
 * view.onResume()                  // from the lifecycle owner
 * view.startSeries()
 * ...
 * view.onPause()                   // aborts a running series
 * view.release()                   // when the host screen is gone for good
 * ```
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Main-thread callbacks into the host. All methods are optional. */
    interface Listener {

        /**
         * Progress of the series.
         *
         * @param attemptIndex number of **completed** attempts — 0 right after [startSeries],
         *   1 after the first hit, …, [GameConfig.attemptsPerSeries] together with the last hit.
         * @param lastReactionMs reaction time of the attempt that just completed, or `null` at the
         *   start of the series.
         */
        fun onProgress(attemptIndex: Int, lastReactionMs: Int?) {}

        /**
         * The player tapped before the target was on screen; the pending delay was re-rolled.
         *
         * Nothing in the app overrides this: false starts are already shown by the renderer (the
         * "Too early!" flash) and counted into [SeriesResult.falseStarts], so the host has nothing
         * to add. It stays on the interface as the one hook for host-side feedback — a toast, a
         * tutorial nudge — and because a series-progress interface that silently omitted its one
         * error event would be a strange thing to hand the next reader.
         */
        fun onFalseStart() {}

        /** The series completed normally. */
        fun onSeriesFinished(result: SeriesResult) {}

        /**
         * The series was dropped without a result — the host paused mid-series, or [abortSeries]
         * was called. Never fires when no series was in flight.
         */
        fun onSeriesAborted() {}
    }

    // --- collaborators --------------------------------------------------------------------------

    private val engine = GameEngine()
    private val renderer = GameRenderer(context)
    private val haptics = Haptics(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- command plumbing -----------------------------------------------------------------------

    private sealed interface Cmd {
        data class Touch(val x: Float, val y: Float, val eventTimeMs: Long) : Cmd
        data class SurfaceSize(val width: Int, val height: Int) : Cmd
        data class SafeInsets(val left: Float, val top: Float, val right: Float, val bottom: Float) : Cmd
        data object Start : Cmd
        data object Abort : Cmd
    }

    private val lock = ReentrantLock()
    private val work = lock.newCondition()
    private val pending = ArrayDeque<Cmd>()
    private val batch = ArrayList<Cmd>(INITIAL_BATCH)

    /** Guarded by [lock]; also read without it by the render thread's crash handler. */
    @Volatile
    private var running = false

    private var renderThread: RenderThread? = null
    private var surfaceAvailable = false

    /**
     * Starts out `true` so that a host which never calls [onResume] still gets a rendered game as
     * soon as the surface exists; [onPause] clears it.
     */
    private var resumed = true

    // --- render-thread state --------------------------------------------------------------------

    private var fieldWidth = 0
    private var fieldHeight = 0
    private var insetLeft = 0f
    private var insetTop = 0f
    private var insetRight = 0f
    private var insetBottom = 0f
    private var startRequested = false
    private var dirty = true
    private var lastDrawFailed = false
    private var lastFrameAtMs = 0L
    private var pendingTargetShown: GameEvent.TargetShown? = null

    /** Read from the main thread in [onPause] — only ever after the render thread has been joined. */
    @Volatile
    private var seriesActive = false

    // --- public API -----------------------------------------------------------------------------

    /** Main-thread callbacks; set it before [startSeries]. */
    var listener: Listener? = null

    /** Sound and haptics preferences. Assign at any time; the render thread picks it up at once. */
    @Volatile
    var settings: GameSettings = GameSettings()

    /**
     * The app-wide sound bank, injected by the host.
     *
     * The playfield deliberately does **not** own it: the Compose screens click, cheer and speak
     * through the same [SoundBank], and two `SoundPool`s in one process would fight over streams
     * and double the memory for no benefit. `null` simply means silence, which keeps the view
     * usable in isolation (a preview, a test host) without a sound stack behind it.
     */
    @Volatile
    var sounds: SoundBank? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        setWillNotDraw(true)
    }

    /**
     * Area (px) at the edges of the view that targets must not overlap: status bar, display cutout,
     * navigation bar and the ad banner. The HUD is laid out inside the remaining safe area and
     * reserves its own strip on top of [top], so targets never hide behind it.
     *
     * Applying this mid-series is allowed: a target already on screen is clamped into the new safe
     * area by [GameEngine.setField], so a resize can never strand it off-surface.
     */
    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        submit(Cmd.SafeInsets(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
    }

    /**
     * Starts a fresh series. If the surface is not ready yet the start is deferred until it is, so
     * the first waiting period always begins with the game actually on screen.
     */
    fun startSeries() {
        submit(Cmd.Start)
    }

    /** Drops a running series without a result; fires [Listener.onSeriesAborted] if one was running. */
    fun abortSeries() {
        submit(Cmd.Abort)
    }

    /** Call from the host's `ON_RESUME`. */
    fun onResume() {
        resumed = true
        maybeStartRenderThread()
    }

    /**
     * Call from the host's `ON_PAUSE`. The render thread is stopped and joined, and a series in
     * flight is aborted — reaction timing cannot survive a pause, so continuing would be a lie.
     */
    fun onPause() {
        resumed = false
        stopRenderThread()
        abortInternal()
    }

    /**
     * Releases the cached bitmaps. Called automatically when the view leaves its window; call it
     * explicitly only if you keep the instance around after that.
     *
     * The sound bank is *not* touched: it belongs to the application and outlives every playfield.
     */
    fun release() {
        stopRenderThread()
        if (liveRenderThread() != null) {
            // Freeing the bitmaps under a thread that is still drawing would turn a stuck teardown
            // into a crash. Leaking them until the process goes away is the lesser evil.
            Log.w(TAG, "render thread outlived its join; the renderer is left for the GC")
            return
        }
        renderer.release()
    }

    // --- view / surface lifecycle ----------------------------------------------------------------

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        maybeStartRenderThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        submit(Cmd.SurfaceSize(width, height))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        // Must block until the thread is out of the surface, otherwise it draws into a dead buffer.
        stopRenderThread()
        // A series cannot survive the playfield disappearing: without this the engine would keep
        // its Waiting phase and spawn the target on the very first frame after the surface comes
        // back. Idempotent with the abort in onPause — whichever runs first wins, the other
        // returns immediately, and the host sees exactly one onSeriesAborted().
        abortInternal()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                // event.eventTime shares the uptimeMillis base the engine ticks on, and excludes
                // the time the event spent in the input queue.
                submit(Cmd.Touch(event.getX(i), event.getY(i), event.eventTime))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- thread lifecycle -------------------------------------------------------------------------

    /**
     * The render thread if one is **still running**, else `null`.
     *
     * The reference is only ever dropped here, once the thread is genuinely dead. A join that timed
     * out must not be treated as a successful stop: everything that assumes "no render thread"
     * — starting a second one, draining commands inline, releasing the renderer — would then run
     * concurrently with a thread that is still touching the engine and the surface.
     *
     * Main-thread only, like the rest of the thread lifecycle.
     */
    private fun liveRenderThread(): RenderThread? {
        val thread = renderThread ?: return null
        if (thread.isAlive) return thread
        renderThread = null
        return null
    }

    private fun maybeStartRenderThread() {
        if (!surfaceAvailable || !resumed || liveRenderThread() != null) return
        lock.withLock { running = true }
        lastDrawFailed = false
        dirty = true
        renderThread = RenderThread().also { it.start() }
    }

    private fun stopRenderThread() {
        val thread = liveRenderThread() ?: run { renderThread = null; return }
        // Both in one critical section: a thread parked in await() would otherwise never wake and
        // the join below would hang the main thread. The per-thread flag makes sure that a thread
        // which outlived its join cannot come back to life when `running` is set again.
        lock.withLock {
            thread.alive = false
            running = false
            work.signalAll()
        }
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            // Keep the reference: while it is set, nothing else will touch the engine or the
            // renderer. `alive` is already false, so the thread exits at its next loop check and
            // the next liveRenderThread() call clears it.
            Log.w(TAG, "render thread did not stop within ${JOIN_TIMEOUT_MS}ms")
            return
        }
        renderThread = null
    }

    private inner class RenderThread : Thread("ReactionSpeed-Render") {

        /** Cleared by [stopRenderThread]; this thread and no other. */
        @Volatile
        var alive = true

        override fun run() {
            try {
                renderLoop(this)
            } catch (t: Throwable) {
                if (running && alive) {
                    Log.e(TAG, "render thread crashed", t)
                    throw t
                }
                // Torn down mid-frame: the surface died under us. Noted, not hidden, not fatal.
                Log.w(TAG, "render thread stopped by an exception during shutdown", t)
            }
        }
    }

    // --- the loop ---------------------------------------------------------------------------------

    private fun renderLoop(self: RenderThread) {
        while (true) {
            lock.withLock {
                if (!running || !self.alive) return
                batch.clear()
                while (pending.isNotEmpty()) batch.add(pending.removeFirst())
            }
            if (batch.isNotEmpty()) {
                val commandTime = SystemClock.uptimeMillis()
                for (cmd in batch) applyCommand(cmd, commandTime)
                batch.clear()
            }

            val now = SystemClock.uptimeMillis()
            if (startRequested && engine.playfield != null) {
                startRequested = false
                beginSeries(now)
            }
            handleEvents(engine.tick(now), now)

            val shown = pendingTargetShown
            if (dirty || lastDrawFailed || shown != null || renderer.isAnimating(now)) {
                val drawn = drawFrame(now)
                lastDrawFailed = !drawn
                if (drawn) {
                    dirty = false
                    lastFrameAtMs = SystemClock.uptimeMillis()
                    if (shown != null) {
                        pendingTargetShown = null
                        // The pixels are on their way: this is the fair baseline, and the cues must
                        // not be a millisecond earlier than it.
                        engine.markTargetPresented(shown.attemptIndex, lastFrameAtMs)
                        fireTargetCues()
                    }
                }
            }

            awaitNextFrame(self)
        }
    }

    /** Parks the thread until the next frame is due, or until something is submitted. */
    private fun awaitNextFrame(self: RenderThread) {
        lock.withLock {
            if (!running || !self.alive || pending.isNotEmpty()) return
            val delay = nextWakeDelayMs(SystemClock.uptimeMillis())
            when {
                delay < 0L -> work.await()
                delay > 0L -> work.await(delay, TimeUnit.MILLISECONDS)
                else -> Unit // due right now
            }
        }
    }

    /** Milliseconds until the loop must run again; negative means "only when something happens". */
    private fun nextWakeDelayMs(now: Long): Long {
        if (lastDrawFailed) return FRAME_MS
        if (renderer.isAnimating(now)) return (lastFrameAtMs + FRAME_MS - now).coerceAtLeast(0L)
        val phase = engine.phase
        // A hidden target needs exactly one wake-up: the moment its delay expires. No busy loop,
        // and no wasted frames while the screen is a still life.
        if (phase is GamePhase.Waiting) {
            return (phase.startedAtMs + phase.delayMs - now).coerceAtLeast(0L)
        }
        return -1L
    }

    // --- commands and events ------------------------------------------------------------------------

    private fun submit(cmd: Cmd) {
        lock.withLock {
            pending.addLast(cmd)
            work.signalAll()
        }
        // No *live* render thread => nobody else can be touching the engine, so run it here and now.
        if (liveRenderThread() == null) drainInline()
    }

    private fun drainInline() {
        val now = SystemClock.uptimeMillis()
        while (true) {
            val cmd = lock.withLock { pending.removeFirstOrNull() } ?: return
            applyCommand(cmd, now)
        }
    }

    private fun applyCommand(cmd: Cmd, now: Long) {
        when (cmd) {
            is Cmd.Touch -> handleEvents(
                engine.onTouch(cmd.x, cmd.y, cmd.eventTimeMs),
                now,
                cmd.x,
                cmd.y,
            )

            is Cmd.SurfaceSize -> {
                fieldWidth = cmd.width
                fieldHeight = cmd.height
                refreshLayout()
                dirty = true
            }

            is Cmd.SafeInsets -> {
                insetLeft = cmd.left
                insetTop = cmd.top
                insetRight = cmd.right
                insetBottom = cmd.bottom
                refreshLayout()
                dirty = true
            }

            Cmd.Start -> startRequested = true

            Cmd.Abort -> {
                startRequested = false
                abortInternal()
            }
        }
    }

    private fun refreshLayout() {
        if (fieldWidth <= 0 || fieldHeight <= 0) return
        renderer.setSize(fieldWidth, fieldHeight)
        renderer.setSafeArea(
            insetLeft,
            insetTop,
            fieldWidth - insetRight,
            fieldHeight - insetBottom,
        )
        // The HUD strip is added to the top inset so no target can hide under it.
        engine.setField(
            fieldWidth.toFloat(),
            fieldHeight.toFloat(),
            Insets(insetLeft, insetTop + renderer.hudHeightPx, insetRight, insetBottom),
        )
        // Faces and gradients are built here, as soon as the geometry is known and always well
        // before the first target: nothing may be decoded or allocated at a target onset.
        engine.targetRadiusPx?.let { renderer.prepareTarget(it) }
    }

    private fun beginSeries(now: Long) {
        renderer.setAttemptsTotal(engine.config.attemptsPerSeries)
        // A no-op unless a resize changed the radius since refreshLayout(); the cost of being sure.
        engine.targetRadiusPx?.let { renderer.prepareTarget(it) }
        renderer.onSeriesStarted()
        engine.start(now)
        seriesActive = true
        dirty = true
        postToMain {
            keepScreenOn = true
            listener?.onProgress(0, null)
        }
    }

    /**
     * Aborts a series if one is running. Safe on either thread: it is called from the render thread
     * (via [Cmd.Abort]) or from the main thread in [onPause], which only runs after the render
     * thread has been joined.
     */
    private fun abortInternal() {
        startRequested = false
        if (!seriesActive) return
        seriesActive = false
        engine.reset()
        renderer.onSeriesEnded()
        pendingTargetShown = null
        dirty = true
        postToMain {
            keepScreenOn = false
            listener?.onSeriesAborted()
        }
    }

    private fun handleEvents(
        events: List<GameEvent>,
        now: Long,
        touchX: Float = 0f,
        touchY: Float = 0f,
    ) {
        if (events.isEmpty()) return
        for (event in events) {
            when (event) {
                is GameEvent.TargetShown -> {
                    renderer.onTargetShown(event.target, now)
                    pendingTargetShown = event
                    dirty = true
                }

                is GameEvent.Hit -> {
                    renderer.onHit(touchX, touchY, event.reactionTimeMs, now)
                    // Two layers: the physical "bonk" and the monster complaining about it.
                    sounds?.let {
                        it.playRandom(Sound.HITS)
                        it.playRandom(Sound.OUCHES)
                    }
                    dirty = true
                    val completed = event.attemptIndex + 1
                    val reaction = event.reactionTimeMs
                    postToMain { listener?.onProgress(completed, reaction) }
                }

                is GameEvent.FalseStart -> {
                    renderer.onFalseStart(now)
                    sounds?.let {
                        it.play(Sound.FALSE_START)
                        // One taunt, never two: the spoken line if voices are on and free, the
                        // wordless cackle otherwise. Stacking them over the 900 ms toast would
                        // still be ringing out when the next target appears.
                        if (it.playVoice(Sound.VOICE_TOO_EARLY) == 0L) it.playRandom(Sound.LAUGHS)
                    }
                    dirty = true
                    postToMain { listener?.onFalseStart() }
                }

                is GameEvent.Miss -> {
                    // Scoring still ignores it (legacy parity); it just is not silent any more.
                    renderer.onMiss(touchX, touchY, now)
                    sounds?.play(Sound.MISS)
                    dirty = true
                }

                is GameEvent.SeriesFinished -> {
                    seriesActive = false
                    // The renderer keeps the last hit effect and a full HUD on screen; the host
                    // decides when to navigate away.
                    sounds?.play(Sound.SERIES_FINISH)
                    val result = event.result
                    dirty = true
                    postToMain {
                        keepScreenOn = false
                        listener?.onSeriesFinished(result)
                    }
                }
            }
        }
    }

    /**
     * Sound and haptics for the target, fired the instant its frame has been posted.
     *
     * Unchanged in every way that matters: same call site, same point in the frame pipeline, same
     * one-shot cost. The only difference is that the cue is now one of three interchangeable pops.
     * `tool/audio` normalises the three with a single shared gain and makes their first 30 ms
     * bit-identical, so which one is drawn cannot bias the measurement - and they are played at one
     * volume here for the same reason. `vox_pop_hello_*` is deliberately unreachable from here.
     */
    private fun fireTargetCues() {
        sounds?.playRandom(Sound.TARGET_POPS)
        if (settings.vibration) haptics.vibrate(Haptics.TARGET_PULSE_MS)
    }

    // --- drawing ------------------------------------------------------------------------------------

    private fun drawFrame(now: Long): Boolean {
        val surface = holder.surface
        if (!surface.isValid) return false

        var canvas: Canvas? = null
        var hardware = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas = surface.lockHardwareCanvas()
                hardware = true
            } else {
                @Suppress("DEPRECATION")
                canvas = holder.lockCanvas()
            }
        } catch (e: IllegalStateException) {
            // Normal during teardown: the surface died between isValid and the lock.
            if (!running || !surface.isValid) {
                Log.d(TAG, "surface went away while locking", e)
                return false
            }
            throw e
        }
        if (canvas == null) return false

        var drawn = false
        try {
            renderer.draw(canvas, now)
            drawn = true
        } finally {
            try {
                if (hardware) surface.unlockCanvasAndPost(canvas) else holder.unlockCanvasAndPost(canvas)
            } catch (e: IllegalStateException) {
                if (drawn && running && surface.isValid) throw e
                Log.d(TAG, "surface went away while posting", e)
                drawn = false
            }
        }
        return drawn
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "GameView"

        /** ~60 fps while something moves. */
        const val FRAME_MS = 16L

        /** Upper bound on how long the main thread may block waiting for the render thread. */
        const val JOIN_TIMEOUT_MS = 1_000L

        const val INITIAL_BATCH = 16
    }
}
