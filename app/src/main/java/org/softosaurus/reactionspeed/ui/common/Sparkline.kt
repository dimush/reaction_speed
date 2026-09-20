package org.softosaurus.reactionspeed.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A tiny, axis-less trend line of the most recent scores. Faster reactions are drawn higher, so
 * "up" always means "better".
 *
 * Fewer than two points draw nothing: a single dot would suggest a trend that does not exist.
 */
@Composable
fun Sparkline(
    values: List<Int>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.tertiary

    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val min = values.min().toFloat()
        val max = values.max().toFloat()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val inset = 3.dp.toPx()
        val usableHeight = (size.height - 2 * inset).coerceAtLeast(1f)

        fun pointAt(index: Int): Offset {
            // Inverted: the fastest value sits at the top.
            val normalised = (values[index] - min) / span
            return Offset(index * stepX, inset + normalised * usableHeight)
        }

        val path = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) {
                val p = pointAt(i)
                lineTo(p.x, p.y)
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(dotColor, radius = 3.dp.toPx(), center = pointAt(values.lastIndex))
    }
}
