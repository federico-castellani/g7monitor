package it.federico.g7monitor.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    private val min = 60_000L

    @Test
    fun movingAverageSmoothsNoiseButNotAcrossGaps() {
        val t = longArrayOf(0, 5 * min, 10 * min, 15 * min, 60 * min)
        val v = floatArrayOf(100f, 120f, 100f, 120f, 200f)
        val s = ChartMath.movingAverage(t, v, passes = 1)
        assertEquals(110f, s[1], 0.01f) // (100 + 240 + 100) / 4
        assertEquals(200f, s[4], 0.01f) // alone after a 45-minute gap: unchanged
        // the zigzag shrinks
        assertTrue(s.copyOfRange(0, 4).let { it.max() - it.min() } < 20f)
    }

    @Test
    fun movingAverageKeepsAFlatLineFlat() {
        val t = LongArray(6) { it * 5 * min }
        val v = FloatArray(6) { 130f }
        assertArrayEquals(v, ChartMath.movingAverage(t, v, passes = 2), 0.001f)
    }

    @Test
    fun curveNeverOvershootsTheReadings() {
        // a sharp step: a plain spline would swing above and below; the monotone one must not
        val xs = floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f)
        val ys = floatArrayOf(100f, 100f, 100f, 200f, 200f, 200f)
        val m = ChartMath.monotoneSlopes(xs, ys)
        for (i in 0 until xs.size - 1) {
            val c = ChartMath.controls(xs, ys, m, i)
            assertTrue("segment $i", ChartMath.staysBetween(ys[i], c[1], c[3], ys[i + 1]))
        }
        // flat parts stay flat
        assertEquals(0f, m[0], 0f)
        assertEquals(0f, m[5], 0f)
    }

    @Test
    fun curvePassesThroughEveryReading() {
        val xs = floatArrayOf(0f, 10f, 20f, 30f)
        val ys = floatArrayOf(120f, 135f, 128f, 150f)
        val m = ChartMath.monotoneSlopes(xs, ys)
        for (i in 0 until xs.size - 1) {
            val c = ChartMath.controls(xs, ys, m, i)
            assertEquals(xs[i] + 10f / 3, c[0], 0.001f) // Bézier from point i…
            assertEquals(xs[i + 1] - 10f / 3, c[2], 0.001f) // …to point i+1: the curve ends on the readings
        }
        assertEquals(0f, m[2], 0f) // a peak in the readings: flat there, no invented higher peak
    }
}
