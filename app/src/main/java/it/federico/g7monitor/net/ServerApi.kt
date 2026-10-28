package it.federico.g7monitor.net

import it.federico.g7monitor.data.GlucoseReading
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * Client for the G7 Monitor server. Plain HttpURLConnection (no extra libraries);
 * HTTPS only, no redirects, so the token is never sent anywhere else.
 */
object ServerApi {
    class HttpError(val status: Int, val code: String?, message: String) : IOException(message)

    data class Paired(val token: String, val username: String)

    /** Below this size gzip saves less than its own header. */
    private const val GZIP_MIN = 1024

    /** Adds https:// when missing; refuses http and anything that is not a plain base address. */
    fun normalizeUrl(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        val withScheme = if ("://" in s) s else "https://$s"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        return withScheme
    }

    /** First step of the setup: is there a G7 Monitor server at this address? (no credentials sent) */
    fun checkServer(base: String) {
        val json = runCatching { JSONObject(send("GET", "$base/api/v1/info", null, null)) }.getOrElse { e ->
            if (e is HttpError && e.status == 404) throw NotG7Server()
            if (e is org.json.JSONException) throw NotG7Server()
            throw e
        }
        if (json.optString("service") != "g7monitor") throw NotG7Server()
    }

    class NotG7Server : IOException("A questo indirizzo non c'è un server G7 Monitor")

    /**
     * Sends the credentials once; only the returned token is stored on the phone.
     * @param reader true for a follower phone (read-only token), false to upload from the sensor.
     */
    fun pair(base: String, username: String, password: String, totp: String?, deviceName: String, reader: Boolean): Paired {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("name", deviceName.take(60))
            .put("kind", if (reader) "reader" else "uploader")
        if (!totp.isNullOrBlank()) body.put("totp", totp)
        val json = JSONObject(post("$base/api/v1/devices", null, body.toString().toByteArray()))
        return Paired(json.getString("token"), json.getString("username"))
    }

    /**
     * Follower: readings newer than [sinceMs], oldest first. Asked once per expected reading,
     * so the answer is usually a single row (well under 1 KB with headers).
     */
    fun fetchSince(base: String, token: String, sinceMs: Long): List<GlucoseReading> {
        val json = JSONObject(send("GET", "$base/api/v1/readings?since=${sinceMs / 1000}", token, null))
        return decode(json.getJSONArray("r"))
    }

    /** The server writes at least every 270 s (ping); no bytes for longer than this means a dead link. */
    private const val STREAM_READ_TIMEOUT_MS = 330_000

    /**
     * Follower, live: opens the server's event stream (readings newer than [sinceMs] first, then each
     * new one as it is uploaded). Blocking. Close the returned connection to stop reading.
     */
    fun openStream(base: String, token: String, sinceMs: Long): HttpURLConnection {
        val conn = URL("$base/api/v1/readings/stream?since=${sinceMs / 1000}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = STREAM_READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Authorization", "Bearer $token")
        val status = conn.responseCode
        if (status != 200) {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val err = runCatching { JSONObject(text) }.getOrNull()
            throw HttpError(status, err?.optString("error"), err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Errore del server ($status)")
        }
        return conn
    }

    /**
     * Reads the stream until it ends or [conn] is closed (then it throws an IOException).
     * @param onActivity every line received, pings included (link alive)
     */
    suspend fun readStream(conn: HttpURLConnection, onActivity: () -> Unit, onRows: suspend (List<GlucoseReading>) -> Unit) {
        conn.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: return
                onActivity()
                if (line.startsWith("data:")) onRows(decode(org.json.JSONArray(line.substring(5).trim())))
            }
        }
    }

    /** Server rows [unix seconds, mg/dL, trend×10 | null, source] → readings. */
    internal fun decode(rows: org.json.JSONArray): List<GlucoseReading> = List(rows.length()) { i ->
        val row = rows.getJSONArray(i)
        GlucoseReading(
            timestamp = row.getLong(0) * 1000,
            mgdl = row.getInt(1),
            trend = if (row.isNull(2)) null else row.getInt(2) / 10.0,
            source = if (row.getInt(3) == 0) GlucoseReading.Source.Live else GlucoseReading.Source.Backfill,
        )
    }

    /** Revokes this phone's token on the server (best effort when disconnecting). */
    fun unpair(base: String, token: String) {
        send("DELETE", "$base/api/v1/devices/self", token, null)
    }

    /**
     * Compact format: {"b": first time, "r": [[seconds since previous, mg/dL, trend×10, source], …]}.
     * About 15 bytes per reading; a full day (288 readings) is ~4 KB, ~1 KB gzipped.
     * @return how many readings the server did not have yet.
     */
    fun upload(base: String, token: String, readings: List<GlucoseReading>): Int =
        JSONObject(post("$base/api/v1/readings", token, encode(readings).toByteArray())).optInt("n")

    /** Readings must be sorted by time (as [it.federico.g7monitor.data.ReadingStore.pendingUpload] returns them). */
    internal fun encode(readings: List<GlucoseReading>): String {
        val first = readings.first().timestamp / 1000
        val sb = StringBuilder(readings.size * 16 + 32).append("{\"b\":").append(first).append(",\"r\":[")
        var prev = first
        readings.forEachIndexed { i, r ->
            val ts = r.timestamp / 1000
            if (i > 0) sb.append(',')
            sb.append('[').append(ts - prev).append(',').append(r.mgdl).append(',')
            val trend = r.trend?.takeIf { it.isFinite() }
            if (trend == null) sb.append("null") else sb.append((trend * 10).roundToInt().coerceIn(-250, 250))
            sb.append(',').append(if (r.source == GlucoseReading.Source.Live) 0 else 1).append(']')
            prev = ts
        }
        return sb.append("]}").toString()
    }

    private fun post(url: String, token: String?, body: ByteArray): String = send("POST", url, token, body)

    private fun send(method: String, url: String, token: String?, body: ByteArray?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            val payload = if (body.size >= GZIP_MIN) gzip(body).also { conn.setRequestProperty("Content-Encoding", "gzip") } else body
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
        }
        val status = conn.responseCode
        val text = (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (status !in 200..299) {
            val err = runCatching { JSONObject(text) }.getOrNull()
            val message = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Errore del server ($status)"
            throw HttpError(status, err?.optString("error"), message)
        }
        return text
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 3 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
