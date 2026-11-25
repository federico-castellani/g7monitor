package it.federico.g7monitor.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorReminderRulesTest {
    private val start = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun at(t: Long) = SensorReminderRules.stage(start, start + t)

    @Test
    fun `nothing during the first nine days`() {
        assertEquals(-1, at(0))
        assertEquals(-1, at(9 * day - 1))
    }

    @Test
    fun `counts ten days, not ten and a half`() {
        assertEquals(0, at(9 * day)) // a day before
        assertEquals(0, at(10 * day - 2 * hour - 1))
        assertEquals(1, at(10 * day - 2 * hour)) // two hours before
        assertEquals(2, at(10 * day)) // the 10 days are over
        assertEquals(2, at(10 * day + 12 * hour - 1)) // still in the grace period
    }

    @Test
    fun `no reminder once the sensor has stopped or before it starts`() {
        assertEquals(-1, at(10 * day + 12 * hour))
        assertEquals(-1, at(-hour))
    }
}
