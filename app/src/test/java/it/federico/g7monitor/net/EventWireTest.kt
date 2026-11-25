package it.federico.g7monitor.net

import it.federico.g7monitor.data.ChartEvent
import it.federico.g7monitor.data.EventType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventWireTest {
    private val t = 1_761_660_000_000L

    @Test
    fun `notes go to the server and come back the same, unknown kinds skipped`() {
        val e = ChartEvent("0f8fad5b-d9cb-469f-a165-70867728950e", t, EventType.Carbs, 45.0, "pizza", updated = t + 5)
        val o = ServerApi.encodeEvent(e)
        assertEquals(t / 1000, o.getLong("ts"))
        assertEquals("carbs", o.getString("type"))
        assertFalse(o.getBoolean("deleted"))
        val echoed = JSONArray().put(o.put("seq", 7)).put(ServerApi.encodeEvent(e.copy(id = "other-id-1")).put("type", "pizza"))
        val back = ServerApi.decodeEvents(echoed)
        assertEquals(listOf(e.copy(seq = 7)), back)
    }

    @Test
    fun `a note without a number and a deletion`() {
        val note = ChartEvent("aaaaaaaa-1", t, EventType.Note, null, null, deleted = true, updated = t)
        val o = ServerApi.encodeEvent(note)
        assertTrue(o.isNull("value"))
        assertTrue(o.isNull("note"))
        assertTrue(ServerApi.decodeEvents(JSONArray().put(o)).single().deleted)
    }

    @Test
    fun `nightscout gets the treatments it knows`() {
        val list = listOf(
            ChartEvent("a", t, EventType.Insulin, 4.5, null),
            ChartEvent("b", t, EventType.Carbs, 30.0, "merenda"),
            ChartEvent("c", t, EventType.Exercise, 40.0, null),
            ChartEvent("d", t, EventType.Note, null, "sensore premuto"),
        )
        val out = NightscoutApi.events(list)
        assertEquals("Correction Bolus", out.getJSONObject(0).getString("eventType"))
        assertEquals(4.5, out.getJSONObject(0).getDouble("insulin"), 0.0)
        assertEquals("Carb Correction", out.getJSONObject(1).getString("eventType"))
        assertEquals(30.0, out.getJSONObject(1).getDouble("carbs"), 0.0)
        assertEquals("merenda", out.getJSONObject(1).getString("notes"))
        assertEquals("Exercise", out.getJSONObject(2).getString("eventType"))
        assertEquals(40.0, out.getJSONObject(2).getDouble("duration"), 0.0)
        assertEquals("Note", out.getJSONObject(3).getString("eventType"))
        assertEquals("sensore premuto", out.getJSONObject(3).getString("notes"))
        assertEquals("2025-10-28T14:00:00.000Z", out.getJSONObject(3).getString("created_at"))
    }

    @Test
    fun `labels on the chart`() {
        assertEquals("4 U", ChartEvent("x", t, EventType.Insulin, 4.0, null).label)
        assertEquals("30 min", ChartEvent("x", t, EventType.Exercise, 30.0, "bici").label)
    }
}
