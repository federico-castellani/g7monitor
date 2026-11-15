package it.federico.g7monitor.predict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class GlucoseModelTest {
    private val min = 60_000L
    private val utc = { _: Long -> 0 }

    /** Days of readings every 5 minutes with meals at fixed hours, a night dip and sensor noise. */
    private fun history(days: Int, seed: Int = 1): Pair<LongArray, IntArray> {
        val rnd = Random(seed)
        val n = days * 288
        val t0 = 1_760_000_000_000L - 1_760_000_000_000L % (24 * 60 * min)
        val times = LongArray(n) { t0 + it * 5 * min }
        val values = IntArray(n) { i ->
            val h = (i % 288) / 12.0
            val meal = { c: Double, a: Double -> a * exp(-(h - c) * (h - c) / 1.2) }
            val night = if (h < 5) -25 * sin(h / 5 * PI) else 0.0
            (115 + meal(8.5, 70.0) + meal(13.5, 90.0) + meal(20.5, 80.0) + night + rnd.nextDouble(-8.0, 8.0)).toInt()
        }
        return times to values
    }

    @Test
    fun `learns only with enough days`() {
        val (t, v) = history(2)
        val r = GlucoseModel.train(t, v, utc)
        assertTrue(r is GlucoseModel.Result.Learning)
        assertTrue((r as GlucoseModel.Result.Learning).days < GlucoseModel.MIN_DAYS)
    }

    @Test
    fun `beats the plain trend on a routine day`() {
        val (t, v) = history(10)
        val r = GlucoseModel.train(t, v, utc) as GlucoseModel.Result.Ready
        for (a in r.model.accuracy.filter { it.horizon >= 30 }) {
            assertTrue("${a.horizon} min: ${a.modelMae} vs ${a.trendMae}", a.modelMae < a.trendMae)
        }
        // Readings rise after breakfast every day: half an hour before, the forecast must see it coming.
        val cut = t.indexOfLast { (it / min) % (24 * 60) == 7L * 60 + 45 }
        val points = GlucoseModel.forecast(r.model, t.copyOf(cut + 1), v.copyOf(cut + 1), now = t[cut], offset = utc)
        assertEquals(4, points.size)
        val at30 = points.first { it.time == t[cut] + 30 * min }
        assertTrue("expected a rise, got ${at30.mgdl} from ${v[cut]}", at30.mgdl > v[cut] + 10)
        assertTrue(points.all { it.low <= it.mgdl && it.mgdl <= it.high })
    }

    @Test
    fun `no forecast from an old reading`() {
        val (t, v) = history(10)
        val r = GlucoseModel.train(t, v, utc) as GlucoseModel.Result.Ready
        assertTrue(GlucoseModel.forecast(r.model, t, v, now = t.last() + 20 * min, offset = utc).isEmpty())
    }

    @Test
    fun `solver solves`() {
        val a = arrayOf(doubleArrayOf(4.0, 1.0), doubleArrayOf(1.0, 3.0))
        assertArrayEquals(doubleArrayOf(1.0 / 11, 7.0 / 11), GlucoseModel.solve(a, doubleArrayOf(1.0, 2.0)), 1e-9)
    }
}
