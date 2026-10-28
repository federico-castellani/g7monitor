package it.federico.g7monitor.net

import it.federico.g7monitor.G7App
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Follower mode: downloads the readings from the server into the local store, so
 * Home, chart and notification work exactly as with the sensor.
 *
 * Battery: no open connection. [nextDelay] schedules the next request right after the
 * moment the next reading is expected (sensor period 5 min), so most requests return
 * one new reading and the radio wakes up about once every 5 minutes.
 */
class Follower(private val app: G7App) {
    private val mutex = Mutex()
    private var failures = 0
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ITALY)

    /** @return true when the server answered. */
    suspend fun sync(): Boolean = mutex.withLock { withContext(Dispatchers.IO) { run() } }

    private suspend fun run(): Boolean {
        val s = app.settings.current()
        if (s.dataSource != DataSource.Server || !s.followConnected) return false
        val now = System.currentTimeMillis()
        // From the newest local reading; never more than a day back on the first sync.
        val since = maxOf(app.readings.newestTimestamp() ?: 0L, now - FIRST_SYNC_MS)
        return try {
            val rows = ServerApi.fetchSince(s.followUrl, s.followToken, since)
            val added = app.readings.insertAll(rows, fromServer = true)
            failures = 0
            app.settings.setFollowResult(now, null)
            if (added > 0) DebugLog.log("server: ricevute $added letture")
            app.status.value = app.status.value.copy(
                link = Link.Waiting, lastContact = now,
                message = "Dal server · aggiornato alle ${timeFmt.format(Date(now))}", deviceName = s.followUrl.removePrefix("https://"),
            )
            true
        } catch (e: ServerApi.HttpError) {
            if (e.status == 401) {
                DebugLog.log("server: token revocato")
                app.settings.disconnectFollow("Il sito ha scollegato questo telefono: collegalo di nuovo")
                showError("Telefono scollegato dal server")
            } else {
                failed(e.message ?: "Errore del server")
            }
            false
        } catch (e: SSLException) {
            failed("Certificato HTTPS del server non valido")
            false
        } catch (e: IOException) {
            failed("Server non raggiungibile")
            false
        }
    }

    /**
     * How long to wait before the next request:
     * - right after the next expected reading (+20 s for the upload to arrive)
     * - every 2 minutes while the data is late (sensor out of range, phone offline…)
     * - 1, 2, 4… up to 15 minutes after errors
     */
    suspend fun nextDelay(): Long {
        if (failures > 0) return (60_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(15 * 60_000L)
        val newest = app.readings.newestTimestamp() ?: return LATE_POLL_MS
        val now = System.currentTimeMillis()
        if (now - newest > 15 * 60_000L) return LATE_POLL_MS
        val expected = newest + PERIOD_MS + 20_000L
        return if (expected > now) (expected - now).coerceAtLeast(15_000L) else LATE_POLL_MS.coerceAtMost(60_000L)
    }

    private suspend fun failed(message: String) {
        failures++
        DebugLog.log("server: $message")
        app.settings.setFollowResult(null, message)
        showError(message)
    }

    private fun showError(message: String) {
        app.status.value = app.status.value.copy(link = Link.Error, message = message)
    }

    companion object {
        private const val PERIOD_MS = 5 * 60_000L
        private const val LATE_POLL_MS = 2 * 60_000L
        private const val FIRST_SYNC_MS = 24 * 3_600_000L
    }
}
