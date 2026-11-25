package it.federico.g7monitor.net

import android.content.Context
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.ChartEvent
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Notes on the chart through the G7 Monitor server, both ways: what is added, changed or deleted
 * here goes up, what the other phones changed comes down. The server numbers every change, and
 * each phone remembers the last number it got, per server.
 *
 * The sensor phone syncs after its uploads and when a note is saved; a follower gets the changes
 * live on its stream, and sends its own notes when saved (if its account may write).
 */
class EventSync(private val app: G7App) {
    private val mutex = Mutex()
    private val prefs = app.getSharedPreferences("event_sync", Context.MODE_PRIVATE)
    /** Account that may only read, or server without notes: they stay here, said once. */
    @Volatile private var refusedLogged = false

    /** Sends the pending notes, then gets the others' changes. Errors are left for the next time. */
    suspend fun sync() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val (url, token) = target() ?: return@withContext
            runCatching { push(url, token) }
            runCatching { received(url, ServerApi.fetchEvents(url, token, since(url))) }
        }
    }

    /** The server the phone uses in its mode, when linked. */
    private suspend fun target(): Pair<String, String>? {
        val s = app.settings.current()
        return when {
            s.dataSource == DataSource.Sensor && s.serverConnected && !s.uploadPaused -> s.serverUrl to s.serverToken
            s.dataSource == DataSource.Server && s.followConnected -> s.followUrl to s.followToken
            else -> null
        }
    }

    private suspend fun push(url: String, token: String) {
        val pending = app.readings.pendingEvents()
        if (pending.isEmpty()) return
        try {
            ServerApi.uploadEvents(url, token, pending)
        } catch (e: ServerApi.HttpError) {
            if (e.status != 403 && e.status != 404) throw e
            if (!refusedLogged) DebugLog.log(str(if (e.status == 403) R.string.events_read_only else R.string.events_old_server))
            refusedLogged = true
            return
        }
        refusedLogged = false
        app.readings.markEventsUploaded(pending)
    }

    /** Changes from the server (also from the follower's live stream). */
    suspend fun received(url: String, list: List<ChartEvent>) {
        if (list.isEmpty()) return
        val changed = app.readings.mergeEvents(list)
        if (changed > 0) DebugLog.log(str(R.string.events_received, changed))
        val last = list.maxOf { it.seq }
        if (last > since(url)) prefs.edit().putLong(url, last).apply()
    }

    /** Last change number received from the server at [url]. */
    fun since(url: String): Long = prefs.getLong(url, 0L)
}
