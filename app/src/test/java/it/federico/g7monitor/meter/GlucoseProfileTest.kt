package it.federico.g7monitor.meter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GlucoseProfileTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun utcMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        Calendar.getInstance(utc).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test
    fun `sfloat values and special codes`() {
        assertEquals(112e-5, GlucoseProfile.sfloat(0xB070)!!, 1e-12) // mantissa 112, exponent -5
        assertEquals(-1.0, GlucoseProfile.sfloat(0x0FFF)!!, 1e-12)
        assertNull(GlucoseProfile.sfloat(0x07FF)) // NaN
        assertNull(GlucoseProfile.sfloat(0x0800)) // NRes
    }

    @Test
    fun `measurement in kg per litre with time offset (Contour style)`() {
        // flags: time offset + concentration (kg/L); seq 42; 2026-10-29 07:45:10; offset +60 min;
        // 112e-5 kg/L = 112 mg/dL; type 1 (capillary whole blood), location 1 (finger)
        val v = bytes(0x03, 42, 0, 0xEA, 0x07, 10, 29, 7, 45, 10, 60, 0, 0x70, 0xB0, 0x11)
        val m = GlucoseProfile.parseMeasurement(v, utc)!!
        assertEquals(42, m.sequence)
        assertEquals(utcMillis(2026, 10, 29, 8, 45, 10), m.time)
        assertEquals(112, m.mgdl)
        assertFalse(m.controlSolution)
    }

    @Test
    fun `measurement in mol per litre, negative offset (Accu-Chek style)`() {
        // flags: time offset + concentration + mol/L; 6.2 mmol/L = 62e-4 mol/L (mantissa 62, exponent -4)
        val sf = (0xC shl 12) or 62
        val v = bytes(0x07, 0x01, 0x01, 0xEA, 0x07, 1, 2, 0, 10, 0, 0xE2, 0xFF, sf and 0xFF, sf shr 8, 0x12)
        val m = GlucoseProfile.parseMeasurement(v, utc)!!
        assertEquals(257, m.sequence)
        assertEquals(utcMillis(2026, 1, 1, 23, 40, 0), m.time) // 00:10 − 30 min, previous day
        assertEquals(112, m.mgdl) // 6.2 × 18.0182 = 111.7
    }

    @Test
    fun `control solution is flagged, unset clock is rejected`() {
        val control = bytes(0x02, 5, 0, 0xEA, 0x07, 10, 29, 7, 45, 10, 0x70, 0xB0, 0x1A)
        assertTrue(GlucoseProfile.parseMeasurement(control, utc)!!.controlSolution)
        val noClock = bytes(0x02, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0x70, 0xB0, 0x11)
        assertNull(GlucoseProfile.parseMeasurement(noClock, utc))
        assertNull(GlucoseProfile.parseMeasurement(bytes(0x02, 5), utc))
    }

    @Test
    fun `record access control point commands and responses`() {
        assertArrayEquals(bytes(0x01, 0x01), GlucoseProfile.requestAll())
        assertArrayEquals(bytes(0x01, 0x03, 0x01, 0x00, 0x00), GlucoseProfile.requestFrom(65536)) // wraps like the meter
        assertArrayEquals(bytes(0x01, 0x03, 0x01, 0x2C, 0x01), GlucoseProfile.requestFrom(300))
        assertEquals(GlucoseProfile.RacpResult.Success, GlucoseProfile.parseRacpResponse(bytes(0x06, 0x00, 0x01, 0x01)))
        assertEquals(GlucoseProfile.RacpResult.NoRecords, GlucoseProfile.parseRacpResponse(bytes(0x06, 0x00, 0x01, 0x06)))
        assertEquals(GlucoseProfile.RacpResult.Unsupported, GlucoseProfile.parseRacpResponse(bytes(0x06, 0x00, 0x01, 0x04)))
        assertNull(GlucoseProfile.parseRacpResponse(bytes(0x05, 0x00, 0x01, 0x01)))
    }

    @Test
    fun `meter clock (Current Time characteristic)`() {
        // 2026-10-29 22:05:30, Thursday, no fractions, adjust reason 0
        val v = bytes(0xEA, 0x07, 10, 29, 22, 5, 30, 4, 0, 0)
        assertEquals(utcMillis(2026, 10, 29, 22, 5, 30), GlucoseProfile.parseCurrentTime(v, utc))
        assertNull(GlucoseProfile.parseCurrentTime(bytes(0, 0, 0, 0, 0, 0, 0), utc)) // clock not set
    }
}
