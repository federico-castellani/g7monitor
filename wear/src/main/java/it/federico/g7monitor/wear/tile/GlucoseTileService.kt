package it.federico.g7monitor.wear.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import it.federico.g7monitor.wear.GlucoseStore
import it.federico.g7monitor.wear.MainActivity
import it.federico.g7monitor.wear.R
import it.federico.g7monitor.wear.WatchGlucose
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The glucose tile: value coloured by range, trend arrow, change, time of the reading and the
 * last 3 hours as a small chart. Redrawn when a reading arrives (see [GlucoseTile.refresh]) and
 * every few minutes otherwise, so a missing reading turns it grey.
 */
class GlucoseTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val g = GlucoseStore.load(this)
        val history = GlucoseStore.loadHistory(this)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(version(g, history.size))
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(g, requestParams.deviceConfiguration)))
            .build()
        return done(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val res = ResourceBuilders.Resources.Builder().setVersion(requestParams.version)
        for (drawable in ARROW_RES.values) {
            res.addIdToImageMapping(
                "arrow_$drawable",
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(drawable).build())
                    .build(),
            )
        }
        val g = GlucoseStore.load(this)
        if (g != null) {
            val density = requestParams.deviceConfiguration.screenDensity
            val w = (CHART_W * density).toInt()
            val h = (CHART_H * density).toInt()
            val png = Sparkline.png(GlucoseStore.loadHistory(this), g, w, h, density)
            res.addIdToImageMapping(
                ID_CHART,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(png).setWidthPx(w).setHeightPx(h)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED) // an encoded PNG
                            .build()
                    )
                    .build(),
            )
        }
        return done(res.build())
    }

    private fun layout(g: WatchGlucose?, device: DeviceParameters): LayoutElement {
        val content = if (g == null) {
            text(getString(R.string.w_waiting), 13f, GREY, maxLines = 4)
        } else {
            val now = System.currentTimeMillis()
            val stale = g.isStale(now)
            val color = if (stale) STALE else rangeColor(g.mgdl, g)
            val time = if (stale) getString(R.string.w_no_reading_for, ((now - g.time) / 60_000L).toInt())
            else getString(R.string.tile_at, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(g.time)))
            val value = Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(text(if (stale) "---" else g.value, 44f, color, bold = true))
            arrowId(g.arrow)?.takeIf { !stale }?.let { id ->
                value.addContent(Spacer.Builder().setWidth(dp(4f)).build())
                value.addContent(
                    Image.Builder().setResourceId(id).setWidth(dp(26f)).setHeight(dp(26f))
                        .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(color)).build())
                        .build()
                )
            }
            Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text(time, 12f, GREY))
                .addContent(value.build())
                .addContent(text(listOfNotNull(g.deltaText?.takeIf { !stale }, g.unit).joinToString(" "), 13f, GREY))
                .addContent(Spacer.Builder().setHeight(dp(6f)).build())
                .addContent(Image.Builder().setResourceId(ID_CHART).setWidth(dp(CHART_W)).setHeight(dp(CHART_H)).build())
                .build()
        }
        // The whole tile opens the watch app.
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build()
                    )
                    .build()
            )
            .build()
        return Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(content)
            .build()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 1): LayoutElement =
        Text.Builder()
            .setText(s)
            .setMaxLines(maxLines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            )
            .build()

    private fun <T : Any> done(value: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { it.set(value); "tile" }

    private companion object {
        const val ID_CHART = "chart"
        const val CHART_W = 140f
        const val CHART_H = 40f
        /** Redrawn at least this often, so that an old value turns grey. */
        const val FRESHNESS_MS = 5 * 60_000L

        /** Trend arrow → drawable, the same as the complication's. */
        val ARROW_RES = mapOf(
            "⇈" to R.drawable.ic_trend_double_up, "↑" to R.drawable.ic_trend_up, "↗" to R.drawable.ic_trend_up_right,
            "→" to R.drawable.ic_trend_flat, "↘" to R.drawable.ic_trend_down_right, "↓" to R.drawable.ic_trend_down,
            "⇊" to R.drawable.ic_trend_double_down,
        )

        fun arrowId(arrow: String): String? = ARROW_RES[arrow]?.let { "arrow_$it" }

        /** A new reading or a change of unit gives the chart a new version, so it is fetched again. */
        fun version(g: WatchGlucose?, points: Int) = "${g?.time ?: 0}-$points-${g?.mmol}-${g?.low}-${g?.high}"
    }
}

/** Same colours as the watch app. */
internal const val LOW = 0xFFFF6B6F.toInt()
internal const val IN_RANGE = 0xFF4CD597.toInt()
internal const val HIGH = 0xFFFFC145.toInt()
internal const val STALE = 0xFF8A9099.toInt()
private const val GREY = 0xFFB0B6BD.toInt()

internal fun rangeColor(mgdl: Int, g: WatchGlucose) = when {
    mgdl < g.low -> LOW
    mgdl > g.high -> HIGH
    else -> IN_RANGE
}

/** The last 3 hours as a PNG: target band, the trace (broken on gaps) and a dot per reading. */
internal object Sparkline {
    private const val SPAN_MS = 3 * 3_600_000L

    fun png(points: List<Pair<Long, Int>>, g: WatchGlucose, w: Int, h: Int, density: Float): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val end = maxOf(System.currentTimeMillis(), g.time)
        val start = end - SPAN_MS
        val shown = points.filter { it.first in start..end }
        val yMax = maxOf(250, (shown.maxOfOrNull { it.second } ?: 0) + 20).coerceAtMost(400).toFloat()
        val yMin = 40f
        val pad = 3 * density
        fun x(t: Long) = pad + (t - start).toFloat() / SPAN_MS * (w - 2 * pad)
        fun y(v: Int) = pad + (1f - (v.coerceIn(yMin.toInt(), yMax.toInt()) - yMin) / (yMax - yMin)) * (h - 2 * pad)

        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = IN_RANGE; alpha = 40 }
        c.drawRoundRect(0f, y(g.high), w.toFloat(), y(g.low), 4 * density, 4 * density, band)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); alpha = 110; style = Paint.Style.STROKE; strokeWidth = 1.5f * density
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        shown.forEachIndexed { i, (t, v) ->
            if (i == 0 || t - shown[i - 1].first > 12 * 60_000L) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
        }
        c.drawPath(path, line)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((t, v) in shown) {
            dot.color = rangeColor(v, g)
            c.drawCircle(x(t), y(v), 1.6f * density, dot)
        }
        shown.lastOrNull()?.let { (t, v) ->
            dot.color = if (g.isStale()) STALE else rangeColor(v, g)
            c.drawCircle(x(t), y(v), 3f * density, dot)
        }
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}

object GlucoseTile {
    /** Asks the system to redraw the tile (a new reading has arrived). */
    fun refresh(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(GlucoseTileService::class.java) }
    }
}
