package it.federico.g7monitor.wear

import android.content.Context
import com.google.android.gms.wearable.DataMap
import java.util.Locale
import kotlin.math.abs

/** Keys of the data item sent by the phone (app/.../watch/WatchSync.kt): keep the two in sync. */
object WatchContract {
    const val PATH = "/g7monitor/glucose"
    const val TIME = "time"
    const val MGDL = "mgdl"
    const val TREND = "trend"
    const val DELTA = "delta"
    const val MMOL = "mmol"
    const val LOW = "low"
    const val HIGH = "high"
}

/** Latest reading received from the phone. */
data class WatchGlucose(
    val time: Long,
    val mgdl: Int,
    /** mg/dL per minute, null when unknown. */
    val trend: Double?,
    /** Difference from the previous reading, null when there was none close enough. */
    val delta: Int?,
    val mmol: Boolean,
    val low: Int,
    val high: Int,
) {
    val value: String
        get() = when {
            mgdl <= 39 -> "LOW"
            mgdl >= 401 -> "HIGH"
            mmol -> String.format(Locale.ITALY, "%.1f", mgdl / MGDL_PER_MMOL)
            else -> mgdl.toString()
        }

    val unit get() = if (mmol) "mmol/L" else "mg/dL"

    /** Same thresholds as the phone app (Dexcom style, mg/dL per minute). */
    val arrow: String
        get() {
            val r = trend ?: return ""
            return when {
                r >= 3 -> "⇈"
                r >= 2 -> "↑"
                r >= 1 -> "↗"
                r > -1 -> "→"
                r > -2 -> "↘"
                r > -3 -> "↓"
                else -> "⇊"
            }
        }

    val deltaText: String?
        get() {
            val d = delta ?: return null
            val sign = if (d >= 0) "+" else "−"
            return if (mmol) sign + String.format(Locale.ITALY, "%.1f", abs(d) / MGDL_PER_MMOL) else sign + abs(d)
        }

    fun isStale(now: Long = System.currentTimeMillis()) = now - time > STALE_MS

    companion object {
        const val MGDL_PER_MMOL = 18.0182
        /** After this the complications show "---" instead of the value. */
        const val STALE_MS = 15 * 60_000L

        fun from(map: DataMap) = WatchGlucose(
            time = map.getLong(WatchContract.TIME),
            mgdl = map.getInt(WatchContract.MGDL),
            trend = map.getDouble(WatchContract.TREND, Double.NaN).takeIf { it.isFinite() },
            delta = if (map.containsKey(WatchContract.DELTA)) map.getInt(WatchContract.DELTA) else null,
            mmol = map.getBoolean(WatchContract.MMOL),
            low = map.getInt(WatchContract.LOW, 70),
            high = map.getInt(WatchContract.HIGH, 180),
        )

        /** Shown in the watch face editor. */
        val SAMPLE = WatchGlucose(System.currentTimeMillis(), 123, 1.2, 4, mmol = false, low = 70, high = 180)
    }
}

/** Last reading, kept in SharedPreferences: read by the complications and the watch screen. */
object GlucoseStore {
    private const val PREFS = "glucose"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, g: WatchGlucose) {
        prefs(context).edit()
            .putLong("time", g.time).putInt("mgdl", g.mgdl)
            .putFloat("trend", g.trend?.toFloat() ?: Float.NaN)
            .putInt("delta", g.delta ?: Int.MIN_VALUE)
            .putBoolean("mmol", g.mmol).putInt("low", g.low).putInt("high", g.high)
            .apply()
    }

    fun load(context: Context): WatchGlucose? {
        val p = prefs(context)
        if (!p.contains("time")) return null
        return WatchGlucose(
            time = p.getLong("time", 0),
            mgdl = p.getInt("mgdl", 0),
            trend = p.getFloat("trend", Float.NaN).toDouble().takeIf { it.isFinite() },
            delta = p.getInt("delta", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            mmol = p.getBoolean("mmol", false),
            low = p.getInt("low", 70),
            high = p.getInt("high", 180),
        )
    }
}
