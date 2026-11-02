package it.federico.g7monitor.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the watch app by itself, without a PC: the latest GitHub release of the project holds
 * the watch APK (G7Monitor-Wear-X.Y.Z.apk), which goes straight into Android's package installer;
 * the system asks for a tap to confirm. Only the very first install needs ADB.
 *
 * The check runs when the app is opened (at most every few hours) and when the phone, updated,
 * tells the watch its new version (see [DataLayerListenerService]).
 */
object WatchUpdater {
    data class Release(val version: String, val code: Int, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private const val TAG = "WatchUpdater"
    private const val LATEST = "https://api.github.com/repos/federico-castellani/g7monitor/releases/latest"
    private val APK = Regex("""G7Monitor-Wear-\d+\.\d+\.\d+\.apk""")
    private const val CHECK_EVERY_MS = 6 * 3_600_000L
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 41
    const val EXTRA_UPDATE = "it.federico.g7monitor.wear.UPDATE"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun installedCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Same rule as the Gradle build: 1.2.3 -> 10203. */
    fun versionCode(version: String): Int? = version.removePrefix("v").split(".").takeIf { it.size == 3 }
        ?.map { it.toIntOrNull() ?: return null }
        ?.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

    /** [force]: always ask GitHub (opened from the notification); otherwise at most every few hours. */
    suspend fun check(context: Context, force: Boolean) {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("last_check", 0) < CHECK_EVERY_MS) return
        _state.value = State.Checking
        _state.value = try {
            val r = withContext(Dispatchers.IO) { latest() }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (r != null && r.code > installedCode(context)) State.Available(r) else State.UpToDate
        } catch (e: Exception) {
            Log.w(TAG, "check: $e")
            if (force) State.Failed(context.getString(R.string.w_check_failed)) else State.Idle
        }
    }

    /** Downloads the APK into the system installer, which then asks to confirm on the watch. */
    suspend fun install(context: Context, release: Release) {
        _state.value = State.Downloading(release, 0f)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (release.size > 0) setSize(release.size)
        }
        var id = -1
        try {
            id = installer.createSession(params)
            withContext(Dispatchers.IO) {
                installer.openSession(id).use { session ->
                    val conn = open(release.apkUrl, "application/octet-stream")
                    try {
                        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
                        conn.inputStream.use { input ->
                            session.openWrite("G7Monitor-Wear.apk", 0, total.takeIf { it > 0 } ?: -1).use { out ->
                                val buf = ByteArray(32 * 1024)
                                var done = 0L
                                var shown = 0f
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val p = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else 0f
                                    if (p - shown >= 0.05f) { shown = p; _state.value = State.Downloading(release, p) }
                                }
                                session.fsync(out)
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    val status = PendingIntent.getBroadcast(
                        context, id, Intent(context, WatchUpdateReceiver::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    session.commit(status.intentSender)
                }
            }
            _state.value = State.Installing(release)
        } catch (e: Exception) {
            Log.w(TAG, "install: $e")
            if (id != -1) runCatching { installer.abandonSession(id) }
            _state.value = State.Failed(context.getString(R.string.w_download_failed))
        }
    }

    internal fun onResult(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Idle
            else -> {
                Log.w(TAG, "install status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                _state.value = State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                        context.getString(R.string.w_signature)
                    else context.getString(R.string.w_install_failed),
                )
            }
        }
    }

    /** The phone has a newer version: a notification on the watch, tapping it opens the update. */
    fun notifyPhoneVersion(context: Context, phoneVersion: String) {
        val code = versionCode(phoneVersion) ?: return
        if (code <= installedCode(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.w_channel_updates), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra(EXTRA_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.w_update_title))
            .setContentText(context.getString(R.string.w_update_text, phoneVersion))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) } // no permission: the app shows it when opened
    }

    fun cancelNotification(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    private fun latest(): Release? {
        val conn = open(LATEST, "application/vnd.github+json")
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val code = versionCode(version) ?: return null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { APK.matches(it.getString("name")) } ?: return null
            return Release(version, code, apk.getString("browser_download_url"), apk.optLong("size"))
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, accept: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000 // the watch often goes through the phone's Bluetooth: slower
        instanceFollowRedirects = true
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", "G7Monitor-Wear")
    }
}

/** Result of the system installer for [WatchUpdater.install]. */
class WatchUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WatchUpdater.onResult(context, intent)
}
