package org.softosaurus.reactionspeed.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * A few seconds of paper falling across the result screen, for a personal best only.
 *
 * Cheap on purpose: the flakes are generated once into primitive arrays and then evaluated from a
 * single elapsed-time value, so a frame is 90 rectangles and no allocation. The composable stops
 * asking for frames the moment the last flake has fallen, which is why it takes its time from
 * [withFrameNanos] rather than an infinite transition that would keep the frame clock busy for as
 * long as the screen is up.
 *
 * Purely decorative — the "New personal best!" card says it in words — so it draws nothing at all
 * when the system's "remove animations" setting is on, and it is invisible to accessibility.
 */
@Composable
fun Confetti(modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (!enabled) return

    val flakes = remember { Flakes(COUNT) }
    var elapsedSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(flakes) {
        val startNanos = withFrameNanos { it }
        while (elapsedSeconds < DURATION_SECONDS) {
            val nanos = withFrameNanos { it }
            elapsedSeconds = (nanos - startNanos) / 1_000_000_000f
        }
    }

    val t = elapsedSeconds
    if (t >= DURATION_SECONDS) return

    Canvas(modifier.semantics { }) {
        val fade = ((DURATION_SECONDS - t) / FADE_SECONDS).coerceIn(0f, 1f)
        for (i in 0 until COUNT) {
            val life = t - flakes.delay[i]
            if (life <= 0f) continue
            val x = size.width * flakes.startX[i] +
                sin((life * flakes.swayRate[i] + flakes.phase[i]).toDouble()).toFloat() *
                flakes.swayAmplitude[i] * size.width
            val y = -FLAKE_MAX_PX + life * flakes.fallSpeed[i] * size.height
            if (y > size.height) continue

            val spin = life * flakes.spinRate[i]
            // A flake seen edge-on is a line: modulating the width by the spin is the whole 3-D.
            val width = flakes.size[i] * abs(sin(spin.toDouble())).toFloat().coerceAtLeast(0.15f)
            rotate(degrees = spin * SPIN_DEGREES, pivot = Offset(x, y)) {
                drawRect(
                    color = COLORS[i % COLORS.size].copy(alpha = fade),
                    topLeft = Offset(x - width / 2f, y - flakes.size[i] / 2f),
                    size = Size(width, flakes.size[i] * 1.6f),
                )
            }
        }
    }
}

/** One allocation for the whole burst; everything afterwards is arithmetic on these arrays. */
private class Flakes(count: Int) {
    private val random = Random(SEED)

    val startX = FloatArray(count) { random.nextFloat() }
    val delay = FloatArray(count) { random.nextFloat() * 1.1f }
    val fallSpeed = FloatArray(count) { 0.45f + random.nextFloat() * 0.55f }
    val swayAmplitude = FloatArray(count) { 0.02f + random.nextFloat() * 0.05f }
    val swayRate = FloatArray(count) { 1.8f + random.nextFloat() * 2.4f }
    val phase = FloatArray(count) { random.nextFloat() * 6.283f }
    val spinRate = FloatArray(count) { 3f + random.nextFloat() * 5f }
    val size = FloatArray(count) { 9f + random.nextFloat() * 9f }

    private companion object {
        /** Fixed, so the burst looks the same every time rather than occasionally coming out sparse. */
        const val SEED = 0x5EED
    }
}

private const val COUNT = 90
private const val DURATION_SECONDS = 3.6f
private const val FADE_SECONDS = 0.9f
private const val FLAKE_MAX_PX = 30f
private const val SPIN_DEGREES = 57f

private val COLORS = listOf(
    Color(0xFFFFD166),
    Color(0xFFEF476F),
    Color(0xFF06D6A0),
    Color(0xFF118AB2),
    Color(0xFFFFFFFF),
    Color(0xFFFF9F1C),
)
