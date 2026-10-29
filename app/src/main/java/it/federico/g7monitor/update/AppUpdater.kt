package it.federico.g7monitor.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import it.federico.g7monitor.G7App
import it.federico.g7monitor.data.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A version published on GitHub. */
data class AppRelease(val version: String, val code: Int, val apkUrl: String, val size: Long, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    /** Android must first allow this app to install apps ("Installa app sconosciute"). */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    /** Android's install page is open: the user confirms there. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}

/**
 * Updates the app from the GitHub Releases of the project: checks the latest release (at most
 * every few hours, or on demand), downloads its APK straight into Android's package installer
 * and lets the system ask the user to confirm. Data and settings stay, as with any update.
 */
class AppUpdater(private val app: G7App) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val installedVersion: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    private val installedCode: Long = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode

    /** [force]: "Controlla ora"; otherwise only if the last check is older than [CHECK_EVERY_MS]. */
    suspend fun check(force: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            // Keep showing a release found earlier, without asking GitHub again.
            if (s == UpdateState.Idle) cachedRelease()?.let { _state.value = UpdateState.Available(it) }
            return
        }
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = withContext(Dispatchers.IO) { latestRelease() }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (release != null && release.code > installedCode) {
                prefs.edit().putString(KEY_RELEASE, release.toJson()).apply()
                UpdateState.Available(release)
            } else {
                prefs.edit().remove(KEY_RELEASE).apply()
                UpdateState.UpToDate
            }
        } catch (e: Exception) {
            DebugLog.log("aggiornamenti: ${e.javaClass.simpleName}: ${e.message}")
            if (force) UpdateState.Failed("Controllo non riuscito: verifica la connessione", null) else UpdateState.Idle
        }
    }

    /** Downloads [release] into the system installer, which then asks the user to confirm. */
    suspend fun install(release: AppRelease) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (release.size > 0) setSize(release.size)
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            withContext(Dispatchers.IO) {
                installer.openSession(sessionId).use { session ->
                    download(release, session)
                    val status = PendingIntent.getBroadcast(
                        app, sessionId, Intent(app, UpdateReceiver::class.java),
                        // Mutable: the installer adds the result to it.
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    session.commit(status.intentSender)
                }
            }
            _state.value = UpdateState.Installing(release)
            DebugLog.log("aggiornamento ${release.version} scaricato")
        } catch (e: Exception) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            DebugLog.log("aggiornamento non scaricato: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = UpdateState.Failed("Download non riuscito: riprova", release)
        }
    }

    /** Result from the system installer (see [UpdateReceiver]). */
    internal fun onInstallResult(context: Context, intent: Intent) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: cachedRelease()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            // The app is replaced and restarted by the system: nothing else to do.
            PackageInstaller.STATUS_SUCCESS -> prefs.edit().remove(KEY_RELEASE).apply()
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> {
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "L'app installata ha una firma diversa: disinstallala una volta e installa l'APK dalla pagina GitHub"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Spazio insufficiente sul telefono"
                    else -> "Installazione non riuscita"
                }
                DebugLog.log("aggiornamento non installato: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                _state.value = UpdateState.Failed(message, release)
            }
        }
    }

    /** Back from the "install unknown apps" setting, or the error dismissed. */
    fun reset() {
        _state.value = cachedRelease()?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }

    private fun download(release: AppRelease, session: PackageInstaller.Session) {
        val conn = open(release.apkUrl, accept = "application/octet-stream")
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                session.openWrite("G7Monitor.apk", 0, total.takeIf { it > 0 } ?: -1).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = 0f
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val p = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else 0f
                        if (p - shown >= 0.02f) {
                            shown = p
                            _state.value = UpdateState.Downloading(release, p)
                        }
                    }
                    session.fsync(out)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun latestRelease(): AppRelease? {
        val conn = open(LATEST_URL, accept = "application/vnd.github+json")
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null // nothing published yet
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val code = versionCode(version) ?: return null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { APK_NAME.matches(it.getString("name")) } ?: return null
            return AppRelease(version, code, apk.getString("browser_download_url"), apk.optLong("size"), json.optString("body"))
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, accept: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true // GitHub serves the files from another https host
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", "G7Monitor/$installedVersion")
    }

    private fun cachedRelease(): AppRelease? = prefs.getString(KEY_RELEASE, null)
        ?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
        ?.takeIf { it.code > installedCode }

    private fun AppRelease.toJson() = JSONObject()
        .put("version", version).put("code", code).put("url", apkUrl).put("size", size).put("notes", notes).toString()

    private fun fromJson(o: JSONObject) =
        AppRelease(o.getString("version"), o.getInt("code"), o.getString("url"), o.getLong("size"), o.optString("notes"))

    companion object {
        const val RELEASES_PAGE = "https://github.com/federico-castellani/g7monitor/releases/latest"
        private const val LATEST_URL = "https://api.github.com/repos/federico-castellani/g7monitor/releases/latest"
        /** Phone APK as named by .github/workflows/release.yml (not the watch one). */
        private val APK_NAME = Regex("""G7Monitor-\d+\.\d+\.\d+\.apk""")
        private const val CHECK_EVERY_MS = 6 * 3_600_000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_RELEASE = "release"

        /** Same rule as the Gradle build: 1.2.3 -> 10203. */
        fun versionCode(version: String): Int? = version.split(".").takeIf { it.size == 3 }
            ?.map { it.toIntOrNull() ?: return null }
            ?.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
    }
}

/** Receives the system installer's result for [AppUpdater.install]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as G7App).updater.onInstallResult(context, intent)
    }
}
