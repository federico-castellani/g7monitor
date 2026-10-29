package it.federico.g7monitor.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.ui.Glucose
import it.federico.g7monitor.ui.theme.LocalRangeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Glucose chart drawn directly on a Canvas: target band, gridlines, points coloured
 * by range, a connecting trace (broken on gaps) and touch-to-inspect.
 */
@Composable
fun GlucoseChart(
    readings: List<GlucoseReading>,
    /** Meter results: drawn as outlined diamonds with their value. */
    fingersticks: List<Fingerstick> = emptyList(),
    windowHours: Int,
    now: Long,
    lowMgdl: Int,
    highMgdl: Int,
    mmol: Boolean,
    modifier: Modifier = Modifier,
) {
    val range = LocalRangeColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = scheme.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }
    var touchX by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(260.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null })
            }
            .pointerInput(Unit) {
                // Horizontal only, so the page can still scroll vertically over the chart.
                detectHorizontalDragGestures(
                    onDragStart = { touchX = it.x },
                    onDragEnd = { touchX = null },
                    onDragCancel = { touchX = null },
                ) { change, _ -> touchX = change.position.x }
            },
    ) {
        val leftPad = 36.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val topPad = 8.dp.toPx()
        val plotW = size.width - leftPad
        val plotH = size.height - bottomPad - topPad

        val start = now - windowHours * 3_600_000L
        val end = now + windowHours * 3_600_000L / 30 // small breathing room on the right
        val maxData = readings.maxOfOrNull { it.mgdl } ?: 0
        val minData = readings.minOfOrNull { it.mgdl } ?: 400
        val yMax = maxOf(250, ((maxData + 25) / 50 + 1) * 50).coerceAtMost(400).toFloat()
        val yMin = minOf(40, minData - 10).coerceAtLeast(30).toFloat()

        fun x(t: Long) = leftPad + (t - start).toFloat() / (end - start) * plotW
        fun y(mgdl: Float) = topPad + (1f - (mgdl - yMin) / (yMax - yMin)) * plotH
        fun colorOf(mgdl: Int) = when {
            mgdl < lowMgdl -> range.low
            mgdl > highMgdl -> range.high
            else -> range.inRange
        }

        // Target band
        drawRoundRect(
            color = range.inRange.copy(alpha = 0.10f),
            topLeft = Offset(leftPad, y(highMgdl.toFloat())),
            size = Size(plotW, y(lowMgdl.toFloat()) - y(highMgdl.toFloat())),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )

        // Horizontal grid + labels
        val step = if (mmol) 3 * Glucose.MGDL_PER_MMOL.toFloat() else 50f
        val gridColor = scheme.outlineVariant.copy(alpha = 0.6f)
        var gv = (yMin / step).toInt().plus(1) * step
        while (gv < yMax) {
            val yy = y(gv)
            drawLine(gridColor, Offset(leftPad, yy), Offset(size.width, yy), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            val label = if (mmol) (gv / Glucose.MGDL_PER_MMOL).toInt().toString() else gv.toInt().toString()
            val m = measurer.measure(label, labelStyle)
            drawText(m, topLeft = Offset(leftPad - m.size.width - 6.dp.toPx(), yy - m.size.height / 2))
            gv += step
        }
        for (limit in listOf(lowMgdl, highMgdl)) {
            val yy = y(limit.toFloat())
            drawLine(range.inRange.copy(alpha = 0.5f), Offset(leftPad, yy), Offset(size.width, yy), 1.5f)
        }

        // Time labels
        val hourStep = when (windowHours) { 3 -> 1; 6 -> 1; 12 -> 3; else -> 6 }
        val hourMs = 3_600_000L
        var t = (start / hourMs + 1) * hourMs
        val tz = java.util.TimeZone.getDefault()
        while (t < end) {
            val localHour = ((t + tz.getOffset(t)) / hourMs % 24).toInt()
            if (localHour % hourStep == 0) {
                val xx = x(t)
                drawLine(gridColor.copy(alpha = 0.3f), Offset(xx, topPad), Offset(xx, topPad + plotH), 1f)
                val m = measurer.measure(timeFmt.format(Date(t)), labelStyle)
                drawText(m, topLeft = Offset(xx - m.size.width / 2, size.height - m.size.height))
            }
            t += hourMs
        }

        // Trace (broken on gaps > 12 min)
        val visible = readings.filter { it.timestamp in start..end }
        val path = Path()
        var last: GlucoseReading? = null
        for (r in visible) {
            val px = x(r.timestamp); val py = y(r.mgdl.toFloat())
            if (last == null || r.timestamp - last.timestamp > 12 * 60_000L) path.moveTo(px, py) else path.lineTo(px, py)
            last = r
        }
        drawPath(path, scheme.onSurface.copy(alpha = 0.18f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        val dotR = if (windowHours <= 6) 3.2.dp.toPx() else 2.4.dp.toPx()
        for (r in visible) drawCircle(colorOf(r.mgdl), dotR, Offset(x(r.timestamp), y(r.mgdl.toFloat())))

        // Meter results: diamond with a light outline, value above
        val half = 6.dp.toPx()
        for (f in fingersticks) {
            if (f.timestamp !in start..end) continue
            val cx = x(f.timestamp)
            val cy = y(f.mgdl.toFloat().coerceIn(yMin, yMax))
            val diamond = Path().apply {
                moveTo(cx, cy - half); lineTo(cx + half, cy); lineTo(cx, cy + half); lineTo(cx - half, cy); close()
            }
            drawPath(diamond, colorOf(f.mgdl))
            drawPath(diamond, scheme.surface, style = Stroke(width = 1.5.dp.toPx()))
            val m = measurer.measure(Glucose.format(f.mgdl, mmol), labelStyle)
            drawText(m, topLeft = Offset(cx - m.size.width / 2, (cy - half - m.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
        }

        // "Now" marker
        val nowX = x(now)
        drawLine(scheme.primary.copy(alpha = 0.35f), Offset(nowX, topPad), Offset(nowX, topPad + plotH), 1.5f)

        // Touch inspection
        val tx = touchX
        if (tx != null && visible.isNotEmpty()) {
            val nearest = visible.minBy { abs(x(it.timestamp) - tx) }
            val px = x(nearest.timestamp); val py = y(nearest.mgdl.toFloat())
            drawLine(scheme.onSurface.copy(alpha = 0.4f), Offset(px, topPad), Offset(px, topPad + plotH), 1.5f)
            drawCircle(Color.White, dotR * 2.2f, Offset(px, py))
            drawCircle(colorOf(nearest.mgdl), dotR * 1.6f, Offset(px, py))
            val text = "${Glucose.format(nearest.mgdl, mmol)} · ${timeFmt.format(Date(nearest.timestamp))}"
            val m = measurer.measure(text, tipStyle)
            val pad = 8.dp.toPx()
            val bw = m.size.width + pad * 2
            val bx = (px - bw / 2).coerceIn(leftPad, size.width - bw)
            val by = (py - m.size.height - pad * 3).coerceAtLeast(0f)
            drawRoundRect(scheme.surfaceContainerHighest, Offset(bx, by), Size(bw, m.size.height + pad), CornerRadius(8.dp.toPx()))
            drawText(m, topLeft = Offset(bx + pad, by + pad / 2))
        }
    }
}
