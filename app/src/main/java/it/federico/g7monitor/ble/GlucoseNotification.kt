package it.federico.g7monitor.ble

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.widget.RemoteViews
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.ui.Glucose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** What the status notification shows; compared to avoid rebuilding an identical notification. */
data class GlucoseNotificationData(
    val latest: GlucoseReading,
    val previous: GlucoseReading?,
    val readings: List<GlucoseReading>,
    val fingersticks: List<Fingerstick>,
    val status: String,
    val mmol: Boolean,
    val low: Int,
    val high: Int,
)

/**
 * Status notification with the value coloured by range, the trend and a chart of the last
 * hours, as xDrip+ does: a small sparkline when collapsed, a larger chart when expanded.
 * The charts are bitmaps kept small (Android limits the size of a notification).
 */
object GlucoseNotification {
    const val HOURS = 3
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ITALY)

    /**
     * The last [HOURS] of data for the notification or the widget.
     * @param maxAgeMs null when the newest reading is older than this
     */
    suspend fun load(app: G7App, status: String, mmol: Boolean, low: Int, high: Int, maxAgeMs: Long): GlucoseNotificationData? {
        val now = System.currentTimeMillis()
        val from = now - HOURS * 3_600_000L
        val recent = app.readings.since(from)
        val latest = recent.lastOrNull()?.takeIf { now - it.timestamp <= maxAgeMs } ?: return null
        return GlucoseNotificationData(
            latest, recent.getOrNull(recent.size - 2), recent, app.readings.fingersticksSince(from), status, mmol, low, high,
        )
    }

    fun time(millis: Long): String = timeFmt.format(Date(millis))

    /** Value colour for [mgdl] in the current light/dark theme (grey when [stale]). */
    fun valueColor(context: Context, d: GlucoseNotificationData, stale: Boolean): Int {
        val p = palette(context)
        return if (stale) p.label else color(p, d.latest.mgdl, d.low, d.high)
    }

    /** Chart of the last [HOURS], [widthDp]×[heightDp]; detailed adds limit values and hour lines. */
    fun chartBitmap(context: Context, d: GlucoseNotificationData, widthDp: Int, heightDp: Int, detailed: Boolean): Bitmap =
        chart(context, d, palette(context), widthDp, heightDp, detailed)

    private class Palette(val low: Int, val inRange: Int, val high: Int, val line: Int, val grid: Int, val label: Int)

    private fun palette(context: Context): Palette {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (night) Palette(0xFFFF6B6F.toInt(), 0xFF4CD597.toInt(), 0xFFFFC145.toInt(), 0x66FFFFFF, 0x33FFFFFF, 0x99FFFFFF.toInt())
        else Palette(0xFFD93A3F.toInt(), 0xFF1F9D62.toInt(), 0xFFD98A00.toInt(), 0x55000000, 0x22000000, 0x99000000.toInt())
    }

    fun views(context: Context, d: GlucoseNotificationData, expanded: Boolean): RemoteViews {
        val p = palette(context)
        val v = RemoteViews(context.packageName, if (expanded) R.layout.notification_glucose_big else R.layout.notification_glucose)
        val stale = System.currentTimeMillis() - d.latest.timestamp > 15 * 60_000L
        v.setTextViewText(R.id.value, Glucose.format(d.latest.mgdl, d.mmol))
        v.setTextColor(R.id.value, if (stale) p.label else color(p, d.latest.mgdl, d.low, d.high))
        val delta = d.previous?.takeIf { d.latest.timestamp - it.timestamp < 12 * 60_000L }?.let { d.latest.mgdl - it.mgdl }
        val arrow = if (stale) "" else Glucose.arrow(d.latest.trend)
        val deltaText = delta?.let { "${Glucose.formatDelta(it, d.mmol)} ${Glucose.unit(d.mmol)}" } ?: Glucose.unit(d.mmol)
        val time = timeFmt.format(Date(d.latest.timestamp))
        val dm = context.resources.displayMetrics
        if (expanded) {
            v.setTextViewText(R.id.details, listOf(arrow, deltaText, "alle $time").filter { it.isNotEmpty() }.joinToString("  "))
            v.setTextViewText(R.id.status, d.status)
            val widthDp = (dm.widthPixels / dm.density - 64).toInt().coerceIn(240, 420)
            v.setImageViewBitmap(R.id.chart, chart(context, d, p, widthDp, 110, expanded = true))
        } else {
            // compact: value + arrow on top, change and time below, the sparkline takes the rest
            v.setTextViewText(R.id.arrow, arrow)
            v.setTextViewText(R.id.details, "$deltaText · $time")
            v.setImageViewBitmap(R.id.chart, chart(context, d, p, 170, 44, expanded = false))
        }
        return v
    }

    private fun color(p: Palette, mgdl: Int, low: Int, high: Int) = when {
        mgdl < low -> p.low
        mgdl > high -> p.high
        else -> p.inRange
    }

    private fun chart(context: Context, d: GlucoseNotificationData, p: Palette, widthDp: Int, heightDp: Int, expanded: Boolean): Bitmap {
        // Density capped: the bitmap travels inside the notification, which Android limits in size.
        val s = min(context.resources.displayMetrics.density, 1.75f)
        val w = (widthDp * s).toInt()
        val h = (heightDp * s).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val now = System.currentTimeMillis()
        val start = now - HOURS * 3_600_000L
        val values = d.readings.map { it.mgdl } + d.fingersticks.map { it.mgdl }
        val yMax = min(400, max(250, (values.maxOrNull() ?: 0) + 20)).toFloat()
        val yMin = max(30, min(40, (values.minOrNull() ?: 400) - 10)).toFloat()
        val left = if (expanded) 26 * s else 2 * s
        val right = w - 4 * s
        val top = 4 * s
        val bottom = h - (if (expanded) 4 else 2) * s
        fun x(t: Long) = left + (t - start).toFloat() / (now - start) * (right - left)
        fun y(v: Float) = top + (1 - (v - yMin) / (yMax - yMin)) * (bottom - top)

        // target band
        paint.color = p.inRange
        paint.alpha = 0x26
        c.drawRect(left, y(d.high.toFloat()), right, y(d.low.toFloat()), paint)
        // limits, with their values on the left when expanded
        paint.color = p.grid
        paint.strokeWidth = s
        paint.textSize = 10 * s
        for (limit in listOf(d.low, d.high)) {
            c.drawLine(left, y(limit.toFloat()), right, y(limit.toFloat()), paint)
            if (expanded) {
                paint.color = p.label
                c.drawText(Glucose.format(limit, d.mmol), 2 * s, y(limit.toFloat()) + 4 * s, paint)
                paint.color = p.grid
            }
        }
        // hour marks
        if (expanded) {
            var t = (start / 3_600_000L + 1) * 3_600_000L
            while (t < now) {
                c.drawLine(x(t), top, x(t), bottom, paint)
                t += 3_600_000L
            }
        }
        // trace, broken on gaps, then dots coloured by range
        paint.color = p.line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * s
        val path = Path()
        var prev: GlucoseReading? = null
        for (r in d.readings) {
            if (r.timestamp < start) continue
            val px = x(r.timestamp)
            val py = y(r.mgdl.toFloat())
            if (prev == null || r.timestamp - prev.timestamp > 12 * 60_000L) path.moveTo(px, py) else path.lineTo(px, py)
            prev = r
        }
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        val radius = (if (expanded) 2.6f else 1.8f) * s
        for (r in d.readings) {
            if (r.timestamp < start) continue
            paint.color = color(p, r.mgdl, d.low, d.high)
            c.drawCircle(x(r.timestamp), y(r.mgdl.toFloat()), radius, paint)
        }
        // meter results: diamonds
        val half = (if (expanded) 5f else 3.5f) * s
        for (f in d.fingersticks) {
            if (f.timestamp < start) continue
            val cx = x(f.timestamp)
            val cy = y(f.mgdl.toFloat().coerceIn(yMin, yMax))
            val diamond = Path().apply { moveTo(cx, cy - half); lineTo(cx + half, cy); lineTo(cx, cy + half); lineTo(cx - half, cy); close() }
            paint.color = color(p, f.mgdl, d.low, d.high)
            c.drawPath(diamond, paint)
        }
        return bmp
    }
}
