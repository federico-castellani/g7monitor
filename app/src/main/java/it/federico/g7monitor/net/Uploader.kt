package it.federico.g7monitor.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import it.federico.g7monitor.G7App
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Fingerstick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Sends new readings to the server, designed for battery and data:
 * - no timers or wake-ups of its own: it runs right after a reading is stored, while
 *   the phone is already awake for the Bluetooth exchange
 * - everything pending goes in one compact (gzipped when large) request
 * - optional longer interval / Wi-Fi only; offline or server errors back off up to 1 hour
 */
class Uploader(private val app: G7App) {
    private val mutex = Mutex()
    private var failures = 0
    private var retryAt = 0L

    /** @param force ignore interval and back-off ("Invia ora", just connected). */
    suspend fun maybeUpload(force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) { run(force) }
    }

    private suspend fun run(force: Boolean) {
        val s = app.settings.current()
        if (s.dataSource != DataSource.Sensor || !s.serverConnected) return
        val now = System.currentTimeMillis()
        // A meter result is rare and wanted at once: it skips the upload interval.
        val urgent = !oldServerLogged && app.readings.pendingFingersticks(1).isNotEmpty()
        if (!force && !urgent) {
            if (now < retryAt) return
            // 1 minute of tolerance: readings arrive every ~5 minutes, not exactly.
            if (now - (s.lastUploadAt ?: 0L) < s.uploadIntervalMin * 60_000L - 60_000L) return
        }
        if (!networkAvailable(s.uploadWifiOnly)) {
            if (force) app.settings.setUploadResult(null, if (s.uploadWifiOnly) "In attesa del Wi-Fi" else "Nessuna connessione")
            return
        }

        var sent = 0
        try {
            while (true) {
                val batch = app.readings.pendingUpload(BATCH)
                if (batch.isEmpty()) break
                ServerApi.upload(s.serverUrl, s.serverToken, batch)
                app.readings.markUploaded(batch)
                sent += batch.size
                if (batch.size < BATCH) break
            }
            sent += uploadFingersticks(s.serverUrl, s.serverToken)
            failures = 0
            retryAt = 0
            if (sent > 0 || force || s.uploadError != null) app.settings.setUploadResult(now, null)
            if (sent > 0) DebugLog.log("server: inviate $sent letture")
        } catch (e: ServerApi.HttpError) {
            if (e.status == 401) {
                DebugLog.log("server: token revocato, telefono scollegato")
                app.settings.disconnectServer("Il sito ha scollegato questo telefono: collegalo di nuovo")
            } else {
                failed(now, e.message ?: "Errore del server")
            }
        } catch (e: SSLException) {
            failed(now, "Certificato HTTPS del server non valido")
        } catch (e: IOException) {
            failed(now, "Server non raggiungibile")
        }
    }

    /** @return how many were sent (0 with a server too old to know meter results: they wait for its update). */
    private suspend fun uploadFingersticks(url: String, token: String): Int {
        var sent = 0
        while (true) {
            val batch = app.readings.pendingFingersticks(500)
            if (batch.isEmpty()) return sent
            try {
                ServerApi.uploadFingersticks(url, token, batch)
            } catch (e: ServerApi.HttpError) {
                when (e.status) {
                    404 -> {
                        if (!oldServerLogged) DebugLog.log("server: non conosce ancora le misurazioni del glucometro, aggiornalo")
                        oldServerLogged = true
                        return sent
                    }
                    // A result the server refuses (e.g. a meter clock far off) must not hold back the others.
                    400 -> return sent + uploadOneByOne(url, token, batch)
                    else -> throw e
                }
            }
            app.readings.markFingersticksUploaded(batch)
            sent += batch.size
            if (batch.size < 500) return sent
        }
    }

    /** After a refused batch: sends each result alone, and drops the ones the server refuses. */
    private suspend fun uploadOneByOne(url: String, token: String, batch: List<Fingerstick>): Int {
        var sent = 0
        for (f in batch) {
            try {
                ServerApi.uploadFingersticks(url, token, listOf(f))
                sent++
            } catch (e: ServerApi.HttpError) {
                if (e.status != 400) throw e
                DebugLog.log("server: misurazione del glucometro rifiutata (${f.mgdl} mg/dL, ${e.message}): non la invio più")
            }
            app.readings.markFingersticksUploaded(listOf(f))
        }
        return sent
    }

    private var oldServerLogged = false

    private suspend fun failed(now: Long, message: String) {
        failures++
        val wait = (5 * 60_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(60 * 60_000L)
        retryAt = now + wait
        DebugLog.log("server: $message (riprovo tra ${wait / 60_000} min)")
        app.settings.setUploadResult(null, message)
    }

    private fun networkAvailable(wifiOnly: Boolean): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (!wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    companion object {
        private const val BATCH = 2000
    }
}
