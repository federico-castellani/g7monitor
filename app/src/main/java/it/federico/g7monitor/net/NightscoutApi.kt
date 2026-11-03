package it.federico.g7monitor.net

import it.federico.g7monitor.R
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal Nightscout uploader client (API v1), like xDrip's "Nightscout sync": sensor values as
 * `sgv` entries, meter results as "BG Check" treatments. HTTPS only, no redirects.
 *
 * Authentication: either the site's API_SECRET (sent hashed, as Nightscout expects) or an access
 * token from Nightscout's admin tools, with a role allowed to create entries and treatments.
 */
object NightscoutApi {
    class Unauthorized : IOException(str(R.string.ns_unauthorized))

    private const val DEVICE = "G7 Monitor"

    /** Access tokens look like "name-0123456789abcdef"; anything else is taken as the API_SECRET. */
    private val TOKEN = Regex("[a-z0-9_.]+-[0-9a-f]{16}")

    internal fun isToken(secret: String) = TOKEN.matches(secret.trim())

    internal fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Checks the address and that the secret allows writing; throws [Unauthorized] if not. */
    fun verify(base: String, secret: String) {
        val json = JSONObject(send("GET", "$base/api/v1/verifyauth", secret, null))
        val message = json.optJSONObject("message")
        if (message?.optBoolean("canWrite") != true) throw Unauthorized()
    }

    fun uploadEntries(base: String, secret: String, readings: List<GlucoseReading>) {
        send("POST", "$base/api/v1/entries", secret, entries(readings).toString())
    }

    fun uploadFingersticks(base: String, secret: String, list: List<Fingerstick>) {
        send("POST", "$base/api/v1/treatments", secret, treatments(list).toString())
    }

    internal fun entries(readings: List<GlucoseReading>): JSONArray = JSONArray().apply {
        for (r in readings) put(
            JSONObject()
                .put("type", "sgv")
                .put("sgv", r.mgdl)
                .put("date", r.timestamp)
                .put("dateString", iso(r.timestamp))
                .put("direction", direction(r.trend))
                .put("device", DEVICE)
        )
    }

    internal fun treatments(list: List<Fingerstick>): JSONArray = JSONArray().apply {
        for (f in list) put(
            JSONObject()
                .put("eventType", "BG Check")
                .put("glucose", f.mgdl)
                .put("glucoseType", "Finger")
                .put("units", "mg/dl")
                .put("created_at", iso(f.timestamp))
                .put("enteredBy", f.meter?.let { "$DEVICE ($it)" } ?: DEVICE)
        )
    }

    /** Nightscout's trend names, with the same thresholds as the app's arrows (mg/dL per minute). */
    internal fun direction(rate: Double?): String = when {
        rate == null || rate.isNaN() -> "NONE"
        rate >= 3 -> "DoubleUp"
        rate >= 2 -> "SingleUp"
        rate >= 1 -> "FortyFiveUp"
        rate > -1 -> "Flat"
        rate > -2 -> "FortyFiveDown"
        rate > -3 -> "SingleDown"
        else -> "DoubleDown"
    }

    internal fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private fun send(method: String, url: String, secret: String, body: String?): String {
        val secretValue = secret.trim()
        val full = if (isToken(secretValue)) url + (if ('?' in url) "&" else "?") + "token=$secretValue" else url
        val conn = URL(full).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            if (!isToken(secretValue)) conn.setRequestProperty("api-secret", sha1(secretValue))
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            if (status == 401 || status == 403) throw Unauthorized()
            if (status !in 200..299) throw ServerApi.HttpError(status, null, str(R.string.ns_error_code, status))
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
