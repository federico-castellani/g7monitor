package it.federico.g7monitor.ui

import it.federico.g7monitor.data.GlucoseReading
import java.util.Locale
import kotlin.math.abs

/** Unit conversion and trend helpers shared by UI and notification. */
object Glucose {
    const val MGDL_PER_MMOL = 18.0182

    /**
     * What the sensor read at [time], to compare with a meter result: interpolated between the
     * readings around it (at most 12 minutes apart), or the nearest one within 5 minutes.
     * @param readings sorted by time
     */
    fun sensorAt(readings: List<GlucoseReading>, time: Long): Int? {
        val i = readings.binarySearchBy(time) { it.timestamp }.let { if (it >= 0) return readings[it].mgdl else -it - 1 }
        val before = readings.getOrNull(i - 1)
        val after = readings.getOrNull(i)
        if (before != null && after != null && after.timestamp - before.timestamp <= 12 * 60_000L) {
            val f = (time - before.timestamp).toDouble() / (after.timestamp - before.timestamp)
            return Math.round(before.mgdl + (after.mgdl - before.mgdl) * f).toInt()
        }
        return listOfNotNull(before, after).filter { abs(it.timestamp - time) <= 5 * 60_000L }
            .minByOrNull { abs(it.timestamp - time) }?.mgdl
    }

    /** "+7 mg/dL" / "−0,4 mmol/L": meter minus sensor. */
    fun formatDifference(meterMgdl: Int, sensorMgdl: Int, mmol: Boolean) =
        "${formatDelta(meterMgdl - sensorMgdl, mmol)} ${unit(mmol)}"

    fun format(mgdl: Int, mmol: Boolean): String = when {
        mgdl <= 39 -> "LOW"
        mgdl >= 401 -> "HIGH"
        mmol -> String.format(Locale.ITALY, "%.1f", mgdl / MGDL_PER_MMOL)
        else -> mgdl.toString()
    }

    fun formatDelta(deltaMgdl: Int, mmol: Boolean): String {
        val sign = if (deltaMgdl >= 0) "+" else "−"
        return if (mmol) sign + String.format(Locale.ITALY, "%.1f", abs(deltaMgdl) / MGDL_PER_MMOL)
        else sign + abs(deltaMgdl)
    }

    fun unit(mmol: Boolean) = if (mmol) "mmol/L" else "mg/dL"

    fun toDisplay(mgdl: Double, mmol: Boolean): Double = if (mmol) mgdl / MGDL_PER_MMOL else mgdl

    enum class Trend(val arrow: String, val label: String, val angle: Float, val double: Boolean = false) {
        DoubleUp("⇈", "In rapida salita", -90f, true),
        Up("↑", "In salita", -90f),
        FortyFiveUp("↗", "In leggera salita", -45f),
        Flat("→", "Stabile", 0f),
        FortyFiveDown("↘", "In leggera discesa", 45f),
        Down("↓", "In discesa", 90f),
        DoubleDown("⇊", "In rapida discesa", 90f, true),
    }

    /** Dexcom-style thresholds in mg/dL per minute. */
    fun trend(rate: Double?): Trend? = when {
        rate == null || rate.isNaN() -> null
        rate >= 3 -> Trend.DoubleUp
        rate >= 2 -> Trend.Up
        rate >= 1 -> Trend.FortyFiveUp
        rate > -1 -> Trend.Flat
        rate > -2 -> Trend.FortyFiveDown
        rate > -3 -> Trend.Down
        else -> Trend.DoubleDown
    }

    fun arrow(rate: Double?): String = trend(rate)?.arrow ?: ""
}
