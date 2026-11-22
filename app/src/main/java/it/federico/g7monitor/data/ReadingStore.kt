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

/** Blood glucose from a meter (finger stick): kept apart from the sensor's readings. */
/** Where readings are sent; each keeps its own "already sent" flag, so backfilled readings go to both. */
enum class UploadTarget(internal val column: String) { Server("uploaded"), Nightscout("ns_uploaded") }

data class Fingerstick(
    val timestamp: Long,
    val mgdl: Int,
    /** Name of the meter, when known. */
    val meter: String?,
)

/**
 * Tiny SQLite store for readings. One table, indexed by timestamp: fast and
 * no annotation processing needed. Readings within ±2 minutes are de-duplicated.
 */
class ReadingStore(context: Context) :
    SQLiteOpenHelper(context, "readings.db", null, 5) {

    private val _changes = MutableStateFlow(0L)

    /** Increments on every insert; combine with a time window to observe readings. */
    val changes: StateFlow<Long> = _changes

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE readings (ts INTEGER PRIMARY KEY, mgdl INTEGER NOT NULL, trend REAL, source INTEGER NOT NULL, " +
                "uploaded INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(PENDING_INDEX)
        createFingersticks(db)
        addNightscout(db)
        createSensors(db)
    }

    /** When each sensor was started: marks on the chart, sent to the server and to Nightscout. */
    private fun createSensors(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sensors (start INTEGER PRIMARY KEY, uploaded INTEGER NOT NULL DEFAULT 0, " +
                "ns_uploaded INTEGER NOT NULL DEFAULT 0)"
        )
    }

    private fun addNightscout(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE readings ADD COLUMN ns_uploaded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fingersticks ADD COLUMN ns_uploaded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX readings_ns_pending ON readings(ts) WHERE ns_uploaded = 0")
    }

    private fun createFingersticks(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE fingersticks (id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, mgdl INTEGER NOT NULL, meter TEXT, " +
                "uploaded INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX fingersticks_ts ON fingersticks(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Server upload: a flag rather than a time cursor, so backfilled (older) readings are sent too.
            db.execSQL("ALTER TABLE readings ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
            db.execSQL(PENDING_INDEX)
        }
        if (oldVersion < 3) createFingersticks(db)
        if (oldVersion < 4) addNightscout(db)
        if (oldVersion < 5) createSensors(db)
    }

    init {
        // WAL: the UI can read while the service writes, and commits are cheaper.
        setWriteAheadLoggingEnabled(true)
    }

    /** @return true when the reading was new. */
    suspend fun insert(r: GlucoseReading): Boolean = insertAll(listOf(r)) == 1

    /**
     * Inserts in a single transaction (one disk sync for a whole backfill) and notifies
     * observers once. @param fromServer readings downloaded in follower mode: never uploaded back.
     * @return how many readings were new.
     */
    suspend fun insertAll(list: List<GlucoseReading>, fromServer: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (list.isEmpty()) return@withContext 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val dupCheck = db.compileStatement("SELECT COUNT(*) FROM readings WHERE ts BETWEEN ? AND ?")
            val values = ContentValues(6)
            for (r in list) {
                dupCheck.bindLong(1, r.timestamp - DEDUPE_MS)
                dupCheck.bindLong(2, r.timestamp + DEDUPE_MS)
                if (dupCheck.simpleQueryForLong() > 0) continue
                values.clear()
                values.put("ts", r.timestamp)
                values.put("mgdl", r.mgdl)
                if (r.trend != null) values.put("trend", r.trend) else values.putNull("trend")
                values.put("source", r.source.ordinal)
                values.put("uploaded", if (fromServer) 1 else 0)
                values.put("ns_uploaded", if (fromServer) 1 else 0)
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

    /** Time of the newest stored reading, or null when there is none. */
    suspend fun newestTimestamp(): Long? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT MAX(ts) FROM readings", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    /** Oldest readings not yet sent to [target]. */
    suspend fun pendingUpload(limit: Int, target: UploadTarget = UploadTarget.Server): List<GlucoseReading> =
        query("WHERE ${target.column} = 0 ORDER BY ts LIMIT ?", limit.toLong())

    suspend fun markUploaded(list: List<GlucoseReading>, target: UploadTarget = UploadTarget.Server) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = db.compileStatement("UPDATE readings SET ${target.column} = 1 WHERE ts = ?")
            for (r in list) {
                update.bindLong(1, r.timestamp)
                update.executeUpdateDelete()
            }
            update.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Newly connected Nightscout: only what is newer than [from] is sent (older history would
     * duplicate what another uploader may already have put there).
     */
    suspend fun resetNightscout(from: Long) = withContext(Dispatchers.IO) {
        val cutoff = arrayOf<Any>(from)
        writableDatabase.execSQL("UPDATE readings SET ns_uploaded = CASE WHEN ts < ? THEN 1 ELSE 0 END", cutoff)
        writableDatabase.execSQL("UPDATE fingersticks SET ns_uploaded = CASE WHEN ts < ? THEN 1 ELSE 0 END", cutoff)
        writableDatabase.execSQL("UPDATE sensors SET ns_uploaded = CASE WHEN start < ? THEN 1 ELSE 0 END", cutoff)
    }

    /** New server or account: everything stored locally is sent again (the server ignores duplicates). */
    suspend fun markAllPending() = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("UPDATE readings SET uploaded = 0 WHERE uploaded != 0")
        writableDatabase.execSQL("UPDATE fingersticks SET uploaded = 0 WHERE uploaded != 0")
        writableDatabase.execSQL("UPDATE sensors SET uploaded = 0 WHERE uploaded != 0")
    }

    /**
     * Sensor starts; one within [SENSOR_WINDOW_MS] of a stored start is the same sensor (the start
     * is worked out again at every reading, a few seconds apart). @return how many were new.
     */
    suspend fun addSensorStarts(starts: List<Long>, fromServer: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (starts.isEmpty()) return@withContext 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val dup = db.compileStatement("SELECT COUNT(*) FROM sensors WHERE start BETWEEN ? AND ?")
            for (s in starts) {
                dup.bindLong(1, s - SENSOR_WINDOW_MS)
                dup.bindLong(2, s + SENSOR_WINDOW_MS)
                if (dup.simpleQueryForLong() > 0) continue
                val values = ContentValues(3).apply {
                    put("start", s)
                    put("uploaded", if (fromServer) 1 else 0)
                    put("ns_uploaded", if (fromServer) 1 else 0)
                }
                if (db.insert("sensors", null, values) != -1L) added++
            }
            dup.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) _changes.value = _changes.value + 1
        added
    }

    /** Sensor starts after [from], oldest first. */
    suspend fun sensorStartsSince(from: Long): List<Long> = longs("SELECT start FROM sensors WHERE start >= ? ORDER BY start", from)

    /** Start of the sensor in use (the newest one known). */
    suspend fun latestSensorStart(): Long? = longs("SELECT start FROM sensors WHERE start >= ? ORDER BY start DESC LIMIT 1", 0).firstOrNull()

    suspend fun pendingSensorStarts(target: UploadTarget = UploadTarget.Server): List<Long> =
        longs("SELECT start FROM sensors WHERE ${target.column} = 0 AND start >= ? ORDER BY start", 0)

    suspend fun markSensorStartsUploaded(list: List<Long>, target: UploadTarget = UploadTarget.Server) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = db.compileStatement("UPDATE sensors SET ${target.column} = 1 WHERE start = ?")
            for (s in list) {
                update.bindLong(1, s)
                update.executeUpdateDelete()
            }
            update.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private suspend fun longs(sql: String, arg: Long): List<Long> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(sql, arrayOf(arg.toString())).use { c ->
            buildList(c.count) { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    private suspend fun query(where: String, arg: Long): List<GlucoseReading> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT ts, mgdl, trend, source FROM readings $where", arrayOf(arg.toString())).use { c ->
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
        writableDatabase.delete("fingersticks", "ts < ?", arrayOf(olderThan.toString()))
    }

    // ---------------------------------------------------------------- meter (finger sticks)

    /**
     * The same measurement can arrive twice (meter re-sync, server echo): same value within
     * a minute counts as one. A genuine re-test usually gives a different value and is kept.
     * @return how many were new
     */
    suspend fun insertFingersticks(list: List<Fingerstick>, fromServer: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (list.isEmpty()) return@withContext 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val dup = db.compileStatement("SELECT COUNT(*) FROM fingersticks WHERE ts BETWEEN ? AND ? AND mgdl = ?")
            val values = ContentValues(5)
            for (f in list) {
                dup.bindLong(1, f.timestamp - FINGERSTICK_DEDUPE_MS)
                dup.bindLong(2, f.timestamp + FINGERSTICK_DEDUPE_MS)
                dup.bindLong(3, f.mgdl.toLong())
                if (dup.simpleQueryForLong() > 0) continue
                values.clear()
                values.put("ts", f.timestamp)
                values.put("mgdl", f.mgdl)
                if (f.meter != null) values.put("meter", f.meter) else values.putNull("meter")
                values.put("uploaded", if (fromServer) 1 else 0)
                values.put("ns_uploaded", if (fromServer) 1 else 0)
                if (db.insert("fingersticks", null, values) != -1L) added++
            }
            dup.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) _changes.value = _changes.value + 1
        added
    }

    suspend fun fingersticksSince(from: Long): List<Fingerstick> = fingersticks("WHERE ts >= ? ORDER BY ts", from)

    suspend fun pendingFingersticks(limit: Int, target: UploadTarget = UploadTarget.Server): List<Fingerstick> =
        fingersticks("WHERE ${target.column} = 0 ORDER BY ts LIMIT ?", limit.toLong())

    suspend fun newestFingerstick(): Long? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT MAX(ts) FROM fingersticks", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    suspend fun markFingersticksUploaded(list: List<Fingerstick>, target: UploadTarget = UploadTarget.Server) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = db.compileStatement("UPDATE fingersticks SET ${target.column} = 1 WHERE ts = ? AND mgdl = ?")
            for (f in list) {
                update.bindLong(1, f.timestamp)
                update.bindLong(2, f.mgdl.toLong())
                update.executeUpdateDelete()
            }
            update.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private suspend fun fingersticks(where: String, arg: Long): List<Fingerstick> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT ts, mgdl, meter FROM fingersticks $where", arrayOf(arg.toString())).use { c ->
            buildList(c.count) {
                while (c.moveToNext()) add(Fingerstick(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getString(2)))
            }
        }
    }

    companion object {
        private const val DEDUPE_MS = 2 * 60_000L
        private const val FINGERSTICK_DEDUPE_MS = 60_000L
        /** Same rule as the server: starts closer than this are the same sensor. */
        private const val SENSOR_WINDOW_MS = 12 * 3_600_000L
        private const val PENDING_INDEX = "CREATE INDEX readings_pending ON readings(ts) WHERE uploaded = 0"
    }
}
