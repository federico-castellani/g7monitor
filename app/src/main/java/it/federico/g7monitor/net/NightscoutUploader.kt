package it.federico.g7monitor.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.UploadTarget
import it.federico.g7monitor.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Sends the sensor readings and meter results to a Nightscout site, next to (or instead of) the
 * G7 Monitor server. Like [Uploader]: no timers of its own, it runs right after a reading is
 * stored; errors back off up to 1 hour.
 */
class NightscoutUploader(private val app: G7App) {
    private val mutex = Mutex()
    private var failures = 0
    private var retryAt = 0L

    /**
     * Checks the site and the secret, then saves them and sends the last 24 hours.
     * @return null when connected, otherwise what went wrong
     */
    suspend fun connect(input: String, secret: String): String? = withContext(Dispatchers.IO) {
        val url = ServerApi.normalizeUrl(input) ?: return@withContext str(R.string.vm_bad_address)
        try {
            NightscoutApi.verify(url, secret.trim())
        } catch (e: Exception) {
            return@withContext describe(e)
        }
        app.settings.setNightscout(url, secret.trim())
        app.settings.setNightscoutPaused(false)
        app.readings.resetNightscout(System.currentTimeMillis() - 24 * 3_600_000L)
        DebugLog.log(str(R.string.ns_connected_log, url.removePrefix("https://")))
        maybeUpload(force = true)
        null
    }

    /** @param force ignore the back-off ("Invia ora", just connected). */
    suspend fun maybeUpload(force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) { run(force) }
    }

    private suspend fun run(force: Boolean) {
        val s = app.settings.current()
        if (s.dataSource != DataSource.Sensor || !s.nightscoutConnected || s.nightscoutPaused) return
        val now = System.currentTimeMillis()
        if (!force && now < retryAt) return
        if (!networkAvailable()) {
            if (force) app.settings.setNightscoutResult(null, str(R.string.upload_no_connection))
            return
        }
        var sent = 0
        try {
            while (true) {
                val batch = app.readings.pendingUpload(BATCH, UploadTarget.Nightscout)
                if (batch.isEmpty()) break
                NightscoutApi.uploadEntries(s.nightscoutUrl, s.nightscoutSecret, batch)
                app.readings.markUploaded(batch, UploadTarget.Nightscout)
                sent += batch.size
                if (batch.size < BATCH) break
            }
            while (true) {
                val batch = app.readings.pendingFingersticks(BATCH, UploadTarget.Nightscout)
                if (batch.isEmpty()) break
                NightscoutApi.uploadFingersticks(s.nightscoutUrl, s.nightscoutSecret, batch)
                app.readings.markFingersticksUploaded(batch, UploadTarget.Nightscout)
                sent += batch.size
                if (batch.size < BATCH) break
            }
            failures = 0
            retryAt = 0
            if (sent > 0 || force || s.nightscoutError != null) app.settings.setNightscoutResult(now, null)
            if (sent > 0) DebugLog.log(str(R.string.ns_sent_log, sent))
        } catch (e: IOException) {
            failures++
            val wait = (5 * 60_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(60 * 60_000L)
            retryAt = now + wait
            val message = describe(e)
            DebugLog.log(str(R.string.ns_retry_log, message, (wait / 60_000).toInt()))
            app.settings.setNightscoutResult(null, message)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is NightscoutApi.Unauthorized -> e.message!!
        is ServerApi.HttpError -> e.message ?: str(R.string.net_server_error)
        is SSLException -> str(R.string.net_bad_certificate)
        is IOException -> str(R.string.ns_unreachable)
        else -> str(R.string.ns_not_nightscout)
    }

    private fun networkAvailable(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val BATCH = 500
    }
}
