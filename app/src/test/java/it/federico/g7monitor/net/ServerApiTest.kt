package it.federico.g7monitor.net

import it.federico.g7monitor.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerApiTest {
    @Test
    fun `upload body uses time deltas and trend x10`() {
        val t0 = 1_761_660_000_000L
        val readings = listOf(
            GlucoseReading(t0, 110, 0.54, GlucoseReading.Source.Live),
            GlucoseReading(t0 + 300_400, 118, null, GlucoseReading.Source.Backfill),
            GlucoseReading(t0 + 600_900, 39, Double.NaN, GlucoseReading.Source.Live),
            GlucoseReading(t0 + 901_000, 401, -40.0, GlucoseReading.Source.Live),
        )
        // Same string is checked against the server in G7Monitor-Server/test/server.test.ts
        assertEquals(
            """{"b":1761660000,"r":[[0,110,5,0],[300,118,null,1],[300,39,null,0],[301,401,-250,0]]}""",
            ServerApi.encode(readings),
        )
    }

    @Test
    fun `server address must be https`() {
        assertEquals("https://g7.example.com", ServerApi.normalizeUrl(" g7.example.com/ "))
        assertEquals("https://g7.example.com:8443/base", ServerApi.normalizeUrl("https://g7.example.com:8443/base"))
        assertNull(ServerApi.normalizeUrl("http://g7.example.com"))
        assertNull(ServerApi.normalizeUrl("https://user:pw@g7.example.com"))
        assertNull(ServerApi.normalizeUrl("https://g7.example.com/?x=1"))
        assertNull(ServerApi.normalizeUrl(""))
    }
}
