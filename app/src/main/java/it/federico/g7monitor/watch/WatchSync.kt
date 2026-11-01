package it.federico.g7monitor.watch

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
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
    /** Message to the watch with the phone's version name (the watch then offers its own update). */
    const val PHONE_VERSION = "/g7monitor/phone-version"
    /** Data item published by the watch app with its version. */
    const val WATCH_INFO = "/g7monitor/watch"
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

    /** A watch connected to the phone, and the version of G7 Monitor on it (null: not installed or never opened). */
    data class Watch(val id: String, val name: String, val appVersion: String?)

    val phoneVersion: String by lazy { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" }

    /** Connected watches; empty without Play services' Wear OS app or with no watch paired. */
    suspend fun watches(): List<Watch> = withContext(Dispatchers.IO) {
        runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS)
            val items = Tasks.await(client.dataItems, 10, TimeUnit.SECONDS)
            val versions = try {
                items.filter { it.uri.path == WatchContract.WATCH_INFO }
                    .associate { it.uri.host to DataMapItem.fromDataItem(it).dataMap.getString("version") }
            } finally {
                items.release()
            }
            nodes.map { Watch(it.id, it.displayName, versions[it.id]) }
        }.getOrDefault(emptyList())
    }

    /**
     * Tells the watches the phone's version: an older watch app shows a notification that updates
     * it from GitHub with one tap. At start only once per installed version, or [force] (button).
     */
    suspend fun announceVersion(force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = app.getSharedPreferences("watch", android.content.Context.MODE_PRIVATE)
        if (!force && prefs.getString("announced", null) == phoneVersion) return@withContext
        val nodes = runCatching { Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS) }.getOrNull() ?: return@withContext
        if (nodes.isEmpty()) return@withContext
        val messages = Wearable.getMessageClient(app)
        for (node in nodes) {
            runCatching { Tasks.await(messages.sendMessage(node.id, WatchContract.PHONE_VERSION, phoneVersion.toByteArray()), 10, TimeUnit.SECONDS) }
        }
        prefs.edit().putString("announced", phoneVersion).apply()
    }
}
