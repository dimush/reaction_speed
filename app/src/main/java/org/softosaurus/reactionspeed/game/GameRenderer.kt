package org.softosaurus.reactionspeed.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import org.softosaurus.reactionspeed.R

/**
 * Everything [GameView] paints, and nothing else.
 *
 * The renderer is **single-threaded**: it is created on the main thread and from then on every
 * method is called from the render thread only. It keeps its own copy of the visual state (target,
 * hit effect, HUD counters) which [GameView] feeds from the engine's events — the renderer never
 * touches [GameEngine].
 *
 * Design notes:
 * * All `Paint`s, `Shader`s, particle pools and the tiled background are allocated once (or once
 *   per surface size), never per frame.
 * * No `BlurMaskFilter` and no `setShadowLayer` on shapes: neither is honoured by a hardware
 *   canvas. Soft edges come from [RadialGradient]s, which are.
 * * Geometry and animation curves live in [GameLayout] so they can be unit-tested.
 *
 * ### Fairness
 * The target is a monster face now, but nothing about *when* it can be seen changed:
 * * all ten faces are decoded **and pre-scaled to the exact drawn size** by [prepareTarget],
 *   together with the gradients behind them, which
 *   runs when the surface is sized and again (as a no-op) when a series starts — never at a target
 *   onset, where a decode or an allocation could cost the player milliseconds;
 * * every face is the same on-screen diameter, the one the engine uses for its hit test;
 * * [GameLayout.targetScale] keeps the old pop-in exactly as it was for its first 80 ms, so the
 *   face is at ≥70 % size on the very first frame; the flourish and the idle wobble only ever start
 *   afterwards;
 * * the grass dust is thrown **outwards from below the face and drawn under it**, so it can never
 *   obscure the thing the player is reacting to.
 */
class GameRenderer(context: Context) {

    private val resources = context.resources
    private val density = resources.displayMetrics.density
    private val appContext = context.applicationContext

    private val progressFormat = context.getString(R.string.game_progress_format)
    private val reactionFormat = context.getString(R.string.game_reaction_ms_format)
    private val falseStartText = context.getString(R.string.game_false_start)

    // --- paints (allocated once) ----------------------------------------------------------------

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val hudTextPaint = textPaint(sizeSp = 15f, bold = true).apply {
        textAlign = Paint.Align.CENTER
        color = HUD_TEXT_COLOR
    }
    private val hitTextPaint = textPaint(sizeSp = 21f, bold = true).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val toastTextPaint = textPaint(sizeSp = 22f, bold = true).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val toastBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = TOAST_BG_COLOR
    }

    private val hudTextAscent = -hudTextPaint.fontMetrics.ascent
    private val toastTextMetrics = toastTextPaint.fontMetrics

    // --- surface / background -------------------------------------------------------------------

    private var width = 0
    private var height = 0
    private var background: Bitmap? = null
    private var grassTile: Bitmap? = null

    // --- faces ------------------------------------------------------------------------------------

    /** Pre-scaled monster faces, indexed as [FACE_RES_IDS]; `null` entries fall back to the disc. */
    private val faces = arrayOfNulls<Bitmap>(FACE_RES_IDS.size)

    /** Diameter the faces in [faces] were scaled to, or 0 when none are prepared. */
    private var facePixels = 0

    private val faceSequence = FaceSequence(FACE_RES_IDS.size)
    private var currentFace = -1
    private var hitFace = -1

    // --- particles ----------------------------------------------------------------------------------

    private val particles = ParticleField(PARTICLE_CAPACITY, density)
    private var burstSeed = 0

    // --- safe area / HUD ------------------------------------------------------------------------

    private var safeLeft = 0f
    private var safeTop = 0f
    private var safeRight = 0f
    private var safeBottom = 0f
    private var attemptsTotal = GameConfig().attemptsPerSeries
    private var hud: GameLayout.HudLayout = GameLayout.hud(0f, 0f, 0f, density, attemptsTotal, hudTextAscent)

    /** Height the HUD occupies at the top of the safe area; targets must stay clear of it. */
    val hudHeightPx: Float get() = hud.height

    // --- animated state -------------------------------------------------------------------------

    private var seriesActive = false
    private var attemptsDone = 0
    private var progressText = ""

    private var targetX = 0f
    private var targetY = 0f
    private var targetRadius = 0f
    private var targetVisible = false
    private var targetShownAtMs = 0L

    private var hitActive = false
    private var hitX = 0f
    private var hitY = 0f
    private var hitRadius = 0f
    private var hitStartedAtMs = 0L
    private var hitText = ""
    private var hitFaceX = 0f
    private var hitFaceY = 0f

    private var falseStartActive = false
    private var falseStartAtMs = 0L
    private var falseStartHalfWidth = 0f

    // --- shaders (rebuilt only when the target radius changes) ----------------------------------

    private var shaderRadius = -1f

    // --- public (render-thread) API -------------------------------------------------------------

    /** Reacts to a new surface size; the tiled background is rebuilt lazily on the next frame. */
    fun setSize(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        this.width = width
        this.height = height
        recycleBackground()
        updateHud()
    }

    /** Sets the area not covered by system bars, cutouts or the ad banner (px). */
    fun setSafeArea(left: Float, top: Float, right: Float, bottom: Float) {
        safeLeft = left
        safeTop = top
        safeRight = right
        safeBottom = bottom
        updateHud()
    }

    /**
     * Gets everything a target needs ready: the ten face bitmaps scaled to the exact diameter they
     * will be drawn at, and the gradients behind the fallback disc and the drop shadow.
     *
     * Called whenever the playfield geometry settles — long before the first target — and again
     * when a series starts, where it is a no-op unless the radius genuinely changed (a mid-series
     * resize is allowed by `GameView.setSafeInsets`). **Nothing here may ever be reached from
     * [onTargetShown]:** showing a target must cost a handful of field writes and nothing else, or
     * the first measurement of the session pays for a decode or an allocation the rest do not.
     */
    fun prepareTarget(radiusPx: Float) {
        if (radiusPx <= 0f) return
        ensureShaders(radiusPx)
        prepareFaces(radiusPx)
    }

    private fun prepareFaces(radiusPx: Float) {
        val pixels = (radiusPx * 2f).roundToInt().coerceAtLeast(1)
        if (pixels == facePixels && faces[0] != null) return
        recycleFaces()
        facePixels = pixels
        val options = BitmapFactory.Options().apply {
            // The source masters are 256 px; halving the decode when the target is much smaller
            // keeps the peak allocation down without costing visible quality.
            inSampleSize = sampleSizeFor(pixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        for (i in FACE_RES_IDS.indices) {
            faces[i] = try {
                val decoded = BitmapFactory.decodeResource(resources, FACE_RES_IDS[i], options)
                if (decoded == null) {
                    null
                } else if (decoded.width == pixels && decoded.height == pixels) {
                    decoded
                } else {
                    decoded.scale(pixels).also { if (it !== decoded) decoded.recycle() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot prepare face $i", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "out of memory preparing face $i", e)
                null
            }
        }
    }

    /** Number of attempts in a series; drives the number of HUD dots. */
    fun setAttemptsTotal(total: Int) {
        if (total == attemptsTotal) return
        attemptsTotal = max(1, total)
        updateHud()
        updateProgressText()
    }

    /** Called when a series starts. */
    fun onSeriesStarted() {
        clearEffects()
        seriesActive = true
        attemptsDone = 0
        faceSequence.reset()
        updateProgressText()
    }

    /** Called when the series ends or is aborted. */
    fun onSeriesEnded() {
        clearEffects()
        seriesActive = false
    }

    /**
     * A target became visible. Deliberately trivial: a few field writes and one index draw from an
     * already-seeded generator. No decoding, no allocation, no shader rebuild in the common case.
     */
    fun onTargetShown(target: Target, nowMs: Long) {
        targetX = target.x
        targetY = target.y
        targetRadius = target.radiusPx
        targetVisible = true
        targetShownAtMs = nowMs
        currentFace = faceSequence.next()
        // Normally a no-op: prepareTarget() has already built these for this radius. It stays as
        // the safety net for a resize that somehow slipped past, and costs one float comparison.
        ensureShaders(target.radiusPx)
        emitGrassDust(target.x, target.y, target.radiusPx, nowMs)
    }

    /** Called when the target was hit at ([x], [y]) with the measured [reactionMs]. */
    fun onHit(x: Float, y: Float, reactionMs: Int, nowMs: Long) {
        hitActive = true
        hitX = x
        hitY = y
        // The face flies away from where it stood, not from where the finger landed: the two can be
        // a target radius apart and a face that teleports to the fingertip looks like a glitch.
        hitFaceX = if (targetVisible) targetX else x
        hitFaceY = if (targetVisible) targetY else y
        hitFace = currentFace
        hitRadius = if (targetRadius > 0f) targetRadius else 24f * density
        hitStartedAtMs = nowMs
        // Formatted once: this string is redrawn for ~600 ms and must not allocate per frame.
        hitText = String.format(Locale.getDefault(), reactionFormat, reactionMs)
        hitTextPaint.color = TIER_TEXT_COLORS[(Verdict.tierOf(reactionMs) - 1).coerceIn(0, 4)]
        targetVisible = false
        attemptsDone += 1
        updateProgressText()
        emitHitBurst(hitFaceX, hitFaceY, hitRadius, nowMs)
    }

    /** A tap that landed off the target while it was visible. */
    fun onMiss(x: Float, y: Float, nowMs: Long) {
        burstSeed += 1
        particles.emit(
            cx = x,
            cy = y,
            count = MISS_PUFF_COUNT,
            speed = 105f * density,
            spreadFrom = 0f,
            spreadTo = (2.0 * PI).toFloat(),
            radius = 3f * density,
            sizePx = 3.2f * density,
            colors = DUST_COLORS,
            kind = particles.kindDust,
            nowMs = nowMs,
            seed = burstSeed,
        )
    }

    fun onFalseStart(nowMs: Long) {
        falseStartActive = true
        falseStartAtMs = nowMs
        falseStartHalfWidth = toastTextPaint.measureText(falseStartText) / 2f
    }

    /** True while something on screen still moves and the loop must keep pacing frames. */
    fun isAnimating(nowMs: Long): Boolean {
        if (targetVisible) return true // the idle wobble never stops while a face is up
        if (hitActive && nowMs - hitStartedAtMs <= GameLayout.HIT_EFFECT_DURATION_MS) return true
        if (falseStartActive && nowMs - falseStartAtMs <= GameLayout.FALSE_START_DURATION_MS) return true
        return particles.isAnimating(nowMs)
    }

    /** Draws one full frame. The canvas contents are always undefined on entry, so nothing is reused. */
    fun draw(canvas: Canvas, nowMs: Long) {
        drawBackground(canvas)
        particles.draw(canvas, nowMs, particlePaint)
        drawHitEffect(canvas, nowMs)
        drawTarget(canvas, nowMs)
        if (seriesActive) drawHud(canvas)
        drawFalseStart(canvas, nowMs)
    }

    /** Drops the cached bitmaps. Called when the surface goes away or the view is detached. */
    fun release() {
        recycleBackground()
        grassTile?.recycle()
        grassTile = null
        recycleFaces()
    }

    // --- drawing --------------------------------------------------------------------------------

    private fun drawBackground(canvas: Canvas) {
        val bg = background ?: buildBackground()
        if (bg != null) {
            canvas.drawBitmap(bg, 0f, 0f, bitmapPaint)
        } else {
            canvas.drawColor(GRASS_FALLBACK_COLOR)
        }
    }

    private fun drawTarget(canvas: Canvas, nowMs: Long) {
        if (!targetVisible || targetRadius <= 0f) return
        val elapsed = nowMs - targetShownAtMs
        val scale = GameLayout.targetScale(elapsed)
        val tilt = GameLayout.targetTiltDegrees(elapsed)
        val r = targetRadius

        // Soft drop shadow: a radial gradient, because setShadowLayer is text-only on a HW canvas.
        canvas.save()
        canvas.translate(targetX, targetY + r * SHADOW_OFFSET_FACTOR * scale)
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, r * SHADOW_RADIUS_FACTOR, shadowPaint)
        canvas.restore()

        canvas.save()
        canvas.translate(targetX, targetY)
        canvas.rotate(tilt)
        canvas.scale(scale, scale)
        drawFace(canvas, currentFace, r, alpha = 255)
        canvas.restore()
    }

    /**
     * Draws face [index] centred on the current canvas origin, or the procedural red disc when the
     * bitmap is missing.
     *
     * The disc fallback matters: a decode can fail on a low-memory device, and a series with no
     * visible target would be unplayable. It is the exact same size and colour family as the faces,
     * so the game stays fair even when it is less funny.
     */
    private fun drawFace(canvas: Canvas, index: Int, radius: Float, alpha: Int) {
        val bitmap = faces.getOrNull(index)
        if (bitmap != null && !bitmap.isRecycled) {
            val half = bitmap.width / 2f
            facePaint.alpha = alpha
            canvas.drawBitmap(bitmap, -half, -half, facePaint)
            return
        }
        bodyPaint.alpha = alpha
        glossPaint.alpha = alpha
        canvas.drawCircle(0f, 0f, radius, bodyPaint)
        canvas.drawCircle(0f, 0f, radius, glossPaint)
        bodyPaint.alpha = 255
        glossPaint.alpha = 255
    }

    private fun drawHitEffect(canvas: Canvas, nowMs: Long) {
        if (!hitActive) return
        val elapsed = nowMs - hitStartedAtMs
        if (elapsed > GameLayout.HIT_EFFECT_DURATION_MS) {
            hitActive = false
            return
        }
        val p = GameLayout.progress(elapsed, GameLayout.HIT_EFFECT_DURATION_MS)
        val alpha = GameLayout.fadeOutAlpha(p, fadeFrom = 0.15f)

        // The struck face: squashed flat on impact, then spinning away and shrinking to nothing.
        val scaleX = GameLayout.hitScaleX(p)
        val scaleY = GameLayout.hitScaleY(p)
        if (scaleX > 0.01f && scaleY > 0.01f) {
            canvas.save()
            canvas.translate(hitFaceX, hitFaceY - GameLayout.hitDrift(density, p))
            canvas.rotate(GameLayout.hitSpinDegrees(p))
            canvas.scale(scaleX, scaleY)
            drawFace(canvas, hitFace, hitRadius, alpha)
            canvas.restore()
        }

        ringPaint.alpha = (alpha * RING_ALPHA_FACTOR).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = max(1f, RING_STROKE_DP * density * (1f - p))
        canvas.drawCircle(hitFaceX, hitFaceY, GameLayout.hitRingRadius(hitRadius, p), ringPaint)

        hitTextPaint.alpha = alpha
        canvas.drawText(
            hitText,
            hitX,
            hitY - hitRadius * 0.6f - GameLayout.hitTextRise(density, p),
            hitTextPaint,
        )
    }

    private fun drawHud(canvas: Canvas) {
        hudTextPaint.alpha = HUD_ALPHA
        canvas.drawText(progressText, hud.centerX, hud.textBaselineY, hudTextPaint)

        for (i in 0 until attemptsTotal) {
            val cx = hud.firstDotX + i * hud.dotSpacing
            fillPaint.color = if (i < attemptsDone) HUD_DOT_DONE_COLOR else HUD_DOT_TODO_COLOR
            canvas.drawCircle(cx, hud.dotCenterY, hud.dotRadius, fillPaint)
        }
    }

    private fun drawFalseStart(canvas: Canvas, nowMs: Long) {
        if (!falseStartActive) return
        val elapsed = nowMs - falseStartAtMs
        if (elapsed > GameLayout.FALSE_START_DURATION_MS) {
            falseStartActive = false
            return
        }
        val p = GameLayout.progress(elapsed, GameLayout.FALSE_START_DURATION_MS)
        val alpha = GameLayout.fadeOutAlpha(p, fadeFrom = 0.6f)

        val cx = (safeLeft + safeRight) / 2f
        val cy = safeTop + (safeBottom - safeTop) * 0.28f
        val padX = 18f * density
        val padY = 10f * density
        val top = cy + toastTextMetrics.ascent - padY
        val bottom = cy + toastTextMetrics.descent + padY
        val radius = (bottom - top) / 2f

        toastBgPaint.alpha = (alpha * TOAST_BG_ALPHA / 255f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(
            cx - falseStartHalfWidth - padX,
            top,
            cx + falseStartHalfWidth + padX,
            bottom,
            radius,
            radius,
            toastBgPaint,
        )
        toastTextPaint.alpha = alpha
        canvas.drawText(falseStartText, cx, cy, toastTextPaint)
    }

    // --- particle bursts ----------------------------------------------------------------------------

    /**
     * The "out of the grass" puff.
     *
     * The arc is the lower half of the circle and the particles start a full radius from the centre
     * moving outwards, so every speck is born outside the face's silhouette and travels away from
     * it. Together with being drawn *under* the target this guarantees the flourish cannot hide any
     * part of the stimulus, not even on the first frame.
     */
    private fun emitGrassDust(cx: Float, cy: Float, radius: Float, nowMs: Long) {
        burstSeed += 1
        particles.emit(
            cx = cx,
            cy = cy + radius * 0.35f,
            count = GRASS_DUST_COUNT,
            speed = 150f * density,
            spreadFrom = 0f,
            spreadTo = PI.toFloat(),
            radius = radius * 0.95f,
            sizePx = 3.6f * density,
            colors = GRASS_DUST_COLORS,
            kind = particles.kindDust,
            nowMs = nowMs,
            seed = burstSeed,
        )
    }

    private fun emitHitBurst(cx: Float, cy: Float, radius: Float, nowMs: Long) {
        burstSeed += 1
        particles.emit(
            cx = cx,
            cy = cy,
            count = HIT_SPARK_COUNT,
            speed = 330f * density,
            spreadFrom = 0f,
            spreadTo = (2.0 * PI).toFloat(),
            radius = radius * 0.6f,
            sizePx = 5.5f * density,
            colors = SPARK_COLORS,
            kind = particles.kindSpark,
            nowMs = nowMs,
            seed = burstSeed,
        )
    }

    // --- internals ------------------------------------------------------------------------------

    private fun updateHud() {
        val right = if (safeRight > safeLeft) safeRight else width.toFloat()
        hud = GameLayout.hud(safeLeft, safeTop, right, density, attemptsTotal, hudTextAscent)
    }

    private fun updateProgressText() {
        val current = (attemptsDone + 1).coerceAtMost(attemptsTotal)
        progressText = String.format(Locale.getDefault(), progressFormat, current, attemptsTotal)
    }

    private fun clearEffects() {
        targetVisible = false
        hitActive = false
        falseStartActive = false
        attemptsDone = 0
        particles.clear()
        updateProgressText()
    }

    /** Rebuilds the target gradients; cheap, and only when the radius actually changed. */
    private fun ensureShaders(radius: Float) {
        if (radius <= 0f || radius == shaderRadius) return
        shaderRadius = radius

        bodyPaint.shader = RadialGradient(
            0f, -radius * 0.25f, radius * 1.3f,
            intArrayOf(TARGET_LIGHT, TARGET_MID, TARGET_DARK),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        glossPaint.shader = RadialGradient(
            -radius * 0.3f, -radius * 0.38f, radius * 0.62f,
            GLOSS_INNER, GLOSS_OUTER,
            Shader.TileMode.CLAMP,
        )
        shadowPaint.shader = RadialGradient(
            0f, 0f, radius * SHADOW_RADIUS_FACTOR,
            intArrayOf(SHADOW_INNER, SHADOW_INNER, SHADOW_OUTER),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * Pre-renders the tiled grass into one bitmap, the way the legacy view did — tiling per frame
     * would be dozens of draw calls for a picture that never changes.
     *
     * The tile is seamless (see `art/README.md`), so it is laid edge to edge with no jitter and no
     * overlap: offsetting it, as the old non-tileable texture needed, would break the very seam the
     * art pipeline was built to hide.
     */
    private fun buildBackground(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val tile = grassTile ?: decodeGrass()?.also { grassTile = it } ?: return null

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "cannot allocate the background bitmap", e)
            return null
        }
        val canvas = Canvas(bitmap)
        canvas.drawColor(GRASS_FALLBACK_COLOR)

        val tileW = tile.width.toFloat()
        val tileH = tile.height.toFloat()
        if (tileW < 1f || tileH < 1f) return bitmap.also { background = it }

        var y = 0f
        while (y < height) {
            var x = 0f
            while (x < width) {
                canvas.drawBitmap(tile, x, y, bitmapPaint)
                x += tileW
            }
            y += tileH
        }
        background = bitmap
        return bitmap
    }

    private fun decodeGrass(): Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.grass_tile)
    } catch (e: Exception) {
        Log.w(TAG, "cannot decode the grass tile", e)
        null
    }

    private fun recycleBackground() {
        background?.recycle()
        background = null
    }

    private fun recycleFaces() {
        for (i in faces.indices) {
            faces[i]?.recycle()
            faces[i] = null
        }
        facePixels = 0
    }

    private fun textPaint(sizeSp: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sizeSp,
            appContext.resources.displayMetrics,
        )
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        // Text is the one case where a hardware canvas does honour a shadow layer.
        setShadowLayer(3f * density, 0f, 1.5f * density, TEXT_SHADOW_COLOR)
    }

    private fun Bitmap.scale(pixels: Int): Bitmap = try {
        Bitmap.createScaledBitmap(this, pixels, pixels, true)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "cannot scale a face to $pixels px", e)
        this
    }

    private companion object {
        const val TAG = "GameRenderer"

        /** The ten monster faces, in resource order. */
        val FACE_RES_IDS = intArrayOf(
            R.drawable.target_face_01,
            R.drawable.target_face_02,
            R.drawable.target_face_03,
            R.drawable.target_face_04,
            R.drawable.target_face_05,
            R.drawable.target_face_06,
            R.drawable.target_face_07,
            R.drawable.target_face_08,
            R.drawable.target_face_09,
            R.drawable.target_face_10,
        )

        /** Edge length of the face masters in `res/drawable-nodpi`, px. */
        const val FACE_SOURCE_PIXELS = 256

        /** Enough for one grass puff plus one full hit burst, with room to overlap. */
        const val PARTICLE_CAPACITY = 48

        const val GRASS_DUST_COUNT = 10
        const val HIT_SPARK_COUNT = 12
        const val MISS_PUFF_COUNT = 8

        val TARGET_LIGHT = 0xFFFF7A6BL.toInt()
        val TARGET_MID = 0xFFE53329L.toInt()
        val TARGET_DARK = 0xFF9C1410L.toInt()
        val GLOSS_INNER = 0x80FFFFFFL.toInt()
        val GLOSS_OUTER = 0x00FFFFFFL.toInt()
        val SHADOW_INNER = 0x73000000L.toInt()
        val SHADOW_OUTER = 0x00000000L.toInt()
        val TEXT_SHADOW_COLOR = 0xB3000000L.toInt()
        val TOAST_BG_COLOR = 0xB3101010L.toInt()
        val HUD_TEXT_COLOR = 0xFFFFFFFFL.toInt()
        val HUD_DOT_DONE_COLOR = 0xFFFFE04DL.toInt()
        val HUD_DOT_TODO_COLOR = 0x66FFFFFFL.toInt()
        val GRASS_FALLBACK_COLOR = 0xFF3F6B2AL.toInt()

        /** Grass and earth tones for the "pop out of the ground" puff. */
        val GRASS_DUST_COLORS = intArrayOf(
            0xFF8FB25AL.toInt(),
            0xFF6E8F42L.toInt(),
            0xFFB9C77AL.toInt(),
            0xFF7A6A4AL.toInt(),
        )

        /** Neutral dust for a missed tap. */
        val DUST_COLORS = intArrayOf(
            0xFFDBD3C2L.toInt(),
            0xFFB3A996L.toInt(),
            0xFF8D8674L.toInt(),
        )

        /** Cartoon spark colours for the hit burst. */
        val SPARK_COLORS = intArrayOf(
            0xFFFFE25AL.toInt(),
            0xFFFFFFFFL.toInt(),
            0xFFFFB03AL.toInt(),
            0xFFFF6F61L.toInt(),
        )

        /**
         * Colour of the floating "231 ms", by [Verdict] tier 1..5 — the slower the tap, the cooler
         * and quieter the colour; a superhuman one comes out in celebratory gold.
         */
        val TIER_TEXT_COLORS = intArrayOf(
            0xFFE8E8E8L.toInt(),
            0xFFBFE6A0L.toInt(),
            0xFF8FE3FFL.toInt(),
            0xFFFFD166L.toInt(),
            0xFFFFF176L.toInt(),
        )

        const val HUD_ALPHA = 230
        const val TOAST_BG_ALPHA = 179
        const val RING_STROKE_DP = 3f

        /** The ring is now a supporting act behind the sparks, so it is drawn fainter than before. */
        const val RING_ALPHA_FACTOR = 0.55f
        const val SHADOW_OFFSET_FACTOR = 0.18f
        const val SHADOW_RADIUS_FACTOR = 1.12f

        /** Largest power-of-two decode reduction that still leaves the face above its drawn size. */
        fun sampleSizeFor(pixels: Int): Int {
            var sample = 1
            while (FACE_SOURCE_PIXELS / (sample * 2) >= pixels && sample < 8) sample *= 2
            return sample
        }
    }
}
