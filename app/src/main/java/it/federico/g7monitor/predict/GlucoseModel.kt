package it.federico.g7monitor.predict

import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Personal glucose forecast, learned on the phone from the user's own readings.
 *
 * For each horizon (15–60 minutes) a ridge regression predicts the change from the current value
 * from: the recent trend (5, 15, 30, 60 minutes), the level, the time of day, and the user's
 * typical day (median per half hour), so that it learns habits such as the rise after breakfast
 * or the dip at night. Before use it is checked on the most recent days it did not train on, and
 * its error is compared with a plain trend extrapolation. Plain Kotlin: tested on the JVM.
 */
object GlucoseModel {
    /** Minutes ahead. */
    val HORIZONS = intArrayOf(15, 30, 45, 60)

    /** Days of readings needed before the first forecast. */
    const val MIN_DAYS = 3.0

    /** Readings used for training: the last month is enough, and keeps up with changes in therapy. */
    const val TRAIN_DAYS = 30

    private const val MIN = 60_000L
    private const val DAY = 24 * 60 * MIN
    private const val SLOT = 30 * MIN
    private const val SLOTS = (DAY / SLOT).toInt()

    /** A reading counts for a moment within this distance (the G7 reads every 5 minutes). */
    private const val TOLERANCE = 150_000L
    private const val MIN_SAMPLES = 300
    private const val LAMBDA = 5.0

    /** Share of the 80% band: ±1.28 standard deviations. */
    private const val BAND = 1.28

    data class Accuracy(
        val horizon: Int,
        /** Mean absolute error of the forecast, mg/dL, on days it was not trained on. */
        val modelMae: Double,
        /** Same for "the current trend continues", for comparison. */
        val trendMae: Double,
        val rmse: Double,
    )

    class Model internal constructor(
        internal val profile: DoubleArray,
        internal val fits: List<Fit>,
        val accuracy: List<Accuracy>,
        /** Days of readings it learned from. */
        val days: Double,
    ) {
        val accuracy30: Accuracy? get() = accuracy.firstOrNull { it.horizon == 30 }
    }

    internal class Fit(val horizon: Int, val mean: DoubleArray, val scale: DoubleArray, val weights: DoubleArray, val intercept: Double)

    data class Point(val time: Long, val mgdl: Double, val low: Double, val high: Double)

    sealed interface Result {
        /** Not enough data yet: [days] of the [MIN_DAYS] needed. */
        data class Learning(val days: Double) : Result
        data class Ready(val model: Model) : Result
    }

    /**
     * Learns from [times] (ascending, ms) and [values] (mg/dL).
     * @param offset local time offset at a moment, for the time of day
     */
    fun train(times: LongArray, values: IntArray, offset: (Long) -> Int = TimeZone.getDefault()::getOffset): Result {
        val n = times.size
        val days = if (n < 2) 0.0 else (times[n - 1] - times[0]).toDouble() / DAY
        if (days < MIN_DAYS) return Result.Learning(days)
        val series = Series(times, values, offset)

        // Check on the last 20% of the time, with a model that never saw it.
        val split = times[0] + ((times[n - 1] - times[0]) * 0.8).toLong()
        val trainProfile = series.profile(until = split)
        val accuracy = HORIZONS.map { h ->
            val (trainX, trainY) = series.samples(h, trainProfile, until = split)
            val (testX, testY, testTrend) = series.samplesWithTrend(h, trainProfile, from = split)
            if (trainY.size < MIN_SAMPLES || testY.isEmpty()) return Result.Learning(minOf(days, MIN_DAYS - 0.1))
            val fit = fit(h, trainX, trainY)
            var mae = 0.0; var se = 0.0; var trend = 0.0
            for (i in testY.indices) {
                val err = predictDelta(fit, testX[i]) - testY[i]
                mae += abs(err); se += err * err
                trend += abs(testTrend[i] - testY[i])
            }
            Accuracy(h, mae / testY.size, trend / testY.size, sqrt(se / testY.size))
        }

        // The model in use learns from everything.
        val profile = series.profile(until = Long.MAX_VALUE)
        val fits = HORIZONS.map { h ->
            val (x, y) = series.samples(h, profile, until = Long.MAX_VALUE)
            fit(h, x, y)
        }
        return Result.Ready(Model(profile, fits, accuracy, days))
    }

    /**
     * Where glucose is heading after the newest reading, with an 80% band.
     * Empty when the newest reading is older than 10 minutes at [now] or the recent readings have gaps.
     */
    fun forecast(model: Model, times: LongArray, values: IntArray, now: Long, offset: (Long) -> Int = TimeZone.getDefault()::getOffset): List<Point> {
        if (times.isEmpty() || now - times.last() > 10 * MIN) return emptyList()
        val series = Series(times, values, offset)
        val i = times.size - 1
        val g0 = values[i].toDouble()
        return model.fits.mapNotNull { fit ->
            val x = series.features(i, fit.horizon, model.profile) ?: return@mapNotNull null
            val v = (g0 + predictDelta(fit, x)).coerceIn(40.0, 400.0)
            val spread = BAND * (model.accuracy.firstOrNull { it.horizon == fit.horizon }?.rmse ?: 0.0)
            Point(times[i] + fit.horizon * MIN, v, (v - spread).coerceAtLeast(40.0), (v + spread).coerceAtMost(400.0))
        }
    }

    // ---------------------------------------------------------------- internals

    private class Series(val times: LongArray, val values: IntArray, val offset: (Long) -> Int) {
        /** Value at [t], from the reading closest to it within the tolerance; NaN if none. */
        fun at(t: Long): Double {
            var lo = 0; var hi = times.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] < t) lo = mid + 1 else hi = mid - 1
            }
            var best = Double.NaN; var bestDist = TOLERANCE + 1
            for (k in intArrayOf(lo - 1, lo)) {
                if (k !in times.indices) continue
                val d = abs(times[k] - t)
                if (d < bestDist) { bestDist = d; best = values[k].toDouble() }
            }
            return best
        }

        fun slot(t: Long): Int = (Math.floorMod(t + offset(t), DAY) / SLOT).toInt()

        /** Median per half hour of the day, from the readings before [until]; NaN where there are none. */
        fun profile(until: Long): DoubleArray {
            val buckets = Array(SLOTS) { ArrayList<Int>() }
            for (k in times.indices) if (times[k] < until) buckets[slot(times[k])].add(values[k])
            return DoubleArray(SLOTS) { s ->
                val b = buckets[s]
                if (b.size < 3) Double.NaN else b.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
            }
        }

        fun features(i: Int, horizon: Int, profile: DoubleArray): DoubleArray? {
            val t = times[i]
            val g0 = values[i].toDouble()
            val g5 = at(t - 5 * MIN); val g15 = at(t - 15 * MIN); val g30 = at(t - 30 * MIN); val g60 = at(t - 60 * MIN)
            if (g5.isNaN() || g15.isNaN() || g30.isNaN() || g60.isNaN()) return null
            val hour = Math.floorMod(t + offset(t), DAY).toDouble() / (60 * MIN)
            val now = profile[slot(t)]
            val later = profile[slot(t + horizon * MIN)]
            val typicalChange = if (now.isNaN() || later.isNaN()) 0.0 else later - now
            val fromTypical = if (now.isNaN()) 0.0 else now - g0
            return doubleArrayOf(
                g0 - g5, g0 - g15, g0 - g30, g15 - g60, g0 - 120.0,
                sin(2 * PI * hour / 24), cos(2 * PI * hour / 24), sin(4 * PI * hour / 24), cos(4 * PI * hour / 24),
                typicalChange, fromTypical,
            )
        }

        /** Features and the change actually seen [horizon] minutes later, for anchors before [until]. */
        fun samples(horizon: Int, profile: DoubleArray, until: Long): Pair<List<DoubleArray>, DoubleArray> {
            val (x, y, _) = collect(horizon, profile, Long.MIN_VALUE, until)
            return x to y
        }

        fun samplesWithTrend(horizon: Int, profile: DoubleArray, from: Long) = collect(horizon, profile, from, Long.MAX_VALUE)

        /** @return features, observed change, change predicted by the 15-minute trend */
        private fun collect(horizon: Int, profile: DoubleArray, from: Long, until: Long): Triple<List<DoubleArray>, DoubleArray, DoubleArray> {
            val xs = ArrayList<DoubleArray>(); val ys = ArrayList<Double>(); val trend = ArrayList<Double>()
            for (i in times.indices) {
                val t = times[i]
                if (t < from || t + horizon * MIN >= until) continue
                val future = at(t + horizon * MIN)
                if (future.isNaN()) continue
                val x = features(i, horizon, profile) ?: continue
                xs.add(x)
                ys.add(future - values[i])
                trend.add(x[1] / 15.0 * horizon)
            }
            return Triple(xs, ys.toDoubleArray(), trend.toDoubleArray())
        }
    }

    private fun predictDelta(fit: Fit, x: DoubleArray): Double {
        var d = fit.intercept
        for (j in x.indices) d += fit.weights[j] * (x[j] - fit.mean[j]) / fit.scale[j]
        return d
    }

    /** Ridge regression on standardised features: (ZᵀZ + λI) w = Zᵀ(y − ȳ). */
    private fun fit(horizon: Int, xs: List<DoubleArray>, y: DoubleArray): Fit {
        val n = xs.size
        val p = xs[0].size
        val mean = DoubleArray(p) { j -> xs.sumOf { it[j] } / n }
        val scale = DoubleArray(p) { j ->
            sqrt(xs.sumOf { (it[j] - mean[j]).let { d -> d * d } } / n).takeIf { it > 1e-9 } ?: 1.0
        }
        val yMean = y.average()
        val a = Array(p) { DoubleArray(p) }
        val b = DoubleArray(p)
        for (i in 0 until n) {
            val z = DoubleArray(p) { j -> (xs[i][j] - mean[j]) / scale[j] }
            for (j in 0 until p) {
                b[j] += z[j] * (y[i] - yMean)
                for (k in j until p) a[j][k] += z[j] * z[k]
            }
        }
        for (j in 0 until p) {
            for (k in 0 until j) a[j][k] = a[k][j]
            a[j][j] += LAMBDA
        }
        return Fit(horizon, mean, scale, solve(a, b), yMean)
    }

    /** Gaussian elimination with partial pivoting (the matrix is small and positive definite). */
    internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (c in 0 until n) {
            val pivot = (c until n).maxBy { abs(m[it][c]) }
            m[c] = m[pivot].also { m[pivot] = m[c] }
            v[c] = v[pivot].also { v[pivot] = v[c] }
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c until n) m[r][k] -= f * m[c][k]
                v[r] -= f * v[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = v[r]
            for (k in r + 1 until n) s -= m[r][k] * x[k]
            x[r] = s / m[r][r]
        }
        return x
    }
}
