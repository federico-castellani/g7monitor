package it.federico.g7monitor.ui

import java.util.Locale
import kotlin.math.abs

/** Unit conversion and trend helpers shared by UI and notification. */
object Glucose {
    const val MGDL_PER_MMOL = 18.0182

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
