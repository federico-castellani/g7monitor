package it.federico.g7monitor.net

import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutApiTest {
    @Test
    fun `api secret is sent as its sha1, tokens as they are`() {
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", NightscoutApi.sha1("test"))
        assertTrue(NightscoutApi.isToken("g7monitor-0123456789abcdef"))
        assertTrue(NightscoutApi.isToken(" uploader.phone-a1b2c3d4e5f60718 "))
        assertFalse(NightscoutApi.isToken("my-long-api-secret"))
        assertFalse(NightscoutApi.isToken("supersecret123"))
    }

    @Test
    fun `entries use nightscout fields and directions`() {
        val t = 1_761_660_000_000L
        val e = NightscoutApi.entries(listOf(GlucoseReading(t, 123, 1.4, GlucoseReading.Source.Live))).getJSONObject(0)
        assertEquals("sgv", e.getString("type"))
        assertEquals(123, e.getInt("sgv"))
        assertEquals(t, e.getLong("date"))
        assertEquals("2025-10-28T14:00:00.000Z", e.getString("dateString"))
        assertEquals("FortyFiveUp", e.getString("direction"))
        assertEquals("G7 Monitor", e.getString("device"))
    }

    @Test
    fun `directions follow the app thresholds`() {
        assertEquals("NONE", NightscoutApi.direction(null))
        assertEquals("NONE", NightscoutApi.direction(Double.NaN))
        assertEquals("DoubleUp", NightscoutApi.direction(3.0))
        assertEquals("SingleUp", NightscoutApi.direction(2.0))
        assertEquals("Flat", NightscoutApi.direction(0.5))
        assertEquals("Flat", NightscoutApi.direction(-0.99))
        assertEquals("FortyFiveDown", NightscoutApi.direction(-1.0))
        assertEquals("SingleDown", NightscoutApi.direction(-2.5))
        assertEquals("DoubleDown", NightscoutApi.direction(-3.0))
    }

    @Test
    fun `meter results are bg check treatments`() {
        val t = 1_761_660_000_000L
        val f = NightscoutApi.treatments(listOf(Fingerstick(t, 98, "Contour"))).getJSONObject(0)
        assertEquals("BG Check", f.getString("eventType"))
        assertEquals(98, f.getInt("glucose"))
        assertEquals("Finger", f.getString("glucoseType"))
        assertEquals("mg/dl", f.getString("units"))
        assertEquals("2025-10-28T14:00:00.000Z", f.getString("created_at"))
        assertEquals("G7 Monitor (Contour)", f.getString("enteredBy"))
    }
}
