package it.federico.g7monitor.alarm

import it.federico.g7monitor.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmRulesTest {
    private val now = 1_800_000_000_000L
    private val defaults = AlarmType.entries.associateWith { AlarmConfig(it) }

    private fun r(minAgo: Int, mgdl: Int, trend: Double? = 0.0) =
        GlucoseReading(now - minAgo * 60_000L, mgdl, trend, GlucoseReading.Source.Live)

    private fun check(
        readings: List<GlucoseReading>,
        configs: Map<AlarmType, AlarmConfig> = defaults,
        receiving: Boolean = true,
        warmingUp: Boolean = false,
    ) = AlarmRules.triggered(configs, readings, readings.maxOfOrNull { it.timestamp }, now, receiving, warmingUp)

    @Test
    fun `in range, steady and recent, no alarm`() {
        assertEquals(emptySet<AlarmType>(), check(listOf(r(5, 120), r(0, 121))))
    }

    @Test
    fun `urgent low hides the milder low`() {
        assertEquals(setOf(AlarmType.Low), check(listOf(r(0, 65))))
        assertEquals(setOf(AlarmType.UrgentLow), check(listOf(r(0, 55))))
    }

    @Test
    fun `urgent high hides the milder high`() {
        assertEquals(setOf(AlarmType.High), check(listOf(r(0, 200))))
        assertEquals(setOf(AlarmType.UrgentHigh), check(listOf(r(0, 250))))
    }

    @Test
    fun `thresholds follow the user settings`() {
        val custom = defaults + (AlarmType.Low to AlarmConfig(AlarmType.Low, value = 80))
        assertEquals(setOf(AlarmType.Low), check(listOf(r(0, 78)), custom))
    }

    @Test
    fun `disabled alarms never ring`() {
        val off = defaults.mapValues { it.value.copy(enabled = false) }
        assertEquals(emptySet<AlarmType>(), check(listOf(r(0, 40, -4.0)), off))
    }

    @Test
    fun `fast fall from the sensor trend, or computed when missing`() {
        assertEquals(setOf(AlarmType.FallingFast), check(listOf(r(0, 130, -2.5))))
        // no trend: 150 -> 135 in 5 minutes = -3 mg/dL/min
        assertEquals(setOf(AlarmType.FallingFast), check(listOf(r(5, 150, null), r(0, 135, null))))
        assertEquals(-3.0, AlarmRules.rate(listOf(r(5, 150, null), r(0, 135, null)))!!, 1e-9)
        assertNull(AlarmRules.rate(listOf(r(0, 135, null))))
    }

    @Test
    fun `fast rise is off by default`() {
        assertEquals(emptySet<AlarmType>(), check(listOf(r(0, 130, 3.0))))
        val on = defaults + (AlarmType.RisingFast to AlarmConfig(AlarmType.RisingFast, enabled = true))
        assertEquals(setOf(AlarmType.RisingFast), check(listOf(r(0, 130, 3.0)), on))
    }

    @Test
    fun `old readings trigger only signal loss`() {
        assertEquals(setOf(AlarmType.SignalLoss), check(listOf(r(25, 50, -4.0))))
        assertEquals(emptySet<AlarmType>(), check(listOf(r(15, 50, -4.0)))) // stale for glucose, not yet signal loss
    }

    @Test
    fun `no signal loss when not receiving or warming up`() {
        assertEquals(emptySet<AlarmType>(), check(listOf(r(30, 120)), receiving = false))
        assertEquals(emptySet<AlarmType>(), check(listOf(r(30, 120)), warmingUp = true))
        assertEquals(emptySet<AlarmType>(), AlarmRules.triggered(defaults, emptyList(), null, now, receiving = true, warmingUp = false))
    }

    @Test
    fun `broken saved settings fall back to safe defaults`() {
        val c = AlarmConfig.fromJson(AlarmType.UrgentLow, """{"enabled":true,"value":5,"snooze":0,"output":"???","volume":0}""")
        assertEquals(55, c.value)
        assertEquals(15, c.snoozeMin)
        assertEquals(AlarmOutput.SoundAndVibration, c.output)
        assertEquals(10, c.volume)
        assertEquals(AlarmConfig(AlarmType.Low), AlarmConfig.fromJson(AlarmType.Low, "non json"))
    }
}
