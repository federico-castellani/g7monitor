package it.federico.g7monitor.ui.home

import kotlin.math.abs
import kotlin.math.sqrt

/** Smoothing of the chart line (pure functions, tested on the JVM). */
object ChartMath {

    /**
     * Moving average 1-2-1 over the neighbouring readings, repeated [passes] times. Readings more
     * than [gapMs] apart are separate runs: the average never bridges a gap in the data.
     */
    fun movingAverage(times: LongArray, values: FloatArray, passes: Int, gapMs: Long = 12 * 60_000L): FloatArray {
        var v = values.copyOf()
        repeat(passes) {
            val out = v.copyOf()
            for (i in v.indices) {
                val prev = (i - 1).takeIf { it >= 0 && times[i] - times[it] <= gapMs }
                val next = (i + 1).takeIf { it < v.size && times[it] - times[i] <= gapMs }
                out[i] = when {
                    prev != null && next != null -> (v[prev] + 2 * v[i] + v[next]) / 4
                    prev != null -> (v[prev] + 2 * v[i]) / 3
                    next != null -> (2 * v[i] + v[next]) / 3
                    else -> v[i]
                }
            }
            v = out
        }
        return v
    }

    /**
     * Slopes for a monotone cubic curve through the points (Fritsch–Carlson): the line passes
     * through every point and never overshoots, so a curve never shows a peak or a dip that the
     * readings do not have. [xs] must increase.
     */
    fun monotoneSlopes(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        if (n < 2) return FloatArray(n)
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / sqrt(h)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /** Control points of the cubic segment from point i to i+1, for Path.cubicTo. */
    fun controls(xs: FloatArray, ys: FloatArray, m: FloatArray, i: Int): FloatArray {
        val dx = (xs[i + 1] - xs[i]) / 3
        return floatArrayOf(xs[i] + dx, ys[i] + m[i] * dx, xs[i + 1] - dx, ys[i + 1] - m[i + 1] * dx)
    }

    /** True if the curve between two points stays between them (used by the tests). */
    internal fun staysBetween(y0: Float, c1: Float, c2: Float, y1: Float): Boolean {
        val lo = minOf(y0, y1) - 1e-3f
        val hi = maxOf(y0, y1) + 1e-3f
        return c1 in lo..hi && c2 in lo..hi || abs(y1 - y0) < 1e-6f
    }
}
