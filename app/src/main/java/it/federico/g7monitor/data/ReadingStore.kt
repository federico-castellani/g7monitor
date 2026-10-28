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

    init {
        // WAL: the UI can read while the service writes, and commits are cheaper.
        setWriteAheadLoggingEnabled(true)
    }

    /** @return true when the reading was new. */
    suspend fun insert(r: GlucoseReading): Boolean = insertAll(listOf(r)) == 1

    /**
     * Inserts in a single transaction (one disk sync for a whole backfill) and notifies
     * observers once. @return how many readings were new.
     */
    suspend fun insertAll(list: List<GlucoseReading>): Int = withContext(Dispatchers.IO) {
        if (list.isEmpty()) return@withContext 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val dupCheck = db.compileStatement("SELECT COUNT(*) FROM readings WHERE ts BETWEEN ? AND ?")
            val values = ContentValues(4)
            for (r in list) {
                dupCheck.bindLong(1, r.timestamp - DEDUPE_MS)
                dupCheck.bindLong(2, r.timestamp + DEDUPE_MS)
                if (dupCheck.simpleQueryForLong() > 0) continue
                values.clear()
                values.put("ts", r.timestamp)
                values.put("mgdl", r.mgdl)
                if (r.trend != null) values.put("trend", r.trend) else values.putNull("trend")
                values.put("source", r.source.ordinal)
                if (db.insert("readings", null, values) != -1L) added++
            }
            dupCheck.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) _changes.value = _changes.value + 1
        added
    }

    suspend fun since(from: Long): List<GlucoseReading> = query("WHERE ts >= ? ORDER BY ts", from)

    suspend fun latest(): GlucoseReading? =
        query("WHERE ts >= ? ORDER BY ts DESC LIMIT 1", System.currentTimeMillis() - 30 * 60_000L).firstOrNull()

    private suspend fun query(where: String, from: Long): List<GlucoseReading> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT ts, mgdl, trend, source FROM readings $where", arrayOf(from.toString())).use { c ->
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

    suspend fun prune(olderThan: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("readings", "ts < ?", arrayOf(olderThan.toString()))
    }

    companion object {
        private const val DEDUPE_MS = 2 * 60_000L
    }
}
