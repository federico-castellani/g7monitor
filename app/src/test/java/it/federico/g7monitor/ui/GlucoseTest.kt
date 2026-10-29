package it.federico.g7monitor.ui

import it.federico.g7monitor.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseTest {
    private val t0 = 1_800_000_000_000L
    private fun r(min: Int, mgdl: Int) = GlucoseReading(t0 + min * 60_000L, mgdl, 0.0, GlucoseReading.Source.Live)

    @Test
    fun sensorValueIsInterpolatedBetweenTheReadingsAround() {
        val readings = listOf(r(0, 100), r(5, 120), r(10, 110))
        assertEquals(110, Glucose.sensorAt(readings, t0 + 150_000L)) // halfway 100 -> 120
        assertEquals(120, Glucose.sensorAt(readings, t0 + 5 * 60_000L))
    }

    @Test
    fun acrossAGapOnlyTheNearbyReadingCounts() {
        val readings = listOf(r(0, 100), r(30, 150))
        assertEquals(100, Glucose.sensorAt(readings, t0 + 4 * 60_000L))
        assertEquals(150, Glucose.sensorAt(readings, t0 + 27 * 60_000L))
        assertNull(Glucose.sensorAt(readings, t0 + 15 * 60_000L))
    }

    @Test
    fun outsideTheReadingsNothingOrTheClosestOne() {
        val readings = listOf(r(0, 100), r(5, 110))
        assertEquals(110, Glucose.sensorAt(readings, t0 + 8 * 60_000L))
        assertNull(Glucose.sensorAt(readings, t0 + 20 * 60_000L))
        assertNull(Glucose.sensorAt(emptyList(), t0))
    }

    @Test
    fun differenceIsMeterMinusSensor() {
        assertEquals("+7 mg/dL", Glucose.formatDifference(112, 105, mmol = false))
        assertEquals("−10 mg/dL", Glucose.formatDifference(95, 105, mmol = false))
    }
}
