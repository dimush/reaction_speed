package org.softosaurus.reactionspeed.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The history graph: one point per finished series, milliseconds on the vertical axis.
 *
 * Long histories are bucket-averaged down to [MAX_POINTS] before drawing — the repository keeps up
 * to 2000 scores and a path with that many segments is both slow and unreadable at phone width.
 */
@Composable
fun HistoryChart(
    values: List<Int>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val points = remember(values) { downsample(values, MAX_POINTS) }

    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        if (points.isEmpty()) return@Canvas

        val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)
        val axisWidth = measurer.measure("0000", labelStyle).size.width + 6.dp.toPx()
        val chartLeft = axisWidth
        val chartRight = size.width
        val chartTop = 6.dp.toPx()
        // The series indices are printed below the plot, never on top of it.
        val captionHeight = measurer.measure("0", labelStyle).size.height.toFloat()
        val chartBottom = size.height - captionHeight - 4.dp.toPx()
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
        val chartWidth = (chartRight - chartLeft).coerceAtLeast(1f)

        val axis = axisRange(points.min(), points.max())

        fun yOf(value: Float) =
            chartBottom - (value - axis.min) / (axis.max - axis.min) * chartHeight

        var line = axis.min
        while (line <= axis.max + HALF) {
            val y = yOf(line)
            drawLine(
                color = gridColor,
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val label = measurer.measure(line.toInt().toString(), labelStyle)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    x = chartLeft - label.size.width - 4.dp.toPx(),
                    y = y - label.size.height / 2f,
                ),
            )
            line += axis.step
        }

        if (points.size == 1) {
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(chartLeft + chartWidth / 2f, yOf(points[0].toFloat())))
            return@Canvas
        }

        val stepX = chartWidth / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = chartLeft + index * stepX
            val y = yOf(value.toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(
            color = lineColor,
            radius = 3.dp.toPx(),
            center = Offset(chartLeft + (points.size - 1) * stepX, yOf(points.last().toFloat())),
        )
        drawXAxisLabels(measurer, labelStyle, chartLeft, chartRight, chartBottom, points.size, values.size)
    }
}

/** X-axis captions: the index of the first and of the last drawn series. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawXAxisLabels(
    measurer: TextMeasurer,
    style: TextStyle,
    left: Float,
    right: Float,
    bottom: Float,
    drawnPoints: Int,
    totalPoints: Int,
) {
    if (drawnPoints < 2) return
    val first = measurer.measure("1", style)
    val last = measurer.measure(totalPoints.toString(), style)
    drawText(first, topLeft = Offset(left, bottom + 4.dp.toPx()))
    drawText(last, topLeft = Offset(right - last.size.width, bottom + 4.dp.toPx()))
}

/** Rounded bounds and grid step of the vertical axis. */
internal data class AxisRange(val min: Float, val max: Float, val step: Float)

/**
 * Picks a "nice" 1/2/5 × 10^k grid step, the way the legacy `calcStep` did, and snaps the bounds
 * outwards to a multiple of it. A flat history (all values equal) still gets a readable band.
 */
internal fun axisRange(minValue: Int, maxValue: Int, targetLines: Int = 4): AxisRange {
    if (maxValue <= minValue) {
        // One point, or a perfectly flat history: give it a band of its own instead of a pile of
        // grid lines that all carry the same label.
        val pad = (maxValue / 20).coerceAtLeast(5)
        return axisRange(minValue - pad, maxValue + pad, targetLines)
    }
    val span = maxValue - minValue
    val raw = span.toFloat() / targetLines
    val magnitude = 10f.pow(floor(log10(raw.toDouble())).toFloat())
    val normalised = raw / magnitude
    // Milliseconds are integers, so a sub-1 step would only repeat labels.
    val step = (magnitude * when {
        normalised <= 1f -> 1f
        normalised <= 2f -> 2f
        normalised <= 5f -> 5f
        else -> 10f
    }).coerceAtLeast(1f)
    val min = floor(minValue / step) * step
    val max = ceil(maxValue / step) * step
    return AxisRange(min, if (abs(max - min) < HALF) min + step else max, step)
}

/** Bucket-averages [values] down to at most [limit] points, keeping the chronological shape. */
internal fun downsample(values: List<Int>, limit: Int): List<Int> {
    if (values.size <= limit) return values
    val bucket = ceil(values.size.toDouble() / limit).toInt()
    return values.chunked(bucket) { chunk -> chunk.average().toInt() }
}

private const val MAX_POINTS = 240
private const val HALF = 0.5f
