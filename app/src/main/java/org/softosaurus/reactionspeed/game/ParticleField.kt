package org.softosaurus.reactionspeed.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fixed-size pool of short-lived decorative particles.
 *
 * Everything is preallocated in parallel primitive arrays and reused forever: the playfield draws
 * these while a reaction is being measured, and an allocation in that loop is a garbage-collection
 * pause waiting to land on somebody's best-ever tap. Emitting past the capacity overwrites the
 * oldest particle instead of growing the pool, which is the right trade for confetti-grade visuals.
 *
 * Coordinates are surface pixels, velocities px/s. Single-threaded, like the rest of the renderer.
 */
class ParticleField(private val capacity: Int, density: Float) {

    /** A round speck of kicked-up grass or dust. */
    val kindDust = 0

    /** A four-pointed spark thrown off by a hit. */
    val kindSpark = 1

    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val size = FloatArray(capacity)
    private val spin = FloatArray(capacity)
    private val bornAt = LongArray(capacity)
    private val color = IntArray(capacity)
    private val kind = IntArray(capacity)
    private val alive = BooleanArray(capacity)

    private var next = 0
    private var aliveCount = 0

    private val gravity = GameLayout.PARTICLE_GRAVITY_DP * density

    /** Unit four-pointed star, built once and stamped with a canvas transform per particle. */
    private val starPath = Path().apply {
        moveTo(0f, -1f)
        quadTo(0.18f, -0.18f, 1f, 0f)
        quadTo(0.18f, 0.18f, 0f, 1f)
        quadTo(-0.18f, 0.18f, -1f, 0f)
        quadTo(-0.18f, -0.18f, 0f, -1f)
        close()
    }

    /** True while at least one particle is still on screen. */
    fun isAnimating(nowMs: Long): Boolean {
        if (aliveCount == 0) return false
        for (i in 0 until capacity) {
            if (alive[i] && nowMs - bornAt[i] <= GameLayout.PARTICLE_LIFETIME_MS) return true
        }
        return false
    }

    /** Drops every particle, e.g. when a series ends. */
    fun clear() {
        java.util.Arrays.fill(alive, false)
        aliveCount = 0
        next = 0
    }

    /**
     * Throws [count] particles outward from ([cx], [cy]).
     *
     * @param speed base outward speed in px/s; each particle varies by ±35 %
     * @param spreadFrom start of the angular arc, radians, 0 = to the right, positive = downwards
     * @param spreadTo end of the angular arc, radians
     * @param radius distance from the centre at which particles start
     * @param sizePx nominal particle size in px
     * @param colors palette; one is picked per particle
     * @param seed decorrelates two bursts emitted in the same millisecond
     */
    @Suppress("LongParameterList")
    fun emit(
        cx: Float,
        cy: Float,
        count: Int,
        speed: Float,
        spreadFrom: Float,
        spreadTo: Float,
        radius: Float,
        sizePx: Float,
        colors: IntArray,
        kind: Int,
        nowMs: Long,
        seed: Int,
    ) {
        if (count <= 0 || colors.isEmpty()) return
        var hash = seed * 0x9E3779B9.toInt() + nowMs.toInt()
        for (n in 0 until count) {
            hash = hash * 1_664_525 + 1_013_904_223
            val a = ((hash ushr 8) and 0xFFFF) / 65_535f
            hash = hash * 1_664_525 + 1_013_904_223
            val b = ((hash ushr 8) and 0xFFFF) / 65_535f
            hash = hash * 1_664_525 + 1_013_904_223
            val c = ((hash ushr 8) and 0xFFFF) / 65_535f

            val angle = spreadFrom + (spreadTo - spreadFrom) * ((n + a) / count)
            val cosA = cos(angle)
            val sinA = sin(angle)
            val v = speed * (0.65f + 0.7f * b)

            val i = next
            next = (next + 1) % capacity
            if (!alive[i]) aliveCount += 1
            alive[i] = true
            x[i] = cx + cosA * radius
            y[i] = cy + sinA * radius
            vx[i] = cosA * v
            vy[i] = sinA * v
            size[i] = sizePx * (0.6f + 0.8f * c)
            spin[i] = (c - 0.5f) * 720f
            bornAt[i] = nowMs
            color[i] = colors[(n + (hash ushr 16)).mod(colors.size)]
            this.kind[i] = kind
        }
    }

    /**
     * Draws every live particle and retires the expired ones.
     *
     * [paint] is the renderer's own scratch paint: its colour, alpha and style are overwritten here.
     */
    fun draw(canvas: Canvas, nowMs: Long, paint: Paint) {
        if (aliveCount == 0) return
        paint.style = Paint.Style.FILL
        paint.shader = null
        for (i in 0 until capacity) {
            if (!alive[i]) continue
            val elapsed = nowMs - bornAt[i]
            val p = GameLayout.progress(elapsed, GameLayout.PARTICLE_LIFETIME_MS)
            if (p >= 1f) {
                alive[i] = false
                aliveCount -= 1
                continue
            }
            val t = elapsed / 1000f
            val px = x[i] + vx[i] * t
            val py = y[i] + vy[i] * t + 0.5f * gravity * t * t
            val radius = size[i] * (1f - 0.55f * p)

            paint.color = color[i]
            paint.alpha = GameLayout.fadeOutAlpha(p, fadeFrom = 0.35f)
            if (kind[i] == kindSpark) {
                canvas.save()
                canvas.translate(px, py)
                canvas.rotate(spin[i] * p)
                canvas.scale(radius, radius)
                canvas.drawPath(starPath, paint)
                canvas.restore()
            } else {
                canvas.drawCircle(px, py, radius, paint)
            }
        }
    }
}
