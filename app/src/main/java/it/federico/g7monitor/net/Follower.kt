package it.federico.g7monitor.net

import it.federico.g7monitor.G7App
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.data.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Follower mode: receives the readings from the server into the local store, so Home,
 * chart, alarms, watch and notification work exactly as with the sensor.
 *
 * Live: one open connection on which the server pushes each reading about a second after the
 * sensor phone uploads it (as xDrip's sync). The line is silent between readings except for a
 * ping every 4.5 minutes of inactivity, so the radio wakes about as often as with polling.
 * If the connection drops it reconnects with a growing pause, getting what was missed; with an
 * older server without the live stream it falls back to one request per expected reading.
 */
class Follower(private val app: G7App) {
    private val syncMutex = Mutex()
    private val liveMutex = Mutex()
    private var failures = 0
    /** Server without the live stream (HTTP 404): poll instead. */
    @Volatile private var legacy = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var lastActivity = 0L
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ITALY)

    // ------------------------------------------------------------------ live

    /** Keeps the live connection until cancelled or until the phone is unlinked from the server. */
    suspend fun follow() = liveMutex.withLock {
        while (true) {
            val s = app.settings.current()
            if (s.dataSource != DataSource.Server || !s.followConnected) return@withLock
            if (legacy) {
                sync()
                delay(nextDelay())
                continue
            }
            try {
                stream(s.followUrl, s.followToken, since())
                delay(2_000) // closed by the server (restart, deploy): reconnect at once
            } catch (e: ServerApi.HttpError) {
                when (e.status) {
                    401 -> {
                        revoked()
                        return@withLock
                    }
                    404 -> {
                        DebugLog.log("server: ricezione in tempo reale non disponibile, uso le richieste periodiche")
                        legacy = true
                    }
                    else -> {
                        failed(e.message ?: "Errore del server")
                        delay(retryDelay())
                    }
                }
            } catch (e: SSLException) {
                failed("Certificato HTTPS del server non valido")
                delay(retryDelay())
            } catch (e: IOException) {
                failed("Collegamento al server interrotto")
                delay(retryDelay())
            }
        }
    }

    private suspend fun stream(url: String, token: String, since: Long) = coroutineScope {
        val conn = withContext(Dispatchers.IO) { ServerApi.openStream(url, token, since, fingerSince()) }
        connection = conn
        // A blocking socket read ignores coroutine cancellation: closing the connection ends it.
        val closer = launch { try { awaitCancellation() } finally { conn.disconnect() } }
        try {
            failures = 0
            lastActivity = System.currentTimeMillis()
            DebugLog.log("server: collegato in tempo reale")
            showStatus("Dal server · in tempo reale")
            withContext(Dispatchers.IO) {
                ServerApi.readStream(
                    conn,
                    onActivity = { lastActivity = System.currentTimeMillis() },
                    onFingersticks = { list -> receivedFingersticks(list) },
                ) { rows -> received(rows) }
            }
        } finally {
            connection = null
            closer.cancel()
        }
    }

    /**
     * Doze can freeze the socket's own timeout, leaving a dead link open: the service calls this
     * from an alarm and the connection is reopened if nothing (not even a ping) arrived for too long.
     */
    fun checkAlive() {
        val conn = connection ?: return
        if (System.currentTimeMillis() - lastActivity > DEAD_LINK_MS) {
            DebugLog.log("server: nessun segnale da ${(System.currentTimeMillis() - lastActivity) / 60_000} min, mi ricollego")
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ one-off ("Aggiorna ora", fallback)

    /** @return true when the server answered. */
    suspend fun sync(): Boolean = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val s = app.settings.current()
            if (s.dataSource != DataSource.Server || !s.followConnected) return@withContext false
            try {
                val fetched = ServerApi.fetchSince(s.followUrl, s.followToken, since(), fingerSince())
                receivedFingersticks(fetched.fingersticks)
                received(fetched.readings, always = true)
                failures = 0
                true
            } catch (e: ServerApi.HttpError) {
                if (e.status == 401) revoked() else failed(e.message ?: "Errore del server")
                false
            } catch (e: SSLException) {
                failed("Certificato HTTPS del server non valido")
                false
            } catch (e: IOException) {
                failed("Server non raggiungibile")
                false
            }
        }
    }

    /**
     * Polling only (older server): right after the next expected reading (+20 s), every 2 minutes
     * while the data is late, 1, 2, 4… up to 15 minutes after errors.
     */
    suspend fun nextDelay(): Long {
        if (failures > 0) return (60_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(15 * 60_000L)
        val newest = app.readings.newestTimestamp() ?: return LATE_POLL_MS
        val now = System.currentTimeMillis()
        if (now - newest > 15 * 60_000L) return LATE_POLL_MS
        val expected = newest + PERIOD_MS + 20_000L
        return if (expected > now) (expected - now).coerceAtLeast(15_000L) else LATE_POLL_MS.coerceAtMost(60_000L)
    }

    // ------------------------------------------------------------------ shared

    /** From the newest local reading; never more than a day back on the first sync. */
    private suspend fun since(): Long =
        maxOf(app.readings.newestTimestamp() ?: 0L, System.currentTimeMillis() - FIRST_SYNC_MS)

    /** Meter results: from the newest one already here (they are rare and may be older than the last reading). */
    private suspend fun fingerSince(): Long =
        maxOf(app.readings.newestFingerstick() ?: 0L, System.currentTimeMillis() - FIRST_SYNC_MS)

    private suspend fun receivedFingersticks(list: List<Fingerstick>) {
        val added = app.readings.insertFingersticks(list, fromServer = true)
        if (added > 0) DebugLog.log("server: ricevute $added misurazioni del glucometro")
    }

    private suspend fun received(rows: List<GlucoseReading>, always: Boolean = false) {
        val added = app.readings.insertAll(rows, fromServer = true)
        if (added == 0 && !always) return
        val now = System.currentTimeMillis()
        app.settings.setFollowResult(now, null)
        if (added > 0) DebugLog.log("server: ricevute $added letture")
        showStatus("Dal server · aggiornato alle ${timeFmt.format(Date(now))}")
    }

    private suspend fun showStatus(message: String) {
        val s = app.settings.current()
        app.status.value = app.status.value.copy(
            link = Link.Waiting, lastContact = System.currentTimeMillis(),
            message = message, deviceName = s.followUrl.removePrefix("https://"),
        )
    }

    private suspend fun revoked() {
        DebugLog.log("server: token revocato")
        app.settings.disconnectFollow("Il sito ha scollegato questo telefono: collegalo di nuovo")
        app.status.value = app.status.value.copy(link = Link.Error, message = "Telefono scollegato dal server")
    }

    private suspend fun failed(message: String) {
        failures++
        DebugLog.log("server: $message")
        app.settings.setFollowResult(null, message)
        app.status.value = app.status.value.copy(link = Link.Error, message = message)
    }

    /** 5 s, 10 s, 20 s… up to 5 minutes. */
    private fun retryDelay(): Long = (5_000L shl (failures - 1).coerceIn(0, 6)).coerceAtMost(5 * 60_000L)

    companion object {
        private const val PERIOD_MS = 5 * 60_000L
        private const val LATE_POLL_MS = 2 * 60_000L
        private const val FIRST_SYNC_MS = 24 * 3_600_000L
        /** Server ping every 4.5 min: nothing for 9 minutes means the link is dead. */
        private const val DEAD_LINK_MS = 9 * 60_000L
    }
}
