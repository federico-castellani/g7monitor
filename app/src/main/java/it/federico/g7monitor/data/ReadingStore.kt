package it.federico.g7monitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class GlucoseReading(
    val timestamp: Long,
    val mgdl: Int,
    /** mg/dL per minute, null when unknown. */
    val trend: Double?,
    val source: Source,
) {
    enum class Source { Live, Backfill }
}

/**
 * Tiny SQLite store for readings. One table, indexed by timestamp: fast and
 * no annotation processing needed. Readings within ±2 minutes are de-duplicated.
 */
class ReadingStore(context: Context) :
    SQLiteOpenHelper(context, "readings.db", null, 1) {

    private val _changes = MutableStateFlow(0L)

    /** Increments on every insert; combine with a time window to observe readings. */
    val changes: StateFlow<Long> = _changes

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE readings (ts INTEGER PRIMARY KEY, mgdl INTEGER NOT NULL, trend REAL, source INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** @return true when the reading was new. */
    suspend fun insert(r: GlucoseReading): Boolean = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val dup = db.rawQuery(
            "SELECT 1 FROM readings WHERE ts BETWEEN ? AND ? LIMIT 1",
            arrayOf((r.timestamp - DEDUPE_MS).toString(), (r.timestamp + DEDUPE_MS).toString()),
        ).use { it.moveToFirst() }
        if (dup) return@withContext false
        db.insert("readings", null, ContentValues().apply {
            put("ts", r.timestamp)
            put("mgdl", r.mgdl)
            if (r.trend != null) put("trend", r.trend) else putNull("trend")
            put("source", r.source.ordinal)
        })
        _changes.value = _changes.value + 1
        true
    }

    suspend fun insertAll(list: List<GlucoseReading>): Int = list.count { insert(it) }

    suspend fun since(from: Long): List<GlucoseReading> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT ts, mgdl, trend, source FROM readings WHERE ts >= ? ORDER BY ts",
            arrayOf(from.toString()),
        ).use { c ->
            buildList(c.count) {
                while (c.moveToNext()) add(
                    GlucoseReading(
                        timestamp = c.getLong(0),
                        mgdl = c.getInt(1),
                        trend = if (c.isNull(2)) null else c.getDouble(2),
                        source = GlucoseReading.Source.entries[c.getInt(3)],
                    )
                )
            }
        }
    }

    suspend fun latest(): GlucoseReading? = since(System.currentTimeMillis() - 30 * 60_000L).lastOrNull()

    suspend fun prune(olderThan: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("readings", "ts < ?", arrayOf(olderThan.toString()))
    }

    companion object {
        private const val DEDUPE_MS = 2 * 60_000L
    }
}
