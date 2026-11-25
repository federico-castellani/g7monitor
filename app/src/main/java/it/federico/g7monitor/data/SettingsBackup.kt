package it.federico.g7monitor.data

import it.federico.g7monitor.G7App
import it.federico.g7monitor.ble.G7Service
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Settings and alarms as a JSON file the user keeps where they like (Drive, Downloads…), to set
 * up a new phone in a moment. Never in it: server tokens, the Nightscout secret, the sensor's
 * authentication key or an imported certificate, nothing that gives access to the data or the
 * sensor. Server and Nightscout addresses are there only to fill in the forms again.
 */
object SettingsBackup {
    private const val FORMAT = "g7monitor-settings"

    class NotABackup : Exception()

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "g7monitor-settings-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))}.json"

    suspend fun export(app: G7App): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("app", app.updater.installedVersion)
        .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
        .put("settings", app.settings.backup())
        .put("alarms", app.alarmSettings.backup())
        .toString(2)

    /**
     * Restores what the file has; what it lacks stays as it is. A new pairing code makes the phone
     * look for that sensor. @return how many settings and alarms were restored
     */
    suspend fun import(app: G7App, text: String): Int {
        val o = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("format") == FORMAT } ?: throw NotABackup()
        val settings = o.optJSONObject("settings") ?: JSONObject()
        var n = app.settings.restore(settings)
        n += o.optJSONObject("alarms")?.let { app.alarmSettings.restore(it) } ?: 0
        val code = settings.optString("pairing_code")
        val s = app.settings.current()
        if (code.matches(Regex("\\d{4}|\\d{6}")) && code != s.pairingCode) {
            app.settings.setPairingCode(code)
            n++
            if (s.collectorEnabled && s.dataSource != DataSource.Server) G7Service.restart(app)
        }
        app.alarms.evaluate()
        return n
    }
}
