package it.federico.g7monitor.watch

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import it.federico.g7monitor.G7App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keys of the data item shared with the watch app. Duplicated in the wear module
 * (WatchContract.kt there): keep the two in sync.
 */
object WatchContract {
    const val PATH = "/g7monitor/glucose"
    const val TIME = "time"
    const val MGDL = "mgdl"
    const val TREND = "trend"
    const val DELTA = "delta"
    const val MMOL = "mmol"
    const val LOW = "low"
    const val HIGH = "high"
}

/**
 * Sends the latest reading to a paired Wear OS watch (Data Layer), for its complications.
 * One small data item per reading; identical content is not sent again by Play services,
 * and without a watch the call simply fails quietly.
 */
class WatchSync(private val app: G7App) {
    private val client by lazy { Wearable.getDataClient(app) }

    suspend fun push() = withContext(Dispatchers.IO) {
        val s = app.settings.current()
        val recent = app.readings.since(System.currentTimeMillis() - 30 * 60_000L)
        val latest = recent.lastOrNull() ?: return@withContext
        val prev = recent.getOrNull(recent.size - 2)?.takeIf { latest.timestamp - it.timestamp < 12 * 60_000L }
        val request = PutDataMapRequest.create(WatchContract.PATH).apply {
            dataMap.putLong(WatchContract.TIME, latest.timestamp)
            dataMap.putInt(WatchContract.MGDL, latest.mgdl)
            dataMap.putDouble(WatchContract.TREND, latest.trend?.takeIf { it.isFinite() } ?: Double.NaN)
            if (prev != null) dataMap.putInt(WatchContract.DELTA, latest.mgdl - prev.mgdl)
            dataMap.putBoolean(WatchContract.MMOL, s.useMmol)
            dataMap.putInt(WatchContract.LOW, s.lowMgdl)
            dataMap.putInt(WatchContract.HIGH, s.highMgdl)
        }.asPutDataRequest().setUrgent()
        runCatching { Tasks.await(client.putDataItem(request), 15, TimeUnit.SECONDS) }
    }
}
