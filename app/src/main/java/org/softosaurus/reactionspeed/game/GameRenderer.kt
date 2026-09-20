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
import kotlin.math.max
import kotlin.random.Random
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
 * * All `Paint`s, `Shader`s and the tiled background are allocated once (or once per surface size),
 *   never per frame.
 * * No `BlurMaskFilter` and no `setShadowLayer` on shapes: neither is honoured by a hardware
 *   canvas. Soft edges come from [RadialGradient]s, which are.
 * * Geometry and animation curves live in [GameLayout] so they can be unit-tested.
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
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
    private val hitTextPaint = textPaint(sizeSp = 20f, bold = true).apply {
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
        updateProgressText()
    }

    /** Called when the series ends or is aborted. */
    fun onSeriesEnded() {
        clearEffects()
        seriesActive = false
    }

    fun onTargetShown(target: Target, nowMs: Long) {
        targetX = target.x
        targetY = target.y
        targetRadius = target.radiusPx
        targetVisible = true
        targetShownAtMs = nowMs
        ensureShaders(target.radiusPx)
    }

    /** Called when the target was hit at ([x], [y]) with the measured [reactionMs]. */
    fun onHit(x: Float, y: Float, reactionMs: Int, nowMs: Long) {
        hitActive = true
        hitX = x
        hitY = y
        hitRadius = if (targetRadius > 0f) targetRadius else 24f * density
        hitStartedAtMs = nowMs
        // Formatted once: this string is redrawn for ~600 ms and must not allocate per frame.
        hitText = String.format(Locale.getDefault(), reactionFormat, reactionMs)
        targetVisible = false
        attemptsDone += 1
        updateProgressText()
    }

    fun onFalseStart(nowMs: Long) {
        falseStartActive = true
        falseStartAtMs = nowMs
        falseStartHalfWidth = toastTextPaint.measureText(falseStartText) / 2f
    }

    /** True while something on screen still moves and the loop must keep pacing frames. */
    fun isAnimating(nowMs: Long): Boolean {
        if (targetVisible && nowMs - targetShownAtMs <= GameLayout.POP_DURATION_MS) return true
        if (hitActive && nowMs - hitStartedAtMs <= GameLayout.HIT_EFFECT_DURATION_MS) return true
        if (falseStartActive && nowMs - falseStartAtMs <= GameLayout.FALSE_START_DURATION_MS) return true
        return false
    }

    /** Draws one full frame. The canvas contents are always undefined on entry, so nothing is reused. */
    fun draw(canvas: Canvas, nowMs: Long) {
        drawBackground(canvas)
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
        val scale = GameLayout.popScale(nowMs - targetShownAtMs)
        val r = targetRadius

        // Soft drop shadow: a radial gradient, because setShadowLayer is text-only on a HW canvas.
        canvas.save()
        canvas.translate(targetX, targetY + r * SHADOW_OFFSET_FACTOR * scale)
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, r * SHADOW_RADIUS_FACTOR, shadowPaint)
        canvas.restore()

        canvas.save()
        canvas.translate(targetX, targetY)
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, r, bodyPaint)
        canvas.drawCircle(0f, 0f, r, glossPaint)
        canvas.restore()
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

        ringPaint.alpha = alpha
        ringPaint.strokeWidth = max(1f, RING_STROKE_DP * density * (1f - p))
        canvas.drawCircle(hitX, hitY, GameLayout.hitRingRadius(hitRadius, p), ringPaint)

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
     * The jitter is seeded from the surface size, so the same device always gets the same meadow
     * (no flicker when the surface is recreated after a rotation or a pause).
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

        val random = Random(width * 31L + height)
        val jitterX = tileW * 0.12f
        val jitterY = tileH * 0.12f
        var y = -tileH
        while (y < height + tileH) {
            var x = -tileW
            while (x < width + tileW) {
                val dx = (random.nextFloat() - 0.5f) * 2f * jitterX
                val dy = (random.nextFloat() - 0.5f) * 2f * jitterY
                canvas.drawBitmap(tile, x + dx, y + dy, bitmapPaint)
                x += tileW * 0.9f
            }
            y += tileH * 0.9f
        }
        background = bitmap
        return bitmap
    }

    private fun decodeGrass(): Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.trava)
    } catch (e: Exception) {
        Log.w(TAG, "cannot decode the grass tile", e)
        null
    }

    private fun recycleBackground() {
        background?.recycle()
        background = null
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

    private companion object {
        const val TAG = "GameRenderer"

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

        const val HUD_ALPHA = 230
        const val TOAST_BG_ALPHA = 179
        const val RING_STROKE_DP = 4f
        const val SHADOW_OFFSET_FACTOR = 0.18f
        const val SHADOW_RADIUS_FACTOR = 1.12f
    }
}
