package it.federico.g7monitor.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import it.federico.g7monitor.wear.GlucoseStore
import it.federico.g7monitor.wear.MainActivity
import it.federico.g7monitor.wear.R
import it.federico.g7monitor.wear.WatchGlucose
import java.time.Instant
import java.util.concurrent.TimeUnit

private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

/** "3 min" counting up from the reading: updated by the watch face itself, no wake-ups. */
private fun ago(g: WatchGlucose, format: String = "^1"): ComplicationText =
    TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(g.time)))
        .setMinimumTimeUnit(TimeUnit.MINUTES)
        .setText(format)
        .build()

/**
 * Base for the three complications. The timeline keeps the value for [WatchGlucose.STALE_MS]
 * after the reading, then the watch face switches by itself to the "stale" version ("---"):
 * the watch is never woken up just to age the data.
 */
abstract class GlucoseSource : SuspendingTimelineComplicationDataSourceService() {

    /** @param stale the reading is too old: hide the value. */
    abstract fun build(type: ComplicationType, g: WatchGlucose, stale: Boolean, tap: PendingIntent?): ComplicationData?

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val tap = openApp(this)
        val g = GlucoseStore.load(this) ?: return ComplicationDataTimeline(NoDataComplicationData(), emptyList())
        val fresh = build(request.complicationType, g, stale = false, tap) ?: return null
        val stale = build(request.complicationType, g, stale = true, tap) ?: return null
        val validity = TimeInterval(Instant.ofEpochMilli(g.time), Instant.ofEpochMilli(g.time + WatchGlucose.STALE_MS))
        return ComplicationDataTimeline(stale, listOf(TimelineEntry(validity, fresh)))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, WatchGlucose.SAMPLE, stale = false, tap = null)
}

/** "123↗" with the minutes since the reading; long text; or a gauge from 40 to 300 mg/dL. */
class GlucoseComplicationService : GlucoseSource() {
    override fun build(type: ComplicationType, g: WatchGlucose, stale: Boolean, tap: PendingIntent?): ComplicationData? {
        val value = if (stale) "---" else g.value
        val arrow = if (stale) "" else g.arrow
        val description = text(if (stale) getString(R.string.c_not_updated) else getString(R.string.c_glucose, g.value, g.unit, g.arrow))
        return when (type) {
            // Only the number as text: watch faces shrink a longer text ("123↗") to fit, and the glucose
            // came out smaller than the 2-character change. The arrow is the complication's icon, drawn
            // bigger and bolder than a character in the small title line; the minutes are on "variazione".
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(value), description)
                .apply { arrowIcon(arrow)?.let { setMonochromaticImage(it) } }
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> {
                val details = listOfNotNull(arrow.ifEmpty { null }, if (stale) null else g.deltaText).joinToString(" ")
                LongTextComplicationData.Builder(text("$value ${g.unit} $details".trim()), description)
                    .setTitle(ago(g, getString(R.string.c_ago)))
                    .setTapAction(tap)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = if (stale) MIN else g.mgdl.toFloat().coerceIn(MIN, MAX), min = MIN, max = MAX, contentDescription = description,
            )
                .setText(text(value))
                .setTitle(text(arrow.ifEmpty { if (stale) "" else g.unit }))
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    private fun arrowIcon(arrow: String): MonochromaticImage? {
        val res = when (arrow) {
            "⇈" -> R.drawable.ic_trend_double_up
            "↑" -> R.drawable.ic_trend_up
            "↗" -> R.drawable.ic_trend_up_right
            "→" -> R.drawable.ic_trend_flat
            "↘" -> R.drawable.ic_trend_down_right
            "↓" -> R.drawable.ic_trend_down
            "⇊" -> R.drawable.ic_trend_double_down
            else -> return null
        }
        return MonochromaticImage.Builder(Icon.createWithResource(this, res)).build()
    }

    private companion object {
        const val MIN = 40f
        const val MAX = 300f
    }
}

/** "+4" with the minutes since the reading below: change from the previous reading, and how fresh it is. */
class DeltaComplicationService : GlucoseSource() {
    override fun build(type: ComplicationType, g: WatchGlucose, stale: Boolean, tap: PendingIntent?): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val delta = if (stale) null else g.deltaText
        return ShortTextComplicationData.Builder(text(delta ?: "--"), text(delta?.let { getString(R.string.c_delta, it, g.unit) } ?: getString(R.string.c_delta_none)))
            .setTitle(ago(g))
            .setTapAction(tap)
            .build()
    }
}

/** Minutes since the last reading: useful next to a watch face's own glucose field. */
class AgeComplicationService : GlucoseSource() {
    override fun build(type: ComplicationType, g: WatchGlucose, stale: Boolean, tap: PendingIntent?): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return ShortTextComplicationData.Builder(ago(g), text(getString(R.string.c_age)))
            .setTitle(text(if (stale) "! G7" else "G7"))
            .setTapAction(tap)
            .build()
    }
}

private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

object Complications {
    private val SERVICES = listOf(
        GlucoseComplicationService::class.java,
        DeltaComplicationService::class.java,
        AgeComplicationService::class.java,
    )

    /** Asks every watch face using our complications to fetch the new data. */
    fun refresh(context: Context) {
        for (service in SERVICES) {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
        }
    }
}
